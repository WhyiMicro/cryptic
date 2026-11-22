package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import imicro.cryptic.puzzle.water.WaterBoard
import imicro.cryptic.puzzle.water.WaterFlip
import imicro.cryptic.puzzle.water.WaterLever
import imicro.cryptic.puzzle.water.WaterPlan
import imicro.cryptic.puzzle.water.WaterPlanner
import imicro.cryptic.puzzle.water.WaterSim
import imicro.cryptic.puzzle.water.WaterTracker
import imicro.cryptic.skyblock.ServerStats
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * The Water Board's dynamic solver: reads the board from the room's blocks, keeps a simulation of
 * it in step with the server ([WaterTracker]), and plans lever pulls from it ([WaterPlanner]) in
 * the background, again whenever what happens isn't what the plan said. Ideas from ADM's Dynamic
 * Water Board; the simulation, tracking and search are Cryptic's own.
 *
 * Until water is moving, nothing on the board changes, so a plan made then is timed from its first
 * pull: that pull shows "PULL" and the rest how long after it. Once water runs, every pull counts
 * down to the tick the server should take the click on, less your ping.
 */
object WaterDynamic {
	private const val PUZZLE = "Water Board"

	private val LEVER_BLOCKS: Map<Block, WaterLever> by lazy {
		mapOf(
			Blocks.COAL_BLOCK to WaterLever.COAL,
			Blocks.GOLD_BLOCK to WaterLever.GOLD,
			Blocks.QUARTZ_BLOCK to WaterLever.QUARTZ,
			Blocks.DIAMOND_BLOCK to WaterLever.DIAMOND,
			Blocks.EMERALD_BLOCK to WaterLever.EMERALD,
			Blocks.TERRACOTTA to WaterLever.CLAY,
		)
	}

	/** Gate blocks at (15, 56, z), in outlet order. */
	private val GATE_Z = intArrayOf(19, 18, 17, 16, 15)

	private val spacing = WaterPlanner.Spacing()
	private val planner = WaterPlanner(spacing)

	private var room: DungeonRoom? = null
	private var tracker: WaterTracker? = null

	/** Room coordinates of each lever, by world position. */
	private val leverAt = HashMap<BlockPos, WaterLever>()

	private var plan: WaterPlan? = null

	/** The plan is only timed from its first pull: no water is moving yet. */
	private var floating = false

	private var job: CompletableFuture<WaterPlan>? = null
	private var jobVersion = -1
	private var jobFloating = false
	private var cancelJob = false

	/** Something happened the plan didn't expect: plan again once nothing is running. */
	private var replanWanted = false

	private var lastCheck = 0
	private var lastStatus: String? = null
	private val dinged = HashSet<WaterFlip>()

	private val active: Boolean
		get() = PuzzleSolver.module.enabled && PuzzleSolver.waterEnabled.value && PuzzleSolver.waterDynamic.value

	/** Whether block packets are worth reading: the board is being followed. */
	@JvmStatic
	fun isWatching(): Boolean = tracker != null

	// ---- Packets -----------------------------------------------------------------------------------

	@JvmStatic
	fun onPing() {
		tracker?.onPing()
	}

	@JvmStatic
	fun onBlock(pos: BlockPos, old: BlockState, updated: BlockState) {
		val tracker = tracker ?: return
		val room = room ?: return
		leverAt[pos]?.let { lever ->
			if (old.block is LeverBlock && updated.block is LeverBlock && old.getValue(LeverBlock.POWERED) != updated.getValue(LeverBlock.POWERED)) {
				tracker.onLever(lever)
				onFlip(lever)
			}
			return
		}
		val relative = room.getRelativeCoords(pos) ?: return
		if (relative.z != WaterBoard.PLANE_Z) return
		val x = relative.x
		val y = relative.y
		if (x !in WaterBoard.X0 until WaterBoard.X0 + WaterBoard.WIDTH || y !in WaterBoard.Y0 until WaterBoard.Y0 + WaterBoard.HEIGHT) return
		tracker.onCell(WaterBoard.cell(x, y), shown(updated))
	}

	private fun shown(state: BlockState): Int = when {
		state.isAir -> WaterSim.AIR
		state.block == Blocks.WATER -> state.getValue(LiquidBlock.LEVEL)
		else -> WaterSim.SOLID
	}

	// ---- Reading the board -------------------------------------------------------------------------

	private class Read(val board: WaterBoard, val slotIn: BooleanArray, val water: IntArray, val closed: Int, val sourceOn: Boolean) {
		val dry: Boolean get() = !sourceOn && water.all { it < 0 }
	}

