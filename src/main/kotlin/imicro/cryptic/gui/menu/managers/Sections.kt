package imicro.cryptic.gui.menu.managers

import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.UiKit
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.render.RoundRect

/** A delete that asks once: the first click arms it, a second within a few seconds deletes. */
class DeleteConfirm {
	private var armed: Any? = null
	private var armedAt = 0L

	fun isArmed(key: Any): Boolean {
		if (armed != null && System.currentTimeMillis() - armedAt > CONFIRM_MILLIS) armed = null
		return armed === key
	}

	fun click(key: Any): Boolean {
		if (isArmed(key)) {
			armed = null
			return true
		}
		armed = key
		armedAt = System.currentTimeMillis()
		return false
	}

	private companion object {
		const val CONFIRM_MILLIS = 3000L
	}
}

/** What the managers share: headings, the delete button, a few glyphs and colours. */
object Sections {
	const val HEADING = 30f

	const val DANGER = MenuStyle.DANGER

	const val ARROW_RIGHT = ""
	const val EDIT = ""
	const val TRASH = ""
	const val PLAY = ""
	const val UNDO = ""
	const val PLUS = ""
	const val MINUS = ""
	const val CHECK = ""

	/** A small uppercase heading over a group of rows. */
	fun heading(ui: UiKit, text: String, x: Float, y: Float) =
		ui.text(text.uppercase(), x + 2f, y + 8f, 11f, MenuStyle.MUTED, UiText.Face.MEDIUM)

	/**
	 * A bin that becomes "Delete?" when clicked, right-aligned at [right] and centred on [cy]; a
	 * second click runs [delete]. Returns its width.
	 */
	fun deleteButton(ui: UiKit, confirm: DeleteConfirm, key: Any, right: Float, cy: Float, delete: () -> Unit): Float {
		val armed = confirm.isArmed(key)
		if (!armed) {
			ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), TRASH, MenuStyle.MUTED) { if (confirm.click(key)) delete() }
			return 28f
		}
		val w = ui.width("Delete?", 12f, UiText.Face.MEDIUM) + 18f
		val box = Box(right - w, cy - 13f, w, 26f)
		RoundRect.fill(ui.graphics, box.x, box.y, box.right, box.bottom, MenuStyle.fade(DANGER, ui.alpha * 0.18f), 6f)
		ui.textCentered("Delete?", box, 12f, DANGER, UiText.Face.MEDIUM)
		ui.onClick(box) { if (confirm.click(key)) delete() }
		return w
	}

	/** "1.2s", "3m 04s", or "-" for nothing yet. */
	fun duration(millis: Long): String {
		if (millis <= 0) return "-"
		val total = millis / 1000.0
		if (total < 60) return String.format(java.util.Locale.ROOT, "%.1fs", total)
		val minutes = (total / 60).toInt()
		return String.format(java.util.Locale.ROOT, "%dm %02ds", minutes, (total - minutes * 60).toInt())
	}
}
