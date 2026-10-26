package imicro.cryptic.gui.menu

import imicro.cryptic.feature.ClickGui
import imicro.cryptic.render.RoundRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.exp
import kotlin.math.roundToInt

class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
	val right get() = x + w
	val bottom get() = y + h
	fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h

	companion object {
		val NONE = Box(0f, 0f, 0f, 0f)
	}
}

object MenuStyle {
	const val WINDOW_BG = 0xFF131315.toInt()
	const val WINDOW_EDGE = 0xFF26262A.toInt()
	const val TAB_PILL = 0xFF222225.toInt()
	const val BUTTON_HOVER = 0xFF232327.toInt()
	const val FIELD = 0xFF1A1A1D.toInt()
	const val FIELD_EDGE = 0xFF2A2A2F.toInt()
	const val FIELD_EDGE_HOVER = 0xFF3A3A40.toInt()
	const val PLACEHOLDER = 0xFF6C6C73.toInt()
	const val TEXT = 0xFFF1F1F2.toInt()
	const val TAB_HOVER = 0xFFC9C9CE.toInt()
	const val MUTED = 0xFF85858C.toInt()
	const val ICON_OFF = 0xFF5E5E64.toInt()
	const val STAR = 0xFFF4C430.toInt()
	const val DANGER = 0xFFDF4A4F.toInt()
	const val CARD_OFF = 0xFF1C1C1F.toInt()
	const val CARD_OFF_HOVER = 0xFF232327.toInt()
	// The switch: a grey track filling with the accent, and a dark knob turning white as it
	// crosses.
	const val SWITCH_OFF = 0xFF474747.toInt()
	const val KNOB_OFF = 0xFF161616.toInt()
	const val KNOB_ON = 0xFFFFFFFF.toInt()
	const val SCROLL_TRACK = 0x1AFFFFFF
	const val SCROLL_THUMB = 0x4DFFFFFF
	const val SCROLL_THUMB_HOVER = 0x8CFFFFFF.toInt()
	const val TRACK = 0xFF2C2C30.toInt()
	const val ROW = 0xFF19191C.toInt()
	const val ROW_HOVER = 0xFF1E1E22.toInt()
	const val ROW_NESTED = 0xFF161619.toInt()
	const val POPUP = 0xFF1B1B1E.toInt()
	const val POPUP_HOVER = 0xFF26262B.toInt()

	/** The Font Awesome glyphs the menu uses. [STAR] comes in solid and outline. */
	object Icons {
		const val ELLIPSIS_VERTICAL = ""
		const val STAR = ""
		const val SEARCH = ""
		const val XMARK = ""
		const val EDIT = ""
		const val GEAR = ""
		const val DUNGEON = ""
		const val SKULL = ""
		const val EYE = ""
		const val SLIDERS = ""
		const val ARROW_LEFT = ""
		const val CHEVRON_DOWN = ""
		const val CHEVRON_RIGHT = ""
		const val CHECK = ""
	}

	/** Click GUI's accent colour, used for everything "on" or "chosen". */
	fun accent(): Int = 0xFF000000.toInt() or ClickGui.accentColor.rgb

	fun cardOn(): Int = scale(accent(), 0.55f)

	fun cardOnHover(): Int = scale(accent(), 0.65f)

	fun onMuted(): Int = blend(accent(), 0xFFFFFFFF.toInt(), 0.7f)

	fun selection(): Int = (accent() and 0x00FFFFFF) or 0x66000000

	fun scale(color: Int, factor: Float): Int {
		val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
		val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
		val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
		return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
	}

	fun approach(current: Float, target: Float, speed: Float, dt: Float): Float =
		if (dt <= 0f) target else current + (target - current) * (1f - exp(-speed * dt))

	fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

