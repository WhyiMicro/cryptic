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
 * Draws players at a size of your choosing, and optionally with the head off.
 *
 * Ported from Athen (BSD 3-Clause, Copyright (c) 2025-2026 Starred), settings
 * and all: who it applies to, the scale itself, and the chibi styles that
 * resize the head against the body rather than with it.
 *
 * Everything here is local. Nobody else sees a resized player, hit boxes do not
 * move, and nothing about how the game is played changes — it is the model and
 * the two things pinned to it, the nametag and the shadow.
 */
object CustomScale {
	/** Indices into [chibiStyle]. */
	private const val CHIBI_NONE = 0
	private const val CHIBI_BIG_HEAD = 1

	/**
	 * Vanilla's own player scale, which the game applies before this does.
	 *
	 * A player model is drawn at fifteen-sixteenths, and a scale meant to read
	 * as "one" has to keep that rather than quietly making everybody bigger.
	 */
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
		description = "Resizes players on your screen",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			applyToSelf, applyToOthers, applyToNpcs,
			scale, scaleNametags, scaleShadow,
			chibiSection, chibiStyle, chibiFactor,
		),
	)

	/**
	 * Whether this player is one of the ones being resized.
	 *
	 * A real account's UUID is random — version 4 — and Hypixel's NPCs are
	 * given ones that are not, which is how Athen tells the two apart and the
	 * only way to without asking the server.
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

	/** The entity a render state was built from, or null if nothing set one. */
	@JvmStatic
	fun entityOf(state: EntityRenderState): Entity? = (state as EntityRenderStateHolder).`cryptic$entity`()

	/** The factor on its own, for the things pinned to the model. */
	@JvmStatic
	fun factor(): Float = scale.value.toFloat()

	/** The model scale to apply, vanilla's own included. */
	@JvmStatic
	fun modelScale(): Float = factor() * VANILLA_PLAYER_SCALE

	/** True while the model itself is being resized, rather than left alone. */
	@JvmStatic
	fun scalesModel(): Boolean = module.enabled && scale.value != 1.0

	@JvmStatic
	fun scalesNametags(): Boolean = module.enabled && scaleNametags.value && scale.value != 1.0

	@JvmStatic
	fun scalesShadow(): Boolean = module.enabled && scaleShadow.value && scale.value != 1.0

	/**
	 * How much bigger the head is than the rest, or null for a head left alone.
	 *
	 * A big head is the factor; a small head is its reciprocal, so the same
	 * slider reads the same way round for both.
	 */
	@JvmStatic
	fun headScale(): Float? {
		if (!module.enabled || chibiStyle.selectedIndex == CHIBI_NONE) return null
		val factor = chibiFactor.value.toFloat()
		return if (chibiStyle.selectedIndex == CHIBI_BIG_HEAD) factor else 1f / factor
	}
}
