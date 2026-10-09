package imicro.cryptic.gui

import imgui.ImDrawList
import imgui.type.ImString
import imicro.cryptic.feature.KeybindManager
import imicro.cryptic.feature.KeybindManager.Binding
import imicro.cryptic.feature.KeybindManager.WorkIn
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * The Keybinds Manager's window: the list of bindings, and the editor for one.
 *
 * The list is grouped by category, each with a switch that turns the whole
 * group off. The editor is where a binding's keys are pressed rather than
 * typed, and where it is told when it may fire, as Athen's popup does.
 */
class KeybindsScreen: ManagerScreen("Keybinds Manager", "keybinds") {
	/** The binding being edited, a copy so Cancel leaves the real one as it was. */
	private var draft: Binding? = null

	/** The real binding [draft] replaces when saved, or null for a new one. */
	private var original: Binding? = null

	private val commandBuffer = ImString(256)
	private val categoryBuffer = ImString(32)

	/** Keys being pressed for the draft, while it is listening. */
	private var capturing = false
	private val captured = mutableListOf<Int>()

	/** The binding whose delete button has been pressed once, and when. */
	private var deleteArmed: Binding? = null
	private var deleteArmedAt = 0L

	/** Applied after the frame, so the list is not changed while it is drawn. */
	private var pending: (() -> Unit)? = null

	override fun onClosed() {
		KeybindManager.save()
		KeybindManager.releaseAll()
	}

	// ---- Capturing keys ---------------------------------------------------

	override fun captureKey(key: Int): Boolean {
		if (capturing) {
			if (key == GLFW.GLFW_KEY_ESCAPE && captured.isEmpty()) {
				capturing = false
				return true
			}
			if (key != GLFW.GLFW_KEY_UNKNOWN && key !in captured && captured.size < MAX_KEYS) captured += key
			return true
		}
		// Escape in the editor goes back to the list, not out of the window.
		if (key == GLFW.GLFW_KEY_ESCAPE && draft != null && !isTyping) {
			closeEditor()
			return true
		}
		return false
	}

	override fun releaseKey(key: Int): Boolean {
		if (!capturing) return false
		// The keys are done being pressed once the first of them comes up.
		if (captured.isNotEmpty()) finishCapture()
		return true
	}

