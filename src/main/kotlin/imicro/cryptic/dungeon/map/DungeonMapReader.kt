package imicro.cryptic.dungeon.map

import imicro.cryptic.feature.RoomAlerts
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import kotlin.jvm.optionals.getOrNull

/**
 * Turns Hypixel's dungeon map item into room progress and player markers.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who); the full
 * licence is in `licenses/dtMap-LICENSE.txt`. Hypixel paints one block of
 * pixels per room tile, the gaps between them are the doors, and the marker
 * decorations are where everyone is standing.
 *
 * This is the half of the map that knows how far the run has got. It writes
 * into [DungeonFloor] alongside [DungeonWorldScan] rather than rebuilding the
 * floor, because the scan has usually already named every room by the time the
 * first map update arrives.
 */
object DungeonMapReader {
	/** Hypixel's grid: a room is 32 blocks, measured from this world corner. */
	private const val WORLD_ORIGIN = 201.0
	private const val BLOCKS_PER_TILE = 32.0
	private const val PIXELS_PER_TILE = 20.0

	private var mapId: MapId? = null
	private var roomSize: Int? = null
	private var startCoords: Vec2i? = null
	private var mapCenter: Vec2i? = null
	private var sampleLoaded = false

	/** Where the map item says each teammate is, in tab-list order. */
	var markers: List<Marker> = emptyList()
		private set

	class Marker(val mapX: Int, val mapZ: Int, val yaw: Float)

	/** What the last update did, which `/cryptic debug map` reports. */
	var status: String = "no map seen yet"
		private set

	/** Drops everything on leaving a world, so no floor bleeds into the next. */
	fun reset() {
		status = "no map seen yet"
		sampleLoaded = false
		mapId = null
		roomSize = null
		startCoords = null
		mapCenter = null
		markers = emptyList()
		endArt = emptyList()
		DungeonFloor.reset()
		DungeonWorldScan.reset()
		BossScan.reset()
		RoomPrediction.reset()
		DungeonStats.reset()
	}

	/**
	 * Takes a map update, and works out on its own whether it is the dungeon's.
	 *
	 * A client sees plenty of maps that are not this one, so the dungeon's is
	 * claimed by what it looks like rather than by being the first to arrive:
	 * the only map whose pixels start with a room-sized run of entrance green.
	 * Claiming the first map instead is exactly how this managed to show
	 * nothing at all — one unrelated map early in a session and every real
	 * update afterwards was dropped as the wrong id.
	 */
	/**
	 * The picture Hypixel puts on the map when a run is over.
	 *
	 * The end of a run replaces the floor on the map item with the score
	 * screen: the four categories, the grade and the number. It is a picture
	 * rather than a floor, so there is nothing to read out of it — it is kept
	 * as it arrives and drawn as it is.
	 *
	 * Stored as runs of one colour rather than as pixels, because a score
	 * screen is mostly flat areas and sixteen thousand rectangles a frame is
	 * not a thing to ask of a HUD.
	 */
	class ArtRun(val top: Int, val left: Int, val right: Int, val argb: Int)

	var endArt: List<ArtRun> = emptyList()
		private set

	/** One side of the map item, which is square. */
	const val ART_SIZE = 128

	private fun captureEndArt(packet: ClientboundMapItemDataPacket) {
		val level = Minecraft.getInstance().level ?: return
		val colors = level.getMapData(packet.mapId)?.colors ?: return
		if (colors.size < ART_SIZE * ART_SIZE) return

		val runs = mutableListOf<ArtRun>()
		for (row in 0 until ART_SIZE) {
			var start = 0
			while (start < ART_SIZE) {
				val packed = colors[row * ART_SIZE + start].toInt() and 0xFF
				var end = start + 1
				while (end < ART_SIZE && (colors[row * ART_SIZE + end].toInt() and 0xFF) == packed) end++
				// Nothing painted is nothing to draw, and the map item is
				// mostly nothing around its edges.
				if (packed != 0) {
					runs += ArtRun(row, start, end, MapColor.getColorFromPackedId(packed))
				}
				start = end
			}
		}

		endArt = runs
		status = "score art, ${runs.size} runs"
	}

