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
 * Creeper Beams: which two lanterns make up each beam (they sit on opposite walls). The pairs come
 * from Odin's answer sheet; only pairs with both lanterns standing are drawn, so a broken beam
 * disappears.
 */
object BeamsSolver {
	private const val PUZZLE = "Creeper Beams"

	private val pairs = ConcurrentHashMap<BlockPos, Pair<BlockPos, Int>>()

	/** Each pair's colour, kept for the visit so colours don't shift when a beam breaks. */
	private val colors = ConcurrentHashMap<BlockPos, Int>()

	/**
	 * Eight colours spread around the wheel and kept bright, so the two ends of a beam can be
	 * matched across a dim room.
	 */
	private val palette = listOf(
		0xFF2020,
		0x20FF20,
		0x4080FF,
		0xFFFF20,
		0xFF20FF,
		0x20FFFF,
		0xFF8000,
		0xFFFFFF,
	)

	/** The colour for the nth beam; past eight, the wheel is divided by the golden angle. */
	private fun colorFor(index: Int): Int {
		palette.getOrNull(index)?.let { return it }
		val hue = ((index - palette.size) * GOLDEN_ANGLE) % 1f
		return Mth.hsvToRgb(hue, 0.85f, 1f) and 0xFFFFFF
	}

	private const val GOLDEN_ANGLE = 0.618033f

	fun onRoomEnter(room: DungeonRoom) {
		// The colours stay: walking out and back in is the same pairs.
		pairs.clear()
		if (room.data?.name != PUZZLE) return
		recalculate(room)
	}

	private fun recalculate(room: DungeonRoom) {
		val level = Minecraft.getInstance().level ?: return
		if (!room.resolveRotation(level)) return

		PuzzleAssets.ensureLoaded()
		pairs.clear()

		// Counted over the pairs still standing, and each pair keeps its first colour. Odin's sheet
		// lists some beams twice (once reversed), so a pair whose lanterns are already taken is
		// skipped and pairs are keyed by their lower end.
		val taken = HashSet<BlockPos>()
		PuzzleAssets.beams.forEach { pair ->
			if (pair.size < 6) return@forEach
			val a = room.getRealCoords(BlockPos(pair[0], pair[1], pair[2])) ?: return@forEach
			val b = room.getRealCoords(BlockPos(pair[3], pair[4], pair[5])) ?: return@forEach
			if (a in taken || b in taken) return@forEach
			if (level.getBlockState(a).block != Blocks.SEA_LANTERN) return@forEach
			if (level.getBlockState(b).block != Blocks.SEA_LANTERN) return@forEach

			taken += a
			taken += b
			val (first, second) = if (a < b) a to b else b to a
			pairs[first] = second to colors.getOrPut(first) { colorFor(colors.size) }
		}
	}

	/**
	 * A lantern turning to prismarine or back is a beam breaking. Every block change in the room is
	 * checked, since both ends change together.
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

	fun reset() {
		pairs.clear()
		colors.clear()
	}
}
