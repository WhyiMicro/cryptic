package imicro.cryptic.hud

import imicro.cryptic.Cryptic
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Everything Cryptic draws on the HUD. Elements register once and are drawn in one pass; placement,
 * scaling and the editor all come from here.
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

		// After the chat, so elements never cover a message.
		HudElementRegistry.attachElementAfter(
			VanillaHudElements.CHAT,
			Cryptic.id("hud"),
			{ context, _ -> renderAll(context) },
		)
	}

	/** Opens the placement editor; escape returns to [parent], or closes when there is none. */
	fun openEditor(parent: net.minecraft.client.gui.screens.Screen? = null) {
		val client = Minecraft.getInstance()
		client.gui.setScreen(HudEditorScreen(parent))
	}

	private fun renderAll(context: GuiGraphicsExtractor) {
		val client = Minecraft.getInstance()
		if (client.gui.screen() is HudEditorScreen) return

		for (element in registered) {
			if (!element.isVisible()) continue
			draw(context, element) { element.render(context) }
		}
	}

	internal fun draw(context: GuiGraphicsExtractor, element: HudElement, body: () -> Unit) {
		val pose = context.pose()
		pose.pushMatrix()
		// On whole pixels, so one-pixel lines and text edges come out even.
		pose.translate(
			Math.round(element.x * context.guiWidth()).toFloat(),
			Math.round(element.y * context.guiHeight()).toFloat(),
		)
		pose.scale(element.scale.toFloat(), element.scale.toFloat())
		body()
		pose.popMatrix()
	}
}
