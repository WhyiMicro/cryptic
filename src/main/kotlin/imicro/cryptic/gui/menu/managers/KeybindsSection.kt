package imicro.cryptic.gui.menu.managers

import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.feature.KeybindManager
import imicro.cryptic.feature.KeybindManager.Binding
import imicro.cryptic.feature.KeybindManager.WorkIn
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.TextField
import imicro.cryptic.gui.menu.UiText
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.max

/**
 * The Keybinds Manager: bindings grouped by category, each group with a switch, and an editor where
 * keys are pressed rather than typed and conditions are set, as in Athen's popup.
 */
class KeybindsSection : ManagerSection("Bindings") {
	/** The binding being edited, a copy so Cancel leaves the real one alone. */
	private var draft: Binding? = null

	/** The binding [draft] replaces on save, or null for a new one. */
	private var original: Binding? = null

	private val command = TextField(256)
	private val category = TextField(32)

	private var capturing = false
	private val captured = mutableListOf<Int>()

	private val deletes = DeleteConfirm()

	override val capturingKeys: Boolean get() = capturing || ui.typing

	override fun onClose() {
		KeybindManager.save()
		KeybindManager.releaseAll()
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val key = event.key()
		if (capturing) {
			if (key == GLFW.GLFW_KEY_ESCAPE && captured.isEmpty()) {
				capturing = false
				return true
			}
			if (key != GLFW.GLFW_KEY_UNKNOWN && key !in captured && captured.size < MAX_KEYS) captured += key
			return true
		}
		if (ui.keyPressed(event)) return true
		// Escape in the editor goes back to the list instead of closing the menu.
		if (key == GLFW.GLFW_KEY_ESCAPE && draft != null) {
			closeEditor()
			return true
		}
		return false
	}

	override fun keyReleased(event: KeyEvent): Boolean {
		if (!capturing) return false
		// Capture ends when the first key comes up.
		if (captured.isNotEmpty()) finishCapture()
		return true
	}

