package imicro.cryptic.puzzle

import imicro.cryptic.feature.PuzzleSolver
import imicro.cryptic.skyblock.ServerStats
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * Which lever to pull on the Water Board, and when.
 *
 * The answers are Odin's (BSD 3-Clause, Copyright (c) 2025 odtheking): the board
 * is one of four layouts, which three of its five outlets are shut decides the
 * answer, and the pair is a key into the answer sheet. The sheet gives a list of
 * times per lever — zero means pull it before the water is let in, anything else
 * means that many seconds after.
 *
 * How the answer is kept up to date is Skyblocker's (LGPL-3.0, its
 * WaterboardOneFlow): each lever keeps the list of pulls it still owes, and
 * pulling it crosses the first one off. A lever pulled before the water that
 * was not meant to be gets a pull added back, to put it right. And solving —
 * the first time, and every time the board is reset — reads where each gate
 * actually is, so a board that has been half played is solved from where it
 * stands rather than from where it started.
 *
 * Once solved, the gates are watched rather than the clicks: a gate block
 * leaving or coming back to its starting place is a pull, whoever made it, so a
 * teammate's pull counts the same as yours. After the water is let in, a pull
 * the plan did not want, one made well early, or one that is overdue puts the
 * board off plan, which is said at once — the timed part of an answer cannot
 * be corrected from the middle, and the way back is resetting the board.
 */
object WaterSolver {
	private const val PUZZLE = "Water Board"

	/** The top middle of the board, which every gate's starting place is measured from. */
	private val WATER_ENTRANCE = BlockPos(15, 78, 26)

	/** Which of the five outlets are shut, which the answer sheet is keyed by. */
	private enum class Outlet(val pos: BlockPos) {
		PURPLE(BlockPos(15, 56, 19)),
		ORANGE(BlockPos(15, 56, 18)),
		BLUE(BlockPos(15, 56, 17)),
		GREEN(BlockPos(15, 56, 16)),
		RED(BlockPos(15, 56, 15));

		/** An outlet is out when there is something standing in its slot. */
		val extended: Boolean
			get() {
				val real = PuzzleRooms.real(PUZZLE, pos) ?: return false
				val level = Minecraft.getInstance().level ?: return false
				return !level.getBlockState(real).isAir
			}
	}

	/**
	 * The seven levers, in the order the answer sheet names them.
	 *
	 * [start] is where the lever's gate block stands on a fresh board, one place
	 * per layout, measured from [WATER_ENTRANCE] — Skyblocker's table. A gate
	 * block missing from its starting place is a gate that has been moved, which
	 * is how a lever that has been pulled is told from one that has not: more
	 * reliable than the lever's own switch, and unaffected by who pulled it.
	 * Null where a layout has no such gate to watch.
	 */
	private enum class Lever(val relative: BlockPos, val key: String, val block: Block?, val start: List<BlockPos?>) {
		COAL(
			BlockPos(20, 61, 10), "coal_block", Blocks.COAL_BLOCK,
			listOf(BlockPos(0, -2, 0), BlockPos(2, -1, 1), null, BlockPos(5, -1, 0)),
		),
		GOLD(
			BlockPos(20, 61, 15), "gold_block", Blocks.GOLD_BLOCK,
			listOf(BlockPos(1, -1, 0), BlockPos(3, -2, 0), BlockPos(-4, -1, 1), BlockPos(1, 0, 0)),
		),
		QUARTZ(
			BlockPos(20, 61, 20), "quartz_block", Blocks.QUARTZ_BLOCK,
			listOf(BlockPos(1, -4, 1), BlockPos(-1, 0, 0), BlockPos(1, 0, 0), BlockPos(-1, 0, 1)),
		),
		DIAMOND(
			BlockPos(10, 61, 20), "diamond_block", Blocks.DIAMOND_BLOCK,
			listOf(BlockPos(0, -5, 1), BlockPos(-2, -1, 0), BlockPos(-1, 0, 1), BlockPos(-3, -4, 1)),
		),
		EMERALD(
			BlockPos(10, 61, 15), "emerald_block", Blocks.EMERALD_BLOCK,
			listOf(BlockPos(-1, -10, 1), BlockPos(1, 0, 1), BlockPos(-6, 0, 0), BlockPos(1, -4, 0)),
		),
		CLAY(
			BlockPos(10, 61, 10), "hardened_clay", Blocks.TERRACOTTA,
			listOf(BlockPos(-1, -1, 1), BlockPos(0, -3, 1), null, BlockPos(-4, -5, 1)),
		),
		WATER(BlockPos(15, 60, 5), "water", null, emptyList());

