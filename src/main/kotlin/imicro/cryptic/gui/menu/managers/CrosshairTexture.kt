package imicro.cryptic.gui.menu.managers

import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * A crosshair drawing painted into a small texture, one texel per cell, and stretched over the
 * canvas as one quad. Repainted only when the drawing or colours change. Nearest sampling keeps
 * edges hard.
 */
internal class CrosshairTexture(name: String) {
	private val image = NativeImage(SIZE, SIZE, true)
	private val texture = DynamicTexture({ "Cryptic crosshair $name" }, image)
	private val id: Identifier = Cryptic.id("crosshair/$name")

	private var paintedFrom: Any? = null

	init {
		Minecraft.getInstance().textureManager.register(id, texture)
	}

	/** Repaints through [paint] (x, y, ARGB) unless [key] matches the last paint. */
	fun update(key: Any, paint: ((Int, Int, Int) -> Unit) -> Unit) {
		if (key == paintedFrom) return
		paintedFrom = key
		image.fillRect(0, 0, SIZE, SIZE, 0)
		paint { x, y, argb -> if (x in 0 until SIZE && y in 0 until SIZE) image.setPixel(x, y, argb) }
		texture.upload()
	}

	/** The top-left [cells] square, drawn [width] by [height] at ([x], [y]), tinted by [color]. */
	fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, cells: Int, color: Int) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, width, height, cells, cells, SIZE, SIZE, color)
	}

	companion object {
		/** The biggest canvas, plus a cell either side for the outline. */
		const val SIZE = 66
	}
}
