package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.crosshair.CrosshairGrid
import imicro.cryptic.crosshair.CrosshairPreset
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.GameType
import kotlin.math.roundToInt

/**
 * A crosshair you draw yourself, pixel by pixel. The drawing is stored in the profile as one digit
 * per cell; the editor is [CrosshairScreen]. Only vanilla's crosshair sprite is replaced; the
 * attack indicator stays.
 *
 * **Vanilla blending** inverts what's behind each painted cell, like the default crosshair,
 * ignoring the palette. **Custom colours** draws the palette as painted, with an optional outline.
 */
object CrosshairEditor {
	private const val BLEND_VANILLA = 0
	private const val BLEND_CUSTOM = 1

	/** Colours a crosshair can use; index zero is always empty. */
	const val PALETTE_SIZE = 6

	/**
	 * Vanilla's crosshair is 15 wide with its centre at 7, drawn from `(guiWidth - 15) / 2`.
	 * Anchoring the centre cell there makes a copy sit on the same pixel.
	 */
	private const val VANILLA_WIDTH = 15
	private const val VANILLA_CENTRE = 7

	@JvmField
	val gridSize = DropdownModuleSetting(
		id = "grid_size",
		label = "Canvas",
		options = CrosshairGrid.SIZES.map { "${it}x$it" },
		defaultIndex = 0,
		description = "How many cells the crosshair is drawn on. Changing it keeps the drawing centred.",
	)

	/** The drawing, with no row on the card; a setting so it travels, exports and resets with the profile. */
	@JvmField
	val pixels = TextModuleSetting(
		id = "pixels",
		label = "Pixels",
		defaultValue = defaultDrawing(),
		maxLength = 64 * 64,
		visibleIf = { false },
	)

	/** The colours, edited in the editor rather than on the card. */
	@JvmField
	val palette: List<ColorModuleSetting> = listOf(
		0xFFFFFF, 0xFF5555, 0x55FF55, 0x55FFFF, 0xFFFF55, 0x000000,
	).mapIndexed { index, rgb ->
		ColorModuleSetting(
			id = "colour_${index + 1}",
			label = "Colour ${index + 1}",
			defaultRgb = rgb,
			supportsAlpha = true,
			defaultAlpha = 0xFF,
			visibleIf = { false },
		)
	}

	private val lookSection = SectionModuleSetting("look_section", "Look")

	@JvmField
	val blendMode = DropdownModuleSetting(
		id = "blend_mode",
		label = "Colours",
		options = listOf("Vanilla blending", "Custom colours"),
		defaultIndex = BLEND_VANILLA,
		description = "Vanilla blending inverts what is behind it.",
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Scale",
		defaultValue = 1.0,
		min = 0.25,
		max = 4.0,
		step = 0.25,
		description = "How big one cell is, where 1 is the size of a pixel of vanilla's crosshair.",
	)

	@JvmField
	val opacity = SliderModuleSetting(
		id = "opacity",
		label = "Opacity",
		defaultValue = 1.0,
		min = 0.1,
		max = 1.0,
		step = 0.05,
		visibleIf = { blendMode.selectedIndex == BLEND_CUSTOM },
	)

	@JvmField
	val outline = ToggleModuleSetting(
		id = "outline",
		label = "Outline",
		defaultValue = false,
		description = "A border, so it shows on any background.",
		visibleIf = { blendMode.selectedIndex == BLEND_CUSTOM },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = 0x000000,
		supportsAlpha = true,
		defaultAlpha = 0xB0,
		visibleIf = { blendMode.selectedIndex == BLEND_CUSTOM && outline.value },
	)

	@JvmField
	val targetTint = ToggleModuleSetting(
		id = "target_tint",
		label = "Change colour on a target",
		defaultValue = false,
		description = "Recolours the crosshair over a mob.",
		visibleIf = { blendMode.selectedIndex == BLEND_CUSTOM },
	)

	@JvmField
	val targetColor = ColorModuleSetting(
		id = "target_color",
		label = "Target",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { blendMode.selectedIndex == BLEND_CUSTOM && targetTint.value },
	)

	private val whenSection = SectionModuleSetting("when_section", "When")

	@JvmField
	val thirdPerson = ToggleModuleSetting(
		id = "third_person",
		label = "Show in third person",
		defaultValue = false,
		description = "Vanilla hides the crosshair behind you and in front of you.",
	)

	@JvmField
	val hideDefault = ToggleModuleSetting(
		id = "hide_default",
		label = "Hide the default crosshair",
		defaultValue = true,
		description = "Hides Minecraft's crosshair, or the one your resource pack gives it, so only yours is drawn.",
	)

