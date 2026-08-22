package imicro.cryptic.dungeon.map

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft

/**
 * What Cryptic knows about a room before anyone walks into it.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who), whose
 * `rooms.json` is copied verbatim into Cryptic's assets; the full licence is in
 * `licenses/dtMap-LICENSE.txt`. Each entry lists the hashes ("cores") of the
 * block columns that identify the room in the world, which is how a name can be
 * put on the map without asking Hypixel anything.
 *
 * Only the fields the map draws are declared. Gson drops the rest of the file
 * on the floor, so the asset can stay byte-for-byte dtMap's.
 */
data class RoomData(
	val name: String = "Unknown",
	val type: DungeonRoom.Type = DungeonRoom.Type.NORMAL,
	val cores: List<Int> = emptyList(),
	val secrets: Int = 0,
	val crypts: Int = 0,
	val shape: DungeonRoom.Shape = DungeonRoom.Shape.UNKNOWN,
	/** True for the one room per floor a Prince can spawn in, worth a bonus point. */
	val prince: Boolean = false,
) {
	companion object {
		/** The shapes are written the way a player says them, not the enum's name. */
		private val shapeNames = mapOf(
			"1x1" to DungeonRoom.Shape.S1X1,
			"1x2" to DungeonRoom.Shape.S2X1,
			"1x3" to DungeonRoom.Shape.S3X1,
			"1x4" to DungeonRoom.Shape.S4X1,
			"2x2" to DungeonRoom.Shape.S2X2,
			"L" to DungeonRoom.Shape.L,
		)

		private val gson = GsonBuilder()
			.registerTypeAdapter(
				DungeonRoom.Shape::class.java,
				JsonDeserializer { element, _, _ ->
					shapeNames[element.asString] ?: DungeonRoom.Shape.UNKNOWN
				},
			)
			.create()

		private var byCore: Map<Int, RoomData> = emptyMap()

		/**
		 * Reads the asset on first use rather than at startup, because the
		 * resource manager is only ready once a world is being joined.
		 */
		private fun ensureLoaded() {
			if (byCore.isNotEmpty()) return

			val resource = Minecraft.getInstance().resourceManager
				.getResource(Cryptic.id("map/rooms.json"))
				.orElse(null)

			if (resource == null) {
				Cryptic.LOGGER.error("Cryptic's room list is missing; the map cannot name rooms.")
				return
			}

			val rooms: List<RoomData> = runCatching {
				resource.open().bufferedReader().use { reader ->
					gson.fromJson<List<RoomData>>(reader, object : TypeToken<List<RoomData>>() {}.type)
				}
			}.getOrElse {
				Cryptic.LOGGER.error("Cryptic could not read its room list", it)
				return
			}

			byCore = buildMap {
				rooms.forEach { room -> room.cores.forEach { put(it, room) } }
			}
			Cryptic.LOGGER.info("Cryptic read {} dungeon rooms across {} block signatures", rooms.size, byCore.size)
		}

		/** The room a scanned column hash belongs to, or null when it is unknown. */
		fun byCore(core: Int): RoomData? {
			ensureLoaded()
			return byCore[core]
		}
	}
}
