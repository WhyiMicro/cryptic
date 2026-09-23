package imicro.cryptic.gui

import com.mojang.blaze3d.platform.InputConstants
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiColorEditFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imicro.cryptic.config.ConfigManager
import imicro.cryptic.crosshair.CrosshairGrid
import imicro.cryptic.crosshair.CrosshairPreset
import imicro.cryptic.feature.ClickGui
import imicro.cryptic.feature.CrosshairEditor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Crosshair Editor's window: a canvas to paint on and a preview of the result.
 *
 * Drawn the way the settings screen and the Carry Manager are drawn — cards in
 * the same palette at the same scale — because three windows in one mod that
 * look like three programs is worse than the work of matching them.
 *
 * The left mouse button paints with the chosen colour and the right one erases,
 * which is how every pixel editor anybody has used works. Strokes are drawn
 * cell to cell rather than sampled, so a fast drag leaves a line instead of a
 * trail of dots, and every stroke is one step of undo.
 */
class CrosshairScreen: Screen(Component.literal("Crosshair Editor")), ImGuiScreen {
	private val grid: CrosshairGrid = CrosshairEditor.grid()

	/** The colour being painted with, one to [CrosshairEditor.PALETTE_SIZE]. */
	private var colour = 1

	private var fillTool = false
	private var mirrorX = true
	private var mirrorY = true

	/** The button a stroke was started with, or -1 between strokes. */
	private var strokeButton = -1
	private var lastCellX = -1
	private var lastCellY = -1

	/** One entry per stroke, newest last. */
	private val undo = ArrayDeque<Pair<IntArray, Int>>()

