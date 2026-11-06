package imicro.cryptic.slayer

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.feature.SlayerFeatures
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

/**
 * Slayer XP progress, from the line Hypixel sends when a quest completes:
 * `Zombie Slayer LVL 7 - Next LVL in 12,345 XP!`. Two of those for the same slayer at the same
 * level give the XP one boss is worth (perks included), which in turn gives bosses to go and XP an
 * hour. The idea is SlayerXPOverlay's (AGPL-3.0); none of its code is here.
 */
object SlayerXp {
	private val FORMATTING = Regex("§.")
	private val PROGRESS = Regex("""(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer LVL (\d+) - (?:Next LVL in ([\d,]+) XP!|LVL MAXED OUT!)""")

	/** A pause this long starts the hourly rate again. */
	private const val IDLE_MILLIS = 15 * 60_000L

	private val NAMES = mapOf(
		"Zombie" to "§2Revenant",
		"Spider" to "§8Tarantula",
		"Wolf" to "§fSven",
		"Enderman" to "§5Voidgloom",
		"Blaze" to "§6Inferno",
		"Vampire" to "§4Riftstalker",
	)

	private class Progress(val slayer: String) {
		var level = 0
		/** XP to the next level; null when maxed. */
		var remaining: Long? = null
		var perBoss: Long? = null

		var sessionXp = 0L
		var sessionBosses = 0
		var sessionStart = 0L
		var lastAt = 0L
	}

	private val bySlayer = HashMap<String, Progress>()
	private var latest: Progress? = null

	private val numbers: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !SlayerFeatures.module.enabled || !SlayerFeatures.xpOverlay.value) return
		val text = message.string.replace(FORMATTING, "")
		val match = PROGRESS.find(text) ?: return
		val slayer = match.groupValues[1]
		val level = match.groupValues[2].toInt()
		val remaining = match.groupValues[3].takeIf { it.isNotEmpty() }?.replace(",", "")?.toLongOrNull()
		record(slayer, level, remaining, System.currentTimeMillis())
	}

	/** One quest completed. Public for `/cryptic debug`. */
	fun record(slayer: String, level: Int, remaining: Long?, now: Long) {
		val progress = bySlayer.getOrPut(slayer) { Progress(slayer) }
		val before = progress.remaining
		// Same level, so the drop is one boss. Across a level-up it can't be measured without the
		// level table, so the last boss's worth stands in.
		val gained = when {
			before != null && remaining != null && progress.level == level && before > remaining ->
				(before - remaining).also { progress.perBoss = it }
			progress.level != 0 && progress.level != level -> progress.perBoss
			else -> null
		}
		if (gained != null) {
			if (progress.sessionStart == 0L || now - progress.lastAt > IDLE_MILLIS) {
				progress.sessionStart = progress.lastAt
				progress.sessionXp = 0
				progress.sessionBosses = 0
			}
			progress.sessionXp += gained
			progress.sessionBosses++
		}
		progress.level = level
		progress.remaining = remaining
		progress.lastAt = now
		latest = progress
	}

	private fun lines(progress: Progress, now: Long): List<String> {
		val list = ArrayList<String>(5)
		list += "${NAMES[progress.slayer] ?: progress.slayer} Slayer §7LVL §f${progress.level}"
		val remaining = progress.remaining
		if (remaining == null) {
			list += "§aMaxed out"
		} else {
			list += "§7Next level: §f${numbers.format(remaining)} XP"
			progress.perBoss?.let { per ->
				list += "§7Bosses to go: §f${ceil(remaining.toDouble() / per).toLong()} §8(${numbers.format(per)} each)"
			}
		}
		if (progress.sessionBosses > 0) {
			val bosses = if (progress.sessionBosses == 1) "1 boss" else "${progress.sessionBosses} bosses"
			list += "§7Session: §f+${numbers.format(progress.sessionXp)} XP §8($bosses)"
			val elapsed = progress.lastAt - progress.sessionStart
			// One boss's time says little about the rate.
			if (progress.sessionBosses >= 2 && elapsed > 0 && now - progress.lastAt < IDLE_MILLIS) {
				list += "§7XP/hour: §f${numbers.format(progress.sessionXp * 3_600_000L / elapsed)}"
			}
		}
		return list
	}

	fun clear() {
		bySlayer.clear()
		latest = null
	}

	class Element : HudElement("slayer_xp", "Slayer XP", 0.01, 0.25) {
		private val font get() = Minecraft.getInstance().font

		private var lastWidth = 140

		override val width: Int get() = lastWidth
		override val height: Int get() = 5 * font.lineHeight

		override val layer get() = SlayerFeatures.module.category

		override fun isVisible(): Boolean =
			SlayerFeatures.module.enabled && SlayerFeatures.xpOverlay.value && latest != null &&
				(SkyblockLocation.onSkyblock || DebugOverrides.slayerAnywhere)

		override fun showInEditor(): Boolean = SlayerFeatures.module.enabled && SlayerFeatures.xpOverlay.value

		override fun render(context: GuiGraphicsExtractor) {
			val progress = latest ?: return
			draw(context, lines(progress, System.currentTimeMillis()))
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			val progress = latest
			if (progress != null) {
				render(context)
				return
			}
			draw(
				context,
				listOf(
					"§5Voidgloom Slayer §7LVL §f7",
					"§7Next level: §f112,500 XP",
					"§7Bosses to go: §f75 §8(1,500 each)",
					"§7Session: §f+15,000 XP §8(10 bosses)",
					"§7XP/hour: §f36,000",
				),
			)
		}

		private fun draw(context: GuiGraphicsExtractor, lines: List<String>) {
			var y = 0
			var widest = 0
			for (line in lines) {
				context.text(font, line, 0, y, -1)
				widest = maxOf(widest, font.width(line))
				y += font.lineHeight
			}
			lastWidth = maxOf(60, widest)
		}
	}
}
