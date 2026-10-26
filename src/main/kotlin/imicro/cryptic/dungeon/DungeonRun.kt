package imicro.cryptic.dungeon

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Where a dungeon run is, from chat: Mort handing over the map starts it, a boss greeting means the
 * boss room, the score summary ends it. Ported from dtMap.
 */
object DungeonRun {
	private const val START_MESSAGE = "[NPC] Mort: Here, I found this map when I first entered the dungeon."

	private val bossEntryMessages = setOf(
		"[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.",
		"[BOSS] Scarf: This is where the journey ends for you, Adventurers.",
		"[BOSS] The Professor: I was burdened with terrible news recently...",
		"[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!",
		"[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.",
		"[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!",
		"[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
	)

	private val FORMATTING = Regex("§.")

	private val endPattern = Regex("""^\s+(?:Master Mode )?The Catacombs - (?:Entrance|Floor [IVX]+)$""")

	var started = false
		private set

	private var heardBoss = false

	/**
	 * True in the boss room: from the boss's greeting, or from standing in the boss area (Odin's
	 * check), since mods that hide boss dialogue also hide the greeting.
	 */
	val inBoss: Boolean
		get() = heardBoss || inBossArea()

	/** Odin's bounds: every boss room is east and south of the floor's rooms. */
	private fun inBossArea(): Boolean {
		if (!DungeonLocation.inDungeon) return false
		val player = Minecraft.getInstance().player ?: return false
		val x = player.x
		val z = player.z
		return when (DungeonLocation.floor) {
			1 -> x > -71 && z > -39
			in 2..4 -> x > -39 && z > -39
			in 5..6 -> x > -39 && z > -7
			7 -> x > -7 && z > -7
			else -> false
		}
	}

	var ended = false
		private set

	/**
	 * Whether a baby zombie dying now could be the mimic: floors 6 and 7 only, before the boss, and
	 * only once. (A Berserk ability in the boss room is a real entity that dies.)
	 */
	fun mimicCouldDieNow(): Boolean =
		DungeonLocation.inDungeon &&
			!inBoss &&
			(DungeonLocation.floor == 6 || DungeonLocation.floor == 7)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// Lines are matched without colour codes (party chat carries them). Read from the packet in
		// [onSystemChat], since mods that hide a line cancel Fabric's chat event. Each floor is a
		// new server, so the state resets on join.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		onMessage(message.string.replace(FORMATTING, ""))
	}

	fun reset() {
		started = false
		heardBoss = false
		ended = false
	}

	private fun onMessage(line: String) {
		when {
			line == START_MESSAGE -> started = true
			bossEntryMessages.contains(line) -> heardBoss = true
			endPattern.matches(line) -> ended = true
		}

		DungeonStats.onMessage(line)
	}
}
