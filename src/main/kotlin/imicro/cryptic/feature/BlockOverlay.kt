package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft

/**
 * Replaces the black wireframe around the block you are looking at.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). Vanilla's outline is a
 * thin black line, which on a dark dungeon floor is very nearly invisible —
 * and the block under your crosshair is the thing a secret, a lever and an
 * etherwarp all depend on. A coloured box of your own width says it plainly.
 *
 * It replaces rather than adds: the vanilla outline is cancelled in the same
 * breath, so there is never one drawn under the other.
 */
object BlockOverlay {
	/** Indices into [mode]. */
	private const val MODE_OUTLINE = 0
	private const val MODE_FILL = 1

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0x0086FF,
		supportsAlpha = true,
		defaultAlpha = 0x32,
		visibleIf = { mode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = 0x0086FF,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.5,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val fullBlock = ToggleModuleSetting(
		id = "full_block",
		label = "Whole cube",
		defaultValue = false,
		description = "Boxes a full cube, whatever the block's shape.",
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = false,
		description = "Draws the box through whatever is in front of it.",
	)

	@JvmField
	val hideWithEtherwarp = ToggleModuleSetting(
		id = "hide_with_etherwarp",
		label = "Hide with Etherwarp",
		defaultValue = true,
		description = "Steps aside while you aim an etherwarp.",
	)

	private val configurable = listOf(mode, fillColor, outlineColor, lineWidth, fullBlock, phase, hideWithEtherwarp)

	@JvmField
	val module = Module(
		id = "block_overlay",
		name = "Block Overlay",
		description = "Recolours the block outline",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurable,
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		// Returning false from this event is what cancels vanilla's own outline,
		// which is the only way to replace it rather than draw over it.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { context, outline ->
			val client = Minecraft.getInstance()
			if (!module.enabled || client.gui.hud.isHidden) return@register true
			if (hideWithEtherwarp.value && Etherwarp.isAiming()) return@register false
			// The Gyro Helper draws its own box on the same block, and owns the
			// switch that decides whether one outline or two is wanted.
			if (GyroHelper.hidesBlockOutline()) return@register false

			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = outline.pos(),
				outlineArgb = outlineColor.argb,
				fillArgb = fillColor.argb,
				outline = mode.selectedIndex != MODE_FILL,
				fill = mode.selectedIndex != MODE_OUTLINE,
				phase = phase.value,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = fullBlock.value,
			)

			false
		}
	}
}
