package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.hud.Hud
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/** Settings for the menu itself. The open-menu bind is Minecraft's real key mapping. */
object ClickGui {
	@JvmField
	val accentColor = ColorModuleSetting(
		id = "accent_color",
		label = "Accent color",
		defaultRgb = 0x998DF4,
	)

	private val guiKeybind = KeybindSetting(
		profileBacked = false,
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

	@JvmField
	val editHud = ButtonModuleSetting(
		id = "edit_hud",
		label = "Edit HUD",
		// The menu is in the way of the thing being arranged, so it closes and
		// hands over to the editor rather than opening it on top of itself.
		action = { Minecraft.getInstance().execute { Hud.openEditor() } },
	)

	@JvmField
	val module = Module(
		id = "click_gui",
		name = "Click GUI",
		description = "Customize Cryptic's interface",
		category = ModuleCategory.MISC,
		hasDemoSettings = false,
		supportsToggle = false,
		supportsKeybind = true,
		keybind = guiKeybind,
		settings = listOf(accentColor, editHud),
	)

	/** Dear ImGui packs colors as ABGR. */
	fun accentAbgr(alpha: Int = 0xFF): Int = rgbToAbgr(accentColor.rgb, alpha)

	fun accentDarkAbgr(alpha: Int = 0xFF): Int {
		val rgb = accentColor.rgb
		val red = ((rgb ushr 16) and 0xFF) * 3 / 10
		val green = ((rgb ushr 8) and 0xFF) * 3 / 10
		val blue = (rgb and 0xFF) * 3 / 10
		return rgbToAbgr((red shl 16) or (green shl 8) or blue, alpha)
	}

	private fun rgbToAbgr(rgb: Int, alpha: Int): Int =
		((alpha and 0xFF) shl 24) or
			((rgb and 0xFF) shl 16) or
			(rgb and 0x00FF00) or
			((rgb ushr 16) and 0xFF)
}
