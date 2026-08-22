package imicro.cryptic.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Draws boxes in world space, for modules that highlight a position.
 *
 * Everything here is submitted during a level-render event, where the pose
 * stack is still camera-relative, so each call translates by the negated
 * camera position before emitting absolute world coordinates.
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
		consumers: MultiBufferSource,
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
			poseStack, consumers,
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
		consumers: MultiBufferSource,
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

		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position()
		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)
		val pose = poseStack.last()

		if (fill) {
			val layer = if (phase) CrypticRenderLayers.FILLED_THROUGH_WALLS else CrypticRenderLayers.FILLED
			consumers.getBuffer(layer).addFilledBox(pose, minX, minY, minZ, maxX, maxY, maxZ, fillArgb)
		}

		if (outline) {
			val layer = if (phase) CrypticRenderLayers.LINES_THROUGH_WALLS else CrypticRenderLayers.LINES
			consumers.getBuffer(layer).addBoxOutline(pose, minX, minY, minZ, maxX, maxY, maxZ, outlineArgb, lineWidth)
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
		consumers: MultiBufferSource,
		x: Double,
		y: Double,
		z: Double,
		argb: Int,
		lineWidth: Float,
		phase: Boolean,
	) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val camera = client.gameRenderer.mainCamera.position()
		val look = player.lookAngle

		val start = camera.add(look.scale(0.5))

		poseStack.pushPose()
		poseStack.translate(-camera.x, -camera.y, -camera.z)

		val layer = if (phase) CrypticRenderLayers.LINES_THROUGH_WALLS else CrypticRenderLayers.LINES
		consumers.getBuffer(layer).addLine(
			poseStack.last(),
			start.x.toFloat(), start.y.toFloat(), start.z.toFloat(),
			x.toFloat(), y.toFloat(), z.toFloat(),
			argb,
			lineWidth,
		)

		poseStack.popPose()
	}

	/**
	 * Draws text that stands at a world position and faces the camera.
	 *
	 * The camera's own orientation is applied so the label stays readable from
	 * any angle, and the Y scale is negated because text is laid out downwards
	 * while world space grows upwards.
	 */
	fun drawText(
		poseStack: PoseStack,
		consumers: MultiBufferSource,
		orientation: Quaternionf,
		text: Component,
		x: Double,
		y: Double,
		z: Double,
		scale: Float,
		seeThrough: Boolean,
		dropShadow: Boolean = true,
		backgroundArgb: Int = 0,
	) {
		val font = Minecraft.getInstance().font
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position()

		poseStack.pushPose()
		poseStack.translate(x - camera.x, y - camera.y, z - camera.z)
		poseStack.mulPose(orientation)
		poseStack.scale(TEXT_SCALE * scale, -TEXT_SCALE * scale, TEXT_SCALE * scale)

		val halfWidth = font.width(text) / 2f
		font.drawInBatch(
			text,
			-halfWidth,
			0f,
			0xFFFFFFFF.toInt(),
			dropShadow,
			poseStack.last().pose(),
			consumers,
			if (seeThrough) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL,
			backgroundArgb,
			FULL_BRIGHT_LIGHT,
		)
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
	) {
		val normal = Vector3f(x2 - x1, y2 - y1, z2 - z1).apply { if (lengthSquared() > 0f) normalize() }
		addVertex(pose, x1, y1, z1).setColor(argb).setNormal(pose, normal).setLineWidth(lineWidth)
		addVertex(pose, x2, y2, z2).setColor(argb).setNormal(pose, normal).setLineWidth(lineWidth)
	}
}
