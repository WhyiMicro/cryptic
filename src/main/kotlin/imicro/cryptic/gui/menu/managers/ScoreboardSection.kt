package imicro.cryptic.gui.menu.managers

import imicro.cryptic.feature.CustomScoreboard
import imicro.cryptic.feature.CustomScoreboard.Line
import imicro.cryptic.feature.CustomScoreboard.Source
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.UiText

/**
 * Custom Scoreboard's Lines page: the lines it shows, top to bottom. Drag one by its grip or use
 * its arrows to move it, take it off with its cross, and add more from the chips underneath (a blank
 * line as often as you like).
 */
class ScoreboardSection : ManagerSection("Lines") {
	/** The row being dragged, by its place in the list. */
	private var dragging: Int? = null
	private var listTop = 0f

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		ui.text("Top to bottom, the lines the scoreboard shows. Drag one by its grip or use the arrows", x + 2f, cy + 2f, 13f, MenuStyle.MUTED)
		ui.text("to move it; the cross takes it off. A line with nothing to show right now is left out.", x + 2f, cy + 20f, 13f, MenuStyle.MUTED)
		val resetW = ui.buttonWidth("Reset")
		ui.button(Box(x + width - resetW, cy, resetW, 32f), "Reset") {
			CustomScoreboard.resetOrder()
			dragging = null
		}
		cy += 46f

		val list = CustomScoreboard.lines()
		listTop = cy
		if (list.isEmpty()) {
			ui.text("No lines. Add some below.", x + 2f, cy + 4f, 13f, MenuStyle.MUTED)
			cy += 28f
		}
		for ((index, line) in list.withIndex()) cy += drawRow(line, index, list.size, x, cy, width) + GAP

		cy += 14f
		Sections.heading(ui, "Add a line", x, cy)
		cy += Sections.HEADING
		cy += ui.chips(x, cy, width, CustomScoreboard.addable(), { it.label }, { false }) { CustomScoreboard.add(it) } + 8f
		return cy - y
	}

	private fun drawRow(line: Line, index: Int, count: Int, x: Float, y: Float, width: Float): Float {
		if (y + ROW < ui.area.y || y > ui.area.bottom) return ROW
		val box = Box(x, y, width, ROW)
		ui.card(box, if (dragging == index) MenuStyle.ROW_HOVER else MenuStyle.ROW)
		val cy = y + ROW / 2f

		// The grip: press and drag up or down.
		val grip = Box(x + 6f, y, 28f, ROW)
		ui.icon(GRIP, grip, 12f, if (dragging == index || ui.hovered(grip)) MenuStyle.TEXT else MenuStyle.MUTED)
		ui.onPress(grip, drag = { _, my -> dragTo(my, count) }) { button, _, _ -> if (button == 0) dragging = index }

		var left = x + 40f
		val name = line.label
		ui.text(name, left, cy - 7f, 14f, if (line == Line.BLANK) MenuStyle.MUTED else MenuStyle.TEXT, UiText.Face.MEDIUM)
		left += ui.width(name, 14f, UiText.Face.MEDIUM) + 12f

		var right = x + width - 10f
		ui.iconButton(Box(right - 26f, cy - 13f, 26f, 26f), REMOVE, MenuStyle.MUTED) { CustomScoreboard.remove(index) }
		right -= 26f + 8f
		ui.iconButton(Box(right - 26f, cy - 13f, 26f, 26f), DOWN, if (index < count - 1) MenuStyle.TAB_HOVER else MenuStyle.ICON_OFF) {
			CustomScoreboard.move(index, 1)
		}
		right -= 26f + 2f
		ui.iconButton(Box(right - 26f, cy - 13f, 26f, 26f), UP, if (index > 0) MenuStyle.TAB_HOVER else MenuStyle.ICON_OFF) {
			CustomScoreboard.move(index, -1)
		}
		right -= 26f + 12f
		val source = when (line.source) {
			Source.SIDEBAR -> "Sidebar"
			Source.TAB -> "Tab list"
			Source.CRYPTIC -> "Cryptic"
		}
		val badgeW = maxOf(34f, ui.width(source, 11f, UiText.Face.MEDIUM) + 16f)
		ui.badge(source, right - badgeW, cy - 11f)
		right -= badgeW + 12f

		if (line.example.isNotEmpty() && right - left > 40f) {
			ui.text(ui.fit(line.example, 12f, right - left), left, cy - 6f, 12f, MenuStyle.MUTED)
		}
		return ROW
	}

	/** Moves the dragged row to the row under the pointer. */
	private fun dragTo(my: Float, count: Int) {
		val from = dragging ?: return
		val to = ((my - listTop) / (ROW + GAP)).toInt().coerceIn(0, count - 1)
		if (to == from) return
		CustomScoreboard.moveTo(from, to)
		dragging = to
	}

	override fun mouseReleased(): Boolean {
		dragging = null
		return super.mouseReleased()
	}

	private companion object {
		const val ROW = 34f
		const val GAP = 2f

		const val GRIP = "\uF58E" // grip-vertical
		const val UP = "\uF077" // chevron-up
		const val DOWN = "\uF078" // chevron-down
		const val REMOVE = "\uF00D" // xmark
	}
}
