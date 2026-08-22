package imicro.cryptic.dungeon

import imicro.cryptic.debug.DebugOverrides
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/**
 * Tracks which Catacombs floor the player is on.
 *
 * Hypixel writes the floor into the scoreboard sidebar, which is where
 * NoammAddons reads it from as well. The scan lives here rather than inside a
 * feature because every floor-specific module needs the same answer, and it
 * runs while any consumer is switched on and idles otherwise.
 */
object DungeonLocation {
	/**
	 * Matches Hypixel's sidebar location line, e.g. "⏣ The Catacombs (M7)".
	 *
	 * The entrance floor is written "(E)" rather than with a number, and it is
	 * a real place with a real map, so it is matched too.
	 */
	private val floorPattern = Regex("""The Catacombs \((?:([FM])(\d)|E)\)""")

	/** Strips leftover section-sign codes so the pattern sees plain text. */
	private val formattingPattern = Regex("§.")

	private const val REFRESH_INTERVAL_TICKS = 10

	private var ticksUntilRefresh = 0

	/** The floor number, 0 on the entrance floor and while outside a dungeon. */
	var floor = 0
		private set

	var masterMode = false
		private set

	/**
	 * True while the sidebar says the player is in the Catacombs at all.
	 *
	 * Kept apart from [floor] because the entrance floor is numbered zero, and
	 * "floor 0" and "not in a dungeon" are different answers.
	 */
	var inDungeon = false
		private set

	/**
	 * How much of a floor's secrets count towards its score.
	 *
	 * Hypixel scales the secret half of the exploration score by floor, so the
	 * earlier ones need far fewer secrets for the same points. Ported from dtMap
	 * (BSD 3-Clause, Copyright (c) 2026 rice.who), which took it from Odin.
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

	/** True on both Floor 7 and Master Mode 7, which share their boss fight. */
	val inFloor7: Boolean get() = floor == 7

	/**
	 * Refreshing twice a second is immediate enough: the floor only changes when
	 * a dungeon starts or ends. [active] is false when no module wants the data,
	 * which stops the scan without any module having to know about the others.
	 */
	fun tick(client: Minecraft, active: Boolean) {
		// A developer override stands in for the sidebar, so the score and the
		// map can be looked at without queueing a dungeon for them.
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
			clear()
			return
		}

		for (entry in scoreboard.listPlayerScores(objective)) {
			// A sidebar line is an entry name dressed in its team's prefix and
			// suffix, so the visible text only exists once the two are joined.
			val team = scoreboard.getPlayersTeam(entry.owner())
			val line = PlayerTeam.formatNameForTeam(team, entry.ownerName()).string
				.replace(formattingPattern, "")

			// The dungeon finder shows the same floor while queueing for it.
			if (line.contains("Queue", ignoreCase = true)) continue

			val match = floorPattern.find(line) ?: continue
			// The entrance floor matches with both groups empty, and is zero.
			floor = match.groupValues[2].toIntOrNull() ?: 0
			masterMode = match.groupValues[1] == "M"
			inDungeon = true
			return
		}

		floor = 0
		masterMode = false
		inDungeon = false
	}

	private fun clear() {
		if (floor == 0 && !masterMode && !inDungeon && ticksUntilRefresh == 0) return
		floor = 0
		masterMode = false
		inDungeon = false
		ticksUntilRefresh = 0
	}
}
