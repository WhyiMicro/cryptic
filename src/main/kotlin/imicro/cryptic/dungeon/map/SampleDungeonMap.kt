package imicro.cryptic.dungeon.map

/**
 * A stand-in dungeon map item, painted like Hypixel's, so `/cryptic debug map` can show a
 * believable map anywhere.
 */
object SampleDungeonMap {
	private const val SIZE = 128
	private const val START = 11
	private const val ROOM = 16
	private const val STRIDE = ROOM + 4

	/** Room colours on the item, by tile. */
	private const val ENTRANCE = 30
	private const val NORMAL = 63
	private const val PUZZLE = 66
	private const val TRAP = 62
	private const val CHAMPION = 74
	private const val BLOOD = 18
	private const val FAIRY = 82
	private const val UNKNOWN = 85

	/** State colours, stamped in a room's middle. */
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

	/** A 1×2 room, to check that joined tiles read as one. */
	private val joined = listOf(Tile(1, 2, NORMAL, CLEARED), Tile(0, 2, NORMAL, CLEARED))

	fun colors(): ByteArray {
		val colors = ByteArray(SIZE * SIZE)

		(layout + joined).forEach { tile ->
			fill(colors, tile.x, tile.z, tile.type)
			tile.state?.let { stamp(colors, tile.x, tile.z, it) }
		}

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
