package imicro.cryptic.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.Minecraft
import net.minecraft.util.ARGB
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A straight line in GUI space, at any angle.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking), whose
 * `drawLine` this is: translate to the start, turn the matrix to face along the
 * line, stretch it across, and fill one rectangle. The rectangle is described
 * two units tall and scaled to the width, so the whole line is a single quad at
 * whatever angle it happens to run.
 *
 * An earlier attempt rasterised the line a column at a time and paid for the
 * edges in alpha, which is anti-aliasing in principle and was wrong in
 * practice: the two branches that decide whether a step is a row or a column
 * were the wrong way round, so the body of the line was drawn across the screen
 * from where it belonged and all that was left were the two round ends. One
 * quad and one matrix is both correct and less to be wrong about.
 */
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
		// Half the width each side of the line, which is what the two units the
		// rectangle is described across become once this has scaled them.
		pose.scale(1f, width / 2f)
		context.fill(0, -1, ceil(length).toInt(), 1, argb)
		pose.popMatrix()
	}
}

/**
 * A filled rectangle with rounded corners, in GUI space.
 *
 * Odin rounds its terminal slots with a shader, which is where its corners get
 * their smoothness: a fragment either side of the curve is shaded by how much
 * of it the shape covers, so the edge fades rather than steps.
 *
 * This draws the same shape out of vanilla's own filled rectangles and gets the
 * smoothness the same way — by coverage rather than by a shader. Two things
 * make that work. The whole shape is laid out in *screen* pixels rather than
 * GUI pixels, so a curve at GUI scale 3 is stepped three times as finely as the
 * grid it is drawn on; and the pixel at each end of every row is drawn at a
 * fraction of the colour's alpha, that fraction being how much of it the curve
 * actually covers. What is left is a corner that reads as smooth at any size,
 * for a hundred-odd quads and no pipeline to keep in step with the game's
 * renderer.
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

		// One unit per screen pixel, so the curve is stepped as finely as the
		// screen can show rather than as coarsely as the GUI grid.
		val scale = Minecraft.getInstance().window.guiScale.toInt().coerceAtLeast(1)
		val pose = context.pose()
		pose.pushMatrix()
		pose.translate(left.toFloat(), top.toFloat())
		pose.scale(1f / scale, 1f / scale)

		val w = width * scale
		val h = height * scale
		val r = ceil(corner * scale).toInt()

		// The straight middle, which is every row between the two corner bands.
		context.fill(0, r, w, h - r, argb)

		val alpha = argb ushr 24 and 0xFF
		val rgb = argb and 0xFFFFFF

		for (row in 0 until r) {
			// From the middle of the row, so a row is measured where it is
			// rather than where it starts.
			val dy = r - row - 0.5
			val inset = r - sqrt(r * r - dy * dy)
			val solid = ceil(inset).toInt()
			val fade = solid - inset

			// The body of the row, in one span at each end of the shape.
			context.fill(solid, row, w - solid, row + 1, argb)
			context.fill(solid, h - row - 1, w - solid, h - row, argb)

			// And the pixel the curve only partly covers, at a matching part of
			// the colour — which is the whole of the smoothing.
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
