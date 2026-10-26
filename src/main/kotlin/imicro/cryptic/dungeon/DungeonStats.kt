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
 * Everything Hypixel shows about the run, and the score it adds up to: secrets, crypts, deaths and
 * puzzles from the tab list, cleared percentage from the sidebar, bonus kills from chat. Ported
 * from dtMap (score maths from Odin).
 */
object DungeonStats {
	private const val REFRESH_INTERVAL_TICKS = 10

	private var ticksUntilRefresh = 0

	/**
	 * Set when the tab list or sidebar changes, so the next tick reads them at once instead of
	 * waiting for the poll. Set from both threads.
	 */
	@Volatile
	private var changed = false

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
	 * The mimic's death animation, seen by everyone, so the score is right even without party mods
	 * announcing it.
	 */
	fun onMimicKilled() {
		if (mimicKilled || !DungeonLocation.inDungeon) return
		mimicKilled = true
		DungeonScore.onMimicKilled()
	}

	/**
	 * Who has killed a bat; one point per player, as Odin counts it since SkyBlock 0.27.2. Only the
	 * killer gets Hypixel's message; teammates are heard through their mods in party chat.
	 */
	private val batKillers = HashSet<String>()

	private val puzzles = mutableMapOf<String, Char>()

	private val puzzleFailers = mutableMapOf<String, String>()

	/**
	 * Every puzzle the tab list has named: name, mark, and who failed it. ??? entries aren't
	 * included; see [puzzleCount].
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
		batKillers.clear()
		puzzles.clear()
		puzzleFailers.clear()
		ticksUntilRefresh = 0
		MayorPaul.reset()
	}

	/** Twice a second; none of these numbers moves faster than a room is cleared. */
	fun tick(client: Minecraft, active: Boolean) {
		if (!active || client.level == null) return
		if (!changed && ticksUntilRefresh-- > 0) return
		changed = false
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS

		readTabList(client)
		readSidebar(client)

		// Paul can't change mid-run, so it's asked once per dungeon.
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

	fun onMessage(line: String) {
		if (!DungeonLocation.inDungeon) return

		if (princePattern.matches(line)) {
			val first = !princeKilled
			princeKilled = true
			if (first) DungeonScore.onPrinceKilled()
			return
		}

		if (batPattern.matches(line)) {
			val name = net.minecraft.client.Minecraft.getInstance().player?.name?.string ?: "you"
			val first = batKillers.add(name)
			// Only the killer is told, so the party hears it from here.
			if (first) DungeonScore.onBatKilled()
			return
		}

		// A charm kill is announced by Hypixel to the caster (NoammAddons' catch).
		if (line.contains(MIMIC_CHARMED, ignoreCase = true)) {
			onMimicKilled()
			return
		}

		// Teammates' mods announce kills in party chat, each worded differently, sometimes with a
		// tag in front, so the wording is searched for anywhere in the message.
		val match = partyMessagePattern.find(line) ?: return
		val said = match.groupValues[3].lowercase()
		when {
			DungeonLocation.floor >= 6 && MIMIC_MESSAGES.any { it in said } -> mimicKilled = true
			PRINCE_MESSAGES.any { it in said } -> princeKilled = true
			BAT_MESSAGES.any { it in said } -> batKillers += match.groupValues[2].substringBefore(' ')
			"300 score" in said -> DungeonScore.onTeammateReached300()
		}
	}

	private const val MIMIC_CHARMED = "You charmed a Mimic"

	/** Every wording party mods use, lower case, as NoammAddons collected them. */
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

	private val bloodDone: Boolean
		get() = DungeonFloor.rooms.any {
			it.type == DungeonRoom.Type.BLOOD && it.state == DungeonRoom.State.GREEN
		}

	/** The floor's total secrets, worked out from the found count and percentage. */
	val totalSecrets: Int
		get() = if (secretsFound == 0 || secretsPercent == 0f) 0
		else floor(100 / secretsPercent * secretsFound + 0.5).toInt()

	private val totalRooms: Int
		get() = if (completedRooms == 0 || percentCleared == 0) 0
		else floor(completedRooms / (percentCleared * 0.01f) + 0.4).toInt()

	private val bonusWithoutPaul: Int
		get() = (if (mimicKilled) 2 else 0) + (if (princeKilled) 1 else 0) +
			batKillers.size + crypts.coerceAtMost(5)

	val paulScore: Int get() = if (MayorPaul.active) 10 else 0

	val bonusScore: Int get() = bonusWithoutPaul + paulScore

	/**
	 * The score Hypixel will award, assuming full time marks. Blood and boss count as completed
	 * rooms, since they will be.
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

	/** How many more secrets until secrets stop holding the score back. */
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
