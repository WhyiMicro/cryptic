package imicro.cryptic.feature

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import imicro.cryptic.render.CrypticRenderLayers
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3f
import java.awt.Color
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Block Overlay's Advanced highlight: Custom Block Highlight's, in Cryptic.
 *
 * Ported from Custom Block Highlight (GPL-3.0, Tektonikal): the outline as
 * real line segments that can be cut back from the corners and opened in the
 * middle, each part its own width; colours that run into a second one, or
 * round the rainbow; the faces drawn chosen by what they touch; and the box
 * gliding from block to block, fading and popping in as you look around.
 *
 * Its second and third line layers, model shapes, fluids, entities and
 * presets are left out. Drawn every frame from the block under the crosshair
 * rather than from vanilla's outline event, because a highlight that fades
 * out has to keep drawing after there is no block left to outline.
 */
object BlockHighlight {
	/** The box being drawn, gliding towards the block looked at, in world coordinates. */
	private var box: AABB? = null

	/** The shape last looked at, in the block's own coordinates, and its bounds. */
	private var shape: VoxelShape = Shapes.block()
	private var shapeBounds: AABB = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
	private var faceSet: Int = ALL

	private var alpha = 0f
	private var pop = 0f
	private var lastFrame = 0L

	private const val ALL = 63

	fun render(context: LevelRenderContext) {
		val now = System.nanoTime()
		val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
		lastFrame = now
		if (!BlockOverlay.module.enabled || !BlockOverlay.advanced.value) {
			box = null
			alpha = 0f
			pop = 0f
			return
		}

		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val target = target(client)
		if (target != null) {
			val pos = target.blockPos
			val state = level.getBlockState(pos)
			var local = if (BlockOverlay.fullBlock.value) Shapes.block() else state.getShape(level, pos)
			if (BlockOverlay.connectedChests.value && state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
				val towards = ChestBlock.getConnectedDirection(state)
				val other = level.getBlockState(pos.relative(towards)).getShape(level, pos.relative(towards))
				local = Shapes.or(local, other.move(towards.stepX.toDouble(), towards.stepY.toDouble(), towards.stepZ.toDouble()))
			}
			if (!local.isEmpty) {
				shape = local
				shapeBounds = local.bounds()
				faceSet = faces(target, pos)
				val goal = shapeBounds.move(pos)
				val current = box
				box = if (current == null || !BlockOverlay.easing.value) goal else lerp(current, goal, ease(BlockOverlay.easeSpeed.value, dt))
				if (current == null && BlockOverlay.popIn.value) pop = 0f
			}
		}

		val looking = target != null && box != null
		alpha = when {
			!BlockOverlay.fade.value -> if (looking) 1f else 0f
			else -> approach(alpha, if (looking) 1f else 0f, BlockOverlay.fadeSpeed.value, dt)
		}
		pop = when {
			!BlockOverlay.popIn.value -> 1f
			else -> approach(pop, if (looking) 1f else 0f, BlockOverlay.popSpeed.value, dt)
		}
		val drawn = box ?: return
		if (alpha < 0.004f || pop < 0.004f) {
			if (!looking) box = null
			return
		}
		draw(context, drawn)
	}

	/** The block to highlight, or null when there is none or one should not show. */
	private fun target(client: Minecraft): BlockHitResult? {
		if (client.gui.hud.isHidden) return null
		if (client.gameMode?.playerMode == GameType.SPECTATOR) return null
		if (BlockOverlay.hideWithEtherwarp.value && Etherwarp.isAiming()) return null
		if (GyroHelper.hidesBlockOutline()) return null
		val hit = client.hitResult as? BlockHitResult ?: return null
		if (hit.type != HitResult.Type.BLOCK) return null
		if (client.level?.getBlockState(hit.blockPos)?.isAir != false) return null
		return hit
	}

