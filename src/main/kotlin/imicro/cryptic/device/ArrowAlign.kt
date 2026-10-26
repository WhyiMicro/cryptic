package imicro.cryptic.device

import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.feature.DeviceSolver
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The arrow device: 25 item frames to turn until they match one of nine shapes. Which shape is
 * wanted is worked out from the frames that are missing, since each shape leaves different squares
 * empty. Ported from NoammAddons.
 */
object ArrowAlign {
	private val GRID_CORNER = BlockPos(-2, 120, 75)

	private val GRID_BOX = AABB(
		GRID_CORNER.x.toDouble(),
		GRID_CORNER.y.toDouble(),
		GRID_CORNER.z.toDouble(),
		GRID_CORNER.x + 1.0,
		GRID_CORNER.y + 5.0,
		GRID_CORNER.z + 5.0,
	)

	private const val CELLS = 25
	private const val ROTATIONS = 8

	/** No frame on this square. */
	private const val EMPTY = -1

	/**
	 * A clicked frame is trusted over the world for this long, until the server catches up, so the
	 * count doesn't flicker back.
	 */
	private const val PREDICTION_HOLD_MILLIS = 1000L

	private const val SCAN_RANGE_SQUARED = 200.0

	private const val LABEL_HEIGHT = 0.55

	private val clickedAt = HashMap<Int, Long>()

	/** Clicks each square still needs, by index; finished squares are absent. */
	private val remaining = LinkedHashMap<Int, Int>()

	private val rotations = IntArray(CELLS) { EMPTY }
	private var solution: List<Int>? = null

	val clicksRemaining: Map<Int, Int> get() = remaining

	fun tick(client: Minecraft) {
		val level = client.level
		val player = client.player
		if (level == null || player == null || !DeviceSolver.arrowAlignEnabled) return reset()
		if (!Floor7.inGoldor) return reset()
		if (player.distanceToSqr(Vec3.atCenterOf(GRID_CORNER)) > SCAN_RANGE_SQUARED) return reset()

		val now = System.currentTimeMillis()
		for (frame in level.getEntitiesOfClass(ItemFrame::class.java, GRID_BOX) { it.item.item == Items.ARROW }) {
			val index = indexOf(frame.blockPosition()) ?: continue
			if (now - (clickedAt[index] ?: 0L) > PREDICTION_HOLD_MILLIS) rotations[index] = frame.rotation
		}

		solution = null
		remaining.clear()

		val shape = SHAPES.firstOrNull { shape ->
			// The empty squares must match exactly; a frame can be turned to anything, a missing
			// one can't be added.
			shape.indices.none { (shape[it] == EMPTY || rotations[it] == EMPTY) && shape[it] != rotations[it] }
		} ?: return

		solution = shape
		for (index in shape.indices) {
			if (rotations[index] == EMPTY) continue
			val needed = clicksBetween(rotations[index], shape[index])
			if (needed != 0) remaining[index] = needed
		}
	}

	/**
	 * Whether to swallow a right click on [pos]: only for squares that are already right, and never
	 * while crouching, so the device can always be done by hand.
	 */
	fun blocksClick(pos: BlockPos): Boolean {
		if (!DeviceSolver.arrowAlignEnabled || !Floor7.inGoldor) return false
		val index = indexOf(pos) ?: return false
		if (rotations[index] == EMPTY) return false
		if (index in remaining) return false

		val crouching = Minecraft.getInstance().player?.isCrouching == true
		return DeviceSolver.arrowBlockWrongClicks.value &&
			crouching == DeviceSolver.arrowInvertSneak.value
	}

	/** Applies a click locally as it goes out, so the count follows your clicks rather than the server. */
	fun onClick(pos: BlockPos) {
		if (!DeviceSolver.arrowAlignEnabled || !Floor7.inGoldor) return
		val index = indexOf(pos) ?: return
		if (rotations[index] == EMPTY) return

		clickedAt[index] = System.currentTimeMillis()
		rotations[index] = (rotations[index] + 1) % ROTATIONS

		val target = solution?.get(index) ?: return
		val needed = clicksBetween(rotations[index], target)
		if (needed == 0) remaining.remove(index) else remaining[index] = needed
	}

	/**
	 * Where square [index]'s count is drawn. No half-block on X: the frames sit on the low edge of
	 * their block.
	 */
	fun labelAnchor(index: Int): Triple<Double, Double, Double> = Triple(
		GRID_CORNER.x.toDouble(),
		GRID_CORNER.y + (index % 5) + LABEL_HEIGHT,
		GRID_CORNER.z + (index / 5) + 0.5,
	)

	fun reset() {
		if (solution == null && remaining.isEmpty() && clickedAt.isEmpty() && rotations.all { it == EMPTY }) return
		rotations.fill(EMPTY)
		solution = null
		remaining.clear()
		clickedAt.clear()
	}

	/** The grid runs up and along the wall: height is the row, depth the column. */
	private fun indexOf(pos: BlockPos): Int? {
		if (pos.x != GRID_CORNER.x) return null
		val index = (pos.y - GRID_CORNER.y) + (pos.z - GRID_CORNER.z) * 5
		return index.takeIf { it in 0 until CELLS }
	}

	private fun clicksBetween(current: Int, target: Int): Int =
		if (target == EMPTY) 0 else (ROTATIONS - current + target) % ROTATIONS

	/** The nine shapes: a rotation per square, or [EMPTY] where there is no frame. */
	private val SHAPES = listOf(
		listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1),
		listOf(-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1),
		listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3),
		listOf(5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1),
		listOf(5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
		listOf(7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
		listOf(-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
		listOf(-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1),
		listOf(-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
	)
}
