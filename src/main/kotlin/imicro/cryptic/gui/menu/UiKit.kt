package imicro.cryptic.gui.menu

import imicro.cryptic.gui.menu.MenuStyle.approach
import imicro.cryptic.gui.menu.MenuStyle.blend
import imicro.cryptic.gui.menu.MenuStyle.fade
import imicro.cryptic.render.RoundRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.max

/**
 * The widgets the menu's managers are built from: cards, buttons, chips, switches, sliders, fields,
 * badges and progress bars. Immediate mode: each clickable widget registers its click as it's
 * drawn, and a press goes to the last widget drawn under it (the top one).
 */
class UiKit {
	lateinit var graphics: GuiGraphicsExtractor
		private set
	var mx = 0f
		private set
	var my = 0f
		private set
	var dt = 0f
		private set
	var alpha = 1f
		private set

	private class Target(val box: Box, val press: (button: Int, x: Float, y: Float) -> Unit, val drag: ((Float, Float) -> Unit)?)

	private val targets = ArrayList<Target>()
	private var dragging: ((Float, Float) -> Unit)? = null

	private var focused: TextField? = null
	private var focusedSelecting = false
	private val fields = ArrayList<Triple<Box, TextField, Boolean>>()

	private val switches = HashMap<Any, Float>()
	private val hover = HashMap<Any, Float>()

	val typing: Boolean get() = focused != null

	/** The area the section was given this frame. */
	var area = Box.NONE
		private set

	fun begin(graphics: GuiGraphicsExtractor, area: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		this.graphics = graphics
		this.area = area
		this.mx = mx
		this.my = my
		this.dt = dt
		this.alpha = alpha
		targets.clear()
		fields.clear()
	}

	fun hovered(box: Box) = area.contains(mx, my) && box.contains(mx, my)

	fun onClick(box: Box, action: () -> Unit) {
		targets += Target(box, { button, _, _ -> if (button == 0) action() }, null)
	}

	/** What any press in [box] does, and what dragging from it does. */
	fun onPress(box: Box, drag: ((Float, Float) -> Unit)? = null, press: (button: Int, x: Float, y: Float) -> Unit) {
		targets += Target(box, press, drag)
	}