	private fun read(room: DungeonRoom, level: Level): Read? {
		fun at(x: Int, y: Int, z: Int): BlockState? = room.getRealCoords(BlockPos(x, y, z))?.let(level::getBlockState)

		val rows = ArrayList<String>()
		for (y in WaterBoard.ROW_TOP downTo WaterBoard.ROW_BOTTOM) {
			val row = StringBuilder()
			for (x in WaterBoard.ROW_X0..WaterBoard.ROW_X1) {
				val front = at(x, y, WaterBoard.PLANE_Z) ?: return null
				val inBoard = LEVER_BLOCKS[front.block]
				val parked = at(x, y, WaterBoard.PLANE_Z + 1)?.let { LEVER_BLOCKS[it.block] }
				row.append(
					when {
						inBoard != null -> inBoard.letter
						(front.isAir || front.block == Blocks.WATER) && parked != null -> parked.letter.lowercaseChar()
						front.isAir || front.block == Blocks.WATER -> '.'
						else -> '#'
					},
				)
			}
			rows += row.toString()
		}

		val origin = room.getRealCoords(BlockPos(15, 60, WaterBoard.PLANE_Z)) ?: return null
		val plusX = room.getRealCoords(BlockPos(16, 60, WaterBoard.PLANE_Z)) ?: return null
		val facing = when {
			plusX.x > origin.x -> WaterBoard.Facing.EAST
			plusX.x < origin.x -> WaterBoard.Facing.WEST
			plusX.z > origin.z -> WaterBoard.Facing.SOUTH
			else -> WaterBoard.Facing.NORTH
		}
		val (board, slotIn) = WaterBoard.parse(rows, facing)

		val water = IntArray(board.size) { -1 }
		for (cell in 0 until board.size) {
			if (!board.open[cell] && board.slotAt(cell) < 0) continue
			val state = at(WaterBoard.x(cell), WaterBoard.y(cell), WaterBoard.PLANE_Z) ?: continue
			if (state.block == Blocks.WATER) water[cell] = state.getValue(LiquidBlock.LEVEL)
		}
		val sourceOn = at(15, WaterBoard.SOURCE_Y, WaterBoard.PLANE_Z)?.block == Blocks.WATER
		var closed = 0
		GATE_Z.forEachIndexed { outlet, z -> if (at(15, 56, z)?.isAir == false) closed = closed or (1 shl outlet) }
		return Read(board, slotIn, water, closed, sourceOn)
	}

	/** Starts following the board in [room] from what it shows now. */
	private fun start(room: DungeonRoom, level: Level) {
		val read = read(room, level) ?: return
		val sim = if (read.dry) WaterSim.fresh(read.board, read.slotIn, read.closed)
		else WaterSim.observed(read.board, read.slotIn, read.closed, read.water, read.sourceOn, 0)
		this.room = room
		tracker = WaterTracker(sim)
		leverAt.clear()
		for (lever in WaterLever.entries) room.getRealCoords(BlockPos(lever.x, lever.y, lever.z))?.let { leverAt[it] = lever }
		plan = null
		replanWanted = true
	}

	// ---- Each tick ---------------------------------------------------------------------------------

	fun tick() {
		if (!active) {
			if (tracker != null) forget()
			return
		}
		val level = Minecraft.getInstance().level ?: return
		val inside = PuzzleRooms.named(PUZZLE)
		val room = room
		if (room == null || (inside != null && inside !== room)) {
			if (inside != null) start(inside, level)
			return
		}
		val tracker = tracker ?: return

		if (tracker.needsRebuild) resync(room, level, tracker)
		// A quiet board can be compared outright: anything different means the copy went wrong.
		if (tracker.now - lastCheck >= CHECK_EVERY && tracker.now - tracker.lastActivity > QUIET_TICKS) {
			lastCheck = tracker.now
			val read = read(room, level)
			if (read != null && (0 until read.board.size).any { cell -> read.board.open[cell] && read.board.slotAt(cell) < 0 && tracker.sim.shown(cell).let { if (it >= 0) it else -1 } != read.water[cell] }) {
				resync(room, level, tracker)
			}
		}

		collectPlan(tracker)
		checkPlan(tracker)
		if (replanWanted && job == null) startPlan(room, level, tracker)
	}

	private fun resync(room: DungeonRoom, level: Level, tracker: WaterTracker) {
		val read = read(room, level) ?: return
		tracker.resync(read.water, read.slotIn, read.closed, read.sourceOn)
		replanWanted = true
	}

	// ---- Planning ----------------------------------------------------------------------------------

	private fun pingTicks(): Int = ServerStats.ping.let { if (it <= 0) 0 else (it + 25) / 50 }

