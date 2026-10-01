package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Counts down the thirty seconds before another dungeon can be entered.
 *
 * Devonian's Warp Cooldown (GPL-3.0, Copyright (c) Synnerz; licence in
 * `licenses/Devonian-LICENSE.txt`). Entering a dungeon starts a cooldown on
 * entering the next, and a party that wants to leave a bad floor and queue
 * again has to wait it out with nothing on screen saying how long. It starts
 * on the line Hypixel prints as the party goes in.
 *
 * Shown only where it is any use: standing in a dungeon that has not started,
 * or back in the Dungeon Hub — and only while there is time left on it.
 */
object DungeonWarpCooldown {
	private const val COOLDOWN_MILLIS = 30_000L
	private const val HUB = "Dungeon Hub"
	private const val LABEL = "Warp: "
	private const val LABEL_COLOR = 0xFFFFFFFF.toInt()

	private val FORMATTING = Regex("§.")

	/** "[MVP+] Name entered The Catacombs, Floor VII!", with MM in front for Master Mode. */
	private val entered = Regex(
		"""^(?:\[[^\]]+] )?\w{1,16} entered (?:MM )?The Catacombs, (?:Floor [IVX]+|Entrance)!""",
	)

	@JvmField
	val module = Module(
		id = "dungeon_warp_cooldown",
		name = "Dungeon Warp Cooldown",
		description = "Counts down until another dungeon can be entered",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
	)

	/** When the cooldown runs out, or zero while there is none. */
	@Volatile
	private var readyAt = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(CooldownElement())
	}

	/** A chat packet. The line is framed in rules of dashes, so every line of it is tried. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		val text = message.string
		// Cheap to rule out, and it is nearly every message there is.
		if ("entered" !in text) return
		for (raw in text.split('\n')) {
			if (entered.containsMatchIn(raw.replace(FORMATTING, "").trim())) {
				readyAt = System.currentTimeMillis() + COOLDOWN_MILLIS
				return
			}
		}
	}

	private fun millisLeft(): Long {
		if (DebugOverrides.sampleHudValues) return 12_300L
		return (readyAt - System.currentTimeMillis()).coerceAtLeast(0L)
	}

	/** Before the run starts, or back in the hub: the two places the wait is being waited out. */
	private fun inPlace(): Boolean =
		DebugOverrides.sampleHudValues ||
			(DungeonLocation.inDungeon && !DungeonRun.started) ||
			(SkyblockLocation.onSkyblock && SkyblockLocation.area == HUB)

	/** Devonian's bands: dark red with most of it left, green when it is nearly up. */
	private fun colorFor(millis: Long): Int = when {
		millis >= COOLDOWN_MILLIS * 3 / 4 -> 0xFFAA0000.toInt()
		millis >= COOLDOWN_MILLIS / 2 -> 0xFFFF5555.toInt()
		millis >= COOLDOWN_MILLIS / 4 -> 0xFFFFFF55.toInt()
		else -> 0xFF55FF55.toInt()
	}

	private class CooldownElement : HudElement("dungeon_warp_cooldown", "Dungeon Warp Cooldown", 0.46, 0.20) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(LABEL + "30.0s")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = module.enabled && millisLeft() > 0L && inPlace()

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) = draw(context, millisLeft())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 12_300L)

		private fun draw(context: GuiGraphicsExtractor, millis: Long) {
			val value = String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
			val x = (width - font.width(LABEL + value)) / 2
			context.text(font, LABEL, x, 0, LABEL_COLOR)
			context.text(font, value, x + font.width(LABEL), 0, colorFor(millis))
		}
	}
}