		val pos: BlockPos?
			get() = PuzzleRooms.real(PUZZLE, relative)

		/** "Coal", "Diamond": for saying which lever went wrong. */
		val title: String
			get() = if (this == CLAY) "Terracotta" else name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
	}

	/** The layout, 0 to 3, or -1 before the board has been read. */
	private var pattern = -1

	/** Every lever's pulls still owed, soonest first. Empty until solved. */
	private val solution = LinkedHashMap<Lever, MutableList<Double>>()

	/** Set when a solve could not happen yet, so it is tried again until it can. */
	private var awaitingSolve = true

	/**
	 * Set by a reset that found water on the board, which is nearly every
	 * reset: the water takes seconds to drain once the board is reset. The
	 * solve waits for it, says so over the water lever, and says so in chat
	 * when the answer is ready — before, the answer just appeared on its own a
	 * few seconds after the reset seemed to have done nothing.
	 */
	private var waitingForDrain = false

	/** The tick the water was let in on, or -1 while it has not been. */
	private var waterAt = -1
	private var ticks = 0

	/** Each watched gate as last seen: true while it is away from where it starts. */
	private val gateMoved = HashMap<Lever, Boolean>()

	/** Why the board has gone off plan, once it has, or null while it is on it. */
	private var offPlan: String? = null

	/**
	 * Solves the board if it has not been solved, and is quiet about why not;
	 * once it has, notices the water being let in by somebody else. Called four
	 * times a second while standing in the room.
	 */
	fun scan() {
		if (awaitingSolve) {
			solve(loud = waitingForDrain)
			return
		}
		watchForWater()
	}

	/**
	 * The water running with no click of yours to have started it: a
	 * teammate's. Seen a whole round trip after it happened — the click's way
	 * there and the board's way back — so the clock is set back by the ping,
	 * which is the one case it needs to be. When you pull the water yourself,
	 * the clock starts at your click, and every timed pull after it travels the
	 * same way your click did, so the two delays cancel out.
	 */
	private fun watchForWater() {
		if (waterAt != -1 || solution.isEmpty()) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val level = Minecraft.getInstance().level ?: return
		if (!WaterPreview.boardState(room, level).running) return
		waterAt = ticks - pingTicks()
		solution[Lever.WATER]?.let { if (it.isNotEmpty()) it.removeAt(0) }
	}

	/**
	 * Watches the gates, once a tick. A gate block leaving its starting place,
	 * or coming back to it, is its lever being pulled — by you or anybody — and
	 * is counted as the pull, made a round trip before it could be seen.
	 */
	fun tick() {
		if (solution.isEmpty()) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val level = Minecraft.getInstance().level ?: return
		val lag = pingTicks()

		Lever.entries.forEach { lever ->
			val at = gatePos(room, lever) ?: return@forEach
			val moved = level.getBlockState(at).block != lever.block
			val was = gateMoved.put(lever, moved) ?: return@forEach
			if (was != moved) credit(lever, ticks - lag)
		}

		if (waterAt != -1 && offPlan == null) checkOverdue(room, lag)
	}

	/** Where [lever]'s gate block starts on this layout, if it has one to watch. */
	private fun gatePos(room: imicro.cryptic.dungeon.map.DungeonRoom, lever: Lever): BlockPos? {
		if (lever.block == null || pattern < 0) return null
		val offset = lever.start.getOrNull(pattern) ?: return null
		return room.getRealCoords(WATER_ENTRANCE.offset(offset))
	}

	/** Any pull the plan owes that is well past due. */
	private fun checkOverdue(room: imicro.cryptic.dungeon.map.DungeonRoom, lag: Int) {
		solution.forEach { (lever, times) ->
			if (lever == Lever.WATER) return@forEach
			val first = times.firstOrNull() ?: return@forEach
			val due = waterAt + (first * 20).toInt()
			// A watched gate is only seen to move a round trip after the pull.
			val seenLate = if (gatePos(room, lever) != null) lag else 0
			val late = ticks - due - seenLate
			if (late > LATE_TICKS) {
				goOffPlan("${lever.title} has not been pulled, ${seconds(late / 20f)}s after it was due")
				return
			}
		}
	}

	/** The ping in ticks, rounded, or nothing before it has been measured. */
	private fun pingTicks(): Int {
		val ping = ServerStats.ping
		return if (ping <= 0) 0 else (ping + 25) / 50
	}

	private fun goOffPlan(reason: String) {
		if (!PuzzleSolver.waterOffPlan.value || offPlan != null) return
		offPlan = reason
		say("§cWater board off plan:§7 $reason. If the gates do not open, reset the board by opening its chest.")
	}