	/** A panel on its own, rounded and edged like a group of settings. */
	fun card(box: Box, color: Int = MenuStyle.ROW) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(color, alpha), GROUP_RADIUS)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.GROUP_EDGE, alpha), GROUP_RADIUS, 1f)
	}

	/** The top of the open group of rows, NaN when none is open, and its sides. */
	private var groupTop = Float.NaN
	private var groupX = 0f
	private var groupW = 0f

	/** Opens a group of [row]s at [y], as the settings list draws them: one panel, hairlines between rows. */
	fun beginGroup(x: Float, y: Float, w: Float) {
		groupTop = y
		groupX = x
		groupW = w
	}

	/**
	 * One row of the open group, lit while hovered when it has a [key]. Call it for rows scrolled out
	 * of sight too (it draws nothing there), or after [beginGroup] the first visible row is taken
	 * for the top one.
	 */
	fun row(box: Box, color: Int = MenuStyle.ROW, key: Any? = null) {
		if (box.bottom < area.y || box.y > area.bottom) return
		val lift = if (key == null) 0f else lift(key, hovered(box))
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(blend(color, MenuStyle.ROW_HOVER, lift), alpha), 0f)
		if (box.y > groupTop + 0.5f) {
			RoundRect.fill(graphics, box.x + ROW_PAD, box.y, box.right - ROW_PAD, box.y + 1f, fade(MenuStyle.ROW_LINE, alpha), 0f)
		}
	}

	/** Closes the open group at [bottom]: rounds its corners by laying the window's colour outside them, then edges it. */
	fun endGroup(bottom: Float) {
		val top = groupTop
		groupTop = Float.NaN
		if (top.isNaN() || bottom <= top) return
		val r = GROUP_RADIUS
		val k = r * 0.5f
		RoundRect.outline(graphics, groupX - k, top - k, groupX + groupW + k, bottom + k, fade(MenuStyle.WINDOW_BG, alpha), r + k, k)
		RoundRect.outline(graphics, groupX, top, groupX + groupW, bottom, fade(MenuStyle.GROUP_EDGE, alpha), r, 1f)
	}

	fun text(text: String, x: Float, y: Float, size: Float, color: Int, face: UiText.Face = UiText.Face.REGULAR) =
		UiText.draw(graphics, text, x, y, size, fade(color, alpha), face)

	fun textCentered(text: String, box: Box, size: Float, color: Int, face: UiText.Face = UiText.Face.REGULAR) =
		text(text, box.x + (box.w - UiText.width(text, size, face)) / 2f, box.y + (box.h - size) / 2f, size, color, face)

	fun icon(glyph: String, box: Box, size: Float, color: Int, outline: Boolean = false) =
		UiText.drawIconCentered(graphics, glyph, box.x + (box.w - box.h) / 2f, box.y, box.h, size, fade(color, alpha), outline)

	fun width(text: String, size: Float, face: UiText.Face = UiText.Face.REGULAR) = UiText.width(text, size, face)

	fun fit(text: String, size: Float, room: Float, face: UiText.Face = UiText.Face.REGULAR) = UiText.fit(text, size, room, face)

	/** A labelled button; [primary] fills it with the accent. */
	fun button(box: Box, label: String, primary: Boolean = false, action: () -> Unit) {
		val over = hovered(box)
		val lift = lift(box.x to label, over)
		if (primary) {
			RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(blend(MenuStyle.accent(), MenuStyle.scale(MenuStyle.accent(), 1.15f), lift), alpha), 6f)
		} else {
			MenuStyle.drawField(graphics, box, over, false, alpha)
		}
		val color = if (primary) 0xFFFFFFFF.toInt() else blend(MenuStyle.TAB_HOVER, MenuStyle.TEXT, lift)
		textCentered(label, box, 13f, color, UiText.Face.MEDIUM)
		onClick(box, action)
	}

	fun buttonWidth(label: String) = width(label, 13f, UiText.Face.MEDIUM) + 28f

	fun iconButton(box: Box, glyph: String, color: Int = MenuStyle.TAB_HOVER, outline: Boolean = false, action: () -> Unit) {
		val over = hovered(box)
		if (over) RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.BUTTON_HOVER, alpha), 6f)
		icon(glyph, box, 12f, if (over) MenuStyle.TEXT else color, outline)
		onClick(box, action)
	}

	/** A small rounded choice; returns its width. */
	fun chip(x: Float, y: Float, label: String, selected: Boolean, action: () -> Unit): Float {
		val w = chipWidth(label)
		val box = Box(x, y, w, CHIP_HEIGHT)
		val over = hovered(box)
		val fill = when {
			selected -> MenuStyle.accent()
			over -> MenuStyle.POPUP_HOVER
			else -> MenuStyle.FIELD
		}
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(fill, alpha), CHIP_HEIGHT / 2f)
		if (!selected) RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.FIELD_EDGE, alpha), CHIP_HEIGHT / 2f, 1f)
		textCentered(label, box, 12f, if (selected) 0xFFFFFFFF.toInt() else MenuStyle.TAB_HOVER)
		onClick(box, action)
		return w
	}

	fun chipWidth(label: String) = width(label, 12f) + 22f

	/** How tall [chips] will be, so a card can be sized first. */
	fun chipsHeight(w: Float, labels: List<String>): Float {
		if (labels.isEmpty()) return 0f
		var rows = 1
		var cx = 0f
		for (label in labels) {
			val cw = chipWidth(label)
			if (cx > 0f && cx + cw > w) {
				rows++
				cx = 0f
			}
			cx += cw + 6f
		}
		return rows * CHIP_HEIGHT + (rows - 1) * 6f
	}

	/** A wrapping row of chips; returns its height. */
	fun <T> chips(x: Float, y: Float, w: Float, items: List<T>, label: (T) -> String, selected: (T) -> Boolean, onClick: (T) -> Unit): Float {
		if (items.isEmpty()) return 0f
		var cx = x
		var cy = y
		for (item in items) {
			val text = label(item)
			val cw = chipWidth(text)
			if (cx > x && cx + cw > x + w) {
				cx = x
				cy += CHIP_HEIGHT + 6f
			}
			cx += chip(cx, cy, text, selected(item)) { onClick(item) } + 6f
		}
		return cy + CHIP_HEIGHT - y
	}

	fun switch(box: Box, on: Boolean, key: Any, action: () -> Unit) {
		val t = approach(switches[key] ?: if (on) 1f else 0f, if (on) 1f else 0f, 20f, dt)
		switches[key] = t
		MenuStyle.drawSwitch(graphics, box, t, alpha)
		onClick(box, action)
	}

	/** A track with a handle at [ratio]; dragging reports the new ratio. */
	fun slider(box: Box, ratio: Float, onChange: (Float) -> Unit) {
		val cy = box.y + box.h / 2f
		val t = ratio.coerceIn(0f, 1f)
		RoundRect.fill(graphics, box.x, cy - 2f, box.right, cy + 2f, fade(MenuStyle.TRACK, alpha), 2f)
		RoundRect.fill(graphics, box.x, cy - 2f, box.x + box.w * t, cy + 2f, fade(MenuStyle.accent(), alpha), 2f)
		val hx = box.x + box.w * t
		RoundRect.fill(graphics, hx - 7f, cy - 7f, hx + 7f, cy + 7f, fade(MenuStyle.TEXT, alpha), 7f)
		val set = { x: Float, _: Float -> onChange(((x - box.x) / box.w).coerceIn(0f, 1f)) }
		onPress(Box(box.x - 8f, box.y, box.w + 16f, box.h), set) { button, x, y -> if (button == 0) set(x, y) }
	}

	fun field(box: Box, field: TextField, hint: String, size: Float = 13f) {
		val focus = focused === field
		MenuStyle.drawField(graphics, box, hovered(box), focus, alpha)
		field.draw(graphics, box, size, MenuStyle.TEXT, focus, alpha, dt, placeholder = hint)
		fields += Triple(box, field, focus)
	}

	/** A key's name in a small box, lit while active. Returns its width. */
	fun badge(text: String, x: Float, y: Float, lit: Boolean = false, height: Float = 22f): Float {
		val size = 11f
		val w = max(34f, width(text, size, UiText.Face.MEDIUM) + 16f)
		val fill = if (lit) fade(MenuStyle.accent(), 0.18f) else MenuStyle.FIELD
		RoundRect.fill(graphics, x, y, x + w, y + height, fade(fill, alpha), 5f)
		RoundRect.outline(graphics, x, y, x + w, y + height, fade(MenuStyle.FIELD_EDGE, alpha), 5f, 1f)
		textCentered(text, Box(x, y, w, height), size, if (lit) MenuStyle.accent() else MenuStyle.MUTED, UiText.Face.MEDIUM)
		return w
	}

	fun progress(box: Box, ratio: Float) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.TRACK, alpha), box.h / 2f)
		val t = ratio.coerceIn(0f, 1f)
		if (t > 0f) RoundRect.fill(graphics, box.x, box.y, box.x + max(box.h, box.w * t), box.bottom, fade(MenuStyle.accent(), alpha), box.h / 2f)
	}

	private fun lift(key: Any, over: Boolean): Float =
		approach(hover[key] ?: 0f, if (over) 1f else 0f, 18f, dt).also { hover[key] = it }

	fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean): Boolean {
		val field = fields.lastOrNull { it.first.contains(mx, my) }
		if (field != null && button == 0) {
			val (box, input, wasFocused) = field
			if (!wasFocused) {
				focused = input
				input.place(box, 13f)
			}
			if (doubleClick) input.selectAll() else input.pointAt(mx)
			focusedSelecting = true
			return true
		}
		if (button == 0) focused = null
		val target = targets.lastOrNull { it.box.contains(mx, my) } ?: return false
		target.press(button, mx, my)
		dragging = target.drag
		return true
	}

	fun mouseDragged(mx: Float, my: Float): Boolean {
		if (focusedSelecting) {
			focused?.pointAt(mx, extend = true)
			return true
		}
		val drag = dragging ?: return false
		drag(mx, my)
		return true
	}

	fun mouseReleased(): Boolean {
		val was = dragging != null || focusedSelecting
		dragging = null
		focusedSelecting = false
		return was
	}

	/** Typing into the focused field; Escape and Enter leave it. */
	fun keyPressed(event: KeyEvent): Boolean {
		val field = focused ?: return false
		when (event.key()) {
			GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> focused = null
			else -> field.keyPressed(event)
		}
		return true
	}

	fun charTyped(codePoint: Int): Boolean {
		val field = focused ?: return false
		field.charTyped(codePoint)
		return true
	}

	/** Focuses [field] with the caret at the end. */
	fun focus(field: TextField) {
		focused = field
	}

	fun blur() {
		focused = null
	}

	fun isFocused(field: TextField) = focused === field

	companion object {
		const val CHIP_HEIGHT = 24f

		/** As the settings list: a group's corners, and how far in a row's hairline stops. */
		const val GROUP_RADIUS = 8f
		const val ROW_PAD = 20f
	}
}
