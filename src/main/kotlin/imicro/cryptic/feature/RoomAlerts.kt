package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * Announces the room you are standing in finishing.
 *
 * Ported from NoammAddons (CC0). A room is done in two steps — cleared, then
 * every secret found — and neither is announced anywhere you are looking: the
 * only sign is a number moving in the tab list. A title and a pling say it
 * while your eyes are still on the fight.
 *
 * Green means the room is finished with. Cleared on its own is white, unless
 * the room holds no secrets, in which case clearing it *is* finishing it.
 */
object RoomAlerts {
	/** Long enough to read, short enough not to sit over the next room. */
	private const val FADE_IN_TICKS = 0
	private const val STAY_TICKS = 20
	private const val FADE_OUT_TICKS = 10

	private val clearedSection = SectionModuleSetting("cleared_section", "Cleared")

	@JvmField
	val alertCleared = ToggleModuleSetting(
		id = "alert_cleared",
		label = "Announce cleared",
		defaultValue = true,
	)

	@JvmField
	val clearedTitle = TextModuleSetting(
		id = "cleared_title",
		label = "Title",
		defaultValue = "Cleared",
		maxLength = 64,
		visibleIf = { alertCleared.value },
	)

	private val secretsSection = SectionModuleSetting("secrets_section", "Secrets")

	@JvmField
	val alertSecrets = ToggleModuleSetting(
		id = "alert_secrets",
		label = "Announce secrets done",
		defaultValue = true,
	)

	@JvmField
	val secretsTitle = TextModuleSetting(
		id = "secrets_title",
		label = "Title",
		defaultValue = "Secrets Done!",
		maxLength = 64,
		visibleIf = { alertSecrets.value },
	)

	private val soundSection = SectionModuleSetting("sound_section", "Sound")

	@JvmField
	val playSound = ToggleModuleSetting(
		id = "play_sound",
		label = "Play a pling",
		defaultValue = true,
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
		clearedTitle,
		alertSecrets,
		secretsTitle,
		playSound,
		volume,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is TextModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "room_alerts",
		name = "Room Alerts",
		description = "Titles the moment your room is cleared or fully secreted",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(clearedSection, alertCleared, clearedTitle) +
			listOf(secretsSection, alertSecrets, secretsTitle) +
			listOf(soundSection, playSound, volume, reset),
	)

	private var initialized = false

	/** The room being watched, and the state it was last seen in. */
	private var watched: DungeonRoom? = null
	private var watchedState: DungeonRoom.State? = null

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		watched = null
		watchedState = null
	}

	/**
	 * Watches the room under the player for its state moving on.
	 *
	 * Polling beats an event here: the alert is only ever about the one room the
	 * player is standing in, and that is a question [DungeonMap] can already
	 * answer, so nothing has to be plumbed through every place a room changes.
	 */
	fun tick(client: Minecraft) {
		if (!module.enabled || !DungeonLocation.inDungeon || DungeonRun.inBoss) {
			forget()
			return
		}

		val room = DungeonMap.currentRoom()
		if (room !== watched) {
			// Walking into a room is not news about it, so the state it is
			// already in is taken as the baseline.
			watched = room
			watchedState = room?.state
			return
		}

		if (room == null) return
		val previous = watchedState
		if (room.state == previous) return
		watchedState = room.state

		if (!announces(room)) return

		when (room.state) {
			DungeonRoom.State.CLEARED -> if (alertCleared.value) {
				// A room with nothing to find is finished the moment it is
				// cleared, so it goes straight to green.
				announce(client, clearedTitle.value, (room.data?.secrets ?: 0) == 0)
			}
			DungeonRoom.State.GREEN -> if (alertSecrets.value && (room.data?.secrets ?: 0) > 0) {
				announce(client, secretsTitle.value, true)
			}
			else -> Unit
		}
	}

	/**
	 * Whether this kind of room is worth announcing.
	 *
	 * The entrance and blood rooms clear themselves as you pass through, and a
	 * puzzle's state says nothing about whether you solved it — except Blaze,
	 * which is cleared exactly when it is beaten.
	 */
	private fun announces(room: DungeonRoom): Boolean {
		if (room.type == DungeonRoom.Type.PUZZLE) return room.data?.name == "Blaze"
		return room.type == DungeonRoom.Type.NORMAL ||
			room.type == DungeonRoom.Type.RARE ||
			room.type == DungeonRoom.Type.TRAP
	}

	private fun announce(client: Minecraft, text: String, done: Boolean) {
		val message = text.trim()
		if (message.isEmpty()) return

		client.gui.setTimes(FADE_IN_TICKS, STAY_TICKS, FADE_OUT_TICKS)
		client.gui.setTitle(Component.literal(if (done) "§a$message" else message))

		if (playSound.value) {
			client.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), volume.value.toFloat(), 1f)
		}
	}

	/** Shows both titles in turn, for `/cryptic debug roomalerts`. */
	fun preview(client: Minecraft) {
		announce(client, clearedTitle.value, false)
	}
}