	/** Which sides are drawn, as a set of [Direction] bits, by the Faces setting. */
	private fun faces(hit: BlockHitResult, pos: BlockPos): Int {
		val level = Minecraft.getInstance().level ?: return ALL
		fun exposed(direction: Direction): Boolean {
			val neighbour = pos.relative(direction)
			return !level.getBlockState(neighbour).isCollisionShapeFullBlock(level, neighbour)
		}
		var bits = 0
		for (direction in Direction.entries) {
			val shown = when (BlockOverlay.faces.selectedIndex) {
				BlockOverlay.FACES_EXPOSED -> exposed(direction)
				BlockOverlay.FACES_LOOKED_AT -> direction == hit.direction
				BlockOverlay.FACES_CONCEALED -> !exposed(direction)
				else -> true
			}
			if (shown) bits = bits or (1 shl direction.ordinal)
		}
		return bits
	}

	// ---- Drawing -----------------------------------------------------------

	private fun draw(context: LevelRenderContext, drawn: AABB) {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		val bounds = sized(drawn)
		val mode = BlockOverlay.mode.selectedIndex
		val outline = mode != 1
		val fill = mode != 0
		val (fillLayer, lineLayer) = when (BlockOverlay.depth.selectedIndex) {
			BlockOverlay.DEPTH_THROUGH -> CrypticRenderLayers.FILLED_THROUGH_WALLS to CrypticRenderLayers.LINES_THROUGH_WALLS
			BlockOverlay.DEPTH_BEHIND -> CrypticRenderLayers.FILLED_BEHIND to CrypticRenderLayers.LINES_BEHIND
			else -> CrypticRenderLayers.FILLED to CrypticRenderLayers.LINES
		}
		val stack: PoseStack = context.poseStack()
		stack.pushPose()
		stack.translate(-camera.x, -camera.y, -camera.z)
		val collector = context.submitNodeCollector()
		val bounding = BlockOverlay.shapeStyle.selectedIndex == BlockOverlay.SHAPE_BOUNDS
		if (fill) {
			val colors = colors(BlockOverlay.fillColor, BlockOverlay.fillColor2)
			collector.submitCustomGeometry(stack, fillLayer) { pose, consumer ->
				val faces = if (bounding) faceSet else ALL
				addFill(consumer, pose, bounds, colors, faces)
			}
		}
		if (outline) {
			val colors = colors(BlockOverlay.outlineColor, BlockOverlay.outlineColor2)
			val width = BlockOverlay.lineWidth.value.toFloat()
			collector.submitCustomGeometry(stack, lineLayer) { pose, consumer ->
				if (bounding) addBoxLines(consumer, pose, bounds, colors, width, faceSet)
				else addShapeLines(consumer, pose, bounds, colors, width)
			}
		}
		stack.popPose()
	}

	/** The box grown by Grow by, scaled by Scale and the pop, about its own centre. */
	private fun sized(box: AABB): AABB {
		val center = box.center
		val scale = BlockOverlay.scalePercent.value / 100.0 * pop
		val grow = BlockOverlay.expandBlocks.value
		val hx = (box.xsize / 2 + grow) * scale
		val hy = (box.ysize / 2 + grow) * scale
		val hz = (box.zsize / 2 + grow) * scale
		return AABB(center.x - hx, center.y - hy, center.z - hz, center.x + hx, center.y + hy, center.z + hz)
	}

	/** Two colours, the corner nearest the box's lowest corner first; equal when there is no gradient. */
	private class Colors(val from: Int, val to: Int, val alpha: Int)

	private fun colors(main: imicro.cryptic.gui.ColorModuleSetting, end: imicro.cryptic.gui.ColorModuleSetting): Colors {
		val alphaOut = ((main.argb ushr 24) * alpha).toInt().coerceIn(0, 255)
		return when {
			BlockOverlay.rainbow.value -> Colors(rainbow(0), rainbow(BlockOverlay.rainbowDelay.value.toLong()), alphaOut)
			BlockOverlay.gradient.value -> Colors(main.rgb, end.rgb, alphaOut)
			else -> Colors(main.rgb, main.rgb, alphaOut)
		}
	}

	/** Custom Block Highlight's rainbow: a hue that turns with the clock, [delay] behind for the far end. */
	private fun rainbow(delay: Long): Int {
		val hue = ((System.currentTimeMillis() + delay) * BlockOverlay.rainbowSpeed.value / 50.0) % 360.0
		return Color.HSBtoRGB(
			(hue / 360.0).toFloat(),
			(BlockOverlay.rainbowSaturation.value / 100.0).toFloat(),
			(BlockOverlay.rainbowBrightness.value / 100.0).toFloat(),
		) and 0xFFFFFF
	}