	/**
	 * Works out the answer from the board as it stands.
	 *
	 * Refuses while there is water on the board, because the answer is timed
	 * from the water being let in and a board already running has a clock this
	 * cannot see; the outlets only read true once it has drained, too. Then the
	 * layout and the shut outlets pick the answer, and every gate is checked
	 * against where it started — which is what makes this the same thing on a
	 * fresh board and on one left half played.
	 */
	private fun solve(loud: Boolean) {
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val level = Minecraft.getInstance().level ?: return

		if (WaterPreview.boardState(room, level).running) {
			// Said once per reset; the solve is tried again every scan and
			// goes through, out loud, as soon as the water has gone.
			if (loud && !waitingForDrain) say("§eWaiting for the water to drain§7 — the board is solved as soon as it has. Turn the water off if it is still on.")
			if (loud) waitingForDrain = true
			return
		}

		val open = Outlet.entries.filter { it.extended }
		if (open.size != 3) {
			if (loud) say("§cCould not read the water board's outlets§7 (${open.size} shut, expected 3).")
			return
		}
		val openKey = open.joinToString("") { it.ordinal.toString() }

		fun blockAt(x: Int, y: Int, z: Int) =
			room.getRealCoords(BlockPos(x, y, z))?.let { level.getBlockState(it).block }

		val read = when {
			blockAt(14, 77, 27) == Blocks.TERRACOTTA -> 0
			blockAt(16, 78, 27) == Blocks.EMERALD_BLOCK -> 1
			blockAt(14, 78, 27) == Blocks.DIAMOND_BLOCK -> 2
			blockAt(14, 78, 27) == Blocks.QUARTZ_BLOCK -> 3
			else -> {
				if (loud) say("§cCould not read the water board's layout.")
				return
			}
		}

		PuzzleAssets.ensureLoaded()
		val plain = answersFor(read, false, openKey)
		val quick = if (PuzzleSolver.waterOptimized.value) answersFor(read, true, openKey) else null

		// The optimised route is only taken when it can actually be played: some
		// of its patterns put two pulls a third of a second apart, which is a
		// solution on paper and a failed board in practice. The gap you are
		// willing to work to is yours to set.
		val gap = PuzzleSolver.waterMinGap.value
		val answers = when {
			quick == null -> plain
			tightestGap(quick) >= gap -> quick
			else -> {
				say("§7The optimised water route needs pulls §f${tightestGap(quick).toFixed()}s§7 apart; using the safe one.")
				plain
			}
		}
		if (answers == null) {
			say("§cNo answer for this water board (layout $read, outlets $openKey).")
			return
		}

		pattern = read
		solution.clear()
		Lever.entries.forEach { lever ->
			solution[lever] = answers[lever.key]?.toMutableList() ?: mutableListOf()
		}
		waterAt = -1
		awaitingSolve = false
		val afterDrain = waitingForDrain
		waitingForDrain = false

		val corrected = matchGates(room, level)
		// Where every gate stands now, for the watch that counts pulls from here.
		offPlan = null
		gateMoved.clear()
		Lever.entries.forEach { lever ->
			val at = gatePos(room, lever) ?: return@forEach
			gateMoved[lever] = level.getBlockState(at).block != lever.block
		}
		if (loud) {
			if (afterDrain) say("§aThe water has drained.")
			val pulls = solution.values.sumOf { it.size }
			val note = if (corrected > 0) " §7($corrected already moved, taken into account)" else ""
			say("§aWater board solved from the board as it stands§7: §f$pulls§7 pulls.$note")
		}
	}

	/**
	 * Puts the answer in step with where the gates actually are.
	 *
	 * Skyblocker's rule, per lever: a gate that has moved from its starting
	 * place has had its lever pulled. If the answer's first pull for it is one
	 * made before the water, that pull is already done and comes off. If it is
	 * not, the lever was pulled when it should not have been, and a pull goes
	 * on the front to put it back. Returns how many levers were found moved.
	 */
	private fun matchGates(room: imicro.cryptic.dungeon.map.DungeonRoom, level: net.minecraft.world.level.Level): Int {
		var moved = 0
		Lever.entries.forEach { lever ->
			if (lever == Lever.WATER) return@forEach
			val block = lever.block ?: return@forEach
			val offset = lever.start.getOrNull(pattern) ?: return@forEach
			val at = room.getRealCoords(WATER_ENTRANCE.offset(offset)) ?: return@forEach
			if (level.getBlockState(at).block == block) return@forEach

			moved++
			val times = solution.getOrPut(lever) { mutableListOf() }
			if (times.firstOrNull() == 0.0) times.removeAt(0) else times.add(0, 0.0)
		}
		return moved
	}

