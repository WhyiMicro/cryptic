package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A sheet of black over the game, to darken a bright world (the brightness slider only goes up).
 * Drawn first it darkens only the world; drawn last it darkens the HUD too. Ported from
 * NoammAddons' Dark Mode.
 */
object DarkMode {
	@JvmField
	val opacity = SliderModuleSetting(
		id = "opacity",
		label = "Opacity",
		unit = "%",
		defaultValue = 25.0,
		min = 1.0,
		max = 80.0,
		step = 1.0,
		description = "How strong the dark tint is.",
	)

	@JvmField
	val tintHud = ToggleModuleSetting(
		id = "tint_hud",
		label = "Tint HUD",
		defaultValue = false,
		description = "Darkens the hotbar, chat and the rest of the HUD as well as the world.",
	)

	@JvmField
	val module = Module(
		id = "dark_mode",
		name = "Dark Mode",
		description = "Darkens the screen",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(opacity, tintHud),
	)

	@JvmStatic
	fun drawUnderHud(graphics: GuiGraphicsExtractor) {
		if (module.enabled && !tintHud.value) draw(graphics)
	}

	@JvmStatic
	fun drawOverHud(graphics: GuiGraphicsExtractor) {
		if (!module.enabled || !tintHud.value) return
		// A layer of its own, so nothing already drawn ends up above it.
		graphics.nextStratum()
		draw(graphics)
	}

	private fun draw(graphics: GuiGraphicsExtractor) {
		val alpha = (opacity.value / 100.0 * 255.0).toInt().coerceIn(0, 255)
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha shl 24)
	}
}
