package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

/**
 * Boulder: which boulder to push, and from where. The board's boulders form a key into Odin's
 * answer sheet, which lists the pushes in order: a box on the boulder and the block whose click
 * pushes it. Ported from Odin.
 */
object BoulderSolver {
	private const val PUZZLE = "Boulder"

	private data class Push(val box: AABB, val click: BlockPos)

	private val pushes = mutableListOf<Push>()

	fun onRoomEnter(room: DungeonRoom) {
		reset()
		if (room.data?.name != PUZZLE) return

		val level = Minecraft.getInstance().level ?: return
		if (!room.resolveRotation(level)) return

		// Read back to front, the order the sheet's keys use.
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
