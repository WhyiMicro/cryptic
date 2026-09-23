package imicro.cryptic.skyblock

import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

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

	val inCrystalHollows: Boolean get() = onSkyblock && area == "Crystal Hollows"

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
		)
	}
}
