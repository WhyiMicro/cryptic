package imicro.cryptic.crosshair

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A crosshair as a square of painted cells.
 *
 * Each cell holds a palette index: zero is empty, one upwards is a colour. An
 * index rather than a colour so that changing a palette entry recolours every
 * cell painted with it at once — which is how anybody editing a two-tone
 * crosshair expects it to behave.
 *
 * ## The centre
 *
 * Every size offered is even, because those are the sizes crosshair textures
 * come in. An even square has no middle pixel, and a crosshair with no middle
 * pixel cannot have a one-pixel-wide line through its centre. The convention
 * resource packs use settles it: the cell at `size / 2` is the centre, the
 * crosshair is drawn with that cell over the middle of the screen, and the
 * first row and column are a spare margin with nothing mirrored into them.
 * Vanilla's own 15×15 crosshair fits a 16 grid exactly this way.
 */
class CrosshairGrid(size: Int) {
	var size: Int = size
		private set

	private var cells = IntArray(size * size)

	/** The cell over the middle of the screen, on both axes. */
	val centre: Int get() = size / 2

	/** Bumped on every change, so a drawing cache knows when to rebuild. */
	var revision = 0
		private set

	operator fun get(x: Int, y: Int): Int =
		if (x in 0 until size && y in 0 until size) cells[y * size + x] else 0

	operator fun set(x: Int, y: Int, value: Int) {
		if (x !in 0 until size || y !in 0 until size) return
		val index = y * size + x
		if (cells[index] == value) return
		cells[index] = value
		revision++
	}

	fun clear() {
		cells.fill(0)
		revision++
	}

	fun isEmpty(): Boolean = cells.all { it == 0 }

	fun snapshot(): IntArray = cells.copyOf()

	fun restore(snapshot: IntArray, snapshotSize: Int) {
		size = snapshotSize
		cells = snapshot.copyOf()
		revision++
	}

	/**
	 * The mirror of a coordinate about the centre cell, or -1 where it falls off.
	 *
	 * It falls off for exactly one row and column — the spare margin — which is
	 * why a mirrored stroke there only ever paints one side.
	 */
	fun mirror(coordinate: Int): Int {
		val mirrored = centre * 2 - coordinate
		return if (mirrored in 0 until size) mirrored else -1
	}

	/**
	 * Changes the size, keeping the drawing centred.
	 *
	 * Growing pads around it and shrinking crops the edges, both about the
	 * centre cell — so a crosshair survives a size change looking like itself
	 * rather than sliding into a corner.
	 */
	fun resize(newSize: Int) {
		if (newSize == size) return
		val oldCells = cells
		val oldSize = size
		val shift = newSize / 2 - oldSize / 2

		size = newSize
		cells = IntArray(newSize * newSize)
		for (y in 0 until oldSize) {
			for (x in 0 until oldSize) {
				val value = oldCells[y * oldSize + x]
				if (value == 0) continue
				val nx = x + shift
				val ny = y + shift
				if (nx in 0 until newSize && ny in 0 until newSize) cells[ny * newSize + nx] = value
			}
		}
		revision++
	}

	/**
	 * Paints every connected cell of one colour, for the bucket tool.
	 *
	 * Iterative rather than recursive: a 64×64 grid is four thousand cells, and
	 * a flood that recurses once per cell is a stack overflow waiting to happen.
	 */
	fun fill(x: Int, y: Int, value: Int) {
		val target = this[x, y]
		if (target == value || x !in 0 until size || y !in 0 until size) return

		val stack = ArrayDeque<Int>()
		stack.addLast(y * size + x)
		while (stack.isNotEmpty()) {
			val index = stack.removeLast()
			if (cells[index] != target) continue
			cells[index] = value
			val cx = index % size
			val cy = index / size
			if (cx > 0) stack.addLast(index - 1)
			if (cx < size - 1) stack.addLast(index + 1)
			if (cy > 0) stack.addLast(index - size)
			if (cy < size - 1) stack.addLast(index + size)
		}
		revision++
	}

	/**
	 * The grid as one digit per cell, for the profile.
	 *
	 * Row by row, top left first. A 64 grid is 4096 characters, which is large
	 * for a setting and nothing for a file — and plain enough that a profile can
	 * still be read and diffed by hand.
	 */
	fun encode(): String {
		val builder = StringBuilder(cells.size)
		for (value in cells) builder.append(Character.forDigit(value, RADIX))
		return builder.toString()
	}

	companion object {
		const val RADIX = 16

		val SIZES = listOf(16, 24, 32, 48, 64)

		/**
		 * A grid from what [encode] wrote, re-centred into [size].
		 *
		 * The size the string was written at is worked out from its length, not
		 * trusted to match: the size setting can be changed from the module card
		 * without the editor ever opening, and the drawing has to follow it.
		 */
		fun decode(text: String, size: Int): CrosshairGrid {
			val stored = sqrt(text.length.toDouble()).roundToInt()
			if (stored <= 0 || stored * stored != text.length) return CrosshairGrid(size)

			val grid = CrosshairGrid(stored)
			for (index in text.indices) {
				val value = Character.digit(text[index], RADIX).coerceAtLeast(0)
				if (value != 0) grid[index % stored, index / stored] = value
			}
			grid.resize(size)
			return grid
		}
	}
}

/**
 * Ready-made shapes, drawn about the centre cell at whatever size the grid is.
 *
 * Generated rather than stored, so every one of them works at every size and
 * stays one pixel thick.
 */
enum class CrosshairPreset(val title: String) {
	/** Vanilla's own: a 9×9 plus, read straight off `hud/crosshair.png`. */
	VANILLA("Vanilla"),
	DOT("Dot"),
	PLUS("Plus"),
	GAP_PLUS("Gap plus"),
	CIRCLE("Circle"),
	CROSS("X"),
	;

	fun paint(grid: CrosshairGrid, colour: Int) {
		grid.clear()
		val c = grid.centre
		val reach = c - 1

		when (this) {
			VANILLA -> {
				for (d in -4..4) {
					grid[c + d, c] = colour
					grid[c, c + d] = colour
				}
			}
			DOT -> {
				grid[c, c] = colour
			}
			PLUS -> {
				val arm = maxOf(3, reach / 2)
				for (d in -arm..arm) {
					grid[c + d, c] = colour
					grid[c, c + d] = colour
				}
			}
			GAP_PLUS -> {
				val gap = maxOf(2, reach / 6)
				val arm = maxOf(gap + 3, reach / 2)
				grid[c, c] = colour
				for (d in gap..arm) {
					grid[c + d, c] = colour
					grid[c - d, c] = colour
					grid[c, c + d] = colour
					grid[c, c - d] = colour
				}
			}
			CIRCLE -> {
				val radius = maxOf(3.0, reach * 0.45)
				// A ring one cell thick: a cell is on it when its centre is
				// within half a cell of the radius.
				for (y in 0 until grid.size) {
					for (x in 0 until grid.size) {
						if (abs(hypot((x - c).toDouble(), (y - c).toDouble()) - radius) < 0.5) grid[x, y] = colour
					}
				}
				grid[c, c] = colour
			}
			CROSS -> {
				val arm = maxOf(3, reach / 2)
				for (d in -arm..arm) {
					grid[c + d, c + d] = colour
					grid[c + d, c - d] = colour
				}
			}
		}
	}
}
