package imicro.cryptic.feature

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import imicro.cryptic.gui.ColorModuleSetting
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
import java.awt.Color
import kotlin.math.abs
import kotlin.math.exp

/**
 * Block Overlay's Advanced highlight, drawn every frame from the block under the crosshair so it
 * can fade out after the block is gone. The box glides to each new block, fades, and can pop in.
 *
 * Each edge is drawn as visible stretches (whole, cut back from the corners, or split around a
 * middle gap), each tapering from the corner's width to the middle's. Colours can run across the
 * box along its diagonal, or cycle the rainbow. Inspired by Custom Block Highlight.
 */
object BlockHighlight {
	private var drawn: AABB? = null

	/** The shape last looked at, in its block's coordinates, plus its bounds and the faces to draw. */
	private var shape: VoxelShape = Shapes.block()
	private var shapeBounds = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
	private var faceBits = ALL_FACES

	/** How faded in and grown in the box is. */
	private var opacity = 0f
	private var growth = 0f
	private var lastFrame = 0L

	private const val ALL_FACES = 0b111111

	fun render(context: LevelRenderContext) {
		val dt = frameSeconds()
		if (!BlockOverlay.module.enabled || !BlockOverlay.advanced.value) {
			drawn = null
			opacity = 0f
			growth = 0f
			return
		}

		val client = Minecraft.getInstance()
		val hit = lookedAt(client)
		if (hit != null) follow(client, hit, dt)

		val looking = hit != null && drawn != null
		val goal = if (looking) 1f else 0f
		opacity = if (BlockOverlay.fade.value) settle(opacity, goal, BlockOverlay.fadeSpeed.value, dt) else goal
		growth = if (BlockOverlay.popIn.value) settle(growth, goal, BlockOverlay.popSpeed.value, dt) else 1f

		val box = drawn ?: return
		if (opacity < VISIBLE || growth < VISIBLE) {
			if (!looking) drawn = null
			return
		}
		paint(context, box)
	}

	private fun frameSeconds(): Float {
		val now = System.nanoTime()
		val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
		lastFrame = now
		return dt
	}

	/** The block to highlight, or null when there's none or the highlight should step aside. */
	private fun lookedAt(client: Minecraft): BlockHitResult? {
		if (client.gui.hud.isHidden || client.gameMode?.playerMode == GameType.SPECTATOR) return null
		if (Etherwarp.overlayShown) return null
		if (GyroHelper.hidesBlockOutline()) return null
		val hit = (client.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK } ?: return null
		if (Mining.hidesOutline(hit.blockPos)) return null
		return hit.takeIf { client.level?.getBlockState(it.blockPos)?.isAir == false }
	}

	/** Takes the shape at [hit] and moves the box toward it. */
	private fun follow(client: Minecraft, hit: BlockHitResult, dt: Float) {
		val level = client.level ?: return
		val pos = hit.blockPos
		val state = level.getBlockState(pos)
		var local = if (BlockOverlay.fullBlock.value) Shapes.block() else state.getShape(level, pos)
		// A double chest is one thing: its other half joins the shape.
		if (BlockOverlay.connectedChests.value && state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			val side = ChestBlock.getConnectedDirection(state)
			val other = pos.relative(side)
			local = Shapes.or(local, level.getBlockState(other).getShape(level, other).move(side.stepX.toDouble(), side.stepY.toDouble(), side.stepZ.toDouble()))
		}
		if (local.isEmpty) return

		shape = local
		shapeBounds = local.bounds()
		faceBits = facesToDraw(hit, pos)
		val target = shapeBounds.move(pos)
		val from = drawn
		drawn = when {
			from == null || !BlockOverlay.easing.value -> target
			else -> blend(from, target, 1.0 - exp(-BlockOverlay.easeSpeed.value * dt))
		}
		if (from == null && BlockOverlay.popIn.value) growth = 0f
	}

