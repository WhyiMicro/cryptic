package imicro.cryptic.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.PrimitiveTopology
import imicro.cryptic.Cryptic
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import java.util.Optional

/**
 * World-space pipelines Cryptic draws its own geometry with.
 *
 * Vanilla has no depth-less line or box pipeline, so the "through walls" halves
 * are the ordinary ones with the depth-stencil state dropped. Pipelines are
 * collected while the game is starting, so [touch] is called from client init
 * rather than waiting for the first frame that draws one.
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

	/** Loads this object, and with it registers every pipeline above. */
	fun touch() = Unit
}

/** The render types [WorldRender] buffers its geometry into. */
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
}
