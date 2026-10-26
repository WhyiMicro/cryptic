package imicro.cryptic.dungeon

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.dungeon.map.Vec2i
import imicro.cryptic.feature.DungeonMap
import imicro.cryptic.net.RelaySocket
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft

/**
 * Room secret counts shared with teammates through Odin's relay, the way Odin's map does it: each
 * client in the lobby's room sends the room it walks into and its count as it rises, and a
 * teammate's higher count for the same room wins. Odin's messages are
 * `{"playerName","roomName","tiles":[{"data"}],"foundSecrets"}`, a tile being the 6×6 grid's x
 * and z packed as `z shl 32 | x`, so Odin users and Cryptic users see each other's.
 */
object SecretSync {
	private const val RELAY_URL = "wss://ws.odtheking.com/"
	private const val RETRY_MILLIS = 10_000L

	private val relay = RelaySocket { message ->
		// Parsed on the socket thread, applied on the client thread. The same room also carries
		// melody messages, which have no room and are skipped.
		val json = JsonParser.parseString(message).takeIf { it.isJsonObject }?.asJsonObject ?: return@RelaySocket
		val player = json.get("playerName")?.asString ?: return@RelaySocket
		val roomName = json.get("roomName")?.asString ?: return@RelaySocket
		val found = json.get("foundSecrets")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@RelaySocket
		val tiles = json.getAsJsonArray("tiles")?.mapNotNull { tile ->
			val obj = tile.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
			obj.get("data")?.asLong?.let { Vec2i(it.toInt(), (it shr 32).toInt()) }
				?: if (obj.has("x") && obj.has("z")) Vec2i(obj.get("x").asInt, obj.get("z").asInt) else null
		} ?: emptyList()
		Minecraft.getInstance().execute { receive(player, roomName, tiles, found) }
	}

	private var lastAttempt = 0L
	private var received = 0
	private var sent = 0

	private val wanted: Boolean
		get() = DungeonMap.module.enabled && DungeonMap.shareSecrets.value &&
			DungeonLocation.inDungeon && !DungeonRun.inBoss

	/** Keeps the relay open while in the dungeon's rooms, and closes it for the boss. */
	fun tick() {
		if (!wanted) {
			if (relay.connected || relay.connecting) relay.shutdown()
			return
		}
		if (relay.connected || relay.connecting) return
		val now = System.currentTimeMillis()
		if (now - lastAttempt < RETRY_MILLIS) return
		val lobby = SkyblockLocation.lobbyId() ?: return
		lastAttempt = now
		relay.connect(RELAY_URL + lobby)
	}

	/** A room walked into, or its count risen. */
	fun send(room: DungeonRoom) {
		if (!wanted || !relay.connected) return
		val name = room.data?.name ?: return
		val json = JsonObject()
		json.addProperty("playerName", Minecraft.getInstance().user.name)
		json.addProperty("roomName", name)
		val tiles = JsonArray()
		// Grid order, which is the order Odin's scan adds them in; it compares the lists as they are.
		for (tile in room.tiles.sortedWith(compareBy({ it.z }, { it.x }))) {
			tiles.add(JsonObject().apply { addProperty("data", (tile.z.toLong() shl 32) or (tile.x.toLong() and 0xFFFF_FFFFL)) })
		}
		json.add("tiles", tiles)
		if (room.secretsRead) json.addProperty("foundSecrets", room.foundSecrets)
		if (relay.send(json.toString())) sent++
	}

	private fun receive(player: String, roomName: String, tiles: List<Vec2i>, found: Int) {
		if (!wanted) return
		if (player == Minecraft.getInstance().user.name || !DungeonTeam.isTeammate(player)) return
		val room = DungeonFloor.rooms.firstOrNull { room ->
			room.data?.name == roomName && (tiles.isEmpty() || room.tiles.any { it in tiles })
		} ?: return
		received++
		if (found > room.foundSecrets) room.foundSecrets = found
		room.secretsRead = true
	}

	fun forget() {
		relay.shutdown()
		lastAttempt = 0L
	}

	fun describe(): List<String> = listOf(
		"Secret sync wanted: $wanted (map on: ${DungeonMap.module.enabled}, toggle: ${DungeonMap.shareSecrets.value}, " +
			"in dungeon: ${DungeonLocation.inDungeon}, in boss: ${DungeonRun.inBoss})",
		"Relay: ${if (relay.connected) "connected" else if (relay.connecting) "connecting" else "closed"}, " +
			"lobby ${SkyblockLocation.lobbyId() ?: "unknown"}, sent $sent, teammates' counts taken $received",
	)
}
