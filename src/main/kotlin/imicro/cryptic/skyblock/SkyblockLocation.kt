package imicro.cryptic.skyblock

import imicro.cryptic.debug.DebugOverrides
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/**
 * Whether the player is on SkyBlock at all, and which island.
 *
 * Both answers are Odin's (BSD 3-Clause, Copyright (c) 2025 odtheking). The
 * first comes from the sidebar: Hypixel names SkyBlock's objective
 * `SBScoreboard`, which no other game on the network uses, so the check does not
 * depend on how the title happens to be worded or coloured this week. The
 * second comes from the tab list, which carries an `Area: Crystal Hollows` row
 * for the island itself — the sidebar only names the part of it you are
 * standing in, and the Crystal Hollows has a dozen of those.
 *
 * Idles while nothing wants it, like the dungeon scans do.
 */
object SkyblockLocation {
	private const val OBJECTIVE_NAME = "SBScoreboard"

	/** An area rarely changes without a server hop, so twice a second is plenty. */
	private const val REFRESH_INTERVAL_TICKS = 10

	/** `Area: Crystal Hollows`, or `Dungeon: Catacombs` inside one. */
	private val areaPattern = Regex("""^(?:Area|Dungeon): (.+)$""")

	private val formattingPattern = Regex("§.")

	private var ticksUntilRefresh = 0

	var onSkyblock = false
		private set

	/** The island's name as the tab list writes it, or null when it is not saying. */
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

	/** The sidebar's date line: "10/01/26 m123AB", the second half naming the server. */
	private val lobbyPattern = Regex("""\d\d/\d\d/\d\d (\w{1,6})""")

	/**
	 * The server this lobby runs on, as the sidebar's top line names it, or null
	 * when there is no such line. Odin's relays key their rooms on it, so two
	 * players in the same dungeon land in the same room. Read when asked rather
	 * than every tick, since it is asked about once a boss fight.
	 */
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

	/**
	 * The part of the island you are in, as the sidebar's location line names
	 * it — "Mines of Divan", "Crystal Nucleus" — or null without one. Read when
	 * asked.
	 */
	fun zone(): String? {
		DebugOverrides.hollowsZone?.let { return it }
		val scoreboard = Minecraft.getInstance().level?.scoreboard ?: return null
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
		// Top to bottom, as the sidebar shows them: highest score first.
		val lines = scoreboard.listPlayerScores(objective)
			.sortedByDescending { it.value() }
			.map { entry ->
				val team = scoreboard.getPlayersTeam(entry.owner())
				PlayerTeam.formatNameForTeam(team, entry.ownerName()).string.replace(formattingPattern, "").trim()
			}
		return zoneFrom(lines, area)
	}

	/** The zone named by sidebar [lines], top to bottom, on the island [island]. */
	fun zoneFrom(lines: List<String>, island: String?): String? {
		// Hypixel's area icon is a glyph from its resource pack, U+E067 (U+E020 in
		// the Rift), on the line that names where you are — Skyblocker's way of
		// reading it.
		lines.firstOrNull { line -> line.any { it == AREA_ICON || it == RIFT_AREA_ICON } }?.let { line ->
			return clean(line.replace(AREA_ICON.toString(), "").replace(RIFT_AREA_ICON.toString(), ""))
		}
		val index = lines.indexOfFirst { line -> line.any { it == '⏣' || it == 'ф' } }
		if (index < 0) return null
		val marked = clean(lines[index].substringAfter('⏣').substringAfter('ф'))
		// Hypixel now writes the island on that line and the part of it you are
		// in on the next, behind an icon of its own: "⏣ Crystal Hollows", then
		// "Mines of Divan". The next line is the zone then, when it has one.
		if (marked == island) {
			val next = lines.getOrNull(index + 1)?.let(::clean)
			if (!next.isNullOrEmpty() && next.first().isLetter()) return next
		}
		return marked
	}

	private const val AREA_ICON = '\uE067'
	private const val RIFT_AREA_ICON = '\uE020'

	/** A sidebar line without its icons and the odd glyphs Hypixel pads lines with. */
	private fun clean(line: String): String =
		line.filter { it.code < 0x2000 || it.isLetter() }.trim().trimStart { !it.isLetterOrDigit() }.trim()

	private fun clear() {
		onSkyblock = false
		area = null
		ticksUntilRefresh = 0
	}

	/** For `/cryptic debug location`. */
	fun describe(): List<String> {
		val objective = Minecraft.getInstance().level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR)
		return listOf(
			"Sidebar objective: ${objective?.name ?: "none"}",
			"On SkyBlock: $onSkyblock, area: ${area ?: "unknown"}, Crystal Hollows: $inCrystalHollows",
			"Zone: ${zone() ?: "unknown"}",
		)
	}
}
