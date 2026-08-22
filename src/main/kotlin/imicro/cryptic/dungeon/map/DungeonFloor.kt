package imicro.cryptic.dungeon.map

/**
 * The floor as Cryptic currently understands it, whoever told it what.
 *
 * Two sources describe the same dungeon and neither is complete on its own:
 * the world scan ([DungeonWorldScan]) knows every room's name and shape as soon
 * as its chunks load, but nothing about progress, while the map item
 * ([DungeonMapReader]) knows exactly how far each room has been cleared but
 * only names it "brown". So the floor is kept here and written into by both,
 * rather than rebuilt from either — which is also what lets a room be marked
 * entered the moment you walk in, ahead of Hypixel saying so.
 *
 * The grid is always Hypixel's 6x6 of 32-block tiles starting at world
 * (-185, -185); [size] is only how much of it this floor uses.
 */
object DungeonFloor {
	const val GRID = 6

	/** The world coordinate of the middle of grid tile (0, 0). */
	const val WORLD_TOP_LEFT = -185

	const val BLOCKS_PER_TILE = 32

	private val owners = arrayOfNulls<DungeonRoom>(GRID * GRID)

	private val roomList = mutableListOf<DungeonRoom>()
	private val doorList = mutableListOf<DungeonDoor>()

	val rooms: List<DungeonRoom> get() = roomList
	val doors: List<DungeonDoor> get() = doorList

	/** How many tiles wide and tall this floor is, which sets the drawn size. */
	var size: Vec2i = Vec2i(GRID, GRID)
		private set

	/** True once the map item has said how big the floor is, which settles it. */
	private var sizeSettled = false

	/** True once anything at all is known, which is when the map is worth drawing. */
	val loaded: Boolean get() = roomList.isNotEmpty()

	fun reset() {
		owners.fill(null)
		roomList.clear()
		doorList.clear()
		size = Vec2i(GRID, GRID)
		sizeSettled = false
	}

	/** The map item is the last word on how big a floor is. */
	fun settleSize(measured: Vec2i) {
		size = measured
		sizeSettled = true
	}

	/**
	 * The size a floor number implies, used until the map item arrives.
	 *
	 * The first few floors are smaller than the grid, and drawing them 6x6 puts
	 * a band of empty background down two sides of the map for as long as it
	 * takes the run to start.
	 */
	fun assumeSize(floor: Int) {
		if (sizeSettled) return
		size = when (floor) {
			0 -> Vec2i(4, 4)
			1 -> Vec2i(4, 5)
			2, 3 -> Vec2i(5, 5)
			else -> Vec2i(GRID, GRID)
		}
		RoomPrediction.applyFloorLayout(floor, size)
	}

	private fun index(tile: Vec2i): Int = tile.x * GRID + tile.z

	private fun inBounds(tile: Vec2i): Boolean =
		tile.x in 0 until GRID && tile.z in 0 until GRID

	fun roomAt(tile: Vec2i): DungeonRoom? =
		if (inBounds(tile)) owners[index(tile)] else null

	/** The tile a world position falls in, whether or not a room is there. */
	fun tileOf(x: Double, z: Double): Vec2i =
		Vec2i(
			Math.floorDiv(x.toInt() + 201, BLOCKS_PER_TILE),
			Math.floorDiv(z.toInt() + 201, BLOCKS_PER_TILE),
		)

	/**
	 * Puts one room over [tiles], absorbing whatever was there before.
	 *
	 * The two sources arrive in either order and disagree about how much of a
	 * room they can see, so claiming a tile has to be able to join two rooms
	 * that turn out to be one — a 1x3 read off the map item one tile at a time
	 * is exactly that case.
	 */
	fun claim(tiles: List<Vec2i>, type: DungeonRoom.Type, shape: DungeonRoom.Shape): DungeonRoom {
		val existing = tiles.mapNotNull(::roomAt).distinct()

		val room = existing.firstOrNull() ?: DungeonRoom(type, shape).also { roomList.add(it) }

		existing.drop(1).forEach { other ->
			other.tiles.forEach(room::addTile)
			room.doors.addAll(other.doors)
			doorList.forEach { door ->
				if (door.rooms.remove(other)) door.rooms.add(room)
			}
			if (room.data == null) room.data = other.data
			roomList.remove(other)
		}

		tiles.forEach(room::addTile)
		room.tiles.forEach { if (inBounds(it)) owners[index(it)] = room }

		// A source that knows nothing must not overwrite one that does: the map
		// item calls an unopened room UNKNOWN, and the scan calls the same room
		// by name.
		if (type != DungeonRoom.Type.UNKNOWN && room.type == DungeonRoom.Type.UNKNOWN) room.type = type
		if (shape != DungeonRoom.Shape.UNKNOWN && room.shape == DungeonRoom.Shape.UNKNOWN) room.shape = shape

		return room
	}

