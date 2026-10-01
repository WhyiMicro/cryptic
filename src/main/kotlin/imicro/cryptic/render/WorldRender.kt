package imicro.cryptic.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws boxes in world space, for modules that highlight a position.
 *
 * Everything here is submitted during a level-render event, where the pose
 * stack is still camera-relative, so each call translates by the negated
 * camera position before emitting absolute world coordinates.
 *
 * Since 26.2 nothing is written into a buffer here. The level hands out a
 * [SubmitNodeCollector] instead, and geometry is queued on it with a snapshot
 * of the pose, to be drawn later in the frame. The vertex-building code is
 * unchanged — it simply runs when the queue is drained rather than now — so
 * everything captured by it must be a value, never state that could move on
 * in the meantime.
 *
 * Which means the *when* matters. The queue is drained once, at the top of
 * `LevelRenderer.render`, before a single pass of the frame is drawn, so
 * anything submitted from an event that fires while the frame is being drawn —
 * `AFTER_TRANSLUCENT_TERRAIN` and the rest — waits in the queue until the next
 * frame and is then drawn against that frame's camera. A box submitted that
 * way sits one frame of movement away from where it belongs, which is
 * invisible standing still and grows with speed. Callers register on
 * `LevelRenderEvents.COLLECT_SUBMITS`, which fires while the level is still
 * collecting, and the camera read below is then the one the frame's matrices
 * are built from.
 */
object WorldRender {
	/** Lifts faces off the block they trace, so the two do not z-fight. */
	private const val SURFACE_OFFSET = 0.002f

	/** Shrinks Minecraft's font to roughly nameplate size at one block away. */
	private const val TEXT_SCALE = 0.025f

	/** World text is lit by its own color, never by the block it stands on. */
	private const val FULL_BRIGHT_LIGHT = 0xF000F0

	/**
	 * Outlines and/or fills one block.
	 *
	 * [fullBlock] traces a whole cube rather than the block's own shape, which
	 * keeps the highlight readable on slabs, stairs and other partial blocks.
	 */
	fun drawBlock(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		pos: BlockPos,
		outlineArgb: Int,
		fillArgb: Int,
		outline: Boolean,
		fill: Boolean,
		phase: Boolean,
		lineWidth: Float,
		fullBlock: Boolean,
	) {
		if (!outline && !fill) return
		val level = Minecraft.getInstance().level ?: return

		val state = level.getBlockState(pos)
		val shape = if (fullBlock || state.isAir) Shapes.block() else state.getShape(level, pos)
		if (shape.isEmpty) return

		val minX = pos.x + shape.min(Direction.Axis.X)
		val minY = pos.y + shape.min(Direction.Axis.Y)
		val minZ = pos.z + shape.min(Direction.Axis.Z)
		val maxX = pos.x + shape.max(Direction.Axis.X)
		val maxY = pos.y + shape.max(Direction.Axis.Y)
		val maxZ = pos.z + shape.max(Direction.Axis.Z)

		drawBox(
			poseStack, collector,
			minX, minY, minZ, maxX, maxY, maxZ,
			outlineArgb, fillArgb, outline, fill, phase, lineWidth,
		)
	}