	fun accept(packet: ClientboundMapItemDataPacket) {
		val level = Minecraft.getInstance().level ?: return
		// A finished run has a score screen on its map rather than a floor,
		// and it arrives as a map of its own rather than as an update to the
		// one being read — so the id is not checked, because by now there is
		// only one map left worth listening to.
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

	/**
	 * Loads a stand-in floor, or clears one, for `/cryptic debug map`.
	 *
	 * Returns whether a sample is now loaded. A real update overwrites it the
	 * moment one arrives, so leaving this on cannot hide a live map.
	 */
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

	/** Reads a whole map's worth of pixels, wherever they came from. */
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
	 * Works out where on the item the grid starts and how big a tile is.
	 *
	 * The first three floors are small enough that Hypixel centres their map
	 * differently, and those offsets are fixed per floor. Anything else is found
	 * from the entrance room, the one green run of pixels on the item, whose
	 * length is also the size of a room tile.
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

	/**
	 * Groups the grid's tiles into rooms and hands them to the floor.
	 *
	 * Two tiles belong to the same room when the pixels between them are
	 * painted, which is how a 1x3 or an L tells itself apart from three rooms
	 * in a row that happen to touch.
	 */
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
	 * Reads each room's progress from the middle of its tile, trying every tile
	 * it covers because only the part you have seen is painted.
	 *
	 * The order the tiles are tried in is the whole trick. Hypixel paints a
	 * room's progress onto one tile only — the top-left one — and leaves the
	 * rest of a big room in its plain colour, so the tiles have to be read
	 * top-left first rather than in whatever order the two sources happened to
	 * claim them. [DungeonFloor.claim] grows a room outwards from the tile it
	 * was first seen from, which for a room approached from its far side puts
	 * the near tile at the front of the list: reading that one back gives plain
	 * brown, and a cleared room is never seen to clear.
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
			// The checkmark moving is the only honest word on a room being
			// finished — what clears one is not one thing, and Hypixel already
			// knows the answer — so whoever cares is told here rather than
			// left to notice by watching.
			if (room.updateState(painted?.first ?: fallback, painted?.second ?: 0)) {
				RoomAlerts.onCheckmarkChanged(room)
			}
		}
	}

	/**
	 * Finds the doors, which are the painted gaps between two rooms.
	 *
	 * A gap inside one large room is painted along its whole width, so a gap is
	 * only a door when the pixel beside it is bare.
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

	/** Teammates too far away to see are still on the map, as decorations. */
	private fun readDecorations(packet: ClientboundMapItemDataPacket) {
		val decorations = packet.decorations.getOrNull() ?: return
		markers = decorations
			.filter { it.type.value() != MapDecorationTypes.FRAME.value() }
			.map { Marker(it.x.toInt(), it.y.toInt(), it.rot() * 360f / 16f) }
	}

	/** Turns a decoration's position into the place it is drawn on the map. */
	fun markerPosition(marker: Marker): Pair<Float, Float> {
		val rs = roomSize ?: return 0f to 0f
		val center = mapCenter ?: return 0f to 0f
		val offset = Vec2i(marker.mapX, marker.mapZ).multiply(BLOCKS_PER_TILE / ((rs + 4.0) * 2))
		val pos = center.add(offset).add(Vec2i(WORLD_ORIGIN.toInt(), WORLD_ORIGIN.toInt()))
			.divide(BLOCKS_PER_TILE / PIXELS_PER_TILE)
		return pos.x.toFloat() to pos.z.toFloat()
	}

	/** The same, for a player Cryptic can see the real position of. */
	fun worldPosition(x: Double, z: Double): Pair<Float, Float> = Pair(
		((x + WORLD_ORIGIN) / (BLOCKS_PER_TILE / PIXELS_PER_TILE)).toFloat(),
		((z + WORLD_ORIGIN) / (BLOCKS_PER_TILE / PIXELS_PER_TILE)).toFloat(),
	)
}
