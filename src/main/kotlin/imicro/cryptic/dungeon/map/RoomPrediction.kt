package imicro.cryptic.dungeon.map

import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.feature.DungeonMap

/**
 * Guesses what an unopened room must be. Every unopened room is the same grey, but a floor has
 * exactly one miniboss and one trap, a known number of puzzles, and puzzles packed into one column
 * of single-tile rooms. Counting what's open against what must exist often leaves one possibility.
 * Only meaningful while exploring. Ported from dtMap.
 */
object RoomPrediction {
	var column = -1

	private var discoveredBeforeColumn = 0

	private var columnRoomCount = 0

	private var known1x1s = 0

	private val opened1x1s = mutableSetOf<DungeonRoom>()

	fun reset() {
		column = -1
		discoveredBeforeColumn = 0
		columnRoomCount = 0
		known1x1s = 0
		opened1x1s.clear()
	}

	/** Some floors fix the puzzle column's position. */
	fun applyFloorLayout(floor: Int, size: Vec2i) {
		column = if ((floor == 5 || floor == 6) && size.x == 6 && size.z == 6) {
			5
		} else if (floor == 4 && size.x == 6 && size.z == 5) {
			5
		} else {
			-1
		}
	}

	/** Re-reads the floor and writes each shut one-tile room's possibilities onto it. */
	fun update() {
		val size = DungeonFloor.size

		for (x in 0 until size.x) {
			for (z in 0 until size.z) {
				val room = DungeonFloor.roomAt(Vec2i(x, z)) ?: continue
				if (room.tiles.size != 1) continue
				if (room.type == DungeonRoom.Type.BLOOD || room.type == DungeonRoom.Type.ENTRANCE) continue

				if (room.unopened) {
					if (x == column) {
						if (!room.isKnown1x1) {
							room.isKnown1x1 = true
							known1x1s++
							columnRoomCount++
						}
						continue
					}

					// Only certainly one tile once every tile it could extend into is something
					// else.
					if (x != size.x - 1 && x + 1 != column && !isSeen(x + 1, z)) continue
					if (x != 0 && !isSeen(x - 1, z)) continue
					if (z != size.z - 1 && !isSeen(x, z + 1)) continue
					if (z != 0 && !isSeen(x, z - 1)) continue

					if (!room.isKnown1x1) {
						room.isKnown1x1 = true
						known1x1s++
					}
				} else if (!room.hidden &&
					(room.type == DungeonRoom.Type.CHAMPION ||
						room.type == DungeonRoom.Type.TRAP ||
						room.type == DungeonRoom.Type.PUZZLE)
				) {
					opened1x1s.add(room)
				}
			}
		}

		// Every puzzle plus the miniboss and trap are accounted for: the column has nothing left.
		if (known1x1s == DungeonStats.puzzleCount + 2) {
			discoveredBeforeColumn = size.z
		} else if (column != -1) {
			var discovered = size.z
			for (z in 0 until size.z) {
				val room = DungeonFloor.roomAt(Vec2i(column - 1, z)) ?: continue
				if (room.isKnown1x1) continue
				if ((room.unopened && room.type != DungeonRoom.Type.BLOOD) || room.hidden) discovered--
			}
			discoveredBeforeColumn = discovered
		}

		val wanted = DungeonMap.predictRooms.value
		DungeonFloor.rooms.forEach { room ->
			room.guess = if (wanted && room.unopened && room.isKnown1x1) guessFor(room) else emptyList()
		}
	}

	private fun isSeen(x: Int, z: Int): Boolean {
		val room = DungeonFloor.roomAt(Vec2i(x, z)) ?: return false
		if (room.hidden) return false
		if (room.unopened && Vec2i(x, z) != room.entryTile) return false
		return true
	}

	/**
	 * The types a shut one-tile room could still be, best first (dtMap's counting: yellow is
	 * generated before orange, the column holds the puzzles, miniboss and trap exist once each).
	 */
	private fun guessFor(room: DungeonRoom): List<DungeonRoom.Type> {
		val puzzleCount = DungeonStats.puzzleCount
		val columnRooms = if (column != -1 && columnRoomCount == 0) 1 else columnRoomCount

		if (room.specialTile) {
			val remaining = DungeonFloor.size.z - discoveredBeforeColumn + columnRooms
			return when {
				remaining <= puzzleCount || known1x1s - columnRooms >= 2 ->
					listOf(DungeonRoom.Type.PUZZLE)
				opened1x1s.any { it.type == DungeonRoom.Type.TRAP && !it.specialTile } ->
					listOf(DungeonRoom.Type.PUZZLE)
				remaining == puzzleCount + 1 ->
					listOf(DungeonRoom.Type.TRAP, DungeonRoom.Type.PUZZLE)
				known1x1s - columnRooms == 1 ->
					listOf(DungeonRoom.Type.TRAP, DungeonRoom.Type.PUZZLE)
				opened1x1s.any { it.type == DungeonRoom.Type.CHAMPION } ->
					listOf(DungeonRoom.Type.CHAMPION, DungeonRoom.Type.TRAP)
				else ->
					listOf(DungeonRoom.Type.CHAMPION, DungeonRoom.Type.TRAP, DungeonRoom.Type.PUZZLE)
			}
		}

		val outsidePuzzles = opened1x1s.count { it.type == DungeonRoom.Type.PUZZLE && !it.specialTile }
		if (columnRooms + outsidePuzzles == puzzleCount) {
			return when {
				opened1x1s.any { it.type == DungeonRoom.Type.TRAP } -> listOf(DungeonRoom.Type.CHAMPION)
				opened1x1s.any { it.type == DungeonRoom.Type.CHAMPION } -> listOf(DungeonRoom.Type.TRAP)
				else -> listOf(DungeonRoom.Type.CHAMPION, DungeonRoom.Type.TRAP)
			}
		}

		// The miniboss is generated before the trap, so a column one longer than the puzzles spends
		// the extra on the miniboss.
		if (columnRooms == puzzleCount + 1) return listOf(DungeonRoom.Type.CHAMPION)

		if (opened1x1s.count { it.type == DungeonRoom.Type.CHAMPION || it.type == DungeonRoom.Type.TRAP } == 2) {
			return listOf(DungeonRoom.Type.PUZZLE)
		}

		if (opened1x1s.size == puzzleCount + 1) {
			if (opened1x1s.none { it.type == DungeonRoom.Type.TRAP }) return listOf(DungeonRoom.Type.TRAP)
			if (opened1x1s.none { it.type == DungeonRoom.Type.CHAMPION }) return listOf(DungeonRoom.Type.CHAMPION)
		}

		return buildList {
			add(DungeonRoom.Type.PUZZLE)
			if (opened1x1s.none { it.type == DungeonRoom.Type.TRAP }) add(DungeonRoom.Type.TRAP)
			if (opened1x1s.none { it.type == DungeonRoom.Type.CHAMPION }) add(DungeonRoom.Type.CHAMPION)
		}
	}
}
