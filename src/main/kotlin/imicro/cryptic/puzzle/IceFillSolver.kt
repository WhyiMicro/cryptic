package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * The line to walk on each floor of the ice fill.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). Each of the
 * three floors is one of a handful of patterns, told apart by a pair of
 * positions: one that is air in this pattern and one that is not. Once the
 * pattern is known the route across it is a list of positions, drawn as a line
 * on the ice.
 *
 * The optimised patterns are the same floors walked in a shorter order — they
 * are quicker and easier to fall off, which is why they are a choice rather
 * than the default.
 */
object IceFillSolver {
	private const val PUZZLE = "Ice Fill"

	private val route = mutableListOf<Vec3>()

	fun onRoomEnter(room: DungeonRoom) {
		if (room.data?.name != PUZZLE) {
			reset()
			return
		}
		if (route.isNotEmpty()) return

		val level = Minecraft.getInstance().level ?: return
		if (!room.resolveRotation(level)) return

		PuzzleAssets.ensureLoaded()
		val data = PuzzleAssets.iceFill
		val patterns = if (PuzzleSolver.iceFillOptimized.value) data.hard else data.easy
		if (data.identifier.isEmpty()) return

		repeat(3) { floor ->
			val identifiers = data.identifier.getOrNull(floor) ?: return@repeat
			for (index in identifiers.indices) {
				val marks = identifiers[index]
				if (marks.size < 2) continue
				if (!isAir(room, level, marks[0]) || isAir(room, level, marks[1])) continue

				patterns.getOrNull(floor)?.getOrNull(index)?.forEach { step ->
					val real = room.getRealCoords(step) ?: return@forEach
					route.add(Vec3(real.x + 0.5, real.y + 0.1, real.z + 0.5))
				}
				return@repeat
			}
		}
	}

	private fun isAir(room: DungeonRoom, level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
		val real = room.getRealCoords(pos) ?: return false
		return level.getBlockState(real).isAir
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.iceFillEnabled.value || route.isEmpty()) return
		if (!PuzzleRooms.inside(PUZZLE)) return
		PuzzleRender.line(context, route, PuzzleSolver.iceFillColor.argb, phase = false)
	}

	fun reset() = route.clear()
}
