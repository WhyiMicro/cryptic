package imicro.cryptic.hud

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft

/** Settings for the HUD editor, on the Settings tab. */
object HudSettings {
	@JvmField
	val showTips = ToggleModuleSetting(
		id = "show_tips",
		label = "Show tips",
		defaultValue = true,
		description = "The line of controls along the bottom of the editor.",
	)

	private val open = ButtonModuleSetting("open", "Edit HUD", action = {
		val parent = Minecraft.getInstance().gui.screen()
		Minecraft.getInstance().execute { Hud.openEditor(parent) }
	})

	@JvmField
	val module = Module(
		id = "hud_editor",
		name = "HUD Editor",
		description = "HUD editor settings",
		category = ModuleCategory.MISC,
		supportsToggle = false,
		settings = listOf(showTips, open),
	)
}
