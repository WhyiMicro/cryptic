package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.RoundedRect
import imicro.cryptic.render.VanillaGlyphs
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
import net.minecraft.world.inventory.ContainerInput
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * Draws the answer to the Floor 7 terminals, and takes their clicks.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking), and of its
 * three ways of drawing a terminal only one is left: the custom GUI, which
 * throws the chest away and draws the puzzle on its own, big and in the middle
 * of the screen with rounded slots. The two that painted over the chest's own
 * slots were taken out — nobody here used them — and with them the business of
 * asking the game for a different GUI scale while a terminal was open.
 */
object TerminalSolver {
	/** A slot's size in the custom GUI before the size setting scales it. */
	private const val BASE_SLOT_SIZE = 24

	/** How far the custom GUI's background reaches past the outermost slots. */
	private const val BASE_PADDING = 2

	/** How deep a rounded corner cuts into the square it replaces, as a share of its radius. */
	private const val CORNER_BITE = 0.3

	/** Minecraft's own chat colours, which Odin's defaults are built from. */
	private const val MINECRAFT_GREEN = 0x55FF55
	private const val MINECRAFT_DARK_RED = 0xAA0000
	private const val MINECRAFT_DARK_PURPLE = 0xAA00AA

	/** The same green and red at half and a quarter brightness, as Odin has them. */
	private const val GREEN_HALF = 0x2B802B
	private const val GREEN_QUARTER = 0x154015
	private const val DARK_RED_HALF = 0x550000

	/** The three rubix modes, in the order the dropdown offers them. */
	private const val RUBIX_LEFT_ONLY = 1
	private const val RUBIX_ONE_BUTTON = 2

	// ---- Rendering -------------------------------------------------------

	private val renderSection = SectionModuleSetting(id = "render_section", label = "Rendering")

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
	val roundness = SliderModuleSetting(
		id = "roundness",
		label = "Slot roundness",
		defaultValue = 5.0,
		min = 0.0,
		max = 15.0,
		step = 0.5,
		description = "How far the corners of the custom terminal's slots are rounded off.",
	)

	@JvmField
	val containerRoundness = SliderModuleSetting(
		id = "container_roundness",
		label = "Container roundness",
		defaultValue = 5.0,
		min = 0.0,
		max = 20.0,
		step = 0.5,
		description = "How far the corners of the box the slots sit in are rounded off.",
	)

	@JvmField
	val slotGap = SliderModuleSetting(
		id = "slot_gap",
		label = "Slot gap",
		defaultValue = 2.0,
		min = 0.0,
		max = 8.0,
		step = 1.0,
		description = "The space left between neighbouring slots in the custom terminal.",
	)

	@JvmField
	val resourcePackFont = ToggleModuleSetting(
		id = "resource_pack_font",
		label = "Resource pack font",
		defaultValue = false,
		description = "Writes the numbers in your resource pack's font.",
	)

	// ---- Solver functionality --------------------------------------------

	private val functionalitySection = SectionModuleSetting(
		id = "functionality_section",
		label = "Solver functionality",
	)

	@JvmField
	val clickPrediction = ToggleModuleSetting(
		id = "click_prediction",
		label = "Client prediction",
		defaultValue = true,
		description = "Shows a click as taken the moment it is sent, rather than when the server answers.",
	)

	@JvmField
	val resolveTimeout = SliderModuleSetting(
		id = "resolve_timeout",
		label = "Resolve timeout (ms)",
		defaultValue = 600.0,
		min = 300.0,
		max = 1200.0,
		step = 10.0,
		description = "How long a predicted click is believed.",
		visibleIf = { clickPrediction.value },
	)

	@JvmField
	val stopMelodySolver = ToggleModuleSetting(
		id = "stop_melody_solver",
		label = "Stop melody solver",
		defaultValue = false,
		description = "Leaves melody alone and plays it as the chest.",
	)

	@JvmField
	val hideNumbers = ToggleModuleSetting(
		id = "hide_numbers",
		label = "Hide numbers",
		defaultValue = false,
		description = "Leaves the Numbers terminal's panes unlabelled, for playing it off the three colours alone.",
	)

	@JvmField
	val melodyTermSize = SliderModuleSetting(
		id = "melody_term_size",
		label = "Melody size",
		defaultValue = 1.5,
		min = 1.0,
		max = 3.0,
		step = 0.1,
		description = "How large melody's own grid is drawn, which is wider than the rest.",
		visibleIf = { !stopMelodySolver.value },
	)

	@JvmField
	val rubixMode = DropdownModuleSetting(
		id = "rubix_mode",
		label = "Rubix mode",
		options = listOf("Fewest clicks", "Left clicks only", "One button"),
		defaultIndex = 0,
		description = "Fewest clicks wants right clicks; one button sends them for you.",
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
		defaultValue = 200.0,
		min = 100.0,
		max = 800.0,
		step = 10.0,
		description = "Clicks dropped for this long. Use 500 minus your ping.",
	)

