package imicro.cryptic.experiment

import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Which pet is out, read from the tab list's Pet widget (a hidden pet has no entity to read), and
 * from the summon/despawn chat lines, which arrive seconds before the tab list catches up.
 * Fails open: null means "couldn't tell", never "no pet".
 */
object EquippedPet {
	/** Anchored, so Pet Training's "1: [Lvl 81] Black Cat READY" is not taken for the pet out. */
	private val PET_ROW = Regex("""^\[Lvl \d+]\s*(.+)""")

	private val SUMMONED = Regex("""^You summoned your (.+?)!$""")
	private val DESPAWNED = Regex("""^You despawned your (.+?)!$""")
	private val AUTOPET = Regex("""^Autopet equipped your \[Lvl \d+] (.+?)!""")

	private val formattingPattern = Regex("§.")

	/** The pet from chat ("" for none), trusted until the tab list's row changes from [tabWhenSaid]. */
	private var fromChat: String? = null
	private var tabWhenSaid: String? = null

	/** Tab rows in the order the tab list shows them; the player set itself has no order. */
	private fun rows(): List<String> {
		val connection = Minecraft.getInstance().connection ?: return emptyList()
		return connection.listedOnlinePlayers
			.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
			.mapNotNull { it.tabListDisplayName?.string?.replace(formattingPattern, "")?.trim() }
	}

	private fun fromTab(): String? {
		val rows = rows()
		// The pet out is the row under the "Pet:" heading; other widgets list pets too.
		val heading = rows.indexOfFirst { it.equals("Pet:", ignoreCase = true) }
		if (heading >= 0) {
			val row = rows.getOrNull(heading + 1) ?: return null
			return PET_ROW.find(row)?.groupValues?.get(1)?.trim()
		}
		return rows.firstNotNullOfOrNull { PET_ROW.find(it)?.groupValues?.get(1)?.trim() }
	}

	/** The chat line's pet while the tab list still shows what it did then, else null. */
	private fun chatPet(): String? {
		val pet = fromChat ?: return null
		if (fromTab() != tabWhenSaid) {
			fromChat = null
			return null
		}
		return pet
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		val line = message.string.replace(formattingPattern, "").trim()
		val pet = SUMMONED.find(line)?.groupValues?.get(1)
			?: AUTOPET.find(line)?.groupValues?.get(1)
			?: if (DESPAWNED.matches(line)) "" else return
		fromChat = pet.trim()
		tabWhenSaid = fromTab()
	}

	fun name(): String? {
		val chat = chatPet()
		if (chat != null) return chat.ifEmpty { null }
		return fromTab()
	}

	/** Whether the tab list has the Pet widget; without it, [name] is always null. */
	fun widgetShown(): Boolean = rows().any { it.startsWith("Pet:") || PET_ROW.containsMatchIn(it) }

	/**
	 * True only when a different pet (or none, per chat) is definitely out. "That pet" and "no
	 * idea" are both false, because the caller closes a menu on this.
	 */
	fun isDefinitelyNot(wanted: String): Boolean {
		val chat = chatPet()
		if (chat != null) return !chat.contains(wanted, ignoreCase = true)
		val name = fromTab() ?: return false
		return !name.contains(wanted, ignoreCase = true)
	}

	/** Every non-empty tab row, for `/cryptic debug pet`. */
	fun describe(): List<String> {
		if (Minecraft.getInstance().connection == null) return listOf("Not connected.")
		val rows = rows().filter { it.isNotBlank() }
		val chat = chatPet()
		return listOf("${rows.size} tab rows:") +
			rows.map { "  \"$it\"" } +
			"Read as pet: ${name() ?: "nothing recognised"}" +
			"From chat: ${chat?.ifEmpty { "despawned" } ?: "nothing pending"}"
	}
}