	/** The sides Faces asks for, as bits by [Direction] ordinal. */
	private fun facesToDraw(hit: BlockHitResult, pos: BlockPos): Int {
		val level = Minecraft.getInstance().level ?: return ALL_FACES
		val mode = BlockOverlay.faces.selectedIndex
		if (mode == BlockOverlay.FACES_ALL) return ALL_FACES
		var bits = 0
		for (side in Direction.entries) {
			val next = pos.relative(side)
			val open = !level.getBlockState(next).isCollisionShapeFullBlock(level, next)
			val wanted = when (mode) {
				BlockOverlay.FACES_EXPOSED -> open
				BlockOverlay.FACES_CONCEALED -> !open
				else -> side == hit.direction
			}
			if (wanted) bits = bits or (1 shl side.ordinal)
		}
		return bits
	}

	private class Palette(val start: Int, val end: Int, val alpha: Int) {
		val flat get() = start == end
	}

	private fun palette(main: ColorModuleSetting, second: ColorModuleSetting): Palette {
		val alpha = ((main.argb ushr 24) * opacity).toInt().coerceIn(0, 255)
		return when {
			BlockOverlay.rainbow.value -> Palette(hueAt(0L), hueAt(BlockOverlay.rainbowDelay.value.toLong()), alpha)
			BlockOverlay.gradient.value -> Palette(main.rgb, second.rgb, alpha)
			else -> Palette(main.rgb, main.rgb, alpha)
		}
	}

	/** The rainbow colour [lagMillis] behind now, at the chosen speed, saturation and brightness. */
	private fun hueAt(lagMillis: Long): Int {
		val turns = (System.currentTimeMillis() - lagMillis) / 1000.0 * BlockOverlay.rainbowSpeed.value * RAINBOW_DEGREES_PER_SECOND / 360.0
		return Color.HSBtoRGB(
			(turns - kotlin.math.floor(turns)).toFloat(),
			(BlockOverlay.rainbowSaturation.value / 100.0).toFloat(),
			(BlockOverlay.rainbowBrightness.value / 100.0).toFloat(),
		) and 0xFFFFFF
	}

	private fun paint(context: LevelRenderContext, box: AABB) {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		val bounds = resized(box)
		val (fillLayer, lineLayer) = when (BlockOverlay.depth.selectedIndex) {
			BlockOverlay.DEPTH_THROUGH -> CrypticRenderLayers.FILLED_THROUGH_WALLS to CrypticRenderLayers.LINES_THROUGH_WALLS
			BlockOverlay.DEPTH_BEHIND -> CrypticRenderLayers.FILLED_BEHIND to CrypticRenderLayers.LINES_BEHIND
			else -> CrypticRenderLayers.FILLED to CrypticRenderLayers.LINES
		}
		val wholeShape = BlockOverlay.shapeStyle.selectedIndex != BlockOverlay.SHAPE_BOUNDS
		val gradient = Gradient(bounds)

		val stack = context.poseStack()
		stack.pushPose()
		stack.translate(-camera.x, -camera.y, -camera.z)
		val collector = context.submitNodeCollector()
		if (BlockOverlay.mode.selectedIndex != MODE_OUTLINE_ONLY) {
			val colors = palette(BlockOverlay.fillColor, BlockOverlay.fillColor2)
			collector.submitCustomGeometry(stack, fillLayer) { pose, out ->
				if (wholeShape) fillShape(out, pose, bounds, colors, gradient) else fillFaces(out, pose, bounds, faceBits, colors, gradient)
			}
		}
		if (BlockOverlay.mode.selectedIndex != MODE_FILL_ONLY) {
			val colors = palette(BlockOverlay.outlineColor, BlockOverlay.outlineColor2)
			val width = BlockOverlay.lineWidth.value.toFloat()
			collector.submitCustomGeometry(stack, lineLayer) { pose, out ->
				val pen = Pen(out, pose, colors, gradient, width)
				if (wholeShape) shapeEdges(pen, bounds) else boxEdges(pen, bounds, faceBits)
			}
		}
		stack.popPose()
	}

