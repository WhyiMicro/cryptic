package imicro.cryptic.dungeon

import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.feature.DungeonScore
import imicro.cryptic.dungeon.map.DungeonRoom
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Everything Hypixel publishes about the run in progress, and the score it adds up to.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who), which took the
 * score arithmetic from Odin (BSD 3-Clause, Copyright (c) 2026 odtheking); the
 * full licence is in `licenses/dtMap-LICENSE.txt`. Hypixel never tells you your
 * score, but it does tell you every number the score is made of: the tab list
 * carries secrets, crypts, deaths and puzzles, the sidebar carries how much of
 * the floor is cleared, and chat carries the bonus kills.
 *
 * Reading it here rather than in the HUD element means the score, the map and
 * anything later all see one set of numbers.
 */
object DungeonStats {
	private const val REFRESH_INTERVAL_TICKS = 10

	private var ticksUntilRefresh = 0

	/**
	 * Set when the tab list or the sidebar has just changed, so the next tick
	 * reads it at once instead of waiting out the poll.
	 *
	 * The poll alone left every number up to half a second stale, and a score
	 * is only as quick as its slowest number — Odin re-reads the moment the tab
	 * list changes, which is why its 300 went out before this one did. Set from
	 * the network thread as well as the game thread, so it is a single flag and
	 * nothing more.
	 */
	@Volatile
	private var changed = false

	/** The tab list or the sidebar changed: read them on the next tick. */
	@JvmStatic
	fun markChanged() {
		changed = true
	}

	var secretsFound = 0
		private set
	var secretsPercent = 0f
		private set
	var crypts = 0
		private set
	var openedRooms = 0
		private set
	var completedRooms = 0
		private set
	var deaths = 0
		private set
	var percentCleared = 0
		private set
	var elapsedTime = "0s"
		private set
	var puzzleCount = 0
		private set
	var mimicKilled = false
		private set
	var princeKilled = false
		private set

	/**
	 * The mimic dying, as seen rather than as announced.
	 *
	 * The chat lines below only fire when somebody else's mod says so in party
	 * chat, so a party where nobody runs one left this permanently false and the
	 * score two points short all run. The death animation is the only signal
	 * Hypixel gives for it, and it comes to everybody.
	 */
	fun onMimicKilled() {
		if (mimicKilled || !DungeonLocation.inDungeon) return
		mimicKilled = true
		DungeonScore.onMimicKilled()
	}

	/**
	 * Whether a bat has been killed, which is worth one bonus point for the run.
	 *
	 * One, not one per bat: Hypixel's "A Bat has been slain. +1 Bonus Score" is
	 * the run's single bat point, and Odin counts it once. This used to count a
	 * point per bat up to five, which put the score up to four points too high
	 * once a second bat died — enough to call 300 before the run was there.
	 */
	var batKilled = false
		private set

	/** Each puzzle the tab list has named, against its tick, cross or dot. */
	private val puzzles = mutableMapOf<String, Char>()

	/** Whoever the tab list blames for a puzzle that was failed, where it names anybody. */
	private val puzzleFailers = mutableMapOf<String, String>()

	/**
	 * Every puzzle the tab list has named, in the order it named them: the name,
	 * its mark, and who failed it (empty where nobody did). The ones still
	 * written as ??? are not in here; [puzzleCount] says how many there are in all.
	 */
	val namedPuzzles: List<Triple<String, Char, String>>
		get() = puzzles.map { Triple(it.key, it.value, puzzleFailers[it.key].orEmpty()) }

	fun reset() {
		secretsFound = 0
		secretsPercent = 0f
		crypts = 0
		openedRooms = 0
		completedRooms = 0
		deaths = 0
		percentCleared = 0
		elapsedTime = "0s"
		puzzleCount = 0
		mimicKilled = false
		princeKilled = false
		batKilled = false
		puzzles.clear()
		puzzleFailers.clear()
		ticksUntilRefresh = 0
		MayorPaul.reset()
	}

	/**
	 * Refreshing twice a second is immediate enough: none of these numbers moves
	 * faster than a room is cleared. [active] is false when no module wants the
	 * data, which stops the scan without any module having to know about the others.
	 */
	fun tick(client: Minecraft, active: Boolean) {
		if (!active || client.level == null) return
		if (!changed && ticksUntilRefresh-- > 0) return
		changed = false
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS

		readTabList(client)
		readSidebar(client)

		// Whether Paul is mayor cannot change during a run, so it is asked once
		// per dungeon and remembered.
		if (DungeonLocation.inDungeon) MayorPaul.ensureFetched()
	}

