package imicro.cryptic.dungeon.map

/**
 * A stand-in dungeon map item, painted the way Hypixel paints one.
 *
 * The real map only exists inside a run, which makes both placing the HUD and
 * checking that the reader works into things you cannot do without queueing a
 * dungeon. This paints the same pixels a floor would — a room grid with types
 * and states, doors in the gaps between them — so `/cryptic debug map` can put
 * a believable map on screen anywhere.
 */
object SampleDungeonMap {
	private const val SIZE = 128
	private const val START = 11
	private const val ROOM = 16
	private const val STRIDE = ROOM + 4

	/** Room colours as they appear on the item, by tile. */
	private const val ENTRANCE = 30
	private const val NORMAL = 63
	private const val PUZZLE = 66
	private const val TRAP = 62
	private const val CHAMPION = 74
	private const val BLOOD = 18
	private const val FAIRY = 82
	private const val UNKNOWN = 85

	/** State colours, stamped in the middle of a room. */
	private const val CLEARED = 34
	private const val GREEN = 30
	private const val UNOPENED = 85

	private class Tile(val x: Int, val z: Int, val type: Int, val state: Int?)

	private val layout = listOf(
		Tile(2, 4, ENTRANCE, null),
		Tile(2, 3, NORMAL, CLEARED),
		Tile(1, 3, NORMAL, GREEN),
		Tile(0, 3, PUZZLE, CLEARED),
		Tile(2, 2, NORMAL, CLEARED),
		Tile(3, 3, TRAP, CLEARED),
		Tile(4, 3, NORMAL, UNOPENED),
		Tile(2, 1, CHAMPION, GREEN),
		Tile(1, 1, NORMAL, CLEARED),
		Tile(0, 1, FAIRY, GREEN),
		Tile(3, 1, NORMAL, UNOPENED),
		Tile(4, 1, UNKNOWN, UNOPENED),
		Tile(2, 0, BLOOD, null),
	)

	/** A 1x2 room, to prove that joined tiles are read as one room. */
	private val joined = listOf(Tile(1, 2, NORMAL, CLEARED), Tile(0, 2, NORMAL, CLEARED))

	fun colors(): ByteArray {
		val colors = ByteArray(SIZE * SIZE)

		(layout + joined).forEach { tile ->
			fill(colors, tile.x, tile.z, tile.type)
			tile.state?.let { stamp(colors, tile.x, tile.z, it) }
		}

		// The joined pair share the gap between them, which is what tells the
		// reader they are one room rather than two that happen to touch.
		connect(colors, 0, 2, true, NORMAL)

		doors(colors)
		return colors
	}

	private fun fill(colors: ByteArray, tileX: Int, tileZ: Int, color: Int) {
		for (x in 0 until ROOM) {
			for (z in 0 until ROOM) {
				colors[index(START + tileX * STRIDE + x, START + tileZ * STRIDE + z)] = color.toByte()
			}
		}
	}

	private fun stamp(colors: ByteArray, tileX: Int, tileZ: Int, color: Int) {
		val centerX = START + tileX * STRIDE + ROOM / 2
		val centerZ = START + tileZ * STRIDE + ROOM / 2
		for (x in -1..1) {
			for (z in -1..1) {
				colors[index(centerX + x, centerZ + z)] = color.toByte()
			}
		}
	}

	/** Paints the gap between two tiles, either as a door or as one room. */
	private fun connect(colors: ByteArray, tileX: Int, tileZ: Int, horizontal: Boolean, color: Int) {
		val baseX = START + tileX * STRIDE
		val baseZ = START + tileZ * STRIDE
		for (offset in 0 until 4) {
			for (across in 0 until ROOM) {
				if (horizontal) {
					colors[index(baseX + ROOM + offset, baseZ + across)] = color.toByte()
				} else {
					colors[index(baseX + across, baseZ + ROOM + offset)] = color.toByte()
				}
			}
		}
	}

	/** A door is only the middle of a gap, leaving the rest of it bare. */
	private fun door(colors: ByteArray, tileX: Int, tileZ: Int, horizontal: Boolean, color: Int) {
		val baseX = START + tileX * STRIDE
		val baseZ = START + tileZ * STRIDE
		for (offset in 0 until 4) {
			for (across in 5 until 11) {
				if (horizontal) {
					colors[index(baseX + ROOM + offset, baseZ + across)] = color.toByte()
				} else {
					colors[index(baseX + across, baseZ + ROOM + offset)] = color.toByte()
				}
			}
		}
	}

	private fun doors(colors: ByteArray) {
		door(colors, 2, 3, false, NORMAL)
		door(colors, 2, 2, false, NORMAL)
		door(colors, 2, 1, false, BLOOD)
		door(colors, 1, 3, true, NORMAL)
		door(colors, 0, 3, true, NORMAL)
		door(colors, 2, 3, true, TRAP)
		door(colors, 3, 3, true, UNKNOWN)
		door(colors, 1, 1, true, NORMAL)
		door(colors, 0, 1, true, FAIRY)
		door(colors, 2, 1, true, UNKNOWN)
		door(colors, 3, 1, true, UNKNOWN)
		door(colors, 1, 2, false, NORMAL)
	}

	private fun index(x: Int, z: Int): Int = z * SIZE + x
}
