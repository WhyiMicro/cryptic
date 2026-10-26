package imicro.cryptic.dungeon

import imicro.cryptic.debug.DebugOverrides
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/** Which Catacombs floor you're on, from the sidebar. Runs only while something wants it. */
object DungeonLocation {
	/** The sidebar's location line, e.g. "⏣ The Catacombs (M7)". The entrance is written "(E)". */
	private val floorPattern = Regex("""The Catacombs \((?:([FM])(\d)|E)\)""")

	private val formattingPattern = Regex("§.")

	private const val REFRESH_INTERVAL_TICKS = 10

	private var ticksUntilRefresh = 0

	/**
	 * The world the floor was read in. The sidebar can drop its location line part way through a
	 * run (M7's dragon phase does), so a floor once read holds until the world changes, as
	 * NoammAddons keeps it.
	 */
	private var readIn: Any? = null

	/** The floor number; 0 on the entrance and outside a dungeon. */
	var floor = 0
		private set

	var masterMode = false
		private set

	/** Separate from [floor], since the entrance is also floor 0. */
	var inDungeon = false
		private set

	/**
	 * How much of the floor's secrets count toward the score; Hypixel scales it by floor. From
	 * dtMap (who took it from Odin).
	 */
	val secretFactor: Float
		get() = when {
			masterMode -> 1f
			floor == 0 || floor == 1 -> 0.3f
			floor == 2 -> 0.4f
			floor == 3 -> 0.5f
			floor == 4 -> 0.6f
			floor == 5 -> 0.7f
			floor == 6 -> 0.85f
			else -> 1f
		}

	val inFloor7: Boolean get() = floor == 7

	/** Twice a second; the floor only changes when a dungeon starts or ends. */
	fun tick(client: Minecraft, active: Boolean) {
		val overriddenFloor = DebugOverrides.dungeonFloor
		if (overriddenFloor != null) {
			floor = overriddenFloor
			masterMode = DebugOverrides.dungeonMasterMode
			inDungeon = true
			return
		}

		if (!active) {
			clear()
			return
		}

		val level = client.level
		if (level == null) {
			clear()
			return
		}

		if (ticksUntilRefresh-- > 0) return
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS

		val scoreboard = level.scoreboard
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
		if (objective == null) {
			if (!(inDungeon && readIn === level)) clear()
			return
		}

		for (entry in scoreboard.listPlayerScores(objective)) {
			// A sidebar line is an entry name inside its team's prefix and suffix.
			val team = scoreboard.getPlayersTeam(entry.owner())
			val line = PlayerTeam.formatNameForTeam(team, entry.ownerName()).string
				.replace(formattingPattern, "")

			// The dungeon finder shows the floor while queueing too.
			if (line.contains("Queue", ignoreCase = true)) continue

			val match = floorPattern.find(line) ?: continue
			floor = match.groupValues[2].toIntOrNull() ?: 0
			masterMode = match.groupValues[1] == "M"
			inDungeon = true
			readIn = level
			return
		}

		if (inDungeon && readIn === level) return
		floor = 0
		masterMode = false
		inDungeon = false
	}

	private fun clear() {
		readIn = null
		if (floor == 0 && !masterMode && !inDungeon && ticksUntilRefresh == 0) return
		floor = 0
		masterMode = false
		inDungeon = false
		ticksUntilRefresh = 0
	}
}