	private fun readTabList(client: Minecraft) {
		val connection = client.connection ?: return
		for (playerInfo in connection.onlinePlayers) {
			val line = playerInfo.tabListDisplayName?.string ?: continue
			if (line.isEmpty()) continue

			secretPercentPattern.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let { secretsPercent = it }
			secretCountPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { secretsFound = it }
			completedRoomsPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { completedRooms = it }
			openedRoomsPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { openedRooms = it }
			puzzleCountPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { puzzleCount = it }
			deathsPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { deaths = it }
			cryptPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { crypts = it }
			timePattern.find(line)?.groupValues?.get(1)?.let { elapsedTime = it }

			puzzlePattern.find(line)?.let { match ->
				val name = match.groupValues[1]
				if (name != "???") {
					puzzles[name] = match.groupValues[2][0]
					val failer = match.groupValues[3]
					if (failer.isEmpty()) puzzleFailers.remove(name) else puzzleFailers[name] = failer
				}
			}
		}
	}

	/** How much of the floor is cleared only exists on the scoreboard sidebar. */
	private fun readSidebar(client: Minecraft) {
		val scoreboard = client.level?.scoreboard ?: return
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return

		for (entry in scoreboard.listPlayerScores(objective)) {
			val team = scoreboard.getPlayersTeam(entry.owner())
			val line = PlayerTeam.formatNameForTeam(team, entry.ownerName()).string
				.replace(formattingPattern, "")
			clearedPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
				percentCleared = it
				return
			}
		}
	}

	/** Bonus points are announced in chat, and nowhere else. */
	fun onMessage(line: String) {
		if (!DungeonLocation.inDungeon) return

		if (princePattern.matches(line)) {
			val first = !princeKilled
			princeKilled = true
			if (first) DungeonScore.onPrinceKilled()
			return
		}

		if (batPattern.matches(line)) {
			val first = !batKilled
			batKilled = true
			// Hypixel tells only the killer, so the party hears it from here.
			if (first) DungeonScore.onBatKilled()
			return
		}

		// A mimic killed by a charm is announced by Hypixel itself, to whoever
		// cast it — NoammAddons' catch, for a death nobody else may see.
		if (line.contains(MIMIC_CHARMED, ignoreCase = true)) {
			onMimicKilled()
			return
		}

		// Teammates announce their own kills through the party's tracker mods,
		// which is the only way to hear about one out of sight. Each mod words
		// it its own way — "Mimic Killed!" (Odin), "Mimic dead!" (Skyblocker),
		// "Mimic Killed" (NoammAddons), a code for Skytils, and flavour text
		// besides — and some put a tag in front, so the wording is looked for
		// anywhere in the message rather than matched whole.
		val match = partyMessagePattern.find(line) ?: return
		val said = match.groupValues[3].lowercase()
		when {
			DungeonLocation.floor >= 6 && MIMIC_MESSAGES.any { it in said } -> mimicKilled = true
			PRINCE_MESSAGES.any { it in said } -> princeKilled = true
			BAT_MESSAGES.any { it in said } -> batKilled = true
			// A teammate's score reaching 300 first, which is worth a title now.
			"300 score" in said -> DungeonScore.onTeammateReached300()
		}
	}

	/** Hypixel's line for a mimic dying to a charm, e.g. "...You charmed a Mimic and captured 2 shards from it." */
	private const val MIMIC_CHARMED = "You charmed a Mimic"

	/** Every wording the party's mods use, lower case, as NoammAddons collected them. */
	private val MIMIC_MESSAGES = listOf(
		"mimic dead", "mimic killed", "mimic slain", "\$skytils-dungeon-score-mimic\$",
		"child destroyed", "mimic obliterated", "mimic exorcised", "mimic destroyed",
		"mimic annhilated", "mimic annihilated", "breefing killed", "breefing dead",
	)
	private val PRINCE_MESSAGES = listOf(
		"prince dead", "prince killed", "prince slain", "prince regicided", "\$skytils-dungeon-score-prince\$",
	)
	private val BAT_MESSAGES = listOf(
		"bat dead", "bat killed", "bat slain", "\$skytils-dungeon-score-bat\$",
	)

	/** True once the blood room has been cleared, which the score counts as a room. */
	private val bloodDone: Boolean
		get() = DungeonFloor.rooms.any {
			it.type == DungeonRoom.Type.BLOOD && it.state == DungeonRoom.State.GREEN
		}

	/**
	 * How many secrets the floor holds, worked out from the percentage Hypixel
	 * shows next to how many have been found.
	 */
	val totalSecrets: Int
		get() = if (secretsFound == 0 || secretsPercent == 0f) 0
		else floor(100 / secretsPercent * secretsFound + 0.5).toInt()

	private val totalRooms: Int
		get() = if (completedRooms == 0 || percentCleared == 0) 0
		else floor(completedRooms / (percentCleared * 0.01f) + 0.4).toInt()

	/** The bonus points already banked, Paul aside. */
	private val bonusWithoutPaul: Int
		get() = (if (mimicKilled) 2 else 0) + (if (princeKilled) 1 else 0) +
			(if (batKilled) 1 else 0) + crypts.coerceAtMost(5)

	/** Paul's mayoral perk is worth ten points on top of everything else. */
	val paulScore: Int get() = if (MayorPaul.active) 10 else 0

	val bonusScore: Int get() = bonusWithoutPaul + paulScore

	/**
	 * The score as Hypixel will award it, assuming full marks for time.
	 *
	 * The blood room and the boss room are both counted as rooms you are going
	 * to complete, because you are: the score you care about is the one at the
	 * end of the run, not the one right now.
	 */
	val score: Int
		get() {
			val completed = completedRooms + (if (!bloodDone) 1 else 0) + (if (!DungeonRun.inBoss) 1 else 0)
			val total = if (totalRooms != 0) totalRooms else 36
			val factor = DungeonLocation.secretFactor

			val secretScore = if (totalSecrets > 0) {
				floor(secretsFound.toDouble() / (totalSecrets.toDouble() * factor) * 40.0)
					.toInt().coerceIn(0, 40)
			} else {
				0
			}

			val exploration = secretScore + floor(completed.toFloat() / total * 60f).coerceIn(0f, 60f).toInt()
			val skillRooms = floor(completed.toFloat() / total * 80f).coerceIn(0f, 80f).toInt()
			val puzzlePenalty = (puzzleCount - puzzles.count { it.value == '✔' }) * 10
			val deathPenalty = (deaths * 2 - 1).coerceAtLeast(0)
			val skill = (20 + skillRooms - puzzlePenalty - deathPenalty).coerceIn(20, 100)

			return exploration + skill + 100 + bonusScore
		}

	/**
	 * How many more secrets are needed before the secret half of the score stops
	 * being what is holding the run back.
	 */
	val missingSecrets: Int
		get() {
			val deathPenalty = (deaths * 2 - 1).coerceAtLeast(0)
			val needed = ceil(
				totalSecrets * DungeonLocation.secretFactor * (40 - bonusWithoutPaul + deathPenalty) / 40f,
			).toInt()
			return (needed - secretsFound).coerceAtLeast(0)
		}

	private val formattingPattern = Regex("§.")
	private val timePattern = Regex("""^ Time: ((?:\d+h ?)?(?:\d+m ?)?\d+s)$""")
	private val cryptPattern = Regex("""^ Crypts: (\d+)$""")
	private val deathsPattern = Regex("""^Team Deaths: (\d+)$""")
	private val puzzleCountPattern = Regex("""^Puzzles: \((\d+)\)$""")
	private val openedRoomsPattern = Regex("""^ Opened Rooms: (\d+)$""")
	private val secretCountPattern = Regex("""^ Secrets Found: (\d+)$""")
	private val completedRoomsPattern = Regex("""^ Completed Rooms: (\d+)$""")
	private val secretPercentPattern = Regex("""^ Secrets Found: ([\d.]+)%$""")
	private val puzzlePattern = Regex("""^ (\w+(?: \w+)*|\?\?\?): \[([✖✔✦])] ?(?:\((\w+)\))?$""")
	private val clearedPattern = Regex("""^Cleared: (\d+)% \(\d+\)$""")
	private val partyMessagePattern = Regex("""Party > (?:\[(.*?)] )?(.+?): (.+)$""")
	private val princePattern = Regex("""^A Prince falls\. \+1 Bonus Score$""")
	private val batPattern = Regex("""^A Bat has been slain\. \+1 Bonus Score$""")
}
