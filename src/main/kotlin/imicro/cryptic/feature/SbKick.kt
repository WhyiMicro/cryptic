package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale

/**
 * Counts time since SkyBlock kicked you, so you know when a retry won't just kick you again. Clears
 * once you're back on SkyBlock, and after a minute. Ported from NoammAddons.
 */
object SbKick {
	private const val KICK_PROBLEM = "There was a problem joining SkyBlock, try again in a moment!"

	private val kickMessages = setOf(
		"A kick occurred in your connection, so you were put in the SkyBlock lobby!",
		"You were kicked while joining that server!",
	)

	/** How long the count stays up, and how long a join must last to be trusted. */
	private const val SHOW_MILLIS = 60_000L
	private const val SETTLE_MILLIS = 10_000L

	private const val EXAMPLE = "§cLast kicked from SkyBlock §b12.34s ago"

	@JvmField
	val announce = ToggleModuleSetting(
		id = "announce",
		label = "Tell your party",
		defaultValue = false,
		description = "Tells the party you were kicked.",
	)

	@JvmField
	val textColor = ColorModuleSetting(
		id = "text_color",
		label = "Text",
		defaultRgb = 0xFFFFFF,
	)

	@JvmField
	val module = Module(
		id = "sb_kick",
		name = "SB Kick",
		description = "Times a SkyBlock kick",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(announce, textColor),
	)

	private var kickedAt = 0L
	private var counting = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(KickElement())
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onMessage(message.string) }
	}

	private fun onMessage(line: String) {
		if (!module.enabled || counting) return

		when (line) {
			KICK_PROBLEM -> start(false)
			in kickMessages -> start(announce.value)
		}
	}

	private fun start(tellParty: Boolean) {
		kickedAt = System.currentTimeMillis()
		counting = true
		if (tellParty) {
			Minecraft.getInstance().connection?.sendCommand("pc You were kicked while joining that server!")
		}
	}

	/**
	 * Time since the kick, or null. The lobby you land in counts as SkyBlock briefly, so a join
	 * must last before it ends the count.
	 */
	private fun sinceKick(): Long? {
		if (!module.enabled || !counting) return null

		val elapsed = System.currentTimeMillis() - kickedAt
		if (elapsed >= SHOW_MILLIS || (SkyblockLocation.onSkyblock && elapsed > SETTLE_MILLIS)) {
			counting = false
			return null
		}
		return elapsed
	}

	private fun text(elapsed: Long): String =
		"§cLast kicked from SkyBlock §b" + String.format(Locale.ROOT, "%.2f", elapsed / 1000.0) + "s ago"

	private class KickElement: HudElement("sb_kick", "SB Kick", 0.30, 0.42) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(EXAMPLE)
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = sinceKick() != null

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) {
			val elapsed = sinceKick() ?: return
			context.text(font, text(elapsed), 0, 0, textColor.argb)
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			context.text(font, EXAMPLE, 0, 0, textColor.argb)
		}
	}
}
