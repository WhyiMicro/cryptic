package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.ServerStats
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale

/**
 * TPS, FPS and ping, with Odin's Performance HUD settings. The numbers come from [ServerStats],
 * which pings every two seconds and only while shown, instead of Odin's every-tick debug pinger.
 */
object PerformanceHud {
	private const val HORIZONTAL = 0

	@JvmField
	val nameColor = ColorModuleSetting(
		id = "name_color",
		label = "Name color",
		defaultRgb = 0x3296DC,
		description = "The colour of \"TPS:\", \"FPS:\" and \"Ping:\".",
	)

	@JvmField
	val valueColor = ColorModuleSetting(
		id = "value_color",
		label = "Value color",
		defaultRgb = 0xFFFFFF,
		description = "The colour of the numbers.",
	)

	@JvmField
	val direction = DropdownModuleSetting(
		id = "direction",
		label = "Direction",
		options = listOf("Horizontal", "Vertical"),
		defaultIndex = HORIZONTAL,
		description = "Side by side on one line, or one under another.",
	)

	@JvmField
	val showFps = ToggleModuleSetting(id = "show_fps", label = "Show FPS", defaultValue = true)

	@JvmField
	val showTps = ToggleModuleSetting(
		id = "show_tps",
		label = "Show TPS",
		defaultValue = true,
		description = "How many ticks a second the server is managing, out of twenty.",
	)

	@JvmField
	val showPing = ToggleModuleSetting(
		id = "show_ping",
		label = "Show ping",
		defaultValue = true,
		description = "Only measured while it is showing.",
	)

	@JvmField
	val pingInterval = SliderModuleSetting(
		id = "ping_interval",
		label = "Ping update",
		unit = "s",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		description = "How often the ping is measured again. Each measurement is one packet.",
		visibleIf = { showPing.value },
	)

	@JvmField
	val module = Module(
		id = "performance_hud",
		name = "Performance HUD",
		description = "TPS, FPS and ping on screen",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(nameColor, valueColor, direction, showFps, showTps, showPing, pingInterval),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(PerformanceElement())
	}

	private fun metrics(): List<Pair<String, String>> {
		val list = ArrayList<Pair<String, String>>(3)
		if (showTps.value) list += "TPS: " to String.format(Locale.ROOT, "%.1f", ServerStats.tps)
		if (showFps.value) list += "FPS: " to Minecraft.getInstance().fps.toString()
		if (showPing.value) {
			// Asking keeps it measured, so it's only asked while wanted.
			ServerStats.pingIntervalMillis = (pingInterval.value * 1000).toLong()
			val ping = ServerStats.ping
			list += "Ping: " to if (ping < 0) "..." else ping.toString()
		}
		return list
	}

	private class PerformanceElement : HudElement("performance_hud", "Performance HUD", 0.01, 0.01) {
		private val font get() = Minecraft.getInstance().font

		private val gap get() = font.width(" ")

		/** Measured against the widest numbers, so the frame doesn't twitch as digits change. */
		override val width: Int
			get() {
				val widths = sized().map { font.width(it.first + it.second) }
				if (widths.isEmpty()) return font.width("FPS: 000")
				return if (direction.selectedIndex == HORIZONTAL) widths.sum() + gap * (widths.size - 1) else widths.max()
			}

		override val height: Int
			get() {
				val rows = if (direction.selectedIndex == HORIZONTAL) 1 else maxOf(1, sized().size)
				return rows * font.lineHeight
			}

		private fun sized(): List<Pair<String, String>> {
			val list = ArrayList<Pair<String, String>>(3)
			if (showTps.value) list += "TPS: " to "20.0"
			if (showFps.value) list += "FPS: " to "0000"
			if (showPing.value) list += "Ping: " to "000"
			return list
		}

		override fun isVisible(): Boolean =
			module.enabled && Minecraft.getInstance().player != null &&
				(showFps.value || showTps.value || showPing.value)

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) {
			var x = 0
			var y = 0
			val horizontal = direction.selectedIndex == HORIZONTAL
			// Each keeps the room its widest value takes, so the rest don't slide.
			val slots = sized()

			metrics().forEachIndexed { index, (name, value) ->
				context.text(font, name, x, y, nameColor.argb)
				context.text(font, value, x + font.width(name), y, valueColor.argb)
				if (horizontal) {
					val slot = slots.getOrNull(index)?.let { font.width(it.first + it.second) }
						?: font.width(name + value)
					x += slot + gap
				} else {
					y += font.lineHeight
				}
			}
		}
	}
}
