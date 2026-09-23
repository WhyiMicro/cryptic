package imicro.cryptic.hud

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * One thing Cryptic draws on the HUD, placed by the player.
 *
 * Position is kept as a fraction of the screen rather than in pixels, so a
 * layout arranged in a window survives going full screen. Everything an
 * element draws is in its own coordinates, starting at its top-left corner:
 * [Hud] applies the placement and the scale before calling it.
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

	/** The element's size at scale one, which the editor needs to grab it. */
	abstract val width: Int
	abstract val height: Int

	/** True while the element has something to say and its module is on. */
	abstract fun isVisible(): Boolean

	/**
	 * Whether the editor should offer this element at all.
	 *
	 * Separate from [isVisible] because they answer different questions: an
	 * element with nothing to say right now is still worth placing, but one that
	 * has been configured to be drawn by something else — a score set to follow
	 * the map — has no placement of its own to arrange, and a frame in the
	 * editor for it is a control that does nothing.
	 */
	open fun showInEditor(): Boolean = true

	abstract fun render(context: GuiGraphicsExtractor)

	/**
	 * Drawn in the editor in place of [render], for elements that would
	 * otherwise be empty outside a dungeon. Defaults to the real thing.
	 */
	open fun renderExample(context: GuiGraphicsExtractor) = render(context)

	fun reset() {
		x = defaultX
		y = defaultY
		scale = defaultScale
	}

	/** Keeps a dragged element from being pushed off the edge of the screen. */
	fun clampTo(screenWidth: Int, screenHeight: Int) {
		if (screenWidth <= 0 || screenHeight <= 0) return
		val maxX = 1.0 - (width * scale) / screenWidth
		val maxY = 1.0 - (height * scale) / screenHeight
		x = x.coerceIn(0.0, maxOf(0.0, maxX))
		y = y.coerceIn(0.0, maxOf(0.0, maxY))
	}
}
