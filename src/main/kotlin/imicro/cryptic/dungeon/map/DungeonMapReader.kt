package imicro.cryptic.dungeon.map

import imicro.cryptic.feature.RoomAlerts
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import kotlin.jvm.optionals.getOrNull

/**
 * Reads Hypixel's dungeon map item into room progress and player markers: one block of pixels per
 * room tile, gaps for doors, decorations for players. Writes into [DungeonFloor] alongside
 * [DungeonWorldScan]. Ported from dtMap.
 */
object DungeonMapReader {
	private const val WORLD_ORIGIN = 201.0
	private const val BLOCKS_PER_TILE = 32.0
	private const val PIXELS_PER_TILE = 20.0

	private var mapId: MapId? = null
	private var roomSize: Int? = null
	private var startCoords: Vec2i? = null
	private var mapCenter: Vec2i? = null
	private var sampleLoaded = false

	var markers: List<Marker> = emptyList()
		private set

	class Marker(val mapX: Int, val mapZ: Int, val yaw: Float)

	var status: String = "no map seen yet"
		private set

	fun reset() {
		status = "no map seen yet"
		sampleLoaded = false
		mapId = null
		roomSize = null
		startCoords = null
		mapCenter = null
		markers = emptyList()
		ScoreArt.forget()
		DungeonFloor.reset()
		DungeonWorldScan.reset()
		RoomPrediction.reset()
		DungeonStats.reset()
	}

	/**
	 * A map update, checked for being the dungeon's: the only map whose pixels start with a
	 * room-sized run of entrance green. Claiming the first map seen broke as soon as an unrelated
	 * map arrived.
	 */
	/** Hands a finished run's score screen to [ScoreArt]. */
	private fun captureEndArt(packet: ClientboundMapItemDataPacket) {
		val level = Minecraft.getInstance().level ?: return
		val colors = level.getMapData(packet.mapId)?.colors ?: return
		ScoreArt.capture(colors)
		status = "score art captured"
	}

	fun accept(packet: ClientboundMapItemDataPacket) {
		val level = Minecraft.getInstance().level ?: return
		// A finished run's score screen arrives as a new map, so its id isn't checked.
		if (DungeonRun.ended) {
			captureEndArt(packet)
			return
		}

		val claimed = mapId
		if (claimed != null && claimed.id() != packet.mapId.id()) return

		val colors = level.getMapData(packet.mapId)?.colors ?: return
		if (startCoords == null && !initializeSizes(colors)) {
			status = "map ${packet.mapId.id()} is not a dungeon map"
			return
		}

		mapId = packet.mapId
		readDecorations(packet)
		read(colors)
	}

	/** Loads or clears a stand-in floor for `/cryptic debug map`. A real update overwrites it. */
	fun toggleSample(): Boolean {
		if (sampleLoaded) {
			reset()
			return false
		}

		reset()
		read(SampleDungeonMap.colors())
		sampleLoaded = true
		return true
	}

	fun read(colors: ByteArray) {
		if (startCoords == null && !initializeSizes(colors)) return

		readRooms(colors)
		readStates(colors)
		readDoors(colors)
		DungeonFloor.relinkDoors()
		RoomPrediction.update()
		status = "${DungeonFloor.rooms.size} rooms, ${DungeonFloor.doors.size} doors, tile ${roomSize}px"
	}

	/**
	 * Where the grid starts on the item and how big a tile is. The first three floors have fixed
	 * offsets; others are found from the entrance's green run, whose length is the tile size.
	 */
	private fun initializeSizes(colors: ByteArray): Boolean {
		var greenStart = -1
		var greenLength = 0
		for (i in colors.indices) {
			if (colors[i].toInt() == 30) {
				if (greenLength++ == 0) greenStart = i
			} else {
				if (greenLength >= 16) break
				greenLength = 0
			}
		}

		if (greenLength != 16 && greenLength != 18) return false

		val fixed = if (DungeonLocation.inDungeon) {
			when (DungeonLocation.floor) {
				0 -> Triple(Vec2i(22, 22), Vec2i(-137, -137), Vec2i(4, 4))
				1 -> Triple(Vec2i(22, 11), Vec2i(-137, -121), Vec2i(4, 5))
				2, 3 -> Triple(Vec2i(11, 11), Vec2i(-121, -121), Vec2i(5, 5))
				else -> null
			}
		} else {
			null
		}

		val (start, center, size) = fixed ?: run {
			val derived = Vec2i(
				(greenStart and 127) % (greenLength + 4),
				(greenStart shr 7) % (greenLength + 4),
			)
			val extra = Vec2i(if (derived.x == 5) 1 else 0, if (derived.z == 5) 1 else 0)
			Triple(
				derived,
				Vec2i(-121, -121).add(Vec2i(extra.x * 16, extra.z * 16)),
				Vec2i(5, 5).add(extra),
			)
		}

		roomSize = greenLength
		startCoords = start
		mapCenter = center
		DungeonFloor.settleSize(size)
		RoomPrediction.applyFloorLayout(DungeonLocation.floor, size)
		return true
	}

