package imicro.cryptic.puzzle

import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The few shapes every puzzle solver draws.
 *
 * Odin's solvers each take a style and a colour and hand them to one of three
 * drawing calls; this is that pair of calls, in Cryptic's own renderer. The
 * style is the same three-way choice the rest of Cryptic uses — outline, fill,
 * or both — so a puzzle box looks like every other box the mod draws.
 */
object PuzzleRender {
	const val STYLE_FILLED = 0
	const val STYLE_OUTLINE = 1
	const val STYLE_BOTH = 2

	/** The three-way choice, in the wording every other module uses. */
	val styles = listOf("Filled", "Outline", "Filled + Outline")

	private const val LINE_WIDTH = 2f

	fun box(
		context: LevelRenderContext,
		box: AABB,
		argb: Int,
		style: Int = STYLE_BOTH,
		phase: Boolean = true,
		lineWidth: Float = LINE_WIDTH,
	) {
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = box.minX,
			minY = box.minY,
			minZ = box.minZ,
			maxX = box.maxX,
			maxY = box.maxY,
			maxZ = box.maxZ,
			// An outline at the fill's alpha disappears against it, so it is
			// drawn solid whenever both halves are on.
			outlineArgb = ARGB.opaque(argb),
			fillArgb = argb,
			outline = style != STYLE_FILLED,
			fill = style != STYLE_OUTLINE,
			phase = phase,
			lineWidth = lineWidth,
		)
	}

	fun block(
		context: LevelRenderContext,
		pos: BlockPos,
		argb: Int,
		style: Int = STYLE_BOTH,
		phase: Boolean = true,
	) = box(context, AABB(pos), argb, style, phase)

	/**
	 * Draws the block's own shape rather than the cube it sits in.
	 *
	 * What a solver points at is usually not a whole block — a sign, a button,
	 * a chest — and a cube around one of those marks the wall behind it as
	 * much as the thing itself. An empty space has no shape to draw, so it
	 * falls back to the cube.
	 */
	fun shape(
		context: LevelRenderContext,
		pos: BlockPos,
		argb: Int,
		style: Int = STYLE_BOTH,
		phase: Boolean = true,
		lineWidth: Float = LINE_WIDTH,
	) = WorldRender.drawBlock(
		poseStack = context.poseStack(),
		collector = context.submitNodeCollector(),
		pos = pos,
		outlineArgb = ARGB.opaque(argb),
		fillArgb = argb,
		outline = style != STYLE_FILLED,
		fill = style != STYLE_OUTLINE,
		phase = phase,
		lineWidth = lineWidth,
		fullBlock = false,
	)

	fun line(
		context: LevelRenderContext,
		points: List<Vec3>,
		argb: Int,
		phase: Boolean = true,
		lineWidth: Float = LINE_WIDTH,
	) = WorldRender.drawLine(
		poseStack = context.poseStack(),
		collector = context.submitNodeCollector(),
		points = points,
		argb = argb,
		lineWidth = lineWidth,
		phase = phase,
	)

	fun tracer(
		context: LevelRenderContext,
		target: Vec3,
		argb: Int,
		phase: Boolean = true,
		lineWidth: Float = LINE_WIDTH,
	) = WorldRender.drawTracer(
		poseStack = context.poseStack(),
		collector = context.submitNodeCollector(),
		x = target.x,
		y = target.y,
		z = target.z,
		argb = argb,
		lineWidth = lineWidth,
		phase = phase,
	)

	fun text(context: LevelRenderContext, text: String, at: Vec3, scale: Float = 1f) =
		WorldRender.drawText(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			orientation = Minecraft.getInstance().gameRenderer.mainCamera().rotation(),
			text = Component.literal(text),
			x = at.x,
			y = at.y,
			z = at.z,
			scale = scale,
			seeThrough = true,
		)

	/**
	 * A column of light standing on a block, for an answer that has to be
	 * findable from across a room.
	 *
	 * A tall box rather than a real beacon beam: the beam is a block entity
	 * effect with its own pass, and a box fading out at the top says the same
	 * thing from the same distance.
	 */
	fun beam(context: LevelRenderContext, pos: BlockPos, argb: Int, height: Double = BEAM_HEIGHT) {
		val base = AABB(pos)
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = base.minX + BEAM_INSET,
			minY = base.maxY,
			minZ = base.minZ + BEAM_INSET,
			maxX = base.maxX - BEAM_INSET,
			maxY = base.maxY + height,
			maxZ = base.maxZ - BEAM_INSET,
			outlineArgb = 0,
			fillArgb = argb,
			outline = false,
			fill = true,
			phase = true,
			lineWidth = LINE_WIDTH,
		)
	}

	private const val BEAM_HEIGHT = 12.0
	private const val BEAM_INSET = 0.3
}
