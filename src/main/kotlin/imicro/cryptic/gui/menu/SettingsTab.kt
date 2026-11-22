package imicro.cryptic.gui.menu

import imicro.cryptic.feature.ClickGui
import imicro.cryptic.feature.ClientRestrictions
import imicro.cryptic.feature.Toasts
import imicro.cryptic.feature.UpdateChecker
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.menu.managers.ChangelogSection
import imicro.cryptic.gui.menu.managers.CreditsSection
import imicro.cryptic.hud.HudSettings

/**
 * The menu's Settings tab: the client's own modules on one page, a heading each. The settings stay
 * their modules' own, so profiles save them where they always were; this module is never registered.
 */
object SettingsTab {
	private val clickGui = SectionModuleSetting("click_gui", "Click GUI")

	private val checkForUpdates = ToggleModuleSetting(
		id = "check_for_updates",
		label = "Check for updates",
		defaultValue = true,
		description = "Looks for a newer Cryptic the first time you join a server each session.",
		backing = UpdateChecker.module,
	)

	private val whatsNew = ToggleModuleSetting(
		id = "whats_new",
		label = "Offer what's new",
		defaultValue = false,
		description = "After an update, the first join offers the changelog in a notification.",
		backing = ChangelogSection.module,
	)

	private val toasts = ToggleModuleSetting(
		id = "show_toasts",
		label = "Show as toasts",
		defaultValue = true,
		description = "Cryptic's notices pop up in a corner. Off, they go to chat instead.",
		backing = Toasts.module,
	)

	private val credits = ButtonModuleSetting("credits", "Credits", action = { MenuScreen.show(CreditsSection.module) })

	val module = Module(
		id = "settings",
		name = "Settings",
		description = "Cryptic's own settings",
		category = ModuleCategory.MISC,
		supportsToggle = false,
		// The menu key, shown under the Click GUI heading.
		supportsKeybind = true,
		keybind = ClickGui.module.keybind,
		settings = listOf(
			SectionModuleSetting("client_restrictions", "Client Restrictions"),
			ClientRestrictions.mode,
			clickGui,
			ClickGui.accentColor,
			ClickGui.backgroundBlur,
			ClickGui.blurStrength,
			ClickGui.sorting,
			ClickGui.rightClickEdits,
			ClickGui.rememberSearch,
			ClickGui.resetButtons,
			SectionModuleSetting("update_checker", "Update Checker"),
			checkForUpdates,
			UpdateChecker.autoUpdate,
			whatsNew,
			UpdateChecker.checkNow,
			SectionModuleSetting("toasts", "Toast Notifications"),
			toasts,
			Toasts.position,
			Toasts.seconds,
			Toasts.scale,
			Toasts.test,
			SectionModuleSetting("hud_editor", "HUD Editor"),
			HudSettings.showTips,
			HudSettings.open,
			SectionModuleSetting("about", "About"),
			credits,
		),
	)

	/** Where the page shows the menu key. */
	val keybind = ModulePage.KeybindPlacement(clickGui, "Menu key", "The key that opens this menu. Escape or Backspace clears it.")
}
