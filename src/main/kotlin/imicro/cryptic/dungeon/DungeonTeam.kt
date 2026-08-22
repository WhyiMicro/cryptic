package imicro.cryptic.dungeon

import imicro.cryptic.debug.DebugOverrides
import net.minecraft.client.Minecraft

/**
 * Tracks who is in the dungeon party and what class each of them plays.
 *
 * Hypixel publishes this in the tab list, and more than one module needs it, so
 * the scan lives here rather than inside whichever feature happened to want it
 * first. It runs while any consumer is switched on and idles otherwise.
 */
object DungeonTeam {
	enum class DungeonClass(val initial: Char) {
		ARCHER('A'),
		BERSERK('B'),
		HEALER('H'),
		MAGE('M'),
		TANK('T'),
		UNKNOWN('?'),
	}

	private val teammatePattern = Regex(
		"""^\[(\d+)] (?:\[[^]]+] )*(\w+) .*?\((\w+)(?: (\w+))*\)$""",
	)

	private const val REFRESH_INTERVAL_TICKS = 10

	private var ticksUntilRefresh = 0

	/** True while the tab list shows the player is inside the Catacombs. */
	var inDungeons = false
		private set

	var classes: Map<String, DungeonClass> = emptyMap()
		private set

	/**
	 * Who is currently dead, in Hypixel's terms.
	 *
	 * A dead teammate keeps their row in the tab list but loses their marker on
	 * the dungeon map, so anything pairing markers with players has to know, or
	 * every head after the dead one is drawn as the wrong person.
	 */
	var dead: Set<String> = emptySet()
		private set

	fun classOf(name: String): DungeonClass? = classes[name]

	fun isTeammate(name: String): Boolean = classes.containsKey(name)

	/**
	 * Refreshing twice a second is immediate enough for tab-list data and tiny
	 * in cost. [active] is false when no module wants the data, which stops the
	 * scan without any module having to know about the others.
	 */
	fun tick(client: Minecraft, active: Boolean) {
		// A developer override stands in for the tab list, so class features can
		// be looked at without queueing a dungeon for them.
		val overriddenClass = DebugOverrides.dungeonClass
		if (overriddenClass != null) {
			val name = client.player?.name?.string
			inDungeons = true
			classes = if (name == null) emptyMap() else mapOf(name to overriddenClass)
			dead = emptySet()
			return
		}

		if (!active) {
			clear()
			return
		}

		val connection = client.connection
		if (client.level == null || connection == null) {
			clear()
			return
		}

		if (ticksUntilRefresh-- > 0) return
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS

		// One pass over the tab list instead of materializing all of its lines
		// first. Teammate rows always begin with the bracketed class level, so a
		// single character check keeps the regex away from every other entry.
		val previous = classes
		var updated: MutableMap<String, DungeonClass>? = null
		var fallen: MutableSet<String>? = null
		var foundCatacombs = false

		for (playerInfo in connection.onlinePlayers) {
			val line = playerInfo.tabListDisplayName?.string ?: continue

			// Odin identifies the Catacombs from Hypixel's Area/Dungeon tab entry.
			if (!foundCatacombs &&
				(line.startsWith("Area: ") || line.startsWith("Dungeon: ")) &&
				line.contains("Catacombs", ignoreCase = true)
			) {
				foundCatacombs = true
				continue
			}

			if (line.isEmpty() || line[0] != '[') continue
			val match = teammatePattern.matchEntire(line) ?: continue
			val name = match.groupValues[2]
			val className = match.groupValues[3]

			// Dead teammates are reported as DEAD; retain the class learned earlier.
			val dungeonClass = if (className.equals("DEAD", ignoreCase = true)) {
				(fallen ?: mutableSetOf<String>().also { fallen = it }).add(name)
				previous[name] ?: DungeonClass.UNKNOWN
			} else {
				parseClass(className)
			}

			val target = updated ?: mutableMapOf<String, DungeonClass>().also { updated = it }
			target[name] = dungeonClass
		}

		inDungeons = foundCatacombs
		classes = if (foundCatacombs) updated ?: emptyMap() else emptyMap()
		dead = if (foundCatacombs) fallen ?: emptySet() else emptySet()
	}

	private fun parseClass(name: String): DungeonClass = when {
		name.equals("Archer", ignoreCase = true) -> DungeonClass.ARCHER
		name.equals("Berserk", ignoreCase = true) -> DungeonClass.BERSERK
		name.equals("Healer", ignoreCase = true) -> DungeonClass.HEALER
		name.equals("Mage", ignoreCase = true) -> DungeonClass.MAGE
		name.equals("Tank", ignoreCase = true) -> DungeonClass.TANK
		else -> DungeonClass.UNKNOWN
	}

	private fun clear() {
		if (!inDungeons && classes.isEmpty() && ticksUntilRefresh == 0) return
		inDungeons = false
		classes = emptyMap()
		dead = emptySet()
		ticksUntilRefresh = 0
	}
}
