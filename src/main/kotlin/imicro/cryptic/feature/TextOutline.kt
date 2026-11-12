package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Style

/**
 * Outlines every shadowed letter by drawing its shadow on all sides, the look of SkyOcean's Full
 * Text Shadow, and can make all text bold. The drawing is in `TextOutlineMixins`.
 *
 * Cheaper than drawing the shadow eight times around every letter: copies the letter itself
 * covers are skipped (bold letters need 10, not 16), Rounded drops the corners (4 or 6), and an
 * underline's outline is one quad.
 */
object TextOutline {
	const val OFF = 0
	const val SQUARE = 1
	const val ROUNDED = 2

	@JvmField
	val outline = ToggleModuleSetting(
		id = "outline",
		label = "Outline text",
		defaultValue = true,
		description = "Draws the text shadow on every side of each letter instead of only below it. Text " +
			"drawn without a shadow, such as chest titles, stays as it is.",
	)

	@JvmField
	val shape = DropdownModuleSetting(
		id = "shape",
		label = "Outline shape",
		options = listOf("Square", "Rounded"),
		description = "Rounded leaves out the corners: four copies of each letter instead of eight, about " +
			"half the cost.",
	)

	@JvmField
	val colour = DropdownModuleSetting(
		id = "colour",
		label = "Outline colour",
		options = listOf("Text shadow", "Black"),
		description = "Text shadow darkens each letter's own colour, as Minecraft's shadow does.",
	)

	@JvmField
	val bold = ToggleModuleSetting(
		id = "bold",
		label = "Bold all text",
		defaultValue = false,
		description = "Draws every letter bold. Bold text is wider, so long lines wrap sooner and some " +
			"labels may not fit.",
	)

	@JvmField
	val module = Module(
		id = "text_outline",
		name = "Text Outline",
		description = "Outline and bold all text",
		category = ModuleCategory.VISUAL,
		settings = listOf(outline.withMore(shape, colour), bold),
	)

	// Read once per glyph by the mixins, so kept as plain fields rather than settings lookups.
	@JvmField var outlineMode = OFF
	@JvmField var blackOutline = false
	@JvmField var boldAll = false

	fun tick(client: Minecraft) {
		val on = module.enabled
		outlineMode = when {
			!on || !outline.value -> OFF
			shape.selectedIndex == 1 -> ROUNDED
			else -> SQUARE
		}
		blackOutline = colour.selectedIndex == 1
		val wantBold = on && bold.value
		if (wantBold != boldAll) {
			boldAll = wantBold
			// Chat lines are wrapped when they arrive; wrap them again at the new width.
			client.gui.hud.chat.rescaleChat()
		}
	}

	@JvmStatic
	fun outlineColour(shadowColour: Int): Int = if (blackOutline) shadowColour and -0x1000000 else shadowColour

	private class Bolded(@JvmField val plain: Style, @JvmField val bold: Style)

	private const val CACHE_SIZE = 64

	/** Bold styles by the plain one's identity, so text doesn't make a new style every frame. */
	private val boldCache = arrayOfNulls<Bolded>(CACHE_SIZE)

	@JvmStatic
	fun bolded(style: Style): Style {
		val slot = System.identityHashCode(style) and (CACHE_SIZE - 1)
		val hit = boldCache[slot]
		if (hit != null && hit.plain === style) return hit.bold
		val bolded = style.withBold(true)
		boldCache[slot] = Bolded(style, bolded)
		return bolded
	}
}
