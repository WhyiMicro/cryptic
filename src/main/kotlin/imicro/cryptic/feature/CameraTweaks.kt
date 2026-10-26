package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.Restrictions
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft

/**
 * Camera tweaks: where it sits, how wide it sees, the overlays over it, and full bright.
 * Client-side only; the sneak fix just stops believing the server's crouch flag. Ported from
 * NoammAddons (blindness and nausea are in No Debuff).
 */
object CameraTweaks {
	private val viewSection = SectionModuleSetting("view_section", "View")

	@JvmField
	val fullbright = ToggleModuleSetting(
		id = "fullbright",
		label = "Full bright",
		defaultValue = false,
		description = "Lights every block as if in daylight.",
	)

	/**
	 * True while the lightmap already holds Cryptic's values, so it's only rebuilt when the setting
	 * changes.
	 */
	@JvmField
	var lightmapApplied = false

	@JvmStatic
	fun isFullbright(): Boolean = module.enabled && fullbright.value

	@JvmField
	val noFrontCamera = ToggleModuleSetting(
		id = "no_front_camera",
		label = "Skip front view",
		defaultValue = false,
		description = "Drops the front view from the F5 cycle.",
	)

	@JvmField
	val noCameraClip = ToggleModuleSetting(
		id = "no_camera_clip",
		label = "Allow camera clipping",
		restriction = Restriction.CHEAT,
		defaultValue = false,
		description = "Stops the third-person camera being pushed forward by walls.",
	)

	@JvmField
	val customDistance = ToggleModuleSetting(
		id = "custom_distance",
		restriction = Restriction.SEMI_LEGIT,
		label = "Custom camera distance",
		defaultValue = false,
	)

	@JvmField
	val distance = SliderModuleSetting(
		id = "distance",
		label = "Distance",
		defaultValue = 4.0,
		min = 1.0,
		max = 10.0,
		step = 0.1,
		visibleIf = { customDistance.value },
	)

	private val fovSection = SectionModuleSetting("fov_section", "Field of view", visibleIf = { Restrictions.allows(Restriction.SEMI_LEGIT) })

	@JvmField
	val customFov = ToggleModuleSetting(
		id = "custom_fov",
		restriction = Restriction.SEMI_LEGIT,
		label = "Custom FOV",
		defaultValue = false,
		description = "Sets a field of view past the slider's limits.",
	)

	@JvmField
	val fov = SliderModuleSetting(
		id = "fov",
		label = "FOV",
		defaultValue = 110.0,
		min = 30.0,
		max = 179.0,
		step = 1.0,
		visibleIf = { customFov.value },
	)

	private val overlaysSection = SectionModuleSetting("overlays_section", "Hide overlays")

	@JvmField
	val hideWaterOverlay = ToggleModuleSetting(
		id = "hide_water_overlay",
		label = "Underwater",
		defaultValue = false,
		description = "The blue wash drawn over the screen while your head is under water.",
	)

	@JvmField
	val hidePortalOverlay = ToggleModuleSetting(
		id = "hide_portal_overlay",
		label = "Portal",
		defaultValue = false,
		description = "The swirl that fills the screen while you stand in a portal.",
	)

	@JvmField
	val hideBlockOverlay = ToggleModuleSetting(
		id = "hide_block_overlay",
		label = "Inside a block",
		defaultValue = false,
		description = "The block texture pasted over the screen when your head is inside one.",
	)

	private val fixesSection = SectionModuleSetting("fixes_section", "Fixes")

	@JvmField
	val doubleSneakFix = ToggleModuleSetting(
		id = "double_sneak_fix",
		label = "Double sneak fix",
		defaultValue = false,
		description = "Stops a sneak needing two presses.",
	)

	@JvmField
	val ridingInputFix = ToggleModuleSetting(
		id = "riding_input_fix",
		label = "Riding input fix",
		defaultValue = false,
		description = "Fixes the mouse lag while riding (MC-206540).",
	)

	private val configurable = listOf(
		fullbright, noFrontCamera, noCameraClip, customDistance, distance, customFov, fov,
		hideWaterOverlay, hidePortalOverlay, hideBlockOverlay, doubleSneakFix, ridingInputFix,
	)

	@JvmField
	val module = Module(
		id = "camera",
		name = "Camera",
		description = "Camera distance and views",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(viewSection, fullbright, noFrontCamera, noCameraClip, customDistance, distance) +
			listOf(fovSection, customFov, fov) +
			listOf(overlaysSection, hideWaterOverlay, hidePortalOverlay, hideBlockOverlay) +
			listOf(fixesSection, doubleSneakFix, ridingInputFix),
	)

	@JvmStatic
	fun skipsFrontCamera(): Boolean = module.enabled && noFrontCamera.value

	@JvmStatic
	fun ignoresWalls(): Boolean = module.enabled && noCameraClip.value

	@JvmStatic
	fun cameraDistance(): Double? =
		if (module.enabled && customDistance.value) distance.value else null

	@JvmStatic
	fun hidesWaterOverlay(): Boolean = module.enabled && hideWaterOverlay.value

	@JvmStatic
	fun hidesPortalOverlay(): Boolean = module.enabled && hidePortalOverlay.value

	@JvmStatic
	fun hidesBlockOverlay(): Boolean = module.enabled && hideBlockOverlay.value

	@JvmStatic
	fun fixesDoubleSneak(): Boolean = module.enabled && doubleSneakFix.value

	@JvmStatic
	fun fixesRidingInput(): Boolean = module.enabled && ridingInputFix.value

	/** A ratio on the game's own FOV, so zoom and anything else touching it keep working. */
	@JvmStatic
	fun fovRatio(): Float {
		if (!module.enabled || !customFov.value) return 1f
		val configured = Minecraft.getInstance().options.fov().get().toFloat()
		if (configured <= 0f) return 1f
		return fov.value.toFloat() / configured
	}
}
