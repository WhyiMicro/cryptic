package imicro.cryptic.dungeon

import imicro.cryptic.feature.DungeonMap
import imicro.cryptic.feature.Secrets
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component

/**
 * The action bar's "2/3 Secrets", read once for both the dungeon map and the Secrets module's
 * movable counter. Read from the packet, since other mods (SkyHanni, Odin) can hide or rewrite the
 * action bar before Fabric's message event sees it; the event only takes the count out of the
 * bar for the movable counter.
 *
 * Right after you walk into a room the bar still shows the last room's count. A different total
 * gives that away, but two rooms with the same total don't, so the first reading after entering a
 * room is dropped when its total matches the one before (Odin's rule, BSD-3).
 */
object RoomSecrets {
	@Volatile
	var found = -1
		private set

	@Volatile
	var total = -1
		private set

	@Volatile
	var seenAt = 0L
		private set

	/** Set on walking into a room: the next reading may still be the last room's. */
	@Volatile
	private var enteredRoom = false

	/** The total the bar showed last, stale or not. */
	private var lastTotal = -1

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay ->
			if (!overlay || !DungeonLocation.inDungeon) return@register message
			strip(message)
		}
	}

	/** An action bar from the server, either packet. Client thread. */
	@JvmStatic
	fun onActionBar(message: Component) {
		if (!DungeonLocation.inDungeon) return
		// Codes come off first: in "§74/4 Secrets" the 7 belongs to the colour code.
		val match = COUNT.find(message.string.replace(FORMATTING, "")) ?: return
		val count = match.groupValues[1].toIntOrNull() ?: return
		val of = match.groupValues[2].toIntOrNull() ?: return
		val sameTotal = of == lastTotal
		lastTotal = of
		if (enteredRoom) {
			enteredRoom = false
			if (sameTotal) return
		}
		found = count
		total = of
		seenAt = System.currentTimeMillis()
		DungeonMap.onRoomSecretCount(count, of)
	}

	private fun strip(message: Component): Component {
		val raw = message.string
		if (!Secrets.movesCounter || !COUNT.containsMatchIn(raw.replace(FORMATTING, ""))) return message
		// The rest of the action bar keeps its codes; only the count and the gap before it go.
		val rest = raw.replace(SEGMENT, "")
		return if (rest.isBlank()) Component.empty() else Component.literal(rest)
	}

	/** You walked into another room. From the dungeon map's room tracking. */
	fun onRoomEntered() {
		enteredRoom = true
		// The last room's count isn't this one's.
		found = -1
		total = -1
		seenAt = 0L
	}

	fun reset() {
		found = -1
		total = -1
		seenAt = 0L
		enteredRoom = false
		lastTotal = -1
	}

	private val FORMATTING = Regex("§.")

	private val COUNT = Regex("""(\d+)/(\d+) Secrets""")

	private val SEGMENT = Regex("""\s*(?:§.)*\d+(?:§.)*/(?:§.)*\d+(?:§.)*\s*(?:§.)*Secrets""")
}
