package imicro.cryptic.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import imicro.cryptic.Cryptic
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rounded rectangles, outlines and soft shadows in GUI space, drawn by a
 * shader rather than out of filled rows.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking), whose
 * `RoundedRectRenderer` this is, less its textured variant. Each shape is one
 * quad handed to Minecraft's own GUI renderer as a render-state element; the
 * fragment shader (`shaders/include/round_rect.glsl`) works out how much of
 * each pixel lies inside the rounded box, so the edge fades instead of
 * stepping. Going through Minecraft's pipeline rather than a library of its
 * own is what lets the new menu run on Vulkan as well as OpenGL.
 */
object RoundRect {
	private const val AA_PADDING = 2f
	private const val HALF_EXTENT_SCALE = 8f
	private const val RADIUS_SCALE = 4f
	private const val MAX_RADIUS = 255f / RADIUS_SCALE
	private const val ALPHA_MASK = 0xFF shl 24

	/** Position, colour, then the shape's own numbers smuggled through the spare attributes. */
	private val FORMAT: VertexFormat = VertexFormat.builder(0)
		.addAttribute(DefaultVertexFormat.POSITION_SEMANTIC_NAME, GpuFormat.RGB32_FLOAT)
		.addAttribute(DefaultVertexFormat.COLOR_SEMANTIC_NAME, GpuFormat.RGBA8_UNORM)
		.addAttribute(DefaultVertexFormat.UV0_SEMANTIC_NAME, GpuFormat.RG32_FLOAT)
		.addAttribute(DefaultVertexFormat.UV1_SEMANTIC_NAME, GpuFormat.RG16_SINT)
		.addAttribute(DefaultVertexFormat.UV2_SEMANTIC_NAME, GpuFormat.RG16_SINT)
		.addAttribute(DefaultVertexFormat.LINE_WIDTH_SEMANTIC_NAME, GpuFormat.R32_FLOAT)
		.build()

	private val SHAPE: RenderPipeline = pipeline("round_rect")
	private val SHADOW: RenderPipeline = pipeline("round_rect_shadow")
	private val CHECKER: RenderPipeline = pipeline("round_rect_checker")

