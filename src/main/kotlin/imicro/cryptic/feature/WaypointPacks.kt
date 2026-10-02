package imicro.cryptic.feature

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.feature.DungeonWaypoints.Custom
import imicro.cryptic.feature.DungeonWaypoints.CustomType
import java.nio.file.Files

/**
 * Dungeon Waypoints' packs: custom waypoints by room name.
 *
 * The **Default** row is Devonian's secret spots, which ship with Cryptic. It
 * is listed with the packs so it can be hidden in the same place, but it cannot
 * be edited, renamed or deleted: it is data rather than anybody's work.
 *
 * Shared packs are written the way Odin writes them — room name to a list of
 * waypoints with a block position, a box and a type — so Odin can read what
 * Cryptic exports, and Cryptic what Odin does.
 *
 * Kept in `config/cryptic/waypoint-packs.json`. The single file the first
 * version of the module wrote becomes a pack called "My Waypoints".
 */
object WaypointPacks : PackStore<Custom>("Waypoint Packs", "waypoint-packs.json", Custom::class.java, "My Waypoints") {
	override val defaultRow = DefaultRow(
		label = "Default §8(Devonian, read-only)",
		count = { DungeonWaypoints.defaultCount },
		enabled = { DungeonWaypoints.secrets.value },
		setEnabled = { DungeonWaypoints.secrets.value = it },
	)

	override fun loadLegacy() {
		val old = dir.resolve("dungeon-waypoints.json")
		if (!Files.exists(old)) return
		val rooms = LinkedHashMap<String, MutableList<Custom>>()
		JsonParser.parseString(Files.readString(old)).asJsonObject.entrySet().forEach { (room, list) ->
			rooms[room] = list.asJsonArray.map { gson.fromJson(it, Custom::class.java) }.toMutableList()
		}
		packs += Pack("My Waypoints", true, rooms)
		setEdit(packs.last())
	}

	override fun toJson(pack: Pack<Custom>): JsonElement {
		val root = JsonObject()
		pack.rooms.filterValues { it.isNotEmpty() }.forEach { (room, list) ->
			val array = JsonArray()
			list.forEach { waypoint ->
				val half = (waypoint.size ?: 1.0) / 2
				array.add(JsonObject().apply {
					add("blockPos", JsonObject().apply {
						addProperty("x", waypoint.x)
						addProperty("y", waypoint.y)
						addProperty("z", waypoint.z)
					})
					add("aabb", JsonObject().apply {
						addProperty("minX", 0.5 - half)
						addProperty("minY", 0.5 - half)
						addProperty("minZ", 0.5 - half)
						addProperty("maxX", 0.5 + half)
						addProperty("maxY", 0.5 + half)
						addProperty("maxZ", 0.5 + half)
					})
					addProperty("filled", waypoint.kind == CustomType.ETHERWARP)
					addProperty("depth", true)
					waypoint.title?.let { addProperty("title", it) }
					addProperty("type", if (waypoint.kind == CustomType.BREAKER) "NORMAL" else waypoint.kind.name)
					addProperty("crypticType", waypoint.kind.name)
				})
			}
			root.add(room, array)
		}
		return root
	}

	override fun fromJson(root: JsonObject): Map<String, List<Custom>> {
		val rooms = LinkedHashMap<String, List<Custom>>()
		root.entrySet().forEach { (room, value) ->
			if (!value.isJsonArray) return@forEach
			val list = mutableListOf<Custom>()
			value.asJsonArray.forEach waypoint@{ element ->
				val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@waypoint
				val pos = obj.getAsJsonObject("blockPos")
				val x = pos?.get("x")?.asInt ?: obj.get("x")?.asInt ?: return@waypoint
				val y = pos?.get("y")?.asInt ?: obj.get("y")?.asInt ?: return@waypoint
				val z = pos?.get("z")?.asInt ?: obj.get("z")?.asInt ?: return@waypoint
				val kind = obj.get("crypticType")?.asString?.let(CustomType::parse)
					?: obj.get("type")?.asString?.let(CustomType::parse)
					?: if (obj.get("secret")?.asBoolean == true) CustomType.SECRET else CustomType.NORMAL
				val span = obj.getAsJsonObject("aabb")?.let {
					runCatching {
						maxOf(
							it.get("maxX").asDouble - it.get("minX").asDouble,
							it.get("maxY").asDouble - it.get("minY").asDouble,
							it.get("maxZ").asDouble - it.get("minZ").asDouble,
						)
					}.getOrNull()
				}
				val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.ifBlank { null }
				if (list.any { it.x == x && it.y == y && it.z == z }) return@waypoint
				list += Custom(x, y, z, kind.name, title, span?.takeIf { kotlin.math.abs(it - 1.0) > 0.01 })
			}
			if (list.isNotEmpty()) rooms[room] = list
		}
		return rooms
	}
}
