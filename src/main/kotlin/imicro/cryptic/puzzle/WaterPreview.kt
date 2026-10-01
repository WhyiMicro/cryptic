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
 * What the water will do, and what a lever would change.
 *
 * Ported from Skyblocker's Waterboard previewer (LGPL-3.0), with the trigger
 * changed. The path traces the water from the top of the board down through
 * the gaps; point at one of the six levers and the board is redrawn as it
 * would be if that lever were pulled — the blocks it would push out filled in,
 * the ones it would pull back outlined where they are parked, and the part of
 * the water's path that would change dashed.
 *
 * Skyblocker asks what block your line of sight crosses the board at, which
 * means the preview appears while you are simply looking at the puzzle. A
 * lever is a thing you deliberately point at, and the six of them are in known
 * places, so that is what this asks about instead.
 *
 * Water in SkyBlock falls, then runs sideways to the nearest hole within five
 * blocks, or both ways up to seven when there is none. Those rules are
 * Skyblocker's, found by watching the board rather than by reading anything.
 */
object WaterPreview {
	private const val PUZZLE = "Water Board"

	/** The board's own bounds, in the room's coordinates. */
	private const val MIN_X = 6
	private const val MAX_X = 24
	private const val MIN_Y = 58
	private const val MAX_Y = 81
	private const val BOARD_Z = 26

	/** Where the water comes in, between the first two toggling blocks. */
	private val ENTRANCE = BlockPos(15, 78, BOARD_Z)

	/**
	 * The six levers: where each one is, which block it moves, and the colour
	 * it wears while it is being previewed.
	 *
	 * The positions are the room's own, and the same six the solver counts
	 * pulls against — two mods and this one all agree on them.
	 */
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

		// Only a lever under the crosshair asks a question; looking at the
		// board itself is not asking anything, and answering it anyway is
		// what made every glance light up with dashes.
		val gate = if (PuzzleSolver.waterPreviewLevers.value) hoveredGate(room) else null

		if (PuzzleSolver.waterPreviewPath.value) {
			val current = pathOf(room, level, null)
			val argb = ARGB.opaque(WATER_RGB)

			if (gate == null) {
				current.forEach { drawSegment(context, room, it, argb, dashed = false) }
			} else {
				// Solid for what the two paths agree on, dashed for only what
				// this lever would change. A board drawn entirely in dashes
				// says the path is a preview but not which part of it moved.
				val preview = pathOf(room, level, gate)
				val shared = current.toSet()
				preview.forEach { drawSegment(context, room, it, argb, dashed = it !in shared) }
			}
		}