	/** The colour at a point: how far along the box's diagonal it is, from one colour to the other. */
	private fun colorAt(colors: Colors, box: AABB, x: Float, y: Float, z: Float): Int {
		if (colors.from == colors.to) return colors.from
		val diagonal = sqrt(box.xsize * box.xsize + box.ysize * box.ysize + box.zsize * box.zsize).toFloat().coerceAtLeast(1e-4f)
		val dx = x - box.minX.toFloat()
		val dy = y - box.minY.toFloat()
		val dz = z - box.minZ.toFloat()
		return mix(colors.from, colors.to, (sqrt(dx * dx + dy * dy + dz * dz) / diagonal).coerceIn(0f, 1f))
	}

	private fun mix(a: Int, b: Int, t: Float): Int {
		fun channel(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).toInt().coerceIn(0, 255)
		return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
	}

	/** The faces, each a pair of triangles with a colour at every corner. */
	private fun addFill(consumer: VertexConsumer, pose: PoseStack.Pose, box: AABB, colors: Colors, faces: Int) {
		val x1 = box.minX.toFloat() - SURFACE
		val y1 = box.minY.toFloat() - SURFACE
		val z1 = box.minZ.toFloat() - SURFACE
		val x2 = box.maxX.toFloat() + SURFACE
		val y2 = box.maxY.toFloat() + SURFACE
		val z2 = box.maxZ.toFloat() + SURFACE
		fun quad(vararg corners: Float) {
			fun vertex(i: Int) {
				val x = corners[i * 3]
				val y = corners[i * 3 + 1]
				val z = corners[i * 3 + 2]
				consumer.addVertex(pose, x, y, z).setColor((colors.alpha shl 24) or colorAt(colors, box, x, y, z))
			}
			vertex(0); vertex(1); vertex(2); vertex(0); vertex(2); vertex(3)
		}
		if (has(faces, Direction.DOWN)) quad(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2)
		if (has(faces, Direction.UP)) quad(x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1)
		if (has(faces, Direction.NORTH)) quad(x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1)
		if (has(faces, Direction.SOUTH)) quad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2)
		if (has(faces, Direction.WEST)) quad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1)
		if (has(faces, Direction.EAST)) quad(x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2)
	}

	private fun has(faces: Int, direction: Direction) = faces and (1 shl direction.ordinal) != 0

	/** The twelve edges, each drawn when either face it borders is. */
	private fun addBoxLines(consumer: VertexConsumer, pose: PoseStack.Pose, box: AABB, colors: Colors, width: Float, faces: Int) {
		val x1 = box.minX.toFloat() - SURFACE
		val y1 = box.minY.toFloat() - SURFACE
		val z1 = box.minZ.toFloat() - SURFACE
		val x2 = box.maxX.toFloat() + SURFACE
		val y2 = box.maxY.toFloat() + SURFACE
		val z2 = box.maxZ.toFloat() + SURFACE
		fun edge(a: Direction, b: Direction, sx: Float, sy: Float, sz: Float, ex: Float, ey: Float, ez: Float) {
			if (has(faces, a) || has(faces, b)) line(consumer, pose, box, colors, width, sx, sy, sz, ex, ey, ez)
		}
		val d = Direction.DOWN
		val u = Direction.UP
		val n = Direction.NORTH
		val s = Direction.SOUTH
		val w = Direction.WEST
		val e = Direction.EAST
		edge(d, n, x1, y1, z1, x2, y1, z1); edge(d, s, x1, y1, z2, x2, y1, z2)
		edge(d, w, x1, y1, z1, x1, y1, z2); edge(d, e, x2, y1, z1, x2, y1, z2)
		edge(u, n, x1, y2, z1, x2, y2, z1); edge(u, s, x1, y2, z2, x2, y2, z2)
		edge(u, w, x1, y2, z1, x1, y2, z2); edge(u, e, x2, y2, z1, x2, y2, z2)
		edge(n, w, x1, y1, z1, x1, y2, z1); edge(n, e, x2, y1, z1, x2, y2, z1)
		edge(s, w, x1, y1, z2, x1, y2, z2); edge(s, e, x2, y1, z2, x2, y2, z2)
	}

	/** Every edge of the block's own shape, stretched onto the drawn box so it glides with it. */
	private fun addShapeLines(consumer: VertexConsumer, pose: PoseStack.Pose, box: AABB, colors: Colors, width: Float) {
		val from = shapeBounds
		fun mapX(x: Double) = (box.minX + (x - from.minX) / from.xsize.coerceAtLeast(1e-6) * box.xsize).toFloat()
		fun mapY(y: Double) = (box.minY + (y - from.minY) / from.ysize.coerceAtLeast(1e-6) * box.ysize).toFloat()
		fun mapZ(z: Double) = (box.minZ + (z - from.minZ) / from.zsize.coerceAtLeast(1e-6) * box.zsize).toFloat()
		shape.forAllEdges { ax, ay, az, bx, by, bz ->
			line(consumer, pose, box, colors, width, mapX(ax), mapY(ay), mapZ(az), mapX(bx), mapY(by), mapZ(bz))
		}
	}

	/**
	 * One edge, Custom Block Highlight's way. Uncut, it is a single segment.
	 * Cut from the corners, it is shortened at both ends; cut from the centre,
	 * it splits into two pieces with a gap between them, each going from the
	 * outer width at its corner to the inner width towards the middle.
	 */
	private fun line(
		consumer: VertexConsumer, pose: PoseStack.Pose, box: AABB, colors: Colors, width: Float,
		x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
	) {
		if (colors.alpha < 1) return
		val normal = Vector3f(x2 - x1, y2 - y1, z2 - z1).apply { if (lengthSquared() > 0f) normalize() }
		fun vertex(v: Vector3f, w: Float) {
			consumer.addVertex(pose, v.x, v.y, v.z)
				.setColor((colors.alpha shl 24) or colorAt(colors, box, v.x, v.y, v.z))
				.setNormal(pose, normal)
				.setLineWidth(w.coerceAtLeast(0f))
		}
		val corner = (BlockOverlay.cutFromCorner.value / 100.0).toFloat()
		val center = (BlockOverlay.cutFromCenter.value / 100.0).toFloat()
		val outer = (BlockOverlay.outerThickness.value / 100.0).toFloat()
		val inner = (BlockOverlay.innerThickness.value / 100.0).toFloat()
		val a = Vector3f(x1, y1, z1)
		val b = Vector3f(x2, y2, z2)
		if (corner == 0f && center == 0f) {
			vertex(a, width); vertex(b, width)
			return
		}
		val minOuter = Vector3f(a).lerp(b, corner / 2f)
		val maxOuter = Vector3f(b).lerp(a, corner / 2f)
		if (center == 0f) {
			// Only shortened: one segment, at the outer width.
			vertex(minOuter, width * outer); vertex(maxOuter, width * outer)
			return
		}
		val middle = Vector3f(a).lerp(b, 0.5f)
		val minInner = Vector3f(middle).lerp(a, center)
		val maxInner = Vector3f(middle).lerp(b, center)
		vertex(minOuter, width * outer); vertex(minInner, width * inner)
		vertex(maxInner, width * inner); vertex(maxOuter, width * outer)
	}

	// ---- Motion ------------------------------------------------------------

	/** The share of the way to go this frame, for a smooth chase at [speed]. */
	private fun ease(speed: Double, dt: Float): Double = 1.0 - exp(-speed * dt)

	private fun approach(current: Float, target: Float, speed: Double, dt: Float): Float =
		(current + (target - current) * ease(speed, dt)).toFloat().let { if (kotlin.math.abs(it - target) < 0.002f) target else it }

	private fun lerp(from: AABB, to: AABB, t: Double): AABB = AABB(
		from.minX + (to.minX - from.minX) * t, from.minY + (to.minY - from.minY) * t, from.minZ + (to.minZ - from.minZ) * t,
		from.maxX + (to.maxX - from.maxX) * t, from.maxY + (to.maxY - from.maxY) * t, from.maxZ + (to.maxZ - from.maxZ) * t,
	)

	/** How far outside the block the box is drawn, so it does not flicker against the block's own faces. */
	private const val SURFACE = 0.002f
}
