package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor

/**
 * Water Board preview: the water's path, and what a lever would change. Point at a lever and the
 * board is redrawn as if it were pulled: blocks it would push out filled, blocks it would pull back
 * outlined, and the changed part of the path dashed.
 *
 * Water falls, then runs sideways to the nearest hole within five blocks, or both ways up to seven
 * without one. Inspired by Skyblocker's Waterboard previewer.
 */
object WaterPreview {
	private const val PUZZLE = "Water Board"

	private const val MIN_X = 6
	private const val MAX_X = 24
	private const val MIN_Y = 58
	private const val MAX_Y = 81
	private const val BOARD_Z = 26

	private val ENTRANCE = BlockPos(15, 78, BOARD_Z)

	/** The six levers: position, the block each moves, and its preview colour. */
	private enum class Gate(val lever: BlockPos, val block: () -> Block, val rgb: Int) {
		COAL(BlockPos(20, 61, 10), { Blocks.COAL_BLOCK }, 0xFF5555),
		GOLD(BlockPos(20, 61, 15), { Blocks.GOLD_BLOCK }, 0xFFFF55),
		QUARTZ(BlockPos(20, 61, 20), { Blocks.QUARTZ_BLOCK }, 0xD0D0D0),
		DIAMOND(BlockPos(10, 61, 20), { Blocks.DIAMOND_BLOCK }, 0x55FFFF),
		EMERALD(BlockPos(10, 61, 15), { Blocks.EMERALD_BLOCK }, 0x55FF55),
		CLAY(BlockPos(10, 61, 10), { Blocks.TERRACOTTA }, 0xFFAA00),
	}