	/** Gives a room its entry from `rooms.json`, and the type and shape with it. */
	fun describe(room: DungeonRoom, data: RoomData) {
		if (room.data === data) return
		room.data = data
		room.type = data.type
		if (data.shape != DungeonRoom.Shape.UNKNOWN) room.shape = data.shape
	}

	/**
	 * Returns the door between two tiles, making it if this is the first sight
	 * of it. [type] only replaces what is known when it says more: a wither door
	 * that has been opened is repainted normal by the map item, and the map
	 * should keep saying it was a wither door.
	 */
	fun door(tile: Vec2i, horizontal: Boolean, type: DungeonDoor.Type): DungeonDoor {
		val found = doorList.firstOrNull { it.tile == tile && it.horizontal == horizontal }
		val door = found ?: DungeonDoor(tile, horizontal, type).also { doorList.add(it) }

		if (found != null && type != DungeonDoor.Type.NORMAL && door.type == DungeonDoor.Type.NORMAL) {
			door.type = type
		}

		door.tiles().mapNotNull(::roomAt).forEach { room ->
			door.rooms.add(room)
			room.doors.add(door)
		}

		return door
	}

	/** Re-attaches doors to rooms after a claim moved tiles between them. */
	fun relinkDoors() {
		roomList.forEach { it.doors.clear() }
		doorList.forEach { door ->
			door.rooms.clear()
			door.tiles().mapNotNull(::roomAt).forEach { room ->
				door.rooms.add(room)
				room.doors.add(door)
			}
		}

		// A door onto the entrance is drawn in the entrance's colour, which is
		// how the way back out stays obvious once the floor fills in.
		val entranceTiles = roomList.filter { it.type == DungeonRoom.Type.ENTRANCE }.flatMap { it.tiles }
		doorList.forEach { it.markEntrance(entranceTiles) }
	}

	/** The map's size in drawn pixels, which the HUD element needs to place it. */
	fun sizeInPixels(): Vec2i =
		Vec2i(size.x * 16 + (size.x - 1) * 4, size.z * 16 + (size.z - 1) * 4)

	/**
	 * What the floor looks like right now, in words, for `/cryptic debug scan`.
	 *
	 * Whether the world scan recognised a room is otherwise only visible as a
	 * name that failed to appear, which says nothing about why.
	 */
	fun describe(): List<String> {
		val grid = size
		val lines = mutableListOf(
			"Floor ${grid.x}x${grid.z}: ${roomList.size} rooms, ${doorList.size} doors, " +
				"scan ${if (DungeonWorldScan.complete) "complete" else "still reading"}",
		)

		roomList.sortedWith(compareBy({ it.tiles.minOf { tile -> tile.z } }, { it.tiles.minOf { tile -> tile.x } }))
			.forEach { room ->
				val where = room.tiles.joinToString(" ") { "${it.x},${it.z}" }
				lines.add("  ${room.data?.name ?: "«${room.type}»"} — ${room.shape}, ${room.state} @ $where")
			}

		doorList.sortedWith(compareBy({ it.tile.z }, { it.tile.x })).forEach { door ->
			val joins = door.rooms.joinToString(" + ") { it.data?.name ?: "«${it.type}»" }.ifEmpty { "nothing" }
			lines.add(
				"  door ${door.type} @ ${door.tile.x},${door.tile.z} " +
					"${if (door.horizontal) "→" else "↓"} joins $joins",
			)
		}

		return lines
	}
}
