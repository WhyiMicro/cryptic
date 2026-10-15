package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
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
		visibleIf = { !advanced.value },
	)

	@JvmField
	val hideWithEtherwarp = ToggleModuleSetting(
		id = "hide_with_etherwarp",
		label = "Hide with Etherwarp",
		defaultValue = true,
		description = "Steps aside while you aim an etherwarp.",
	)

	// ---- Advanced: Custom Block Highlight --------------------------------
	//
	// Ported from Custom Block Highlight (GPL-3.0, Tektonikal). Drawn by
	// [BlockHighlight] rather than the plain box above, which is what the
	// Advanced switch chooses between.

	@JvmField
	val advanced = ToggleModuleSetting(
		id = "advanced",
		label = "Advanced",
		defaultValue = false,
		description = "Custom Block Highlight's shapes, gradients and animations.",
	)

	private fun shown() = advanced.value
	private fun outlined() = advanced.value && mode.selectedIndex != MODE_FILL
	private fun filled() = advanced.value && mode.selectedIndex != MODE_OUTLINE

	private val shapeSection = SectionModuleSetting("advanced_shape_section", "Shape", visibleIf = ::shown)

	/** Indices into [shapeStyle]. */
	const val SHAPE_BOUNDS = 0
	const val SHAPE_COLLISION = 1

	@JvmField
	val shapeStyle = DropdownModuleSetting(
		id = "shape_style",
		label = "Shape",
		options = listOf("Bounds", "Collision shape"),
		defaultIndex = SHAPE_BOUNDS,
		description = "One box round the block, or every edge of its real shape.",
		visibleIf = ::shown,
	)

	/** Indices into [faces]. */
	const val FACES_ALL = 0
	const val FACES_EXPOSED = 1
	const val FACES_LOOKED_AT = 2
	const val FACES_CONCEALED = 3

	@JvmField
	val faces = DropdownModuleSetting(
		id = "faces",
		label = "Faces",
		options = listOf("All", "Air-exposed", "Looked-at face", "Concealed"),
		defaultIndex = FACES_ALL,
		description = "Which sides are drawn. Bounds only.",
		visibleIf = { shown() && shapeStyle.selectedIndex == SHAPE_BOUNDS },
	)

	@JvmField
	val expandBlocks = SliderModuleSetting(
		id = "expand_blocks",
		label = "Grow by",
		defaultValue = 0.0,
		min = -0.25,
		max = 0.25,
		step = 0.005,
		unit = " blocks",
		visibleIf = ::shown,
	)

	@JvmField
	val scalePercent = SliderModuleSetting(
		id = "scale_percent",
		label = "Scale",
		defaultValue = 100.0,
		min = 50.0,
		max = 150.0,
		step = 1.0,
		unit = "%",
		visibleIf = ::shown,
	)

	@JvmField
	val connectedChests = ToggleModuleSetting(
		id = "connected_chests",
		label = "Join double chests",
		defaultValue = true,
		description = "Boxes both halves of a double chest as one.",
		visibleIf = ::shown,
	)

	private val linesSection = SectionModuleSetting("advanced_lines_section", "Lines", visibleIf = ::outlined)

	@JvmField
	val cutFromCorner = SliderModuleSetting(
		id = "cut_from_corner",
		label = "Cut from corner",
		defaultValue = 0.0,
		min = 0.0,
		max = 99.0,
		step = 1.0,
		unit = "%",
		description = "Shortens each edge from both ends.",
		visibleIf = ::outlined,
	)

	@JvmField
	val cutFromCenter = SliderModuleSetting(
		id = "cut_from_center",
		label = "Cut from centre",
		defaultValue = 0.0,
		min = 0.0,
		max = 99.0,
		step = 1.0,
		unit = "%",
		description = "Opens a gap in the middle of each edge.",
		visibleIf = ::outlined,
	)

	@JvmField
	val outerThickness = SliderModuleSetting(
		id = "outer_thickness",
		label = "Outer thickness",
		defaultValue = 100.0,
		min = 0.0,
		max = 200.0,
		step = 5.0,
		unit = "%",
		description = "The width at the corners.",
		visibleIf = { outlined() && (cutFromCenter.value > 0 || cutFromCorner.value > 0) },
	)

	@JvmField
	val innerThickness = SliderModuleSetting(
		id = "inner_thickness",
		label = "Inner thickness",
		defaultValue = 100.0,
		min = 0.0,
		max = 200.0,
		step = 5.0,
		unit = "%",
		description = "The width towards the middle of each edge.",
		visibleIf = { outlined() && (cutFromCenter.value > 0 || cutFromCorner.value > 0) },
	)

	/** Indices into [depth]. */
	const val DEPTH_NORMAL = 0
	const val DEPTH_THROUGH = 1
	const val DEPTH_BEHIND = 2

	@JvmField
	val depth = DropdownModuleSetting(
		id = "depth",
		label = "Depth",
		options = listOf("Normal", "Through walls", "Only behind blocks"),
		defaultIndex = DEPTH_NORMAL,
		description = "How the highlight draws against whatever is in front of it.",
		visibleIf = ::shown,
	)

	private val colourSection = SectionModuleSetting("advanced_colour_section", "Colour", visibleIf = ::shown)

	@JvmField
	val gradient = ToggleModuleSetting(
		id = "gradient",
		label = "Gradient",
		defaultValue = false,
		description = "Runs each colour into a second one across the block.",
		visibleIf = { shown() && !rainbow.value },
	)

	@JvmField
	val outlineColor2 = ColorModuleSetting(
		id = "outline_color_2",
		label = "Outline end",
		defaultRgb = 0xFF55FF,
		visibleIf = { outlined() && gradient.value && !rainbow.value },
	)

	@JvmField
	val fillColor2 = ColorModuleSetting(
		id = "fill_color_2",
		label = "Fill end",
		defaultRgb = 0xFF55FF,
		visibleIf = { filled() && gradient.value && !rainbow.value },
	)

	@JvmField
	val rainbow = ToggleModuleSetting(
		id = "rainbow",
		label = "Rainbow",
		defaultValue = false,
		description = "Cycles through every colour. The colours above set the opacity.",
		visibleIf = ::shown,
	)

	@JvmField
	val rainbowSpeed = SliderModuleSetting(
		id = "rainbow_speed",
		label = "Speed",
		defaultValue = 5.0,
		min = 1.0,
		max = 10.0,
		step = 0.1,
		unit = "x",
		visibleIf = { shown() && rainbow.value },
	)

	@JvmField
	val rainbowDelay = SliderModuleSetting(
		id = "rainbow_delay",
		label = "Delay",
		defaultValue = 250.0,
		min = -1000.0,
		max = 1000.0,
		step = 10.0,
		unit = "ms",
		description = "How far behind the far corner runs, which is what makes the gradient.",
		visibleIf = { shown() && rainbow.value },
	)

	@JvmField
	val rainbowSaturation = SliderModuleSetting(
		id = "rainbow_saturation",
		label = "Saturation",
		defaultValue = 100.0,
		min = 0.0,
		max = 100.0,
		step = 1.0,
		unit = "%",
		visibleIf = { shown() && rainbow.value },
	)

	@JvmField
	val rainbowBrightness = SliderModuleSetting(
		id = "rainbow_brightness",
		label = "Brightness",
		defaultValue = 100.0,
		min = 0.0,
		max = 100.0,
		step = 1.0,
		unit = "%",
		visibleIf = { shown() && rainbow.value },
	)

	private val animationSection = SectionModuleSetting("advanced_animation_section", "Animation", visibleIf = ::shown)

	@JvmField
	val easing = ToggleModuleSetting(
		id = "easing",
		label = "Glide between blocks",
		defaultValue = true,
		description = "Slides the highlight from one block to the next.",
		visibleIf = ::shown,
	)

	@JvmField
	val easeSpeed = SliderModuleSetting(
		id = "ease_speed",
		label = "Glide speed",
		defaultValue = 35.0,
		min = 5.0,
		max = 100.0,
		step = 1.0,
		visibleIf = { shown() && easing.value },
	)

	@JvmField
	val fade = ToggleModuleSetting(
		id = "fade",
		label = "Fade in and out",
		defaultValue = true,
		visibleIf = ::shown,
	)

	@JvmField
	val fadeSpeed = SliderModuleSetting(
		id = "fade_speed",
		label = "Fade speed",
		defaultValue = 15.0,
		min = 2.0,
		max = 50.0,
		step = 1.0,
		visibleIf = { shown() && fade.value },
	)

	@JvmField
	val popIn = ToggleModuleSetting(
		id = "pop_in",
		label = "Pop in",
		defaultValue = false,
		description = "Grows from nothing when you start looking at a block.",
		visibleIf = ::shown,
	)

	@JvmField
	val popSpeed = SliderModuleSetting(
		id = "pop_speed",
		label = "Pop speed",
		defaultValue = 15.0,
		min = 2.0,
		max = 50.0,
		step = 1.0,
		visibleIf = { shown() && popIn.value },
	)

	private val advancedSettings = listOf(
		advanced,
		shapeSection, shapeStyle, faces, expandBlocks, scalePercent, connectedChests,
		linesSection, cutFromCorner, cutFromCenter, outerThickness, innerThickness, depth,
		colourSection, gradient, outlineColor2, fillColor2, rainbow, rainbowSpeed, rainbowDelay, rainbowSaturation, rainbowBrightness,
		animationSection, easing, easeSpeed, fade, fadeSpeed, popIn, popSpeed,
	)

	private val configurable = listOf(mode, fillColor, outlineColor, lineWidth, fullBlock, phase, hideWithEtherwarp) + advancedSettings

	@JvmField
	val module = Module(
		id = "block_overlay",
		name = "Block Overlay",
		description = "Recolours the block outline",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = configurable,
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(BlockHighlight::render)
		// Returning false from this event is what cancels vanilla's own outline,
		// which is the only way to replace it rather than draw over it.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { context, outline ->
			val client = Minecraft.getInstance()
			if (!module.enabled || client.gui.hud.isHidden) return@register true
			if (hideWithEtherwarp.value && Etherwarp.isAiming()) return@register false
			// The Gyro Helper draws its own box on the same block, and owns the
			// switch that decides whether one outline or two is wanted.
			if (GyroHelper.hidesBlockOutline()) return@register false
			// Advanced draws its own, every frame, so it can fade out after the block is gone.
			if (advanced.value) return@register false

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
