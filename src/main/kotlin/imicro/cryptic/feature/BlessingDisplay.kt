package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Lists the blessings the run has picked up, and how strong each has got.
 *
 * Ported from Odin's Blessing Display (BSD 3-Clause, Copyright (c) 2025
 * odtheking) and NoammAddons' (CC0, Noamm9), which are the same feature with
 * the same defaults: Power and Time on, the other three off, each in the colour
 * its name is written in. A blessing is found behind a secret and raises the
 * whole party's stats for the run, and the only place the running total is
 * written down is the block of small print under the tab list — which nobody
 * reads, and which is not on screen anyway.
 *
 * Power is the one a party actually plans around: how many of them have been
 * found decides which dragons can be split in Master Mode 7.
 */
object BlessingDisplay {
	/** How often the tab list's footer is read. A blessing is not a fast-moving thing. */
	private const val READ_INTERVAL_TICKS = 10

	private const val VALUE_COLOR = 0xFFFFFFFF.toInt()
	private const val EXAMPLE_LEVEL = 19

	private val FORMATTING = Regex("§.")

	/** Roman numerals up to the thirties, which is further than a blessing goes. */
	private const val ROMAN = "(X{0,3}(?:IX|IV|V?I{0,3}))"

	/**
	 * The five blessings, in the order Odin lists them.
	 *
	 * [level] is the last one read off the tab list, and zero for a blessing the
	 * run has not found.
	 */
	private enum class Blessing(val label: String, pattern: String) {
		POWER("Power", "Blessing of Power $ROMAN"),
		TIME("Time", "Blessing of Time $ROMAN"),
		STONE("Stone", "Blessing of Stone $ROMAN"),
		LIFE("Life", "Blessing of Life $ROMAN"),
		WISDOM("Wisdom", "Blessing of Wisdom $ROMAN");

		val regex = Regex(pattern)
		var level = 0
	}

	private val shownSection = SectionModuleSetting("shown_section", "Shown")

	@JvmField
	val power = ToggleModuleSetting("power", "Power", defaultValue = true)

	@JvmField
	val time = ToggleModuleSetting("time", "Time", defaultValue = true)

	@JvmField
	val stone = ToggleModuleSetting("stone", "Stone", defaultValue = false)

	@JvmField
	val life = ToggleModuleSetting("life", "Life", defaultValue = false)

	@JvmField
	val wisdom = ToggleModuleSetting("wisdom", "Wisdom", defaultValue = false)

	private val colorSection = SectionModuleSetting("color_section", "Colors")

	@JvmField
	val powerColor = ColorModuleSetting("power_color", "Power", 0xAA0000, visibleIf = { power.value })

	@JvmField
	val timeColor = ColorModuleSetting("time_color", "Time", 0xAA00AA, visibleIf = { time.value })

	@JvmField
	val stoneColor = ColorModuleSetting("stone_color", "Stone", 0xAAAAAA, visibleIf = { stone.value })

	@JvmField
	val lifeColor = ColorModuleSetting("life_color", "Life", 0xFF5555, visibleIf = { life.value })

	@JvmField
	val wisdomColor = ColorModuleSetting("wisdom_color", "Wisdom", 0x55FFFF, visibleIf = { wisdom.value })

	@JvmField
	val module = Module(
		id = "blessing_display",
		name = "Blessing Display",
		description = "Lists the run's blessings and their levels",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			shownSection, power, time, stone, life, wisdom,
			colorSection, powerColor, timeColor, stoneColor, lifeColor, wisdomColor,
		),
	)

	private fun shows(blessing: Blessing): Boolean = when (blessing) {
		Blessing.POWER -> power.value
		Blessing.TIME -> time.value
		Blessing.STONE -> stone.value
		Blessing.LIFE -> life.value
		Blessing.WISDOM -> wisdom.value
	}

	private fun colorOf(blessing: Blessing): Int = when (blessing) {
		Blessing.POWER -> powerColor.argb
		Blessing.TIME -> timeColor.argb
		Blessing.STONE -> stoneColor.argb
		Blessing.LIFE -> lifeColor.argb
		Blessing.WISDOM -> wisdomColor.argb
	}

	private var initialized = false
	private var ticksUntilRead = 0

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(BlessingElement())
		// Blessings belong to the run they were found in.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() = Blessing.entries.forEach { it.level = 0 }

	/** Reads the footer, twice a second, while there is a run to read it for. */
	fun tick(client: Minecraft) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		if (ticksUntilRead-- > 0) return
		ticksUntilRead = READ_INTERVAL_TICKS

		val overlay = client.gui.hud.tabList as? PlayerTabOverlayAccessor ?: return
		val footer = overlay.`cryptic$footer`()?.string?.replace(FORMATTING, "") ?: return
		Blessing.entries.forEach { blessing ->
			// Only ever raised from what is written: a footer that has not
			// arrived yet says nothing, which is not the same as saying none.
			blessing.regex.find(footer)?.let { blessing.level = romanToInt(it.groupValues[1]) }
		}
	}

	private fun romanToInt(numeral: String): Int {
		var total = 0
		var previous = 0
		for (letter in numeral.reversed()) {
			val value = when (letter) {
				'I' -> 1
				'V' -> 5
				'X' -> 10
				'L' -> 50
				else -> 0
			}
			// A smaller numeral before a larger one is taken away: IV, IX.
			if (value < previous) total -= value else total += value
			previous = value
		}
		return total
	}

	/** The blessings worth a row: switched on, and found. */
	private fun rows(): List<Pair<Blessing, Int>> {
		if (DebugOverrides.sampleHudValues) return Blessing.entries.filter(::shows).map { it to EXAMPLE_LEVEL }
		return Blessing.entries.filter { shows(it) && it.level > 0 }.map { it to it.level }
	}

	private class BlessingElement : HudElement("blessing_display", "Blessing Display", 0.86, 0.45) {
		private val font get() = Minecraft.getInstance().font

		// As wide as the widest name with a two-digit level, so the frame the
		// editor draws is the one the real thing fills.
		override val width: Int
			get() = Blessing.entries.filter(::shows).maxOfOrNull { font.width("${it.label}: $EXAMPLE_LEVEL") }
				?: font.width("Power: $EXAMPLE_LEVEL")

		override val height: Int
			get() = font.lineHeight * maxOf(1, Blessing.entries.count(::shows))

		override fun isVisible(): Boolean =
			module.enabled && (DungeonLocation.inDungeon || DebugOverrides.sampleHudValues) && rows().isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) = draw(context, rows())

		override fun renderExample(context: GuiGraphicsExtractor) =
			draw(context, Blessing.entries.filter(::shows).map { it to EXAMPLE_LEVEL })

		private fun draw(context: GuiGraphicsExtractor, rows: List<Pair<Blessing, Int>>) {
			rows.forEachIndexed { index, (blessing, level) ->
				val name = "${blessing.label}: "
				val y = index * font.lineHeight
				context.text(font, name, 0, y, colorOf(blessing))
				context.text(font, level.toString(), font.width(name), y, VALUE_COLOR)
			}
		}
	}
}