	/** Groups tiles into rooms: two tiles are one room when the pixels between them are painted. */
	private fun readRooms(colors: ByteArray) {
		val rs = roomSize ?: return
		val sc = startCoords ?: return
		val size = DungeonFloor.size
		val stride = rs + 4

		val ids = Array(size.x) { IntArray(size.z) { -1 } }
		var next = 0
		for (i in 0 until size.x) {
			for (j in 0 until size.z) {
				val index = Vec2i(i, j).multiply(stride).add(sc).mapIndex()
				if (index < colors.size && colors[index].toInt() != 0) ids[i][j] = next++
			}
		}

		var changed: Boolean
		do {
			changed = false
			for (i in 0 until size.x) {
				for (j in 0 until size.z) {
					if (ids[i][j] == -1) continue

					if (i + 1 < size.x && ids[i + 1][j] != -1 && ids[i + 1][j] != ids[i][j]) {
						val gap = sc.add(Vec2i(rs + 1 + i * stride, j * stride + 1)).mapIndex()
						if (gap < colors.size && colors[gap].toInt() != 0) {
							ids[i + 1][j] = ids[i][j]
							changed = true
						}
					}

					if (j + 1 < size.z && ids[i][j + 1] != -1 && ids[i][j + 1] != ids[i][j]) {
						val gap = sc.add(Vec2i(i * stride + 1, rs + 1 + j * stride)).mapIndex()
						if (gap < colors.size && colors[gap].toInt() != 0) {
							ids[i][j + 1] = ids[i][j]
							changed = true
						}
					}
				}
			}
		} while (changed)

		val byId = linkedMapOf<Int, MutableList<Vec2i>>()
		for (i in 0 until size.x) {
			for (j in 0 until size.z) {
				if (ids[i][j] == -1) continue
				byId.getOrPut(ids[i][j]) { mutableListOf() }.add(Vec2i(i, j))
			}
		}

		byId.values.forEach { tiles ->
			val first = tiles.first()
			val index = first.multiply(stride).add(sc).mapIndex()
			if (index >= colors.size) return@forEach

			val type = typeOf(colors[index].toInt()) ?: return@forEach
			DungeonFloor.claim(tiles, type, shapeOf(tiles, type))
		}
	}

	private fun typeOf(color: Int): DungeonRoom.Type? = when (color) {
		18 -> DungeonRoom.Type.BLOOD
		30 -> DungeonRoom.Type.ENTRANCE
		85 -> DungeonRoom.Type.UNKNOWN
		63 -> DungeonRoom.Type.NORMAL
		62 -> DungeonRoom.Type.TRAP
		66 -> DungeonRoom.Type.PUZZLE
		74 -> DungeonRoom.Type.CHAMPION
		82 -> DungeonRoom.Type.FAIRY
		else -> null
	}

	private fun shapeOf(tiles: List<Vec2i>, type: DungeonRoom.Type): DungeonRoom.Shape {
		if (type == DungeonRoom.Type.UNKNOWN) return DungeonRoom.Shape.UNKNOWN
		val inLine = tiles.all { it.x == tiles[0].x } || tiles.all { it.z == tiles[0].z }
		return when (tiles.size) {
			1 -> DungeonRoom.Shape.S1X1
			2 -> DungeonRoom.Shape.S2X1
			3 -> if (inLine) DungeonRoom.Shape.S3X1 else DungeonRoom.Shape.L
			4 -> if (inLine) DungeonRoom.Shape.S4X1 else DungeonRoom.Shape.S2X2
			else -> DungeonRoom.Shape.UNKNOWN
		}
	}

