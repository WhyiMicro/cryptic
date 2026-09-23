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

	/**
	 * The three titles that report on the room you are standing in.
	 *
	 * One heading rather than three, because they are three answers to the same
	 * question — is there anything left to do here — and reading them together is
	 * how you pick which of them you want.
	 */
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

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is TextModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				else -> Unit
			}
		}
		// The score's announcements sit on this card now.
		DungeonScore.resetSettings()
	})

	@JvmField
	val module = Module(
		id = "room_alerts",
		name = "Alerts",
		description = "Titles and party messages for a run",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(titlesSection, alertCleared, alertSecrets) +
			DungeonScore.titleSettings +
			listOf(announcementsSection) + DungeonScore.announcementSettings +
			DungeonScore.customSettings +
			listOf(soundSection, playSound, volume, reset),
	)

	private var initialized = false

	/** Bonus-point kills, which reset with the run rather than with the room. */
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
	 * A prince dying, which Hypixel says in chat but only to you.
	 *
	 * The kills are announcements now rather than titles — a party message says
	 * the thing the party cannot otherwise know, and a title over your own screen
	 * for a mob you just killed says what you already saw. The wording for all
	 * three lives with the score, which is what counts them.
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
	@JvmStatic
	fun onCheckmarkChanged(room: DungeonRoom) {
		if (!module.enabled || !DungeonLocation.inDungeon || DungeonRun.inBoss) return
		// The room you are standing in, and no other. A checkmark appearing
		// across the floor is somebody else's news.
		if (room !== DungeonMap.currentRoom()) return
		if (!announces(room)) return

		val client = Minecraft.getInstance()
		when (room.state) {
			DungeonRoom.State.CLEARED -> if (alertCleared.value) announce(client, "Cleared", false)
			DungeonRoom.State.GREEN -> if (alertSecrets.value) announce(client, "Secrets Done!", true)
			else -> Unit
		}
	}

	/**
	 * Whether this kind of room is worth announcing.
	 *
	 * Odin's rule, which is everything except the two rooms that clear
	 * themselves: the entrance, which is green the moment the run starts, and
	 * the fairy room, which has nothing in it to clear.
	 *
	 * Cryptic used to allow only normal, rare and trap rooms, plus Blaze — and
	 * that is what made this look unreliable, because a miniboss room or a
	 * solved puzzle turning green said nothing at all.
	 */
	private fun announces(room: DungeonRoom): Boolean =
		room.type != DungeonRoom.Type.ENTRANCE && room.type != DungeonRoom.Type.FAIRY

	private fun announce(client: Minecraft, text: String, done: Boolean) {
		val message = text.trim()
		if (message.isEmpty()) return

		client.gui.hud.setTimes(FADE_IN_TICKS, STAY_TICKS, FADE_OUT_TICKS)
		client.gui.hud.setTitle(Component.literal(if (done) "§a$message" else message))

		playAlertSound(client)
	}

	/** The pling that goes with every title this module puts on screen. */
	fun playAlertSound(client: Minecraft) {
		if (!module.enabled || !playSound.value) return
		client.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), volume.value.toFloat(), 1f)
	}

	/** Shows the cleared title, for `/cryptic debug roomalerts`. */
	fun preview(client: Minecraft) {
		announce(client, "Cleared", false)
	}
}
