package imicro.cryptic.slayer

import imicro.cryptic.feature.SlayerFeatures
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale

/**
 * The Bloodfiend's Impel order in place of Hypixel's title, which is easy to miss mid-fight with
 * only two seconds to answer: the order flashing between two colours, arrows either side for the
 * clicks, and the seconds left under it, centred.
 */
class ImpelDisplay : HudElement("impel_display", "Impel Display", 0.5, 0.32, 2.0) {
	private val font get() = Minecraft.getInstance().font

	init {
		anchorX = 0.5
	}

	private var lastWidth = 80

	override val width: Int get() = lastWidth
	override val height: Int get() = font.lineHeight * 2 + 2

	override val layer get() = SlayerFeatures.module.category

	override val slayer get() = true

	override fun isVisible(): Boolean =
		SlayerFeatures.module.enabled && SlayerFeatures.impelDisplay.value && SkyblockLocation.onSkyblock &&
			SlayerAutomation.impelShown(System.currentTimeMillis()) != null

	override fun showInEditor(): Boolean = SlayerFeatures.module.enabled && SlayerFeatures.impelDisplay.value

	override fun render(context: GuiGraphicsExtractor) {
		val (order, left) = SlayerAutomation.impelShown(System.currentTimeMillis()) ?: return
		draw(context, order, left)
	}

	override fun renderExample(context: GuiGraphicsExtractor) = draw(context, "CLICK UP", 1.7)

	private fun draw(context: GuiGraphicsExtractor, order: String, left: Double?) {
		val arrow = when {
			"UP" in order -> "↑"
			"DOWN" in order -> "↓"
			else -> null
		}
		val bright = (System.currentTimeMillis() / FLASH_MILLIS) % 2 == 0L
		val colour = if (bright) "§b§l" else "§f§l"
		val top = colour + (arrow?.let { "$it $order $it" } ?: order)
		val bottom = left?.let { "§c" + String.format(Locale.ROOT, "%.1fs", it) } ?: ""
		val width = maxOf(font.width(top), font.width(bottom), 1)
		context.text(font, top, (width - font.width(top)) / 2, 0, -1)
		if (bottom.isNotEmpty()) context.text(font, bottom, (width - font.width(bottom)) / 2, font.lineHeight + 2, -1)
		lastWidth = width
	}

	private companion object {
		/** Each colour of the flash lasts this long. */
		const val FLASH_MILLIS = 200L
	}
}