	override fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean): Boolean {
		if (capturing) {
			// A left click cancels, as with every other bind in the menu.
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				if (captured.isEmpty()) capturing = false else finishCapture()
			} else {
				val code = KeybindModuleSetting.mouse(button)
				if (code !in captured && captured.size < MAX_KEYS) captured += code
			}
			return true
		}
		return super.mouseClicked(mx, my, button, doubleClick)
	}

	override fun mouseReleased(): Boolean {
		if (capturing && captured.isNotEmpty()) {
			finishCapture()
			return true
		}
		return super.mouseReleased()
	}

	private fun finishCapture() {
		draft?.keys = captured.toMutableList()
		captured.clear()
		capturing = false
	}

	override fun draw(x: Float, y: Float, width: Float): Float {
		val editing = draft
		return if (editing != null) drawEditor(x, y, width, editing) else drawList(x, y, width)
	}

	private fun drawList(x: Float, y: Float, width: Float): Float {
		var cy = y
		val headH = 60f
		ui.card(Box(x, cy, width, headH))
		val count = KeybindManager.bindings.size
		ui.text(if (count == 1) "1 binding" else "$count bindings", x + 18f, cy + 14f, 15f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		ui.text("Keys that run a command or say something, wherever and whenever you choose.", x + 18f, cy + 36f, 12f, MenuStyle.MUTED)
		val newW = ui.buttonWidth("New binding")
		ui.button(Box(x + width - 18f - newW, cy + (headH - 34f) / 2f, newW, 34f), "New binding", primary = true) { openEditor(null) }
		cy += headH + 14f

		if (KeybindManager.bindings.isEmpty()) {
			ui.text("No bindings yet.", x + 2f, cy + 4f, 13f, MenuStyle.MUTED)
			return cy + 24f - y
		}

		val groups = KeybindManager.bindings.groupBy { it.category }
		val order = listOf("") + KeybindManager.categories.map { it.name }.filter { it in groups }
		for (name in order) {
			val members = groups[name] ?: continue
			if (name.isNotEmpty()) {
				val group = KeybindManager.categories.firstOrNull { it.name == name }
				Sections.heading(ui, "$name  ·  ${members.size}", x, cy)
				val on = group?.enabled != false
				ui.switch(Box(x + width - 18f - 32f, cy + 6f, 32f, 15f), on, "category:$name") { KeybindManager.toggleCategory(name) }
				cy += Sections.HEADING
			}
			for (binding in members) cy += drawRow(binding, x, cy, width) + 2f
			cy += 12f
		}
		return cy - y
	}

	private fun drawRow(binding: Binding, x: Float, y: Float, width: Float): Float {
		val h = 58f
		if (y + h < ui.area.y || y > ui.area.bottom) return h
		ui.card(Box(x, y, width, h))
		val pad = 18f
		val cy = y + h / 2f

		var right = x + width - pad
		right -= Sections.deleteButton(ui, deletes, binding, right, cy) { KeybindManager.remove(binding) } + 4f
		ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), Sections.EDIT) { openEditor(binding) }
		right -= 28f + 12f
		ui.switch(Box(right - 32f, cy - 7.5f, 32f, 15f), binding.enabled, binding) { KeybindManager.toggle(binding) }
		right -= 32f + 16f

		var cx = x + pad
		cx += ui.badge(KeybindManager.describe(binding.keys), cx, y + 9f, lit = binding.enabled) + 10f
		ui.text(ui.fit(binding.command, 13f, right - cx), cx, y + 13f, 13f, if (binding.enabled) MenuStyle.TEXT else MenuStyle.MUTED)
		ui.text(ui.fit(conditions(binding), 12f, right - x - pad), x + pad, y + 37f, 12f, MenuStyle.MUTED)
		return h
	}

	/** What a binding waits for, in one line: "Outside GUI | F7, M7 | Mage | P3". */
	private fun conditions(binding: Binding): String {
		val parts = mutableListOf(binding.workIn.title)
		if (binding.islands.isNotEmpty()) parts += binding.islands.joinToString(", ")
		if (binding.floors.isNotEmpty()) parts += binding.floors.joinToString(", ")
		if (binding.classes.isNotEmpty()) parts += binding.classes.joinToString(", ") { className(it) }
		if (binding.phases.isNotEmpty()) parts += binding.phases.sorted().joinToString(", ") { "P$it" }
		return parts.joinToString("  |  ")
	}

	private fun drawEditor(x: Float, y: Float, width: Float, binding: Binding): Float {
		var cy = y
		Sections.heading(ui, if (original == null) "New binding" else "Edit binding", x, cy)
		cy += Sections.HEADING

		val labelW = 200f
		val controlX = x + labelW
		val controlW = width - labelW - 18f

		cy += editorRow(x, cy, width, "Command", "Starts with / for a command; anything else is said in chat.", 34f) { top ->
			ui.field(Box(controlX, top, controlW, 34f), command, "/warp dungeon_hub, or a chat message")
		}

		cy += editorRow(x, cy, width, "Keys", "Hold several together for a combination. Mouse side buttons work too.", 34f) { top ->
			val box = Box(controlX, top, controlW, 34f)
			val text = when {
				capturing && captured.isEmpty() -> "Press keys... (Esc cancels)"
				capturing -> KeybindManager.describe(captured) + " ..."
				binding.keys.isEmpty() -> "Click, then press the keys"
				else -> KeybindManager.describe(binding.keys)
			}
			MenuStyle.drawField(ui.graphics, box, ui.hovered(box), capturing, ui.alpha)
			ui.textCentered(text, box, 13f, if (capturing) MenuStyle.accent() else MenuStyle.TEXT, UiText.Face.MEDIUM)
			ui.onClick(box) {
				if (!capturing) {
					captured.clear()
					capturing = true
					ui.blur()
				}
			}
		}

		val existing = KeybindManager.categories.map { it.name }
		val categoryH = 34f + if (existing.isEmpty()) 0f else 8f + ui.chipsHeight(controlW, existing)
		cy += editorRow(x, cy, width, "Category", "Groups it in the list. A group can be switched off as one.", categoryH) { top ->
			ui.field(Box(controlX, top, controlW, 34f), category, "None")
			if (existing.isNotEmpty()) {
				ui.chips(controlX, top + 42f, controlW, existing, { it }, { it == category.text.trim() }) {
					category.set(if (category.text.trim() == it) "" else it)
				}
			}
		}

		cy += chipsRow(x, cy, width, "Works", "Whether it fires with a screen open.", WorkIn.entries, { it.title }, { it == binding.workIn }) {
			binding.workIn = it
		}
		cy += chipsRow(x, cy, width, "Islands", "None picked means anywhere.", KeybindManager.ISLANDS, { it }, { it in binding.islands }) {
			if (!binding.islands.remove(it)) binding.islands += it
		}
		cy += chipsRow(x, cy, width, "Dungeon floors", "None picked means any floor.", KeybindManager.FLOORS, { it }, { it in binding.floors }) {
			if (!binding.floors.remove(it)) binding.floors += it
		}
		cy += chipsRow(x, cy, width, "Dungeon classes", "None picked means any class.", KeybindManager.CLASSES, { className(it) }, { it in binding.classes }) {
			if (!binding.classes.remove(it)) binding.classes += it
		}
		cy += chipsRow(x, cy, width, "Floor 7 phases", "None picked means any phase.", (1..5).toList(), { "P$it" }, { it in binding.phases }) {
			if (!binding.phases.remove(it)) binding.phases += it
		}

		// Cancel and Save, with what's still missing beside them.
		cy += 12f
		val problem = when {
			command.text.isBlank() -> "Give it a command or a message."
			binding.keys.isEmpty() -> "Give it a key."
			else -> null
		}
		problem?.let { ui.text(it, x + 2f, cy + 10f, 13f, MenuStyle.MUTED) }
		val saveW = ui.buttonWidth("Save")
		val cancelW = ui.buttonWidth("Cancel")
		ui.button(Box(x + width - saveW, cy, saveW, 34f), "Save", primary = true) { if (problem == null) saveDraft() }
		ui.button(Box(x + width - saveW - 8f - cancelW, cy, cancelW, 34f), "Cancel") { closeEditor() }
		cy += 34f
		return cy - y
	}

	/** An editor row: name and hint on the left, control on the right. */
	private fun editorRow(x: Float, y: Float, width: Float, label: String, hint: String, controlH: Float, control: (top: Float) -> Unit): Float {
		val h = max(56f, controlH + 28f)
		ui.card(Box(x, y, width, h))
		ui.text(label, x + 18f, y + 12f, 14f, MenuStyle.TEXT)
		ui.text(ui.fit(hint, 12f, 175f), x + 18f, y + 32f, 12f, MenuStyle.MUTED)
		control(y + 14f)
		return h + 2f
	}

	private fun <T> chipsRow(
		x: Float, y: Float, width: Float, label: String, hint: String,
		items: List<T>, name: (T) -> String, selected: (T) -> Boolean, onClick: (T) -> Unit,
	): Float {
		val controlW = width - 200f - 18f
		val chipsH = ui.chipsHeight(controlW, items.map(name))
		return editorRow(x, y, width, label, hint, chipsH) { top ->
			ui.chips(x + 200f, top, controlW, items, name, selected, onClick)
		}
	}

	private fun openEditor(binding: Binding?) {
		original = binding
		val copy = binding?.copy() ?: Binding()
		draft = copy
		command.set(copy.command)
		category.set(copy.category)
		capturing = false
		captured.clear()
		ui.blur()
	}

	private fun closeEditor() {
		draft = null
		original = null
		capturing = false
		captured.clear()
		ui.blur()
	}

	private fun saveDraft() {
		val binding = draft ?: return
		binding.command = command.text.trim()
		binding.category = category.text.trim()
		KeybindManager.put(binding, original)
		closeEditor()
	}

	private fun className(value: DungeonTeam.DungeonClass): String =
		value.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

	private companion object {
		const val MAX_KEYS = 4
	}
}
