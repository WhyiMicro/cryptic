package imicro.cryptic.skyblock

/**
 * Whether a booster cookie is running, and for how long. Read from the tab list rows "Cookie Buff"
 * and the duration under it (the footer is checked second). Rows work even with a screen open.
 *
 * No heading means not on SkyBlock or the tab list hasn't arrived, which is [BuffReading.Unknown],
 * not "no cookie".
 */
object BoosterCookie {
	private const val HEADING = "Cookie Buff"

	/** Rows past the heading to look for its value: one, or two with a spacer. */
	private const val VALUE_SEARCH = 2

	fun read(): BuffReading = readFrom(TabText.rows()) ?: readFrom(TabText.footer()) ?: BuffReading.Unknown

	private fun readFrom(lines: List<String>): BuffReading? {
		val heading = lines.indexOfFirst { it == HEADING }
		if (heading < 0) return null

		val value = (1..VALUE_SEARCH)
			.mapNotNull { lines.getOrNull(heading + it) }
			.firstOrNull { it.isNotEmpty() }
			?: return null

		// Only the first row of the wrapped "Not active!" message is read.
		if (value.startsWith("Not")) return BuffReading.Inactive
		return BuffReading.Active(TabText.minutesIn(value))
	}

	/** Both places the cookie could be, for `/cryptic debug cookie`. */
	fun describe(): List<String> {
		val rows = TabText.rows()
		val footer = TabText.footer()

		return listOf("${rows.size} tab rows, in display order:") +
			rows.mapIndexed { index, row -> "  $index \"$row\"" } +
			"${footer.size} footer lines:" +
			footer.map { "  \"$it\"" } +
			"Cookie rows say: ${readFrom(rows) ?: "nothing"}" +
			"Cookie footer says: ${readFrom(footer) ?: "nothing"}" +
			"Cookie read as: ${read()}"
	}
}
