package imicro.cryptic.gui.menu

import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Font
import kotlin.math.roundToInt

/**
 * Menu text in Poppins and Font Awesome, in design pixels. Each size is baked by a [BakedFont] at
 * its real pixel size and drawn on whole pixels. A line gets a box [size] tall with its capitals
 * centred, which lines labels up with their controls.
 */
object UiText {
	enum class Face(internal val path: String, internal val reference: String) {
		REGULAR("/assets/cryptic/font/poppins-regular.ttf", "H"),
		MEDIUM("/assets/cryptic/font/poppins-medium.ttf", "H"),

		ICON("/assets/cryptic/font/fa-solid-900.ttf", ""),

		/** Font Awesome's outlined icons (the empty star). */
		ICON_OUTLINE("/assets/cryptic/font/fa-regular-400.ttf", ""),
		;

		internal val font: Font by lazy { BakedFont.load(path) }
	}

	private val baked = HashMap<String, BakedFont>()

	/** Real pixels per design pixel, set at the start of every frame. */
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
		// One unit becomes one real pixel.
		pose.scale(1f / ppu, 1f / ppu)
		val baseline = (y + size / 2f) * ppu + font.capHeight / 2f
		font.draw(graphics, text, x * ppu, baseline, color)
		pose.popMatrix()
	}

	fun drawIcon(graphics: GuiGraphicsExtractor, glyph: String, x: Float, y: Float, size: Float, color: Int, outline: Boolean = false) =
		draw(graphics, glyph, x, y, size, color, if (outline) Face.ICON_OUTLINE else Face.ICON)

	fun drawIconCentered(graphics: GuiGraphicsExtractor, glyph: String, x: Float, y: Float, box: Float, size: Float, color: Int, outline: Boolean = false) {
		val face = if (outline) Face.ICON_OUTLINE else Face.ICON
		drawIcon(graphics, glyph, x + (box - width(glyph, size, face)) / 2f, y + (box - size) / 2f, size, color, outline)
	}

	fun width(text: String, size: Float, face: Face = Face.REGULAR): Float = bake(face, size).width(text) / pixelsPerUnit

	/** [text] shortened with an ellipsis to fit [maxWidth]. */
	fun fit(text: String, size: Float, maxWidth: Float, face: Face = Face.REGULAR): String {
		if (width(text, size, face) <= maxWidth) return text
		var end = text.length
		while (end > 0 && width(text.substring(0, end).trimEnd() + "…", size, face) > maxWidth) end--
		return text.substring(0, end).trimEnd() + "…"
	}

	/** [text] wrapped at spaces to [maxWidth]; an overlong word gets its own line. */
	fun wrap(text: String, size: Float, maxWidth: Float, face: Face = Face.REGULAR): List<String> {
		val lines = mutableListOf<String>()
		var line = ""
		for (word in text.split(' ').filter { it.isNotEmpty() }) {
			val next = if (line.isEmpty()) word else "$line $word"
			if (line.isEmpty() || width(next, size, face) <= maxWidth) {
				line = next
			} else {
				lines += line
				line = word
			}
		}
		if (line.isNotEmpty()) lines += line
		return lines
	}
}