	private fun startPlan(room: DungeonRoom, level: Level, tracker: WaterTracker) {
		replanWanted = false
		val base = tracker.sim.copy()
		if (base.closed == 0 && base.quiet) {
			plan = null
			return
		}
		val float = base.quiet
		val budget = if (plan == null) FIRST_BUDGET_MS else REPLAN_BUDGET_MS
		val earliest = IntArray(WaterLever.entries.size) { base.time + 1 }
		if (!float) {
			val reaction = (PuzzleSolver.waterReaction.value / 50.0).toInt()
			val soonest = base.time + pingTicks() + reaction + (budget / 50).toInt()
			val player = Minecraft.getInstance().player
			val at = player?.let { room.getRelativeCoords(it.blockPosition()) }
			for (lever in WaterLever.entries) {
				val walk = if (at != null) spacing.from(at.x + 0.5, at.z + 0.5, lever) else 0
				earliest[lever.ordinal] = soonest + walk
			}
		}
		val seeds = ArrayList<List<WaterFlip>>()
		plan?.let { old -> seeds += old.flips.filter { it.tick > base.time } }
		if (float) {
			WaterSolver.sheetAnswer(room, level, base.closed)?.let { seeds += WaterPlanner.sheetSeed(it, base.time + 1) }
		}
		val goal = WaterPlanner.Goal.entries.getOrElse(PuzzleSolver.waterGoal.selectedIndex) { WaterPlanner.Goal.SAFEST }
		val request = WaterPlanner.Request(base, earliest, goal, seeds, budget)
		cancelJob = false
		jobVersion = tracker.version
		jobFloating = float
		job = CompletableFuture.supplyAsync { planner.plan(request) { cancelJob } }
	}

	private fun collectPlan(tracker: WaterTracker) {
		val running = job ?: return
		if (!running.isDone) return
		job = null
		val found = runCatching { running.get() }.getOrNull() ?: return
		if (cancelJob) return
		plan = found
		floating = jobFloating
		dinged.clear()
		if (tracker.version != jobVersion) validate(tracker)
		lastStatus = if (found.solvedAt == WaterSim.NONE) "no solve found" else null
	}

	/** Plays the rest of the plan on the board as it is now; plans again if it no longer solves in time. */
	private fun validate(tracker: WaterTracker) {
		val current = plan ?: return
		if (floating) return
		val sim = tracker.sim.copy()
		val flips = current.flips.filter { it.tick > sim.time }.groupBy { it.tick }
		val last = flips.keys.maxOrNull() ?: sim.time
		val end = sim.time + WaterPlanner.HORIZON
		while (sim.time < end && sim.solvedAt == WaterSim.NONE) {
			sim.step(flips[sim.time + 1].orEmpty().fold(0) { bits, flip -> bits or flip.lever.bit })
			if (sim.time > last && sim.settled) break
		}
		if (sim.solvedAt == WaterSim.NONE || current.solvedAt == WaterSim.NONE || sim.solvedAt > current.solvedAt + LATE_SOLVE) {
			replanWanted = true
		}
	}

	/** A lever flip seen: crossed off the plan if it's one of its pulls, or the plan is redone. */
	private fun onFlip(lever: WaterLever) {
		val tracker = tracker ?: return
		cancelRunning()
		val current = plan
		if (current == null) {
			replanWanted = true
			return
		}
		if (floating) {
			val first = current.flips.firstOrNull()
			if (first == null || first.lever != lever) {
				replanWanted = true
				return
			}
			// Timed from now on, if water is moving; still floating otherwise.
			plan = current.shifted(tracker.now - first.tick).let { it.without(it.flips.first()) }
			floating = tracker.sim.quiet
			if (!floating) validate(tracker)
			return
		}
		val match = current.flips.filter { it.lever == lever && Math.abs(it.tick - tracker.now) <= MATCH_WINDOW }
			.minByOrNull { Math.abs(it.tick - tracker.now) }
		if (match == null) {
			replanWanted = true
			return
		}
		plan = current.without(match)
		validate(tracker)
	}

	private fun cancelRunning() {
		if (job != null) {
			cancelJob = true
			job = null
			replanWanted = true
		}
	}

	/** Pulls the server should have had by now and hasn't: the plan is redone without them. */
	private fun checkPlan(tracker: WaterTracker) {
		val current = plan ?: return
		if (floating) return
		if (current.flips.any { it.tick < tracker.now - OVERDUE }) {
			plan = WaterPlan(current.flips.filter { it.tick >= tracker.now - OVERDUE }, current.solvedAt, current.solveRate, current.cost)
			replanWanted = true
		}
		if (tracker.version != jobVersion && job == null && !replanWanted) {
			jobVersion = tracker.version
			validate(tracker)
		}
	}

