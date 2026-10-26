package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/** Settings for the menu itself. The open-menu key is a real Minecraft key mapping. */
object ClickGui {
	@JvmField
	val accentColor = ColorModuleSetting(
		id = "accent_color",
		label = "Accent color",
		defaultRgb = 0x998DF4,
	)

	private val guiKeybind = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.openGuiKey.isUnbound) {
				"None"
			} else {
				CrypticClient.openGuiKey.translatedKeyMessage.string.uppercase()
			}
		},
		onKeyChanged = { key ->
			CrypticClient.openGuiKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	const val SORT_A_TO_Z = 0
	const val SORT_Z_TO_A = 1
	const val SORT_WIDTH = 2

	@JvmField
	val sorting = DropdownModuleSetting(
		id = "sorting",
		label = "Sort modules",
		options = listOf("A-Z", "Z-A", "Width"),
		defaultIndex = SORT_A_TO_Z,
		description = "The order cards are listed in. Width puts the longest names first.",
	)

	@JvmField
	val leftClickToggles = ToggleModuleSetting(
		id = "left_click_toggles",
		label = "Left-click to toggle",
		defaultValue = true,
		description = "Turns a module on or off by clicking its card, as well as its switch.",
	)

	@JvmField
	val rememberSearch = ToggleModuleSetting(
		id = "remember_search",
		label = "Remember search",
		defaultValue = false,
		description = "Keeps what you typed in the search when it is closed with Escape or the menu is shut, and puts it back the next time.",
	)

	var rememberedSearch: String = ""

	@JvmField
	val backgroundBlur = ToggleModuleSetting(
		id = "background_blur",
		label = "Background blur",
		defaultValue = true,
		description = "Blurs the game behind the menu while it is open. Off, the game shows through, only dimmed.",
	)

	@JvmField
	val blurStrength = SliderModuleSetting(
		id = "blur_strength",
		label = "Blur strength",
		defaultValue = 5.0,
		min = 1.0,
		max = 10.0,
		step = 1.0,
		description = "How strongly the game is blurred behind the menu. Cryptic's own, so Minecraft's Menu Background Blur option can stay off.",
		visibleIf = { backgroundBlur.value },
	)

	@JvmField
	val rightClickEdits = ToggleModuleSetting(
		id = "right_click_edits",
		label = "Right-click to edit module",
		defaultValue = true,
		description = "Opens a module's settings when you right-click its card, as well as from its '⋮'.",
	)

	@JvmField
	val editHud = ButtonModuleSetting(
		id = "edit_hud",
		label = "Edit HUD",
		// The menu closes and hands over to the editor, which returns to it on escape.
		action = {
			val parent = Minecraft.getInstance().gui.screen()
			Minecraft.getInstance().execute { Hud.openEditor(parent) }
		},
	)

	@JvmField
	val module = Module(
		id = "click_gui",
		name = "Click GUI",
		description = "Cryptic's own interface",
		category = ModuleCategory.MISC,
		supportsToggle = false,
		supportsKeybind = true,
		keybind = guiKeybind,
		settings = listOf(accentColor, backgroundBlur, blurStrength, sorting, leftClickToggles, rightClickEdits, rememberSearch, editHud),
	)
}
