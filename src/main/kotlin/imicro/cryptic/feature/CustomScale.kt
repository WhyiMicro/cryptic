package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.duck.EntityRenderStateHolder
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Draws players at a chosen size, with optional chibi heads. Local only: hitboxes don't move; only
 * the model, nametag and shadow change. Ported from Athen, settings and all.
 */
object CustomScale {
	private const val CHIBI_NONE = 0
	private const val CHIBI_BIG_HEAD = 1

	/** Vanilla draws players at 15/16; "one" keeps that. */
	private const val VANILLA_PLAYER_SCALE = 0.9375f

	@JvmField
	val applyToSelf = ToggleModuleSetting(
		id = "apply_to_self",
		label = "Yourself",
		defaultValue = true,
	)

	@JvmField
	val applyToOthers = ToggleModuleSetting(
		id = "apply_to_others",
		label = "Other players",
		defaultValue = false,
	)

	@JvmField
	val applyToNpcs = ToggleModuleSetting(
		id = "apply_to_npcs",
		label = "NPCs",
		defaultValue = false,
		description = "Hypixel's player-shaped NPCs, told apart by UUID.",
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Scale",
		defaultValue = 1.0,
		min = 0.1,
		max = 5.0,
		step = 0.05,
	)

	@JvmField
	val scaleNametags = ToggleModuleSetting(
		id = "scale_nametags",
		label = "Scale nametags",
		defaultValue = false,
		description = "Moves the name tag with the model, so it still sits over the head.",
	)

	@JvmField
	val scaleShadow = ToggleModuleSetting(
		id = "scale_shadow",
		label = "Scale shadow",
		defaultValue = false,
	)

	private val chibiSection = SectionModuleSetting(id = "chibi_section", label = "Chibi")

	@JvmField
	val chibiStyle = DropdownModuleSetting(
		id = "chibi_style",
		label = "Chibi style",
		options = listOf("None", "Big head", "Small head"),
		defaultIndex = CHIBI_NONE,
		description = "Resizes the head against the body rather than with it.",
	)

	@JvmField
	val chibiFactor = SliderModuleSetting(
		id = "chibi_factor",
		label = "Chibi factor",
		defaultValue = 2.0,
		min = 1.0,
		max = 5.0,
		step = 0.1,
		visibleIf = { chibiStyle.selectedIndex != CHIBI_NONE },
	)

	@JvmField
	val module = Module(
		id = "custom_scale",
		name = "Custom Scale",
		description = "Resizes other players",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(
			applyToSelf, applyToOthers, applyToNpcs,
			scale, scaleNametags, scaleShadow,
			chibiSection, chibiStyle, chibiFactor,
		),
	)

	/**
	 * Whether this player is resized. Real accounts have random (version 4) uuids; Hypixel's NPCs
	 * don't, as Athen tells them apart.
	 */
	@JvmStatic
	fun appliesTo(entity: Entity?): Boolean {
		if (!module.enabled) return false
		return when {
			entity is LocalPlayer -> applyToSelf.value
			entity is Player && entity.uuid.version() == 4 -> applyToOthers.value
			entity is Player -> applyToNpcs.value
			else -> false
		}
	}

	@JvmStatic
	fun entityOf(state: EntityRenderState): Entity? = (state as EntityRenderStateHolder).`cryptic$entity`()

	@JvmStatic
	fun factor(): Float = scale.value.toFloat()

	@JvmStatic
	fun modelScale(): Float = factor() * VANILLA_PLAYER_SCALE

	@JvmStatic
	fun scalesModel(): Boolean = module.enabled && scale.value != 1.0

	@JvmStatic
	fun scalesNametags(): Boolean = module.enabled && scaleNametags.value && scale.value != 1.0

	@JvmStatic
	fun scalesShadow(): Boolean = module.enabled && scaleShadow.value && scale.value != 1.0

	/**
	 * How much bigger the head is than the body, or null. A small head is the reciprocal, so the
	 * slider reads the same both ways.
	 */
	@JvmStatic
	fun headScale(): Float? {
		if (!module.enabled || chibiStyle.selectedIndex == CHIBI_NONE) return null
		val factor = chibiFactor.value.toFloat()
		return if (chibiStyle.selectedIndex == CHIBI_BIG_HEAD) factor else 1f / factor
	}
}