	fun fade(color: Int, amount: Float): Int {
		if (amount >= 0.999f) return color
		val a = ((color ushr 24) * amount.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
		return (color and 0x00FFFFFF) or (a shl 24)
	}

	fun blend(from: Int, to: Int, t: Float): Int {
		if (t <= 0f) return from
		if (t >= 1f) return to
		var out = 0
		for (shift in intArrayOf(0, 8, 16, 24)) {
			val a = (from ushr shift) and 0xFF
			val b = (to ushr shift) and 0xFF
			out = out or (((a + (b - a) * t).toInt() and 0xFF) shl shift)
		}
		return out
	}

	/**
	 * A switch whose track and knob blend as it slides, so one flicked back halfway turns from
	 * where it is.
	 */
	fun drawSwitch(graphics: GuiGraphicsExtractor, box: Box, t: Float, alpha: Float) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(blend(SWITCH_OFF, accent(), t), alpha), box.h / 2f)
		val radius = box.h * 0.37f
		val inset = box.h / 6f
		val cx = box.x + inset + radius + (box.w - inset * 2f - radius * 2f) * t
		val cy = box.y + box.h / 2f
		RoundRect.fill(graphics, cx - radius, cy - radius, cx + radius, cy + radius, fade(blend(KNOB_OFF, KNOB_ON, t), alpha), radius)
	}

	/**
	 * Soft edges on a scrolling list: the window colour fading in over the top and bottom of [box].
	 * Each fade shows only while there's more list beyond it, and grows in over the first [topRamp]
	 * / [bottomRamp] of scrolling. Drawn after the list, inside its clip.
	 */
	fun drawScrollFades(
		graphics: GuiGraphicsExtractor, box: Box, scroll: Float, maxScroll: Float, alpha: Float,
		topRamp: Float = SCROLL_FADE, bottomRamp: Float = SCROLL_FADE,
	) {
		val top = (scroll / topRamp.coerceAtLeast(1f)).coerceIn(0f, 1f)
		val bottom = ((maxScroll - scroll) / bottomRamp.coerceAtLeast(1f)).coerceIn(0f, 1f)
		if (top > 0.01f) drawFade(graphics, box.x - 1f, box.right + 1f, box.y, 1f, alpha * top)
		if (bottom > 0.01f) drawFade(graphics, box.x - 1f, box.right + 1f, box.bottom, -1f, alpha * bottom)
	}

	/**
	 * One edge's fade: a few solid pixels, then an eased fall-off (a straight ramp shows a line).
	 * Filled a screen row at a time so there are no seams.
	 */
	private fun drawFade(graphics: GuiGraphicsExtractor, x0: Float, x1: Float, edge: Float, direction: Float, strength: Float) {
		val ppu = UiText.pixelsPerUnit
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.scale(1f / ppu, 1f / ppu)
		val left = (x0 * ppu).roundToInt()
		val right = (x1 * ppu).roundToInt()
		val start = (edge * ppu).roundToInt()
		val solid = (FADE_SOLID * ppu).roundToInt().coerceAtLeast(1)
		val length = (FADE_LENGTH * ppu).roundToInt().coerceAtLeast(1)
		val step = if (direction > 0) 1 else -1
		// Starts outside the edge: the clip rounds outward to whole GUI units, and a sliver of list
		// left there shows as a line.
		val overlap = Minecraft.getInstance().window.guiScale + ppu.roundToInt() + 1
		for (row in -overlap until solid + length) {
			val t = ((row - solid + 0.5f) / length).coerceIn(0f, 1f)
			val cover = if (row < solid) 1f else 1f - t * t * (3f - 2f * t)
			val y = if (step > 0) start + row else start - row - 1
			graphics.fill(left, y, right, y + 1, fade(WINDOW_BG, strength * cover))
		}
		pose.popMatrix()
	}

	private const val FADE_SOLID = 3f
	private const val FADE_LENGTH = 30f
	private const val SCROLL_FADE = FADE_SOLID + FADE_LENGTH

	/**
	 * A field's box: a shade off the row, with an edge that lightens on hover and turns teal while
	 * typing.
	 */
	fun drawField(graphics: GuiGraphicsExtractor, box: Box, hovered: Boolean, focused: Boolean, alpha: Float, radius: Float = 6f) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(FIELD, alpha), radius)
		val edge = when {
			focused -> accent()
			hovered -> FIELD_EDGE_HOVER
			else -> FIELD_EDGE
		}
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(edge, alpha), radius, 1f)
	}
}