	/**
	 * Outlines and/or fills an arbitrary box in world coordinates, for
	 * highlighting something that is not a block — a dropped item, a mob, or
	 * whatever an invisible armour stand is carrying.
	 */
	fun drawBox(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		minX: Double,
		minY: Double,
		minZ: Double,
		maxX: Double,
		maxY: Double,
		maxZ: Double,
		outlineArgb: Int,
		fillArgb: Int,
		outline: Boolean,
		fill: Boolean,
		phase: Boolean,
		lineWidth: Float,
	) {
		if (!outline && !fill) return

		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)
		if (fill) {
			val layer = if (phase) CrypticRenderLayers.FILLED_THROUGH_WALLS else CrypticRenderLayers.FILLED
			collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
				consumer.addFilledBox(pose, minX, minY, minZ, maxX, maxY, maxZ, fillArgb)
			}
		}

		if (outline) {
			val layer = if (phase) CrypticRenderLayers.LINES_THROUGH_WALLS else CrypticRenderLayers.LINES
			collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
				consumer.addBoxOutline(pose, minX, minY, minZ, maxX, maxY, maxZ, outlineArgb, lineWidth)
			}
		}

		poseStack.popPose()
	}

	/**
	 * Draws a line from the player's view to a point in the world.
	 *
	 * It starts a little in front of the eyes rather than at them, because a
	 * line beginning exactly at the near plane is clipped into nothing.
	 */
	fun drawTracer(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		x: Double,
		y: Double,
		z: Double,
		argb: Int,
		lineWidth: Float,
		phase: Boolean,
	) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val camera = client.gameRenderer.mainCamera().position()
		val look = player.lookAngle

		val start = camera.add(look.scale(0.5))

		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)

		val layer = if (phase) CrypticRenderLayers.LINES_THROUGH_WALLS else CrypticRenderLayers.LINES
		val fromX = start.x.toFloat()
		val fromY = start.y.toFloat()
		val fromZ = start.z.toFloat()
		collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
			consumer.addLine(pose, fromX, fromY, fromZ, x.toFloat(), y.toFloat(), z.toFloat(), argb, lineWidth)
		}

		poseStack.popPose()
	}

	/**
	 * Draws a line through a list of world positions.
	 *
	 * One submit for the whole path rather than one per segment, because the
	 * queue is drained in order and a path split across submits is a path that
	 * can be drawn with something else in the middle of it.
	 */
	fun drawLine(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		points: List<Vec3>,
		argb: Int,
		lineWidth: Float,
		phase: Boolean,
	) {
		if (points.size < 2) return

		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)

		val layer = if (phase) CrypticRenderLayers.LINES_THROUGH_WALLS else CrypticRenderLayers.LINES
		val path = points.toList()
		collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
			for (index in 0 until path.size - 1) {
				val from = path[index]
				val to = path[index + 1]
				consumer.addLine(
					pose,
					from.x.toFloat(), from.y.toFloat(), from.z.toFloat(),
					to.x.toFloat(), to.y.toFloat(), to.z.toFloat(),
					argb, lineWidth,
				)
			}
		}

		poseStack.popPose()
	}

	/**
	 * Draws one line whose two ends are different colours.
	 *
	 * The colours are put on the vertices rather than drawn as a run of short
	 * lines, so the gradient is the one the graphics card interpolates for
	 * free — which is smooth at any length and costs exactly as much as a
	 * plain line.
	 */
	fun drawGradientLine(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		from: Vec3,
		to: Vec3,
		argbFrom: Int,
		argbTo: Int,
		lineWidth: Float,
		phase: Boolean,
	) {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)

		val layer = if (phase) CrypticRenderLayers.LINES_THROUGH_WALLS else CrypticRenderLayers.LINES
		collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
			consumer.addLine(
				pose,
				from.x.toFloat(), from.y.toFloat(), from.z.toFloat(),
				to.x.toFloat(), to.y.toFloat(), to.z.toFloat(),
				argbFrom, argbTo, lineWidth,
			)
		}

		poseStack.popPose()
	}

	/**
	 * Draws a ring lying flat around a point, for an ability with a radius.
	 *
	 * Enough segments that the corners stop reading as corners at the ten-block
	 * radius this is mostly used at; a circle that looks like a polygon reads as
	 * a smaller circle than it is.
	 */
	fun drawCircle(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		center: Vec3,
		radius: Double,
		argb: Int,
		lineWidth: Float,
		phase: Boolean,
		segments: Int = CIRCLE_SEGMENTS,
	) {
		val points = ArrayList<Vec3>(segments + 1)
		for (step in 0..segments) {
			val angle = step * 2.0 * Math.PI / segments
			points.add(Vec3(center.x + cos(angle) * radius, center.y, center.z + sin(angle) * radius))
		}
		drawLine(poseStack, collector, points, argb, lineWidth, phase)
	}

	private const val CIRCLE_SEGMENTS = 64

	/**
	 * Draws text that stands at a world position and faces the camera.
	 *
	 * The camera's own orientation is applied so the label stays readable from
	 * any angle, and the Y scale is negated because text is laid out downwards
	 * while world space grows upwards.
	 *
	 * [argb] is the colour a part of [text] that carries none of its own is
	 * drawn in — and, whatever the parts carry, its **alpha** is the alpha they
	 * are all drawn at. Minecraft combines the two that way round
	 * (`ARGB.color(alpha(base), style.color)`), which is what makes fading a
	 * label of several colours a matter of one number rather than of rebuilding
	 * every part of it.
	 */
	fun drawText(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		orientation: Quaternionf,
		text: Component,
		x: Double,
		y: Double,
		z: Double,
		scale: Float,
		seeThrough: Boolean,
		dropShadow: Boolean = true,
		backgroundArgb: Int = 0,
		argb: Int = 0xFFFFFFFF.toInt(),
	) {
		val font = Minecraft.getInstance().font
		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()

		poseStack.pushPose()
		poseStack.translate(x - camera.x, y - camera.y, z - camera.z)
		poseStack.mulPose(orientation)
		poseStack.scale(TEXT_SCALE * scale, -TEXT_SCALE * scale, TEXT_SCALE * scale)

		val halfWidth = font.width(text) / 2f
		// Text asked to be read through walls is also asked to be read through
		// ice and glass, which means being drawn after them.
		CrypticRenderLayers.textOverTerrain = seeThrough
		collector.submitText(
			poseStack,
			-halfWidth,
			0f,
			text.visualOrderText,
			dropShadow,
			if (seeThrough) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL,
			FULL_BRIGHT_LIGHT,
			argb,
			backgroundArgb,
			0,
		)
		CrypticRenderLayers.textOverTerrain = false
		poseStack.popPose()
	}

	private fun VertexConsumer.addFilledBox(
		pose: PoseStack.Pose,
		x1: Double,
		y1: Double,
		z1: Double,
		x2: Double,
		y2: Double,
		z2: Double,
		argb: Int,
	) {
		val minX = x1.toFloat() - SURFACE_OFFSET
		val minY = y1.toFloat() - SURFACE_OFFSET
		val minZ = z1.toFloat() - SURFACE_OFFSET
		val maxX = x2.toFloat() + SURFACE_OFFSET
		val maxY = y2.toFloat() + SURFACE_OFFSET
		val maxZ = z2.toFloat() + SURFACE_OFFSET

		addQuad(pose, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, argb)
		addQuad(pose, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, argb)
		addQuad(pose, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, argb)
		addQuad(pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, argb)
		addQuad(pose, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, argb)
		addQuad(pose, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, argb)
	}

	private fun VertexConsumer.addBoxOutline(
		pose: PoseStack.Pose,
		x1: Double,
		y1: Double,
		z1: Double,
		x2: Double,
		y2: Double,
		z2: Double,
		argb: Int,
		lineWidth: Float,
	) {
		val minX = x1.toFloat() - SURFACE_OFFSET
		val minY = y1.toFloat() - SURFACE_OFFSET
		val minZ = z1.toFloat() - SURFACE_OFFSET
		val maxX = x2.toFloat() + SURFACE_OFFSET
		val maxY = y2.toFloat() + SURFACE_OFFSET
		val maxZ = z2.toFloat() + SURFACE_OFFSET

		addLine(pose, minX, minY, minZ, maxX, minY, minZ, argb, lineWidth)
		addLine(pose, maxX, minY, minZ, maxX, minY, maxZ, argb, lineWidth)
		addLine(pose, maxX, minY, maxZ, minX, minY, maxZ, argb, lineWidth)
		addLine(pose, minX, minY, maxZ, minX, minY, minZ, argb, lineWidth)

		addLine(pose, minX, maxY, minZ, maxX, maxY, minZ, argb, lineWidth)
		addLine(pose, maxX, maxY, minZ, maxX, maxY, maxZ, argb, lineWidth)
		addLine(pose, maxX, maxY, maxZ, minX, maxY, maxZ, argb, lineWidth)
		addLine(pose, minX, maxY, maxZ, minX, maxY, minZ, argb, lineWidth)

		addLine(pose, minX, minY, minZ, minX, maxY, minZ, argb, lineWidth)
		addLine(pose, maxX, minY, minZ, maxX, maxY, minZ, argb, lineWidth)
		addLine(pose, maxX, minY, maxZ, maxX, maxY, maxZ, argb, lineWidth)
		addLine(pose, minX, minY, maxZ, minX, maxY, maxZ, argb, lineWidth)
	}

	private fun VertexConsumer.addQuad(
		pose: PoseStack.Pose,
		x1: Float, y1: Float, z1: Float,
		x2: Float, y2: Float, z2: Float,
		x3: Float, y3: Float, z3: Float,
		x4: Float, y4: Float, z4: Float,
		argb: Int,
	) {
		// The filled pipeline draws triangles, so each face is two of them.
		addVertex(pose, x1, y1, z1).setColor(argb)
		addVertex(pose, x2, y2, z2).setColor(argb)
		addVertex(pose, x3, y3, z3).setColor(argb)
		addVertex(pose, x1, y1, z1).setColor(argb)
		addVertex(pose, x3, y3, z3).setColor(argb)
		addVertex(pose, x4, y4, z4).setColor(argb)
	}

	private fun VertexConsumer.addLine(
		pose: PoseStack.Pose,
		x1: Float, y1: Float, z1: Float,
		x2: Float, y2: Float, z2: Float,
		argb: Int,
		lineWidth: Float,
	) = addLine(pose, x1, y1, z1, x2, y2, z2, argb, argb, lineWidth)

	/**
	 * One line segment, with a colour at each end.
	 *
	 * A single colour is the same call with the same colour twice: everything
	 * between the two vertices is the graphics card's own interpolation, which
	 * is what makes a gradient cost nothing.
	 */
	private fun VertexConsumer.addLine(
		pose: PoseStack.Pose,
		x1: Float, y1: Float, z1: Float,
		x2: Float, y2: Float, z2: Float,
		argbFrom: Int,
		argbTo: Int,
		lineWidth: Float,
	) {
		val normal = Vector3f(x2 - x1, y2 - y1, z2 - z1).apply { if (lengthSquared() > 0f) normalize() }
		addVertex(pose, x1, y1, z1).setColor(argbFrom).setNormal(pose, normal).setLineWidth(lineWidth)
		addVertex(pose, x2, y2, z2).setColor(argbTo).setNormal(pose, normal).setLineWidth(lineWidth)
	}
}