	@JvmField
	val accountForServerLag = ToggleModuleSetting(
		id = "account_for_server_lag",
		label = "Account for server lag",
		defaultValue = false,
		description = "Waits server ticks too, for a lagging server.",
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

	// ---- Colors ----------------------------------------------------------

	private val colorSection = SectionModuleSetting(id = "color_section", label = "Colors", startsCollapsed = true)

	@JvmField
	val backgroundColor = ColorModuleSetting(
		id = "background_color",
		label = "Background",
		defaultRgb = 0x1A1A1A,
		supportsAlpha = true,
	)

	@JvmField
	val textColor = ColorModuleSetting(
		id = "text_color",
		label = "Text",
		defaultRgb = 0xFFFFFF,
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
		visibleIf = { !stopMelodySolver.value },
	)

	@JvmField
	val melodyPointerColor = ColorModuleSetting(
		id = "melody_pointer_color",
		label = "Melody pointer",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
		visibleIf = { !stopMelodySolver.value },
	)

	@JvmField
	val melodySlotColor = ColorModuleSetting(
		id = "melody_slot_color",
		label = "Melody background",
		defaultRgb = 0x262626,
		supportsAlpha = true,
		visibleIf = { !stopMelodySolver.value },
	)

	@JvmField
	val module = Module(
		id = "terminal_solver",
		name = "Terminal Solver",
		description = "Solves the Floor 7 terminals",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			renderSection,
			termSize,
			roundness,
			containerRoundness,
			slotGap,
			melodyTermSize,
			resourcePackFont,
			functionalitySection,
			clickPrediction,
			resolveTimeout,
			stopMelodySolver,
			rubixMode,
			hideNumbers,
			protectionSection,
			firstClickProtection,
			accountForServerLag,
			lagProtectionTicks,
			colorSection,
			backgroundColor,
			textColor,
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
		) + MelodyHud.settings,
	)

	/** True when rubix should never ask for a right click at all. */
	val leftClicksOnly: Boolean get() = rubixMode.selectedIndex == RUBIX_LEFT_ONLY

	/** True when rubix sends whichever button the answer wants, not the one pressed. */
	val rubixOneButton: Boolean get() = rubixMode.selectedIndex == RUBIX_ONE_BUTTON

	/**
	 * The slot the cursor was over when the custom GUI was last drawn.
	 *
	 * Keeping it from the render pass is what lets a key press click "the slot
	 * under the mouse" without working the layout out a second time.
	 */
	private var hoveredSlot: Int? = null

	/**
	 * Cryptic's own copy of Minecraft's font, so a resource pack that replaces
	 * the game's cannot replace the terminal's numbers with something harder to
	 * read at a glance. It is vanilla's `default.json` under Cryptic's
	 * namespace, which is where a pack will not think to look.
	 */
	private val vanillaFont = Style.EMPTY.withFont(FontDescription.Resource(Cryptic.id("vanilla")))

	/**
	 * The terminal open on screen right now, or null when there is none.
	 *
	 * The screen is checked, not just the tracker: a terminal that is no longer
	 * on screen must never reach the menu that replaced it, because the solver
	 * hides items and swallows clicks by slot number and the player's own
	 * inventory numbers its slots the same way.
	 */
	private fun tracked(): TerminalHandler? {
		if (!module.enabled) return null
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return null
		if (!Terminals.screenIsCurrent(screen)) return null
		return Terminals.current
	}

	/** The terminal being *drawn* right now, which melody can be excused from. */
	private fun active(): TerminalHandler? {
		val handler = tracked() ?: return null
		if (stopMelodySolver.value && handler.type == TerminalType.MELODY) return null
		return handler
	}

	/** True while the custom GUI has taken the screen over. */
	private fun ownsScreen(screen: Screen): Boolean =
		active() != null &&
			screen is AbstractContainerScreen<*> &&
			Minecraft.getInstance().gui.screen() === screen

	/** Tooltips are the chest talking about items nobody can see. */
	@JvmStatic
	fun hidesTooltip(): Boolean = active() != null

	/**
	 * A click on one of the chest's slots.
	 *
	 * Every click in a terminal goes through the handler, whichever way it is
	 * being drawn: the handler is what holds the first-click protection, and a
	 * click that went straight to the chest would walk around it.
	 *
	 * Vanilla sends hotbar keys and the drop key through here too, with the
	 * hotbar number in place of a mouse button, so anything that is not a
	 * plain pickup is read as a left click.
	 */
	@JvmStatic
	fun handleSlotClick(slotId: Int, button: Int, input: ContainerInput): Boolean {
		// Melody is asked even when it is not being drawn: the ban risk is in
		// the clicking, not in the painting, so first click protection holds
		// whatever the render settings say.
		val handler = tracked() ?: return false
		val pressed = if (input == ContainerInput.PICKUP) button else GLFW.GLFW_MOUSE_BUTTON_LEFT
		handler.click(slotId, pressed)
		return true
	}

	// ---- The custom GUI --------------------------------------------------

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

	private fun scaleFor(type: TerminalType): Double =
		if (type == TerminalType.MELODY) melodyTermSize.value else termSize.value

