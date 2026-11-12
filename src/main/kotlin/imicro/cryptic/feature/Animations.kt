package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.FishingHookRenderer
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f

/** First-person view-model controls, adapted from NoammAddons' Animations. */
object Animations {
	@JvmField val itemScale = SliderModuleSetting(
		"item_scale", "Item Scale", 0.0, -1.5, 1.5, 0.05,
		"0 is normal size; -0.5 is half size; 1 is double size",
	)
	@JvmField val positionX = SliderModuleSetting("position_x", "Position X", 0.0, -2.0, 2.0, 0.01)
	@JvmField val positionY = SliderModuleSetting("position_y", "Position Y", 0.0, -2.0, 2.0, 0.01)
	@JvmField val positionZ = SliderModuleSetting("position_z", "Position Z", 0.0, -2.0, 2.0, 0.01)
	@JvmField val leftHand = ToggleModuleSetting(
		"left_hand", "Left hand", description = "Displays your main hand and held item on the left side",
	)
	@JvmField val blockHitting = ToggleModuleSetting(
		"block_hitting", "Block hitting",
		description = "Shows the classic sword block pose while holding the use key",
	)

	@JvmField val rotationX = SliderModuleSetting("rotation_x", "Rotation X", 0.0, -50.0, 50.0, 1.0)
	@JvmField val rotationY = SliderModuleSetting("rotation_y", "Rotation Y", 0.0, -50.0, 50.0, 1.0)
	@JvmField val rotationZ = SliderModuleSetting("rotation_z", "Rotation Z", 0.0, -50.0, 50.0, 1.0)

	@JvmField val swingX = SliderModuleSetting("swing_x", "Swing X", 1.0, 0.0, 2.0, 0.01)
	@JvmField val swingY = SliderModuleSetting("swing_y", "Swing Y", 1.0, 0.0, 2.0, 0.01)
	@JvmField val swingZ = SliderModuleSetting("swing_z", "Swing Z", 1.0, 0.0, 2.0, 0.01)

	@JvmField val disableHandMovement = ToggleModuleSetting(
		"disable_hand_movement", "Disable hand movement", description = "Stops the held item moving when you look around",
	)
	@JvmField val disableEquipAnimation = ToggleModuleSetting(
		"disable_equip_animation", "Disable equip animation", description = "Skips the animation when the held item changes",
	)
	@JvmField val disableSwingAnimation = ToggleModuleSetting(
		"disable_swing_animation", "Disable swing animation", description = "Disables the held-item swing animation",
	)

	@JvmField val swingSpeed = SliderModuleSetting(
		"swing_speed", "Swing Speed", 0.0, -2.0, 1.0, 0.05,
		visibleIf = { !disableSwingAnimation.value },
	)
	@JvmField val ignoreHaste = ToggleModuleSetting(
		"ignore_haste", "Ignore Haste", description = "Ignores Haste when calculating swing speed",
		visibleIf = { !disableSwingAnimation.value },
	)
	@JvmField val swingAffectsHand = ToggleModuleSetting(
		"swing_affects_hand", "Swing speed affects hand", description = "Your empty hand swings at that speed too",
		visibleIf = { !disableSwingAnimation.value },
	)

	private val configurableSettings = listOf(
		itemScale,
		positionX, positionY, positionZ,
		leftHand, blockHitting,
		rotationX, rotationY, rotationZ,
		swingX, swingY, swingZ,
		disableHandMovement, disableEquipAnimation, disableSwingAnimation,
		swingSpeed, ignoreHaste, swingAffectsHand,
	)

	// Grouped under the part of the view model each control moves.
	private val layout = listOf(
		SectionModuleSetting("hand_section", "Hand"),
		itemScale, positionX, positionY, positionZ, leftHand, blockHitting,
		SectionModuleSetting("rotation_section", "Rotation"),
		rotationX, rotationY, rotationZ,
		SectionModuleSetting("swing_section", "Swing"),
		swingX, swingY, swingZ, swingSpeed, ignoreHaste, swingAffectsHand,
		SectionModuleSetting("animation_section", "Animations"),
		disableHandMovement, disableEquipAnimation, disableSwingAnimation,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is SliderModuleSetting -> it.reset()
				is ToggleModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField val module = Module(
		id = "animations",
		name = "Animations",
		description = "Restyles your held item",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = layout + reset,
	)

	// ---- The fishing line from the rod's tip ---------------------------------------------------

	/** The cast rod texture's tip pixel (14, 1), in the item model's 0..1 space, mid-layer. */
	private val ROD_TIP = Vector3f(14.5f / 16f, 14.5f / 16f, 0.5f)

	/** Set while the first-person fishing rod is drawn, so the layer hook knows to note its pose. */
	@JvmField var drawingRod: ItemDisplayContext? = null

	/** The hands' pose before any arm transform: the inverse view rotation and the view bob. */
	private val handBase = Matrix4f()
	private val handBaseInverse = Matrix4f()
	private var handBaseInverted = false

	/** The rod tip as last drawn, from the camera in view space, bob left out; per hand. */
	private val rodTip = arrayOf(Vector3f(), Vector3f())
	private val rodTipAt = longArrayOf(0L, 0L)

	@JvmStatic
	fun noteHandBase(pose: Matrix4fc) {
		handBase.set(pose)
		handBaseInverted = false
	}

	/** Called with the rod's model pose, display transform included, so pack models line up too. */
	@JvmStatic
	fun noteRodPose(pose: Matrix4fc, rightHand: Boolean) {
		if (!handBaseInverted) {
			handBase.invert(handBaseInverse)
			handBaseInverted = true
		}
		val side = if (rightHand) 0 else 1
		// Bob is left out because the world's projection adds it again.
		handBaseInverse.mul(pose, Matrix4f()).transformPosition(ROD_TIP, rodTip[side])
		rodTipAt[side] = System.currentTimeMillis()
	}

	/**
	 * Where the fishing line should start in first person: the rod's tip as drawn, moved from the
	 * hand's field of view to the world's so it lands on the same pixel. Null to keep vanilla's.
	 */
	@JvmStatic
	fun rodTipInWorld(owner: Player): Vec3? {
		if (!Fishing.module.enabled || !Fishing.rodLine.value) return null
		val client = Minecraft.getInstance()
		if (owner !== client.player || !client.options.cameraType.isFirstPerson) return null
		val side = if (FishingHookRenderer.getHoldingArm(owner) == HumanoidArm.RIGHT) 0 else 1
		// Left hand draws the main hand on the other side.
		val drawnSide = if (leftHand.value) 1 - side else side
		if (System.currentTimeMillis() - rodTipAt[drawnSide] > 250) return null
		val tip = rodTip[drawnSide]
		val camera = client.gameRenderer.mainCamera()
		val hudFov = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.hudFov
		if (hudFov <= 0f) return null
		val k = (Math.tan(Math.toRadians(camera.fov.toDouble() / 2)) / Math.tan(Math.toRadians(hudFov.toDouble() / 2))).toFloat()
		val left = camera.leftVector()
		val up = camera.upVector()
		val forward = camera.forwardVector()
		val x = -left.x() * tip.x * k + up.x() * tip.y * k - forward.x() * tip.z
		val y = -left.y() * tip.x * k + up.y() * tip.y * k - forward.y() * tip.z
		val z = -left.z() * tip.x * k + up.z() * tip.y * k - forward.z() * tip.z
		val position = camera.position()
		return Vec3(position.x + x, position.y + y, position.z + z)
	}
}
