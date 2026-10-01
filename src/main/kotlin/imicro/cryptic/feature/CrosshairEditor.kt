package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.crosshair.CrosshairGrid
import imicro.cryptic.crosshair.CrosshairPreset
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.CrosshairScreen
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
 * A crosshair you draw yourself, pixel by pixel.
 *
 * The drawing lives in the profile like every other preference, as one digit
 * per cell; the window that edits it is [CrosshairScreen]. What is drawn in
 * game replaces only vanilla's crosshair sprite — the attack indicator under it
 * is still vanilla's, because it is information rather than decoration and a
 * custom crosshair is no reason to lose it.
 *
 * Two ways to draw it. **Vanilla blending** inverts whatever is behind each
 * painted cell, which is how the default crosshair stays visible on both sky
 * and stone; it ignores the palette, because an inverted pixel has no colour of
 * its own. **Custom colours** draws the palette as painted, with an optional
 * outline to stand in for what inverting did.
 */
object CrosshairEditor {
	/** Indices into [blendMode]. */
	private const val BLEND_VANILLA = 0
	private const val BLEND_CUSTOM = 1

	/** How many colours a crosshair can use. Index zero is always "empty". */
	const val PALETTE_SIZE = 6

	/**
	 * Vanilla's crosshair is 15 wide with its centre at 7, drawn from
	 * `(guiWidth - 15) / 2`. Anchoring the centre cell to the same spot is what
	 * makes a copy of it sit on exactly the same pixel.
	 */
	private const val VANILLA_WIDTH = 15
	private const val VANILLA_CENTRE = 7

	private val editor = ButtonModuleSetting("editor", "Open editor", action = { CrosshairScreen.request() })

	@JvmField
	val gridSize = DropdownModuleSetting(
		id = "grid_size",
		label = "Canvas",
		options = CrosshairGrid.SIZES.map { "${it}x$it" },
		defaultIndex = 0,
		description = "How many cells the crosshair is drawn on. Changing it keeps the drawing centred.",
	)

	/**
	 * The drawing itself, which has no row on the card — a grid of digits is
	 * neither readable nor editable in a text box. It is a setting so that it
	 * travels with the profile, exports with it, and resets with it.
	 */
	@JvmField
	val pixels = TextModuleSetting(
		id = "pixels",
		label = "Pixels",
		defaultValue = defaultDrawing(),
		maxLength = 64 * 64,
		visibleIf = { false },
	)

	/** The colours, edited in the window rather than on the card. */
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

	private val configurable = listOf(
		gridSize, blendMode, scale, opacity, outline, outlineColor, targetTint, targetColor, thirdPerson,
	)

