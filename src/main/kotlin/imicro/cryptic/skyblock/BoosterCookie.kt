package imicro.cryptic.skyblock

import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import net.minecraft.client.Minecraft

/**
 * Whether a booster cookie is running, and for how much longer.
 *
 * SkyBlock has no client-facing way to ask, and the only place the answer is
 * written down is the tab list, as a heading with its value on the row below:
 *
 * ```
 * Cookie Buff
 * 1d 4h 21m
 * ```
 *
 * with `Not active! Obtain booster cookies from the community shop!` wrapped
 * over the rows below the heading when there is none.
 *
 * It is read off the **rows**, not off the block of text under them. The footer
 * is where Hypixel used to put it and is still checked second, but the live
 * copy is a pair of rows in the grid — which is also why this works with a
 * screen open: rows arrive in a packet and sit in the connection whether or not
 * anything is drawing them. Which rows to read is the one fact here that cannot
 * be worked out from outside the game; it is the same pair Skyblocker
 * (LGPL-3.0) reads, and nothing of theirs beyond that fact is used.
 *
 * The heading doubles as the check for being on SkyBlock at all. No heading
 * anywhere means Hypixel's lobby, another game, or a tab list that has not
 * arrived yet, and all three answer [Reading.Unknown] rather than "no cookie" —
 * the difference matters, because one of them is worth interrupting somebody
 * over and the others are not.
 */
object BoosterCookie {
	private const val HEADING = "Cookie Buff"

	/**
	 * How far past the heading to look for its value.
	 *
	 * One row normally, two when Hypixel has put a spacer in. Any further and a
	 * cookie block with no value in it would start reading the next section's
	 * heading as a duration.
	 */
	private const val VALUE_SEARCH = 2

	/** `1d`, `4h`, `21m`, `30s`, and a year for the people who stack them. */
	private val DURATION = Regex("""(\d+)\s*([ydhms])""")

	private val formattingPattern = Regex("§.")

	sealed interface Reading {
		/** Not on SkyBlock, or the tab list has not said yet. */
		data object Unknown: Reading

		/** On SkyBlock, and there is no cookie running. */
		data object Inactive: Reading

		/**
		 * A cookie is running. [minutesLeft] is null when Hypixel wrote the time
		 * in a shape this does not recognise, which is still an active cookie —
		 * just not one anything can count down.
		 */
		data class Active(val minutesLeft: Long?): Reading
	}

	fun read(): Reading = readFrom(tabRows()) ?: readFrom(footerLines()) ?: Reading.Unknown

	/** The answer this set of lines holds, or null if it does not hold one. */
	private fun readFrom(lines: List<String>): Reading? {
		val heading = lines.indexOfFirst { it == HEADING }
		if (heading < 0) return null

		val value = (1..VALUE_SEARCH)
			.mapNotNull { lines.getOrNull(heading + it) }
			.firstOrNull { it.isNotEmpty() }
			?: return null

		// The inactive message wraps over several rows; only the first is read,
		// and "Not active!" is the whole of what distinguishes it from a time.
		if (value.startsWith("Not")) return Reading.Inactive
		return Reading.Active(minutesIn(value))
	}

	/**
	 * Every tab row, in the order the tab list would draw it.
	 *
	 * The connection hands its players out in whatever order they arrived, and
	 * a heading is only above its value once they are sorted the way the overlay
	 * sorts them.
	 */
	private fun tabRows(): List<String> {
		val connection = Minecraft.getInstance().connection ?: return emptyList()
		return connection.listedOnlinePlayers
			.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
			.map { it.tabListDisplayName?.string?.replace(formattingPattern, "")?.trim().orEmpty() }
	}

	/** The block of text under the tab list, where this used to live. */
	private fun footerLines(): List<String> {
		val overlay = Minecraft.getInstance().gui.hud.tabList as? PlayerTabOverlayAccessor ?: return emptyList()
		val footer = overlay.`cryptic$footer`() ?: return emptyList()
		return footer.string.lineSequence().map { it.trim() }.toList()
	}

	/**
	 * A duration as whole minutes, rounding seconds away.
	 *
	 * Rounding down rather than up on purpose: a cookie with forty seconds left
	 * is one that has effectively run out, and reporting it as a minute would
	 * be the wrong way to be wrong.
	 */
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

	/**
	 * Both places the cookie could be, for `/cryptic debug cookie`.
	 *
	 * Where Hypixel writes this is the only part of it that has to be seen from
	 * inside the game, and it decides whether the reminder ever fires. Both
	 * sources are printed rather than just the one that answered, because the
	 * useful case is the one where neither did.
	 */
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