	private fun answersFor(pattern: Int, optimised: Boolean, openKey: String): Map<String, List<Double>>? =
		PuzzleAssets.water[optimised.toString()]?.get(pattern.toString())?.get(openKey)

	/**
	 * The shortest wait between two timed pulls in a solution.
	 *
	 * Pulls at zero are the ones made before the water is let in — as many as
	 * you like, as fast as you like — so only the timed ones are measured.
	 */
	private fun tightestGap(answers: Map<String, List<Double>>): Double {
		val timed = answers.values.flatten().filter { it > 0.0 }.sorted()
		if (timed.size < 2) return Double.MAX_VALUE
		return timed.zipWithNext { first, second -> second - first }.min()
	}

	private fun Double.toFixed(): String = String.format(Locale.ROOT, "%.1f", this)

	/**
	 * A lever you pulled. A lever whose gate is watched is left to the watch,
	 * which sees the pull whoever made it and only once it has really happened;
	 * the water lever, and a lever this layout gives no gate to watch, are
	 * counted from the click.
	 */
	fun onLeverUsed(pos: BlockPos) {
		if (solution.isEmpty()) return
		val lever = Lever.entries.firstOrNull { it.pos == pos } ?: return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		if (lever != Lever.WATER && gatePos(room, lever) != null) return
		credit(lever, ticks)
	}

	/**
	 * One pull of [lever], made on tick [at].
	 *
	 * Skyblocker's rule before the water: a lever the answer did not want pulled
	 * gets a pull added to put it back, otherwise the first pull it owed is
	 * crossed off. The water lever starts the clock. After the water, the pull is
	 * held to the plan: one it had no pull left for, or one made well before its
	 * time, puts the board off plan.
	 */
	private fun credit(lever: Lever, at: Int) {
		val times = solution.getOrPut(lever) { mutableListOf() }

		if (lever == Lever.WATER) {
			if (times.isNotEmpty()) times.removeAt(0)
			if (waterAt == -1) waterAt = at
			return
		}

		if (waterAt == -1) {
			if (times.firstOrNull() == 0.0) times.removeAt(0) else times.add(0, 0.0)
			return
		}

		val first = times.firstOrNull()
		if (first == null) {
			goOffPlan("${lever.title} was pulled, and the plan had no pull left for it")
			return
		}
		times.removeAt(0)
		val early = waterAt + (first * 20).toInt() - at
		if (early > EARLY_TICKS) goOffPlan("${lever.title} was pulled ${seconds(early / 20f)}s early")
	}

	@JvmStatic
	fun onServerTick() {
		ticks++
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.waterEnabled.value) return
		if (!PuzzleRooms.inside(PUZZLE)) return
		if (waitingForDrain) {
			Lever.WATER.pos?.let { PuzzleRender.text(context, "§eWaiting for the water to drain", Vec3(it.x + 0.5, it.y + 1.5, it.z + 0.5)) }
			return
		}
		if (pattern == -1 || solution.isEmpty()) return

		// Off plan, the countdowns are a plan for a board that is not there any
		// more; only the way back is shown.
		if (offPlan != null) {
			Lever.WATER.pos?.let {
				PuzzleRender.text(context, "§c§lOFF PLAN", Vec3(it.x + 0.5, it.y + 2.0, it.z + 0.5))
				PuzzleRender.text(context, "§7Open the chest to reset", Vec3(it.x + 0.5, it.y + 1.5, it.z + 0.5))
			}
			return
		}

		// Everything still to do, soonest first. The water lever's own zero comes
		// after every other lever's, since those are made before it is.
		val remaining = solution
			.flatMap { (lever, times) -> times.map { lever to it } }
			.sortedWith(
				compareBy(
					{ it.second + if (it.first == Lever.WATER) WATER_LAST else 0.0 },
					{ it.first.ordinal },
				),
			)

		val nextLever = remaining.firstOrNull()?.first
		val first = nextLever?.pos
		val second = remaining.getOrNull(1)?.first?.pos?.takeIf { it != first }

		if (PuzzleSolver.waterTracer.value && first != null) {
			PuzzleRender.tracer(context, Vec3.atCenterOf(first), PuzzleSolver.waterTracerFirst.argb)

			if (second != null) {
				PuzzleRender.line(
					context,
					listOf(Vec3.atCenterOf(first), Vec3.atCenterOf(second)),
					PuzzleSolver.waterTracerSecond.argb,
				)
			}
		}