	/**
	 * Reads each room's progress from the middle of its tiles. Hypixel paints progress on the
	 * top-left tile only, so tiles are tried top-left first.
	 */
	private fun readStates(colors: ByteArray) {
		val rs = roomSize ?: return
		val sc = startCoords?.add(rs / 2, rs / 2) ?: return
		val stride = rs + 4

		DungeonFloor.rooms.forEach { room ->
			val ordered = room.tiles.sortedWith(compareBy({ it.x }, { it.z }))
			val fallback = ordered.firstOrNull() ?: return@forEach

			val painted = ordered.firstNotNullOfOrNull { tile ->
				val index = sc.add(tile.multiply(stride)).mapIndex()
				if (index >= colors.size) return@firstNotNullOfOrNull null
				colors[index].toInt().takeIf { it != 0 }?.let { tile to it }
			}
			// The checkmark moving is the reliable sign of a room being finished, so listeners are
			// told here.
			if (room.updateState(painted?.first ?: fallback, painted?.second ?: 0)) {
				RoomAlerts.onCheckmarkChanged(room)
			}
		}
	}

	/**
	 * Finds doors: painted gaps between rooms. A gap inside a large room is painted along its whole
	 * width, so a door needs a bare pixel beside it.
	 */
	private fun readDoors(colors: ByteArray) {
		val rs = roomSize ?: return
		val size = DungeonFloor.size
		val half = rs / 2
		val sc = startCoords?.add(Vec2i(half, half)) ?: return
		val stride = rs + 4

		for (a in 0 until size.x) {
			for (b in 0 until size.z) {
				if (a + 1 < size.x) {
					val door = sc.add(Vec2i(half + a * stride, b * stride)).mapIndex()
					val beside = sc.add(Vec2i(half + a * stride, b * stride - half + 1)).mapIndex()
					if (door < colors.size && beside < colors.size && colors[beside].toInt() == 0) {
						doorTypeOf(colors[door].toInt())?.let { DungeonFloor.door(Vec2i(a, b), true, it) }
					}
				}

				if (b + 1 < size.z) {
					val door = sc.add(Vec2i(a * stride, half + b * stride)).mapIndex()
					val beside = sc.add(Vec2i(a * stride - half + 1, half + b * stride)).mapIndex()
					if (door < colors.size && beside < colors.size && colors[beside].toInt() == 0) {
						doorTypeOf(colors[door].toInt())?.let { DungeonFloor.door(Vec2i(a, b), false, it) }
					}
				}
			}
		}
	}

	private fun doorTypeOf(color: Int): DungeonDoor.Type? = when (color) {
		119, 82 -> DungeonDoor.Type.WITHER
		63, 66, 85, 62, 74 -> DungeonDoor.Type.NORMAL
		18 -> DungeonDoor.Type.BLOOD
		else -> null
	}

	/** Teammates out of sight are still on the map as decorations. */
	private fun readDecorations(packet: ClientboundMapItemDataPacket) {
		val decorations = packet.decorations.getOrNull() ?: return
		markers = decorations
			.filter { it.type.value() != MapDecorationTypes.FRAME.value() }
			.map { Marker(it.x.toInt(), it.y.toInt(), it.rot() * 360f / 16f) }
	}

	fun markerPosition(marker: Marker): Pair<Float, Float> {
		val rs = roomSize ?: return 0f to 0f
		val center = mapCenter ?: return 0f to 0f
		val offset = Vec2i(marker.mapX, marker.mapZ).multiply(BLOCKS_PER_TILE / ((rs + 4.0) * 2))
		val pos = center.add(offset).add(Vec2i(WORLD_ORIGIN.toInt(), WORLD_ORIGIN.toInt()))
			.divide(BLOCKS_PER_TILE / PIXELS_PER_TILE)
		return pos.x.toFloat() to pos.z.toFloat()
	}

	fun worldPosition(x: Double, z: Double): Pair<Float, Float> = Pair(
		((x + WORLD_ORIGIN) / (BLOCKS_PER_TILE / PIXELS_PER_TILE)).toFloat(),
		((z + WORLD_ORIGIN) / (BLOCKS_PER_TILE / PIXELS_PER_TILE)).toFloat(),
	)
}
