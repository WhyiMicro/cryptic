package imicro.cryptic.dungeon

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.PlayerSkin

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

	/**
	 * The skin on each teammate's own row of the tab list.
	 *
	 * Hypixel draws the row with the player's head, so the row carries their
	 * skin even when their own player entry has not been sent — which is when
	 * looking the name up came back empty, and the leap menu used to fall back
	 * to your own face for them.
	 */
	var tabSkins: Map<String, PlayerSkin> = emptyMap()
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
		var skins: MutableMap<String, PlayerSkin>? = null
		var foundCatacombs = false

		// In the order the rows are drawn in, which is not the order the
		// connection keeps its players in — that one is however they happened
		// to be hashed. The difference matters because the party's order in the
		// tab list is also the order Hypixel puts their markers on the dungeon
		// map, and the map has nothing else to say who is who by. Sorted the
		// wrong way, every head on the map belongs to somebody else.
		for (playerInfo in connection.listedOnlinePlayers.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())) {
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
			(skins ?: mutableMapOf<String, PlayerSkin>().also { skins = it })[name] = playerInfo.skin
		}

		inDungeons = foundCatacombs
		classes = if (foundCatacombs) updated ?: emptyMap() else emptyMap()
		dead = if (foundCatacombs) fallen ?: emptySet() else emptySet()
		tabSkins = if (foundCatacombs) skins ?: emptyMap() else emptyMap()
	}

	/**
	 * The party in tab-list order, against the markers the map has for them.
	 *
	 * The dungeon map says where five people are and nothing at all about who
	 * they are: the first marker belongs to the first living teammate in the
	 * tab list, the second to the second, and so on. That is the whole of the
	 * pairing, and when it is wrong every head on the map is somebody else — so
	 * this prints both halves of it, in order, to be read against the map.
	 */
	fun describePairing(): List<String> {
		val self = Minecraft.getInstance().player?.name?.string
		if (!inDungeons) return listOf("§7Not in a dungeon, so there is no party to pair.")

		val living = classes.keys.filter { it != self && it !in dead }
		val markers = imicro.cryptic.dungeon.map.DungeonMapReader.markers

		val lines = mutableListOf(
			"§7Party in tab order (§f${classes.size}§7), markers on the map (§f${markers.size}§7):",
		)
		classes.keys.forEachIndexed { index, name ->
			val note = when {
				name == self -> "§8you, drawn from the world"
				name in dead -> "§8dead, no marker"
				else -> {
					val slot = living.indexOf(name)
					val marker = markers.getOrNull(slot)
					if (marker == null) {
						"§cno marker §8(slot $slot)"
					} else {
						"§7marker §f$slot §8at ${marker.mapX}, ${marker.mapZ}"
					}
				}
			}
			lines += "§8${index + 1}. §f$name §8— ${classes[name]?.name?.lowercase()} §8— $note"
		}
		if (markers.size > living.size) {
			lines += "§cMore markers than living teammates: the pairing will be wrong."
		}
		return lines
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
		tabSkins = emptyMap()
		ticksUntilRefresh = 0
	}
}