		if (gate != null) drawGateChanges(context, room, level, gate)
	}

	/** The water's route, either as the board stands or with one lever pulled. */
	private fun pathOf(room: DungeonRoom, level: Level, gate: Gate?): List<Pair<BlockPos, BlockPos>> {
		val path = mutableListOf<Pair<BlockPos, BlockPos>>()
		path.add(ENTRANCE.above(5) to ENTRANCE.above(3))
		fall(room, level, gate, ENTRANCE.above(3), path)
		return path
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

	/** Water falling: straight down until something stops it, then sideways. */
	private fun fall(
		room: DungeonRoom,
		level: Level,
		gate: Gate?,
		from: BlockPos,
		path: MutableList<Pair<BlockPos, BlockPos>>,
	) {
		if (path.size > MAX_STEPS) return
		if (!passable(room, level, gate, from.below())) return

		var bottom = from.below()
		while (passable(room, level, gate, bottom.below())) bottom = bottom.below()
		path.add(from to bottom)
		spread(room, level, gate, bottom, path)
	}

	/**
	 * Water on a ledge: it runs to the nearest hole within five blocks, and
	 * when there is none it runs both ways as far as seven.
	 */
	private fun spread(
		room: DungeonRoom,
		level: Level,
		gate: Gate?,
		from: BlockPos,
		path: MutableList<Pair<BlockPos, BlockPos>>,
	) {
		if (path.size > MAX_STEPS) return
		if (passable(room, level, gate, from.below())) return

		var left = from
		var leftSteps = 0
		while (passable(room, level, gate, left.east()) && !passable(room, level, gate, left.below()) && leftSteps < 7) {
			left = left.east()
			leftSteps++
		}

		var right = from
		var rightSteps = 0
		while (passable(room, level, gate, right.west()) && !passable(room, level, gate, right.below()) && rightSteps < 7) {
			right = right.west()
			rightSteps++
		}

		val leftDrops = passable(room, level, gate, left.below())
		val rightDrops = passable(room, level, gate, right.below())

		when {
			leftDrops && leftSteps <= 5 && (leftSteps < rightSteps || !rightDrops) -> {
				path.add(from to left)
				fall(room, level, gate, left, path)
			}
			rightDrops && rightSteps <= 5 && (rightSteps < leftSteps || !leftDrops) -> {
				path.add(from to right)
				fall(room, level, gate, right, path)
			}
			else -> {
				if (leftSteps > 0) {
					path.add(from to left)
					fall(room, level, gate, left, path)
				}
				if (rightSteps > 0) {
					path.add(from to right)
					fall(room, level, gate, right, path)
				}
			}
		}
	}

	/**
	 * Whether water can be in a square, with a lever's change applied.
	 *
	 * With a lever in mind, a block it would push out closes its square and a
	 * block it would pull back opens one: the two are told apart by whether the
	 * block is on the board or in the row behind it.
	 */
	private fun passable(room: DungeonRoom, level: Level, gate: Gate?, pos: BlockPos): Boolean {
		if (pos.x < MIN_X || pos.x > MAX_X || pos.y < MIN_Y || pos.y > MAX_Y || pos.z != BOARD_Z) return false

		val real = room.getRealCoords(pos) ?: return false
		val behind = room.getRealCoords(pos.relative(Direction.SOUTH)) ?: return false
		val state = level.getBlockState(real)
		val open = state.isAir || state.block == Blocks.WATER

		if (gate == null) return open
		val block = gate.block()
		return open && level.getBlockState(behind).block != block || state.block == block
	}

	/**
	 * The lever the crosshair is on, if it is on one at all.
	 *
	 * A lever is told apart from the board by being a lever, and which gate it
	 * works out by what it is mounted on: every one of the six stands on the
	 * block it moves. That is a question the world answers, unlike a written
	 * table of six positions, which is only right if the room was measured
	 * correctly and stays right only until it is not. The table is still
	 * consulted, but as the fallback, for a lever mounted on something plain.
	 */
	private fun hoveredGate(room: DungeonRoom): Gate? {
		val level = Minecraft.getInstance().level ?: return null
		val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return null
		val pos = hit.blockPos
		val state = level.getBlockState(pos)
		if (state.block != Blocks.LEVER) return null

		// What it is mounted on: the wall behind it, the floor under it or the
		// ceiling over it, depending on how it was placed.
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

	/**
	 * What the preview can see from where you are standing, in words.
	 *
	 * Reported by `/cryptic debug room` inside the Water Board, because a
	 * preview that draws nothing looks exactly like a preview that is switched
	 * off.
	 */
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
	 * What the lever under your crosshair would move.
	 *
	 * A block standing in the board is filled in, where it is. A block parked
	 * behind the board is outlined, also where it is — a row further back,
	 * which is where you can see it is. Skyblocker draws the parked ones at the
	 * position they would move to instead, and that reads as a block already
	 * standing in a slot that is plainly empty.
	 */
	/**
	 * Which gates are standing on the board, and whether water is on it.
	 *
	 * The board is the plane the water runs down; one block behind it is where
	 * a gate waits its turn. So a gate's blocks being on the plane is the gate
	 * being open, and that is a fact about the puzzle rather than about the
	 * lever that opened it — a lever's own switch position is the same thing
	 * one step removed, and one step is a step that can be out of date.
	 *
	 * Asked for by the solver, which has to know how much of its answer has
	 * already been played before it can say what is left of it.
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

	/** What the board is doing: which gates are out, and whether it is running. */
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

	/** A line drawn as dashes, for a path that has not happened yet. */
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

	/** A wall of blocks cannot need more steps than this, and a loop must not. */
	private const val MAX_STEPS = 64

	/** How solid a block that would be pushed out is drawn. */
	private const val FILL_ALPHA = 0x80

	/** The dash and the gap of a path that is only being considered. */
	private const val DASH_LENGTH = 0.3
	private const val DASH_GAP = 0.3
}
