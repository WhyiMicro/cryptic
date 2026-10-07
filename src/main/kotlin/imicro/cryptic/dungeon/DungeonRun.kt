package imicro.cryptic.dungeon

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Where a dungeon run has got to, as told by chat.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who); the full
 * licence is in `licenses/dtMap-LICENSE.txt`. Three moments matter and none of
 * them is on the scoreboard: Mort handing over the map is the run starting, a
 * boss greeting you is the boss room, and the score summary is the run ending.
 * The map and the score both need to know, so the listening happens once here.
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

	/** The centred banner Hypixel prints once a run is over, one per floor. */
	/** Hypixel's colour codes, written into a line's text rather than its style. */
	private val FORMATTING = Regex("§.")

	private val endPattern = Regex("""^\s+(?:Master Mode )?The Catacombs - (?:Entrance|Floor [IVX]+)$""")

	/** True once Mort has handed over the map, which is the run starting. */
	var started = false
		private set

	/** True from the boss's greeting until the run is left. */
	private var heardBoss = false

	/**
	 * True in the boss room: from the boss's greeting, or from standing where
	 * the boss room is, as Odin decides it.
	 *
	 * The greeting alone was not enough. A mod that hides boss dialogue hides
	 * the greeting too, and Boss Waypoints then never once showed in a boss.
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

	/** True once the score summary has been printed. */
	var ended = false
		private set

	/**
	 * Whether a baby zombie dying right now could be the mimic.
	 *
	 * Odin's three conditions, and Cryptic had none of them: only floors 6 and
	 * 7 hold a mimic, only the clearing half of the run does, and only once.
	 * Without them any baby zombie dying anywhere counted — which is how a
	 * Berserk's ability ending in the boss room announced a mimic kill, since
	 * Hypixel builds that effect out of a real entity that dies when it stops.
	 */
	fun mimicCouldDieNow(): Boolean =
		DungeonLocation.inDungeon &&
			!inBoss &&
			(DungeonLocation.floor == 6 || DungeonLocation.floor == 7)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// Plain text from here on. Hypixel writes colour codes into some lines —
		// party chat above all, "§9Party §8> ..." — and every pattern the run
		// and the score match against is written without them, so a teammate's
		// "Mimic Killed!" in party chat was never once heard.
		// Heard from the packet, in [onSystemChat], rather than from Fabric's
		// chat event: a mod that hides a line cancels that event, and the boss
		// greeting is a line such mods hide.
		// Hypixel moves you between servers for every floor, so the run state
		// from the last one must not survive into the next.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	/** A chat packet, on the client thread, before any mod has hidden it. */
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
