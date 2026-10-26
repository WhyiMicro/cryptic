package imicro.cryptic.gui.menu

import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * One typeface at one pixel size, rasterised by Java2D into its own atlas at exactly the size shown
 * and drawn one texel per pixel. Minecraft's TrueType fonts are baked once and scaled, which looked
 * soft. Glyphs are added the first time they're drawn.
 */
class BakedFont(private val face: Font, val pixelSize: Int, name: String, reference: String) {
	private class Glyph(
		val u: Int, val v: Int,
		val width: Int, val height: Int,
		val offsetX: Int, val offsetY: Int,
		val advance: Float,
	)

	private val font = face.deriveFont(pixelSize.toFloat())
	private val context = FontRenderContext(null, true, true)
	private val glyphs = HashMap<Int, Glyph?>()

	private val image = NativeImage(ATLAS, ATLAS, true)
	private val texture = DynamicTexture({ "Cryptic menu font $name $pixelSize" }, image)
	private val id: Identifier = Cryptic.id("menu_font/${name}_$pixelSize")
	private var dirty = false

	// Shelf packing: rows fill left to right.
	private var packX = PADDING
	private var packY = PADDING
	private var shelfHeight = 0

	/** How far above the baseline [reference] reaches: a capital, or a typical icon. */
	val capHeight: Float

	init {
		Minecraft.getInstance().textureManager.register(id, texture)
		val bounds = font.createGlyphVector(context, reference).visualBounds
		capHeight = (-bounds.y).toFloat()
	}

	fun width(text: String): Float {
		var width = 0f
		var index = 0
		while (index < text.length) {
			val codePoint = text.codePointAt(index)
			width += glyph(codePoint)?.advance ?: 0f
			index += Character.charCount(codePoint)
		}
		return width
	}

	/**
	 * Draws [text] at [x] on [baseline], in screen pixels. Glyphs land on whole pixels, which keeps
	 * them sharp.
	 */
	fun draw(graphics: GuiGraphicsExtractor, text: String, x: Float, baseline: Float, color: Int) {
		var pen = x
		val base = baseline.roundToInt()
		var index = 0
		// New glyphs are rasterised first, so the texture uploads once.
		while (index < text.length) {
			val codePoint = text.codePointAt(index)
			glyph(codePoint)
			index += Character.charCount(codePoint)
		}
		if (dirty) {
			texture.upload()
			dirty = false
		}

		index = 0
		while (index < text.length) {
			val codePoint = text.codePointAt(index)
			index += Character.charCount(codePoint)
			val glyph = glyphs[codePoint] ?: continue
			if (glyph.width > 0) {
				graphics.blit(
					RenderPipelines.GUI_TEXTURED, id,
					pen.roundToInt() + glyph.offsetX, base + glyph.offsetY,
					glyph.u.toFloat(), glyph.v.toFloat(),
					glyph.width, glyph.height,
					ATLAS, ATLAS,
					color,
				)
			}
			pen += glyph.advance
		}
	}

	private fun glyph(codePoint: Int): Glyph? = glyphs.getOrPut(codePoint) { rasterise(codePoint) }

	private fun rasterise(codePoint: Int): Glyph? {
		if (!font.canDisplay(codePoint)) return null
		val text = String(Character.toChars(codePoint))
		val vector = font.createGlyphVector(context, text)
		val advance = vector.getGlyphMetrics(0).advance
		val bounds = vector.getGlyphPixelBounds(0, context, 0f, 0f)
		if (bounds.width <= 0 || bounds.height <= 0) return Glyph(0, 0, 0, 0, 0, 0, advance)

		// A pixel of room around each glyph for its anti-aliased edge.
		val width = bounds.width + 2
		val height = bounds.height + 2
		if (packX + width + PADDING > ATLAS) {
			packX = PADDING
			packY += shelfHeight + PADDING
			shelfHeight = 0
		}
		// A full atlas starts over; glyphs still in use are drawn again next frame.
		if (packY + height + PADDING > ATLAS) {
			reset()
			return rasterise(codePoint)
		}

		val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val g = bitmap.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
		g.font = font
		g.color = java.awt.Color.WHITE
		g.drawGlyphVector(vector, (1 - bounds.x).toFloat(), (1 - bounds.y).toFloat())
		g.dispose()

		// White with coverage as alpha, so the draw colour tints it.
		for (y in 0 until height) {
			for (x in 0 until width) {
				val alpha = bitmap.getRGB(x, y) ushr 24
				image.setPixelABGR(packX + x, packY + y, (alpha shl 24) or 0x00FFFFFF)
			}
		}
		dirty = true

		val glyph = Glyph(packX, packY, width, height, bounds.x - 1, bounds.y - 1, advance)
		packX += width + PADDING
		if (height > shelfHeight) shelfHeight = height
		return glyph
	}

	private fun reset() {
		glyphs.clear()
		image.fillRect(0, 0, ATLAS, ATLAS, 0)
		packX = PADDING
		packY = PADDING
		shelfHeight = 0
	}

	fun close() {
		Minecraft.getInstance().textureManager.release(id)
	}

	companion object {
		private const val ATLAS = 1024
		private const val PADDING = 1

		fun load(path: String): Font {
			val stream = BakedFont::class.java.getResourceAsStream(path) ?: error("Missing font $path")
			return stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
		}
	}
}
