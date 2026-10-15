package imicro.cryptic.gui.menu

import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Cryptic's mark, top left of the menu.
 *
 * Drawn the way [BakedFont] draws text: scaled down by Java2D to exactly the
 * number of real pixels it covers, then put on the screen one texel to one
 * pixel. Squeezing the 128-pixel original on the GPU would sample every few
 * pixels and leave the crescent's thin tips broken.
 */
object MenuLogo {
	private const val SOURCE = "/assets/cryptic/logo.png"

	private val original: BufferedImage? by lazy {
		runCatching { MenuLogo::class.java.getResourceAsStream(SOURCE)?.use(ImageIO::read) }.getOrNull()
	}

	private var bakedSize = 0
	private var id: Identifier? = null

	/** Draws the mark [size] design pixels square at [x], [y], white at [color]'s alpha. */
	fun draw(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float, color: Int) {
		val ppu = UiText.pixelsPerUnit
		val pixels = (size * ppu).roundToInt().coerceAtLeast(1)
		val texture = bake(pixels) ?: return
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.scale(1f / ppu, 1f / ppu)
		graphics.blit(
			RenderPipelines.GUI_TEXTURED, texture,
			(x * ppu).roundToInt(), (y * ppu).roundToInt(),
			0f, 0f, pixels, pixels, pixels, pixels,
			color,
		)
		pose.popMatrix()
	}

	private fun bake(pixels: Int): Identifier? {
		if (pixels == bakedSize) return id
		val source = original ?: return null
		val scaled = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB)
		// Area averaging first, so every source pixel counts; bicubic alone
		// still skips some at a five-to-one shrink.
		val smooth = source.getScaledInstance(pixels, pixels, java.awt.Image.SCALE_AREA_AVERAGING)
		scaled.createGraphics().apply {
			setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
			drawImage(smooth, 0, 0, null)
			dispose()
		}

		val image = NativeImage(pixels, pixels, true)
		for (py in 0 until pixels) for (px in 0 until pixels) {
			val argb = scaled.getRGB(px, py)
			// NativeImage is ABGR.
			val abgr = (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)
			image.setPixelABGR(px, py, abgr)
		}
		val identifier = Cryptic.id("menu_logo/$pixels")
		Minecraft.getInstance().textureManager.register(identifier, DynamicTexture({ "Cryptic menu logo $pixels" }, image))
		id?.let { Minecraft.getInstance().textureManager.release(it) }
		id = identifier
		bakedSize = pixels
		return identifier
	}
}
