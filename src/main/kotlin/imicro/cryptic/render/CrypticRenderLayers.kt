package imicro.cryptic.render

import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.PrimitiveTopology
import imicro.cryptic.Cryptic
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import java.util.Optional

/**
 * World pipelines for Cryptic's own geometry. The "through walls" ones are vanilla's without depth
 * testing. [touch] registers them during client init.
 */
object CrypticRenderPipelines {
	val FILLED: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
			withLocation(Cryptic.id("pipeline/filled"))
			withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		}.build(),
	)

	val FILLED_THROUGH_WALLS: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
			withLocation(Cryptic.id("pipeline/filled_through_walls"))
			withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			withDepthStencilState(Optional.empty())
		}.build(),
	)

	val LINES: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).apply {
			withLocation(Cryptic.id("pipeline/lines"))
		}.build(),
	)

	val LINES_THROUGH_WALLS: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).apply {
			withLocation(Cryptic.id("pipeline/lines_through_walls"))
			withDepthStencilState(Optional.empty())
		}.build(),
	)

	/**
	 * Drawn only where something is in front. Depth is reversed in 26.x (nearer is greater), so
	 * "behind" is less-than.
	 */
	val FILLED_BEHIND: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
			withLocation(Cryptic.id("pipeline/filled_behind"))
			withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN, false))
		}.build(),
	)

	val LINES_BEHIND: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).apply {
			withLocation(Cryptic.id("pipeline/lines_behind"))
			withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN, false))
		}.build(),
	)

	fun touch() = Unit
}

object CrypticRenderLayers {
	val FILLED: RenderType = RenderType.create(
		"cryptic_filled",
		RenderSetup.builder(CrypticRenderPipelines.FILLED).createRenderSetup(),
	)

	val FILLED_THROUGH_WALLS: RenderType = RenderType.create(
		"cryptic_filled_through_walls",
		RenderSetup.builder(CrypticRenderPipelines.FILLED_THROUGH_WALLS).createRenderSetup(),
	)

	val LINES: RenderType = RenderType.create(
		"cryptic_lines",
		RenderSetup.builder(CrypticRenderPipelines.LINES).createRenderSetup(),
	)

	val LINES_THROUGH_WALLS: RenderType = RenderType.create(
		"cryptic_lines_through_walls",
		RenderSetup.builder(CrypticRenderPipelines.LINES_THROUGH_WALLS).createRenderSetup(),
	)

	val FILLED_BEHIND: RenderType = RenderType.create(
		"cryptic_filled_behind",
		RenderSetup.builder(CrypticRenderPipelines.FILLED_BEHIND).createRenderSetup(),
	)

	val LINES_BEHIND: RenderType = RenderType.create(
		"cryptic_lines_behind",
		RenderSetup.builder(CrypticRenderPipelines.LINES_BEHIND).createRenderSetup(),
	)

	/**
	 * Whether this layer is drawn last. Asked for every custom geometry submit, so it is just two
	 * identity checks.
	 */
	@JvmStatic
	fun drawsOverTerrain(type: RenderType): Boolean =
		type === FILLED_THROUGH_WALLS || type === LINES_THROUGH_WALLS || type === FILLED_BEHIND || type === LINES_BEHIND

	/** Set around one text submit that should be read through what's in front of it. */
	@JvmStatic
	var textOverTerrain: Boolean = false

	@JvmStatic
	fun textDrawsOverTerrain(): Boolean = textOverTerrain
}
