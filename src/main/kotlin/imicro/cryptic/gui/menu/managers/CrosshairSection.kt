package imicro.cryptic.gui.menu.managers

import imicro.cryptic.config.ConfigManager
import imicro.cryptic.crosshair.CrosshairGrid
import imicro.cryptic.crosshair.CrosshairPreset
import imicro.cryptic.feature.CrosshairEditor
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.render.RoundRect
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Crosshair Editor on its module's page: a canvas to paint the crosshair
 * on, its colours and tools beside it, and underneath a preview of it at the
 * size it will be in game, over a bright sky and over dark stone.
 *
 * Left paints, right erases, and Ctrl+Z undoes. With a mirror on, a stroke is
 * painted on the far side of the centre as well, which is how a symmetrical
 * crosshair is drawn in a quarter of the strokes.
 */
class CrosshairSection : ManagerSection("Editor") {
	private val grid: CrosshairGrid = CrosshairEditor.grid()
	private var colour = 1
	private var fillTool = false
	private var mirrorX = true
	private var mirrorY = true
	private val undo = ArrayDeque<Pair<IntArray, Int>>()

	/** The button painting a stroke, -1 between strokes, and the cell it last reached. */
	private var strokeButton = -1
	private var lastCellX = -1
	private var lastCellY = -1

	/** Where the canvas was drawn, in design pixels, and how big a cell is there. */
	private var canvasLeft = 0f
	private var canvasTop = 0f
	private var cell = 1f

	override fun onClose() {
		CrosshairEditor.commit(grid)
		ConfigManager.flush()
	}

	// ---- Drawing ---------------------------------------------------------

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		ui.text("Left paints  ·  Right erases  ·  Ctrl+Z undoes", x + 2f, cy + 2f, 12f, MenuStyle.MUTED)
		cy += 26f

		val canvasCard = 340f
		drawCanvas(x, cy, canvasCard)
		val sideX = x + canvasCard + 12f
		val sideW = width - canvasCard - 12f
		var sy = cy
		sy += drawPalette(sideX, sy, sideW) + 2f
		sy += drawTools(sideX, sy, sideW) + 2f
		sy += drawSizes(sideX, sy, sideW) + 2f
		sy += drawPresets(sideX, sy, sideW)
		cy += max(canvasCard, sy - cy) + 12f

