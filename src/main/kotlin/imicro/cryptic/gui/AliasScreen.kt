package imicro.cryptic.gui

import imgui.ImDrawList
import imgui.type.ImString
import imicro.cryptic.feature.AliasManager
import imicro.cryptic.feature.AliasManager.Alias
import net.minecraft.client.Minecraft

/**
 * The Alias Manager's window: a card to add or edit one alias above the list
 * of them.
 */
class AliasScreen: ManagerScreen("Alias Manager", "aliases") {
	private val nameBuffer = ImString(24)
	private val commandBuffer = ImString(256)

	/** The alias being edited in the top card, or null while it adds a new one. */
	private var editing: Alias? = null

	/** What was wrong with the last Add, shown until the fields change. */
	private var error: String? = null
	private var errorFor = ""

	private var deleteArmed: Alias? = null
	private var deleteArmedAt = 0L

	private var pending: (() -> Unit)? = null

	override fun onClosed() {
		AliasManager.save()
	}

	override fun afterFrame() {
		pending?.invoke()
		pending = null
		if (deleteArmed != null && System.currentTimeMillis() - deleteArmedAt > DELETE_CONFIRM_MILLIS) deleteArmed = null
	}

	override fun drawContent(draw: ImDrawList, x: Float, top: Float, width: Float): Float {
		var y = top
		val pad = dp(16f)
		val fieldHeight = dp(22f)

		y += measuredCard(draw, x, y, width) {
			var cy = y + dp(12f)
			label(draw, if (editing == null) "Add an alias" else "Edit /${editing?.name}", x + pad, cy, MUTED_TEXT, dp(9f))
			cy += dp(18f)

			val nameWidth = dp(92f)
			textField(draw, "##alias_name", "/name", nameBuffer, x + pad, cy, nameWidth, fieldHeight)
			label(draw, "->", x + pad + nameWidth + dp(5f), cy + dp(5f), MUTED_TEXT, dp(10f))
			val commandX = x + pad + nameWidth + dp(20f)

			val buttonRight = x + width - pad
			val (leftOfSave, save) = buttonLeftOf(draw, "##alias_save", if (editing == null) "Add" else "Save", buttonRight, cy, fieldHeight)
			var commandRight = leftOfSave - dp(6f)
			if (editing != null) {
				val (leftOfCancel, cancel) = buttonLeftOf(draw, "##alias_cancel", "Cancel", commandRight, cy, fieldHeight)
				if (cancel) pending = { clearFields() }
				commandRight = leftOfCancel - dp(6f)
			}
			textField(draw, "##alias_command", "party kick", commandBuffer, commandX, cy, commandRight - commandX, fieldHeight)
			if (save) pending = { submit() }
			cy += fieldHeight + dp(8f)

			val typed = nameBuffer.get() + "\u0000" + commandBuffer.get()
			if (typed != errorFor) error = null
			error?.let {
				label(draw, it, x + pad, cy, DELETE_TEXT, dp(9f))
				cy += dp(15f)
			}
			label(draw, "What you type after it goes on the end, or where {args} is; {1}, {2} take one word each.", x + pad, cy, MUTED_TEXT, dp(8.5f))
			cy += dp(13f)
			label(draw, "Several commands: separate them with ;   e.g.  p disband; p invite {1}", x + pad, cy, MUTED_TEXT, dp(8.5f))
			cy += dp(13f)
			if (!AliasManager.module.enabled) {
				cy += dp(4f)
				label(draw, "Alias Manager is off in Settings, so these go to the server as typed.", x + pad, cy + dp(3f), DELETE_TEXT, dp(9f))
				val (_, enable) = buttonLeftOf(draw, "##alias_enable", "Turn on", x + width - pad, cy, dp(18f))
				if (enable) pending = { AliasManager.module.enabled = true }
				cy += dp(20f)
			}
			cy + dp(10f) - y
		} + dp(CARD_GAP)

		if (AliasManager.aliases.isEmpty()) {
			val emptyHeight = dp(46f)
			card(draw, x, y, width, emptyHeight)
			centered(draw, "No aliases yet", x, y, width, emptyHeight, MUTED_TEXT, dp(11f))
			return y + emptyHeight - top
		}

		AliasManager.aliases.forEachIndexed { index, alias ->
			y += drawRow(draw, x, y, width, alias, index) + dp(4f)
		}
		return y - top
	}

	private fun drawRow(draw: ImDrawList, x: Float, y: Float, width: Float, alias: Alias, index: Int): Float {
		val height = dp(32f)
		if (!inView(y, height)) return height
		val pad = dp(16f)
		card(draw, x, y, width, height)

		val buttonSize = dp(18f)
		var right = x + width - pad - buttonSize
		val armed = deleteArmed === alias
		if (iconButton(draw, "##alias_del_$index", if (armed) "?" else FontAwesomeIcons.XMARK, right, y + dp(7f), buttonSize, DELETE_TEXT)) {
			if (armed) {
				pending = {
					AliasManager.remove(alias)
					if (editing === alias) clearFields()
				}
				deleteArmed = null
			} else {
				deleteArmed = alias
				deleteArmedAt = System.currentTimeMillis()
			}
		}
		right -= buttonSize + dp(4f)
		if (iconButton(draw, "##alias_edit_$index", FontAwesomeIcons.EDIT, right, y + dp(7f), buttonSize)) {
			pending = { edit(alias) }
		}
		right -= dp(SWITCH_WIDTH) + dp(10f)
		if (switch(draw, "##alias_on_$index", right, y + dp(8f), alias.enabled)) pending = { AliasManager.toggle(alias) }

		val nameText = "/${alias.name}"
		val nameColor = if (alias.enabled) ACCENT else MUTED_TEXT
		label(draw, nameText, x + pad, y + dp(9f), nameColor, dp(11f))
		val arrowX = x + pad + textWidth(nameText, dp(11f)) + dp(8f)
		label(draw, "->", arrowX, y + dp(10f), MUTED_TEXT, dp(10f))
		val commandX = arrowX + textWidth("->", dp(10f)) + dp(8f)
		val shown = alias.command.split(';').joinToString("; ") { "/" + it.trim().removePrefix("/") }
		label(draw, ellipsize(shown, dp(10f), right - dp(10f) - commandX), commandX, y + dp(10f), if (alias.enabled) TEXT else MUTED_TEXT, dp(10f))
		return height
	}

	private fun submit() {
		val name = AliasManager.clean(nameBuffer.get())
		val command = commandBuffer.get().trim()
		val problem = AliasManager.problemWith(name, editing)
			?: if (command.removePrefix("/").isBlank()) "Give it a command to run." else null
		if (problem != null) {
			error = problem
			errorFor = nameBuffer.get() + "\u0000" + commandBuffer.get()
			return
		}
		AliasManager.put(Alias(name, command, editing?.enabled ?: true), editing)
		clearFields()
	}

	private fun edit(alias: Alias) {
		editing = alias
		nameBuffer.set(alias.name)
		commandBuffer.set(alias.command)
		error = null
		scrollToTop()
	}

	private fun clearFields() {
		editing = null
		nameBuffer.clear()
		commandBuffer.clear()
		error = null
	}

	companion object {
		private const val DELETE_CONFIRM_MILLIS = 3000L

		private var requested = false

		fun request() {
			requested = true
		}

		fun openIfRequested(client: Minecraft) {
			if (!requested) return
			requested = false
			ImGuiRuntime.open(client) { AliasScreen() }
		}
	}
}
