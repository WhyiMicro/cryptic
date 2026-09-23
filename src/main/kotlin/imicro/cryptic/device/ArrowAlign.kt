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
 * The arrow device: twenty-five item frames that have to be turned until the
 * arrows spell out one of nine shapes.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). Each frame holds an
 * arrow at one of eight rotations and a right click turns it one step
 * clockwise, so the whole puzzle is "how many clicks from here to there" —
 * except that nobody is told which of the nine shapes is being asked for. That
 * is worked out from the frames that are *empty*: every shape leaves a
 * different set of squares blank, so the blanks alone name the shape.
 */
object ArrowAlign {
	/** The bottom-left corner of the grid, which the whole solve is indexed off. */
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

	/** No frame here, in a shape; also the reading for a square with no frame. */
	private const val EMPTY = -1

	/**
	 * How long a clicked frame is trusted over what the world says.
	 *
	 * A click is answered by the server, and until that answer lands the frame
	 * still reads at its old rotation. Predicting the turn locally and holding
	 * the prediction for a second is what stops the count flickering back up
	 * and being clicked one step too far.
	 */
	private const val PREDICTION_HOLD_MILLIS = 1000L

	/** Beyond this the device is out of sight and not worth scanning for. */
	private const val SCAN_RANGE_SQUARED = 200.0

	/** How far up its frame a count sits, as NoammAddons places it. */
	private const val LABEL_HEIGHT = 0.55

	private val clickedAt = HashMap<Int, Long>()

	/** Clicks each square still wants, by index; a square not in it is done. */
	private val remaining = LinkedHashMap<Int, Int>()

	private val rotations = IntArray(CELLS) { EMPTY }
	private var solution: List<Int>? = null

	/** What the render pass draws: each unfinished square and its click count. */
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
			// A frame clicked a moment ago is left at the rotation the click
			// predicted, because the server has not answered it yet.
			if (now - (clickedAt[index] ?: 0L) > PREDICTION_HOLD_MILLIS) rotations[index] = frame.rotation
		}

		solution = null
		remaining.clear()

		val shape = SHAPES.firstOrNull { shape ->
			// The blank squares have to line up exactly: a square with a frame
			// can be turned to anything, but one without cannot be filled in.
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
	 * True when a right click on [pos] should be swallowed rather than sent.
	 *
	 * Only ever says yes for a square the shape does not want turned any
	 * further, and crouching turns it off — the same escape hatch NoammAddons
	 * leaves, because the solver being wrong must never mean the device cannot
	 * be played by hand.
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

	/**
	 * A click that is going out, applied to the board here and now.
	 *
	 * The frame is one step further round as far as this is concerned from the
	 * moment the click leaves, so the count on screen answers the hand rather
	 * than the connection.
	 */
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
	 * Where square [index]'s count is written, which is NoammAddons' anchor.
	 *
	 * X gets no half-block offset. The frames hang on the west wall and their
	 * faces are at the low edge of the block they occupy, so the whole number
	 * of the corner *is* the surface — half a block further out and the count
	 * floats in the room in front of the frame it belongs to. NoammAddons lands
	 * on the same place by accident: its own offset helper takes a `Number` and
	 * truncates it to an int, so the 0.5 it passes for X becomes 0.
	 *
	 * The other two are real offsets: centred across the frame, and a little
	 * above its middle so the arrow underneath stays readable.
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

	/**
	 * The grid runs up the wall and along it, so height is the row and depth
	 * the column. Anything off the wall or outside the five-by-five is null.
	 */
	private fun indexOf(pos: BlockPos): Int? {
		if (pos.x != GRID_CORNER.x) return null
		val index = (pos.y - GRID_CORNER.y) + (pos.z - GRID_CORNER.z) * 5
		return index.takeIf { it in 0 until CELLS }
	}

	/** Clicks from one rotation to another, which only ever turn one way. */
	private fun clicksBetween(current: Int, target: Int): Int =
		if (target == EMPTY) 0 else (ROTATIONS - current + target) % ROTATIONS

	/**
	 * The nine shapes the device can ask for, as NoammAddons records them: one
	 * rotation per square, and [EMPTY] where that square has no frame at all.
	 */
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