		cy += drawPreview(x, cy, width)
		return cy - y
	}

	private fun drawCanvas(x: Float, y: Float, size: Float) {
		ui.card(Box(x, y, size, size))
		val ppu = UiText.pixelsPerUnit
		val cells = grid.size
		// Whole real pixels per cell, so every cell is the same size on screen.
		val cellPixels = floor((size - 28f) * ppu / cells).coerceAtLeast(1f)
		cell = cellPixels / ppu
		val canvas = cell * cells
		// On whole pixels, so the board's squares and the painted cells line up.
		canvasLeft = ((x + (size - canvas) / 2f) * ppu).roundToInt() / ppu
		canvasTop = ((y + (size - canvas) / 2f) * ppu).roundToInt() / ppu

		// The board and the drawing are a quad each whatever the size: a cell at
		// a time was thousands of fills a frame on the big canvas.
		RoundRect.checker(ui.graphics, canvasLeft, canvasTop, canvasLeft + canvas, canvasTop + canvas, 0f, cell, ui.alpha, CHECKER_TINT)
		val palette = List(CrosshairEditor.PALETTE_SIZE) { CrosshairEditor.colorOf(it + 1) }
		canvasTexture.update(listOf(grid, grid.revision, cells, palette)) { set ->
			for (gy in 0 until cells) for (gx in 0 until cells) {
				val value = grid[gx, gy]
				if (value != 0) set(gx, gy, palette[value - 1])
			}
		}
		blitCells(canvasTexture, canvasLeft, canvasTop, canvasLeft + canvas, canvasTop + canvas, cells, MenuStyle.fade(WHITE, ui.alpha))
		inPixels { fill ->
			// Faint guides through the centre, and the centre cell outlined.
			val c = grid.centre
			val guide = MenuStyle.fade(MenuStyle.accent(), 0.35f)
			val mid = 0.5f / UiText.pixelsPerUnit
			fill(canvasLeft + (c + 0.5f) * cell - mid, canvasTop, canvasLeft + (c + 0.5f) * cell + mid, canvasTop + canvas, guide)
			fill(canvasLeft, canvasTop + (c + 0.5f) * cell - mid, canvasLeft + canvas, canvasTop + (c + 0.5f) * cell + mid, guide)
		}
		val c = grid.centre
		RoundRect.outline(ui.graphics, canvasLeft + c * cell, canvasTop + c * cell, canvasLeft + (c + 1) * cell, canvasTop + (c + 1) * cell, MenuStyle.fade(MenuStyle.accent(), ui.alpha), 0f, 1f)

		// The cell under the pointer.
		val hx = floor((ui.mx - canvasLeft) / cell).toInt()
		val hy = floor((ui.my - canvasTop) / cell).toInt()
		if (hx in 0 until cells && hy in 0 until cells && ui.area.contains(ui.mx, ui.my)) {
			RoundRect.outline(ui.graphics, canvasLeft + hx * cell, canvasTop + hy * cell, canvasLeft + (hx + 1) * cell, canvasTop + (hy + 1) * cell, MenuStyle.fade(MenuStyle.TEXT, ui.alpha), 0f, 1f)
		}
	}

	private fun drawPalette(x: Float, y: Float, width: Float): Float {
		val h = 76f
		ui.card(Box(x, y, width, h))
		ui.text("Colour", x + 18f, y + 12f, 14f, MenuStyle.TEXT)
		ui.text("Click the chosen one again to change it", x + width - 18f - ui.width("Click the chosen one again to change it", 12f), y + 13f, 12f, MenuStyle.MUTED)
		val swatch = 30f
		var sx = x + 18f
		val sy = y + 36f
		for (index in 1..CrosshairEditor.PALETTE_SIZE) {
			val box = Box(sx, sy, swatch, swatch)
			val selected = colour == index
			if (selected) RoundRect.fill(ui.graphics, box.x - 3f, box.y - 3f, box.right + 3f, box.bottom + 3f, MenuStyle.fade(MenuStyle.accent(), ui.alpha), 8f)
			val argb = CrosshairEditor.colorOf(index)
			if ((argb ushr 24) < 0xFF) RoundRect.checker(ui.graphics, box.x, box.y, box.right, box.bottom, 6f, 5f, ui.alpha)
			RoundRect.fill(ui.graphics, box.x, box.y, box.right, box.bottom, MenuStyle.fade(argb, ui.alpha), 6f)
			RoundRect.outline(ui.graphics, box.x, box.y, box.right, box.bottom, MenuStyle.fade(0x30FFFFFF, ui.alpha), 6f, 1f)
			ui.onClick(box) {
				if (colour == index) page.openColorPicker(CrosshairEditor.palette[index - 1], box) else colour = index
			}
			sx += swatch + 10f
		}
		return h
	}

	private fun drawTools(x: Float, y: Float, width: Float): Float {
		val h = 64f
		ui.card(Box(x, y, width, h))
		ui.text("Tools", x + 18f, y + (h - 14f) / 2f, 14f, MenuStyle.TEXT)
		var bx = x + 120f
		val by = y + (h - 32f) / 2f
		bx += tool(bx, by, PENCIL, !fillTool) { fillTool = false } + 6f
		bx += tool(bx, by, FILL, fillTool) { fillTool = true } + 18f
		bx += tool(bx, by, MIRROR_X, mirrorX) { mirrorX = !mirrorX } + 6f
		bx += tool(bx, by, MIRROR_Y, mirrorY) { mirrorY = !mirrorY } + 18f
		bx += tool(bx, by, Sections.UNDO, false) { undoLast() } + 6f
		tool(bx, by, Sections.TRASH, false, Sections.DANGER) {
			pushUndo()
			grid.clear()
			CrosshairEditor.commit(grid)
		}
		return h
	}

	/** A square tool button, lit with the accent while its tool is on. Returns its width. */
	private fun tool(x: Float, y: Float, glyph: String, active: Boolean, color: Int = MenuStyle.TAB_HOVER, action: () -> Unit): Float {
		val box = Box(x, y, 32f, 32f)
		val over = ui.hovered(box)
		val fill = when {
			active -> MenuStyle.accent()
			over -> MenuStyle.POPUP_HOVER
			else -> MenuStyle.FIELD
		}
		RoundRect.fill(ui.graphics, box.x, box.y, box.right, box.bottom, MenuStyle.fade(fill, ui.alpha), 6f)
		ui.icon(glyph, box, 13f, if (active) 0xFFFFFFFF.toInt() else color)
		ui.onClick(box, action)
		return box.w
	}

	private fun drawSizes(x: Float, y: Float, width: Float): Float {
		val h = 58f
		ui.card(Box(x, y, width, h))
		ui.text("Canvas", x + 18f, y + (h - 14f) / 2f, 14f, MenuStyle.TEXT)
		var cx = x + 120f
		for (size in CrosshairGrid.SIZES) {
			cx += ui.chip(cx, y + (h - 24f) / 2f, "${size}x$size", grid.size == size) {
				if (grid.size != size) {
					pushUndo()
					grid.resize(size)
					CrosshairEditor.commit(grid)
				}
			} + 6f
		}
		return h
	}

	private fun drawPresets(x: Float, y: Float, width: Float): Float {
		val labels = CrosshairPreset.entries.map { it.title }
		val chipsW = width - 120f - 18f
		val h = max(58f, ui.chipsHeight(chipsW, labels) + 34f)
		ui.card(Box(x, y, width, h))
		ui.text("Presets", x + 18f, y + 17f, 14f, MenuStyle.TEXT)
		ui.chips(x + 120f, y + 17f, chipsW, CrosshairPreset.entries, { it.title }, { false }) { preset ->
			pushUndo()
			preset.paint(grid, colour)
			CrosshairEditor.commit(grid)
		}
		return h
	}

	/** The crosshair over a bright sky and over dark stone, at the size it is in game. */
	private fun drawPreview(x: Float, y: Float, width: Float): Float {
		val h = 150f
		ui.card(Box(x, y, width, h))
		ui.text("In game", x + 18f, y + 12f, 14f, MenuStyle.TEXT)
		val boxY = y + 36f
		val boxH = h - 50f
		val half = (width - 36f - 10f) / 2f
		val light = Box(x + 18f, boxY, half, boxH)
		val dark = Box(light.right + 10f, boxY, half, boxH)
		RoundRect.fill(ui.graphics, light.x, light.y, light.right, light.bottom, MenuStyle.fade(PREVIEW_LIGHT, ui.alpha), 6f)
		RoundRect.fill(ui.graphics, dark.x, dark.y, dark.right, dark.bottom, MenuStyle.fade(PREVIEW_DARK, ui.alpha), 6f)

		// One cell is one of vanilla's crosshair pixels at your GUI scale, times the Scale setting.
		val window = Minecraft.getInstance().window
		var cellPixels = CrosshairEditor.cellPixels(window.guiScale).toFloat()
		val fits = floor(boxH * UiText.pixelsPerUnit * 0.9f / grid.size).coerceAtLeast(1f)
		val shrunk = cellPixels > fits
		if (shrunk) cellPixels = fits
		val previewCell = cellPixels / UiText.pixelsPerUnit
		drawCrosshairAt(light.x + light.w / 2f, light.y + light.h / 2f, previewCell, PREVIEW_LIGHT)
		drawCrosshairAt(dark.x + dark.w / 2f, dark.y + dark.h / 2f, previewCell, PREVIEW_DARK)
		if (shrunk) ui.text("Shrunk to fit", x + width - 18f - ui.width("Shrunk to fit", 12f), y + 13f, 12f, MenuStyle.MUTED)
		return h
	}

	private fun drawCrosshairAt(centreX: Float, centreY: Float, size: Float, background: Int) {
		val c = grid.centre
		val ppu = UiText.pixelsPerUnit
		// Snapped to whole pixels, so the preview is as crisp as the real thing.
		val left = ((centreX - (c + 0.5f) * size) * ppu).roundToInt() / ppu
		val top = ((centreY - (c + 0.5f) * size) * ppu).roundToInt() / ppu
		val vanilla = CrosshairEditor.usesVanillaBlending
		updatePreviewTexture(vanilla)
		// Vanilla blending paints white, tinted here to the negative of the background.
		val tint = if (vanilla) inverted(background) else WHITE
		val cells = grid.size + 2
		blitCells(previewTexture, left - size, top - size, left + (cells - 1) * size, top + (cells - 1) * size, cells, MenuStyle.fade(tint, ui.alpha))
	}

	/**
	 * The drawing as it looks in game, with a cell of room on each side for the
	 * outline: the palette at the chosen opacity, or plain white to be tinted
	 * when the game inverts it instead.
	 */
	private fun updatePreviewTexture(vanilla: Boolean) {
		val palette = List(CrosshairEditor.PALETTE_SIZE) { CrosshairEditor.colorOf(it + 1) }
		val outline = !vanilla && CrosshairEditor.outline.value
		val outlineArgb = CrosshairEditor.outlineColor.argb
		val opacity = CrosshairEditor.opacity.value.toFloat()
		previewTexture.update(listOf(grid, grid.revision, grid.size, vanilla, palette, outline, outlineArgb, opacity)) { set ->
			for (gy in -1..grid.size) for (gx in -1..grid.size) {
				val value = grid[gx, gy]
				when {
					value != 0 -> set(gx + 1, gy + 1, if (vanilla) WHITE else withOpacity(palette[value - 1], opacity))
					outline && touchesPaint(gx, gy) -> set(gx + 1, gy + 1, outlineArgb)
				}
			}
		}
	}

	/** Stretches a texture's top-left [cells] square over a box given in design pixels, snapped to real ones. */
	private fun blitCells(texture: CrosshairTexture, x0: Float, y0: Float, x1: Float, y1: Float, cells: Int, color: Int) {
		val ppu = UiText.pixelsPerUnit
		val pose = ui.graphics.pose()
		pose.pushMatrix()
		pose.scale(1f / ppu, 1f / ppu)
		val left = (x0 * ppu).roundToInt()
		val top = (y0 * ppu).roundToInt()
		texture.draw(ui.graphics, left, top, (x1 * ppu).roundToInt() - left, (y1 * ppu).roundToInt() - top, cells, color)
		pose.popMatrix()
	}

	private fun touchesPaint(x: Int, y: Int): Boolean {
		for (dy in -1..1) for (dx in -1..1) {
			if ((dx != 0 || dy != 0) && grid[x + dx, y + dy] != 0) return true
		}
		return false
	}

	/**
	 * Fills in real pixels: rectangles given in design pixels are rounded to
	 * whole screen pixels, which is what keeps a grid of thousands of cells
	 * crisp, and costs one plain quad each.
	 */
	private inline fun inPixels(block: ((Float, Float, Float, Float, Int) -> Unit) -> Unit) {
		val ppu = UiText.pixelsPerUnit
		val pose = ui.graphics.pose()
		pose.pushMatrix()
		pose.scale(1f / ppu, 1f / ppu)
		block { x0, y0, x1, y1, color ->
			ui.graphics.fill((x0 * ppu).roundToInt(), (y0 * ppu).roundToInt(), (x1 * ppu).roundToInt(), (y1 * ppu).roundToInt(), MenuStyle.fade(color, ui.alpha))
		}
		pose.popMatrix()
	}

	// ---- Painting --------------------------------------------------------

	override fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean): Boolean {
		val cellX = floor((mx - canvasLeft) / cell).toInt()
		val cellY = floor((my - canvasTop) / cell).toInt()
		val onCanvas = cellX in 0 until grid.size && cellY in 0 until grid.size
		if (onCanvas && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
			pushUndo()
			strokeButton = button
			val value = strokeValue()
			if (fillTool) {
				grid.fill(cellX, cellY, value)
				mirrored(cellX, cellY) { mx2, my2 -> grid.fill(mx2, my2, value) }
				endStroke()
				return true
			}
			lastCellX = cellX
			lastCellY = cellY
			plot(cellX, cellY, value)
			return true
		}
		return super.mouseClicked(mx, my, button, doubleClick)
	}

	override fun mouseDragged(mx: Float, my: Float): Boolean {
		if (strokeButton < 0) return super.mouseDragged(mx, my)
		val cellX = floor((mx - canvasLeft) / cell).toInt().coerceIn(0, grid.size - 1)
		val cellY = floor((my - canvasTop) / cell).toInt().coerceIn(0, grid.size - 1)
		if (cellX == lastCellX && cellY == lastCellY) return true
		val value = strokeValue()
		line(lastCellX, lastCellY, cellX, cellY) { lx, ly -> plot(lx, ly, value) }
		lastCellX = cellX
		lastCellY = cellY
		return true
	}

	override fun mouseReleased(): Boolean {
		if (strokeButton >= 0) {
			endStroke()
			return true
		}
		return super.mouseReleased()
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		if (event.key() == GLFW.GLFW_KEY_Z && (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0) {
			undoLast()
			return true
		}
		return super.keyPressed(event)
	}

	private fun strokeValue(): Int = if (strokeButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT) 0 else colour

	private fun plot(x: Int, y: Int, value: Int) {
		grid[x, y] = value
		mirrored(x, y) { mx, my -> grid[mx, my] = value }
	}

	private inline fun mirrored(x: Int, y: Int, action: (Int, Int) -> Unit) {
		val mx = grid.mirror(x)
		val my = grid.mirror(y)
		if (mirrorX && mx >= 0) action(mx, y)
		if (mirrorY && my >= 0) action(x, my)
		if (mirrorX && mirrorY && mx >= 0 && my >= 0) action(mx, my)
	}

	private fun endStroke() {
		strokeButton = -1
		lastCellX = -1
		lastCellY = -1
		CrosshairEditor.commit(grid)
	}

	/** Bresenham's line, so a fast stroke leaves no gaps. */
	private inline fun line(x0: Int, y0: Int, x1: Int, y1: Int, action: (Int, Int) -> Unit) {
		var x = x0
		var y = y0
		val dx = abs(x1 - x0)
		val dy = -abs(y1 - y0)
		val sx = if (x0 < x1) 1 else -1
		val sy = if (y0 < y1) 1 else -1
		var error = dx + dy
		while (true) {
			action(x, y)
			if (x == x1 && y == y1) break
			val twice = error * 2
			if (twice >= dy) {
				error += dy
				x += sx
			}
			if (twice <= dx) {
				error += dx
				y += sy
			}
		}
	}

	private fun pushUndo() {
		undo.addLast(grid.snapshot() to grid.size)
		while (undo.size > UNDO_LIMIT) undo.removeFirst()
	}

	private fun undoLast() {
		val (cells, size) = undo.removeLastOrNull() ?: return
		grid.restore(cells, size)
		CrosshairEditor.commit(grid)
	}

	private fun inverted(argb: Int): Int = (argb and OPAQUE) or (argb.inv() and 0x00FFFFFF)

	private fun withOpacity(argb: Int, opacity: Float): Int {
		val alpha = (((argb ushr 24) and 0xFF) * opacity).roundToInt().coerceIn(0, 255)
		return (argb and 0x00FFFFFF) or (alpha shl 24)
	}

	private companion object {
		const val UNDO_LIMIT = 50
		const val OPAQUE = 0xFF000000.toInt()
		const val PREVIEW_LIGHT = 0xFFB9D3EE.toInt()
		const val PREVIEW_DARK = 0xFF2A2A2A.toInt()
		/** Darkens the swatch checkerboard to the card's greys. */
		const val CHECKER_TINT = 0x2C2C2C
		const val WHITE = 0xFFFFFFFF.toInt()

		// Made once and kept: the page is rebuilt every time it opens, and a
		// texture registered per page would be one more left behind each time.
		val canvasTexture by lazy { CrosshairTexture("canvas") }
		val previewTexture by lazy { CrosshairTexture("preview") }

		// The editor's own Font Awesome glyphs.
		const val PENCIL = ""
		const val FILL = ""
		const val MIRROR_X = ""
		const val MIRROR_Y = ""
	}
}
