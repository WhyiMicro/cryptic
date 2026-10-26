package imicro.cryptic.render

import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * Minecraft's own letters from the game's built-in glyph sheet, whatever font a resource pack
 * installs. Read at runtime from the game's own copy. Only the first 256 codepoints; anything else
 * falls back to the normal font. Used for terminal labels.
 */
object VanillaGlyphs {
	private val VANILLA_SHEET = Identifier.fromNamespaceAndPath("minecraft", "textures/font/ascii.png")
	private val TEXTURE = Cryptic.id("font/vanilla_ascii")

	private const val GRID = 16

	private const val GLYPHS = GRID * GRID

	private var sheet: DynamicTexture? = null
	private var cell = 0
	private var advances = IntArray(0)

	/** A failed read isn't retried; the fallback is fine. */
	private var attempted = false

	fun isReady(): Boolean {
		load()
		return sheet != null
	}

	fun width(text: String): Int {
		if (!isReady()) return 0
		var total = 0
		for (character in text) total += advanceOf(character)
		return total
	}

	val lineHeight: Int get() = cell

	/** Draws [text] with its top-left at [left], [top], with the game's usual shadow. */
	fun draw(context: GuiGraphicsExtractor, text: String, left: Int, top: Int, argb: Int) {
		if (!isReady()) return
		// Vanilla's shadow: the same colour at a quarter brightness, one pixel down and right.
		val shadow = (argb and 0xFF000000.toInt()) or ((argb and 0xFCFCFC) shr 2)
		drawPass(context, text, left + 1, top + 1, shadow)
		drawPass(context, text, left, top, argb)
	}

	private fun drawPass(context: GuiGraphicsExtractor, text: String, left: Int, top: Int, argb: Int) {
		val size = cell
		val sheetSize = size * GRID
		var x = left
		for (character in text) {
			val index = character.code
			if (index >= GLYPHS) continue
			context.blit(
				RenderPipelines.GUI_TEXTURED,
				TEXTURE,
				x,
				top,
				((index % GRID) * size).toFloat(),
				((index / GRID) * size).toFloat(),
				size,
				size,
				sheetSize,
				sheetSize,
				argb,
			)
			x += advanceOf(character)
		}
	}

	private fun advanceOf(character: Char): Int {
		val index = character.code
		return if (index < GLYPHS) advances[index] else cell / 2
	}

	private fun load() {
		if (attempted) return
		attempted = true

		val client = Minecraft.getInstance()
		runCatching {
			// The first entry is the lowest pack, the game's own.
			val vanilla = client.resourceManager.getResourceStack(VANILLA_SHEET).firstOrNull()
				?: error("Minecraft's own font sheet is not in the resource stack")

			val image = vanilla.open().use(NativeImage::read)
			cell = image.width / GRID
			require(cell > 0) { "Minecraft's font sheet is ${image.width} pixels across, which is too small" }
			advances = measure(image)

			val texture = DynamicTexture({ TEXTURE.toString() }, image)
			client.textureManager.register(TEXTURE, texture)
			sheet = texture
		}.onFailure {
			Cryptic.LOGGER.warn("Could not read Minecraft's own font; terminal labels will use the installed one", it)
		}
	}

	/**
	 * Each glyph's advance, as the game works it out: rightmost opaque pixel plus one. An empty
	 * cell is a space, half a cell wide.
	 */
	private fun measure(image: NativeImage): IntArray = IntArray(GLYPHS) { index ->
		val originX = (index % GRID) * cell
		val originY = (index / GRID) * cell
		var rightmost = -1
		for (x in cell - 1 downTo 0) {
			var opaque = false
			for (y in 0 until cell) {
				if (image.getPixel(originX + x, originY + y) ushr 24 != 0) {
					opaque = true
					break
				}
			}
			if (opaque) {
				rightmost = x
				break
			}
		}
		if (rightmost < 0) cell / 2 else rightmost + 2
	}
}