	/**
	 * How far the background reaches past the outermost slots.
	 *
	 * A rounded corner takes a bite out of the box's own corner — at its
	 * deepest, the diagonal, about three tenths of the radius — so the padding
	 * grows with the rounding. Without that, turning the container's corners up
	 * while leaving the slots square clipped the corner slot.
	 */
	private fun paddingFor(scale: Double): Int =
		(BASE_PADDING * scale).roundToInt() + (containerRoundness.value * CORNER_BITE * scale).roundToInt()

	private fun layoutFor(type: TerminalType, screen: Screen): Layout {
		val scale = scaleFor(type)
		val slotSize = (BASE_SLOT_SIZE * scale).roundToInt().coerceAtLeast(1)
		val gap = (slotGap.value * scale).roundToInt()

		return Layout(
			type = type,
			originX = (screen.width - (type.columns * slotSize + (type.columns - 1) * gap)) / 2,
			originY = (screen.height - (type.rows * slotSize + (type.rows - 1) * gap)) / 2,
			slotSize = slotSize,
			gap = gap,
			padding = paddingFor(scale),
		)
	}

	/**
	 * Draws the terminal in place of everything the chest screen would draw.
	 *
	 * The whole screen has to go rather than only its contents: the chest
	 * texture behind the slots is a picture of an inventory, and leaving it up
	 * puts a row of fake item slots under a terminal that has nothing to do
	 * with them. What replaces it is the same dimming any in-game screen lays
	 * over the world, so the terminal is the only thing on it.
	 *
	 * Returns true when it took over, so the caller can drop the vanilla screen.
	 */
	@JvmStatic
	fun renderCustomGui(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		if (!ownsScreen(screen)) return false
		val terminal = active() ?: return false
		val layout = layoutFor(terminal.type, screen)
		val font = Minecraft.getInstance().font
		val scale = scaleFor(terminal.type)
		val slotRadius = (roundness.value * scale).toFloat()
		val boxRadius = (containerRoundness.value * scale).toFloat()

		// The same stratum the screen would have drawn its background into.
		context.nextStratum()
		context.fillGradient(0, 0, context.guiWidth(), context.guiHeight(), BACKDROP_TOP, BACKDROP_BOTTOM)
		context.nextStratum()

		RoundedRect.fill(
			context,
			layout.originX - layout.padding,
			layout.originY - layout.padding,
			layout.originX + layout.width + layout.padding,
			layout.originY + layout.height + layout.padding,
			backgroundColor.argb,
			boxRadius,
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
				RoundedRect.fill(context, x, y, x + layout.slotSize, y + layout.slotSize, overlay.argb, slotRadius)
			}

			overlay.text?.let { label ->
				val pose = context.pose()
				pose.pushMatrix()
				pose.translate(x + layout.slotSize / 2f, y + layout.slotSize / 2f)
				pose.scale(scaleFor(terminal.type).toFloat(), scaleFor(terminal.type).toFloat())
				drawLabel(context, font, label, textColor.argb, 0, 0)
				pose.popMatrix()
			}
		}

		return true
	}

	/**
	 * Draws [text] centred on ([centerX], [centerY]), in Minecraft's own
	 * letters where they could be read and in the installed font where they
	 * could not.
	 */
	private fun drawLabel(
		context: GuiGraphicsExtractor,
		font: Font,
		text: String,
		argb: Int,
		centerX: Int,
		centerY: Int,
	) {
		if (!resourcePackFont.value && VanillaGlyphs.isReady()) {
			VanillaGlyphs.draw(
				context,
				text,
				centerX - VanillaGlyphs.width(text) / 2,
				centerY - VanillaGlyphs.lineHeight / 2,
				argb,
			)
			return
		}

		// The declared font is Cryptic's own copy of Minecraft's, which most
		// packs cannot reach; asking for the pack's font means asking for
		// whatever the game would ordinarily draw with.
		val component = Component.literal(text)
		if (!resourcePackFont.value) component.setStyle(vanillaFont)
		context.centeredText(font, component, centerX, centerY - font.lineHeight / 2, argb)
	}

	/** Returns true when the click was ours, and the chest should not see it. */
	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, button: Int): Boolean {
		if (!ownsScreen(screen)) return false
		val terminal = active() ?: return false

		val slot = layoutFor(terminal.type, screen).slotAt(mouseX, mouseY)
		hoveredSlot = slot
		if (slot != null) terminal.click(slot, button)

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
		if (!ownsScreen(screen)) return false
		val options = Minecraft.getInstance().options
		if (!options.keyDrop.matches(event) && options.keyHotbarSlots.none { it.matches(event) }) return false

		val terminal = active() ?: return false
		hoveredSlot?.let {
			terminal.click(
				it,
				if (event.hasControlDown()) GLFW.GLFW_MOUSE_BUTTON_RIGHT else GLFW.GLFW_MOUSE_BUTTON_LEFT,
			)
		}
		return true
	}

	/** Vanilla's own in-game screen dimming, so nothing about it looks new. */
	private const val BACKDROP_TOP = 0xC0101010.toInt()
	private const val BACKDROP_BOTTOM = 0xD0101010.toInt()
}
