package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * One of every control the menu can draw, wired to nothing.
 *
 * The idea is NoammAddons' Comp Test (CC0, Noamm9): a card that exists to be
 * poked at. It is where a change to the menu itself gets looked at — every kind
 * of row is here, so a row that has gone wrong is here too — and where the
 * things a card can do are on show in one place: a setting that appears when
 * another is switched on, a group that starts folded away, a key bound from a
 * row, and a HUD element that reads its card back.
 *
 * Nothing here changes the game. Its settings are saved with the profile like
 * any other card's, which is itself one of the things worth testing.
 */
object ExampleModule {
	private const val SOURCE = "Example Module"
	private const val WHITE = 0xFFFFFFFF.toInt()
	private const val MUTED = 0xFFAAAAAA.toInt()

	// ---- Switches --------------------------------------------------------

	private val switchSection = SectionModuleSetting("switch_section", "Switches")

	@JvmField
	val toggle = ToggleModuleSetting(
		id = "toggle",
		label = "A toggle",
		defaultValue = true,
		description = "Rest the cursor on any row to read its description, which is this.",
	)

	@JvmField
	val dependent = ToggleModuleSetting(
		id = "dependent",
		label = "Only while the toggle is on",
		defaultValue = false,
		description = "A setting can hide itself until another one makes it relevant.",
		visibleIf = { toggle.value },
	)

	// ---- Numbers ---------------------------------------------------------

	private val numberSection = SectionModuleSetting("number_section", "Numbers")

	@JvmField
	val wholeSlider = SliderModuleSetting(
		id = "whole_slider",
		label = "Whole numbers",
		defaultValue = 50.0,
		min = 0.0,
		max = 100.0,
		step = 1.0,
		description = "Drag it, or click the number to type one.",
	)

	@JvmField
	val decimalSlider = SliderModuleSetting(
		id = "decimal_slider",
		label = "Decimals",
		defaultValue = 1.5,
		min = 0.0,
		max = 5.0,
		step = 0.1,
		description = "The same control with a finer step.",
	)

	@JvmField
	val range = RangeModuleSetting(
		id = "range",
		label = "A range",
		defaultLower = 25.0,
		defaultUpper = 75.0,
		min = 0.0,
		max = 100.0,
		step = 1.0,
		description = "Two ends on one track, for anything that wants a number from somewhere in between.",
	)

	// ---- Choices ---------------------------------------------------------

	private val choiceSection = SectionModuleSetting("choice_section", "Choices")

	@JvmField
	val dropdown = DropdownModuleSetting(
		id = "dropdown",
		label = "A dropdown",
		options = listOf("First", "Second", "Third", "Fourth"),
		defaultIndex = 0,
		description = "Saved by its label, so rewording the list later cannot quietly reorder it.",
	)

	@JvmField
	val dropdownExtra = SliderModuleSetting(
		id = "dropdown_extra",
		label = "Only for \"Third\"",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 1.0,
		description = "A setting can depend on which option is chosen, too.",
		visibleIf = { dropdown.selectedIndex == 2 },
	)

	@JvmField
	val text = TextModuleSetting(
		id = "text",
		label = "Some text",
		defaultValue = "Hello",
		maxLength = 40,
		hint = "Type something",
		description = "Free text. The HUD element repeats whatever is written here.",
	)

	@JvmField
	val keybind = KeybindModuleSetting(
		id = "keybind",
		label = "A key",
		description = "Click, then press a key. Escape or backspace clears it.",
	)

	@JvmField
	val button = ButtonModuleSetting(
		id = "button",
		label = "A button",
		action = { Toasts.show(SOURCE, "The button was pressed") },
	)

	// ---- Colours ---------------------------------------------------------

	/** Folded away to begin with, the way a card's long tail of colours usually is. */
	private val colorSection = SectionModuleSetting("color_section", "Colors (starts folded)", startsCollapsed = true)

	@JvmField
	val color = ColorModuleSetting(
		id = "color",
		label = "A color",
		defaultRgb = 0x998DF4,
		description = "Click the swatch for the picker, or type a hex value.",
	)

	@JvmField
	val alphaColor = ColorModuleSetting(
		id = "alpha_color",
		label = "With opacity",
		defaultRgb = 0x55FFFF,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		description = "The same, with a fourth channel for how see-through it is.",
	)

	@JvmField
	val module = Module(
		id = "example_module",
		name = "Example Module",
		description = "One of every control, wired to nothing",
		category = ModuleCategory.MISC,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			switchSection, toggle, dependent,
			numberSection, wholeSlider, decimalSlider, range,
			choiceSection, dropdown, dropdownExtra, text, keybind, button,
			colorSection, color, alphaColor,
		),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(ExampleElement())
	}

	/** What the card is set to, a line per kind of setting. */
	private fun lines(): List<Pair<String, Int>> = listOf(
		"Example Module" to (0xFF000000.toInt() or color.rgb),
		"Toggle: ${if (toggle.value) "on" else "off"}" to WHITE,
		"Sliders: ${wholeSlider.displayValue}, ${decimalSlider.displayValue}" to WHITE,
		"Range: ${range.displayLower} to ${range.displayUpper}" to WHITE,
		"Dropdown: ${dropdown.selected}" to WHITE,
		"Key: ${keybind.keyName}" to WHITE,
		"Text: ${text.value}" to MUTED,
	)

	/**
	 * A HUD element that reads the card back.
	 *
	 * Here to be dragged about in the editor as much as to be looked at: it
	 * resizes as the text setting is typed into, takes its heading's colour from
	 * the colour setting and its backdrop from the one with opacity.
	 */
	private class ExampleElement : HudElement("example_module", "Example Module", 0.70, 0.30) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = lines().maxOf { font.width(it.first) } + PADDING * 2
		override val height: Int get() = lines().size * font.lineHeight + PADDING * 2

		override fun isVisible(): Boolean = module.enabled && Minecraft.getInstance().player != null

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) {
			context.fill(0, 0, width, height, alphaColor.argb)
			lines().forEachIndexed { index, (line, argb) ->
				context.text(font, line, PADDING, PADDING + index * font.lineHeight, argb)
			}
		}

		private companion object {
			const val PADDING = 3
		}
	}
}
