package imicro.cryptic.skyblock

import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import net.minecraft.client.Minecraft

/**
 * Whether a booster cookie is running, and for how long. Read from the tab list rows "Cookie Buff"
 * and the duration under it (the footer is checked second). Rows work even with a screen open.
 *
 * No heading means not on SkyBlock or the tab list hasn't arrived, which is [Reading.Unknown], not
 * "no cookie".
 */
object BoosterCookie {
	private const val HEADING = "Cookie Buff"

	/** Rows past the heading to look for its value: one, or two with a spacer. */
	private const val VALUE_SEARCH = 2

	private val DURATION = Regex("""(\d+)\s*([ydhms])""")

	private val formattingPattern = Regex("§.")

	sealed interface Reading {
		data object Unknown: Reading

		data object Inactive: Reading

		/** A cookie is running. [minutesLeft] is null when the time isn't in a recognised format. */
		data class Active(val minutesLeft: Long?): Reading
	}

	fun read(): Reading = readFrom(tabRows()) ?: readFrom(footerLines()) ?: Reading.Unknown

	private fun readFrom(lines: List<String>): Reading? {
		val heading = lines.indexOfFirst { it == HEADING }
		if (heading < 0) return null

		val value = (1..VALUE_SEARCH)
			.mapNotNull { lines.getOrNull(heading + it) }
			.firstOrNull { it.isNotEmpty() }
			?: return null

		// Only the first row of the wrapped "Not active!" message is read.
		if (value.startsWith("Not")) return Reading.Inactive
		return Reading.Active(minutesIn(value))
	}

	/** Tab rows sorted the way the overlay draws them, so a heading sits above its value. */
	private fun tabRows(): List<String> {
		val connection = Minecraft.getInstance().connection ?: return emptyList()
		return connection.listedOnlinePlayers
			.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
			.map { it.tabListDisplayName?.string?.replace(formattingPattern, "")?.trim().orEmpty() }
	}

	private fun footerLines(): List<String> {
		val overlay = Minecraft.getInstance().gui.hud.tabList as? PlayerTabOverlayAccessor ?: return emptyList()
		val footer = overlay.`cryptic$footer`() ?: return emptyList()
		return footer.string.lineSequence().map { it.trim() }.toList()
	}

	/** A duration in whole minutes, rounded down: forty seconds left has effectively run out. */
	private fun minutesIn(text: String): Long? {
		var total = 0L
		var matched = false

		for (match in DURATION.findAll(text)) {
			val amount = match.groupValues[1].toLongOrNull() ?: continue
			matched = true
			total += when (match.groupValues[2]) {
				"y" -> amount * 365 * 24 * 60
				"d" -> amount * 24 * 60
				"h" -> amount * 60
				"m" -> amount
				else -> 0
			}
		}

		return if (matched) total else null
	}

	/** Both places the cookie could be, for `/cryptic debug cookie`. */
	fun describe(): List<String> {
		val rows = tabRows()
		val footer = footerLines()

		return listOf("${rows.size} tab rows, in display order:") +
			rows.mapIndexed { index, row -> "  $index \"$row\"" } +
			"${footer.size} footer lines:" +
			footer.map { "  \"$it\"" } +
			"Rows say: ${readFrom(rows) ?: "nothing"}" +
			"Footer says: ${readFrom(footer) ?: "nothing"}" +
			"Read as: ${read()}"
	}
}
