package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/**
 * Look around without turning: while the key is held (or toggled), the mouse moves the camera and
 * your player keeps facing where it was, so you keep walking and aiming the same way. Third
 * person swings the view out behind you; first person looks around from your own eyes.
 */
object Freelook {
	private const val MODE_THIRD_PERSON = 0

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "View",
		options = listOf("Third person", "First person"),
		description = "Third person orbits the camera around you; first person turns your view in place.",
	)

	@JvmField
	val toggle = ToggleModuleSetting(
		id = "toggle",
		label = "Press to toggle",
		defaultValue = false,
		description = "One press starts freelook and the next ends it, instead of holding the key.",
	)

	@JvmField
	val invertPitch = ToggleModuleSetting(
		id = "invert_pitch",
		label = "Invert up and down",
		defaultValue = false,
	)

	/** Minecraft's own key mapping, so the controls screen can rebind it. Unbound by default. */
	private val keybind = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.freelookKey.isUnbound) "None" else CrypticClient.freelookKey.translatedKeyMessage.string.uppercase()
		},
		onKeyChanged = { key ->
			CrypticClient.freelookKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "freelook",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		name = "Freelook",
		description = "Look around without turning",
		category = ModuleCategory.GENERAL,
		supportsKeybind = true,
		keybind = keybind,
		settings = listOf(mode, toggle, invertPitch),
	)

	/** The camera's own angles while freelook is on. */
	private var yaw = 0f
	private var pitch = 0f

	@JvmStatic
	var active = false
		private set

	/** The view to go back to, when third person put the camera behind you. */
	private var cameraBefore: CameraType? = null
	private var wasDown = false

	fun tick(client: Minecraft) {
		val player = client.player
		val down = module.enabled && player != null && client.gui.screen() == null && CrypticClient.freelookKey.isDown
		val want = when {
			!module.enabled || player == null -> false
			toggle.value -> if (down && !wasDown) !active else active
			else -> down
		}
		wasDown = down
		if (want == active) return
		if (want && player != null) {
			yaw = player.yRot
			pitch = player.xRot
			if (mode.selectedIndex == MODE_THIRD_PERSON && client.options.cameraType.isFirstPerson) {
				cameraBefore = client.options.cameraType
				client.options.cameraType = CameraType.THIRD_PERSON_BACK
			}
		} else {
			cameraBefore?.let { client.options.cameraType = it }
			cameraBefore = null
		}
		active = want
	}

	/** The mouse's turn while freelook is on; true when it was taken from the player. */
	@JvmStatic
	fun turn(dx: Double, dy: Double): Boolean {
		if (!active) return false
		// The same scale Entity.turn applies.
		yaw += (dx * 0.15).toFloat()
		pitch = (pitch + (if (invertPitch.value) -dy else dy) * 0.15).toFloat().coerceIn(-90f, 90f)
		return true
	}

	@JvmStatic
	fun yaw(original: Float): Float = if (active) yaw else original

	@JvmStatic
	fun pitch(original: Float): Float = if (active) pitch else original
}
