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

	/**
	 * Escape goes back where you came from.
	 *
	 * Reached from the menu's own button this is a step into a sub-screen, and
	 * a step out again should land back in the menu rather than in the game.
	 * Reached from `/cryptic hud` there is nothing behind it, so it closes.
	 */
	override fun onClose() {
		if (parent != null) minecraft?.setScreen(parent) else super.onClose()
	}

	override fun isPauseScreen(): Boolean = false

	override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		context.fill(0, 0, width, height, BACKDROP)

		for (element in Hud.elements) {
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
		element.clampTo(width, height)
		return true
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (dragged != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragged = null
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
		Hud.elements.lastOrNull { boundsOf(it).contains(mouseX, mouseY) }

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
		const val FRAME = 0x60FFFFFF
		const val FRAME_HOVERED = 0xFFFFFFFF.toInt()
		const val LABEL = 0xFFE4E4E4.toInt()
		const val SCALE_STEP = 0.1
		const val MIN_SCALE = 0.3
		const val MAX_SCALE = 4.0
	}
}
