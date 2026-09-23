package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.mixin.ContainerScreenAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner

/**
 * Lets the wheel move a tooltip that is taller than the screen.
 *
 * SkyBlock writes essays on its items — a fully enchanted armour piece runs
 * well past the top and bottom of a 1080p window, and the lines that fall off
 * are the ones nobody can read. Vanilla has no answer to this because vanilla
 * has no items like it: `DefaultTooltipPositioner` only ever pushes a tooltip
 * *up* to fit, and once it is taller than the screen there is nowhere left to
 * push it.
 *
 * The idea is the one every mod called some variation of "scrollable tooltips"
 * has; none of their code is here. What is drawn is vanilla's own tooltip,
 * translated — nothing is re-laid-out, re-measured or re-wrapped, so an item
 * with a picture in it or a custom style still looks exactly as it did.
 *
 * The offset resets on its own when you point at something else, because a
 * tooltip scrolled halfway down and then left that way is a tooltip that looks
 * broken on the next item.
 */
object ScrollableTooltips {
	@JvmField
	val speed = SliderModuleSetting(
		id = "speed",
		label = "Speed",
		defaultValue = 10.0,
		min = 1.0,
		max = 40.0,
		step = 1.0,
		description = "How far one notch of the wheel moves the tooltip, in pixels.",
	)

	@JvmField
	val horizontal = ToggleModuleSetting(
		id = "horizontal",
		label = "Sideways with Shift",
		defaultValue = true,
		description = "Hold shift to scroll sideways.",
	)

	@JvmField
	val resetOnChange = ToggleModuleSetting(
		id = "reset_on_change",
		label = "Reset on a new item",
		defaultValue = true,
		description = "Puts the tooltip back where it belongs when you point at something else.",
	)

	@JvmField
	val blockScroll = ToggleModuleSetting(
		id = "block_scroll",
		label = "Keep it off the screen",
		defaultValue = false,
		description = "Keeps the wheel off the menu underneath.",
	)

	private val configurable = listOf(speed, horizontal, resetOnChange, blockScroll)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurable.forEach {
			when (it) {
				is SliderModuleSetting -> it.reset()
				is ToggleModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "scrollable_tooltips",
		name = "Scrollable Tooltips",
		description = "Scrolls a tooltip too tall to fit",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurable + reset,
	)

	private var offsetX = 0f
	private var offsetY = 0f

	/** What was being pointed at when the offset was last meaningful. */
	private var lastScreen: Screen? = null
	private var lastSlot = -1

	/** True while a tooltip was drawn on the last frame, so scrolling means this. */
	private var tooltipShown = false

	/**
	 * A turn of the wheel. Returns true when the screen underneath should not
	 * also see it.
	 */
	@JvmStatic
	fun onScroll(horizontalAmount: Double, verticalAmount: Double): Boolean {
		if (!module.enabled || !tooltipShown) return false
		val client = Minecraft.getInstance()
		if (client.gui.screen() == null) return false

		// Asked of the keyboard, not the key mapping. Vanilla only updates its
		// mappings while no screen is open, so `keyShift.isDown` reads false in
		// every inventory there is — which is why this never went sideways.
		val sideways = horizontal.value && client.hasShiftDown()

		when {
			// Some mice and drivers send a shifted wheel as a horizontal one,
			// which is the same request arriving on the other axis.
			verticalAmount == 0.0 && horizontalAmount != 0.0 ->
				if (horizontal.value) offsetX += (horizontalAmount * speed.value).toFloat() else return false
			verticalAmount == 0.0 -> return false
			sideways -> offsetX += (verticalAmount * speed.value).toFloat()
			else -> offsetY += (verticalAmount * speed.value).toFloat()
		}
		return blockScroll.value
	}

	/**
	 * Called before a tooltip is drawn, to move it.
	 *
	 * The offset is clamped here rather than when the wheel turns, because this
	 * is the only place the tooltip's real size and position are known. The rule
	 * is that a tooltip may be moved exactly far enough to show its hidden part
	 * and no further: its top can come down to the top of the screen, its bottom
	 * up to the bottom, and one that already fits cannot be moved at all.
	 *
	 * The push is unconditional so that the matching pop always has something to
	 * undo, whatever the settings say by the time the tooltip finishes.
	 */
	@JvmStatic
	fun beforeTooltip(
		graphics: GuiGraphicsExtractor,
		font: Font,
		lines: List<ClientTooltipComponent>,
		anchorX: Int,
		anchorY: Int,
		positioner: ClientTooltipPositioner,
	) {
		tooltipShown = true
		noteWhatIsHovered()

		val pose = graphics.pose()
		pose.pushMatrix()
		if (!module.enabled || lines.isEmpty()) return

		// Measured the way vanilla measures it, so the edges agree with what is
		// actually about to be drawn.
		var width = 0
		var height = if (lines.size == 1) -2 else 0
		for (line in lines) {
			width = maxOf(width, line.getWidth(font))
			height += line.getHeight(font)
		}
		val placed = positioner.positionTooltip(graphics.guiWidth(), graphics.guiHeight(), anchorX, anchorY, width, height)

		offsetX = clampAxis(offsetX, placed.x(), width, graphics.guiWidth())
		offsetY = clampAxis(offsetY, placed.y(), height, graphics.guiHeight())
		pose.translate(offsetX, offsetY)
	}

	/**
	 * How far one axis may move: towards the far edge until the tooltip's end
	 * reaches it, towards the near edge until its start does, and always back
	 * to zero.
	 */
	private fun clampAxis(offset: Float, start: Int, size: Int, screen: Int): Float {
		val towardsFarEdge = (screen - EDGE_PADDING) - (start + size).toFloat()
		val towardsNearEdge = EDGE_PADDING - start.toFloat()
		return offset.coerceIn(minOf(0f, towardsFarEdge), maxOf(0f, towardsNearEdge))
	}

	/** Room left between the tooltip and the edge, which is its own border. */
	private const val EDGE_PADDING = 4f

	@JvmStatic
	fun afterTooltip(graphics: GuiGraphicsExtractor) {
		graphics.pose().popMatrix()
	}

	/**
	 * Forgets the offset when the thing under the cursor changes.
	 *
	 * The slot index is what actually identifies an item here: two different
	 * items can produce tooltips of exactly the same shape, and comparing what
	 * the tooltip says would mean measuring it every frame.
	 */
	private fun noteWhatIsHovered() {
		if (!resetOnChange.value) return

		val screen = Minecraft.getInstance().gui.screen()
		val slot = (screen as? AbstractContainerScreen<*>)
			?.let { it as? ContainerScreenAccessor }
			?.`cryptic$hoveredSlot`()
			?.index
			?: -1

		if (screen !== lastScreen || slot != lastSlot) {
			lastScreen = screen
			lastSlot = slot
			offsetX = 0f
			offsetY = 0f
		}
	}

	/**
	 * Clears the flag that says a tooltip was on screen, which is how the wheel
	 * goes back to whatever is underneath once one is gone.
	 *
	 * Only the flag. The offset itself is left alone here: this runs on the tick
	 * rather than the frame, and below twenty frames a second a tick can land
	 * with no frame drawn between — resetting there would throw away a scroll
	 * while the tooltip it belongs to is still on screen. Pointing at something
	 * else is what clears it.
	 */
	fun endFrame() {
		tooltipShown = false
	}
}
