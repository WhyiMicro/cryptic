package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.PanelModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
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
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.inventory.ContainerInput
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * Draws the answer to the Floor 7 terminals and handles their clicks, ported from Odin. Only Odin's
 * custom GUI is kept: the chest is replaced by the puzzle alone, large and centred, with rounded
 * slots.
 */
object TerminalSolver {
	private const val BASE_SLOT_SIZE = 24

	private const val BASE_PADDING = 2

	/** How deep a rounded corner cuts into its square, as a share of the radius. */
	private const val CORNER_BITE = 0.3

	/** Minecraft's chat colours, which Odin's defaults use. */
	private const val MINECRAFT_GREEN = 0x55FF55
	private const val MINECRAFT_DARK_RED = 0xAA0000
	private const val MINECRAFT_DARK_PURPLE = 0xAA00AA

	/** The same green and red at half and quarter brightness, as Odin has them. */
	private const val GREEN_HALF = 0x2B802B
	private const val GREEN_QUARTER = 0x154015
	private const val DARK_RED_HALF = 0x550000

	/** The three rubix modes, in dropdown order. */
	private const val RUBIX_LEFT_ONLY = 1
	private const val RUBIX_ONE_BUTTON = 2

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
		label = "Resolve timeout",
		unit = "ms",
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
		options = listOf("Normal", "No minus", "One button"),
		defaultIndex = 0,
		description = "Normal takes the fewest clicks, right clicks included; No minus only left clicks; One button sends them for you.",
		optionRestrictions = mapOf("No minus" to imicro.cryptic.gui.Restriction.SEMI_LEGIT),
		renamed = mapOf("Fewest clicks" to "Normal", "Left clicks only" to "No minus"),
	)

	private val protectionSection = SectionModuleSetting(
		id = "protection_section",
		label = "First click protection",
	)

	@JvmField
	val firstClickProtection = SliderModuleSetting(
		id = "first_click_protection",
		label = "Protection",
		unit = "ms",
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
		label = "Protection",
		unit = "ticks",
		defaultValue = 8.0,
		min = 7.0,
		max = 16.0,
		step = 1.0,
		description = "Server ticks to wait as well. Each one is 50ms on a server that is keeping up.",
		visibleIf = { accountForServerLag.value },
	)

	// ---- Sound -----------------------------------------------------------
	//
	// Odin's Terminal Sounds and NoammAddons' click sound: Hypixel answers every terminal click
	// with a note block pling (volume 8, pitch 4.047619, a pair nothing else uses). While a
	// terminal is open that sound is dropped, and the chosen one plays on the click instead,
	// without waiting a round trip.

	private val soundSection = SectionModuleSetting(id = "sound_section", label = "Sound", startsCollapsed = true)

	@JvmField
	val clickSound = ToggleModuleSetting(
		id = "click_sound",
		label = "Custom click sound",
		defaultValue = false,
		description = "Plays the sound chosen below on every terminal click, and drops Hypixel's own click sound.",
	)

	@JvmField
	val clickSoundChoice = DropdownModuleSetting(
		id = "click_sound_choice",
		label = "Sound",
		options = SoundChoices.labels,
		defaultIndex = SoundChoices.indexOf("Button Click"),
		visibleIf = { clickSound.value },
	)

	private val customClickSound = SoundChoices.customField("custom_click_sound", clickSoundChoice) { clickSound.value }

	@JvmField
	val clickSoundVolume = SliderModuleSetting(
		id = "click_sound_volume",
		label = "Volume",
		defaultValue = 0.5,
		min = 0.0,
		max = 1.0,
		step = 0.05,
		visibleIf = { clickSound.value },
	)

	@JvmField
	val clickSoundPitch = SliderModuleSetting(
		id = "click_sound_pitch",
		label = "Pitch",
		defaultValue = 1.0,
		min = 0.5,
		max = 2.0,
		step = 0.05,
		visibleIf = { clickSound.value },
	)

	private val previewClickSound = ButtonModuleSetting(
		id = "preview_click_sound",
		label = "Play sound",
		action = { playClickSound() },
		visibleIf = { clickSound.value },
	)

	val replacesClickSound: Boolean get() = module.enabled && clickSound.value

	fun playClickSound() {
		SoundChoices.play(clickSoundChoice.selectedIndex, clickSoundVolume.value.toFloat(), clickSoundPitch.value.toFloat(), customClickSound.value)
	}

	/**
	 * Whether a server sound is Hypixel's terminal click, to be dropped. May run on the network
	 * thread, so it only reads.
	 */
	@JvmStatic
	fun eatsServerSound(packet: ClientboundSoundPacket): Boolean {
		if (!replacesClickSound || Terminals.current == null) return false
		if (packet.sound.value() != SoundEvents.NOTE_BLOCK_PLING.value()) return false
		return packet.volume == HYPIXEL_CLICK_VOLUME && kotlin.math.abs(packet.pitch - HYPIXEL_CLICK_PITCH) < 0.001f
	}

	/**
	 * The gate falling and the core opening ring the same pling, so one dropped while a terminal
	 * was open is replayed, as Odin does.
	 */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !replacesClickSound || Terminals.current == null) return
		val text = message.string.replace(FORMATTING, "").trim()
		if (text != GATE_DESTROYED && text != CORE_OPENING) return
		Minecraft.getInstance().soundManager.play(
			net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 2f, 1f),
		)
	}

	private const val HYPIXEL_CLICK_VOLUME = 8f
	private const val HYPIXEL_CLICK_PITCH = 4.047619f
	private const val GATE_DESTROYED = "The gate has been destroyed!"
	private const val CORE_OPENING = "The Core entrance is opening!"
	private val FORMATTING = Regex("§.")

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
		supportsKeybind = false,
		settings = listOf(
			renderSection,
			termSize,
			melodyTermSize,
			resourcePackFont,
			PanelModuleSetting("shape_panel", "Shape", "How round the slots and the window are, and the gap between slots.")
				.withMore(roundness, containerRoundness, slotGap),
			PanelModuleSetting("color_panel", "Colors", "Every colour the terminal window is drawn in.").withMore(
				backgroundColor, textColor, panesColor,
				rubixColor1, rubixColor2, rubixReverseColor1, rubixReverseColor2,
				orderColor1, orderColor2, orderColor3,
				startsWithColor, selectColor,
				melodyColumnColor, melodyPointerColor, melodySlotColor,
			),
			functionalitySection,
			clickPrediction.withMore(resolveTimeout),
			stopMelodySolver,
			rubixMode,
			hideNumbers,
			protectionSection,
			firstClickProtection,
			accountForServerLag.withMore(lagProtectionTicks),
			soundSection,
			clickSound.withMore(clickSoundChoice, customClickSound, clickSoundVolume, clickSoundPitch, previewClickSound),
		) + MelodyHud.settings,
	)

	val leftClicksOnly: Boolean get() = rubixMode.selectedIndex == RUBIX_LEFT_ONLY

	/** True when rubix sends whichever button the answer wants. */
	val rubixOneButton: Boolean get() = rubixMode.selectedIndex == RUBIX_ONE_BUTTON

	/**
	 * The slot under the cursor when the GUI was last drawn, so keys can click it without redoing
	 * the layout.
	 */
	private var hoveredSlot: Int? = null

	/**
	 * Cryptic's own copy of Minecraft's font (vanilla's `default.json` under Cryptic's namespace),
	 * so resource packs can't make the terminal numbers harder to read.
	 */
	private val vanillaFont = Style.EMPTY.withFont(FontDescription.Resource(Cryptic.id("vanilla")))

	/**
	 * The terminal open on screen, or null. Checks the screen too, so a stale terminal never
	 * reaches the next menu (the inventory numbers its slots the same way, and the solver hides
	 * items and swallows clicks by slot).
	 */
	private fun tracked(): TerminalHandler? {
		if (!module.enabled) return null
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return null
		if (!Terminals.screenIsCurrent(screen)) return null
		return Terminals.current
	}

	/** The terminal being drawn right now (melody can be excused from drawing). */
	private fun active(): TerminalHandler? {
		val handler = tracked() ?: return null
		if (stopMelodySolver.value && handler.type == TerminalType.MELODY) return null
		return handler
	}

	private fun ownsScreen(screen: Screen): Boolean =
		active() != null &&
			screen is AbstractContainerScreen<*> &&
			Minecraft.getInstance().gui.screen() === screen

	@JvmStatic
	fun hidesTooltip(): Boolean = active() != null

	/**
	 * A click on a chest slot. Every terminal click goes through the handler, which holds the
	 * first-click protection. Hotbar and drop keys come through here too and are read as left
	 * clicks.
	 */
	@JvmStatic
	fun handleSlotClick(slotId: Int, button: Int, input: ContainerInput): Boolean {
		// Melody is asked even when not drawn: the ban risk is in clicking, so protection always
		// holds.
		val handler = tracked() ?: return false
		val pressed = if (input == ContainerInput.PICKUP) button else GLFW.GLFW_MOUSE_BUTTON_LEFT
		handler.click(slotId, pressed)
		return true
	}

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
	 * How far the background reaches past the outer slots; grows with the corner radius (a corner
	 * bites about three tenths of the radius), or round corners clip the corner slot.
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
	 * Draws the terminal instead of the chest screen. The whole screen goes, since the chest
	 * texture is a picture of an inventory. The usual in-game dimming replaces it. Returns true
	 * when it took over.
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

		// The stratum the screen's background would have used.
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
			// A transparent slot still gets its label: the numbers terminal labels every pane but
			// paints only the next few.
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
	 * Draws [text] centred on ([centerX], [centerY]), in Minecraft's letters where readable,
	 * otherwise the installed font.
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

		// The declared font is Cryptic's copy of Minecraft's, which most packs can't reach.
		val component = Component.literal(text)
		if (!resourcePackFont.value) component.setStyle(vanillaFont)
		context.centeredText(font, component, centerX, centerY - font.lineHeight / 2, argb)
	}

	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, button: Int): Boolean {
		if (!ownsScreen(screen)) return false
		val terminal = active() ?: return false

		val slot = layoutFor(terminal.type, screen).slotAt(mouseX, mouseY)
		hoveredSlot = slot
		if (slot != null) terminal.click(slot, button)

		// Clicks between slots are swallowed too; the chest isn't on screen, so a click through
		// would be blind.
		return true
	}

	/**
	 * A drop or hotbar key clicks the slot under the cursor, so terminals can be played without
	 * moving off a slot. Control right-clicks.
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

	/** Vanilla's in-game screen dimming. */
	private const val BACKDROP_TOP = 0xC0101010.toInt()
	private const val BACKDROP_BOTTOM = 0xD0101010.toInt()
}
