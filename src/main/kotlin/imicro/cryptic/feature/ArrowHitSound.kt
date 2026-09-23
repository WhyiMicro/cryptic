package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/**
 * Replaces the ding an arrow makes when it hits something.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). The hit sound is the
 * only confirmation an archer gets that a shot landed, and vanilla's is a thin
 * click that disappears under a boss fight. A note you chose, at a volume you
 * chose, is audible in the places it is needed.
 *
 * The original is not played over — it is cancelled and yours is played in its
 * place, so two sounds never overlap.
 */
object ArrowHitSound {
	private val soundOptions = listOf(
		"Harp" to SoundEvents.NOTE_BLOCK_HARP.value(),
		"Pling" to SoundEvents.NOTE_BLOCK_PLING.value(),
		"Bell" to SoundEvents.NOTE_BLOCK_BELL.value(),
		"Bit" to SoundEvents.NOTE_BLOCK_BIT.value(),
		"Chime" to SoundEvents.NOTE_BLOCK_CHIME.value(),
		"Experience Orb" to SoundEvents.EXPERIENCE_ORB_PICKUP,
		"Button Click" to SoundEvents.UI_BUTTON_CLICK.value(),
		"Amethyst Chime" to SoundEvents.AMETHYST_BLOCK_CHIME,
		"Anvil Land" to SoundEvents.ANVIL_LAND,
	)

	@JvmField
	val silence = ToggleModuleSetting(
		id = "silence",
		label = "Silence only",
		defaultValue = false,
		description = "Takes the hit sound away and puts nothing in its place.",
	)

	@JvmField
	val sound = DropdownModuleSetting(
		id = "sound",
		label = "Sound",
		options = soundOptions.map { it.first },
		visibleIf = { !silence.value },
	)

	@JvmField
	val volume = SliderModuleSetting(
		id = "volume",
		label = "Volume",
		defaultValue = 1.0,
		min = 0.1,
		max = 2.0,
		step = 0.1,
		visibleIf = { !silence.value },
	)

	@JvmField
	val pitch = SliderModuleSetting(
		id = "pitch",
		label = "Pitch",
		defaultValue = 1.0,
		min = 0.5,
		max = 2.0,
		step = 0.1,
		visibleIf = { !silence.value },
	)

	private val preview = ButtonModuleSetting(
		id = "preview",
		label = "Play it",
		action = { play() },
		visibleIf = { !silence.value },
	)

	private val configurable = listOf(silence, sound, volume, pitch)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurable.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "arrow_hit_sound",
		name = "Arrow Hit Sound",
		description = "A louder arrow hit sound",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(silence, sound, volume, pitch, preview, reset),
	)

	/**
	 * Answers whether the sound about to play is the one being replaced, and
	 * plays the replacement if it is.
	 *
	 * True means the original should not be started.
	 */
	@JvmStatic
	fun intercept(instance: SoundInstance): Boolean {
		if (!module.enabled) return false
		if (instance.identifier != SoundEvents.ARROW_HIT_PLAYER.location) return false
		if (!silence.value) play()
		return true
	}

	private fun play() {
		Minecraft.getInstance().soundManager.play(
			SimpleSoundInstance.forUI(selected(), pitch.value.toFloat(), volume.value.toFloat()),
		)
	}

	private fun selected(): SoundEvent =
		soundOptions[sound.selectedIndex.coerceIn(soundOptions.indices)].second
}