		// The lever itself, in the same two colours the tracers use: a line
		// arriving at a wall of six identical levers still leaves you picking
		// one of them by eye.
		if (PuzzleSolver.waterLeverHighlight.value) {
			val style = PuzzleSolver.waterLeverStyle.selectedIndex
			first?.let { PuzzleRender.shape(context, it, PuzzleSolver.waterTracerFirst.argb, style) }
			second?.let { PuzzleRender.shape(context, it, PuzzleSolver.waterTracerSecond.argb, style) }
		}

		solution.forEach { (lever, times) ->
			val pos = lever.pos ?: return@forEach
			times.forEachIndexed { index, time ->
				val inTicks = (time * 20).toInt()
				val label = when {
					// The water waits for every pull made before it.
					lever == Lever.WATER && time == 0.0 && nextLever != Lever.WATER -> "§c§lWAIT"
					waterAt == -1 && inTicks == 0 -> "§a§lPULL"
					waterAt == -1 -> "§e${time}s"
					else -> {
						val left = waterAt + inTicks - ticks
						if (left > 0) "§e${seconds(left / 20f)}s" else "§a§lPULL"
					}
				}
				PuzzleRender.text(context, label, Vec3(pos.x + 0.5, pos.y + index * 0.5 + 1.5, pos.z + 0.5))
			}
		}
	}

	private fun seconds(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

	private fun say(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$text"))
	}

	/**
	 * Solves the board again from where it stands — Skyblocker's reset.
	 *
	 * Nothing about the last attempt is kept: the water, the outlets and every
	 * gate are read afresh, so a lever pulled by hand, one pulled at the wrong
	 * time, and one Hypixel put back are all accounted for the same way.
	 */
	fun reset() {
		pattern = -1
		solution.clear()
		gateMoved.clear()
		offPlan = null
		waterAt = -1
		awaitingSolve = true
		waitingForDrain = false
		solve(loud = true)
	}

	/**
	 * The chest in the room, which is how the board is restarted in game.
	 *
	 * NoammAddons' idea: you reset the puzzle by opening its chest, so the
	 * solver may as well reset itself then too.
	 */
	fun onChestUsed(pos: BlockPos) {
		if (!PuzzleRooms.inside(PUZZLE)) return
		val level = Minecraft.getInstance().level ?: return
		if (level.getBlockState(pos).block != Blocks.CHEST) return
		reset()
	}

	/** Forgets the room entirely, for a floor that is being left. */
	fun forget() {
		pattern = -1
		solution.clear()
		gateMoved.clear()
		offPlan = null
		waterAt = -1
		awaitingSolve = true
		waitingForDrain = false
	}

	/**
	 * What the solver can see, in words, for `/cryptic debug room`.
	 *
	 * Each gate against its starting place, and the pulls each lever still owes,
	 * so a wrong answer can be traced to the one reading that was wrong.
	 */
	fun describe(): List<String> {
		val lines = mutableListOf("Water board: layout $pattern, ${if (solution.isEmpty()) "not solved" else "solved"}")
		val room = PuzzleRooms.named(PUZZLE)
		val level = Minecraft.getInstance().level
		if (room == null || level == null) return lines + "  Room not placed yet."

		lines += "  Outlets: " + Outlet.entries.joinToString(" ") { "${it.name}=${if (it.extended) "shut" else "open"}" }
		lines += "  Water on the board: ${WaterPreview.boardState(room, level).running}, clock ${if (waterAt < 0) "not started" else "running"}"
		lines += "  Plan: ${offPlan?.let { "off ($it)" } ?: "on"}, ping ${pingTicks()} ticks"

		Lever.entries.forEach { lever ->
			val offset = lever.start.getOrNull(pattern)
			val gate = if (lever.block == null || offset == null) {
				"no gate to watch"
			} else {
				val at = room.getRealCoords(WATER_ENTRANCE.offset(offset))
				if (at != null && level.getBlockState(at).block == lever.block) "gate at start" else "gate moved"
			}
			lines += "  ${lever.name}: $gate, owes ${solution[lever]?.joinToString(", ") ?: "-"}"
		}
		return lines
	}

	/** Enough to put the water lever's zero after every other zero, and before any real time. */
	private const val WATER_LAST = 0.001

	/** How early a timed pull may be, in ticks, before the board is called off plan. */
	private const val EARLY_TICKS = 20

	/** How late a timed pull may be, in ticks, before the board is called off plan. */
	private const val LATE_TICKS = 30
}
