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
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
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

	/** What Hypixel says for the two bonus kills that announce themselves. */
	private val princePattern = Regex("""^A Prince falls\. \+1 Bonus Score$""")
	private val batPattern = Regex("""^A Bat has been slain\. \+1 Bonus Score$""")

	/** All five bats is the point at which there are no more to find. */
	private const val BATS_PER_RUN = 5

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

	private val completeSection = SectionModuleSetting("complete_section", "Room complete")

	@JvmField
	val roomComplete = ToggleModuleSetting(
		id = "room_complete",
		label = "Announce room complete",
		description = "Says the room is done instead of naming the secrets, when the checkmark turns green.",
	)

	@JvmField
	val completeTitle = TextModuleSetting(
		id = "complete_title",
		label = "Title",
		defaultValue = "Room Complete!",
		maxLength = 64,
		visibleIf = { roomComplete.value },
	)

	private val killsSection = SectionModuleSetting("kills_section", "Kills")

	@JvmField
	val alertPrince = ToggleModuleSetting(
		id = "alert_prince",
		label = "Announce prince",
		defaultValue = true,
		description = "A prince is a bonus point, and the only sign of one dying is a line of chat.",
	)

	@JvmField
	val princeTitle = TextModuleSetting(
		id = "prince_title",
		label = "Message",
		defaultValue = "Prince Killed!",
		maxLength = 64,
		visibleIf = { alertPrince.value },
	)

	@JvmField
	val alertMimic = ToggleModuleSetting(
		id = "alert_mimic",
		label = "Announce mimic",
		defaultValue = true,
		description = "The mimic says nothing at all when it dies, so this is the only announcement there is.",
	)

	@JvmField
	val mimicTitle = TextModuleSetting(
		id = "mimic_title",
		label = "Message",
		defaultValue = "Mimic Killed!",
		maxLength = 64,
		visibleIf = { alertMimic.value },
	)

	@JvmField
	val alertBats = ToggleModuleSetting(
		id = "alert_bats",
		label = "Announce bats done",
		defaultValue = true,
		description = "Fires on the fifth bat, which is all of them and the last bonus point from them.",
	)

	@JvmField
	val batsTitle = TextModuleSetting(
		id = "bats_title",
		label = "Message",
		defaultValue = "Bats Done!",
		maxLength = 64,
		visibleIf = { alertBats.value },
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
		roomComplete,
		completeTitle,
		alertPrince,
		princeTitle,
		alertMimic,
		mimicTitle,
		alertBats,
		batsTitle,
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
		name = "Alerts",
		description = "Titles the moment a room finishes, or a bonus-point mob dies",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(clearedSection, alertCleared, clearedTitle) +
			listOf(secretsSection, alertSecrets, secretsTitle) +
			listOf(completeSection, roomComplete, completeTitle) +
			listOf(killsSection, alertPrince, princeTitle, alertMimic, mimicTitle, alertBats, batsTitle) +
			listOf(soundSection, playSound, volume, reset),
	)

	private var initialized = false

	/** The room being watched, and the state it was last seen in. */
	private var watched: DungeonRoom? = null
	private var watchedState: DungeonRoom.State? = null

	/** Bonus-point kills, which reset with the run rather than with the room. */
	private var batsSlain = 0
	private var mimicAnnounced = false

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onMessage(message.string) }
	}

	private fun forget() {
		watched = null
		watchedState = null
		batsSlain = 0
		mimicAnnounced = false
	}

	/**
	 * Bonus-point kills, which the room state knows nothing about.
	 *
	 * The prince and the bats say so in chat and are simply read back. The
	 * mimic says nothing at all, so it is caught the way Odin catches it: the
	 * server sends the death animation as an entity event, and on a dungeon
	 * floor that can hold a mimic the only baby zombie dying is the mimic.
	 */
	fun onMessage(message: String) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		val client = Minecraft.getInstance()

		if (princePattern.matches(message)) {
			if (alertPrince.value) announce(client, princeTitle.value, true)
			return
		}

		if (!batPattern.matches(message)) return
		batsSlain++
		if (alertBats.value && batsSlain >= BATS_PER_RUN) announce(client, batsTitle.value, true)
	}

	@JvmStatic
	fun onMimicKilled() {
		if (!module.enabled || !DungeonLocation.inDungeon || mimicAnnounced) return
		mimicAnnounced = true
		if (alertMimic.value) announce(Minecraft.getInstance(), mimicTitle.value, true)
	}

	/**
	 * Watches the checkmark on the room under the player.
	 *
	 * Ported from Odin's `RoomClear`, which does exactly this and nothing else,
	 * and is right for a reason worth writing down: what clears a room is not
	 * one thing. Some rooms are a handful of starred mobs, some are a mini boss,
	 * and Stairs is a mini boss *and* starred mobs. Hypixel already knows the
	 * answer and puts it on the map — white for cleared, green for cleared with
	 * every secret — so reading the checkmark handles all three cases for free.
	 *
	 * An earlier version counted secrets itself to catch the case where the
	 * secrets finish before the mobs do. Hypixel's map has no colour for that,
	 * so it had to be inferred, and inferring it is what made this unreliable.
	 */
	fun tick(client: Minecraft) {
		if (!module.enabled || !DungeonLocation.inDungeon || DungeonRun.inBoss) {
			forget()
			return
		}

		val room = DungeonMap.currentRoom()
		if (room !== watched) {
			// Walking into a room is not news about it, so whatever it already
			// says is taken as the baseline.
			watched = room
			watchedState = room?.state
			return
		}

		if (room == null || !announces(room)) return
		val state = room.state
		if (state == watchedState) return
		watchedState = state

		when (state) {
			DungeonRoom.State.CLEARED -> if (alertCleared.value) {
				announce(client, clearedTitle.value, false)
			}
			DungeonRoom.State.GREEN -> {
				val finished = roomComplete.value
				if (finished) announce(client, completeTitle.value, true)
				else if (alertSecrets.value) announce(client, secretsTitle.value, true)
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
