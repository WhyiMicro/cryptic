package imicro.cryptic.experiment

import net.minecraft.client.Minecraft

/**
 * Which pet is out, read from the tab list's `[Lvl 100] Guardian` row (a hidden pet has no entity
 * to read). Fails open: null means "couldn't tell", never "no pet".
 */
object EquippedPet {
	/** Only the pet row is written this way, so no heading has to be found first. */
	private val PET_ROW = Regex("""\[Lvl \d+]\s*(.+)""")

	private val formattingPattern = Regex("§.")

	fun name(): String? {
		val connection = Minecraft.getInstance().connection ?: return null

		for (info in connection.onlinePlayers) {
			val row = info.tabListDisplayName?.string?.replace(formattingPattern, "") ?: continue
			val name = PET_ROW.find(row)?.groupValues?.get(1) ?: continue
			return name.trim()
		}
		return null
	}

	/** Whether the tab list has the Pet widget; without it, [name] is always null. */
	fun widgetShown(): Boolean {
		val connection = Minecraft.getInstance().connection ?: return false
		return connection.onlinePlayers.any {
			val row = it.tabListDisplayName?.string?.replace(formattingPattern, "")?.trim() ?: return@any false
			row.startsWith("Pet:") || PET_ROW.containsMatchIn(row)
		}
	}

	/**
	 * True only when a different pet is definitely out. "That pet" and "no idea" are both false,
	 * because the caller closes a menu on this.
	 */
	fun isDefinitelyNot(wanted: String): Boolean {
		val name = name() ?: return false
		return !name.contains(wanted, ignoreCase = true)
	}

	/** Every non-empty tab row, for `/cryptic debug pet`. */
	fun describe(): List<String> {
		val connection = Minecraft.getInstance().connection ?: return listOf("Not connected.")

		val rows = connection.onlinePlayers
			.mapNotNull { it.tabListDisplayName?.string?.replace(formattingPattern, "") }
			.filter { it.isNotBlank() }

		return listOf("${rows.size} tab rows:") +
			rows.map { "  \"$it\"" } +
			"Read as pet: ${name() ?: "nothing recognised"}"
	}
}
