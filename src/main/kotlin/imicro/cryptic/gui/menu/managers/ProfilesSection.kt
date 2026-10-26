package imicro.cryptic.gui.menu.managers

import imicro.cryptic.config.ConfigManager
import imicro.cryptic.config.ProfileEntry
import imicro.cryptic.feature.Toasts
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.TextField
import imicro.cryptic.gui.menu.UiText
import net.minecraft.client.Minecraft

/**
 * Profiles: whole setups saved by name, switched between, and shared as a line of text. Changes
 * save to the active profile as they're made; a new profile copies the current settings. Copy puts
 * a profile on the clipboard, Import adds one, and Import here replaces the active profile's
 * settings.
 */
class ProfilesSection : ManagerSection("Profiles") {
	private val deletes = DeleteConfirm()

	private var renaming: ProfileEntry? = null
	private val renameField = TextField(32)

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		val headH = 60f
		ui.card(Box(x, cy, width, headH))
		ui.text("Your setups", x + 18f, cy + 14f, 15f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		ui.text("Changes save to the active profile as you make them.", x + 18f, cy + 36f, 12f, MenuStyle.MUTED)
		var right = x + width - 18f
		val buttonY = cy + (headH - 34f) / 2f
		for ((label, primary, action) in listOf<Triple<String, Boolean, () -> Unit>>(
			Triple("New profile", true) { report(ConfigManager.createProfile().map { "Created §f$it" }) },
			Triple("Import", false) { report(ConfigManager.importProfile(clipboard()).map { "Imported §f$it" }) },
			Triple("Folder", false) { ConfigManager.openProfileDirectory() },
		)) {
			val w = ui.buttonWidth(label)
			ui.button(Box(right - w, buttonY, w, 34f), label, primary, action)
			right -= w + 8f
		}
		cy += headH + 14f

		Sections.heading(ui, "Profiles", x, cy)
		cy += Sections.HEADING
		for (entry in ConfigManager.profiles()) cy += drawRow(entry, x, cy, width) + 2f
		return cy - y
	}

	private fun drawRow(entry: ProfileEntry, x: Float, y: Float, width: Float): Float {
		val h = 54f
		ui.card(Box(x, y, width, h), if (entry.active) MenuStyle.scale(MenuStyle.accent(), 0.28f) or 0xFF000000.toInt() else MenuStyle.ROW)
		val pad = 18f
		val cy = y + h / 2f

		var right = x + width - pad
		if (!entry.builtIn) {
			right -= Sections.deleteButton(ui, deletes, entry.path, right, cy) { report(ConfigManager.deleteProfile(entry).map { "Deleted §f${entry.name}" }) } + 4f
		}
		ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), COPY) {
			ConfigManager.exportProfile(entry).onSuccess {
				Minecraft.getInstance().keyboardHandler.clipboard = it
				Toasts.show(SOURCE, "Copied §f${entry.name} §7to the clipboard", color = Toasts.Colors.CRYPTIC)
			}.onFailure { Toasts.show(SOURCE, it.message ?: "Could not copy it", error = true) }
		}
		right -= 28f + 4f
		if (!entry.builtIn) {
			ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), Sections.EDIT) {
				renaming = entry
				renameField.set(entry.name, selectAll = true)
				ui.focus(renameField)
			}
			right -= 28f + 8f
		}
		if (entry.active && !entry.builtIn) {
			val w = ui.buttonWidth("Import here")
			ui.button(Box(right - w, cy - 15f, w, 30f), "Import here") {
				report(ConfigManager.importIntoProfile(entry, clipboard()).map { "Imported into §f$it" })
			}
			right -= w + 8f
		}
		if (!entry.active) {
			val w = ui.buttonWidth("Load")
			ui.button(Box(right - w, cy - 15f, w, 30f), "Load", primary = true) {
				report(ConfigManager.loadProfile(entry).map { "Loaded §f${entry.name}" })
			}
			right -= w + 8f
		}

		if (renaming?.path == entry.path) {
			ui.field(Box(x + pad - 6f, cy - 16f, 260f, 32f), renameField, entry.name)
			val saveW = ui.buttonWidth("Save")
			ui.button(Box(x + pad + 262f, cy - 16f, saveW, 32f), "Save", primary = true) { finishRename(entry) }
			return h
		}
		ui.text(entry.name, x + pad, y + 10f, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		var tx = x + pad + ui.width(entry.name, 14f, UiText.Face.MEDIUM) + 10f
		if (entry.active) tx += ui.badge("Active", tx, y + 8f, lit = true) + 6f
		if (entry.builtIn) ui.badge("Built in", tx, y + 8f)
		ui.text(edited(entry.modifiedAt), x + pad, y + 32f, 12f, MenuStyle.MUTED)
		return h
	}

	override fun keyPressed(event: net.minecraft.client.input.KeyEvent): Boolean {
		val entry = renaming
		if (entry != null && ui.typing) {
			when (event.key()) {
				org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
					finishRename(entry)
					return true
				}
				org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> {
					renaming = null
					ui.blur()
					return true
				}
			}
		}
		return super.keyPressed(event)
	}

	private fun finishRename(entry: ProfileEntry) {
		val name = renameField.text.trim()
		renaming = null
		ui.blur()
		if (name.isNotEmpty() && name != entry.name) report(ConfigManager.renameProfile(entry, name).map { "Renamed to §f$it" })
	}

	private fun clipboard(): String = Minecraft.getInstance().keyboardHandler.clipboard

	private fun report(result: Result<String>) {
		result.onSuccess { Toasts.show(SOURCE, it, color = Toasts.Colors.CRYPTIC) }
			.onFailure { Toasts.show(SOURCE, it.message ?: "That did not work", error = true) }
	}

	private fun edited(at: Long): String {
		val seconds = (System.currentTimeMillis() - at) / 1000
		return when {
			seconds < 60 -> "Edited just now"
			seconds < 3600 -> "Edited ${seconds / 60}m ago"
			seconds < 86_400 -> "Edited ${seconds / 3600}h ago"
			else -> "Edited ${seconds / 86_400}d ago"
		}
	}

	companion object {
		private const val SOURCE = "Profiles"
		private const val COPY = ""

		/** The card in Settings the Profiles page hangs from. */
		val module = Module(
			id = "profiles",
			name = "Profiles",
			description = "Save and share setups",
			category = ModuleCategory.MISC,
			supportsToggle = false,
		)
	}
}
