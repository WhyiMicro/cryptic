package imicro.cryptic.hud

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.config.ConfigManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory

/**
 * Drag Cryptic's HUD elements into place. Each is drawn where and how big it really is; elements
 * with nothing to show draw [HudElement.renderExample] instead. Layers along the top show one tab's
 * elements at a time, or only those on screen where you are, so a full HUD doesn't pile up.
 */
class HudEditorScreen(private val parent: Screen? = null) : Screen(Component.literal("Cryptic HUD")) {
	private var dragged: HudElement? = null

	/** The element the arrow keys move. Stays selected after the drag, so it can be nudged. */
	private var selected: HudElement? = null
	private var grabX = 0.0
	private var grabY = 0.0

	private var lastMouseX = 0.0
	private var lastMouseY = 0.0

	private var snappedX = false
	private var snappedY = false

	/** How far the other elements have faded while one is dragged, 0 to 1. */
	private var dim = 0f
	private var lastFrame = 0L

	override fun onClose() {
		if (parent != null) minecraft.gui.setScreen(parent) else super.onClose()
	}

	override fun isPauseScreen(): Boolean = false

	override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		lastMouseX = mouseX.toDouble()
		lastMouseY = mouseY.toDouble()
		context.fill(0, 0, width, height, BACKDROP)

		val now = System.nanoTime()
		val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
		lastFrame = now
		val target = if (dragged != null) 1f else 0f
		dim = if (dt <= 0f) target else dim + (target - dim) * (1f - kotlin.math.exp(-DIM_SPEED * dt))

		if (dragged != null) drawCentreCross(context)

		val held = dragged
		for (element in shown().filter { it !== held } + listOfNotNull(held)) {
			val bounds = boundsOf(element)
			val hovered = element === dragged || (dragged == null && bounds.contains(mouseX.toDouble(), mouseY.toDouble()))
			// The selected one is lit and named as if hovered, so it's clear what the arrows move.
			val lit = hovered || element === selected

			Hud.draw(context, element) { element.renderExample(context) }
			// The others fade while one is dragged. The GUI can't fade items, so this is a veil
			// over them.
			val faded = if (element === held) 0f else dim
			if (faded > 0.01f) {
				context.fill(bounds.left, bounds.top, bounds.right, bounds.bottom, scaleAlpha(VEIL, faded))
			}
			outline(context, bounds, scaleAlpha(if (lit) FRAME_HOVERED else FRAME, 1f - faded * 0.6f))

			// Names only on the elements in hand, so a full HUD isn't buried in labels.
			if (lit) {
				val label = "${element.name}  ${"%.2f".format(element.scale)}x"
				context.centeredText(font, label, (bounds.left + bounds.right) / 2, bounds.top - font.lineHeight - 2, scaleAlpha(LABEL, 1f - faded * 0.7f))
			}
		}