	private fun pipeline(name: String): RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
			.withLocation(Cryptic.id("pipeline/$name"))
			.withFragmentShader(Cryptic.id("core/$name"))
			.withVertexShader(Cryptic.id("core/round_rect"))
			.withVertexBinding(0, FORMAT)
			.build(),
	)

	/** Registers the pipelines while the game starts, before the shaders are loaded. */
	fun touch() = Unit

	/** A filled rounded rectangle. */
	fun fill(graphics: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, radius: Float) =
		emit(graphics, x0, y0, x1, y1, color, color, color, color, radius, radius, radius, radius, 0f, AA_PADDING, SHAPE)

	/** The same, with each corner rounded on its own (top left, top right, bottom right, bottom left). */
	fun fill(
		graphics: GuiGraphicsExtractor,
		x0: Float, y0: Float, x1: Float, y1: Float,
		color: Int,
		topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
	) = emit(graphics, x0, y0, x1, y1, color, color, color, color, topLeft, topRight, bottomRight, bottomLeft, 0f, AA_PADDING, SHAPE)

	/** A top-to-bottom gradient. */
	fun gradient(graphics: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, top: Int, bottom: Int, radius: Float) =
		emit(graphics, x0, y0, x1, y1, top, top, bottom, bottom, radius, radius, radius, radius, 0f, AA_PADDING, SHAPE)

	/** A colour per corner, blended across the shape: what a colour picker's square is made of. */
	fun fillCorners(
		graphics: GuiGraphicsExtractor,
		x0: Float, y0: Float, x1: Float, y1: Float,
		topLeftColor: Int, topRightColor: Int, bottomRightColor: Int, bottomLeftColor: Int,
		radius: Float,
	) = emit(graphics, x0, y0, x1, y1, topLeftColor, topRightColor, bottomRightColor, bottomLeftColor, radius, radius, radius, radius, 0f, AA_PADDING, SHAPE)

	/** Only the edge, [width] wide, drawn inside the rectangle. */
	fun outline(graphics: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, radius: Float, width: Float) =
		emit(graphics, x0, y0, x1, y1, color, color, color, color, radius, radius, radius, radius, width, AA_PADDING, SHAPE)

	/**
	 * The checkerboard a see-through colour is shown over, in [cell]-sized
	 * squares, clipped to the rounded shape so it has the same corners as the
	 * colour drawn on top of it. Drawn by its own shader (`round_rect_checker`).
	 * [tint] (RGB) multiplies both tones, so a darker board is one quad too.
	 */
	fun checker(graphics: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, cell: Float, alpha: Float = 1f, tint: Int = 0xFFFFFF) {
		val color = ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (tint and 0xFFFFFF)
		emit(graphics, x0, y0, x1, y1, color, color, color, color, radius, radius, radius, radius, cell, AA_PADDING, CHECKER)
	}

	/** A soft shadow that fades out over [blur] around the rectangle. */
	fun shadow(graphics: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, radius: Float, blur: Float) =
		emit(graphics, x0, y0, x1, y1, color, color, color, color, radius, radius, radius, radius, blur, AA_PADDING + blur, SHADOW)

	private fun emit(
		graphics: GuiGraphicsExtractor,
		x0: Float, y0: Float, x1: Float, y1: Float,
		topLeftColor: Int, topRightColor: Int, bottomRightColor: Int, bottomLeftColor: Int,
		topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
		edgeWidth: Float, padding: Float,
		pipeline: RenderPipeline,
	) {
		if ((topLeftColor or topRightColor or bottomRightColor or bottomLeftColor) and ALPHA_MASK == 0) return

		val left = min(x0, x1)
		val top = min(y0, y1)
		val right = max(x0, x1)
		val bottom = max(y0, y1)
		val halfWidth = (right - left) * 0.5f
		val halfHeight = (bottom - top) * 0.5f
		if (halfWidth <= 0f || halfHeight <= 0f) return

		val pose = snapshotPose(graphics.pose())
		val scissor = graphics.scissorStack.peek()

		val quadX0 = floor(left - padding).toInt()
		val quadY0 = floor(top - padding).toInt()
		val quadX1 = ceil(right + padding).toInt()
		val quadY1 = ceil(bottom + padding).toInt()

		val quad = ScreenRectangle(quadX0, quadY0, quadX1 - quadX0, quadY1 - quadY0).transformMaxBounds(pose)
		val bounds = if (scissor != null) scissor.intersection(quad) else quad

		graphics.guiRenderState.addGuiElement(
			Element(
				pose,
				quadX0.toFloat(), quadY0.toFloat(), quadX1.toFloat(), quadY1.toFloat(),
				(left + right) * 0.5f, (top + bottom) * 0.5f,
				topLeftColor, topRightColor, bottomRightColor, bottomLeftColor,
				fixedPoint(halfWidth), fixedPoint(halfHeight),
				packRadius(topLeft) or (packRadius(topRight) shl 8),
				packRadius(bottomRight) or (packRadius(bottomLeft) shl 8),
				edgeWidth, pipeline, scissor, bounds,
			),
		)
	}

	private val NO_TEXTURE: TextureSetup = TextureSetup.noTexture()

	/**
	 * The pose the shape was submitted under, copied: the stack it came from is
	 * changed again before the element is turned into vertices. Consecutive
	 * shapes under one pose share the copy.
	 */
	private var cachedPose: Matrix3x2f? = null

	private fun snapshotPose(current: Matrix3x2fc): Matrix3x2f {
		val cached = cachedPose
		if (cached != null && cached.equals(current, 0f)) return cached
		return Matrix3x2f(current).also { cachedPose = it }
	}

	private fun fixedPoint(value: Float): Int =
		(value * HALF_EXTENT_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

	private fun packRadius(radius: Float): Int =
		(radius.coerceIn(0f, MAX_RADIUS) * RADIUS_SCALE).roundToInt().coerceIn(0, 255)

	private class Element(
		private val pose: Matrix3x2fc,
		private val x0: Float, private val y0: Float,
		private val x1: Float, private val y1: Float,
		private val centerX: Float, private val centerY: Float,
		private val topLeftColor: Int, private val topRightColor: Int,
		private val bottomRightColor: Int, private val bottomLeftColor: Int,
		private val packedHalfWidth: Int, private val packedHalfHeight: Int,
		private val packedRadiiTop: Int, private val packedRadiiBottom: Int,
		private val edgeWidth: Float,
		private val pipeline: RenderPipeline,
		private val scissorArea: ScreenRectangle?,
		private val bounds: ScreenRectangle?,
	) : GuiElementRenderState {
		override fun pipeline() = pipeline
		override fun textureSetup(): TextureSetup = NO_TEXTURE
		override fun scissorArea() = scissorArea
		override fun bounds() = bounds

		override fun buildVertices(consumer: VertexConsumer) {
			vertex(consumer, x0, y0, topLeftColor)
			vertex(consumer, x0, y1, bottomLeftColor)
			vertex(consumer, x1, y1, bottomRightColor)
			vertex(consumer, x1, y0, topRightColor)
		}

		private fun vertex(consumer: VertexConsumer, x: Float, y: Float, color: Int) {
			consumer.addVertexWith2DPose(pose, x, y)
				.setColor(color)
				.setUv(x - centerX, y - centerY)
				.setUv1(packedHalfWidth, packedHalfHeight)
				.setUv2(packedRadiiTop, packedRadiiBottom)
				.setLineWidth(edgeWidth)
		}
	}
}
