package imicro.cryptic.hud

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * One thing drawn on the HUD. Position is a fraction of the screen so a layout survives resizing;
 * drawing is in the element's own coordinates, with placement and scale applied by [Hud].
 */
abstract class HudElement(
	val id: String,
	val name: String,
	private val defaultX: Double,
	private val defaultY: Double,
	private val defaultScale: Double = 1.0,
) {
	var x: Double = defaultX
	var y: Double = defaultY
	var scale: Double = defaultScale

	/** The element's size at scale one. */
	abstract val width: Int
	abstract val height: Int

	abstract fun isVisible(): Boolean

	/**
	 * The editor layer (a menu tab) this element sits in. Null finds it from the module of the
	 * feature that declares the element.
	 */
	open val layer: imicro.cryptic.gui.ModuleCategory? get() = null

	/**
	 * Whether the editor offers this element. False for elements drawn as part of another one (a
	 * score attached to the map).
	 */
	open fun showInEditor(): Boolean = true

	abstract fun render(context: GuiGraphicsExtractor)

	/** Drawn in the editor instead of [render], for elements that are empty outside a dungeon. */
	open fun renderExample(context: GuiGraphicsExtractor) = render(context)

	fun reset() {
		x = defaultX
		y = defaultY
		scale = defaultScale
	}

	fun clampTo(screenWidth: Int, screenHeight: Int) {
		if (screenWidth <= 0 || screenHeight <= 0) return
		val maxX = 1.0 - (width * scale) / screenWidth
		val maxY = 1.0 - (height * scale) / screenHeight
		x = x.coerceIn(0.0, maxOf(0.0, maxX))
		y = y.coerceIn(0.0, maxOf(0.0, maxY))
	}
}
