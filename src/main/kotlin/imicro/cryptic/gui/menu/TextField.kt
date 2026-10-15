package imicro.cryptic.gui.menu

import imicro.cryptic.gui.menu.MenuStyle.fade
import imicro.cryptic.render.RoundRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

/**
 * The editing behind every box in the menu that takes typing: the search, a
 * text setting, a number beside a slider, a colour's hex code.
 *
 * It does what a text box anywhere else does: a caret that moves with the
 * arrows, Home and End; a selection made with Shift, with Ctrl+A, or by
 * dragging the pointer across it, which typing replaces; Ctrl+C, X and V;
 * Ctrl with the arrows or Backspace working a word at a time. The text scrolls
 * sideways to keep the caret in view.
 */
class TextField(private val maxLength: Int, private val accepts: (Int) -> Boolean = { it >= ' '.code }) {
	private val content = StringBuilder()

	/** Where typing goes, and the other end of the selection; equal when nothing is selected. */
	var caret = 0
		private set
	private var anchor = 0

	/** How far the text is scrolled left to keep the caret in view, in design pixels. */
	private var scrollX = 0f
	private var blink = 0f

	/** Where the text was last drawn, for turning a pointer position into a place in it. */
	private var textX = 0f
	private var size = 13f

	val text: String get() = content.toString()
	val isEmpty: Boolean get() = content.isEmpty()
	private val hasSelection get() = caret != anchor
	private val selectionStart get() = min(caret, anchor)
	private val selectionEnd get() = max(caret, anchor)

	fun set(value: String, selectAll: Boolean = false) {
		content.setLength(0)
		content.append(value.take(maxLength))
		caret = content.length
		anchor = if (selectAll) 0 else caret
		scrollX = 0f
		blink = 0f
	}

	fun clear() = set("")

	fun selectAll() {
		anchor = 0
		caret = content.length
	}

	/**
	 * Lays the text out in [box] the way [draw] will, so a press can find its
	 * place in a field before the field has been drawn as the active one.
	 */
	fun place(box: Box, textSize: Float, padding: Float = 12f, centered: Boolean = false) {
		size = textSize
		val width = UiText.width(text, size)
		textX = if (centered && width <= box.w - padding * 2f) box.x + (box.w - width) / 2f else box.x + padding - scrollX
	}

	/** Puts the caret under the pointer; with [extend], drags the selection there instead. */
	fun pointAt(mx: Float, extend: Boolean = false) {
		val local = mx - textX
		var index = 0
		while (index < content.length) {
			val before = UiText.width(content.substring(0, index), size)
			val after = UiText.width(content.substring(0, index + 1), size)
			if (local < (before + after) / 2f) break
			index++
		}
		caret = index
		if (!extend) anchor = index
		blink = 0f
	}