	override fun captureMouse(button: Int): Boolean {
		if (!capturing) return false
		// A left click is how every other bind in the menu is cancelled.
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			if (captured.isEmpty()) capturing = false else finishCapture()
			return true
		}
		val code = KeybindModuleSetting.mouse(button)
		if (code !in captured && captured.size < MAX_KEYS) captured += code
		return true
	}

	override fun releaseMouse(button: Int): Boolean {
		if (!capturing || button == GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
		if (captured.isNotEmpty()) finishCapture()
		return true
	}

	private fun finishCapture() {
		draft?.keys = captured.toMutableList()
		captured.clear()
		capturing = false
	}

	// ---- Drawing ------------------------------------------------------------

	override fun drawContent(draw: ImDrawList, x: Float, top: Float, width: Float): Float {
		val editing = draft
		return if (editing != null) drawEditor(draw, x, top, width, editing) else drawList(draw, x, top, width)
	}

	override fun afterFrame() {
		pending?.invoke()
		pending = null
		if (deleteArmed != null && System.currentTimeMillis() - deleteArmedAt > DELETE_CONFIRM_MILLIS) deleteArmed = null
	}

	private fun drawList(draw: ImDrawList, x: Float, top: Float, width: Float): Float {
		var y = top
		val pad = dp(16f)

		// The window opens whether or not the module is on, which is the point
		// of having it here; but a list that does nothing should say so.
		val off = !KeybindManager.module.enabled
		val headHeight = dp(if (off) 70f else 50f)
		card(draw, x, y, width, headHeight)
		label(draw, "Bindings", x + pad, y + dp(12f), MUTED_TEXT, dp(9f))
		val count = KeybindManager.bindings.size
		label(draw, if (count == 1) "1 binding" else "$count bindings", x + pad, y + dp(26f), TEXT, dp(12f))
		val (_, create) = buttonLeftOf(draw, "##kb_new", "New binding", x + width - pad, y + dp(15f), dp(20f))
		if (create) pending = { openEditor(null) }
		if (off) {
			label(draw, "Keybinds Manager is off in Settings, so none of these fire.", x + pad, y + dp(48f), DELETE_TEXT, dp(9f))
			val (_, enable) = buttonLeftOf(draw, "##kb_enable", "Turn on", x + width - pad, y + dp(44f), dp(18f))
			if (enable) pending = { KeybindManager.module.enabled = true }
		}
		y += headHeight + dp(CARD_GAP)

		if (KeybindManager.bindings.isEmpty()) {
			val emptyHeight = dp(46f)
			card(draw, x, y, width, emptyHeight)
			centered(draw, "No bindings yet", x, y, width, emptyHeight, MUTED_TEXT, dp(11f))
			return y + emptyHeight - top
		}

		val groups = KeybindManager.bindings.groupBy { it.category }
		val order = listOf("") + KeybindManager.categories.map { it.name }.filter { it in groups }
		for (name in order) {
			val members = groups[name] ?: continue
			if (name.isNotEmpty()) {
				val category = KeybindManager.categories.firstOrNull { it.name == name }
				val rowHeight = dp(30f)
				card(draw, x, y, width, rowHeight)
				label(draw, name, x + pad, y + dp(9f), TEXT, dp(11f))
				label(draw, "${members.size}", x + pad + textWidth(name, dp(11f)) + dp(8f), y + dp(10f), MUTED_TEXT, dp(9.5f))
				val on = category?.enabled != false
				if (switch(draw, "##kb_cat_$name", x + width - pad - dp(SWITCH_WIDTH), y + dp(7f), on)) {
					pending = { KeybindManager.toggleCategory(name) }
				}
				y += rowHeight + dp(4f)
			}
			members.forEachIndexed { index, binding ->
				y += drawRow(draw, x, y, width, binding, "${name}_$index") + dp(4f)
			}
			y += dp(CARD_GAP - 4f)
		}
		return y - top
	}

	private fun drawRow(draw: ImDrawList, x: Float, y: Float, width: Float, binding: Binding, id: String): Float {
		val height = dp(48f)
		if (!inView(y, height)) return height
		val pad = dp(16f)
		card(draw, x, y, width, height)

		val buttonSize = dp(18f)
		var right = x + width - pad
		right -= buttonSize
		val armed = deleteArmed === binding
		if (iconButton(draw, "##kb_del_$id", if (armed) "?" else FontAwesomeIcons.XMARK, right, y + dp(15f), buttonSize, DELETE_TEXT)) {
			if (armed) {
				pending = { KeybindManager.remove(binding) }
				deleteArmed = null
			} else {
				deleteArmed = binding
				deleteArmedAt = System.currentTimeMillis()
			}
		}
		right -= buttonSize + dp(4f)
		if (iconButton(draw, "##kb_edit_$id", FontAwesomeIcons.EDIT, right, y + dp(15f), buttonSize)) {
			pending = { openEditor(binding) }
		}
		right -= dp(SWITCH_WIDTH) + dp(10f)
		if (switch(draw, "##kb_on_$id", right, y + dp(16f), binding.enabled)) pending = { KeybindManager.toggle(binding) }

		// The keys, as badges, then the line they send.
		var cx = x + pad
		val keyText = KeybindManager.describe(binding.keys)
		cx += badge(draw, keyText, cx, y + dp(9f), lit = binding.enabled) + dp(8f)
		val commandSpace = right - dp(10f) - cx
		val color = if (binding.enabled) TEXT else MUTED_TEXT
		label(draw, ellipsize(binding.command, dp(10.5f), commandSpace), cx, y + dp(10f), color, dp(10.5f))
		label(draw, ellipsize(conditions(binding), dp(9f), right - dp(10f) - x - pad), x + pad, y + dp(30f), MUTED_TEXT, dp(9f))
		return height
	}

	/** What a binding waits for, in one line: "Outside GUI · F7, M7 · Mage · P3". */
	private fun conditions(binding: Binding): String {
		val parts = mutableListOf(binding.workIn.title)
		if (binding.islands.isNotEmpty()) parts += binding.islands.joinToString(", ")
		if (binding.floors.isNotEmpty()) parts += binding.floors.joinToString(", ")
		if (binding.classes.isNotEmpty()) parts += binding.classes.joinToString(", ") { className(it) }
		if (binding.phases.isNotEmpty()) parts += binding.phases.sorted().joinToString(", ") { "P$it" }
		return parts.joinToString("  |  ")
	}

	private fun drawEditor(draw: ImDrawList, x: Float, top: Float, width: Float, binding: Binding): Float {
		var y = top
		val pad = dp(16f)
		val inner = width - pad * 2f
		val fieldHeight = dp(22f)

		// What it does: the line and the keys.
		y += measuredCard(draw, x, y, width) {
			var cy = y + dp(12f)
			label(draw, if (original == null) "New binding" else "Edit binding", x + pad, cy, MUTED_TEXT, dp(9f))
			cy += dp(18f)

			label(draw, "Command", x + pad, cy, TEXT, dp(10f))
			cy += dp(15f)
			textField(draw, "##kb_command", "/warp dungeon_hub, or a chat message", commandBuffer, x + pad, cy, inner, fieldHeight)
			cy += fieldHeight + dp(4f)
			label(draw, "Starts with / for a command, anything else is said in chat.", x + pad, cy, MUTED_TEXT, dp(8.5f))
			cy += dp(18f)

			label(draw, "Keys", x + pad, cy, TEXT, dp(10f))
			cy += dp(15f)
			val keyText = when {
				capturing && captured.isEmpty() -> "Press keys... (Esc cancels)"
				capturing -> KeybindManager.describe(captured) + " ..."
				binding.keys.isEmpty() -> "Click to bind"
				else -> KeybindManager.describe(binding.keys)
			}
			val over = hovered(x + pad, cy, inner, fieldHeight)
			draw.addRectFilled(x + pad, cy, x + pad + inner, cy + fieldHeight, if (capturing) ACCENT_DARK else if (over) SURFACE_ACTIVE else RENAME_FIELD, fieldHeight / 2f)
			draw.addRect(x + pad, cy, x + pad + inner, cy + fieldHeight, if (capturing) ACCENT else DISABLED_BORDER, fieldHeight / 2f, 0, dp(1f))
			centered(draw, keyText, x + pad, cy, inner, fieldHeight, TEXT, dp(10f))
			if (hit("##kb_keys", x + pad, cy, inner, fieldHeight) && !capturing) {
				captured.clear()
				capturing = true
			}
			cy += fieldHeight + dp(4f)
			label(draw, "Hold several together for a combination. Mouse side buttons work too.", x + pad, cy, MUTED_TEXT, dp(8.5f))
			cy += dp(20f)
			cy - y
		} + dp(CARD_GAP)

		// Where it goes, and when it may fire.
		y += measuredCard(draw, x, y, width) {
			var cy = y + dp(12f)
			label(draw, "Category", x + pad, cy, TEXT, dp(10f))
			cy += dp(15f)
			textField(draw, "##kb_category", "None", categoryBuffer, x + pad, cy, inner, fieldHeight)
			cy += fieldHeight + dp(6f)
			val existing = KeybindManager.categories.map { it.name }
			if (existing.isNotEmpty()) {
				cy += chips(draw, "kb_catpick", existing, { it }, { it == categoryBuffer.get().trim() }, x + pad, cy, inner) {
					categoryBuffer.set(if (categoryBuffer.get().trim() == it) "" else it)
				} + dp(8f)
			}

			label(draw, "Works", x + pad, cy, TEXT, dp(10f))
			cy += dp(15f)
			cy += chips(draw, "kb_workin", WorkIn.entries, { it.title }, { it == binding.workIn }, x + pad, cy, inner) {
				binding.workIn = it
			} + dp(10f)

			label(draw, "Islands", x + pad, cy, TEXT, dp(10f))
			label(draw, "none picked means anywhere", x + pad + textWidth("Islands", dp(10f)) + dp(8f), cy + dp(1f), MUTED_TEXT, dp(8.5f))
			cy += dp(15f)
			cy += chips(draw, "kb_island", KeybindManager.ISLANDS, { it }, { it in binding.islands }, x + pad, cy, inner) {
				if (!binding.islands.remove(it)) binding.islands += it
			} + dp(10f)

			label(draw, "Dungeon floors", x + pad, cy, TEXT, dp(10f))
			cy += dp(15f)
			cy += chips(draw, "kb_floor", KeybindManager.FLOORS, { it }, { it in binding.floors }, x + pad, cy, inner) {
				if (!binding.floors.remove(it)) binding.floors += it
			} + dp(10f)

			label(draw, "Dungeon classes", x + pad, cy, TEXT, dp(10f))
			cy += dp(15f)
			cy += chips(draw, "kb_class", KeybindManager.CLASSES, { className(it) }, { it in binding.classes }, x + pad, cy, inner) {
				if (!binding.classes.remove(it)) binding.classes += it
			} + dp(10f)

			label(draw, "Floor 7 phases", x + pad, cy, TEXT, dp(10f))
			cy += dp(15f)
			cy += chips(draw, "kb_phase", (1..5).toList(), { "P$it" }, { it in binding.phases }, x + pad, cy, inner) {
				if (!binding.phases.remove(it)) binding.phases += it
			} + dp(14f)
			cy - y
		} + dp(CARD_GAP)

		// Cancel and Save.
		val rowHeight = dp(40f)
		card(draw, x, y, width, rowHeight)
		val problem = when {
			commandBuffer.get().isBlank() -> "Give it a command or a message."
			binding.keys.isEmpty() -> "Give it a key."
			else -> null
		}
		problem?.let { label(draw, it, x + pad, y + dp(14f), MUTED_TEXT, dp(9f)) }
		val (saveX, save) = buttonLeftOf(draw, "##kb_save", "Save", x + width - pad, y + dp(10f), dp(20f))
		val (_, cancel) = buttonLeftOf(draw, "##kb_cancel", "Cancel", saveX - dp(6f), y + dp(10f), dp(20f))
		if (cancel) pending = { closeEditor() }
		if (save && problem == null) pending = { saveDraft() }
		y += rowHeight + dp(CARD_GAP)

		return y - top
	}

	// ---- Editing ------------------------------------------------------------

	private fun openEditor(binding: Binding?) {
		original = binding
		val copy = binding?.copy() ?: Binding()
		draft = copy
		commandBuffer.set(copy.command)
		categoryBuffer.set(copy.category)
		capturing = false
		captured.clear()
		scrollToTop()
	}

	private fun closeEditor() {
		draft = null
		original = null
		capturing = false
		captured.clear()
		scrollToTop()
	}

	private fun saveDraft() {
		val binding = draft ?: return
		binding.command = commandBuffer.get().trim()
		binding.category = categoryBuffer.get().trim()
		KeybindManager.put(binding, original)
		closeEditor()
	}

	private fun className(value: imicro.cryptic.dungeon.DungeonTeam.DungeonClass): String =
		value.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

	companion object {
		private const val MAX_KEYS = 4
		private const val DELETE_CONFIRM_MILLIS = 3000L

		/** Set when the window should open; picked up by the client tick, as the Carry Manager's is. */
		private var requested = false

		fun request() {
			requested = true
		}

		fun openIfRequested(client: Minecraft) {
			if (!requested) return
			requested = false
			ImGuiRuntime.open(client) { KeybindsScreen() }
		}
	}
}