	/** The module card's bind button, pointed at the real key mapping. */
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
		category = ModuleCategory.MISC,
		hasDemoSettings = false,
		supportsKeybind = true,
		keybind = openKeybind,
		settings = listOf(editor, gridSize, pixels) + palette +
			listOf(lookSection, blendMode, scale, opacity, outline, outlineColor, targetTint, targetColor) +
			listOf(whenSection, thirdPerson),
	)

	val usesVanillaBlending: Boolean get() = blendMode.selectedIndex == BLEND_VANILLA

	val selectedSize: Int get() = CrosshairGrid.SIZES[gridSize.selectedIndex.coerceIn(CrosshairGrid.SIZES.indices)]

	/**
	 * The grid the setting describes, decoded once and then kept.
	 *
	 * Rebuilt only when the text or the chosen size has moved on from what it
	 * was decoded from: a profile being switched or imported changes the text
	 * underneath, and a size picked on the card changes the size, and either has
	 * to show up without the editor having been opened.
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
		// The stored text is brought to the new size too, so what is saved is
		// what is drawn.
		val encoded = grid.encode()
		if (encoded != pixels.value) pixels.value = encoded
		decodedFrom = pixels.value
		return grid
	}

	/** Writes the grid back into the profile, after the editor has changed it. */
	fun commit(grid: CrosshairGrid) {
		decoded = grid
		decodedSize = grid.size
		gridSize.selectedIndex = CrosshairGrid.SIZES.indexOf(grid.size).coerceAtLeast(0)
		pixels.value = grid.encode()
		decodedFrom = pixels.value
	}

	/** Colour index [index] as ARGB, index one being the first palette entry. */
	fun colorOf(index: Int): Int = palette.getOrNull(index - 1)?.argb ?: 0

	// ---- drawing ----------------------------------------------------------

	/** Horizontal runs of one colour: row, first cell, last cell, colour. */
	private var runs: IntArray = IntArray(0)
	private var outlineRuns: IntArray = IntArray(0)
	private var runsRevision = -1
	private var runsGrid: CrosshairGrid? = null

	/**
	 * Merges each row into runs of one colour, and works out the outline.
	 *
	 * A 64 grid filled solid would otherwise be four thousand separate fills
	 * every frame. Runs make a typical crosshair a dozen, and they are only
	 * rebuilt when the drawing actually changes.
	 */
	private fun rebuildRuns(grid: CrosshairGrid) {
		if (grid === runsGrid && grid.revision == runsRevision) return
		runsGrid = grid
		runsRevision = grid.revision

		runs = collectRuns(grid.size) { x, y -> grid[x, y] }
		outlineRuns = collectRuns(grid.size + 2) { x, y ->
			// An outline cell is an empty one touching a painted one, sideways
			// or corner to corner. The border is one cell wider than the grid so
			// a drawing that reaches the edge is outlined on that side too.
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

	/**
	 * Vanilla's crosshair sprite — or a resource pack's, since a pack replaces
	 * the texture behind this name rather than the name itself.
	 */
	private val VANILLA_CROSSHAIR: Identifier = Identifier.withDefaultNamespace("hud/crosshair")

	/**
	 * Whether a sprite about to be drawn is the crosshair this replaces.
	 *
	 * Asked from inside `blitSprite` itself rather than from the call in the HUD
	 * that draws the crosshair, and that is the whole of the fix for a pack's
	 * crosshair showing through. The first version shrank that call's width to
	 * zero, which works right up until another mod wraps the call — Devonian
	 * does, to draw its own crosshair, and calls the original with arguments of
	 * its own, so the zero never arrived. Every wrapper still has to reach the
	 * real method in the end, so the answer given here holds whatever else is
	 * installed.
	 *
	 * Checked on every sprite the GUI draws, so the cheap half comes first and
	 * the name is only compared while the module is on.
	 */
	@JvmStatic
	fun hidesSprite(location: Identifier): Boolean = module.enabled && location == VANILLA_CROSSHAIR

	/**
	 * Draws the crosshair, from the point vanilla would have drawn its own.
	 *
	 * Filled cell by cell in the GUI's own coordinates, scaled about the centre
	 * cell — so at scale 1 every cell is exactly one pixel of vanilla's sprite,
	 * and the result is as sharp as vanilla is.
	 */
	@JvmStatic
	fun draw(graphics: GuiGraphicsExtractor) {
		if (!module.enabled) return
		val grid = grid()
		if (grid.isEmpty()) return
		rebuildRuns(grid)

		val client = Minecraft.getInstance()
		val anchorX = (graphics.guiWidth() - VANILLA_WIDTH) / 2 + VANILLA_CENTRE
		val anchorY = (graphics.guiHeight() - VANILLA_WIDTH) / 2 + VANILLA_CENTRE
		val c = grid.centre

		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(anchorX.toFloat(), anchorY.toFloat())
		val cell = scale.value.toFloat()
		pose.scale(cell, cell)

		if (usesVanillaBlending) {
			// White through the inverting pipeline is exactly vanilla's own
			// crosshair: every painted cell becomes the negative of what is
			// behind it.
			forEachRun(runs) { y, x0, x1, _ ->
				graphics.fill(RenderPipelines.GUI_INVERT, x0 - c, y - c, x1 - c + 1, y - c + 1, WHITE)
			}
		} else {
			if (outline.value) {
				val outlineArgb = outlineColor.argb
				// The outline grid is offset by one to have room for a border.
				forEachRun(outlineRuns) { y, x0, x1, _ ->
					graphics.fill(x0 - c - 1, y - c - 1, x1 - c, y - c, outlineArgb)
				}
			}

			val targeting = targetTint.value && client.crosshairPickEntity is LivingEntity
			val fade = opacity.value.toFloat()
			forEachRun(runs) { y, x0, x1, index ->
				val argb = if (targeting) targetColor.argb else colorOf(index)
				graphics.fill(x0 - c, y - c, x1 - c + 1, y - c + 1, withOpacity(argb, fade))
			}
		}

		pose.popMatrix()
	}

	/**
	 * The third-person half, which vanilla never reaches.
	 *
	 * Vanilla returns before drawing anything when the camera is not in first
	 * person, so there is no sprite to replace — this draws the crosshair on its
	 * own, keeping vanilla's other rule that a spectator only gets one over
	 * something they can open.
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

	/** Vanilla's crosshair on the smallest canvas, which is where everybody starts. */
	private fun defaultDrawing(): String {
		val grid = CrosshairGrid(CrosshairGrid.SIZES.first())
		CrosshairPreset.VANILLA.paint(grid, 1)
		return grid.encode()
	}
}
