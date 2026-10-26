package imicro.cryptic.render

import imicro.cryptic.gui.ColorModuleSetting

/** A highlighted box's fill and outline colours; "Fill + Outline" styles need both. */
class BoxColors(val fill: ColorModuleSetting, val outline: ColorModuleSetting) {
	fun outlineArgb(drawsOutline: Boolean): Int = if (drawsOutline) outline.argb else 0

	fun fillArgb(drawsFill: Boolean): Int = if (drawsFill) fill.argb else 0
}
