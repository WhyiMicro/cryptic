package imicro.cryptic.slayer

import imicro.cryptic.feature.SlayerFeatures
import imicro.cryptic.hud.HudElement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Your slayer boss on the HUD: its name and stage, its health (or hits) with a bar under it, what
 * it's doing, then the fight's time and the time left before it despawns. Sized to what it shows.
 */
class BossHud : HudElement("slayer_boss", "Slayer Boss", 0.01, 0.4) {
	private val font get() = Minecraft.getInstance().font

	private var lastWidth = MIN_WIDTH
	private var lastHeight = 4 * 9 + BAR_ROOM

	override val width: Int get() = lastWidth
	override val height: Int get() = lastHeight

	override val layer get() = SlayerFeatures.module.category

	override val slayer get() = true

	override fun isVisible(): Boolean = SlayerFeatures.hudView() != null

	override fun showInEditor(): Boolean = SlayerFeatures.hudOn

	override fun render(context: GuiGraphicsExtractor) {
		val view = SlayerFeatures.hudView() ?: return
		keepOnScreen(context)
		draw(context, view)
	}

	override fun renderExample(context: GuiGraphicsExtractor) =
		draw(context, SlayerFeatures.hudView() ?: SlayerFeatures.exampleView())

	private fun lines(view: SlayerFeatures.BossView): Pair<List<String>, List<String>> {
		val whose = view.owner?.let { " §8($it)" } ?: ""
		val head = listOf(view.title + (view.stage?.let { " $it" } ?: "") + whose) +
			listOfNotNull(view.main?.let { it + "§7${view.maximum}" + if (view.maximum.isNotEmpty()) " §8${Math.round(view.fraction * 100)}%" else "" })
		val time = "§7Time §f${SlayerFeatures.duration(view.elapsedMillis)}" +
			(view.timeLeft?.let { " §8· §7Despawn §f${SlayerFeatures.clock(it)}" } ?: "")
		return head to view.state + time
	}

	private fun draw(context: GuiGraphicsExtractor, view: SlayerFeatures.BossView) {
		val (head, tail) = lines(view)
		val line = font.lineHeight
		val width = maxOf(MIN_WIDTH, (head + tail).maxOf { font.width(it) })
		var y = 0
		for (text in head) {
			context.text(font, text, 0, y, -1)
			y += line
		}
		if (SlayerFeatures.hudBar.value && view.main != null) {
			val filled = Math.round(width * view.fraction).toInt()
			context.fill(0, y, width, y + BAR_HEIGHT, BAR_BACK)
			context.fill(0, y, filled, y + BAR_HEIGHT, barColour(view.shade))
			y += BAR_ROOM
		}
		for (text in tail) {
			context.text(font, text, 0, y, -1)
			y += line
		}
		lastWidth = width
		lastHeight = maxOf(line, y)
	}

	private fun barColour(code: String): Int = when (code) {
		"§a" -> 0xFF55FF55.toInt()
		"§e" -> 0xFFFFFF55.toInt()
		else -> 0xFFFF5555.toInt()
	}

	private companion object {
		const val MIN_WIDTH = 100
		const val BAR_HEIGHT = 3
		const val BAR_ROOM = 5
		const val BAR_BACK = 0x80000000.toInt()
	}
}
