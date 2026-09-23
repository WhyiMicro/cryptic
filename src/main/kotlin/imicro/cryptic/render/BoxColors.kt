package imicro.cryptic.render

import imicro.cryptic.gui.ColorModuleSetting

/**
 * The two colours one highlighted box is drawn with.
 *
 * A style that draws both halves wants two colours — a heavy fill under a
 * bright ring is the usual thing, and neither can be got from the other. The
 * modules that offer "Fill + Outline" therefore carry a pair per thing they
 * mark, and hide whichever half the chosen style does not draw.
 */
class BoxColors(val fill: ColorModuleSetting, val outline: ColorModuleSetting) {
	/** The outline's own colour, or nothing when the style is fill only. */
	fun outlineArgb(drawsOutline: Boolean): Int = if (drawsOutline) outline.argb else 0

	/** The fill's own colour, or nothing when the style is outline only. */
	fun fillArgb(drawsFill: Boolean): Int = if (drawsFill) fill.argb else 0
}
