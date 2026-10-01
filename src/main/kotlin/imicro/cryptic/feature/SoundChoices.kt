package imicro.cryptic.feature

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/**
 * The sounds a module can be set to play, shared so every picker offers the same
 * list in the same order.
 *
 * A profile stores the sound by its name here, so a name, once shipped, is part
 * of every saved profile: rename one and whoever picked it is quietly put back
 * on the default. Adding and reordering are both safe.
 */
object SoundChoices {
	val options: List<Pair<String, SoundEvent>> = listOf(
		"Experience Orb" to SoundEvents.EXPERIENCE_ORB_PICKUP,
		"Pling" to SoundEvents.NOTE_BLOCK_PLING.value(),
		"Bell" to SoundEvents.NOTE_BLOCK_BELL.value(),
		"Harp" to SoundEvents.NOTE_BLOCK_HARP.value(),
		"Button Click" to SoundEvents.UI_BUTTON_CLICK.value(),
		"Amethyst Chime" to SoundEvents.AMETHYST_BLOCK_CHIME,
		"Enderman Teleport" to SoundEvents.ENDERMAN_TELEPORT,
		"Arrow Hit" to SoundEvents.ARROW_HIT_PLAYER,
		"Level Up" to SoundEvents.PLAYER_LEVELUP,
		"Anvil Land" to SoundEvents.ANVIL_LAND,
	)

	val labels: List<String> = options.map { it.first }

	/** Where a sound is in the list, for a picker that should start on it. */
	fun indexOf(label: String): Int = labels.indexOf(label).coerceAtLeast(0)

	/** Plays one of them, heard wherever you are rather than from a point in the world. */
	fun play(index: Int, volume: Float, pitch: Float) {
		val sound = options[index.coerceIn(options.indices)].second
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
	}
}
