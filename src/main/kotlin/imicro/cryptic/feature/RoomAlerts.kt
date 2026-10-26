package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * Announces the room you're in finishing, with a title and a sound: green when finished, white when
 * only cleared (unless the room has no secrets, when clearing finishes it). Ported from
 * NoammAddons.
 */
object RoomAlerts {
	private const val FADE_IN_TICKS = 0
	private const val STAY_TICKS = 20
	private const val FADE_OUT_TICKS = 10

	/** Hypixel's lines for the two bonus kills that announce themselves. */
	private val princePattern = Regex("""^A Prince falls\. \+1 Bonus Score$""")

	/** The three titles about the room you're in. */
	private val titlesSection = SectionModuleSetting("room_titles_section", "Titles")

	private val announcementsSection = SectionModuleSetting("messages_section", "Announcements")

	@JvmField
	val alertCleared = ToggleModuleSetting(
		id = "alert_cleared",
		label = "Cleared title",
		defaultValue = true,
	)

	@JvmField
	val alertSecrets = ToggleModuleSetting(
		id = "alert_secrets",
		label = "Secrets done title",
		defaultValue = true,
	)

	private val clearedText = TextModuleSetting(
		id = "cleared_text",
		label = "Title",
		defaultValue = "Cleared",
		maxLength = 100,
		hint = "Cleared",
		description = "What the title says. & colour codes work.",
		visibleIf = { alertCleared.value },
	)

	private val secretsText = TextModuleSetting(
		id = "secrets_text",
		label = "Title",
		defaultValue = "Secrets Done!",
		maxLength = 100,
		hint = "Secrets Done!",
		description = "What the title says. & colour codes work.",
		visibleIf = { alertSecrets.value },
	)

	private val soundSection = SectionModuleSetting("sound_section", "Sound")

	@JvmField
	val playSound = ToggleModuleSetting(
		id = "play_sound",
		label = "Play a pling",
		defaultValue = true,
		description = "A pling with every title above.",
	)

	@JvmField
	val volume = SliderModuleSetting(
		id = "volume",
		label = "Volume",
		defaultValue = 0.25,
		min = 0.05,
		max = 1.0,
		step = 0.05,
		visibleIf = { playSound.value },
	)

	private val configurableSettings = listOf(
		alertCleared,
		alertSecrets,
		playSound,
		volume,
	)

	@JvmField
	val module = Module(
		id = "room_alerts",
		name = "Alerts",
		description = "Titles and calls for a run",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(titlesSection, alertCleared.withMore(clearedText), alertSecrets.withMore(secretsText)) +
			DungeonScore.titleSettings +
			listOf(announcementsSection) + DungeonScore.announcementSettings +
			listOf(soundSection, playSound, volume),
	)

	private var initialized = false

	/** Bonus-point kills reset with the run, not the room. */
	private var mimicAnnounced = false

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onMessage(message.string) }
	}

	private fun forget() {
		mimicAnnounced = false
	}

	/**
	 * A prince dying, which Hypixel only tells you. Kills are party announcements now, worded with
	 * the score.
	 */
	fun onMessage(message: String) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		if (princePattern.matches(message)) DungeonScore.onPrinceKilled()
	}

	@JvmStatic
	fun onMimicKilled() {
		if (!module.enabled || !DungeonLocation.inDungeon || mimicAnnounced) return
		mimicAnnounced = true
		DungeonScore.onMimicKilled()
	}

	/**
	 * Watches the checkmark of the room you're in, after Odin's `RoomClear`. What clears a room
	 * varies (starred mobs, a mini boss, both), but Hypixel's map colour already accounts for it:
	 * white cleared, green cleared with every secret.
	 */
	@JvmStatic
	fun onCheckmarkChanged(room: DungeonRoom) {
		if (!module.enabled || !DungeonLocation.inDungeon || DungeonRun.inBoss) return
		// Only your current room; a checkmark elsewhere is someone else's news.
		if (room !== DungeonMap.currentRoom()) return
		if (!announces(room)) return

		val client = Minecraft.getInstance()
		when (room.state) {
			DungeonRoom.State.CLEARED -> if (alertCleared.value) announce(client, clearedText.value, false)
			DungeonRoom.State.GREEN -> if (alertSecrets.value) announce(client, secretsText.value, true)
			else -> Unit
		}
	}

	/**
	 * Everything except the entrance (green from the start) and the fairy room (nothing to clear),
	 * as Odin does.
	 */
	private fun announces(room: DungeonRoom): Boolean =
		room.type != DungeonRoom.Type.ENTRANCE && room.type != DungeonRoom.Type.FAIRY

	private fun announce(client: Minecraft, text: String, done: Boolean) {
		val message = text.trim()
		if (message.isEmpty()) return

		client.gui.hud.setTimes(FADE_IN_TICKS, STAY_TICKS, FADE_OUT_TICKS)
		client.gui.hud.setTitle(Component.literal(DungeonScore.colored(message, if (done) "§a" else "")))

		playAlertSound(client)
	}

	fun playAlertSound(client: Minecraft) {
		if (!module.enabled || !playSound.value) return
		client.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), volume.value.toFloat(), 1f)
	}

	/** The cleared title, for `/cryptic debug roomalerts`. */
	fun preview(client: Minecraft) {
		announce(client, clearedText.value, false)
	}
}
