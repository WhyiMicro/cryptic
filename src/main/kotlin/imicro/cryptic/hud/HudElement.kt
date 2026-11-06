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
	/**
	 * Where the element's anchor sits, as a fraction of the screen. The anchor is a point on the
	 * element ([anchorX], [anchorY]: 0 its left or top, 0.5 its middle, 1 its right or bottom), so
	 * one placed against the right edge or on a centre line stays there as its size changes.
	 */
	var x: Double = defaultX
	var y: Double = defaultY
	var anchorX: Double = 0.0
	var anchorY: Double = 0.0
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

	/**
	 * Shifts this frame's drawing back on screen when what the element shows has grown past the
	 * right or bottom edge. The saved place stays, so it's back where it was once it shrinks again;
	 * the HUD editor pulls elements on screen when it opens. For elements whose size follows what
	 * they show.
	 */
	protected fun keepOnScreen(context: GuiGraphicsExtractor) {
		val left = Math.round(left(context.guiWidth())).toDouble()
		val top = Math.round(top(context.guiHeight())).toDouble()
		val dx = minOf(0.0, context.guiWidth() - (left + width * scale)).let { if (left + it < 0) -left else it }
		val dy = minOf(0.0, context.guiHeight() - (top + height * scale)).let { if (top + it < 0) -top else it }
		if (dx != 0.0 || dy != 0.0) context.pose().translate((dx / scale).toFloat(), (dy / scale).toFloat())
	}

	/** The left edge in pixels, on a screen [screenWidth] wide. */
	fun left(screenWidth: Int): Double = x * screenWidth - width * scale * anchorX

	/** The top edge in pixels, on a screen [screenHeight] tall. */
	fun top(screenHeight: Int): Double = y * screenHeight - height * scale * anchorY

	/** Puts the top-left corner at [left], [top] pixels, keeping the anchors. */
	fun moveTo(left: Double, top: Double, screenWidth: Int, screenHeight: Int) {
		if (screenWidth <= 0 || screenHeight <= 0) return
		x = (left + width * scale * anchorX) / screenWidth
		y = (top + height * scale * anchorY) / screenHeight
	}

	/**
	 * Picks the anchors from where the element now sits: an edge it touches, or a centre line it's
	 * on, otherwise its top left. Called after the editor moves it.
	 */
	fun reanchor(screenWidth: Int, screenHeight: Int) {
		if (screenWidth <= 0 || screenHeight <= 0) return
		val left = left(screenWidth)
		val top = top(screenHeight)
		val w = width * scale
		val h = height * scale
		anchorX = anchorFor(left, w, screenWidth)
		anchorY = anchorFor(top, h, screenHeight)
		moveTo(left, top, screenWidth, screenHeight)
	}

	private fun anchorFor(start: Double, size: Double, screen: Int): Double = when {
		start <= EDGE -> 0.0
		start + size >= screen - EDGE -> 1.0
		kotlin.math.abs(start + size / 2 - screen / 2.0) <= EDGE -> 0.5
		else -> 0.0
	}

	fun reset() {
		x = defaultX
		y = defaultY
		anchorX = 0.0
		anchorY = 0.0
		scale = defaultScale
	}

	fun clampTo(screenWidth: Int, screenHeight: Int) {
		if (screenWidth <= 0 || screenHeight <= 0) return
		val maxLeft = maxOf(0.0, screenWidth - width * scale)
		val maxTop = maxOf(0.0, screenHeight - height * scale)
		moveTo(left(screenWidth).coerceIn(0.0, maxLeft), top(screenHeight).coerceIn(0.0, maxTop), screenWidth, screenHeight)
	}

	private companion object {
		/** How close, in pixels, counts as touching an edge or sitting on a centre line. */
		const val EDGE = 1.0
	}
}
