package imicro.cryptic.dungeon

import imicro.cryptic.feature.DungeonMap
import imicro.cryptic.feature.Secrets
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component

/**
 * The action bar's "2/3 Secrets", read once for everything that wants it.
 *
 * Hypixel counts a room's secrets in the action bar while you stand in it, and
 * two things want that number: the dungeon map, which files it against the
 * room, and the Secrets module's movable counter, which takes it out of the
 * action bar and draws it wherever it was placed. Taking it out has to come
 * after reading it, and has to happen before anything else sees the message —
 * a reader registered after the strip would find nothing to read — so both are
 * done here, in that order, at the one point where a message can be changed.
 */
object RoomSecrets {
	/** The last count Hypixel gave, or -1 before there has been one. */
	@Volatile
	var found = -1
		private set

	@Volatile
	var total = -1
		private set

	/** When the count was last seen, which is how long it is worth showing. */
	@Volatile
	var seenAt = 0L
		private set

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay ->
			if (!overlay || !DungeonLocation.inDungeon) return@register message
			read(message)
		}
	}

	private fun read(message: Component): Component {
		val raw = message.string
		// The colour codes come off before the numbers are read: Hypixel writes
		// "§74/4 Secrets", and a digit pattern reading that raw takes the 7 out
		// of the grey colour code as part of the number.
		val match = COUNT.find(raw.replace(FORMATTING, "")) ?: return message
		found = match.groupValues[1].toIntOrNull() ?: return message
		total = match.groupValues[2].toIntOrNull() ?: return message
		seenAt = System.currentTimeMillis()

		DungeonMap.onRoomSecretCount(found)

		if (!Secrets.movesCounter) return message
		// The rest of the action bar is kept as it was sent, colour codes and
		// all; only the count and the gap in front of it go.
		val rest = raw.replace(SEGMENT, "")
		return if (rest.isBlank()) Component.empty() else Component.literal(rest)
	}

	fun reset() {
		found = -1
		total = -1
		seenAt = 0L
	}

	private val FORMATTING = Regex("§.")

	/** "2/3 Secrets", once the colours are gone. */
	private val COUNT = Regex("""(\d+)/(\d+) Secrets""")

	/** The same with its colour codes and the spaces before it, as it sits in the raw text. */
	private val SEGMENT = Regex("""\s*(?:§.)*\d+(?:§.)*/(?:§.)*\d+(?:§.)*\s*(?:§.)*Secrets""")
}
