package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.PanelModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The blessings the run has found and their levels, read from the tab list footer. Power matters
 * most: it decides which M7 dragons can be split. From Odin's and NoammAddons' Blessing Display
 * (same defaults).
 */
object BlessingDisplay {
	private const val READ_INTERVAL_TICKS = 10

	private const val VALUE_COLOR = 0xFFFFFFFF.toInt()
	private const val EXAMPLE_LEVEL = 19

	private val FORMATTING = Regex("§.")

	/** Roman numerals up to the thirties. */
	private const val ROMAN = "(X{0,3}(?:IX|IV|V?I{0,3}))"

	/** The five blessings in Odin's order. [level] is the last read, zero if not found. */
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
		description = "The run's blessings on screen",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(
			shownSection, power, time, stone, life, wisdom,
			PanelModuleSetting("color_panel", "Colors", "The colour each blessing is written in.")
				.withMore(powerColor, timeColor, stoneColor, lifeColor, wisdomColor),
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
		// Blessings belong to the run.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() = Blessing.entries.forEach { it.level = 0 }

	fun tick(client: Minecraft) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		if (ticksUntilRead-- > 0) return
		ticksUntilRead = READ_INTERVAL_TICKS

		val overlay = client.gui.hud.tabList as? PlayerTabOverlayAccessor ?: return
		val footer = overlay.`cryptic$footer`()?.string?.replace(FORMATTING, "") ?: return
		Blessing.entries.forEach { blessing ->
			// Only raised from what's written; a footer that hasn't arrived says nothing.
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
			// A smaller numeral before a larger one subtracts: IV, IX.
			if (value < previous) total -= value else total += value
			previous = value
		}
		return total
	}

	/** Blessings that are switched on and found. */
	private fun rows(): List<Pair<Blessing, Int>> {
		if (DebugOverrides.sampleHudValues) return Blessing.entries.filter(::shows).map { it to EXAMPLE_LEVEL }
		return Blessing.entries.filter { shows(it) && it.level > 0 }.map { it to it.level }
	}

	private class BlessingElement : HudElement("blessing_display", "Blessing Display", 0.86, 0.45) {
		private val font get() = Minecraft.getInstance().font

		// As wide as the widest name with a two-digit level.
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
