package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Lays a sheet of black over the game, to take the edge off a bright world.
 *
 * Ported from NoammAddons' Dark Mode (CC0, Noamm9), with the same two settings.
 * SkyBlock is lit like a showroom — a snowy island or the inside of a quartz
 * dungeon room is a white screen at night — and the game's own brightness
 * slider only goes up. This goes down, by as much as is wanted.
 *
 * The one choice is what the sheet goes over. Drawn first, it darkens the world
 * and leaves the hotbar, the chat and everything else on the HUD at full
 * strength on top of it; drawn last, it darkens those as well.
 */
object DarkMode {
	@JvmField
	val opacity = SliderModuleSetting(
		id = "opacity",
		label = "Opacity (%)",
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
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(opacity, tintHud),
	)

	/** Before anything on the HUD is drawn, which puts the sheet under all of it. */
	@JvmStatic
	fun drawUnderHud(graphics: GuiGraphicsExtractor) {
		if (module.enabled && !tintHud.value) draw(graphics)
	}

	/** After everything on the HUD is drawn, which puts the sheet over all of it. */
	@JvmStatic
	fun drawOverHud(graphics: GuiGraphicsExtractor) {
		if (!module.enabled || !tintHud.value) return
		// A layer of its own, so nothing already drawn can end up above it.
		graphics.nextStratum()
		draw(graphics)
	}

	private fun draw(graphics: GuiGraphicsExtractor) {
		val alpha = (opacity.value / 100.0 * 255.0).toInt().coerceIn(0, 255)
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha shl 24)
	}
}
