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
 * Narrows the field of view while a key is held, the way a spyglass does.
 *
 * Modelled on Zoomify (LGPL, not ported — only its set of options is), which is
 * the mod worth matching for what a zoom should offer: a zoom the key alone
 * gives you, a wheel that goes further, a curve for the way in and out, and a
 * say in whether the held item comes along.
 *
 * Nothing here holds a per-frame animation timer. The zoom is a pure function
 * of the clock and the transition it is part-way through, so the two render
 * hooks can both ask for it in the same frame and get the same answer.
 */
object Zoom {
	/** How long a transition takes. Short enough not to be in the way. */
	private const val TRANSITION_NANOS = 200_000_000.0

	/** What one notch of the wheel multiplies the zoom by. */
	private const val SCROLL_STEP = 1.2

	/** Zoom in its own units: 1x is the view untouched, and the floor for both. */
	private const val NO_ZOOM = 1.0

	/** As far as the wheel goes, which the slider on its own cannot reach. */
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

	/**
	 * Bound to Minecraft's own key mapping, so the controls screen can rebind it
	 * and Minecraft's options file is what remembers it. Unbound out of the box:
	 * a zoom key is a matter of taste, and there is no spare key worth claiming.
	 */
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
		description = "Zooms the view while a key is held",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = true,
		keybind = zoomKeybind,
		settings = listOf(initialZoom, transition, scrollZoom, cinematicCamera, affectHandFov),
	)

	/** How far the wheel has been turned this time round, in notches. */
	private var scrolled = 0.0

	/** The zoom the running transition is heading for, and where it set off from. */
	private var target = NO_ZOOM
	private var from = NO_ZOOM
	private var startedAt = 0L

	/** True while Cryptic is the reason Minecraft's cinematic camera is on. */
	private var forcingSmoothCamera = false
	private var smoothCameraWas = false

	private var wasHeld = false

	/** True while the key is down somewhere the zoom should apply. */
	private fun held(): Boolean {
		if (!module.enabled) return false
		val client = Minecraft.getInstance()
		if (client.player == null || client.screen != null) return false
		return CrypticClient.zoomKey.isDown
	}

	/** The zoom being asked for right now, before the transition catches up. */
	private fun requested(): Double {
		if (!held()) return NO_ZOOM
		val base = initialZoom.value
		if (!scrollZoom.value) return base
		return (base * SCROLL_STEP.pow(scrolled)).coerceIn(NO_ZOOM, MAX_SCROLL_ZOOM)
	}

	/**
	 * What to divide the field of view by this frame.
	 *
	 * Also what starts a transition, because the thing worth reacting to — the
	 * zoom being asked for having changed — is exactly what this has to read
	 * anyway. Asking twice in one frame is safe: the second call finds the
	 * transition already running and only reads it.
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

	/** The same, for the hand, which only follows the view when asked to. */
	@JvmStatic
	fun handFactor(): Float = if (affectHandFov.value) factor() else 1f

	/**
	 * The zoom part-way through the running transition.
	 *
	 * The multiplier is what is interpolated, not the angle: 1x to 4x passing
	 * through 2x half way is what reads as an even zoom, where dividing the
	 * field of view evenly rushes the start and crawls at the end.
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

	/**
	 * Takes the wheel over while zoomed, and says so, because the same turn
	 * would otherwise also change the held item.
	 */
	@JvmStatic
	fun onScroll(amount: Double): Boolean {
		if (amount == 0.0 || !scrollZoom.value || !held()) return false

		// Clamped in notches rather than in zoom, so scrolling past an end and
		// back again takes the same number of turns either way instead of
		// sitting on a piled-up number that has to be unwound first.
		val base = initialZoom.value
		val lowest = ln(NO_ZOOM / base) / ln(SCROLL_STEP)
		val highest = ln(MAX_SCROLL_ZOOM / base) / ln(SCROLL_STEP)
		scrolled = (scrolled + amount).coerceIn(lowest, highest)
		return true
	}

	/**
	 * Holds Minecraft's cinematic camera on for as long as the zoom is, and
	 * forgets how far the wheel was turned once the key comes back up, so every
	 * zoom starts from the slider rather than where the last one left off.
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
