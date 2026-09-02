package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.VanillaGlyphs
import imicro.cryptic.terminal.TerminalClicks
import imicro.cryptic.terminal.TerminalHandler
import imicro.cryptic.terminal.TerminalType
import imicro.cryptic.terminal.Terminals
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * Draws the Floor 7 terminals as a grid of coloured squares, and clicks them.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking), whose
 * "Custom GUI" render type this is: the chest Hypixel opened is hidden and the
 * puzzle is drawn on its own, big and in the middle of the screen, so the
 * answer is the only thing on it. The solving itself lives in
 * [imicro.cryptic.terminal.TerminalHandler]; this is the part that draws and
 * takes clicks.
 *
 * Odin's other two render types, which colour the real chest in place, are
 * deliberately not here — this one replaces them rather than sitting alongside.
 */
object TerminalSolver {
	/** A slot's size before the size setting scales it, in GUI pixels. */
	private const val BASE_SLOT_SIZE = 24

	/** How far the background reaches past the outermost slots, unscaled. */
	private const val BASE_PADDING = 2

	private const val LABEL_COLOR = 0xFFFFFFFF.toInt()

	/** The index of "Manual terms" in [solvingMode], which has no delay to set. */
	private const val MANUAL_SOLVING = 0

	/** The index of "Que terms", the only mode with a queue worth showing. */
	private const val QUEUE_SOLVING = 1

	/** Minecraft's own chat colours, which Odin's defaults are built from. */
	private const val MINECRAFT_GREEN = 0x55FF55
	private const val MINECRAFT_DARK_RED = 0xAA0000
	private const val MINECRAFT_DARK_PURPLE = 0xAA00AA

	/** The same green and red at half and a quarter brightness, as Odin has them. */
	private const val GREEN_HALF = 0x2B802B
	private const val GREEN_QUARTER = 0x154015
	private const val DARK_RED_HALF = 0x550000

	// ---- Custom GUI ------------------------------------------------------

	@JvmField
	val termSize = SliderModuleSetting(
		id = "term_size",
		label = "Term size",
		defaultValue = 2.0,
		min = 1.0,
		max = 3.0,
		step = 0.1,
		description = "How large the terminal is drawn.",
	)

	@JvmField
	val slotGap = SliderModuleSetting(
		id = "slot_gap",
		label = "Slot gap",
		defaultValue = 2.0,
		min = 0.0,
		max = 8.0,
		step = 1.0,
		description = "The space left between neighbouring slots.",
	)

	@JvmField
	val showNumbers = ToggleModuleSetting(
		id = "show_numbers",
		label = "Show numbers",
		defaultValue = true,
		description = "Writes the click order on the order terminal, and the clicks left on rubix.",
	)

	@JvmField
	val resourcePackFont = ToggleModuleSetting(
		id = "resource_pack_font",
		label = "Resource pack font",
		defaultValue = false,
		description = "Writes the numbers in your resource pack's font. Off, they are in Minecraft's own, " +
			"read straight from the game rather than from whatever is installed over it.",
		visibleIf = { showNumbers.value },
	)

	// ---- First click protection ------------------------------------------

	private val protectionSection = SectionModuleSetting(
		id = "protection_section",
		label = "First click protection",
	)

	@JvmField
	val firstClickProtection = SliderModuleSetting(
		id = "first_click_protection",
		label = "Protection (ms)",
		defaultValue = 500.0,
		min = 350.0,
		max = 800.0,
		step = 10.0,
		description = "Clicks are dropped for this long after a terminal opens. Set it to 500 minus your ping.",
	)

	@JvmField
	val accountForServerLag = ToggleModuleSetting(
		id = "account_for_server_lag",
		label = "Account for server lag",
		defaultValue = false,
		description = "Also waits a number of server ticks, so a lagging server cannot let a click through early.",
	)

	@JvmField
	val lagProtectionTicks = SliderModuleSetting(
		id = "lag_protection_ticks",
		label = "Protection (ticks)",
		defaultValue = 8.0,
		min = 7.0,
		max = 16.0,
		step = 1.0,
		description = "Server ticks to wait as well. Each one is 50ms on a server that is keeping up.",
		visibleIf = { accountForServerLag.value },
	)

	// ---- Solver functionality --------------------------------------------

	private val functionalitySection = SectionModuleSetting(
		id = "functionality_section",
		label = "Solver functionality",
	)

	@JvmField
	val rubixLeftClick = ToggleModuleSetting(
		id = "rubix_left_click",
		label = "Rubix left click",
		defaultValue = false,
		description = "Lets the panes that need a right click be left clicked; the right click is sent for you.",
	)

	@JvmField
	val melodyClickProtection = ToggleModuleSetting(
		id = "melody_click_protection",
		label = "Melody click protection",
		defaultValue = false,
		description = "Only lets melody's button be pressed while the note is on the mark, so it cannot be failed.",
	)

