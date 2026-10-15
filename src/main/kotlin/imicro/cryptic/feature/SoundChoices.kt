package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.resources.Identifier
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

	/** The last entry of every sound picker: any sound the game has, by its id. */
	const val CUSTOM = "Custom"

	val labels: List<String> = options.map { it.first } + CUSTOM

	/** Where a sound is in the list, for a picker that should start on it. */
	fun indexOf(label: String): Int = labels.indexOf(label).coerceAtLeast(0)

	/** Plays one of them, heard wherever you are rather than from a point in the world. */
	fun play(index: Int, volume: Float, pitch: Float, customId: String = "") {
		val sound = if (labels.getOrNull(index) == CUSTOM) custom(customId) else options[index.coerceIn(options.indices)].second
		play(sound ?: return, volume, pitch)
	}

	fun play(sound: SoundEvent, volume: Float, pitch: Float) {
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
	}

	/**
	 * The field a picker shows once Custom is chosen in [picker], for the id
	 * of the sound: `minecraft:block.amethyst_block.hit`, or one a resource
	 * pack adds. [also] is whatever else hides the picker itself.
	 */
	fun customField(id: String, picker: DropdownModuleSetting, also: () -> Boolean = { true }) = TextModuleSetting(
		id = id,
		label = "Sound id",
		defaultValue = "minecraft:block.note_block.pling",
		maxLength = 128,
		hint = "minecraft:block.note_block.pling",
		description = "Any sound the game has. Suggestions appear as you type.",
		suggestions = ::suggest,
		visibleIf = { also() && picker.selected == CUSTOM },
	)

	/** The sound for an id typed into a Custom field, or null when it is not one. */
	fun custom(id: String): SoundEvent? {
		val location = Identifier.tryParse(id.trim().lowercase()) ?: return null
		return SoundEvent.createVariableRangeEvent(location)
	}

	/**
	 * Sound ids that fit what has been typed so far: those starting with it
	 * first, with or without the `minecraft:` in front, then those merely
	 * containing it. Read from the sound manager, so a resource pack's own
	 * sounds are offered too.
	 */
	fun suggest(typed: String): List<String> {
		val query = typed.trim().lowercase()
		if (query.isEmpty()) return emptyList()
		val ids = Minecraft.getInstance().soundManager.availableSounds.map { it.toString() }
		val starting = ids.filter { it.startsWith(query) || it.substringAfter(':').startsWith(query) }.sorted()
		val containing = if (starting.size >= SUGGESTIONS) emptyList() else ids.filter { query in it && it !in starting }.sorted()
		return (starting + containing).take(SUGGESTIONS)
	}

	private const val SUGGESTIONS = 6
}
