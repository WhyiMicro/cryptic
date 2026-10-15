package imicro.cryptic.gui.menu

import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Font
import kotlin.math.roundToInt

/**
 * Text for the menu, in Poppins and Font Awesome.
 *
 * Sizes and positions are in the menu's design pixels. Each size is baked by
 * a [BakedFont] at the number of real pixels it covers on this screen, and
 * drawn on whole pixels, so text is as sharp as the display allows. A line of
 * text is given a box [size] tall, with its capitals centred in it, which is
 * what lines a label up with the control beside it.
 */
object UiText {
	/** The typefaces the menu writes in. */
	enum class Face(internal val path: String, internal val reference: String) {
		REGULAR("/assets/cryptic/font/poppins-regular.ttf", "H"),
		MEDIUM("/assets/cryptic/font/poppins-medium.ttf", "H"),

		/** Font Awesome's filled icons. */
		ICON("/assets/cryptic/font/fa-solid-900.ttf", ""),

		/** Font Awesome's outlined icons, the empty star among them. */
		ICON_OUTLINE("/assets/cryptic/font/fa-regular-400.ttf", ""),
		;

		internal val font: Font by lazy { BakedFont.load(path) }
	}

	private val baked = HashMap<String, BakedFont>()

	/** Real pixels per design pixel. The menu sets it at the start of every frame. */
	var pixelsPerUnit = 1f

	private fun bake(face: Face, size: Float): BakedFont {
		val pixels = (size * pixelsPerUnit).roundToInt().coerceAtLeast(1)
		return baked.getOrPut("${face.name}/$pixels") { BakedFont(face.font, pixels, face.name.lowercase(), face.reference) }
	}

	fun draw(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, size: Float, color: Int, face: Face = Face.REGULAR) {
		if ((color ushr 24) == 0) return
		val font = bake(face, size)
		val ppu = pixelsPerUnit
		val pose = graphics.pose()
		pose.pushMatrix()
		// One unit becomes one real pixel, so glyphs land on the pixel grid.
		pose.scale(1f / ppu, 1f / ppu)
		val baseline = (y + size / 2f) * ppu + font.capHeight / 2f
		font.draw(graphics, text, x * ppu, baseline, color)
		pose.popMatrix()
	}

	fun drawIcon(graphics: GuiGraphicsExtractor, glyph: String, x: Float, y: Float, size: Float, color: Int, outline: Boolean = false) =
		draw(graphics, glyph, x, y, size, color, if (outline) Face.ICON_OUTLINE else Face.ICON)

	/** An icon centred in a [box]-sized square at [x], [y]. */
	fun drawIconCentered(graphics: GuiGraphicsExtractor, glyph: String, x: Float, y: Float, box: Float, size: Float, color: Int, outline: Boolean = false) {
		val face = if (outline) Face.ICON_OUTLINE else Face.ICON
		drawIcon(graphics, glyph, x + (box - width(glyph, size, face)) / 2f, y + (box - size) / 2f, size, color, outline)
	}

	fun width(text: String, size: Float, face: Face = Face.REGULAR): Float = bake(face, size).width(text) / pixelsPerUnit

	/** [text] shortened with an ellipsis until it fits in [maxWidth]. */
	fun fit(text: String, size: Float, maxWidth: Float, face: Face = Face.REGULAR): String {
		if (width(text, size, face) <= maxWidth) return text
		var end = text.length
		while (end > 0 && width(text.substring(0, end).trimEnd() + "…", size, face) > maxWidth) end--
		return text.substring(0, end).trimEnd() + "…"
	}
}
