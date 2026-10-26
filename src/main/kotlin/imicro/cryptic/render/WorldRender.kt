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
 * Draws boxes, lines and text in world space for highlighting features.
 *
 * Since 26.2, geometry is queued on a [SubmitNodeCollector] and drawn later in the frame, so
 * everything captured must be a plain value. Callers must submit from
 * `LevelRenderEvents.COLLECT_SUBMITS`: anything submitted later in the frame waits for the next one
 * and lags a frame behind the camera.
 */
object WorldRender {
	private const val SURFACE_OFFSET = 0.002f

	private const val TEXT_SCALE = 0.025f

	private const val FULL_BRIGHT_LIGHT = 0xF000F0

	/**
	 * Outlines and/or fills one block. [fullBlock] traces a whole cube, which reads better on slabs
	 * and stairs.
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

	/** Outlines and/or fills any box in world coordinates. */
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
		faces: Int = ALL_FACES,
	) {
		if (!outline && !fill) return

		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)
		if (fill) {
			val layer = if (phase) CrypticRenderLayers.FILLED_THROUGH_WALLS else CrypticRenderLayers.FILLED
			collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
				consumer.addFilledBox(pose, minX, minY, minZ, maxX, maxY, maxZ, fillArgb, faces)
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
	 * A line from the view to a point, starting a little in front of the eyes so the near plane
	 * doesn't clip it.
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

	/** A line through a list of positions, in one submit so nothing is drawn in the middle of it. */
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

	/** One line with a different colour at each end, interpolated by the GPU. */
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

	/** A flat ring around a point, with enough segments to look round at ten blocks. */
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
	 * A short open cylinder: rings at [center] and [height] above, joined at every segment. After
	 * Odin's `drawCylinder`.
	 */
	fun drawCylinder(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		center: Vec3,
		radius: Double,
		height: Double,
		argb: Int,
		lineWidth: Float,
		phase: Boolean,
		segments: Int = 32,
	) {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)

		val layer = if (phase) CrypticRenderLayers.LINES_THROUGH_WALLS else CrypticRenderLayers.LINES
		val cx = center.x.toFloat()
		val cy = center.y.toFloat()
		val cz = center.z.toFloat()
		val top = (center.y + height).toFloat()
		collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
			for (step in 0 until segments) {
				val a1 = step * 2.0 * Math.PI / segments
				val a2 = (step + 1) * 2.0 * Math.PI / segments
				val x1 = cx + (cos(a1) * radius).toFloat()
				val z1 = cz + (sin(a1) * radius).toFloat()
				val x2 = cx + (cos(a2) * radius).toFloat()
				val z2 = cz + (sin(a2) * radius).toFloat()
				consumer.addLine(pose, x1, top, z1, x2, top, z2, argb, lineWidth)
				consumer.addLine(pose, x1, cy, z1, x2, cy, z2, argb, lineWidth)
				if (height > 0.0) consumer.addLine(pose, x1, cy, z1, x1, top, z1, argb, lineWidth)
			}
		}

		poseStack.popPose()
	}

	/**
	 * A filled open cylinder: a wall [height] tall around [center], and a disc across its foot. Either
	 * colour can be fully transparent to leave that part out.
	 */
	fun drawFilledCylinder(
		poseStack: PoseStack,
		collector: SubmitNodeCollector,
		center: Vec3,
		radius: Double,
		height: Double,
		wallArgb: Int,
		floorArgb: Int,
		phase: Boolean,
		segments: Int = 48,
	) {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)
		val layer = if (phase) CrypticRenderLayers.FILLED_THROUGH_WALLS else CrypticRenderLayers.FILLED
		val cx = center.x.toFloat()
		val cy = center.y.toFloat()
		val cz = center.z.toFloat()
		val top = (center.y + height).toFloat()
		collector.submitCustomGeometry(poseStack, layer) { pose, consumer ->
			for (step in 0 until segments) {
				val a1 = step * 2.0 * Math.PI / segments
				val a2 = (step + 1) * 2.0 * Math.PI / segments
				val x1 = cx + (cos(a1) * radius).toFloat()
				val z1 = cz + (sin(a1) * radius).toFloat()
				val x2 = cx + (cos(a2) * radius).toFloat()
				val z2 = cz + (sin(a2) * radius).toFloat()
				// Both windings, so the wall shows from inside and out.
				if ((wallArgb ushr 24) != 0) {
					consumer.addQuad(pose, x1, cy, z1, x2, cy, z2, x2, top, z2, x1, top, z1, wallArgb)
					consumer.addQuad(pose, x1, cy, z1, x1, top, z1, x2, top, z2, x2, cy, z2, wallArgb)
				}
				if ((floorArgb ushr 24) != 0) {
					consumer.addQuad(pose, cx, cy, cz, x2, cy, z2, x1, cy, z1, cx, cy, cz, floorArgb)
					consumer.addQuad(pose, cx, cy, cz, x1, cy, z1, x2, cy, z2, cx, cy, cz, floorArgb)
				}
			}
		}
		poseStack.popPose()
	}

	/**
	 * Text at a world position, facing the camera. [argb] colours parts that have no colour of
	 * their own, and its alpha applies to every part, so a multi-coloured label fades with one
	 * number.
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
		centered: Boolean = false,
	) {
		val font = Minecraft.getInstance().font
		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()

		poseStack.pushPose()
		poseStack.translate(x - camera.x, y - camera.y, z - camera.z)
		poseStack.mulPose(orientation)
		poseStack.scale(TEXT_SCALE * scale, -TEXT_SCALE * scale, TEXT_SCALE * scale)

		val halfWidth = font.width(text) / 2f
		// Text read through walls must also be drawn after ice and glass.
		CrypticRenderLayers.textOverTerrain = seeThrough
		collector.submitText(
			poseStack,
			-halfWidth,
			// Centred, the text's middle sits on the point.
			if (centered) -(font.lineHeight - 1) / 2f else 0f,
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
		faces: Int = ALL_FACES,
	) {
		// A left-out side isn't pushed outwards, or two boxes meeting there overlap and show a
		// line.
		fun out(face: Int) = if ((faces and face) != 0) SURFACE_OFFSET else 0f
		val minX = x1.toFloat() - out(FACE_WEST)
		val minY = y1.toFloat() - out(FACE_DOWN)
		val minZ = z1.toFloat() - out(FACE_NORTH)
		val maxX = x2.toFloat() + out(FACE_EAST)
		val maxY = y2.toFloat() + out(FACE_UP)
		val maxZ = z2.toFloat() + out(FACE_SOUTH)

		if ((faces and FACE_DOWN) != 0) addQuad(pose, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, argb)
		if ((faces and FACE_UP) != 0) addQuad(pose, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, argb)
		if ((faces and FACE_NORTH) != 0) addQuad(pose, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, argb)
		if ((faces and FACE_SOUTH) != 0) addQuad(pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, argb)
		if ((faces and FACE_WEST) != 0) addQuad(pose, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, argb)
		if ((faces and FACE_EAST) != 0) addQuad(pose, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, argb)
	}

	/** The sides of a filled box, for leaving out the ones touching another. */
	const val FACE_DOWN = 1
	const val FACE_UP = 2
	const val FACE_NORTH = 4
	const val FACE_SOUTH = 8
	const val FACE_WEST = 16
	const val FACE_EAST = 32
	const val ALL_FACES = 63

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

	/** One line segment with a colour at each end. */
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
