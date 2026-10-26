package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting

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
}
