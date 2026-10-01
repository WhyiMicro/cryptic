package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft

/**
 * The handful of things the camera does that are worth taking back.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9), minus blindness and
 * nausea, which belong to No Debuff. What is left is the view itself — where
 * the camera sits, how wide it sees, and the overlays painted over it — plus
 * full bright, which used to be a module of its own and is one line of state
 * and a toggle.
 *
 * Nothing here is sent anywhere. Every one of these changes what this client
 * draws and nothing else, which is why the sneak fix is a fix rather than a
 * trick: the server told the client to crouch, and the client stops believing
 * it while going on crouching whenever the player actually does.
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
	 * True while the extracted lightmap already holds Cryptic's values.
	 *
	 * A fully lit lightmap never changes, so the texture is only rebuilt on the
	 * frames where the setting is switched on or off.
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
		label = "No camera clip",
		defaultValue = false,
		description = "Stops the third-person camera being pushed forward by walls.",
	)

	@JvmField
	val customDistance = ToggleModuleSetting(
		id = "custom_distance",
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

	private val fovSection = SectionModuleSetting("fov_section", "Field of view")

	@JvmField
	val customFov = ToggleModuleSetting(
		id = "custom_fov",
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
		description = "Camera distance, view and overlays",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
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

	/** The distance the camera should sit at, or null to leave the game's own. */
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

	/**
	 * How much wider than the settings screen's own the view should be.
	 *
	 * A ratio rather than an angle, because the number the game has by the time
	 * it asks has already been through everything else that touches it — a zoom
	 * most of all. Scaling it keeps those working; replacing it would not.
	 */
	@JvmStatic
	fun fovRatio(): Float {
		if (!module.enabled || !customFov.value) return 1f
		val configured = Minecraft.getInstance().options.fov().get().toFloat()
		if (configured <= 0f) return 1f
		return fov.value.toFloat() / configured
	}
}