		// The tabs and tips get out of the way while an element is being moved.
		val chrome = 1f - dim
		if (chrome > 0.01f) {
			drawLayers(context, mouseX, mouseY, chrome)
			if (HudSettings.showTips.value) context.centeredText(font, HINT, width / 2, height - font.lineHeight - 6, scaleAlpha(LABEL, chrome))
		}
		if (shown().isEmpty()) context.centeredText(font, EMPTY_LAYER, width / 2, height / 2 - 4, LABEL_MUTED)
		super.extractRenderState(context, mouseX, mouseY, partialTick)
	}

	// ---- Layers -------------------------------------------------------------------

	private enum class Layer(val label: String) { ALL("All"), DUNGEON("Dungeon"), BOSS("Boss"), GENERAL("General"), RENDER("Render"), NOW("Showing now") }

	/** The elements in the current layer that the editor offers. */
	private fun shown(): List<HudElement> = Hud.elements.filter { element ->
		element.showInEditor() && when (layer) {
			Layer.ALL -> true
			Layer.NOW -> element.isVisible()
			Layer.DUNGEON -> categoryOf(element) == ModuleCategory.DUNGEON
			Layer.BOSS -> categoryOf(element) == ModuleCategory.FLOOR_7
			Layer.GENERAL -> categoryOf(element) == ModuleCategory.GENERAL || categoryOf(element) == ModuleCategory.MISC
			Layer.RENDER -> categoryOf(element) == ModuleCategory.VISUAL
		}
	}

	private fun layerBoxes(): List<Pair<Layer, Bounds>> {
		var x = width / 2 - (Layer.entries.sumOf { font.width(it.label) + LAYER_PAD * 2 } + (Layer.entries.size - 1) * LAYER_GAP) / 2
		return Layer.entries.map { each ->
			val w = font.width(each.label) + LAYER_PAD * 2
			(each to Bounds(x, LAYER_TOP, x + w, LAYER_TOP + LAYER_HEIGHT)).also { x += w + LAYER_GAP }
		}
	}

	private fun drawLayers(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, alpha: Float) {
		for ((each, box) in layerBoxes()) {
			val active = each == layer
			val hovered = dragged == null && box.contains(mouseX.toDouble(), mouseY.toDouble())
			context.fill(box.left, box.top, box.right, box.bottom, scaleAlpha(if (active) LAYER_ACTIVE else if (hovered) LAYER_HOVER else LAYER_IDLE, alpha))
			if (active) context.fill(box.left, box.bottom - 1, box.right, box.bottom, scaleAlpha(FRAME_HOVERED, alpha))
			context.text(font, each.label, box.left + LAYER_PAD, box.top + (LAYER_HEIGHT - font.lineHeight) / 2 + 1, scaleAlpha(if (active || hovered) LABEL else LABEL_MUTED, alpha))
		}
	}

	override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
		layerBoxes().firstOrNull { it.second.contains(event.x(), event.y()) }?.let { (each, _) ->
			layer = each
			selected = null
			return true
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			val hit = topmostAt(event.x(), event.y())
			if (hit != null) {
				dragged = hit
				selected = hit
				grabX = event.x() - hit.x * width
				grabY = event.y() - hit.y * height
				return true
			}
			selected = null
		}

		// Right-click opens the settings of the module the element belongs to.
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			topmostAt(event.x(), event.y())?.let { element ->
				moduleOf(element)?.let { module ->
					imicro.cryptic.gui.menu.MenuScreen.request(module, settings = true)
					imicro.cryptic.gui.menu.MenuScreen.openIfRequested(minecraft)
				}
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

	/** Snaps each axis to the screen's middle on its own, and lights that arm of the cross. */
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

	private fun drawCentreCross(context: GuiGraphicsExtractor) {
		val midX = width / 2
		val midY = height / 2

		val horizontal = if (snappedY) CROSS_SNAPPED else CROSS
		val vertical = if (snappedX) CROSS_SNAPPED else CROSS

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
		// R resets the element under the cursor, or the selected one; shift-R the whole layer.
		if (event.key() == InputConstants.KEY_R) {
			if (event.hasShiftDown()) {
				shown().forEach(HudElement::reset)
				selected = null
			} else {
				(topmostAt(lastMouseX, lastMouseY) ?: selected)?.let {
					it.reset()
					it.clampTo(width, height)
				}
			}
			return true
		}

		// One pixel, or five with shift. Placements are fractions of the screen.
		val step = if (event.hasShiftDown()) FAST_NUDGE else 1
		val nudge = when (event.key()) {
			InputConstants.KEY_LEFT -> -step to 0
			InputConstants.KEY_RIGHT -> step to 0
			InputConstants.KEY_UP -> 0 to -step
			InputConstants.KEY_DOWN -> 0 to step
			else -> return super.keyPressed(event)
		}

		// The element under the cursor first, so a nudge doesn't need a click.
		val element = topmostAt(lastMouseX, lastMouseY) ?: selected ?: return true
		selected = element
		element.x += nudge.first.toDouble() / width
		element.y += nudge.second.toDouble() / height
		element.clampTo(width, height)
		return true
	}

	/** Saved on removal, which catches every way out of the screen. */
	override fun removed() {
		ConfigManager.flush()
		super.removed()
	}

	private fun scaleAlpha(color: Int, amount: Float): Int {
		val a = ((color ushr 24) * amount.coerceIn(0f, 1f)).toInt()
		return (color and 0x00FFFFFF) or (a shl 24)
	}

	private fun outline(context: GuiGraphicsExtractor, bounds: Bounds, color: Int) {
		context.fill(bounds.left, bounds.top, bounds.right, bounds.top + 1, color)
		context.fill(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, color)
		context.fill(bounds.left, bounds.top, bounds.left + 1, bounds.bottom, color)
		context.fill(bounds.right - 1, bounds.top, bounds.right, bounds.bottom, color)
	}

	private fun topmostAt(mouseX: Double, mouseY: Double): HudElement? =
		shown().lastOrNull { boundsOf(it).contains(mouseX, mouseY) }

	private fun boundsOf(element: HudElement): Bounds {
		val left = Math.round(element.x * width).toInt()
		val top = Math.round(element.y * height).toInt()
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
		/** Kept for the session, so the editor opens on the layer last used. */
		var layer = Layer.ALL

		private val modules = HashMap<String, Module?>()

		/**
		 * The module an element belongs to, found from the feature object that declares it (its
		 * `module` field), so elements don't each have to say.
		 */
		fun moduleOf(element: HudElement): Module? = modules.getOrPut(element.id) {
			runCatching {
				val owner = element.javaClass.enclosingClass ?: element.javaClass
				val instance = owner.getField("INSTANCE").get(null)
				(runCatching { owner.getField("module").get(instance) }.getOrNull()
					?: owner.getDeclaredField("module").apply { isAccessible = true }.get(instance)) as? Module
			}.getOrNull()
		}

		/** The menu tab of the module an element belongs to. */
		fun categoryOf(element: HudElement): ModuleCategory = element.layer ?: moduleOf(element)?.category ?: ModuleCategory.GENERAL

		const val HINT = "Drag to move  •  Arrows nudge  •  Scroll to resize  •  Right-click for settings  •  R resets one  •  Shift-R resets the layer"
		const val EMPTY_LAYER = "Nothing in this layer"
		const val LAYER_TOP = 6
		const val LAYER_HEIGHT = 14
		const val LAYER_PAD = 6
		const val LAYER_GAP = 2
		const val LAYER_IDLE = 0x80101010.toInt()
		const val LAYER_HOVER = 0xA0202020.toInt()
		const val LAYER_ACTIVE = 0xC0303030.toInt()
		const val LABEL_MUTED = 0xFF9A9A9A.toInt()
		const val BACKDROP = 0xA0101010.toInt()

		const val VEIL = 0xB4101010.toInt()
		const val DIM_SPEED = 14f

		const val SNAP_DISTANCE = 6

		const val FAST_NUDGE = 5

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
