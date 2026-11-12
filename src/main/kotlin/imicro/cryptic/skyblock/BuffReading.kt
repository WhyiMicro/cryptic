package imicro.cryptic.skyblock

import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import net.minecraft.client.Minecraft

/** What the tab list says about a timed buff (booster cookie, God Potion). */
sealed interface BuffReading {
	/** Not on SkyBlock, or the tab list hasn't arrived: not the same as "none". */
	data object Unknown: BuffReading

	data object Inactive: BuffReading

	/** Running. [minutesLeft] is null when the time isn't in a recognised format. */
	data class Active(val minutesLeft: Long?): BuffReading
}

/** The tab list's rows and footer as plain text, shared by the buff readers. */
internal object TabText {
	private val formattingPattern = Regex("§.")

	/** "3d 2h", "4 Days", "30 Minutes": a number and the first letter of its unit. */
	private val DURATION = Regex("""(\d+)\s*([ydhms])""", RegexOption.IGNORE_CASE)

	/** Tab rows sorted the way the overlay draws them, so a heading sits above its value. */
	fun rows(): List<String> {
		val connection = Minecraft.getInstance().connection ?: return emptyList()
		return connection.listedOnlinePlayers
			.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
			.map { it.tabListDisplayName?.string?.replace(formattingPattern, "")?.trim().orEmpty() }
	}

	fun footer(): List<String> {
		val overlay = Minecraft.getInstance().gui.hud.tabList as? PlayerTabOverlayAccessor ?: return emptyList()
		val footer = overlay.`cryptic$footer`() ?: return emptyList()
		return footer.string.replace(formattingPattern, "").lineSequence().map { it.trim() }.toList()
	}

	/** A duration in whole minutes, rounded down: forty seconds left has effectively run out. */
	fun minutesIn(text: String): Long? {
		var total = 0L
		var matched = false

		for (match in DURATION.findAll(text)) {
			val amount = match.groupValues[1].toLongOrNull() ?: continue
			matched = true
			total += when (match.groupValues[2].lowercase()) {
				"y" -> amount * 365 * 24 * 60
				"d" -> amount * 24 * 60
				"h" -> amount * 60
				"m" -> amount
				else -> 0
			}
		}

		return if (matched) total else null
	}
}