	// ---- Drawing -----------------------------------------------------------------------------------

	fun render(context: LevelRenderContext) {
		if (!active) return
		val room = room ?: return
		val tracker = tracker ?: return
		if (PuzzleRooms.named(PUZZLE) !== room) return
		fun leverPos(lever: WaterLever) = room.getRealCoords(BlockPos(lever.x, lever.y, lever.z))
		val waterLever = leverPos(WaterLever.WATER)

		val current = plan
		if (current == null || current.flips.isEmpty()) {
			val text = when {
				job != null -> "§ePlanning…"
				tracker.sim.closed == 0 -> null
				else -> lastStatus?.let { "§c$it" }
			}
			if (text != null && waterLever != null) PuzzleRender.text(context, text, Vec3(waterLever.x + 0.5, waterLever.y + 1.5, waterLever.z + 0.5))
			return
		}

		val ping = pingTicks()
		val first = current.flips.first()
		val next = leverPos(first.lever)
		val after = current.flips.drop(1).firstOrNull { it.lever != first.lever }?.let { leverPos(it.lever) }
		if (PuzzleSolver.waterTracer.value && next != null) {
			PuzzleRender.tracer(context, Vec3.atCenterOf(next), PuzzleSolver.waterTracerFirst.argb)
			if (after != null) PuzzleRender.line(context, listOf(Vec3.atCenterOf(next), Vec3.atCenterOf(after)), PuzzleSolver.waterTracerSecond.argb)
		}
		if (PuzzleSolver.waterLeverHighlight.value) {
			val style = PuzzleSolver.waterLeverStyle.selectedIndex
			next?.let { PuzzleRender.shape(context, it, PuzzleSolver.waterTracerFirst.argb, style) }
			after?.let { PuzzleRender.shape(context, it, PuzzleSolver.waterTracerSecond.argb, style) }
		}

		for ((lever, flips) in current.flips.groupBy { it.lever }) {
			val pos = leverPos(lever) ?: continue
			flips.forEachIndexed { index, flip ->
				val label = if (floating) {
					if (flip === first) "§a§lPULL" else "§e+${seconds(flip.tick - first.tick)}s"
				} else {
					val left = flip.tick - tracker.now - ping
					if (left <= 1) {
						if (PuzzleSolver.waterClickSound.value && dinged.add(flip)) ding()
						"§a§lPULL"
					} else "§e${seconds(left)}s"
				}
				PuzzleRender.text(context, label, Vec3(pos.x + 0.5, pos.y + index * 0.5 + 1.5, pos.z + 0.5))
			}
		}
	}

	private fun ding() {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		client.level?.playLocalSound(player.x, player.y, player.z, SoundEvents.NOTE_BLOCK_PLING.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1f, 2f, false)
	}

	private fun seconds(ticks: Int): String = String.format(Locale.ROOT, "%.1f", ticks / 20f)

	// ---- Lifetime ----------------------------------------------------------------------------------

	/** Reads the board again and plans from it (the room's chest was opened, or Reset was pressed). */
	fun reset() {
		val room = room ?: return
		val level = Minecraft.getInstance().level ?: return
		cancelRunning()
		start(room, level)
	}

	fun forget() {
		cancelJob = true
		job = null
		room = null
		tracker = null
		plan = null
		leverAt.clear()
		replanWanted = false
		lastStatus = null
	}

	fun describe(): List<String> {
		val tracker = tracker ?: return listOf("Dynamic water: not following a board.")
		val lines = mutableListOf(
			"Dynamic water: tick ${tracker.now}, gates ${Integer.toBinaryString(tracker.sim.closed)}, source ${if (tracker.sim.sourceOn) "on" else "off"}, ${if (tracker.sim.quiet) "quiet" else "moving"}",
		)
		val current = plan
		lines += if (current == null) "  No plan${if (job != null) " (planning)" else ""}"
		else "  Plan${if (floating) " (from its first pull)" else ""}: solves t${current.solvedAt}, ${String.format(Locale.ROOT, "%.0f", current.solveRate * 100)}% with jitter: " +
			current.flips.joinToString { "${it.lever.name.lowercase()}@${it.tick}" }
		lines += tracker.log.map { "  $it" }
		return lines
	}

	private const val FIRST_BUDGET_MS = 1500L
	private const val REPLAN_BUDGET_MS = 700L
	private const val CHECK_EVERY = 20
	private const val QUIET_TICKS = 12
	private const val MATCH_WINDOW = 10
	private const val OVERDUE = 4
	private const val LATE_SOLVE = 10
}
