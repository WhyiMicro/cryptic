package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * Replaces Minecraft's glow outline with a cleaner one.
 *
 * Vanilla builds the outline by averaging the colours of five samples whether
 * they are lit or not, which leaves an edge pixel at a fraction of its
 * brightness: that is the dark fringe every glow has, and it also washes over
 * the entity's own front. Cryptic ships the same effect with those two things
 * fixed, in `assets/cryptic/post_effect/better_glow.json`, and swaps it in for
 * vanilla's whenever this module is on. The idea and the fix are from the
 * Smoother Glowing resource pack (MIT).
 *
 * The swap happens where the level renderer asks for the chain by name, so it
 * takes effect the moment the module is toggled, with no resource reload.
 */
object BetterGlow {
	private val VANILLA_CHAIN: Identifier = Identifier.withDefaultNamespace("entity_outline")
	private val CRYPTIC_CHAIN: Identifier = Cryptic.id("better_glow")

	/**
	 * The alpha that means no fill. It is one rather than zero because the same
	 * byte tells the shader an entity is there at all, and a glow with nothing
	 * behind it is not a glow.
	 */
	private const val NO_FILL_ALPHA = 1

	private const val MAX_ALPHA = 255

	@JvmField
	val fill = ToggleModuleSetting(
		id = "fill",
		label = "Fill",
		defaultValue = true,
		description = "Washes the glow's color over the entity as well as around it.",
	)

	@JvmField
	val fillOpacity = SliderModuleSetting(
		id = "fill_opacity",
		label = "Fill opacity",
		defaultValue = 28.0,
		min = 0.0,
		max = 100.0,
		step = 1.0,
		visibleIf = { fill.value },
	)

	@JvmField
	val module = Module(
		id = "better_glow",
		name = "Better Glow",
		description = "Tidies up glow outlines",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(fill, fillOpacity),
	)

	/** The post-effect chain to load in place of [id], asked once per frame. */
	@JvmStatic
	fun chainFor(id: Identifier): Identifier =
		if (module.enabled && id == VANILLA_CHAIN) CRYPTIC_CHAIN else id

	/**
	 * Carries the fill strength to the shader in the alpha of [outlineColor].
	 *
	 * A pass's uniforms are read from its file when it loads and there is no
	 * way to set one afterwards, but the outline colour is written per entity
	 * every frame and its alpha is otherwise unused, so the slider rides along
	 * in it. Entities the server makes glow pass through here too, which is why
	 * the whole screen answers to one slider.
	 */
	@JvmStatic
	fun applyFill(outlineColor: Int): Int {
		if (!module.enabled) return outlineColor
		return (fillAlpha() shl 24) or (outlineColor and 0xFFFFFF)
	}

	private fun fillAlpha(): Int {
		if (!fill.value) return NO_FILL_ALPHA
		val span = MAX_ALPHA - NO_FILL_ALPHA
		return NO_FILL_ALPHA + (fillOpacity.value / 100.0 * span).roundToInt().coerceIn(0, span)
	}
}