	/** Handles a key; returns false for one that is not about editing text, such as Escape. */
	fun keyPressed(event: KeyEvent): Boolean {
		val control = (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0
		val shift = (event.modifiers() and GLFW.GLFW_MOD_SHIFT) != 0
		blink = 0f
		when (event.key()) {
			GLFW.GLFW_KEY_LEFT -> move(if (control) wordBefore(caret) else if (hasSelection && !shift) selectionStart else caret - 1, shift)
			GLFW.GLFW_KEY_RIGHT -> move(if (control) wordAfter(caret) else if (hasSelection && !shift) selectionEnd else caret + 1, shift)
			GLFW.GLFW_KEY_HOME -> move(0, shift)
			GLFW.GLFW_KEY_END -> move(content.length, shift)
			GLFW.GLFW_KEY_BACKSPACE -> when {
				hasSelection -> deleteSelection()
				caret > 0 -> {
					val from = if (control) wordBefore(caret) else caret - 1
					content.delete(from, caret)
					caret = from
					anchor = from
				}
			}
			GLFW.GLFW_KEY_DELETE -> when {
				hasSelection -> deleteSelection()
				caret < content.length -> content.delete(caret, if (control) wordAfter(caret) else caret + 1)
			}
			GLFW.GLFW_KEY_A -> if (control) selectAll() else return false
			GLFW.GLFW_KEY_C -> if (control) copy() else return false
			GLFW.GLFW_KEY_X -> if (control) {
				copy()
				deleteSelection()
			} else return false
			GLFW.GLFW_KEY_V -> if (control) insert(Minecraft.getInstance().keyboardHandler.clipboard) else return false
			else -> return false
		}
		return true
	}

	fun charTyped(codePoint: Int): Boolean {
		if (!accepts(codePoint)) return false
		insert(String(Character.toChars(codePoint)))
		return true
	}

	private fun move(to: Int, extend: Boolean) {
		caret = to.coerceIn(0, content.length)
		if (!extend) anchor = caret
	}

	private fun insert(text: String) {
		deleteSelection()
		val allowed = buildString {
			var index = 0
			while (index < text.length) {
				val codePoint = text.codePointAt(index)
				if (accepts(codePoint)) appendCodePoint(codePoint)
				index += Character.charCount(codePoint)
			}
		}.take(maxLength - content.length)
		content.insert(caret, allowed)
		caret += allowed.length
		anchor = caret
		blink = 0f
	}

	private fun deleteSelection() {
		if (!hasSelection) return
		val start = selectionStart
		content.delete(start, selectionEnd)
		caret = start
		anchor = start
	}

	private fun copy() {
		if (hasSelection) Minecraft.getInstance().keyboardHandler.clipboard = content.substring(selectionStart, selectionEnd)
	}

	private fun wordBefore(from: Int): Int {
		var index = from
		while (index > 0 && content[index - 1] == ' ') index--
		while (index > 0 && content[index - 1] != ' ') index--
		return index
	}

	private fun wordAfter(from: Int): Int {
		var index = from
		while (index < content.length && content[index] == ' ') index++
		while (index < content.length && content[index] != ' ') index++
		return index
	}

	/**
	 * Draws the text inside [box], with the selection behind it and the caret
	 * while [focused]; [centered] for the short numbers beside a slider.
	 */
	fun draw(
		graphics: GuiGraphicsExtractor, box: Box, textSize: Float, color: Int, focused: Boolean,
		alpha: Float, dt: Float, padding: Float = 12f, centered: Boolean = false, placeholder: String = "",
	) {
		size = textSize
		blink = (blink + dt) % 1f
		val room = box.w - padding * 2f
		val full = text
		val fullWidth = UiText.width(full, size)
		val caretOffset = UiText.width(full.substring(0, caret), size)

		if (centered && fullWidth <= room) {
			scrollX = 0f
			textX = box.x + (box.w - fullWidth) / 2f
		} else {
			// Scroll just far enough to keep the caret inside the box.
			if (caretOffset - scrollX > room) scrollX = caretOffset - room
			if (caretOffset - scrollX < 0f) scrollX = caretOffset
			scrollX = scrollX.coerceIn(0f, max(0f, fullWidth - room))
			textX = box.x + padding - scrollX
		}
		val textY = box.y + (box.h - size) / 2f

		graphics.enableScissor((box.x + 2f).toInt(), box.y.toInt(), (box.right - 2f).toInt(), box.bottom.toInt())
		if (focused && hasSelection) {
			val from = textX + UiText.width(full.substring(0, selectionStart), size)
			val to = textX + UiText.width(full.substring(0, selectionEnd), size)
			RoundRect.fill(graphics, from, box.y + 6f, to, box.bottom - 6f, fade(MenuStyle.selection(), alpha), 2f)
		}
		if (full.isEmpty() && placeholder.isNotEmpty() && !focused) {
			UiText.draw(graphics, placeholder, box.x + padding, textY, size, fade(MenuStyle.PLACEHOLDER, alpha))
		} else {
			UiText.draw(graphics, full, textX, textY, size, fade(color, alpha))
		}
		if (focused && blink < 0.55f) {
			val cx = textX + caretOffset
			RoundRect.fill(graphics, cx, box.y + 7f, cx + 1.2f, box.bottom - 7f, fade(MenuStyle.TEXT, alpha), 0f)
		}
		graphics.disableScissor()
	}
}