	private const val WATER_RGB = 0x55AAFF

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.waterPreviewPath.value && !PuzzleSolver.waterPreviewLevers.value) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val level = Minecraft.getInstance().level ?: return

		// Only a lever under the crosshair is previewed.
		val gate = if (PuzzleSolver.waterPreviewLevers.value) hoveredGate(room) else null

		if (PuzzleSolver.waterPreviewPath.value) {
			val current = pathOf(room, level, null)
			val argb = ARGB.opaque(WATER_RGB)

			if (gate == null) {
				current.forEach { drawSegment(context, room, it, argb, dashed = false) }
			} else {
				// Solid where the two paths agree, dashed for what the lever changes.
				val preview = pathOf(room, level, gate)
				val shared = current.toSet()
				preview.forEach { drawSegment(context, room, it, argb, dashed = it !in shared) }
			}
		}

		if (gate != null) drawGateChanges(context, room, level, gate)
	}

	/**
	 * The water's route as straight runs, with [pulled]'s lever applied if set. From each place
	 * water arrives it falls, then runs along the ledge; where a run tips over is a new arrival.
	 * Each place is started from once, so a loop can't trap the trace.
	 */
	private fun pathOf(room: DungeonRoom, level: Level, pulled: Gate?): List<Pair<BlockPos, BlockPos>> {
		val board = Board(room, level, pulled)
		val runs = mutableListOf(ENTRANCE.above(5) to ENTRANCE.above(3))
		val arrivals = ArrayDeque<BlockPos>()
		val started = HashSet<BlockPos>()
		arrivals.addLast(ENTRANCE.above(3))

		while (arrivals.isNotEmpty() && runs.size < MAX_RUNS) {
			val from = arrivals.removeFirst()
			if (!started.add(from) || !board.open(from.below())) continue
			var bottom = from.below()
			while (board.open(bottom.below())) bottom = bottom.below()
			runs += from to bottom

			val east = board.runFrom(bottom, Direction.EAST)
			val west = board.runFrom(bottom, Direction.WEST)
			for (side in board.waysOff(east, west)) {
				runs += bottom to side.end
				arrivals.addLast(side.end)
			}
		}
		return runs
	}

	private class Run(val end: BlockPos, val steps: Int, val tips: Boolean)

	/**
	 * The board's squares, with [pulled]'s gate moved: a block on the board goes back, a parked one
	 * comes out.
	 */
	private class Board(val room: DungeonRoom, val level: Level, val pulled: Gate?) {
		fun open(pos: BlockPos): Boolean {
			if (pos.z != BOARD_Z || pos.x !in MIN_X..MAX_X || pos.y !in MIN_Y..MAX_Y) return false
			val here = level.getBlockState(room.getRealCoords(pos) ?: return false)
			if (pulled != null) {
				val gateBlock = pulled.block()
				if (here.block == gateBlock) return true
				val behind = room.getRealCoords(pos.relative(Direction.SOUTH)) ?: return false
				if (level.getBlockState(behind).block == gateBlock) return false
			}
			return here.isAir || here.block == Blocks.WATER
		}

		/** Water running from [from] along [way], at most [LONGEST_RUN]. */
		fun runFrom(from: BlockPos, way: Direction): Run {
			var at = from
			var steps = 0
			while (steps < LONGEST_RUN && open(at.relative(way)) && !open(at.below())) {
				at = at.relative(way)
				steps++
			}
			return Run(at, steps, open(at.below()))
		}

		/**
		 * Which way water leaves a ledge: toward the nearer hole within [NEAREST_HOLE], or both
		 * ways when tied or none is near.
		 */
		fun waysOff(east: Run, west: Run): List<Run> {
			fun goesFor(run: Run, other: Run) = run.tips && run.steps <= NEAREST_HOLE && (run.steps < other.steps || !other.tips)
			return when {
				goesFor(east, west) -> listOf(east)
				goesFor(west, east) -> listOf(west)
				else -> listOf(east, west).filter { it.steps > 0 }
			}
		}
	}

	private fun drawSegment(
		context: LevelRenderContext,
		room: DungeonRoom,
		segment: Pair<BlockPos, BlockPos>,
		argb: Int,
		dashed: Boolean,
	) {
		val start = room.getRealCoords(segment.first)?.let(Vec3::atCenterOf) ?: return
		val end = room.getRealCoords(segment.second)?.let(Vec3::atCenterOf) ?: return
		if (dashed) drawDashed(context, start, end, argb)
		else PuzzleRender.line(context, listOf(start, end), argb, phase = true, lineWidth = 3f)
	}

	/**
	 * The lever under the crosshair. Its gate is found from the block it's mounted on (each stands
	 * on the block it moves), with the position table as a fallback.
	 */
	private fun hoveredGate(room: DungeonRoom): Gate? {
		val level = Minecraft.getInstance().level ?: return null
		val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return null
		val pos = hit.blockPos
		val state = level.getBlockState(pos)
		if (state.block != Blocks.LEVER) return null

		// The wall, floor or ceiling it's mounted on, depending on how it was placed.
		val mount = when (state.getOptionalValue(FaceAttachedHorizontalDirectionalBlock.FACE).orElse(null)) {
			AttachFace.FLOOR -> pos.below()
			AttachFace.CEILING -> pos.above()
			AttachFace.WALL -> state.getOptionalValue(HorizontalDirectionalBlock.FACING)
				.orElse(null)?.let { pos.relative(it.opposite) }
			else -> null
		}
		if (mount != null) {
			val block = level.getBlockState(mount).block
			Gate.entries.firstOrNull { it.block() == block }?.let { return it }
		}

		return Gate.entries.firstOrNull { room.getRealCoords(it.lever) == pos }
	}

	/** What the preview sees, for `/cryptic debug room`. */
	fun describe(): List<String> {
		val room = PuzzleRooms.named(PUZZLE) ?: return listOf("Water preview: not in the Water Board.")

		val hit = Minecraft.getInstance().hitResult as? BlockHitResult
		val lines = mutableListOf(
			"Water preview: looking at ${hit?.blockPos?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "nothing"}",
		)

		Gate.entries.forEach { gate ->
			val pos = room.getRealCoords(gate.lever)
			lines += "  ${gate.name} lever at ${pos?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "unknown"}"
		}
		lines += "  Previewing: ${hoveredGate(room)?.name ?: "none"}"
		return lines
	}

	/**
	 * What the lever under the crosshair would move: blocks on the board filled, parked blocks
	 * behind it outlined.
	 */
	/**
	 * Which gates are on the board, and whether water is running. A gate on the board's plane is
	 * open, which is more reliable than the lever's switch.
	 */
	fun boardState(room: DungeonRoom, level: Level): BoardState {
		val open = mutableSetOf<Block>()
		var water = false

		for (x in MIN_X..MAX_X) {
			for (y in MIN_Y..MAX_Y) {
				val pos = room.getRealCoords(BlockPos(x, y, BOARD_Z)) ?: continue
				val block = level.getBlockState(pos).block
				if (block == Blocks.WATER) water = true
				if (Gate.entries.any { it.block() == block }) open.add(block)
			}
		}

		return BoardState(open, water)
	}

	data class BoardState(val openGates: Set<Block>, val running: Boolean)

	private fun drawGateChanges(context: LevelRenderContext, room: DungeonRoom, level: Level, gate: Gate) {
		val block = gate.block()
		val fill = ARGB.color(FILL_ALPHA, ARGB.opaque(gate.rgb))
		val outline = ARGB.opaque(gate.rgb)

		for (x in MIN_X..MAX_X) {
			for (y in MIN_Y..MAX_Y) {
				val active = room.getRealCoords(BlockPos(x, y, BOARD_Z)) ?: continue
				val inactive = room.getRealCoords(BlockPos(x, y, BOARD_Z + 1)) ?: continue

				if (level.getBlockState(active).block == block) {
					PuzzleRender.box(context, AABB(active), fill, PuzzleRender.STYLE_FILLED)
				} else if (level.getBlockState(inactive).block == block) {
					PuzzleRender.box(context, AABB(inactive), outline, PuzzleRender.STYLE_OUTLINE)
				}
			}
		}
	}

	private fun drawDashed(context: LevelRenderContext, from: Vec3, to: Vec3, argb: Int) {
		val length = from.distanceTo(to)
		if (length <= 0.0) return
		val step = from.subtract(to).normalize().scale(-1.0)

		var travelled = 0.0
		while (travelled < length) {
			val dash = minOf(DASH_LENGTH, length - travelled)
			val start = from.add(step.scale(travelled))
			PuzzleRender.line(context, listOf(start, start.add(step.scale(dash))), argb, phase = true, lineWidth = 3f)
			travelled += DASH_LENGTH + DASH_GAP
		}
	}

	/** More runs than this means something's wrong; the trace stops. */
	private const val MAX_RUNS = 200

	private const val LONGEST_RUN = 7
	private const val NEAREST_HOLE = 5

	private const val FILL_ALPHA = 0x80

	private const val DASH_LENGTH = 0.3
	private const val DASH_GAP = 0.3
}
