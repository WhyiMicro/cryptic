package imicro.cryptic.gui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import imicro.cryptic.feature.ClickGui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A manager window of the Settings tab: the Keybinds, Alias and Sound managers.
 *
 * Drawn the way the Carry Manager is — a title pill over a column of cards, in
 * the settings screen's palette and at its scale — so the windows a player
 * opens from Cryptic all look like one program. What is common to them lives
 * here: forwarding input to Dear ImGui, the frame and its scrolling, and the
 * handful of controls each one is built from.
 *
 * A subclass draws its cards in [drawContent], top down from the y it is
 * given, and returns how tall they came to so the column can scroll.
 */
abstract class ManagerScreen(private val heading: String, private val windowId: String): Screen(Component.literal(heading)), ImGuiScreen {
	protected var scale = 1f
		private set
	protected var dt = 0f
		private set

	private var scrollOffset = 0f
	private var maxScroll = 0f

	/** Where the scrolling column is on screen this frame, for skipping rows out of sight. */
	protected var viewTop = 0f
		private set
	protected var viewBottom = 0f
		private set

	/** Each switch's eased position, by its id, so a flick slides rather than jumps. */
	private val switchProgress = HashMap<String, Float>()

	/** Whether a text field had the keyboard last frame, which keeps Escape from closing the window. */
	private var typing = false

	protected val isTyping: Boolean get() = typing

	// ---- Input ------------------------------------------------------------

	/**
	 * Offered every key press before Dear ImGui sees it. True keeps it there:
	 * a manager listening for a key to bind takes it here.
	 */
	protected open fun captureKey(key: Int): Boolean = false

	/** Offered every key release, for a capture that ends when the keys come up. */
	protected open fun releaseKey(key: Int): Boolean = false

	/** Offered every mouse press; true keeps it from Dear ImGui. */
	protected open fun captureMouse(button: Int): Boolean = false

	protected open fun releaseMouse(button: Int): Boolean = false

