package imicro.cryptic.dungeon

import imicro.cryptic.dungeon.map.DungeonFloor
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

	/** Who has killed a bat; Hypixel gives one bonus point each, up to five. */
	private val bats = mutableSetOf<String>()

	/** Each puzzle the tab list has named, against its tick, cross or dot. */
	private val puzzles = mutableMapOf<String, Char>()

	val batCount: Int get() = bats.size.coerceAtMost(5)

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
		bats.clear()
		puzzles.clear()
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
		if (ticksUntilRefresh-- > 0) return
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
				if (name != "???") puzzles[name] = match.groupValues[2][0]
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
			princeKilled = true
			return
		}

		if (batPattern.matches(line)) {
			bats.add(Minecraft.getInstance().player?.name?.string ?: return)
			return
		}

		// Teammates announce their own kills through the party's tracker mods,
		// which is the only way to hear about a bat you did not kill yourself.
		val match = partyMessagePattern.find(line) ?: return
		val name = match.groupValues[2].lowercase()
		when (match.groupValues[3].lowercase().trimEnd('!')) {
			"mimic killed", "mimic slain", "mimic dead", "\$skytils-dungeon-score-mimic\$" -> mimicKilled = true
			"prince killed", "prince slain", "prince dead", "prince regicided",
			"\$skytils-dungeon-score-prince\$" -> princeKilled = true
			"bat killed", "bat dead" -> bats.add(name)
		}
	}

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
			batCount + crypts.coerceAtMost(5)

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
