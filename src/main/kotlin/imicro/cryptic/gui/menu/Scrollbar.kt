package imicro.cryptic.gui.menu

import imicro.cryptic.gui.menu.MenuStyle.approach
import imicro.cryptic.gui.menu.MenuStyle.fade
import imicro.cryptic.render.RoundRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

/**
 * A thin rounded track with a thumb sized to the visible fraction. Dragging the thumb scrolls;
 * clicking the track jumps there and continues as a drag.
 */
class Scrollbar {
	private var track = Box.NONE
	private var thumb = Box.NONE
	private var dragging = false
	private var grab = 0f
	private var lift = 0f

	private var active = false

	fun draw(
		graphics: GuiGraphicsExtractor, x: Float, y: Float, h: Float,
		contentH: Float, scroll: Float, maxScroll: Float,
		mx: Float, my: Float, dt: Float, alpha: Float,
	) {
		active = maxScroll > 0f
		if (!active) {
			dragging = false
			return
		}
		track = Box(x - 5f, y, WIDTH + 10f, h)
		val thumbH = max(36f, h * h / contentH)
		val thumbY = y + (h - thumbH) * (scroll / maxScroll).coerceIn(0f, 1f)
		thumb = Box(x, thumbY, WIDTH, thumbH)

		val hovered = dragging || track.contains(mx, my)
		lift = approach(lift, if (hovered) 1f else 0f, 18f, dt)
		RoundRect.fill(graphics, x, y, x + WIDTH, y + h, fade(MenuStyle.SCROLL_TRACK, alpha), WIDTH / 2f)
		// Neutral, not the accent: a scrollbar isn't a choice.
		val color = MenuStyle.blend(MenuStyle.SCROLL_THUMB, MenuStyle.SCROLL_THUMB_HOVER, lift)
		RoundRect.fill(graphics, x, thumbY, x + WIDTH, thumbY + thumbH, fade(color, alpha), WIDTH / 2f)
	}

	/** A press on the bar: starts a drag, jumping the thumb under the pointer if it missed. */
	fun press(mx: Float, my: Float, maxScroll: Float, setScroll: (Float) -> Unit): Boolean {
		if (!active || !track.contains(mx, my)) return false
		dragging = true
		grab = if (my >= thumb.y && my < thumb.bottom) my - thumb.y else thumb.h / 2f
		drag(my, maxScroll, setScroll)
		return true
	}

	fun drag(my: Float, maxScroll: Float, setScroll: (Float) -> Unit): Boolean {
		if (!dragging) return false
		val travel = track.h - thumb.h
		val t = if (travel <= 0f) 0f else ((my - grab - track.y) / travel).coerceIn(0f, 1f)
		setScroll(t * maxScroll)
		return true
	}

	fun release(): Boolean {
		val was = dragging
		dragging = false
		return was
	}

	companion object {
		const val WIDTH = 5f

		/** Centred in the menu's 20-pixel right margin. */
		const val GAP = (20f - WIDTH) / 2f
	}
}