	/** Called as the window shuts, which is when a manager saves. */
	protected open fun onClosed() {}

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		extractBlurredBackground(graphics)
		graphics.fill(0, 0, width, height, 0x99000000.toInt())
	}

	override fun isPauseScreen() = false

	override fun keyPressed(event: KeyEvent): Boolean {
		if (captureKey(event.key())) return true
		ImGuiRuntime.key(event.key(), event.scancode(), event.modifiers(), true)
		// Escape in a field leaves the field, not the window.
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && typing) return true
		return super.keyPressed(event)
	}

	override fun keyReleased(event: KeyEvent): Boolean {
		if (releaseKey(event.key())) return true
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
		if (captureMouse(event.button())) return true
		ImGuiRuntime.mouseButton(event.button(), true)
		return true
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		if (releaseMouse(event.button())) return true
		ImGuiRuntime.mouseButton(event.button(), false)
		return true
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		return true
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		ImGuiRuntime.mousePosition(mouseX, mouseY)
		scrollOffset = (scrollOffset - verticalAmount.toFloat() * SCROLL_STEP * scale).coerceIn(0f, maxScroll)
		return true
	}

	override fun removed() {
		ImGuiRuntime.releaseInput()
		onClosed()
		super.removed()
	}

	/** Back to the top, for a manager whose list has just been swapped for another. */
	protected fun scrollToTop() {
		scrollOffset = 0f
	}

	// ---- Frame ------------------------------------------------------------

	override fun drawImGui() {
		val io = ImGui.getIO()
		val displayWidth = io.displaySizeX
		val displayHeight = io.displaySizeY
		if (displayWidth <= 0f || displayHeight <= 0f) return

		scale = (displayHeight / REFERENCE_HEIGHT * REFERENCE_SCALE).coerceAtLeast(0.75f)
		dt = io.deltaTime.coerceIn(0f, 0.05f)

		ImGui.setNextWindowPos(0f, 0f)
		ImGui.setNextWindowSize(displayWidth, displayHeight)
		val flags = ImGuiWindowFlags.NoDecoration or
			ImGuiWindowFlags.NoMove or
			ImGuiWindowFlags.NoSavedSettings or
			ImGuiWindowFlags.NoBackground or
			ImGuiWindowFlags.NoBringToFrontOnFocus or
			ImGuiWindowFlags.NoNavFocus

		ImGui.begin("##cryptic_${windowId}_root", flags)
		val draw = ImGui.getWindowDrawList()
		typing = false

		val panelWidth = dp(PANEL_WIDTH)
		val panelX = (displayWidth - panelWidth) / 2f

		drawHeader(draw, panelX, panelWidth)

		val contentY = dp(CONTENT_Y)
		val contentBottom = displayHeight - dp(CONTENT_BOTTOM_MARGIN)
		viewTop = contentY
		viewBottom = contentBottom
		draw.pushClipRect(panelX, contentY, panelX + panelWidth, contentBottom, true)
		val used = drawContent(draw, panelX, contentY - scrollOffset, panelWidth)
		draw.popClipRect()

		drawOverlay(draw)

		maxScroll = (used - (contentBottom - contentY)).coerceAtLeast(0f)
		scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

		ImGui.end()
		afterFrame()
	}

	/** The window's name in a pill across the top, where the Carry Manager has its tabs. */
	private fun drawHeader(draw: ImDrawList, panelX: Float, panelWidth: Float) {
		val y = dp(28f)
		val height = dp(30f)
		draw.addRectFilled(panelX, y, panelX + panelWidth, y + height, SURFACE, height / 2f)
		draw.addRectFilled(
			panelX + dp(4f), y + dp(4f), panelX + panelWidth - dp(4f), y + height - dp(4f),
			SURFACE_ACTIVE, (height - dp(8f)) / 2f,
		)
		centered(draw, heading, panelX, y, panelWidth, height, TEXT, dp(11f))
	}

	/** Draws the cards, top down from [top], and returns how tall they came to. */
	protected abstract fun drawContent(draw: ImDrawList, x: Float, top: Float, width: Float): Float

	/** Anything drawn over the cards and outside their clip, such as an open list. */
	protected open fun drawOverlay(draw: ImDrawList) {}

	/** After the frame has ended: where a change made by a button is applied. */
	protected open fun afterFrame() {}

	// ---- Controls ---------------------------------------------------------

	protected fun card(draw: ImDrawList, x: Float, y: Float, width: Float, height: Float) {
		draw.addRectFilled(x, y, x + width, y + height, SURFACE, dp(10f))
	}

	/**
	 * A card as tall as what [content] draws in it, for cards whose rows wrap.
	 * The content goes on a layer above, and the card is filled in under it
	 * once its height is known. [content] returns the height it used.
	 */
	protected fun measuredCard(draw: ImDrawList, x: Float, y: Float, width: Float, content: () -> Float): Float {
		draw.channelsSplit(2)
		draw.channelsSetCurrent(1)
		val height = content()
		draw.channelsSetCurrent(0)
		card(draw, x, y, width, height)
		draw.channelsMerge()
		return height
	}

	/** True when [y] to [y] + [height] is at least partly in the scrolling column. */
	protected fun inView(y: Float, height: Float): Boolean = y + height >= viewTop && y <= viewBottom

	protected fun label(draw: ImDrawList, text: String, x: Float, y: Float, color: Int, size: Float) {
		draw.addText(ImGuiRuntime.font, size.roundToInt().coerceAtLeast(1), x, y, color, text)
	}

	protected fun centered(draw: ImDrawList, text: String, x: Float, y: Float, width: Float, height: Float, color: Int, size: Float) {
		val w = textWidth(text, size)
		val h = ImGuiRuntime.textHeight(text, size)
		label(draw, text, x + (width - w) / 2f, y + (height - h) / 2f, color, size)
	}

	/** [text] cut to fit [maxWidth], with an ellipsis where it was cut. */
	protected fun ellipsize(text: String, size: Float, maxWidth: Float): String {
		if (textWidth(text, size) <= maxWidth) return text
		var end = text.length
		while (end > 0 && textWidth(text.substring(0, end) + "...", size) > maxWidth) end--
		return text.substring(0, end) + "..."
	}

	protected fun textWidth(text: String, size: Float): Float = ImGuiRuntime.textWidth(text, size)

	protected fun hit(id: String, x: Float, y: Float, width: Float, height: Float): Boolean {
		ImGui.setCursorScreenPos(x, y)
		ImGui.setNextItemAllowOverlap()
		return ImGui.invisibleButton(id, width.coerceAtLeast(1f), height.coerceAtLeast(1f), ImGuiMouseButton.Left)
	}

	protected fun hovered(x: Float, y: Float, width: Float, height: Float): Boolean =
		ImGui.isMouseHoveringRect(x, y, x + width, y + height)

	/** A rounded button, filled with the accent while the mouse is over it. */
	protected fun button(draw: ImDrawList, id: String, text: String, x: Float, y: Float, width: Float, height: Float, color: Int = TEXT): Boolean {
		val over = hovered(x, y, width, height)
		draw.addRectFilled(x, y, x + width, y + height, if (over) ACCENT else SURFACE_ACTIVE, height / 2f)
		centered(draw, text, x, y, width, height, if (over) SURFACE else color, dp(10f))
		return hit(id, x, y, width, height)
	}

	/** A button as wide as its text, right-aligned to [right]. Returns its left edge and whether it was clicked. */
	protected fun buttonLeftOf(draw: ImDrawList, id: String, text: String, right: Float, y: Float, height: Float): Pair<Float, Boolean> {
		val width = textWidth(text, dp(10f)) + dp(22f)
		val x = right - width
		return x to button(draw, id, text, x, y, width, height)
	}

	/** A round button holding one glyph: ✎, X, a play arrow. */
	protected fun iconButton(draw: ImDrawList, id: String, glyph: String, x: Float, y: Float, size: Float, color: Int = TEXT): Boolean {
		val over = hovered(x, y, size, size)
		draw.addRectFilled(x, y, x + size, y + size, if (over) SURFACE_ACTIVE else TRACK, size / 2f)
		centered(draw, glyph, x, y, size, size, color, dp(9.5f))
		return hit(id, x, y, size, size)
	}

	/** A pill that is on or off, for picking from a set. True when clicked. */
	protected fun chip(draw: ImDrawList, id: String, text: String, x: Float, y: Float, selected: Boolean): Boolean {
		val width = chipWidth(text)
		val height = dp(CHIP_HEIGHT)
		val over = hovered(x, y, width, height)
		val fill = when {
			selected -> ACCENT
			over -> SURFACE_ACTIVE
			else -> TRACK
		}
		draw.addRectFilled(x, y, x + width, y + height, fill, height / 2f)
		centered(draw, text, x, y, width, height, if (selected) SURFACE else TEXT, dp(9.5f))
		return hit(id, x, y, width, height)
	}

	protected fun chipWidth(text: String): Float = textWidth(text, dp(9.5f)) + dp(16f)

	/**
	 * A row of chips that wraps onto as many lines as it needs, inside [width].
	 * [onClick] is told which was clicked. Returns the height it took.
	 */
	protected fun <T> chips(
		draw: ImDrawList,
		idPrefix: String,
		items: List<T>,
		name: (T) -> String,
		selected: (T) -> Boolean,
		x: Float,
		y: Float,
		width: Float,
		onClick: (T) -> Unit,
	): Float {
		val gap = dp(5f)
		val height = dp(CHIP_HEIGHT)
		var cx = x
		var cy = y
		items.forEachIndexed { index, item ->
			val text = name(item)
			val w = chipWidth(text)
			if (cx > x && cx + w > x + width) {
				cx = x
				cy += height + gap
			}
			if (chip(draw, "##${idPrefix}_$index", text, cx, cy, selected(item))) onClick(item)
			cx += w + gap
		}
		return if (items.isEmpty()) 0f else cy + height - y
	}

	/**
	 * The settings screen's switch: the track fades from grey to the accent and
	 * the knob from dark to white as it slides. True when clicked.
	 */
	protected fun switch(draw: ImDrawList, id: String, x: Float, y: Float, on: Boolean): Boolean {
		val width = dp(SWITCH_WIDTH)
		val height = dp(SWITCH_HEIGHT)
		val target = if (on) 1f else 0f
		val current = switchProgress.getOrPut(id) { target }
		val next = current + (target - current) * (1f - exp(-SWITCH_SPEED * dt))
		val progress = if (kotlin.math.abs(next - target) < 0.01f) target else next
		switchProgress[id] = progress

		val eased = progress * progress * (3f - 2f * progress)
		draw.addRectFilled(x, y, x + width, y + height, blend(TOGGLE_OFF, ACCENT, eased), height / 2f)
		val radius = dp(5.5f)
		val knobX = x + dp(2.5f) + radius + (width - dp(5f) - radius * 2f) * eased
		draw.addCircleFilled(knobX, y + height / 2f, radius, blend(KNOB, KNOB_ON, eased), 32)
		return hit(id, x, y, width, height)
	}

	/**
	 * A slider from 0 to 1, drawn like the settings screen's. Returns the new
	 * position while it is being dragged, or null when it is left alone.
	 */
	protected fun slider(draw: ImDrawList, id: String, x: Float, y: Float, width: Float, ratio: Float): Float? {
		val trackHeight = dp(4f)
		val clamped = ratio.coerceIn(0f, 1f)
		draw.addRectFilled(x, y, x + width, y + trackHeight, TRACK, trackHeight / 2f)
		draw.addRectFilled(x, y, x + width * clamped, y + trackHeight, ACCENT, trackHeight / 2f)
		draw.addCircleFilled(x + width * clamped, y + trackHeight / 2f, dp(6f), KNOB_ON, 32)
		hit(id, x - dp(6f), y - dp(6f), width + dp(12f), dp(16f))
		if (!ImGui.isItemActive()) return null
		return ((ImGui.getMousePosX() - x) / width).coerceIn(0f, 1f)
	}

	/**
	 * A text field: a real Dear ImGui field under a box drawn here, the way the
	 * settings screen and the Carry Manager do theirs. Returns whether it has
	 * the keyboard.
	 */
	protected fun textField(
		draw: ImDrawList,
		id: String,
		hint: String,
		buffer: ImString,
		x: Float,
		y: Float,
		width: Float,
		height: Float,
		flags: Int = 0,
	): Boolean {
		val fontSize = dp(10f)
		draw.addRectFilled(x, y, x + width, y + height, RENAME_FIELD, height / 2f)

		ImGui.setCursorScreenPos(x + dp(4f), y)
		ImGui.setNextItemWidth(width - dp(8f))
		ImGui.pushStyleColor(ImGuiCol.Text, TEXT)
		ImGui.pushStyleColor(ImGuiCol.TextDisabled, MUTED_TEXT)
		ImGui.pushStyleColor(ImGuiCol.FrameBg, 0)
		ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0)
		ImGui.pushStyleColor(ImGuiCol.FrameBgActive, 0)
		ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, ACCENT_DARK)
		ImGui.pushStyleColor(ImGuiCol.InputTextCursor, ACCENT)
		ImGui.pushFont(ImGuiRuntime.font, fontSize)
		ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, dp(6f), ((height - fontSize) / 2f).coerceAtLeast(0f))
		ImGui.inputTextWithHint(id, hint, buffer, flags)
		val editing = ImGui.isItemActive()
		ImGui.popStyleVar()
		ImGui.popFont()
		ImGui.popStyleColor(7)

		draw.addRect(x, y, x + width, y + height, if (editing) ACCENT else DISABLED_BORDER, height / 2f, 0, dp(1f))
		if (editing) typing = true
		return editing
	}

	/** A small grey label in a rounded box, like the settings screen's key badges. */
	protected fun badge(draw: ImDrawList, text: String, x: Float, y: Float, lit: Boolean = false): Float {
		val fontSize = dp(8.5f)
		val height = dp(15f)
		val width = textWidth(text, fontSize) + dp(10f)
		draw.addRectFilled(x, y, x + width, y + height, if (lit) ACCENT_DARK else SURFACE_RAISED, dp(4f))
		centered(draw, text, x, y, width, height, if (lit) TEXT else MUTED_TEXT, fontSize)
		return width
	}

	protected fun dp(value: Float) = value * scale

	/** [from] to [to], channel by channel. Works the same on ABGR as on ARGB. */
	private fun blend(from: Int, to: Int, amount: Float): Int {
		val t = amount.coerceIn(0f, 1f)
		var out = 0
		for (shift in intArrayOf(0, 8, 16, 24)) {
			val a = (from ushr shift) and 0xFF
			val b = (to ushr shift) and 0xFF
			out = out or ((a + (b - a) * t).roundToInt().coerceIn(0, 255) shl shift)
		}
		return out
	}

	companion object {
		private const val REFERENCE_HEIGHT = 1080f
		private const val REFERENCE_SCALE = 1.5f
		const val PANEL_WIDTH = 420f
		private const val CONTENT_Y = 70f
		private const val CONTENT_BOTTOM_MARGIN = 12f
		const val CARD_GAP = 8f
		const val CHIP_HEIGHT = 18f
		const val SWITCH_WIDTH = 30f
		const val SWITCH_HEIGHT = 16f
		private const val SWITCH_SPEED = 18f
		private const val SCROLL_STEP = 34f

		// Dear ImGui packs colours as ABGR: the settings screen's palette.
		const val SURFACE = 0xFF141414.toInt()
		const val SURFACE_RAISED = 0xFF3F3F3F.toInt()
		const val SURFACE_ACTIVE = 0xFF464646.toInt()
		const val RENAME_FIELD = 0xFF1D1D1D.toInt()
		const val DISABLED_BORDER = 0xFF2B2B2B.toInt()
		const val TRACK = 0xFF2B2B2B.toInt()
		const val TEXT = 0xFFE4E4E4.toInt()
		const val MUTED_TEXT = 0xFFA4A4A4.toInt()
		const val DELETE_TEXT = 0xFF4F4ADF.toInt()
		private const val TOGGLE_OFF = 0xFF474747.toInt()
		private const val KNOB = 0xFF161616.toInt()
		private const val KNOB_ON = 0xFFFFFFFF.toInt()

		val ACCENT: Int get() = ClickGui.accentAbgr()
		val ACCENT_DARK: Int get() = ClickGui.accentDarkAbgr()
	}
}
