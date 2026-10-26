package imicro.cryptic.gui.menu.managers

import imicro.cryptic.feature.PartyBlacklist
import imicro.cryptic.feature.PartyBlacklist.Entry
import imicro.cryptic.feature.Toasts
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.TextField
import imicro.cryptic.gui.menu.UiText
import kotlin.math.roundToInt

/** The Party Blacklist: a card to add or edit a player, above the list with each one's face. */
class BlacklistSection : ManagerSection("Blacklist") {
	private val name = TextField(16) { Character.isLetterOrDigit(it) || it == '_'.code }
	private val reason = TextField(120)

	/** The entry being edited, or null when adding. */
	private var editing: Entry? = null

	private var error: String? = null
	private var errorFor = ""

	private val deletes = DeleteConfirm()

	override fun onClose() = PartyBlacklist.save()

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		cy += drawEditor(x, cy, width) + 14f

		if (PartyBlacklist.entries.isEmpty()) {
			ui.text("Nobody yet. Add a player above, or type /cryptic blacklist add <name> <reason>.", x + 2f, cy + 4f, 13f, MenuStyle.MUTED)
			return cy + 24f - y
		}
		Sections.heading(ui, "Blacklisted (${PartyBlacklist.entries.size})", x, cy)
		cy += Sections.HEADING
		// Newest first: the one just added is the one being looked for.
		for (entry in PartyBlacklist.entries.sortedByDescending { it.added }) cy += drawRow(entry, x, cy, width) + 2f
		return cy - y
	}

	private fun drawEditor(x: Float, y: Float, width: Float): Float {
		val pad = 18f
		val typed = name.text + "\u0000" + reason.text
		if (typed != errorFor) error = null
		val height = 18f + 22f + 6f + 34f + 12f + (if (error != null) 20f else 0f) + 18f + 14f
		ui.card(Box(x, y, width, height))

		var cy = y + 18f
		ui.text(editing?.let { "Edit ${it.name}" } ?: "Add a player", x + pad, cy, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		cy += 22f + 6f

		val fieldH = 34f
		val nameW = 170f
		ui.field(Box(x + pad, cy, nameW, fieldH), name, "Name")

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
		val reasonX = x + pad + nameW + 8f
		ui.field(Box(reasonX, cy, right - reasonX, fieldH), reason, "Reason")
		cy += fieldH + 12f

		error?.let {
			ui.text(it, x + pad, cy, 12f, Sections.DANGER)
			cy += 20f
		}
		val state = if (PartyBlacklist.module.enabled) "They're kicked when they join while you lead." else "The module is off: nobody is kicked until you turn it on."
		ui.text(state, x + pad, cy, 12f, MenuStyle.MUTED)
		return height
	}

	private fun drawRow(entry: Entry, x: Float, y: Float, width: Float): Float {
		val h = 52f
		if (y + h < ui.area.y || y > ui.area.bottom) return h
		ui.card(Box(x, y, width, h), if (editing === entry) MenuStyle.scale(MenuStyle.accent(), 0.28f) or 0xFF000000.toInt() else MenuStyle.ROW)
		val pad = 14f
		val cy = y + h / 2f

		val face = 28f
		drawFace(entry.name, x + pad, cy - face / 2f, face)

		var right = x + width - pad
		right -= Sections.deleteButton(ui, deletes, entry, right, cy) {
			PartyBlacklist.remove(entry)
			if (editing === entry) clear()
		} + 4f
		ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), Sections.EDIT) { edit(entry) }
		right -= 28f + 12f

		val date = PartyBlacklist.date(entry.added)
		val dateW = ui.width(date, 12f)
		ui.text(date, right - dateW, cy - 6f, 12f, MenuStyle.MUTED)
		right -= dateW + 16f

		val textX = x + pad + face + 12f
		ui.text(entry.name, textX, cy - 15f, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		val shown = entry.reason.ifEmpty { "No reason given" }
		ui.text(ui.fit(shown, 12f, right - textX), textX, cy + 3f, 12f, if (entry.reason.isEmpty()) MenuStyle.MUTED else MenuStyle.TAB_HOVER)
		return h
	}

	/** The player's face as toasts draw it, snapped to real pixels so it stays sharp. */
	private fun drawFace(player: String, x: Float, y: Float, size: Float) {
		val ppu = UiText.pixelsPerUnit
		val pose = ui.graphics.pose()
		pose.pushMatrix()
		pose.scale(1f / ppu, 1f / ppu)
		Toasts.drawFace(ui.graphics, player, (x * ppu).roundToInt(), (y * ppu).roundToInt(), (size * ppu).roundToInt())
		pose.popMatrix()
	}

	private fun submit() {
		val player = name.text.trim()
		val problem = when {
			player.isEmpty() -> "Give a name."
			!PartyBlacklist.NAME.matches(player) -> "That isn't a Minecraft name."
			else -> null
		}
		if (problem != null) {
			error = problem
			errorFor = name.text + "\u0000" + reason.text
			return
		}
		val replacing = editing ?: PartyBlacklist.find(player)
		PartyBlacklist.put(Entry(player, reason.text.trim(), replacing?.added ?: 0L), replacing)
		clear()
	}

	private fun edit(entry: Entry) {
		editing = entry
		name.set(entry.name)
		reason.set(entry.reason)
		error = null
	}

	private fun clear() {
		editing = null
		name.clear()
		reason.clear()
		error = null
		ui.blur()
	}
}
