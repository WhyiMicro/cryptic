package imicro.cryptic.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.Minecraft
import net.minecraft.util.ARGB
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A straight line in GUI space at any angle: one quad, rotated and stretched. After Odin's `drawLine`. */
object GuiShapes {
	fun line(
		context: GuiGraphicsExtractor,
		x1: Float,
		y1: Float,
		x2: Float,
		y2: Float,
		width: Float,
		argb: Int,
	) {
		if (ARGB.alpha(argb) == 0) return

		val length = hypot(x2 - x1, y2 - y1)
		if (length < 0.5f) return

		val pose = context.pose()
		pose.pushMatrix()
		pose.translate(x1, y1)
		pose.rotate(atan2(y2 - y1, x2 - x1))
		// Half the width each side of the line.
		pose.scale(1f, width / 2f)
		context.fill(0, -1, ceil(length).toInt(), 1, argb)
		pose.popMatrix()
	}
}

/**
 * A filled rounded rectangle in GUI space, smoothed by coverage instead of a shader: laid out in
 * screen pixels, with each row's end pixel drawn at the fraction of alpha the curve covers.
 */
object RoundedRect {
	fun fill(
		context: GuiGraphicsExtractor,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		argb: Int,
		radius: Float,
	) {
		val width = right - left
		val height = bottom - top
		if (width <= 0 || height <= 0) return

		val corner = radius.coerceIn(0f, minOf(width, height) / 2f)
		if (corner < 0.5f) {
			context.fill(left, top, right, bottom, argb)
			return
		}

		val scale = Minecraft.getInstance().window.guiScale.coerceAtLeast(1)
		val pose = context.pose()
		pose.pushMatrix()
		pose.translate(left.toFloat(), top.toFloat())
		pose.scale(1f / scale, 1f / scale)

		val w = width * scale
		val h = height * scale
		val r = ceil(corner * scale).toInt()

		context.fill(0, r, w, h - r, argb)

		val alpha = argb ushr 24 and 0xFF
		val rgb = argb and 0xFFFFFF

		for (row in 0 until r) {
			val dy = r - row - 0.5
			val inset = r - sqrt(r * r - dy * dy)
			val solid = ceil(inset).toInt()
			val fade = solid - inset

			context.fill(solid, row, w - solid, row + 1, argb)
			context.fill(solid, h - row - 1, w - solid, h - row, argb)

			// The partly covered pixel at a matching fraction of the colour.
			if (fade <= 0.01 || solid <= 0) continue
			val edge = ((alpha * fade).roundToInt() shl 24) or rgb
			context.fill(solid - 1, row, solid, row + 1, edge)
			context.fill(w - solid, row, w - solid + 1, row + 1, edge)
			context.fill(solid - 1, h - row - 1, solid, h - row, edge)
			context.fill(w - solid, h - row - 1, w - solid + 1, h - row, edge)
		}

		pose.popMatrix()
	}
}
