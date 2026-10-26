package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/**
 * Replaces the arrow hit sound with one you choose, at a volume you choose. The original is
 * cancelled, not played over. Ported from NoammAddons. Part of [BowTweaks].
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
		id = "hit_sound_silence",
		label = "Silence only",
		defaultValue = false,
		description = "Takes the hit sound away and puts nothing in its place.",
	)

	@JvmField
	val sound = DropdownModuleSetting(
		id = "hit_sound_sound",
		label = "Sound",
		options = soundOptions.map { it.first } + SoundChoices.CUSTOM,
		visibleIf = { !silence.value },
	)

	private val customSound = SoundChoices.customField("hit_sound_custom_sound", sound) { !silence.value }

	@JvmField
	val volume = SliderModuleSetting(
		id = "hit_sound_volume",
		label = "Volume",
		defaultValue = 1.0,
		min = 0.1,
		max = 2.0,
		step = 0.1,
		visibleIf = { !silence.value },
	)

	@JvmField
	val pitch = SliderModuleSetting(
		id = "hit_sound_pitch",
		label = "Pitch",
		defaultValue = 1.0,
		min = 0.5,
		max = 2.0,
		step = 0.1,
		visibleIf = { !silence.value },
	)

	private val preview = ButtonModuleSetting(
		id = "hit_sound_preview",
		label = "Play it",
		action = { play() },
		visibleIf = { !silence.value },
	)

	val settings = listOf(silence, sound, customSound, volume, pitch, preview)

	/**
	 * Whether the sound about to play is the one being replaced; plays the replacement if so. True
	 * means don't start the original.
	 */
	@JvmStatic
	fun intercept(instance: SoundInstance): Boolean {
		if (!BowTweaks.module.enabled || !BowTweaks.hitSound.value) return false
		if (instance.identifier != SoundEvents.ARROW_HIT_PLAYER.location) return false
		if (silence.value) return true
		// A custom sound set to the hit sound itself plays as is, or it would loop back through
		// here.
		val replacement = selected() ?: return true
		if (replacement.location == SoundEvents.ARROW_HIT_PLAYER.location) return false
		play()
		return true
	}

	private fun play() {
		val replacement = selected() ?: return
		if (replacement.location == SoundEvents.ARROW_HIT_PLAYER.location) return
		Minecraft.getInstance().soundManager.play(
			SimpleSoundInstance.forUI(replacement, pitch.value.toFloat(), volume.value.toFloat()),
		)
	}

	/** The chosen sound, or null when Custom holds no id. */
	private fun selected(): SoundEvent? =
		if (sound.selected == SoundChoices.CUSTOM) SoundChoices.custom(customSound.value)
		else soundOptions[sound.selectedIndex.coerceIn(soundOptions.indices)].second
}