	private fun resized(box: AABB): AABB {
		if (growth >= 1.0) return box
		val c = box.center
		val hx = box.xsize / 2 * growth
		val hy = box.ysize / 2 * growth
		val hz = box.zsize / 2 * growth
		return AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz)
	}

	/**
	 * Where a point falls between the colours: its position along the box's diagonal, 0 at the
	 * lowest corner to 1 at the highest.
	 */
	private class Gradient(box: AABB) {
		private val ox = box.minX.toFloat()
		private val oy = box.minY.toFloat()
		private val oz = box.minZ.toFloat()
		private val dx = box.xsize.toFloat()
		private val dy = box.ysize.toFloat()
		private val dz = box.zsize.toFloat()
		private val lengthSquared = (dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-8f)

		fun at(x: Float, y: Float, z: Float): Float =
			(((x - ox) * dx + (y - oy) * dy + (z - oz) * dz) / lengthSquared).coerceIn(0f, 1f)
	}

	private fun color(palette: Palette, t: Float): Int {
		if (palette.flat) return (palette.alpha shl 24) or palette.start
		fun channel(shift: Int): Int {
			val a = palette.start shr shift and 0xFF
			val b = palette.end shr shift and 0xFF
			return (a + (b - a) * t).toInt().coerceIn(0, 255) shl shift
		}
		return (palette.alpha shl 24) or channel(16) or channel(8) or channel(0)
	}

	/** Each requested face as two triangles, coloured per corner. */
	private fun fillFaces(out: VertexConsumer, pose: PoseStack.Pose, box: AABB, faces: Int, palette: Palette, gradient: Gradient) {
		if (palette.alpha == 0) return
		val lo = floatArrayOf(box.minX.toFloat() - LIFT, box.minY.toFloat() - LIFT, box.minZ.toFloat() - LIFT)
		val hi = floatArrayOf(box.maxX.toFloat() + LIFT, box.maxY.toFloat() + LIFT, box.maxZ.toFloat() + LIFT)
		for (side in Direction.entries) {
			if (faces and (1 shl side.ordinal) == 0) continue
			val corners = FACE_CORNERS[side.ordinal]
			fun put(corner: Int) {
				val x = if (corners[corner] and 4 != 0) hi[0] else lo[0]
				val y = if (corners[corner] and 2 != 0) hi[1] else lo[1]
				val z = if (corners[corner] and 1 != 0) hi[2] else lo[2]
				out.addVertex(pose, x, y, z).setColor(color(palette, gradient.at(x, y, z)))
			}
			put(0); put(1); put(2)
			put(0); put(2); put(3)
		}
	}

	/**
	 * The outside of the block's own shape, stretched onto the drawn box. The shape is cut into the
	 * grid its corners make, and a cell's side is drawn only where its neighbour is empty, so a
	 * stair fills as a stair with no doubled inner faces.
	 */
	private fun fillShape(out: VertexConsumer, pose: PoseStack.Pose, box: AABB, palette: Palette, gradient: Gradient) {
		if (palette.alpha == 0) return
		val from = shapeBounds
		val parts = shape.toAabbs()
		val grid = Direction.Axis.entries.map { shape.getCoords(it).toDoubleArray() }
		val (xs, ys, zs) = grid
		if (xs.size < 2 || ys.size < 2 || zs.size < 2) return
		val cx = xs.size - 1
		val cy = ys.size - 1
		val cz = zs.size - 1
		val filled = BooleanArray(cx * cy * cz)
		for (i in 0 until cx) for (j in 0 until cy) for (k in 0 until cz) {
			val mx = (xs[i] + xs[i + 1]) / 2
			val my = (ys[j] + ys[j + 1]) / 2
			val mz = (zs[k] + zs[k + 1]) / 2
			filled[(i * cy + j) * cz + k] = parts.any { mx > it.minX && mx < it.maxX && my > it.minY && my < it.maxY && mz > it.minZ && mz < it.maxZ }
		}
		fun at(i: Int, j: Int, k: Int) = i in 0 until cx && j in 0 until cy && k in 0 until cz && filled[(i * cy + j) * cz + k]
		fun onto(value: Double, min: Double, size: Double, newMin: Double, newSize: Double) =
			(newMin + (value - min) / size.coerceAtLeast(1e-6) * newSize).toFloat()

		val lo = FloatArray(3)
		val hi = FloatArray(3)
		for (i in 0 until cx) for (j in 0 until cy) for (k in 0 until cz) {
			if (!at(i, j, k)) continue
			for (side in Direction.entries) {
				if (at(i + side.stepX, j + side.stepY, k + side.stepZ)) continue
				lo[0] = onto(xs[i], from.minX, from.xsize, box.minX, box.xsize)
				hi[0] = onto(xs[i + 1], from.minX, from.xsize, box.minX, box.xsize)
				lo[1] = onto(ys[j], from.minY, from.ysize, box.minY, box.ysize)
				hi[1] = onto(ys[j + 1], from.minY, from.ysize, box.minY, box.ysize)
				lo[2] = onto(zs[k], from.minZ, from.zsize, box.minZ, box.zsize)
				hi[2] = onto(zs[k + 1], from.minZ, from.zsize, box.minZ, box.zsize)
				// Lifted along its own direction only, so neighbouring quads meet without
				// overlapping.
				val axis = side.axis.ordinal
				if (side.axisDirection == Direction.AxisDirection.POSITIVE) hi[axis] += LIFT else lo[axis] -= LIFT
				val corners = FACE_CORNERS[side.ordinal]
				fun put(corner: Int) {
					val x = if (corners[corner] and 4 != 0) hi[0] else lo[0]
					val y = if (corners[corner] and 2 != 0) hi[1] else lo[1]
					val z = if (corners[corner] and 1 != 0) hi[2] else lo[2]
					out.addVertex(pose, x, y, z).setColor(color(palette, gradient.at(x, y, z)))
				}
				put(0); put(1); put(2)
				put(0); put(2); put(3)
			}
		}
	}

	/** Shared edge drawing state: width, colours and gradient. */
	private class Pen(
		val out: VertexConsumer,
		val pose: PoseStack.Pose,
		val palette: Palette,
		val gradient: Gradient,
		val width: Float,
	)

	/** The box's twelve edges, each drawn when a face beside it is. */
	private fun boxEdges(pen: Pen, box: AABB, faces: Int) {
		val lo = floatArrayOf(box.minX.toFloat() - LIFT, box.minY.toFloat() - LIFT, box.minZ.toFloat() - LIFT)
		val hi = floatArrayOf(box.maxX.toFloat() + LIFT, box.maxY.toFloat() + LIFT, box.maxZ.toFloat() + LIFT)
		for ((from, to, sides) in BOX_EDGES) {
			if (faces and sides == 0) continue
			fun x(c: Int) = if (c and 4 != 0) hi[0] else lo[0]
			fun y(c: Int) = if (c and 2 != 0) hi[1] else lo[1]
			fun z(c: Int) = if (c and 1 != 0) hi[2] else lo[2]
			edge(pen, x(from), y(from), z(from), x(to), y(to), z(to))
		}
	}

	/** Every edge of the block's own shape, stretched onto the drawn box. */
	private fun shapeEdges(pen: Pen, box: AABB) {
		val from = shapeBounds
		fun onto(value: Double, min: Double, size: Double, newMin: Double, newSize: Double) =
			(newMin + (value - min) / size.coerceAtLeast(1e-6) * newSize).toFloat()
		shape.forAllEdges { ax, ay, az, bx, by, bz ->
			edge(
				pen,
				onto(ax, from.minX, from.xsize, box.minX, box.xsize), onto(ay, from.minY, from.ysize, box.minY, box.ysize), onto(az, from.minZ, from.zsize, box.minZ, box.zsize),
				onto(bx, from.minX, from.xsize, box.minX, box.xsize), onto(by, from.minY, from.ysize, box.minY, box.ysize), onto(bz, from.minZ, from.zsize, box.minZ, box.zsize),
			)
		}
	}

	/**
	 * One edge from a to b, as its visible stretches. With 0 and 1 its corners, a corner cut c
	 * leaves [c/2, 1 - c/2] and a middle gap g removes (1/2 - g/2, 1/2 + g/2). Each stretch tapers
	 * from the corner width to the middle width.
	 */
	private fun edge(pen: Pen, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
		if (pen.palette.alpha == 0) return
		val corner = (BlockOverlay.cutFromCorner.value / 100.0).toFloat()
		val gap = (BlockOverlay.cutFromCenter.value / 100.0).toFloat()
		val outer = if (corner == 0f && gap == 0f) 1f else (BlockOverlay.outerThickness.value / 100.0).toFloat()
		val inner = (BlockOverlay.innerThickness.value / 100.0).toFloat()

		val dx = bx - ax
		val dy = by - ay
		val dz = bz - az
		val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
		val nx = dx / length
		val ny = dy / length
		val nz = dz / length

		fun point(t: Float, widthShare: Float) {
			val x = ax + dx * t
			val y = ay + dy * t
			val z = az + dz * t
			pen.out.addVertex(pen.pose, x, y, z)
				.setColor(color(pen.palette, pen.gradient.at(x, y, z)))
				.setNormal(pen.pose, nx, ny, nz)
				.setLineWidth((pen.width * widthShare).coerceAtLeast(0f))
		}

		val start = corner / 2f
		val end = 1f - corner / 2f
		if (gap == 0f) {
			point(start, outer)
			point(end, outer)
			return
		}
		val gapStart = 0.5f - gap / 2f
		val gapEnd = 0.5f + gap / 2f
		if (gapStart > start) {
			point(start, outer)
			point(gapStart, inner)
		}
		if (end > gapEnd) {
			point(gapEnd, inner)
			point(end, outer)
		}
	}

	/** [current] moved toward [goal] at [speed] over [dt], snapping once close. */
	private fun settle(current: Float, goal: Float, speed: Double, dt: Float): Float {
		val next = (current + (goal - current) * (1.0 - exp(-speed * dt))).toFloat()
		return if (abs(next - goal) < 0.002f) goal else next
	}

	private fun blend(from: AABB, to: AABB, t: Double): AABB = AABB(
		from.minX + (to.minX - from.minX) * t, from.minY + (to.minY - from.minY) * t, from.minZ + (to.minZ - from.minZ) * t,
		from.maxX + (to.maxX - from.maxX) * t, from.maxY + (to.maxY - from.maxY) * t, from.maxZ + (to.maxZ - from.maxZ) * t,
	)

	/** Box corners as three bits, x then y then z: 0 lowest, 7 highest. */
	private val FACE_CORNERS: Array<IntArray> = arrayOf(
		intArrayOf(0b000, 0b100, 0b101, 0b001),
		intArrayOf(0b010, 0b011, 0b111, 0b110),
		intArrayOf(0b000, 0b010, 0b110, 0b100),
		intArrayOf(0b001, 0b101, 0b111, 0b011),
		intArrayOf(0b000, 0b001, 0b011, 0b010),
		intArrayOf(0b100, 0b110, 0b111, 0b101),
	)

	/** The twelve edges: two corners each, and the bits of the two faces beside it. */
	private val BOX_EDGES: List<Triple<Int, Int, Int>> = run {
		fun faces(vararg sides: Direction) = sides.fold(0) { bits, side -> bits or (1 shl side.ordinal) }
		val (d, u, n, s, w, e) = listOf(Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
		listOf(
			Triple(0b000, 0b100, faces(d, n)), Triple(0b001, 0b101, faces(d, s)),
			Triple(0b000, 0b001, faces(d, w)), Triple(0b100, 0b101, faces(d, e)),
			Triple(0b010, 0b110, faces(u, n)), Triple(0b011, 0b111, faces(u, s)),
			Triple(0b010, 0b011, faces(u, w)), Triple(0b110, 0b111, faces(u, e)),
			Triple(0b000, 0b010, faces(n, w)), Triple(0b100, 0b110, faces(n, e)),
			Triple(0b001, 0b011, faces(s, w)), Triple(0b101, 0b111, faces(s, e)),
		)
	}

	private operator fun <T> List<T>.component6(): T = this[5]

	private const val MODE_OUTLINE_ONLY = 0
	private const val MODE_FILL_ONLY = 1

	private const val RAINBOW_DEGREES_PER_SECOND = 20.0

	/** Below this, faded or shrunk, the box isn't drawn. */
	private const val VISIBLE = 0.004f

	/** How far outside the shape the box is drawn, to avoid z-fighting. */
	private const val LIFT = 0.002f
}
