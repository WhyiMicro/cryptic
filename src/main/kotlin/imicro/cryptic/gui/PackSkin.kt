package imicro.cryptic.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier

/** Cryptic's own widgets drawn from the resource pack's textures, so they take on its look. */
object PackSkin {
	private val BUTTON = Identifier.withDefaultNamespace("widget/button")
	private val BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted")
	private val BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled")

	/** The pack's button at any size, like the pause menu's own. */
	@JvmStatic
	fun button(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, active: Boolean = true, hovered: Boolean = false) {
		val sprite = when {
			!active -> BUTTON_DISABLED
			hovered -> BUTTON_HIGHLIGHTED
			else -> BUTTON
		}
		// Vanilla's button is 200 by 20 with a 3 px border.
		nineSlice(graphics, sprite, x, y, width, height, 3, 3f / 200f, 3f / 20f)
	}

	/**
	 * Sliced here rather than by the sprite's own scaling: many packs ship a button without its
	 * .mcmeta, and Minecraft then stretches it, which thickens the border on anything taller.
	 */
	private fun nineSlice(
		graphics: GuiGraphicsExtractor, id: Identifier, x: Int, y: Int, width: Int, height: Int,
		border: Int, uBorder: Float, vBorder: Float,
	) {
		val sprite = runCatching { Minecraft.getInstance().atlasManager.getAtlasOrThrow(AtlasIds.GUI).getSprite(id) }.getOrNull() ?: return
		val b = minOf(border, width / 2, height / 2)
		val xs = intArrayOf(x, x + b, x + width - b, x + width)
		val ys = intArrayOf(y, y + b, y + height - b, y + height)
		val us = floatArrayOf(0f, uBorder, 1f - uBorder, 1f)
		val vs = floatArrayOf(0f, vBorder, 1f - vBorder, 1f)
		for (i in 0..2) {
			if (xs[i + 1] <= xs[i]) continue
			for (j in 0..2) {
				if (ys[j + 1] <= ys[j]) continue
				graphics.blit(
					sprite.atlasLocation(), xs[i], ys[j], xs[i + 1], ys[j + 1],
					sprite.getU(us[i]), sprite.getU(us[i + 1]), sprite.getV(vs[j]), sprite.getV(vs[j + 1]),
				)
			}
		}
	}
}
