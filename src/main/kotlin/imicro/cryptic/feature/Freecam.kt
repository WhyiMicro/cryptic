package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth

/**
 * A camera that leaves your body: the key detaches it where you stand, the movement keys fly it
 * (jump up, sneak down, sprint faster), the wheel pushes it forward or back, and your player stands
 * still where it was. For looking around a room or a whole floor while debugging.
 */
object Freecam {
	@JvmField
	val cinematic = ToggleModuleSetting(
		id = "cinematic",
		label = "Cinematic",
		defaultValue = false,
		description = "A clean picture: no HUD or hand while the camera is out. Off, everything shows as if you stood there.",
	)

	@JvmField
	val speed = SliderModuleSetting(
		id = "speed",
		label = "Speed",
		defaultValue = 0.6,
		min = 0.05,
		max = 5.0,
		step = 0.05,
		unit = "b/t",
		description = "Blocks a tick while a movement key is held. Each notch of the wheel moves four times that.",
	)

	private val keybind = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.freecamKey.isUnbound) "None" else CrypticClient.freecamKey.translatedKeyMessage.string.uppercase()
		},
		onKeyChanged = { key ->
			CrypticClient.freecamKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "freecam",
		restriction = Restriction.CHEAT,
		name = "Freecam",
		description = "Fly the camera freely",
		category = ModuleCategory.GENERAL,
		supportsKeybind = true,
		keybind = keybind,
		settings = listOf(cinematic, speed),
	)

	@JvmStatic
	var active = false
		private set

	private var x = 0.0
	private var y = 0.0
	private var z = 0.0
	private var xo = 0.0
	private var yo = 0.0
	private var zo = 0.0
	private var yaw = 0f
	private var pitch = 0f

	/** Wheel notches not yet flown, applied on the next tick so the move is smoothed. */
	private var scrolled = 0.0

	/** Whether Cinematic hid the HUD, so it's only shown again if Freecam hid it. */
	private var hidHud = false
	private var wasDown = false

	fun tick(client: Minecraft) {
		val player = client.player
		val down = module.enabled && player != null && client.gui.screen() == null && CrypticClient.freecamKey.isDown
		val pressed = down && !wasDown
		wasDown = down
		when {
			!module.enabled || player == null -> if (active) stop(client)
			pressed && !active -> start(client)
			pressed -> stop(client)
		}
		if (active) fly(client)
	}

	private fun start(client: Minecraft) {
		val camera = client.gameRenderer.mainCamera()
		val at = camera.position()
		x = at.x; y = at.y; z = at.z
		xo = x; yo = y; zo = z
		yaw = camera.yRot()
		pitch = camera.xRot()
		scrolled = 0.0
		active = true
	}

	private fun stop(client: Minecraft) {
		active = false
		if (hidHud && client.gui.hud.isHidden) client.gui.hud.toggle()
		hidHud = false
	}

	/** Cinematic follows its switch while the camera is out, not only when it leaves. */
	private fun syncHud(client: Minecraft) {
		val hud = client.gui.hud
		if (cinematic.value && !hidHud && !hud.isHidden) {
			hud.toggle()
			hidHud = true
		} else if (!cinematic.value && hidHud) {
			if (hud.isHidden) hud.toggle()
			hidHud = false
		}
	}

	private fun fly(client: Minecraft) {
		syncHud(client)
		xo = x; yo = y; zo = z
		val options = client.options
		val inScreen = client.gui.screen() != null
		fun held(key: KeyMapping) = !inScreen && key.isDown
		val forward = (if (held(options.keyUp)) 1.0 else 0.0) - (if (held(options.keyDown)) 1.0 else 0.0)
		val left = (if (held(options.keyLeft)) 1.0 else 0.0) - (if (held(options.keyRight)) 1.0 else 0.0)
		val up = (if (held(options.keyJump)) 1.0 else 0.0) - (if (held(options.keyShift)) 1.0 else 0.0)
		val step = speed.value * (if (held(options.keySprint)) 2.0 else 1.0)

		// Forward follows the look, pitch included; strafing stays level.
		val yawRad = Math.toRadians(yaw.toDouble())
		val pitchRad = Math.toRadians(pitch.toDouble())
		val lookX = -Math.sin(yawRad) * Math.cos(pitchRad)
		val lookY = -Math.sin(pitchRad)
		val lookZ = Math.cos(yawRad) * Math.cos(pitchRad)
		val leftX = Math.cos(yawRad)
		val leftZ = Math.sin(yawRad)

		var dx = lookX * forward + leftX * left
		var dy = lookY * forward + up
		var dz = lookZ * forward + leftZ * left
		val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
		if (length > 1.0) {
			dx /= length; dy /= length; dz /= length
		}
		val wheel = scrolled * speed.value * WHEEL_STEPS
		scrolled = 0.0
		x += dx * step + lookX * wheel
		y += dy * step + lookY * wheel
		z += dz * step + lookZ * wheel
	}

	/** The mouse's turn while the camera is out; true when it was taken from the player. */
	@JvmStatic
	fun turn(dx: Double, dy: Double): Boolean {
		if (!active) return false
		// The same scale Entity.turn applies.
		yaw += (dx * 0.15).toFloat()
		pitch = (pitch + dy * 0.15).toFloat().coerceIn(-90f, 90f)
		return true
	}

	/** The wheel, while the camera is out: forward or back instead of the hotbar. */
	@JvmStatic
	fun onScroll(amount: Double): Boolean {
		if (!active || Minecraft.getInstance().gui.screen() != null) return false
		scrolled += amount
		return true
	}

	// By component: the camera asks every frame.
	@JvmStatic fun x(partialTick: Float): Double = Mth.lerp(partialTick.toDouble(), xo, x)
	@JvmStatic fun y(partialTick: Float): Double = Mth.lerp(partialTick.toDouble(), yo, y)
	@JvmStatic fun z(partialTick: Float): Double = Mth.lerp(partialTick.toDouble(), zo, z)
	@JvmStatic fun yaw(): Float = yaw
	@JvmStatic fun pitch(): Float = pitch

	private const val WHEEL_STEPS = 4.0
}
