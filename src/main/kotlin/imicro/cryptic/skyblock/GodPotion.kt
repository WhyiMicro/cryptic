package imicro.cryptic.skyblock

/**
 * Whether a God Potion is running, and for how long. Read from a "God Potion" tab row (the Active
 * Effects widget, when it is turned on), then from the footer's Active Effects section, which
 * names the God Potion whenever one is running.
 *
 * Potions are paused in dungeons and the Rift, so nothing there is read as "none".
 */
object GodPotion {
	private const val NAME = "God Potion"

	/** The footer's section heading; without it the footer says nothing about effects either way. */
	private const val EFFECTS_HEADING = "Active Effects"

	private val PAUSED_AREAS = setOf("Catacombs", "The Rift")

	fun read(): BuffReading {
		// The footer arrives before the Area row, so an unknown island may still be a paused one.
		val area = SkyblockLocation.area ?: return BuffReading.Unknown
		if (area in PAUSED_AREAS) return BuffReading.Unknown
		return fromRows(TabText.rows()) ?: fromFooter(TabText.footer()) ?: BuffReading.Unknown
	}

	/** "God Potion: 1d 4h", or the heading with the time on the row below. */
	private fun fromRows(rows: List<String>): BuffReading? {
		val index = rows.indexOfFirst { it.startsWith(NAME) }
		if (index < 0) return null
		val minutes = TabText.minutesIn(rows[index].removePrefix(NAME))
			?: rows.getOrNull(index + 1)?.let(TabText::minutesIn)
		return BuffReading.Active(minutes)
	}

	/** "You have a God Potion active! 1d 4h": the time may wrap onto the next line. */
	private fun fromFooter(lines: List<String>): BuffReading? {
		val index = lines.indexOfFirst { it.contains(NAME) }
		if (index < 0) {
			return if (lines.any { it.startsWith(EFFECTS_HEADING) }) BuffReading.Inactive else null
		}
		val minutes = TabText.minutesIn(lines[index].substringAfter(NAME))
			?: lines.getOrNull(index + 1)?.let(TabText::minutesIn)
		return BuffReading.Active(minutes)
	}

	/** For `/cryptic debug cookie`, after the rows and footer it prints. */
	fun describe(): List<String> = listOf(
		"God Potion rows say: ${fromRows(TabText.rows()) ?: "nothing"}",
		"God Potion footer says: ${fromFooter(TabText.footer()) ?: "nothing"}",
		"God Potion read as: ${read()}" + when (SkyblockLocation.area) {
			null -> " (island unknown)"
			in PAUSED_AREAS -> " (paused here)"
			else -> ""
		},
	)
}
