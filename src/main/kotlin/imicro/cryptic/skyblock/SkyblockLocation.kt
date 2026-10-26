package imicro.cryptic.skyblock

import imicro.cryptic.debug.DebugOverrides
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/**
 * Whether you're on SkyBlock and on which island, after Odin. SkyBlock's sidebar objective is
 * always `SBScoreboard`; the island comes from the tab list's `Area:` row, since the sidebar only
 * names the part of it you're in.
 */
object SkyblockLocation {
	private const val OBJECTIVE_NAME = "SBScoreboard"

	private const val REFRESH_INTERVAL_TICKS = 10

	private val areaPattern = Regex("""^(?:Area|Dungeon): (.+)$""")

	private val formattingPattern = Regex("§.")

	private var ticksUntilRefresh = 0

	var onSkyblock = false
		private set

	var area: String? = null
		private set

	val inCrystalHollows: Boolean
		get() = (onSkyblock && area == "Crystal Hollows") || DebugOverrides.hollowsZone != null

	fun tick(client: Minecraft, active: Boolean) {
		if (!active || client.level == null) {
			clear()
			return
		}
		if (ticksUntilRefresh-- > 0) return
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS

		val objective = client.level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR)
		onSkyblock = objective?.name == OBJECTIVE_NAME
		area = if (onSkyblock) readArea(client) else null
	}

	private fun readArea(client: Minecraft): String? {
		val connection = client.connection ?: return null
		for (info in connection.onlinePlayers) {
			val row = info.tabListDisplayName?.string?.replace(formattingPattern, "")?.trim() ?: continue
			areaPattern.find(row)?.let { return it.groupValues[1].trim() }
		}
		return null
	}

	/** The sidebar's date line ("10/01/26 m123AB"); the second part names the server. */
	private val lobbyPattern = Regex("""\d\d/\d\d/\d\d (\w{1,6})""")

	/** The server this lobby runs on, from the sidebar. Odin's relay rooms are keyed on it. */
	fun lobbyId(): String? {
		val scoreboard = Minecraft.getInstance().level?.scoreboard ?: return null
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
		for (entry in scoreboard.listPlayerScores(objective)) {
			val team = scoreboard.getPlayersTeam(entry.owner())
			val line = PlayerTeam.formatNameForTeam(team, entry.ownerName()).string.replace(formattingPattern, "")
			lobbyPattern.find(line)?.let { return it.groupValues[1] }
		}
		return null
	}

	/** The part of the island you're in ("Mines of Divan"), from the sidebar. */
	fun zone(): String? {
		DebugOverrides.hollowsZone?.let { return it }
		val scoreboard = Minecraft.getInstance().level?.scoreboard ?: return null
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
		val lines = scoreboard.listPlayerScores(objective)
			.sortedByDescending { it.value() }
			.map { entry ->
				val team = scoreboard.getPlayersTeam(entry.owner())
				PlayerTeam.formatNameForTeam(team, entry.ownerName()).string.replace(formattingPattern, "").trim()
			}
		return zoneFrom(lines, area)
	}

	fun zoneFrom(lines: List<String>, island: String?): String? {
		// Hypixel's area icon (U+E067, or U+E020 in the Rift) marks the location line.
		lines.firstOrNull { line -> line.any { it == AREA_ICON || it == RIFT_AREA_ICON } }?.let { line ->
			return clean(line.replace(AREA_ICON.toString(), "").replace(RIFT_AREA_ICON.toString(), ""))
		}
		val index = lines.indexOfFirst { line -> line.any { it == '⏣' || it == 'ф' } }
		if (index < 0) return null
		val marked = clean(lines[index].substringAfter('⏣').substringAfter('ф'))
		// Hypixel now writes the island on that line and the zone on the next.
		if (marked == island) {
			val next = lines.getOrNull(index + 1)?.let(::clean)
			if (!next.isNullOrEmpty() && next.first().isLetter()) return next
		}
		return marked
	}

	private const val AREA_ICON = '\uE067'
	private const val RIFT_AREA_ICON = '\uE020'

	private fun clean(line: String): String =
		line.filter { it.code < 0x2000 || it.isLetter() }.trim().trimStart { !it.isLetterOrDigit() }.trim()

	private fun clear() {
		onSkyblock = false
		area = null
		ticksUntilRefresh = 0
	}

	fun describe(): List<String> {
		val objective = Minecraft.getInstance().level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR)
		return listOf(
			"Sidebar objective: ${objective?.name ?: "none"}",
			"On SkyBlock: $onSkyblock, area: ${area ?: "unknown"}, Crystal Hollows: $inCrystalHollows",
			"Zone: ${zone() ?: "unknown"}",
		)
	}
}
