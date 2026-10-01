package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Says how long the server has been silent, once it has been silent too long.
 *
 * Ported from Athen (BSD 3-Clause, Copyright (c) 2025-2026 Starred), whose
 * format this is: nothing at all while the server is keeping up, and the
 * milliseconds since its last tick the moment that stops being true. A number
 * that is only ever on screen when it matters is one you read instantly; a
 * number that is always there is furniture.
 *
 * What it counts is Hypixel's own per-tick ping, which [ServerTicks] already
 * watches for the terminal solver's click protection — so a lag spike here and
 * a click being held back there are the same observation.
 */
object LagDetector {
	private const val EXAMPLE = "670ms"

	@JvmField
	val threshold = SliderModuleSetting(
		id = "threshold",
		label = "Threshold (ms)",
		defaultValue = 500.0,
		min = 100.0,
		max = 1000.0,
		step = 10.0,
		description = "How long the server may go quiet before the display appears.",
	)

	@JvmField
	val textColor = ColorModuleSetting(
		id = "text_color",
		label = "Text",
		defaultRgb = 0xFF5555,
	)

	@JvmField
	val dungeonsOnly = ToggleModuleSetting(
		id = "dungeons_only",
		label = "Only in dungeons",
		defaultValue = false,
		description = "Keeps the display for the Catacombs, where a stall decides a run, and quiet everywhere else.",
	)

	@JvmField
	val module = Module(
		id = "lag_detector",
		name = "Lag Detector",
		description = "Times how long the server stalls",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(threshold, textColor, dungeonsOnly),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(LagElement())
		// A server hop is not a lag spike, however long it took.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> ServerTicks.forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ServerTicks.forget() }
	}

	/** True while the floor has to be read for this, for [imicro.cryptic.CrypticClient]. */
	val needsDungeon: Boolean get() = module.enabled && dungeonsOnly.value

	/**
	 * Milliseconds since the last server tick, or null while that is not worth
	 * saying — the server is keeping up, the module is off, or there is no
	 * server to have heard from.
	 */
	private fun silenceMillis(): Long? {
		if (!module.enabled) return null
		if (Minecraft.getInstance().player == null) return null
		if (dungeonsOnly.value && !DungeonLocation.inDungeon) return null

		val since = ServerTicks.sinceLastTick ?: return null
		return since.takeIf { it > threshold.value }
	}

	private class LagElement : HudElement("lag_detector", "Lag Detector", 0.45, 0.30) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(EXAMPLE)
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = silenceMillis() != null

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) {
			val silence = silenceMillis() ?: return
			context.text(font, "${silence}ms", 0, 0, textColor.argb)
		}

		/** The editor needs something to place, and there is no lag to hand. */
		override fun renderExample(context: GuiGraphicsExtractor) {
			context.text(font, EXAMPLE, 0, 0, textColor.argb)
		}
	}
}