	@JvmField
	val solvingMode = DropdownModuleSetting(
		id = "solving",
		label = "Solving",
		options = listOf("Manual terms", "Que terms", "Auto terms"),
		defaultIndex = 0,
		description = "Manual sends each click as you make it. " +
			"Que answers your clicks at once on screen and sends them one at a time, re-sending any the server " +
			"missed. " +
			"Auto plays the whole terminal without being asked.",
	)

	@JvmField
	val clickDelay = RangeModuleSetting(
		id = "click_delay",
		label = "Click delay (ms)",
		defaultLower = 100.0,
		defaultUpper = 150.0,
		min = 0.0,
		max = 300.0,
		step = 5.0,
		description = "The gap between clicks going out, drawn fresh from this range each time so it never " +
			"repeats. Your own clicks are answered on screen at once whatever this says; this only paces " +
			"what reaches the server. Too low and the terminal stops keeping up.",
		visibleIf = { solvingMode.selectedIndex != MANUAL_SOLVING },
	)

	// ---- Colors ----------------------------------------------------------

	private val colorSection = SectionModuleSetting(id = "color_section", label = "Colors")

	@JvmField
	val backgroundColor = ColorModuleSetting(
		id = "background_color",
		label = "Background",
		defaultRgb = 0x1A1A1A,
		supportsAlpha = true,
	)

	@JvmField
	val panesColor = ColorModuleSetting(
		id = "panes_color",
		label = "Panes",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
	)

	@JvmField
	val rubixColor1 = ColorModuleSetting(
		id = "rubix_color_1",
		label = "Rubix 1",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
	)

	@JvmField
	val rubixColor2 = ColorModuleSetting(
		id = "rubix_color_2",
		label = "Rubix 2",
		defaultRgb = GREEN_HALF,
		supportsAlpha = true,
	)

	@JvmField
	val rubixReverseColor1 = ColorModuleSetting(
		id = "rubix_reverse_color_1",
		label = "Rubix -1",
		defaultRgb = MINECRAFT_DARK_RED,
		supportsAlpha = true,
	)

	@JvmField
	val rubixReverseColor2 = ColorModuleSetting(
		id = "rubix_reverse_color_2",
		label = "Rubix -2",
		defaultRgb = DARK_RED_HALF,
		supportsAlpha = true,
	)

	@JvmField
	val orderColor1 = ColorModuleSetting(
		id = "order_color_1",
		label = "Order 1",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
	)

	@JvmField
	val orderColor2 = ColorModuleSetting(
		id = "order_color_2",
		label = "Order 2",
		defaultRgb = GREEN_HALF,
		supportsAlpha = true,
	)

	@JvmField
	val orderColor3 = ColorModuleSetting(
		id = "order_color_3",
		label = "Order 3",
		defaultRgb = GREEN_QUARTER,
		supportsAlpha = true,
	)

	@JvmField
	val startsWithColor = ColorModuleSetting(
		id = "starts_with_color",
		label = "Starts with",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
	)

	@JvmField
	val selectColor = ColorModuleSetting(
		id = "select_color",
		label = "Select",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
	)

	@JvmField
	val melodyColumnColor = ColorModuleSetting(
		id = "melody_column_color",
		label = "Melody column",
		defaultRgb = MINECRAFT_DARK_PURPLE,
		supportsAlpha = true,
	)

	@JvmField
	val melodyPointerColor = ColorModuleSetting(
		id = "melody_pointer_color",
		label = "Melody pointer",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
	)

	@JvmField
	val melodySlotColor = ColorModuleSetting(
		id = "melody_slot_color",
		label = "Melody slot",
		defaultRgb = 0x262626,
		supportsAlpha = true,
	)

	@JvmField
	val showQueue = ToggleModuleSetting(
		id = "show_queue",
		label = "Show queue depth",
		defaultValue = true,
		description = "Writes how many clicks are still waiting to go out under the terminal.",
		visibleIf = { solvingMode.selectedIndex == QUEUE_SOLVING },
	)

	@JvmField
	val queueTextColor = ColorModuleSetting(
		id = "queue_text_color",
		label = "Queue text",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		visibleIf = { solvingMode.selectedIndex == QUEUE_SOLVING && showQueue.value },
	)

