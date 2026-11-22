package imicro.cryptic.puzzle.water

/**
 * One Water Board's water plane, in room coordinates: z = 26, the board proper at x 5..25 and
 * y 57..80, the inlet column above x = 15 up to the source at y = 82, all inside a ring of wall so
 * every cell has four neighbours. Pure: no Minecraft, so the simulation can be tested.
 *
 * Built from rows of characters ([parse]), which the room's blocks give in game.
 */
class WaterBoard private constructor(
	/** Per cell: water can be there when no lever's block is. */
	val open: BooleanArray,
	/** Per lever block on the board: its cell. */
	val slotCell: IntArray,
	/** Per lever block on the board: the [WaterLever] that moves it. */
	val slotLever: IntArray,
	/** The world direction of room +x, which decides the order 1.8 visits neighbours in. */
	val plusX: Facing,
) {
	/** The four horizontal directions, as 1.8 names them. */
	enum class Facing { NORTH, EAST, SOUTH, WEST;
		val opposite: Facing get() = entries[(ordinal + 2) % 4]
	}

	val size = WIDTH * HEIGHT
	val source = cell(15, SOURCE_Y)
	val outletCell = IntArray(OUTLET_X.size) { cell(OUTLET_X[it], OUTLET_Y) }

	/** Room ±x as cell steps, in the order 1.8's horizontal plane iterates (N, E, S, W). */
	val horizontal: IntArray = order(listOf(Facing.NORTH, Facing.EAST, Facing.SOUTH, Facing.WEST))

	/** The order water spreads to the sides it picked: an EnumSet's (N, S, W, E). */
	val spread: IntArray = order(listOf(Facing.NORTH, Facing.SOUTH, Facing.WEST, Facing.EAST))

	/**
	 * The order a block change wakes its neighbours: W, E, down, up, N, S, less the two that are
	 * glass and wall.
	 */
	val wake: IntArray = listOf("W", "E", "D", "U", "N", "S").mapNotNull { name ->
		when (name) {
			"D" -> -WIDTH
			"U" -> WIDTH
			else -> {
				val facing = Facing.entries.first { it.name.startsWith(name) }
				if (facing == plusX) 1 else if (facing == plusX.opposite) -1 else null
			}
		}
	}.toIntArray()

	private fun order(facings: List<Facing>): IntArray {
		val plusFirst = facings.indexOf(plusX) < facings.indexOf(plusX.opposite)
		return if (plusFirst) intArrayOf(1, -1) else intArrayOf(-1, 1)
	}

	/** The same board, turned so room +x faces [facing]. */
	fun facing(facing: Facing) = WaterBoard(open, slotCell, slotLever, facing)

	/** The slot at [cell], or -1. */
	fun slotAt(cell: Int): Int = slotCell.indexOf(cell)

	companion object {
		/** The plane: x 4..26, y 56..83, so the board proper has a ring of wall around it. */
		const val X0 = 4
		const val Y0 = 56
		const val WIDTH = 23
		const val HEIGHT = 28
		const val PLANE_Z = 26

		/** The rows [parse] reads: x 5..25, y 80 down to 57. */
		const val ROW_X0 = 5
		const val ROW_X1 = 25
		const val ROW_TOP = 80
		const val ROW_BOTTOM = 57

		const val SOURCE_Y = 82
		const val OUTLET_Y = 58

		/** Outlet pits, in gate order: purple, orange, blue, green, red. */
		val OUTLET_X = intArrayOf(24, 20, 15, 10, 6)

		fun cell(x: Int, y: Int): Int = (y - Y0) * WIDTH + (x - X0)
		fun x(cell: Int): Int = cell % WIDTH + X0
		fun y(cell: Int): Int = cell / WIDTH + Y0

		/**
		 * A board from its rows, top (y = 80) first, x 5..25: '#' wall, '.' open, a lever's
		 * letter for its block ([WaterLever.letter]), upper case in the board and lower case
		 * pulled back. Returns the board and which slots are in.
		 */
		fun parse(rows: List<String>, plusX: Facing): Pair<WaterBoard, BooleanArray> {
			require(rows.size == ROW_TOP - ROW_BOTTOM + 1) { "a water board has ${ROW_TOP - ROW_BOTTOM + 1} rows" }
			require(rows.all { it.length == ROW_X1 - ROW_X0 + 1 }) { "a water board row is ${ROW_X1 - ROW_X0 + 1} wide" }
			val open = BooleanArray(WIDTH * HEIGHT)
			val slots = ArrayList<Int>()
			val levers = ArrayList<Int>()
			val inside = ArrayList<Boolean>()
			rows.forEachIndexed { row, text ->
				val y = ROW_TOP - row
				text.forEachIndexed { column, char ->
					val x = ROW_X0 + column
					val lever = WaterLever.byLetter(char)
					when {
						// Above y = 78 only the inlet column joins the board's water.
						char == '.' -> if (y <= 78 || x == 15) open[cell(x, y)] = true
						lever != null -> {
							slots += cell(x, y)
							levers += lever.ordinal
							inside += char.isUpperCase()
						}
					}
				}
			}
			for (y in 79 until SOURCE_Y) open[cell(15, y)] = true
			return WaterBoard(open, slots.toIntArray(), levers.toIntArray(), plusX) to inside.toBooleanArray()
		}
	}
}

/**
 * The seven levers, in Cryptic's water solver order, with where each stands in the room and the
 * letter its block has in a board's rows. [key] names it in Odin's answer sheet.
 */
enum class WaterLever(val letter: Char, val x: Int, val y: Int, val z: Int, val key: String) {
	COAL('C', 20, 61, 10, "coal_block"),
	GOLD('G', 20, 61, 15, "gold_block"),
	QUARTZ('Q', 20, 61, 20, "quartz_block"),
	DIAMOND('D', 10, 61, 20, "diamond_block"),
	EMERALD('E', 10, 61, 15, "emerald_block"),
	CLAY('T', 10, 61, 10, "hardened_clay"),
	WATER('W', 15, 60, 5, "water");

	val bit: Int get() = 1 shl ordinal

	companion object {
		const val WATER_BIT = 1 shl 6
		fun byLetter(char: Char): WaterLever? = entries.firstOrNull { it != WATER && it.letter == char.uppercaseChar() }
	}
}
