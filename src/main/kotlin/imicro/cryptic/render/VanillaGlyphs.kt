package imicro.cryptic.render

import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * Minecraft's own letters, drawn straight from Minecraft's own glyph sheet,
 * whatever font the player has installed over the top.
 *
 * A resource pack changes the font either by replacing `font/default.json` or
 * by replacing the bitmap it points at, and a mod that simply asks for a font
 * by name gets whichever of those the player has. Declaring a font of Cryptic's
 * own in `assets/cryptic/font/vanilla.json` defeats the first; this defeats
 * both, by going to the *lowest* pack in the stack — which is always the game's
 * built-in one — and reading the untouched sheet out of it.
 *
 * Nothing is redistributed: the sheet is read at runtime from the copy the
 * player's game already has, and only ever the one the game shipped with.
 *
 * Only the terminal's labels are drawn this way, so this handles the first 256
 * codepoints and nothing else. Anything outside them falls back to the ordinary
 * font, which is the right answer for text that is not a number.
 */
object VanillaGlyphs {
	private val VANILLA_SHEET = Identifier.fromNamespaceAndPath("minecraft", "textures/font/ascii.png")
	private val TEXTURE = Cryptic.id("font/vanilla_ascii")

	/** The sheet is a 16 by 16 grid, however many pixels each cell turns out to be. */
	private const val GRID = 16

	private const val GLYPHS = GRID * GRID

	private var sheet: DynamicTexture? = null
	private var cell = 0
	private var advances = IntArray(0)

	/** A failed read is not worth retrying every frame; the fallback is fine. */
	private var attempted = false

	/** True once the sheet is loaded and text can actually be drawn from it. */
	fun isReady(): Boolean {
		load()
		return sheet != null
	}

	/** How wide [text] will be drawn, so the caller can centre it. */
	fun width(text: String): Int {
		if (!isReady()) return 0
		var total = 0
		for (character in text) total += advanceOf(character)
		return total
	}

	val lineHeight: Int get() = cell

	/**
	 * Draws [text] with its left edge at [left] and its top at [top], with the
	 * same dropped shadow the game's own text has.
	 */
	fun draw(context: GuiGraphicsExtractor, text: String, left: Int, top: Int, argb: Int) {
		if (!isReady()) return
		// Vanilla's shadow is the same colour at a quarter brightness, one
		// pixel down and right, and is drawn under the text rather than over.
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
			// The first entry is the lowest-priority pack, which is the game's
			// own. Anything the player has layered on top comes after it.
			val vanilla = client.resourceManager.getResourceStack(VANILLA_SHEET).firstOrNull()
				?: error("Minecraft's own font sheet is not in the resource stack")

			val image = vanilla.open().use(NativeImage::read)
			cell = image.width / GRID
			require(cell > 0) { "Minecraft's font sheet is ${image.width} pixels across, which is too small" }
			advances = measure(image)

			// The image is handed over to the texture, which owns it from here.
			val texture = DynamicTexture({ TEXTURE.toString() }, image)
			client.textureManager.register(TEXTURE, texture)
			sheet = texture
		}.onFailure {
			Cryptic.LOGGER.warn("Could not read Minecraft's own font; terminal labels will use the installed one", it)
		}
	}

	/**
	 * How far each glyph moves the pen along, worked out the way the game works
	 * it out: the rightmost pixel that is not transparent, plus a column of
	 * space. A cell with nothing in it is a space, and gets half a cell.
	 */
	private fun measure(image: NativeImage): IntArray = IntArray(GLYPHS) { index ->
		val originX = (index % GRID) * cell
		val originY = (index / GRID) * cell
		var rightmost = -1
		for (x in cell - 1 downTo 0) {
			var opaque = false
			for (y in 0 until cell) {
				// Pixels are ABGR, so the alpha is the top byte either way.
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
