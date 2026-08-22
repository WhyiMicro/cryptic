package imicro.cryptic.hud

import imicro.cryptic.Cryptic
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Everything Cryptic draws on top of the game, and where the player put it.
 *
 * Elements register themselves once at startup and are drawn in one pass, so a
 * new one needs no rendering plumbing of its own: it declares its size and how
 * to draw itself, and the placement, scaling and editing all come from here.
 * Positions live in the profile, next to the module settings they belong with.
 */
object Hud {
	private val registered = mutableListOf<HudElement>()

	val elements: List<HudElement> get() = registered

	private var initialized = false

	fun register(element: HudElement) {
		if (registered.none { it.id == element.id }) registered.add(element)
	}

	fun byId(id: String): HudElement? = registered.firstOrNull { it.id == id }

	fun initialize() {
		if (initialized) return
		initialized = true

		// After the chat so an element never hides a message, and before the
		// screens the editor itself draws.
		HudElementRegistry.attachElementAfter(
			VanillaHudElements.CHAT,
			Cryptic.id("hud"),
			{ context, _ -> renderAll(context) },
		)
	}

	/**
	 * Opens the placement editor. [parent] is the screen to return to on
	 * escape, which is the menu when the editor was opened from its own button
	 * and nothing at all when it came from a command.
	 */
	fun openEditor(parent: net.minecraft.client.gui.screens.Screen? = null) {
		val client = Minecraft.getInstance()
		client.setScreen(HudEditorScreen(parent))
	}

	private fun renderAll(context: GuiGraphicsExtractor) {
		val client = Minecraft.getInstance()
		// The editor draws the elements itself, with handles around them.
		if (client.screen is HudEditorScreen) return

		for (element in registered) {
			if (!element.isVisible()) continue
			draw(context, element) { element.render(context) }
		}
	}

	/** Applies one element's placement, so both halves of the HUD agree on it. */
	internal fun draw(context: GuiGraphicsExtractor, element: HudElement, body: () -> Unit) {
		val pose = context.pose()
		pose.pushMatrix()
		pose.translate(
			(element.x * context.guiWidth()).toFloat(),
			(element.y * context.guiHeight()).toFloat(),
		)
		pose.scale(element.scale.toFloat(), element.scale.toFloat())
		body()
		pose.popMatrix()
	}
}