	private val configurable = listOf(
		gridSize, blendMode, scale, opacity, outline, outlineColor, targetTint, targetColor, thirdPerson, hideDefault,
	)

	/** The card's bind button, pointed at the real key mapping. */
	private val openKeybind = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.crosshairEditorKey.isUnbound) {
				"None"
			} else {
				CrypticClient.crosshairEditorKey.translatedKeyMessage.string.uppercase()
			}
		},
		onKeyChanged = { key ->
			CrypticClient.crosshairEditorKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "crosshair_editor",
		name = "Crosshair Editor",
		description = "Draw your own crosshair",
		category = ModuleCategory.GENERAL,
		supportsKeybind = true,
		keybind = openKeybind,
		settings = listOf(gridSize, pixels) + palette +
			listOf(lookSection, blendMode, scale, opacity, outline, outlineColor, targetTint, targetColor) +
			listOf(whenSection, hideDefault, thirdPerson),
	)

	val usesVanillaBlending: Boolean get() = blendMode.selectedIndex == BLEND_VANILLA

	val selectedSize: Int get() = CrosshairGrid.SIZES[gridSize.selectedIndex.coerceIn(CrosshairGrid.SIZES.indices)]

	/**
	 * The setting's grid, decoded once. Rebuilt when the text (profile switch or import) or the
	 * size changes, so changes show without opening the editor.
	 */
	private var decoded: CrosshairGrid? = null
	private var decodedFrom: String? = null
	private var decodedSize = 0

	fun grid(): CrosshairGrid {
		val size = selectedSize
		val cached = decoded
		if (cached != null && decodedFrom === pixels.value && decodedSize == size) return cached

		val grid = CrosshairGrid.decode(pixels.value, size)
		decoded = grid
		decodedSize = size
		// The stored text follows the new size, so what's saved is what's drawn.
		val encoded = grid.encode()
		if (encoded != pixels.value) pixels.value = encoded
		decodedFrom = pixels.value
		return grid
	}

	/** Writes the grid back into the profile after editing. */
	fun commit(grid: CrosshairGrid) {
		decoded = grid
		decodedSize = grid.size
		gridSize.selectedIndex = CrosshairGrid.SIZES.indexOf(grid.size).coerceAtLeast(0)
		pixels.value = grid.encode()
		decodedFrom = pixels.value
	}

	/** Colour [index] as ARGB; index one is the first palette entry. */
	fun colorOf(index: Int): Int = palette.getOrNull(index - 1)?.argb ?: 0

	/** Horizontal runs of one colour: row, first cell, last cell, colour. */
	private var runs: IntArray = IntArray(0)
	private var outlineRuns: IntArray = IntArray(0)
	private var runsRevision = -1
	private var runsGrid: CrosshairGrid? = null

	/**
	 * Merges rows into single-colour runs and works out the outline, so a typical crosshair is a
	 * dozen fills instead of thousands. Rebuilt only when the drawing changes.
	 */
	private fun rebuildRuns(grid: CrosshairGrid) {
		if (grid === runsGrid && grid.revision == runsRevision) return
		runsGrid = grid
		runsRevision = grid.revision

		runs = collectRuns(grid.size) { x, y -> grid[x, y] }
		outlineRuns = collectRuns(grid.size + 2) { x, y ->
			// An outline cell is an empty one touching a painted one, including diagonally. The
			// border is a cell wider than the grid, so edge drawings are outlined too.
			val gx = x - 1
			val gy = y - 1
			if (grid[gx, gy] != 0) return@collectRuns 0
			for (dy in -1..1) for (dx in -1..1) {
				if ((dx != 0 || dy != 0) && grid[gx + dx, gy + dy] != 0) return@collectRuns 1
			}
			0
		}
	}

	private inline fun collectRuns(size: Int, valueAt: (Int, Int) -> Int): IntArray {
		val out = ArrayList<Int>()
		for (y in 0 until size) {
			var x = 0
			while (x < size) {
				val value = valueAt(x, y)
				if (value == 0) {
					x++
					continue
				}
				val start = x
				while (x + 1 < size && valueAt(x + 1, y) == value) x++
				out += y
				out += start
				out += x
				out += value
				x++
			}
		}
		return out.toIntArray()
	}

	/** Vanilla's crosshair sprite, or a resource pack's, since packs replace the texture behind this name. */
	private val VANILLA_CROSSHAIR: Identifier = Identifier.withDefaultNamespace("hud/crosshair")

	/**
	 * Whether a sprite about to be drawn is the crosshair being replaced. Asked inside `blitSprite`
	 * itself, so it holds even when another mod wraps the HUD's crosshair call (Devonian does).
	 * Checked for every GUI sprite, so the cheap half comes first.
	 */
	@JvmStatic
	fun hidesSprite(location: Identifier): Boolean = module.enabled && hideDefault.value && location == VANILLA_CROSSHAIR

	/** The same, for a sprite drawn straight from the atlas, as some mods do. */
	@JvmStatic
	fun hidesSprite(sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite): Boolean =
		module.enabled && hideDefault.value && sprite.contents().name() == VANILLA_CROSSHAIR

	/** Screen pixels per cell: Scale times the GUI scale, rounded so every cell is equal. */
	fun cellPixels(guiScale: Int): Int = (guiScale * scale.value).roundToInt().coerceAtLeast(1)

	/**
	 * Draws the crosshair where vanilla would have. Drawn in real screen pixels with whole-pixel
	 * cells; scaling the GUI instead gives uneven cells that look blurred. At scale 1 a cell is one
	 * pixel of vanilla's sprite, on the same spot.
	 */
	@JvmStatic
	fun draw(graphics: GuiGraphicsExtractor) {
		if (!module.enabled) return
		val grid = grid()
		if (grid.isEmpty()) return
		rebuildRuns(grid)

		val client = Minecraft.getInstance()
		val guiScale = client.window.guiScale.coerceAtLeast(1)
		val anchorX = (graphics.guiWidth() - VANILLA_WIDTH) / 2 + VANILLA_CENTRE
		val anchorY = (graphics.guiHeight() - VANILLA_WIDTH) / 2 + VANILLA_CENTRE
		val c = grid.centre
		val cell = cellPixels(guiScale)
		// The centre cell centred on vanilla's middle pixel.
		val left = ((anchorX + 0.5f) * guiScale - cell / 2f).roundToInt() - c * cell
		val top = ((anchorY + 0.5f) * guiScale - cell / 2f).roundToInt() - c * cell

		val pose = graphics.pose()
		pose.pushMatrix()
		pose.scale(1f / guiScale, 1f / guiScale)

		if (usesVanillaBlending) {
			// White through the inverting pipeline is exactly vanilla's crosshair.
			forEachRun(runs) { y, x0, x1, _ ->
				graphics.fill(RenderPipelines.GUI_INVERT, left + x0 * cell, top + y * cell, left + (x1 + 1) * cell, top + (y + 1) * cell, WHITE)
			}
		} else {
			if (outline.value) {
				val outlineArgb = outlineColor.argb
				// The outline grid is offset by one to make room for the border.
				forEachRun(outlineRuns) { y, x0, x1, _ ->
					graphics.fill(left + (x0 - 1) * cell, top + (y - 1) * cell, left + x1 * cell, top + y * cell, outlineArgb)
				}
			}

			val targeting = targetTint.value && client.crosshairPickEntity is LivingEntity
			val fade = opacity.value.toFloat()
			forEachRun(runs) { y, x0, x1, index ->
				val argb = if (targeting) targetColor.argb else colorOf(index)
				graphics.fill(left + x0 * cell, top + y * cell, left + (x1 + 1) * cell, top + (y + 1) * cell, withOpacity(argb, fade))
			}
		}

		pose.popMatrix()
	}

	/**
	 * Third person, where vanilla draws nothing. Keeps vanilla's rule that spectators only get one
	 * over something they can open.
	 */
	@JvmStatic
	fun drawThirdPerson(graphics: GuiGraphicsExtractor) {
		if (!module.enabled || !thirdPerson.value) return
		val client = Minecraft.getInstance()
		if (client.options.cameraType.isFirstPerson) return
		if (client.gameMode?.playerMode == GameType.SPECTATOR) return

		graphics.nextStratum()
		draw(graphics)
	}

	private inline fun forEachRun(runs: IntArray, action: (Int, Int, Int, Int) -> Unit) {
		var i = 0
		while (i + 3 < runs.size) {
			action(runs[i], runs[i + 1], runs[i + 2], runs[i + 3])
			i += 4
		}
	}

	private fun withOpacity(argb: Int, opacity: Float): Int {
		if (opacity >= 0.999f) return argb
		val alpha = (((argb ushr 24) and 0xFF) * opacity).roundToInt().coerceIn(0, 255)
		return (argb and 0x00FFFFFF) or (alpha shl 24)
	}

	private const val WHITE = 0xFFFFFFFF.toInt()

	/** Vanilla's crosshair on the smallest canvas, the starting drawing. */
	private fun defaultDrawing(): String {
		val grid = CrosshairGrid(CrosshairGrid.SIZES.first())
		CrosshairPreset.VANILLA.paint(grid, 1)
		return grid.encode()
	}
}
