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
 * The three numbers that say how the game is running: TPS, FPS and ping.
 *
 * Odin's Performance HUD (BSD 3-Clause, Copyright (c) 2025 odtheking), with
 * its settings as they are there — a colour for the names, a colour for the
 * values, which way they are laid out, and a switch for each of the three.
 *
 * What differs is where the numbers come from, which is [ServerStats]. Odin
 * keeps the game's debug pinger running for good to have a ping to show, which
 * is a packet every tick; this asks once every two seconds, and only while the
 * ping is actually on screen.
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
		label = "Ping update (seconds)",
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
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(nameColor, valueColor, direction, showFps, showTps, showPing, pingInterval),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(PerformanceElement())
	}

	/** One name and its number, in the order Odin lists them. */
	private fun metrics(): List<Pair<String, String>> {
		val list = ArrayList<Pair<String, String>>(3)
		if (showTps.value) list += "TPS: " to String.format(Locale.ROOT, "%.1f", ServerStats.tps)
		if (showFps.value) list += "FPS: " to Minecraft.getInstance().fps.toString()
		if (showPing.value) {
			// Asking is what keeps it being measured, so it is only asked for
			// while it is wanted.
			ServerStats.pingIntervalMillis = (pingInterval.value * 1000).toLong()
			val ping = ServerStats.ping
			list += "Ping: " to if (ping < 0) "..." else ping.toString()
		}
		return list
	}

	private class PerformanceElement : HudElement("performance_hud", "Performance HUD", 0.01, 0.01) {
		private val font get() = Minecraft.getInstance().font

		/** The gap between two metrics on one line. */
		private val gap get() = font.width(" ")

		/**
		 * Measured against numbers as wide as they get, not the ones showing.
		 *
		 * The frame the editor draws and the space the element takes should not
		 * twitch every time the FPS gains or loses a digit.
		 */
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
			// Horizontally each one keeps the room its widest value would take,
			// so the ones after it do not slide about as a digit comes and goes.
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
