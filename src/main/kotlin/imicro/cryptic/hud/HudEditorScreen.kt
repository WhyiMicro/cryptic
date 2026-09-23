package imicro.cryptic.hud

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.config.ConfigManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Drag Cryptic's HUD elements into place.
 *
 * Every element is drawn where it will really sit, at the size it will really
 * be, with a frame around it: arranging the HUD against a mock-up is how you
 * end up with one that overlaps something in the real thing. Elements that
 * have nothing to show outside a dungeon draw a stand-in instead, which is
 * what [HudElement.renderExample] is for.
 */
class HudEditorScreen(private val parent: Screen? = null) : Screen(Component.literal("Cryptic HUD")) {
	private var dragged: HudElement? = null
	private var grabX = 0.0
	private var grabY = 0.0

	/** Which arms of the centre cross are currently holding the dragged element. */
	private var snappedX = false
	private var snappedY = false

	/**
	 * Escape goes back where you came from.
	 *
	 * Reached from the menu's own button this is a step into a sub-screen, and
	 * a step out again should land back in the menu rather than in the game.
	 * Reached from `/cryptic hud` there is nothing behind it, so it closes.
	 */
	override fun onClose() {
		if (parent != null) minecraft?.gui?.setScreen(parent) else super.onClose()
	}

	override fun isPauseScreen(): Boolean = false

	override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		context.fill(0, 0, width, height, BACKDROP)

		if (dragged != null) drawCentreCross(context)

		for (element in Hud.elements) {
			if (!element.showInEditor()) continue
			val bounds = boundsOf(element)
			val hovered = element === dragged || bounds.contains(mouseX.toDouble(), mouseY.toDouble())

			Hud.draw(context, element) { element.renderExample(context) }
			outline(context, bounds, if (hovered) FRAME_HOVERED else FRAME)

			val label = "${element.name}  ${"%.2f".format(element.scale)}x"
			context.centeredText(font, label, (bounds.left + bounds.right) / 2, bounds.top - font.lineHeight - 2, LABEL)
		}

		context.centeredText(font, HINT, width / 2, height - font.lineHeight - 6, LABEL)
		super.extractRenderState(context, mouseX, mouseY, partialTick)
	}

	override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			val hit = topmostAt(event.x(), event.y())
			if (hit != null) {
				dragged = hit
				grabX = event.x() - hit.x * width
				grabY = event.y() - hit.y * height
				return true
			}
		}

		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			topmostAt(event.x(), event.y())?.let {
				it.reset()
				return true
			}
		}

		return super.mouseClicked(event, doubled)
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		val element = dragged ?: return super.mouseDragged(event, dragX, dragY)
		element.x = (event.x() - grabX) / width
		element.y = (event.y() - grabY) / height
		snapToCentre(element)
		element.clampTo(width, height)
		return true
	}

	/**
	 * Pulls a dragged element onto the middle of the screen.
	 *
	 * Each axis snaps on its own, so an element can be centred across the
	 * screen while sitting wherever you like down it — which is what you want
	 * nine times out of ten, and is impossible to hit by hand at this scale.
	 * [snappedX] and [snappedY] say which arms of the cross to light up, so the
	 * snap is something you can see happen rather than something you discover
	 * afterwards.
	 */
	private fun snapToCentre(element: HudElement) {
		snappedX = false
		snappedY = false
		if (width <= 0 || height <= 0) return

		val elementWidth = element.width * element.scale
		val elementHeight = element.height * element.scale

		val centredX = (width - elementWidth) / 2.0 / width
		val centredY = (height - elementHeight) / 2.0 / height

		if (kotlin.math.abs(element.x - centredX) * width <= SNAP_DISTANCE) {
			element.x = centredX
			snappedX = true
		}
		if (kotlin.math.abs(element.y - centredY) * height <= SNAP_DISTANCE) {
			element.y = centredY
			snappedY = true
		}
	}

	/**
	 * The cross in the middle of the screen, drawn only while something is
	 * being dragged — it is a guide, and a guide with nothing to guide is just
	 * a mark on the screen.
	 */
	private fun drawCentreCross(context: GuiGraphicsExtractor) {
		val midX = width / 2
		val midY = height / 2

		val horizontal = if (snappedY) CROSS_SNAPPED else CROSS
		val vertical = if (snappedX) CROSS_SNAPPED else CROSS

		// Each arm is drawn its full length when it is holding the element, so
		// a snap reads as a line through the screen rather than a longer tick.
		val armX = if (snappedX) height else CROSS_ARM
		val armY = if (snappedY) width else CROSS_ARM

		context.fill(midX - armY / 2, midY, midX + armY / 2, midY + 1, horizontal)
		context.fill(midX, midY - armX / 2, midX + 1, midY + armX / 2, vertical)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (dragged != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragged = null
			snappedX = false
			snappedY = false
			return true
		}
		return super.mouseReleased(event)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		val element = topmostAt(mouseX, mouseY) ?: return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
		element.scale = (element.scale + scrollY * SCALE_STEP).coerceIn(MIN_SCALE, MAX_SCALE)
		element.clampTo(width, height)
		return true
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		if (event.key() == InputConstants.KEY_R) {
			Hud.elements.forEach(HudElement::reset)
			return true
		}
		return super.keyPressed(event)
	}

	/**
	 * Placements belong to the profile, the same as every other setting.
	 *
	 * Saving on removal rather than on close catches every way out of the
	 * screen, including being replaced by another one.
	 */
	override fun removed() {
		ConfigManager.flush()
		super.removed()
	}

	/** A one-pixel border, so the frame shows an element's reach without hiding it. */
	private fun outline(context: GuiGraphicsExtractor, bounds: Bounds, color: Int) {
		context.fill(bounds.left, bounds.top, bounds.right, bounds.top + 1, color)
		context.fill(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, color)
		context.fill(bounds.left, bounds.top, bounds.left + 1, bounds.bottom, color)
		context.fill(bounds.right - 1, bounds.top, bounds.right, bounds.bottom, color)
	}

	/** The last element drawn is the one on top, so it is hit first. */
	private fun topmostAt(mouseX: Double, mouseY: Double): HudElement? =
		Hud.elements.lastOrNull { it.showInEditor() && boundsOf(it).contains(mouseX, mouseY) }

	private fun boundsOf(element: HudElement): Bounds {
		val left = (element.x * width).toInt()
		val top = (element.y * height).toInt()
		return Bounds(
			left,
			top,
			left + (element.width * element.scale).toInt(),
			top + (element.height * element.scale).toInt(),
		)
	}

	private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		fun contains(x: Double, y: Double): Boolean = x >= left && x <= right && y >= top && y <= bottom
	}

	private companion object {
		const val HINT = "Drag to move  •  Scroll to resize  •  Right-click to reset one  •  R resets all"
		const val BACKDROP = 0xA0101010.toInt()

		/** How near the middle an element has to be dragged before it is taken. */
		const val SNAP_DISTANCE = 6

		/** The cross's reach when it is only offering, in GUI pixels. */
		const val CROSS_ARM = 34

		const val CROSS = 0x50FFFFFF
		const val CROSS_SNAPPED = 0xFF55FF55.toInt()
		const val FRAME = 0x60FFFFFF
		const val FRAME_HOVERED = 0xFFFFFFFF.toInt()
		const val LABEL = 0xFFE4E4E4.toInt()
		const val SCALE_STEP = 0.1
		const val MIN_SCALE = 0.3
		const val MAX_SCALE = 4.0
	}
}
