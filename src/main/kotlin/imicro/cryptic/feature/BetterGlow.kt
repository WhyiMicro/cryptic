package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * Replaces Minecraft's glow outline with a cleaner one. Vanilla averages five samples lit or not,
 * which darkens the edge and washes over the entity. Cryptic ships a fixed copy
 * (`assets/cryptic/post_effect/better_glow.json`, the idea and fix from the Smoother Glowing pack)
 * and swaps it in by name, so toggling needs no resource reload.
 */
object BetterGlow {
	private val VANILLA_CHAIN: Identifier = Identifier.withDefaultNamespace("entity_outline")
	private val CRYPTIC_CHAIN: Identifier = Cryptic.id("better_glow")

	/**
	 * The alpha meaning no fill. One, not zero, because the same byte tells the shader an entity is
	 * there.
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
		supportsKeybind = false,
		settings = listOf(fill, fillOpacity),
	)

	@JvmStatic
	fun chainFor(id: Identifier): Identifier =
		if (module.enabled && id == VANILLA_CHAIN) CRYPTIC_CHAIN else id

	/**
	 * Passes the fill strength to the shader in the outline colour's unused alpha, since a pass's
	 * uniforms can't be changed after loading. Server-made glows pass through here too.
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
