package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap

/**
 * Which two lanterns belong to each other.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). The room has
 * eight beams to break and the pair that make up each one are on opposite
 * walls, which is impossible to see from inside. The pairs are fixed, so the
 * answer is a list of them; only the pairs whose lanterns are both still
 * standing are drawn, which is what makes a broken beam disappear from the
 * picture.
 *
 * The pairs are worked out again whenever a lantern changes, because breaking
 * one is exactly the event that should take its line off the screen.
 */
object BeamsSolver {
	private const val PUZZLE = "Creeper Beams"

	/** One lantern to its partner, and the colour they share. */
	private val pairs = ConcurrentHashMap<BlockPos, Pair<BlockPos, Int>>()

	/**
	 * Eight colours meant to be told apart at a glance.
	 *
	 * The two ends of a beam are marked in the same colour, so what matters is
	 * not that the palette looks nice but that no two of its entries can be
	 * confused across a dim room. These are spread around the wheel and kept
	 * bright: the pale and the dark shades that sat next to each other in the
	 * first version - two greens, two purples - were the ones that could not be
	 * paired up by eye.
	 */
	private val palette = listOf(
		0xFF2020, // red
		0x20FF20, // green
		0x4080FF, // blue
		0xFFFF20, // yellow
		0xFF20FF, // magenta
		0x20FFFF, // cyan
		0xFF8000, // orange
		0xFFFFFF, // white
	)

	/**
	 * The colour for the nth beam in the room.
	 *
	 * Eight are written down; past that the wheel is divided evenly, so even a
	 * room with more beams than anybody has seen still gives every one of them
	 * a colour of its own.
	 */
	private fun colorFor(index: Int): Int {
		palette.getOrNull(index)?.let { return it }
		val hue = ((index - palette.size) * GOLDEN_ANGLE) % 1f
		return Mth.hsvToRgb(hue, 0.85f, 1f) and 0xFFFFFF
	}

	/** Spacing that keeps successive hues as far apart as they can be. */
	private const val GOLDEN_ANGLE = 0.618033f

	fun onRoomEnter(room: DungeonRoom) {
		reset()
		if (room.data?.name != PUZZLE) return
		recalculate(room)
	}

	private fun recalculate(room: DungeonRoom) {
		val level = Minecraft.getInstance().level ?: return
		if (!room.resolveRotation(level)) return

		PuzzleAssets.ensureLoaded()
		pairs.clear()

		// Counted over the pairs that are actually still standing rather than
		// over the answer sheet. The sheet lists more pairs than any one room
		// uses, so colouring by its index left gaps - and a gap of eight is two
		// beams wearing the same colour, which is the one thing this must not
		// do.
		var taken = 0
		PuzzleAssets.beams.forEach { pair ->
			if (pair.size < 6) return@forEach
			val first = room.getRealCoords(BlockPos(pair[0], pair[1], pair[2])) ?: return@forEach
			val second = room.getRealCoords(BlockPos(pair[3], pair[4], pair[5])) ?: return@forEach
			if (level.getBlockState(first).block != Blocks.SEA_LANTERN) return@forEach
			if (level.getBlockState(second).block != Blocks.SEA_LANTERN) return@forEach

			pairs[first] = second to colorFor(taken++)
		}
	}

	/**
	 * A lantern turning into prismarine, or back, is a beam being broken.
	 *
	 * Asked about every block change in the room rather than only the lanterns,
	 * because the other end of a broken beam changes at the same moment and
	 * both have to leave the picture together.
	 */
	fun onBlockChanged(pos: BlockPos, wasLantern: Boolean, isLantern: Boolean) {
		if (!PuzzleRooms.inside(PUZZLE)) return
		if (!wasLantern && !isLantern) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		Minecraft.getInstance().execute { recalculate(room) }
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.beamsEnabled.value || pairs.isEmpty()) return
		if (!PuzzleRooms.inside(PUZZLE)) return

		val style = PuzzleSolver.beamsStyle.selectedIndex
		val alpha = (PuzzleSolver.beamsAlpha.value / 100.0 * 255).toInt().coerceIn(0, 255)

		pairs.forEach { (first, value) ->
			val (second, rgb) = value
			val argb = ARGB.color(alpha, ARGB.opaque(rgb))
			val phase = PuzzleSolver.beamsPhase.value
			PuzzleRender.box(context, AABB(first), argb, style, phase)
			PuzzleRender.box(context, AABB(second), argb, style, phase)
			if (PuzzleSolver.beamsTracer.value) {
				PuzzleRender.line(
					context,
					listOf(Vec3.atCenterOf(first), Vec3.atCenterOf(second)),
					argb,
					phase,
				)
			}
		}
	}

	fun reset() = pairs.clear()
}
