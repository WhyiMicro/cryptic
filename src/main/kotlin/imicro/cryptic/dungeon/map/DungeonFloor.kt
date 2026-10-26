package imicro.cryptic.dungeon.map

/**
 * The floor as Cryptic understands it, written into by two sources. The world scan
 * ([DungeonWorldScan]) knows names and shapes as soon as chunks load; the map item
 * ([DungeonMapReader]) knows progress but not names. A room can also be marked entered the moment
 * you walk in.
 *
 * The grid is Hypixel's 6×6 of 32-block tiles from world (-185, -185); [size] is how much of it
 * this floor uses.
 */
object DungeonFloor {
	const val GRID = 6

	const val WORLD_TOP_LEFT = -185

	const val BLOCKS_PER_TILE = 32

	private val owners = arrayOfNulls<DungeonRoom>(GRID * GRID)

	private val roomList = mutableListOf<DungeonRoom>()
	private val doorList = mutableListOf<DungeonDoor>()

	val rooms: List<DungeonRoom> get() = roomList
	val doors: List<DungeonDoor> get() = doorList

	var size: Vec2i = Vec2i(GRID, GRID)
		private set

	private var sizeSettled = false

	val loaded: Boolean get() = roomList.isNotEmpty()

	fun reset() {
		owners.fill(null)
		roomList.clear()
		doorList.clear()
		size = Vec2i(GRID, GRID)
		sizeSettled = false
	}

	fun settleSize(measured: Vec2i) {
		size = measured
		sizeSettled = true
	}

	/** The size a floor number implies, until the map item says. Early floors are smaller than the grid. */
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

	fun tileOf(x: Double, z: Double): Vec2i =
		Vec2i(
			Math.floorDiv(x.toInt() + 201, BLOCKS_PER_TILE),
			Math.floorDiv(z.toInt() + 201, BLOCKS_PER_TILE),
		)

	/**
	 * Puts a room over [tiles], absorbing whatever was there. The sources arrive in either order
	 * and see different amounts, so two rooms can turn out to be one (a 1×3 read a tile at a time).
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

		// A source that knows nothing mustn't overwrite one that does.
		if (type != DungeonRoom.Type.UNKNOWN && room.type == DungeonRoom.Type.UNKNOWN) room.type = type
		if (shape != DungeonRoom.Shape.UNKNOWN && room.shape == DungeonRoom.Shape.UNKNOWN) room.shape = shape

		return room
	}

	fun describe(room: DungeonRoom, data: RoomData) {
		if (room.data === data) return
		room.data = data
		room.type = data.type
		if (data.shape != DungeonRoom.Shape.UNKNOWN) room.shape = data.shape
	}

	/**
	 * The door between two tiles, created on first sight. [type] only replaces what's known when it
	 * says more, since an opened wither door is repainted as normal.
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

	fun relinkDoors() {
		roomList.forEach { it.doors.clear() }
		doorList.forEach { door ->
			door.rooms.clear()
			door.tiles().mapNotNull(::roomAt).forEach { room ->
				door.rooms.add(room)
				room.doors.add(door)
			}
		}

		// Doors onto the entrance take its colour, so the way out stays obvious.
		val entranceTiles = roomList.filter { it.type == DungeonRoom.Type.ENTRANCE }.flatMap { it.tiles }
		doorList.forEach { it.markEntrance(entranceTiles) }
	}

	fun sizeInPixels(): Vec2i =
		Vec2i(size.x * 16 + (size.x - 1) * 4, size.z * 16 + (size.z - 1) * 4)

	/** The floor in words, for `/cryptic debug scan`. */
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