	@JvmField
	val module = Module(
		id = "terminal_solver",
		name = "Terminal Solver",
		description = "Draws the answer to the Floor 7 terminals in a GUI of its own",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			termSize,
			slotGap,
			protectionSection,
			firstClickProtection,
			accountForServerLag,
			lagProtectionTicks,
			functionalitySection,
			showNumbers,
			resourcePackFont,
			rubixLeftClick,
			melodyClickProtection,
			solvingMode,
			clickDelay,
			colorSection,
			backgroundColor,
			panesColor,
			rubixColor1,
			rubixColor2,
			rubixReverseColor1,
			rubixReverseColor2,
			orderColor1,
			orderColor2,
			orderColor3,
			startsWithColor,
			selectColor,
			melodyColumnColor,
			melodyPointerColor,
			melodySlotColor,
			showQueue,
			queueTextColor,
		),
	)

	/**
	 * The slot the cursor was over when the terminal was last drawn.
	 *
	 * Keeping it from the render pass is what lets a key press click "the slot
	 * under the mouse" without working the layout out a second time.
	 */
	private var hoveredSlot: Int? = null

	private var queueLabelFor = -1
	private var queueLabelText = ""

	/**
	 * Cryptic's own copy of Minecraft's font, so a resource pack that replaces
	 * the game's cannot replace the terminal's numbers with something harder to
	 * read at a glance. It is vanilla's `default.json` under Cryptic's
	 * namespace, which is where a pack will not think to look.
	 */
	private val vanillaFont = Style.EMPTY.withFont(FontDescription.Resource(Cryptic.id("vanilla")))

	/** Where each slot of the open terminal is on screen, in GUI pixels. */
	private class Layout(
		val type: TerminalType,
		val originX: Int,
		val originY: Int,
		val slotSize: Int,
		gap: Int,
		val padding: Int,
	) {
		val step: Int = slotSize + gap

		val width: Int = type.columns * slotSize + (type.columns - 1) * gap
		val height: Int = type.rows * slotSize + (type.rows - 1) * gap

		inline fun forEachSlot(body: (slotIndex: Int, x: Int, y: Int) -> Unit) {
			for (row in 0 until type.rows) {
				for (column in 0 until type.columns) {
					body(
						(type.firstRow + row) * 9 + (type.firstColumn + column),
						originX + column * step,
						originY + row * step,
					)
				}
			}
		}

		/** The slot at a screen position, or null between or outside them. */
		fun slotAt(x: Double, y: Double): Int? {
			var found: Int? = null
			forEachSlot { slotIndex, slotX, slotY ->
				if (x >= slotX && x < slotX + slotSize && y >= slotY && y < slotY + slotSize) found = slotIndex
			}
			return found
		}
	}

	/** True while this module has taken a terminal over and is drawing it. */
	private fun isActive(screen: Screen): Boolean =
		module.enabled &&
			screen is AbstractContainerScreen<*> &&
			Terminals.current != null &&
			Minecraft.getInstance().screen === screen

	private fun layoutFor(type: TerminalType, screen: Screen): Layout {
		val scale = termSize.value
		val slotSize = (BASE_SLOT_SIZE * scale).roundToInt().coerceAtLeast(1)
		val gap = (slotGap.value * scale).roundToInt()

		return Layout(
			type = type,
			originX = (screen.width - (type.columns * slotSize + (type.columns - 1) * gap)) / 2,
			originY = (screen.height - (type.rows * slotSize + (type.rows - 1) * gap)) / 2,
			slotSize = slotSize,
			gap = gap,
			padding = (BASE_PADDING * scale).roundToInt(),
		)
	}

	/**
	 * Draws the terminal in place of everything the chest screen would draw.
	 *
	 * The whole screen is taken over rather than only its contents: the chest
	 * texture behind the slots is a picture of an inventory, and leaving it up
	 * puts a row of fake item slots under a terminal that has nothing to do
	 * with them. What replaces it is the same dimming any in-game screen lays
	 * over the world, so the terminal is the only thing on it.
	 *
	 * Sizes are worked out in whole GUI pixels rather than by scaling the
	 * matrix, so the grid lands on pixel boundaries at every size. Labels are
	 * the one thing drawn through a scaled matrix, since the font has no size
	 * of its own to grow.
	 *
	 * Returns true when it took over, so the caller can drop the vanilla screen.
	 */
	@JvmStatic
	fun renderCustomGui(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		if (!isActive(screen)) return false
		val terminal = Terminals.current ?: return false
		val layout = layoutFor(terminal.type, screen)
		val font = Minecraft.getInstance().font
		val scale = termSize.value.toFloat()

		// The same stratum the screen would have drawn its background into.
		context.nextStratum()
		context.fillGradient(0, 0, context.guiWidth(), context.guiHeight(), BACKDROP_TOP, BACKDROP_BOTTOM)
		context.nextStratum()

		context.fill(
			layout.originX - layout.padding,
			layout.originY - layout.padding,
			layout.originX + layout.width + layout.padding,
			layout.originY + layout.height + layout.padding,
			backgroundColor.argb,
		)

		hoveredSlot = null
		layout.forEachSlot { slotIndex, x, y ->
			if (mouseX >= x && mouseX < x + layout.slotSize && mouseY >= y && mouseY < y + layout.slotSize) {
				hoveredSlot = slotIndex
			}

			val overlay = terminal.overlay(slotIndex) ?: return@forEachSlot
			// A fully transparent slot still has a label worth drawing: the
			// order terminal numbers every pane but only paints the next few.
			if (overlay.argb ushr 24 != 0) {
				context.fill(x, y, x + layout.slotSize, y + layout.slotSize, overlay.argb)
			}

			val label = overlay.text ?: return@forEachSlot
			val pose = context.pose()
			pose.pushMatrix()
			pose.translate(x + layout.slotSize / 2f, y + layout.slotSize / 2f)
			pose.scale(scale, scale)
			drawLabel(context, font, label, LABEL_COLOR)
			pose.popMatrix()
		}

		if (solvingMode.selectedIndex == QUEUE_SOLVING && showQueue.value) {
			val pose = context.pose()
			pose.pushMatrix()
			pose.translate(
				layout.originX + layout.width / 2f,
				(layout.originY + layout.height + layout.padding).toFloat() + QUEUE_TEXT_GAP * scale,
			)
			pose.scale(scale, scale)
			drawLabel(context, font, queueLabel(TerminalClicks.queued), queueTextColor.argb, fromTop = true)
			pose.popMatrix()
		}

		return true
	}

	/**
	 * Draws [text] centred on the origin left to right, in Minecraft's own
	 * letters where they could be read and in the installed font where they
	 * could not. [fromTop] hangs it below the origin rather than centring it on
	 * it, so a caller placing text under something can say where the top goes.
	 */
	private fun drawLabel(
		context: GuiGraphicsExtractor,
		font: Font,
		text: String,
		argb: Int,
		fromTop: Boolean = false,
	) {
		if (!resourcePackFont.value && VanillaGlyphs.isReady()) {
			val top = if (fromTop) 0 else -VanillaGlyphs.lineHeight / 2
			VanillaGlyphs.draw(context, text, -VanillaGlyphs.width(text) / 2, top, argb)
			return
		}

		// The declared font is Cryptic's own copy of Minecraft's, which most
		// packs cannot reach; asking for the pack's font means asking for
		// whatever the game would ordinarily draw with.
		val component = Component.literal(text)
		if (!resourcePackFont.value) component.setStyle(vanillaFont)
		context.centeredText(font, component, 0, if (fromTop) 0 else -font.lineHeight / 2, argb)
	}

	/**
	 * "Queue: 3", built only when the number changes. The queue line is redrawn
	 * every frame but the depth moves a handful of times a terminal.
	 */
	private fun queueLabel(depth: Int): String {
		if (depth != queueLabelFor) {
			queueLabelFor = depth
			queueLabelText = "Queue: $depth"
		}
		return queueLabelText
	}

	/** Returns true when the click was ours, and the chest should not see it. */
	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, button: Int): Boolean {
		if (!isActive(screen)) return false
		val terminal = Terminals.current ?: return false

		val slot = layoutFor(terminal.type, screen).slotAt(mouseX, mouseY)
		hoveredSlot = slot
		if (slot != null) click(terminal, slot, button)

		// Clicks that missed every slot are swallowed too: the chest underneath
		// is not on screen, so letting one through would click a slot blind.
		return true
	}

	/**
	 * Clicks the slot under the cursor when a key bound to dropping or to a
	 * hotbar number is pressed, which is what makes the terminal quick to play
	 * without moving the mouse off a slot. Holding control right-clicks.
	 */
	@JvmStatic
	fun handleKeyPress(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
		if (!isActive(screen)) return false
		val options = Minecraft.getInstance().options
		if (!options.keyDrop.matches(event) && options.keyHotbarSlots.none { it.matches(event) }) return false

		val terminal = Terminals.current ?: return false
		hoveredSlot?.let {
			click(terminal, it, if (event.hasControlDown()) GLFW.GLFW_MOUSE_BUTTON_RIGHT else GLFW.GLFW_MOUSE_BUTTON_LEFT)
		}
		return true
	}

	/**
	 * A left click is sent as a middle click, which is what Hypixel's terminals
	 * expect; a right click stays one, because rubix reads the button. What
	 * happens to the click after that — straight out, into the queue, or
	 * nothing at all because the mod is playing — is [TerminalClicks]' to say.
	 */
	private fun click(terminal: TerminalHandler, slotIndex: Int, button: Int) {
		val sent = if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) GLFW.GLFW_MOUSE_BUTTON_MIDDLE else button
		TerminalClicks.onPlayerClick(terminal, slotIndex, sent)
	}

	/** Vanilla's own in-game screen dimming, so nothing about it looks new. */
	private const val BACKDROP_TOP = 0xC0101010.toInt()
	private const val BACKDROP_BOTTOM = 0xD0101010.toInt()

	/** How far under the box the queue line sits, before the size setting. */
	private const val QUEUE_TEXT_GAP = 3f
}
