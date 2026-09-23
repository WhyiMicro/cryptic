package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
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
 * Counts how long ago SkyBlock threw you out.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). Being kicked while
 * joining puts you in a lobby with a cooldown nobody tells you the length of,
 * and trying again too early simply kicks you again. A clock running from the
 * kick is the whole fix: you can see when it is worth another attempt instead
 * of guessing and being thrown out twice more.
 *
 * It clears itself once you are on SkyBlock again, and gives up after a minute
 * — by then the cooldown has long gone and the number is only clutter.
 */
object SbKick {
	private const val KICK_PROBLEM = "There was a problem joining SkyBlock, try again in a moment!"

	private val kickMessages = setOf(
		"A kick occurred in your connection, so you were put in the SkyBlock lobby!",
		"You were kicked while joining that server!",
	)

	/** How long the count stays up, and how long a successful join takes to trust. */
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

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		announce.reset()
		textColor.reset()
	})

	@JvmField
	val module = Module(
		id = "sb_kick",
		name = "SB Kick",
		description = "Times a SkyBlock kick",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(announce, textColor, reset),
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
	 * How long ago the kick was, or null while there is nothing to say.
	 *
	 * Getting back onto SkyBlock ends it, but not instantly: the lobby you land
	 * in is SkyBlock too for a moment, so the count has to outlive that before a
	 * join is believed.
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

		/** There is no kick to show in the editor, and hopefully none to come. */
		override fun renderExample(context: GuiGraphicsExtractor) {
			context.text(font, EXAMPLE, 0, 0, textColor.argb)
		}
	}
}
