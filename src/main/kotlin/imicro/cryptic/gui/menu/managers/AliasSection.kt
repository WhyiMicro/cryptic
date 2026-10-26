package imicro.cryptic.gui.menu.managers

import imicro.cryptic.feature.AliasManager
import imicro.cryptic.feature.AliasManager.Alias
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.TextField
import imicro.cryptic.gui.menu.UiText

/** The Alias Manager: a card to add or edit an alias, above the list, each with its own switch. */
class AliasSection : ManagerSection("Aliases") {
	private val name = TextField(24)
	private val command = TextField(256)

	/** The alias being edited, or null when adding. */
	private var editing: Alias? = null

	/** What was wrong with the last Add, shown until the fields change. */
	private var error: String? = null
	private var errorFor = ""

	private val deletes = DeleteConfirm()

	override fun onClose() = AliasManager.save()

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		cy += drawEditor(x, cy, width) + 14f

		if (AliasManager.aliases.isEmpty()) {
			ui.text("No aliases yet. Add one above and type it in chat like any command.", x + 2f, cy + 4f, 13f, MenuStyle.MUTED)
			return cy + 24f - y
		}
		Sections.heading(ui, "Your aliases", x, cy)
		cy += Sections.HEADING
		for (alias in AliasManager.aliases.toList()) cy += drawRow(alias, x, cy, width) + 2f
		return cy - y
	}

	private fun drawEditor(x: Float, y: Float, width: Float): Float {
		val pad = 18f
		val typed = name.text + "\u0000" + command.text
		if (typed != errorFor) error = null
		val height = 18f + 22f + 34f + 12f + (if (error != null) 20f else 0f) + 36f + 14f
		ui.card(Box(x, y, width, height))

		var cy = y + 18f
		ui.text(editing?.let { "Edit /${it.name}" } ?: "Add an alias", x + pad, cy, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		cy += 22f + 6f

		val fieldH = 34f
		val nameW = 170f
		ui.field(Box(x + pad, cy, nameW, fieldH), name, "/name")
		ui.icon(Sections.ARROW_RIGHT, Box(x + pad + nameW, cy, 34f, fieldH), 12f, MenuStyle.MUTED)

		var right = x + width - pad
		val saveLabel = if (editing == null) "Add" else "Save"
		val saveW = ui.buttonWidth(saveLabel)
		ui.button(Box(right - saveW, cy, saveW, fieldH), saveLabel, primary = true) { submit() }
		right -= saveW + 8f
		if (editing != null) {
			val cancelW = ui.buttonWidth("Cancel")
			ui.button(Box(right - cancelW, cy, cancelW, fieldH), "Cancel") { clear() }
			right -= cancelW + 8f
		}
		val commandX = x + pad + nameW + 34f
		ui.field(Box(commandX, cy, right - commandX, fieldH), command, "party kick")
		cy += fieldH + 12f

		error?.let {
			ui.text(it, x + pad, cy, 12f, Sections.DANGER)
			cy += 20f
		}
		ui.text("What you type after it goes on the end, or where {args} is; {1}, {2} take one word each.", x + pad, cy, 12f, MenuStyle.MUTED)
		ui.text("Several commands: separate them with ;   e.g.  p disband; p invite {1}", x + pad, cy + 18f, 12f, MenuStyle.MUTED)
		return height
	}

	private fun drawRow(alias: Alias, x: Float, y: Float, width: Float): Float {
		val h = 46f
		if (y + h < ui.area.y || y > ui.area.bottom) return h
		ui.card(Box(x, y, width, h))
		val pad = 18f
		val cy = y + h / 2f

		var right = x + width - pad
		right -= Sections.deleteButton(ui, deletes, alias, right, cy) {
			AliasManager.remove(alias)
			if (editing === alias) clear()
		} + 4f
		ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), Sections.EDIT) { edit(alias) }
		right -= 28f + 12f
		ui.switch(Box(right - 32f, cy - 7.5f, 32f, 15f), alias.enabled, alias) { AliasManager.toggle(alias) }
		right -= 32f + 16f

		val nameText = "/${alias.name}"
		ui.text(nameText, x + pad, cy - 7f, 14f, if (alias.enabled) MenuStyle.accent() else MenuStyle.MUTED, UiText.Face.MEDIUM)
		val arrowX = x + pad + ui.width(nameText, 14f, UiText.Face.MEDIUM) + 8f
		ui.icon(Sections.ARROW_RIGHT, Box(arrowX, cy - 9f, 18f, 18f), 10f, MenuStyle.MUTED)
		val commandX = arrowX + 26f
		val shown = alias.command.split(';').joinToString("; ") { "/" + it.trim().removePrefix("/") }
		ui.text(ui.fit(shown, 13f, right - commandX), commandX, cy - 6.5f, 13f, if (alias.enabled) MenuStyle.TEXT else MenuStyle.MUTED)
		return h
	}

	private fun submit() {
		val cleanName = AliasManager.clean(name.text)
		val line = command.text.trim()
		val problem = AliasManager.problemWith(cleanName, editing)
			?: if (line.removePrefix("/").isBlank()) "Give it a command to run." else null
		if (problem != null) {
			error = problem
			errorFor = name.text + "\u0000" + command.text
			return
		}
		AliasManager.put(Alias(cleanName, line, editing?.enabled ?: true), editing)
		clear()
	}

	private fun edit(alias: Alias) {
		editing = alias
		name.set(alias.name)
		command.set(alias.command)
		error = null
	}

	private fun clear() {
		editing = null
		name.clear()
		command.clear()
		error = null
		ui.blur()
	}
}
