package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import kotlin.math.ln
import kotlin.math.pow

/**
 * Narrows the view while a key is held, like a spyglass. The options are modelled on Zoomify (not
 * ported): a key zoom, the wheel to go further, an easing curve, and whether the hand follows. The
 * zoom is a pure function of the clock and the running transition, so both render hooks get the
 * same answer in a frame.
 */
object Zoom {
	private const val TRANSITION_NANOS = 200_000_000.0

	private const val SCROLL_STEP = 1.2

	/** 1x is the untouched view, and the floor for both zooms. */
	private const val NO_ZOOM = 1.0

	/** The wheel's limit, beyond the slider's reach. */
	private const val MAX_SCROLL_ZOOM = 20.0

	@JvmField
	val initialZoom = SliderModuleSetting(
		id = "initial_zoom",
		label = "Initial zoom",
		defaultValue = 4.0,
		min = 1.0,
		max = 5.0,
		step = 0.1,
		description = "How far the key alone zooms. At 1x it is as if you never zoomed.",
	)

	@JvmField
	val transition = DropdownModuleSetting(
		id = "transition",
		label = "Transition",
		options = listOf("Instant", "Linear", "Ease in", "Ease out", "Ease in and out"),
		defaultIndex = 3,
		description = "The curve the zoom follows on the way in and back out.",
	)

	@JvmField
	val scrollZoom = ToggleModuleSetting(
		id = "scroll_zoom",
		label = "Scroll to zoom",
		defaultValue = true,
		description = "The wheel zooms further in or out while the key is held, anywhere from 1x to 20x.",
	)

	@JvmField
	val cinematicCamera = ToggleModuleSetting(
		id = "cinematic_camera",
		label = "Cinematic camera",
		description = "Smooths the mouse while zoomed, so the view glides instead of snapping.",
	)

	@JvmField
	val affectHandFov = ToggleModuleSetting(
		id = "affect_hand_fov",
		label = "Affect hand FOV",
		description = "Zooms the held item along with the view, instead of leaving it the size it was.",
	)

	/** Minecraft's own key mapping, so the controls screen can rebind it. Unbound by default. */
	private val zoomKeybind = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.zoomKey.isUnbound) {
				"None"
			} else {
				CrypticClient.zoomKey.translatedKeyMessage.string.uppercase()
			}
		},
		onKeyChanged = { key ->
			CrypticClient.zoomKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "zoom",
		name = "Zoom",
		description = "Zooms while a key is held",
		category = ModuleCategory.GENERAL,
		supportsKeybind = true,
		keybind = zoomKeybind,
		settings = listOf(initialZoom, transition, scrollZoom, cinematicCamera, affectHandFov),
	)

	private var scrolled = 0.0

	/** The zoom the transition heads for, and where it started. */
	private var target = NO_ZOOM
	private var from = NO_ZOOM
	private var startedAt = 0L

	/** True while Cryptic is the reason the cinematic camera is on. */
	private var forcingSmoothCamera = false
	private var smoothCameraWas = false

	private var wasHeld = false

	private fun held(): Boolean {
		if (!module.enabled) return false
		val client = Minecraft.getInstance()
		if (client.player == null || client.gui.screen() != null) return false
		return CrypticClient.zoomKey.isDown
	}

	private fun requested(): Double {
		if (!held()) return NO_ZOOM
		val base = initialZoom.value
		if (!scrollZoom.value) return base
		return (base * SCROLL_STEP.pow(scrolled)).coerceIn(NO_ZOOM, MAX_SCROLL_ZOOM)
	}

	/**
	 * What to divide the FOV by this frame. Also starts transitions, since it reads the request
	 * anyway; asking twice a frame is safe.
	 */
	@JvmStatic
	fun factor(): Float {
		val now = System.nanoTime()
		val wanted = requested()
		if (wanted != target) {
			from = zoomAt(now)
			target = wanted
			startedAt = now
		}
		return zoomAt(now).toFloat()
	}

	@JvmStatic
	fun handFactor(): Float = if (affectHandFov.value) factor() else 1f

	/**
	 * The zoom part-way through the transition. The multiplier is interpolated, not the angle, so
	 * it reads as even.
	 */
	private fun zoomAt(now: Long): Double {
		if (from == target) return target
		val elapsed = (now - startedAt) / TRANSITION_NANOS
		return from * (target / from).pow(ease(elapsed.coerceIn(0.0, 1.0)))
	}

	private fun ease(t: Double): Double = when (transition.selectedIndex) {
		0 -> 1.0
		1 -> t
		2 -> t * t
		3 -> 1.0 - (1.0 - t) * (1.0 - t)
		else -> if (t < 0.5) 2.0 * t * t else 1.0 - (2.0 - 2.0 * t) * (2.0 - 2.0 * t) / 2.0
	}

	/** Takes the wheel while zoomed, so it doesn't also change the held item. */
	@JvmStatic
	fun onScroll(amount: Double): Boolean {
		if (amount == 0.0 || !scrollZoom.value || !held()) return false

		// Clamped in notches, so scrolling past an end and back takes the same turns.
		val base = initialZoom.value
		val lowest = ln(NO_ZOOM / base) / ln(SCROLL_STEP)
		val highest = ln(MAX_SCROLL_ZOOM / base) / ln(SCROLL_STEP)
		scrolled = (scrolled + amount).coerceIn(lowest, highest)
		return true
	}

	/**
	 * Holds the cinematic camera on while zoomed, and resets the wheel when the key comes up, so
	 * each zoom starts from the slider.
	 */
	fun tick(client: Minecraft) {
		val zooming = held()
		if (wasHeld && !zooming) scrolled = 0.0
		wasHeld = zooming

		val wantSmooth = zooming && cinematicCamera.value
		if (wantSmooth == forcingSmoothCamera) return

		forcingSmoothCamera = wantSmooth
		if (wantSmooth) {
			smoothCameraWas = client.options.smoothCamera
			client.options.smoothCamera = true
		} else {
			client.options.smoothCamera = smoothCameraWas
		}
	}
}
