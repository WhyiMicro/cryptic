package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.mixin.ContainerScreenAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen

/**
 * Lets the wheel move, and resize, the tooltip of the item under the cursor.
 *
 * Ported from the scrollable tooltips in NoammAddons' Item Tooltip (CC0,
 * Noamm9). SkyBlock writes essays on its items, and a fully enchanted piece of
 * armour runs past the edge of the window at any GUI scale worth playing at.
 *
 * The version this replaces only woke up for a tooltip it had measured as
 * taller than the screen, and sat on its hands for every other one — so on a
 * tooltip that was merely awkward, or sitting under the cursor, or cut off at
 * the side, the wheel did nothing and the module read as broken. NoammAddons'
 * rule is the simple one and the right one: while the cursor is on an item, the
 * wheel belongs to its tooltip. Every tooltip moves, in both directions.
 *
 * - The wheel moves it up and down.
 * - Shift and the wheel move it sideways.
 * - Control and the wheel make it bigger or smaller.
 *
 * What is drawn is still vanilla's own tooltip, moved — nothing is re-laid-out
 * or re-wrapped, so an item with a picture in it looks exactly as it did.
 */
object ScrollableTooltips {
	@JvmField
	val speed = SliderModuleSetting(
		id = "speed",
		label = "Scroll speed",
		defaultValue = 10.0,
		min = 1.0,
		max = 40.0,
		step = 1.0,
		description = "How far one notch of the wheel moves the tooltip, in pixels.",
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Tooltip scale (%)",
		defaultValue = 100.0,
		min = 30.0,
		max = 150.0,
		step = 5.0,
		description = "The size every tooltip starts at. Hold control and scroll to change one as you read it.",
	)

	@JvmField
	val scaleSpeed = SliderModuleSetting(
		id = "scale_speed",
		label = "Scale speed",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 1.0,
		description = "How much one notch changes the size while control is held.",
	)

	@JvmField
	val module = Module(
		id = "scrollable_tooltips",
		name = "Scrollable Tooltips",
		description = "Scroll to move a tooltip, control-scroll to resize it",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(speed, scale, scaleSpeed),
	)

	/** How far the tooltip has been moved, in tooltip pixels. */
	private var offsetX = 0f
	private var offsetY = 0f

	/** How far control-scrolling has taken the size from [scale], as a multiplier added to it. */
	private var scaleOffset = 0f

	/** What was being pointed at when the offsets were last meaningful. */
	private var lastScreen: Screen? = null
	private var lastSlot = -1

	private const val MIN_SCALE = 0.3f
	private const val MAX_SCALE = 2.0f

	/**
	 * A turn of the wheel, taken before the screen underneath sees it.
	 *
	 * Taken whenever the cursor is on an item in a container, whatever the
	 * tooltip's size — NoammAddons' rule. With nothing under the cursor the
	 * wheel goes to the screen as it always did.
	 */
	@JvmStatic
	fun onScroll(verticalAmount: Double): Boolean {
		if (!module.enabled || verticalAmount == 0.0) return false
		val client = Minecraft.getInstance()
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
		// The creative inventory is a list that scrolls, and wants its wheel.
		if (screen is CreativeModeInventoryScreen) return false
		val slot = (screen as? ContainerScreenAccessor)?.`cryptic$hoveredSlot`() ?: return false
		if (!slot.hasItem()) return false
		// A terminal's items are hidden and so are their tooltips.
		if (TerminalSolver.hidesTooltip()) return false

		noteWhatIsHovered(screen, slot.index)

		val shift = client.hasShiftDown()
		val control = client.hasControlDown()
		val step = (verticalAmount * speed.value).toFloat()

		when {
			shift && !control -> offsetX -= step
			control && !shift -> {
				val base = scale.value.toFloat() / 100f
				val next = (base + scaleOffset + (verticalAmount * scaleSpeed.value / 100.0).toFloat())
					.coerceIn(MIN_SCALE, MAX_SCALE)
				scaleOffset = next - base
			}
			else -> offsetY += step
		}
		return true
	}

	/**
	 * Called before a tooltip is drawn, to move and size it.
	 *
	 * Scaled about the cursor, which is where the tooltip hangs from, so a
	 * tooltip made smaller shrinks towards the item it is describing rather than
	 * towards a corner of the screen. The push is unconditional so that the
	 * matching pop always has something to undo, whatever the settings say by
	 * the time the tooltip finishes.
	 */
	@JvmStatic
	fun beforeTooltip(graphics: GuiGraphicsExtractor, anchorX: Int, anchorY: Int) {
		val pose = graphics.pose()
		pose.pushMatrix()
		if (!module.enabled) return

		val screen = Minecraft.getInstance().gui.screen()
		val slot = (screen as? AbstractContainerScreen<*>)
			?.let { it as? ContainerScreenAccessor }
			?.`cryptic$hoveredSlot`()
			?.index
			?: -1
		noteWhatIsHovered(screen, slot)

		// Only an item's tooltip is moved. A button's hint in some other screen
		// has no business sitting where the last item's tooltip was left.
		if (slot < 0) return

		val size = (scale.value.toFloat() / 100f + scaleOffset).coerceIn(MIN_SCALE, MAX_SCALE)
		pose.translate(anchorX.toFloat(), anchorY.toFloat())
		pose.scale(size, size)
		pose.translate(offsetX, offsetY)
		pose.translate(-anchorX.toFloat(), -anchorY.toFloat())
	}

	@JvmStatic
	fun afterTooltip(graphics: GuiGraphicsExtractor) {
		graphics.pose().popMatrix()
	}

	/**
	 * Forgets the offsets when the thing under the cursor changes.
	 *
	 * A tooltip scrolled halfway down and then left that way is a tooltip that
	 * looks broken on the next item, so each one starts where the game put it.
	 * The slot is what identifies an item here: two different items can produce
	 * tooltips of exactly the same shape.
	 */
	private fun noteWhatIsHovered(screen: Screen?, slot: Int) {
		if (screen === lastScreen && slot == lastSlot) return
		lastScreen = screen
		lastSlot = slot
		offsetX = 0f
		offsetY = 0f
		scaleOffset = 0f
	}
}