	/** The palette entry whose colour picker is open, or -1. */
	private var editingColour = -1
	private val pickerValue = FloatArray(4)

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		extractBlurredBackground(graphics)
		graphics.fill(0, 0, width, height, 0x99000000.toInt())
	}

	override fun isPauseScreen() = false

	override fun keyPressed(event: KeyEvent): Boolean {
		ImGuiRuntime.key(event.key(), event.scancode(), event.modifiers(), true)
		// The one shortcut a drawing tool cannot be without.
		if (event.key() == GLFW.GLFW_KEY_Z && (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0) {
			undoLast()
			return true
		}
		if (event.key() == InputConstants.KEY_ESCAPE && editingColour >= 0) {
			editingColour = -1
			return true
		}
		return super.keyPressed(event)
	}

	override fun keyReleased(event: KeyEvent): Boolean {
		ImGuiRuntime.key(event.key(), event.scancode(), event.modifiers(), false)
		return super.keyReleased(event)
	}

	override fun charTyped(event: CharacterEvent): Boolean {
		ImGuiRuntime.character(event.codepoint())
		return true
	}

	override fun mouseMoved(mouseX: Double, mouseY: Double) {
		ImGuiRuntime.mousePosition(mouseX, mouseY)
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		ImGuiRuntime.mouseButton(event.button(), true)
		return true
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		ImGuiRuntime.mouseButton(event.button(), false)
		return true
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		return true
	}

	override fun mouseScrolled(
		mouseX: Double,
		mouseY: Double,
		horizontalAmount: Double,
		verticalAmount: Double,
	): Boolean {
		ImGuiRuntime.mousePosition(mouseX, mouseY)
		ImGuiRuntime.scroll(horizontalAmount, verticalAmount)
		return true
	}

	override fun removed() {
		ImGuiRuntime.releaseInput()
		CrosshairEditor.commit(grid)
		// This window is not the settings screen, so the settings screen's own
		// autosave never sees what was drawn here unless it is written now.
		ConfigManager.flush()
		super.removed()
	}

	override fun drawImGui() {
		val io = ImGui.getIO()
		val displayWidth = io.displaySizeX
		val displayHeight = io.displaySizeY
		if (displayWidth <= 0f || displayHeight <= 0f) return

		val scale = (displayHeight / REFERENCE_HEIGHT * REFERENCE_SCALE).coerceAtLeast(0.75f)

		ImGui.setNextWindowPos(0f, 0f)
		ImGui.setNextWindowSize(displayWidth, displayHeight)
		val flags = ImGuiWindowFlags.NoDecoration or
			ImGuiWindowFlags.NoMove or
			ImGuiWindowFlags.NoSavedSettings or
			ImGuiWindowFlags.NoBackground or
			ImGuiWindowFlags.NoBringToFrontOnFocus or
			ImGuiWindowFlags.NoNavFocus

		ImGui.begin("##cryptic_crosshair_root", flags)
		val draw = ImGui.getWindowDrawList()

		val panelWidth = dp(PANEL_WIDTH, scale)
		val panelX = (displayWidth - panelWidth) / 2f
		val top = dp(28f, scale)

		drawHeader(draw, panelX, top, panelWidth, scale)

		val bodyY = top + dp(30f, scale) + dp(CARD_GAP, scale)
		val canvasCard = dp(CANVAS_CARD, scale)
		drawCanvas(draw, panelX, bodyY, canvasCard, scale)

		val sideX = panelX + canvasCard + dp(CARD_GAP, scale)
		val sideWidth = panelWidth - canvasCard - dp(CARD_GAP, scale)
		var y = bodyY
		y = drawPalette(draw, sideX, y, sideWidth, scale)
		y = drawTools(draw, sideX, y, sideWidth, scale)
		y = drawSizes(draw, sideX, y, sideWidth, scale)
		drawPresets(draw, sideX, y, sideWidth, scale)

		val previewY = bodyY + canvasCard + dp(CARD_GAP, scale)
		drawPreview(draw, panelX, previewY, panelWidth, scale)

		drawColourPicker(scale)

		ImGui.end()
	}

	/** The title bar, which is the other windows' tab pill with one tab. */
	private fun drawHeader(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float) {
		val height = dp(30f, scale)
		draw.addRectFilled(x, y, x + width, y + height, SURFACE, height / 2f)
		centered(draw, "Crosshair Editor", x, y, width, height, TEXT, dp(11f, scale))

		val hint = "Left paints  ·  Right erases  ·  Ctrl+Z undoes"
		label(
			draw,
			hint,
			x + width - dp(16f, scale) - textWidth(hint, dp(8.5f, scale)),
			y + (height - ImGuiRuntime.textHeight(hint, dp(8.5f, scale))) / 2f,
			MUTED_TEXT,
			dp(8.5f, scale),
		)
	}

	/**
	 * The canvas, and all of the painting.
	 *
	 * Mouse state is read from Dear ImGui directly rather than through a button,
	 * because a drag across a grid is not a click on any one thing in it — and
	 * a stroke only starts on a press that lands on the canvas, so letting go of
	 * a swatch over it paints nothing.
	 */
	private fun drawCanvas(draw: ImDrawList, x: Float, y: Float, size: Float, scale: Float) {
		card(draw, x, y, size, size, scale)

		val padding = dp(12f, scale)
		val area = size - padding * 2f
		val cells = grid.size
		val cell = floor(area / cells).coerceAtLeast(1f)
		val canvas = cell * cells
		val left = x + (size - canvas) / 2f
		val topY = y + (size - canvas) / 2f

		// A checkerboard, so an empty cell reads as empty rather than as black.
		for (cy in 0 until cells) {
			for (cx in 0 until cells) {
				val shade = if ((cx + cy) % 2 == 0) CHECKER_DARK else CHECKER_LIGHT
				val px = left + cx * cell
				val py = topY + cy * cell
				draw.addRectFilled(px, py, px + cell, py + cell, shade)
				val value = grid[cx, cy]
				if (value != 0) draw.addRectFilled(px, py, px + cell, py + cell, abgr(CrosshairEditor.colorOf(value)))
			}
		}

		// The centre cell, which is the one over the middle of the screen. The
		// lines through it are what make a symmetrical drawing possible by eye.
		val c = grid.centre
		val guide = fade(ACCENT, 0.35f)
		draw.addLine(left + (c + 0.5f) * cell, topY, left + (c + 0.5f) * cell, topY + canvas, guide, 1f)
		draw.addLine(left, topY + (c + 0.5f) * cell, left + canvas, topY + (c + 0.5f) * cell, guide, 1f)
		draw.addRect(
			left + c * cell,
			topY + c * cell,
			left + (c + 1) * cell,
			topY + (c + 1) * cell,
			ACCENT,
			0f,
			0,
			max(1f, dp(1f, scale)),
		)

		val mouse = ImGui.getIO().mousePos
		val hovering = mouse.x >= left && mouse.x < left + canvas && mouse.y >= topY && mouse.y < topY + canvas
		val cellX = floor((mouse.x - left) / cell).toInt()
		val cellY = floor((mouse.y - topY) / cell).toInt()

		if (hovering && editingColour < 0) {
			draw.addRect(
				left + cellX * cell,
				topY + cellY * cell,
				left + (cellX + 1) * cell,
				topY + (cellY + 1) * cell,
				TEXT,
				0f,
				0,
				max(1f, dp(1f, scale)),
			)
		}

		paint(hovering, cellX, cellY)
	}

	private fun paint(hovering: Boolean, cellX: Int, cellY: Int) {
		if (editingColour >= 0) return

		if (strokeButton < 0) {
			val button = when {
				ImGui.isMouseClicked(ImGuiMouseButton.Left) -> ImGuiMouseButton.Left
				ImGui.isMouseClicked(ImGuiMouseButton.Right) -> ImGuiMouseButton.Right
				else -> return
			}
			if (!hovering) return

			pushUndo()
			strokeButton = button
			val value = strokeValue()
			if (fillTool) {
				grid.fill(cellX, cellY, value)
				mirrored(cellX, cellY) { mx, my -> grid.fill(mx, my, value) }
				endStroke()
				return
			}
			lastCellX = cellX
			lastCellY = cellY
			plot(cellX, cellY, value)
			return
		}

		if (!ImGui.isMouseDown(strokeButton)) {
			endStroke()
			return
		}

		if (cellX == lastCellX && cellY == lastCellY) return
		// Every cell between the last one and this one, so a quick drag is a
		// line and not a dotted one.
		val value = strokeValue()
		line(lastCellX, lastCellY, cellX, cellY) { lx, ly -> plot(lx, ly, value) }
		lastCellX = cellX
		lastCellY = cellY
	}

	private fun strokeValue(): Int = if (strokeButton == ImGuiMouseButton.Right) 0 else colour

	private fun plot(x: Int, y: Int, value: Int) {
		grid[x, y] = value
		mirrored(x, y) { mx, my -> grid[mx, my] = value }
	}

	/** The mirror images of a cell under whichever mirrors are on. */
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

	/** Bresenham's line between two cells. */
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

	/**
	 * The palette, one swatch per colour. Erasing is the right mouse button, so
 * there is no eraser to pick.
	 *
	 * Clicking a swatch picks it; clicking the one already picked opens its
	 * colour, which is where a second click on a selected colour goes in most
	 * editors and costs no extra button.
	 */
	private fun drawPalette(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float): Float {
		val height = dp(66f, scale)
		card(draw, x, y, width, height, scale)
		label(draw, "Colour", x + dp(14f, scale), y + dp(10f, scale), MUTED_TEXT, dp(9f, scale))
		label(
			draw,
			"click again to edit",
			x + width - dp(14f, scale) - textWidth("click again to edit", dp(8f, scale)),
			y + dp(11f, scale),
			DIM_TEXT,
			dp(8f, scale),
		)

		val swatch = dp(22f, scale)
		val gap = dp(6f, scale)
		var sx = x + dp(14f, scale)
		val sy = y + dp(30f, scale)
		for (index in 1..CrosshairEditor.PALETTE_SIZE) {
			val selected = colour == index
			draw.addRectFilled(sx, sy, sx + swatch, sy + swatch, if (selected) ACCENT else TRACK, dp(6f, scale))
			draw.addRectFilled(
				sx + dp(2f, scale),
				sy + dp(2f, scale),
				sx + swatch - dp(2f, scale),
				sy + swatch - dp(2f, scale),
				abgr(CrosshairEditor.colorOf(index) or OPAQUE),
				dp(4f, scale),
			)
			if (hit("##crosshair_colour_$index", sx, sy, swatch, swatch)) {
				if (colour == index) openPicker(index) else colour = index
			}
			sx += swatch + gap
		}
		return y + height + dp(CARD_GAP, scale)
	}

	private fun openPicker(index: Int) {
		editingColour = index
		val argb = CrosshairEditor.palette[index - 1].argb
		pickerValue[0] = ((argb ushr 16) and 0xFF) / 255f
		pickerValue[1] = ((argb ushr 8) and 0xFF) / 255f
		pickerValue[2] = (argb and 0xFF) / 255f
		pickerValue[3] = ((argb ushr 24) and 0xFF) / 255f
		ImGui.openPopup(PICKER_ID)
	}

	private fun drawColourPicker(scale: Float) {
		if (editingColour < 0) return

		ImGui.pushStyleColor(ImGuiCol.PopupBg, SURFACE)
		ImGui.pushStyleColor(ImGuiCol.Border, TRACK)
		ImGui.pushStyleColor(ImGuiCol.FrameBg, RAISED)
		ImGui.pushStyleColor(ImGuiCol.Text, TEXT)
		ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, dp(8f, scale))
		ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, dp(4f, scale))
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, dp(10f, scale), dp(10f, scale))

		if (ImGui.beginPopup(PICKER_ID)) {
			ImGui.setNextItemWidth(dp(180f, scale))
			val changed = ImGui.colorPicker4(
				"##crosshair_picker",
				pickerValue,
				ImGuiColorEditFlags.AlphaBar or ImGuiColorEditFlags.NoSidePreview or ImGuiColorEditFlags.DisplayHex,
			)
			if (changed) {
				val argb = (channel(pickerValue[3]) shl 24) or
					(channel(pickerValue[0]) shl 16) or
					(channel(pickerValue[1]) shl 8) or
					channel(pickerValue[2])
				CrosshairEditor.palette[editingColour - 1].argb = argb
			}
			ImGui.endPopup()
		} else {
			// Closed by clicking elsewhere, which ImGui handles on its own.
			editingColour = -1
		}

		ImGui.popStyleVar(3)
		ImGui.popStyleColor(4)
	}

	private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()

	private fun drawTools(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float): Float {
		val height = dp(62f, scale)
		card(draw, x, y, width, height, scale)
		label(draw, "Tools", x + dp(14f, scale), y + dp(10f, scale), MUTED_TEXT, dp(9f, scale))

		val button = dp(24f, scale)
		val gap = dp(6f, scale)
		var bx = x + dp(14f, scale)
		val by = y + dp(28f, scale)

		toolButton(draw, bx, by, button, scale, "##crosshair_pencil", FontAwesomeIcons.PENCIL, !fillTool) { fillTool = false }
		bx += button + gap
		toolButton(draw, bx, by, button, scale, "##crosshair_fill", FontAwesomeIcons.FILL, fillTool) { fillTool = true }
		bx += button + gap * 3
		toolButton(draw, bx, by, button, scale, "##crosshair_mirror_x", FontAwesomeIcons.MIRROR_X, mirrorX) { mirrorX = !mirrorX }
		bx += button + gap
		toolButton(draw, bx, by, button, scale, "##crosshair_mirror_y", FontAwesomeIcons.MIRROR_Y, mirrorY) { mirrorY = !mirrorY }
		bx += button + gap * 3
		toolButton(draw, bx, by, button, scale, "##crosshair_undo", FontAwesomeIcons.UNDO, false) { undoLast() }
		bx += button + gap
		toolButton(draw, bx, by, button, scale, "##crosshair_clear", FontAwesomeIcons.TRASH, false, DELETE_TEXT) {
			pushUndo()
			grid.clear()
			CrosshairEditor.commit(grid)
		}

		return y + height + dp(CARD_GAP, scale)
	}

	/** The canvas sizes, as one pill each: five choices is a row, not a dropdown. */
	private fun drawSizes(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float): Float {
		val height = dp(58f, scale)
		card(draw, x, y, width, height, scale)
		label(draw, "Canvas", x + dp(14f, scale), y + dp(10f, scale), MUTED_TEXT, dp(9f, scale))

		val sizes = CrosshairGrid.SIZES
		val gap = dp(5f, scale)
		val pill = (width - dp(28f, scale) - gap * (sizes.size - 1)) / sizes.size
		val pillHeight = dp(20f, scale)
		var px = x + dp(14f, scale)
		val py = y + dp(28f, scale)
		for (size in sizes) {
			val selected = grid.size == size
			val hovered = ImGui.isMouseHoveringRect(px, py, px + pill, py + pillHeight)
			draw.addRectFilled(px, py, px + pill, py + pillHeight, if (selected) ACCENT else if (hovered) ACTIVE else TRACK, pillHeight / 2f)
			centered(draw, "$size", px, py, pill, pillHeight, if (selected) SURFACE else TEXT, dp(9.5f, scale))
			if (hit("##crosshair_size_$size", px, py, pill, pillHeight) && !selected) {
				pushUndo()
				grid.resize(size)
				CrosshairEditor.commit(grid)
			}
			px += pill + gap
		}
		return y + height + dp(CARD_GAP, scale)
	}

	private fun drawPresets(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float): Float {
		val presets = CrosshairPreset.entries
		val columns = 3
		val rows = (presets.size + columns - 1) / columns
		val gap = dp(5f, scale)
		val buttonHeight = dp(20f, scale)
		val height = dp(28f, scale) + rows * buttonHeight + (rows - 1) * gap + dp(12f, scale)

		card(draw, x, y, width, height, scale)
		label(draw, "Presets", x + dp(14f, scale), y + dp(10f, scale), MUTED_TEXT, dp(9f, scale))

		val buttonWidth = (width - dp(28f, scale) - gap * (columns - 1)) / columns
		presets.forEachIndexed { index, preset ->
			val bx = x + dp(14f, scale) + (index % columns) * (buttonWidth + gap)
			val by = y + dp(28f, scale) + (index / columns) * (buttonHeight + gap)
			val hovered = ImGui.isMouseHoveringRect(bx, by, bx + buttonWidth, by + buttonHeight)
			draw.addRectFilled(bx, by, bx + buttonWidth, by + buttonHeight, if (hovered) ACTIVE else TRACK, buttonHeight / 2f)
			centered(draw, preset.title, bx, by, buttonWidth, buttonHeight, TEXT, dp(9.5f, scale))
			if (hit("##crosshair_preset_${preset.name}", bx, by, buttonWidth, buttonHeight)) {
				pushUndo()
				preset.paint(grid, colour)
				CrosshairEditor.commit(grid)
			}
		}
		return y + height + dp(CARD_GAP, scale)
	}

	/**
	 * The crosshair at the size it will really be, over a light and a dark sky.
	 *
	 * Two backgrounds because the whole question a crosshair has to answer is
	 * whether it shows on both. Vanilla blending is shown the way it will look
	 * — each painted cell the negative of what is behind it — since ImGui cannot
	 * blend that way itself.
	 */
	private fun drawPreview(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float) {
		val height = dp(PREVIEW_HEIGHT, scale)
		card(draw, x, y, width, height, scale)
		label(draw, "In game", x + dp(14f, scale), y + dp(10f, scale), MUTED_TEXT, dp(9f, scale))

		val boxY = y + dp(26f, scale)
		val boxHeight = height - dp(38f, scale)
		val half = (width - dp(28f, scale) - dp(8f, scale)) / 2f
		val lightX = x + dp(14f, scale)
		val darkX = lightX + half + dp(8f, scale)

		draw.addRectFilled(lightX, boxY, lightX + half, boxY + boxHeight, abgr(PREVIEW_LIGHT), dp(6f, scale))
		draw.addRectFilled(darkX, boxY, darkX + half, boxY + boxHeight, abgr(PREVIEW_DARK), dp(6f, scale))

		// One cell is one GUI pixel times the scale setting, and ImGui draws in
		// real pixels — so the GUI scale is what turns one into the other.
		val window = Minecraft.getInstance().window
		val guiScale = window.screenWidth / window.guiScaledWidth.coerceAtLeast(1).toFloat()
		var cell = guiScale * CrosshairEditor.scale.value.toFloat()
		// A 64 canvas at a large scale is bigger than the box; it is shrunk to
		// fit and says so, rather than being cut off.
		val fits = boxHeight * 0.9f / grid.size
		val shrunk = cell > fits
		if (shrunk) cell = fits

		drawCrosshairAt(draw, lightX + half / 2f, boxY + boxHeight / 2f, cell, PREVIEW_LIGHT)
		drawCrosshairAt(draw, darkX + half / 2f, boxY + boxHeight / 2f, cell, PREVIEW_DARK)

		if (shrunk) {
			val note = "Shrunk to fit"
			label(draw, note, x + width - dp(14f, scale) - textWidth(note, dp(8f, scale)), y + dp(11f, scale), DIM_TEXT, dp(8f, scale))
		}
	}

	private fun drawCrosshairAt(draw: ImDrawList, centreX: Float, centreY: Float, cell: Float, background: Int) {
		val c = grid.centre
		val left = centreX - (c + 0.5f) * cell
		val top = centreY - (c + 0.5f) * cell
		val vanilla = CrosshairEditor.usesVanillaBlending

		if (!vanilla && CrosshairEditor.outline.value) {
			val outline = abgr(CrosshairEditor.outlineColor.argb)
			for (y in -1..grid.size) {
				for (x in -1..grid.size) {
					if (grid[x, y] != 0 || !touchesPaint(x, y)) continue
					draw.addRectFilled(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, outline)
				}
			}
		}

		val opacity = CrosshairEditor.opacity.value.toFloat()
		for (y in 0 until grid.size) {
			for (x in 0 until grid.size) {
				val value = grid[x, y]
				if (value == 0) continue
				val argb = if (vanilla) inverted(background) else withOpacity(CrosshairEditor.colorOf(value), opacity)
				draw.addRectFilled(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, abgr(argb))
			}
		}
	}

	private fun touchesPaint(x: Int, y: Int): Boolean {
		for (dy in -1..1) for (dx in -1..1) {
			if ((dx != 0 || dy != 0) && grid[x + dx, y + dy] != 0) return true
		}
		return false
	}

	private fun toolButton(
		draw: ImDrawList,
		x: Float,
		y: Float,
		size: Float,
		scale: Float,
		id: String,
		icon: String,
		active: Boolean,
		iconColour: Int = TEXT,
		action: () -> Unit,
	) {
		val hovered = ImGui.isMouseHoveringRect(x, y, x + size, y + size)
		draw.addRectFilled(x, y, x + size, y + size, if (active) ACCENT else if (hovered) ACTIVE else TRACK, dp(6f, scale))
		centered(draw, icon, x, y, size, size, if (active) SURFACE else iconColour, dp(10f, scale))
		if (hit(id, x, y, size, size)) action()
	}

	private fun card(draw: ImDrawList, x: Float, y: Float, width: Float, height: Float, scale: Float) {
		draw.addRectFilled(x, y, x + width, y + height, SURFACE, dp(10f, scale))
	}

	private fun label(draw: ImDrawList, text: String, x: Float, y: Float, color: Int, size: Float) {
		draw.addText(ImGuiRuntime.font, size.roundToInt().coerceAtLeast(1), x, y, color, text)
	}

	private fun centered(draw: ImDrawList, text: String, x: Float, y: Float, width: Float, height: Float, color: Int, size: Float) {
		val w = textWidth(text, size)
		val h = ImGuiRuntime.textHeight(text, size)
		label(draw, text, x + (width - w) / 2f, y + (height - h) / 2f, color, size)
	}

	private fun textWidth(text: String, size: Float): Float = ImGuiRuntime.textWidth(text, size)

	private fun hit(id: String, x: Float, y: Float, width: Float, height: Float): Boolean {
		ImGui.setCursorScreenPos(x, y)
		ImGui.setNextItemAllowOverlap()
		return ImGui.invisibleButton(id, width.coerceAtLeast(1f), height.coerceAtLeast(1f), ImGuiMouseButton.Left)
	}

	private fun dp(value: Float, scale: Float) = value * scale

	/** ARGB, which is what the rest of the game uses, as ImGui's ABGR. */
	private fun abgr(argb: Int): Int {
		val alpha = (argb ushr 24) and 0xFF
		val red = (argb ushr 16) and 0xFF
		val green = (argb ushr 8) and 0xFF
		val blue = argb and 0xFF
		return (alpha shl 24) or (blue shl 16) or (green shl 8) or red
	}

	private fun inverted(argb: Int): Int = (argb and OPAQUE) or (argb.inv() and 0x00FFFFFF)

	private fun withOpacity(argb: Int, opacity: Float): Int {
		val alpha = (((argb ushr 24) and 0xFF) * opacity).roundToInt().coerceIn(0, 255)
		return (argb and 0x00FFFFFF) or (alpha shl 24)
	}

	private fun fade(abgr: Int, opacity: Float): Int {
		val alpha = (((abgr ushr 24) and 0xFF) * opacity).roundToInt().coerceIn(0, 255)
		return (abgr and 0x00FFFFFF) or (alpha shl 24)
	}

	companion object {
		private const val REFERENCE_HEIGHT = 1080f
		private const val REFERENCE_SCALE = 1.5f
		private const val PANEL_WIDTH = 560f
		private const val CANVAS_CARD = 300f
		private const val PREVIEW_HEIGHT = 120f
		private const val CARD_GAP = 8f
		private const val UNDO_LIMIT = 50

		private const val PICKER_ID = "##crosshair_colour_picker"

		private const val OPAQUE = 0xFF000000.toInt()

		/** A pale sky and dark stone, as ARGB — the two things a crosshair sits on. */
		private const val PREVIEW_LIGHT = 0xFFB9D3EE.toInt()
		private const val PREVIEW_DARK = 0xFF2A2A2A.toInt()

		// Dear ImGui packs colors as ABGR; these are the settings screen's.
		private const val SURFACE = 0xFF141414.toInt()
		private const val RAISED = 0xFF3F3F3F.toInt()
		private const val ACTIVE = 0xFF464646.toInt()
		private const val TRACK = 0xFF2B2B2B.toInt()
		private const val CHECKER_DARK = 0xFF1C1C1C.toInt()
		private const val CHECKER_LIGHT = 0xFF232323.toInt()
		private const val TEXT = 0xFFE4E4E4.toInt()
		private const val MUTED_TEXT = 0xFFA4A4A4.toInt()
		private const val DIM_TEXT = 0xFF6E6E6E.toInt()
		private const val DELETE_TEXT = 0xFF4F4ADF.toInt()

		private val ACCENT: Int get() = ClickGui.accentAbgr()

		/**
		 * Set when the window should open.
		 *
		 * The button that opens it is drawn inside the settings screen's own
		 * ImGui frame, and swapping Minecraft's screen from inside that frame
		 * tears down the screen that is mid-draw — so it opens a tick later.
		 */
		private var requested = false

		fun request() {
			requested = true
		}

		fun openIfRequested(client: Minecraft) {
			if (!requested) return
			requested = false
			ImGuiRuntime.open(client) { CrosshairScreen() }
		}
	}
}
