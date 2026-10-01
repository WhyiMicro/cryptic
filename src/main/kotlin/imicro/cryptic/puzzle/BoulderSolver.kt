package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

/**
 * Which boulder to push, and from where.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). The room is
 * one of a few dozen fixed layouts, so reading which of the grid's spaces have
 * a boulder on them gives a string that the answer sheet is keyed by. The
 * answer is a list of pushes in order: a box on the boulder to push, and the
 * block whose click pushes it, which is how a push is crossed off.
 *
 * Odin's pairing is kept exactly as it is. An earlier version here marked the
 * block that gets clicked instead, on the theory that the sign is the thing
 * you aim at - and it put boxes in mid-air, because the two positions in the
 * answer sheet are a boulder and the space beside it rather than a sign and
 * its wall.
 */
object BoulderSolver {
	private const val PUZZLE = "Boulder"

	/** A boulder to push, and the block whose click pushes it. */
	private data class Push(val box: AABB, val click: BlockPos)

	private val pushes = mutableListOf<Push>()

	fun onRoomEnter(room: DungeonRoom) {
		reset()
		if (room.data?.name != PUZZLE) return

		val level = Minecraft.getInstance().level ?: return
		if (!room.resolveRotation(level)) return

		// The grid read back to front, which is the order the answer sheet's
		// keys were written in.
		val board = StringBuilder(42)
		for (z in 24 downTo 9 step 3) {
			for (x in 24 downTo 6 step 3) {
				val pos = room.getRealCoords(BlockPos(x, 66, z)) ?: return
				board.append(if (level.getBlockState(pos).isAir) '0' else '1')
			}
		}

		PuzzleAssets.ensureLoaded()
		val solution = PuzzleAssets.boulder[board.toString()] ?: return
		solution.forEach { step ->
			val boulder = room.getRealCoords(BlockPos(step[0], 65, step[1])) ?: return@forEach
			val click = room.getRealCoords(BlockPos(step[2], 65, step[3])) ?: return@forEach
			pushes.add(Push(AABB(boulder), click))
		}
	}

	/** A push is done when the block that makes it has been clicked. */
	fun onBlockUsed(pos: BlockPos) {
		pushes.firstOrNull { it.click == pos }?.let(pushes::remove)
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.boulderEnabled.value || pushes.isEmpty()) return
		if (!PuzzleRooms.inside(PUZZLE)) return

		val style = PuzzleSolver.boulderStyle.selectedIndex
		val color = PuzzleSolver.boulderColor.argb
		val phase = PuzzleSolver.boulderPhase.value

		val shown = if (PuzzleSolver.boulderAll.value) pushes else pushes.take(1)
		shown.forEach { PuzzleRender.box(context, it.box, color, style, phase) }
	}

	fun reset() = pushes.clear()
}
