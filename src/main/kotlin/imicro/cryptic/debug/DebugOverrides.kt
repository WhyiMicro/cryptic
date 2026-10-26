package imicro.cryptic.debug

import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import java.util.Locale

/**
 * Switches from `/cryptic debug` that make dungeon-only features testable outside a dungeon. Memory
 * only, so a forgotten switch doesn't carry into the next session.
 */
object DebugOverrides {
	var outlineEveryWither = false
		private set

	var dungeonClass: DungeonClass? = null
		private set

	/** Overrides the floor the scoreboard names, or null to read it. */
	var dungeonFloor: Int? = null
		private set

	var dungeonMasterMode = false
		private set

	var forceWitherCloak = false
		private set

	var previewLeapMessage = false
		private set

	var previewScoreMessage = false
		private set

	/** Treats every armour stand as a door key, so the highlight can be tried with `/summon armor_stand`. */
	var highlightEveryArmorStand = false
		private set

	/** Lets the Terracotta Timer run off any flower pot, anywhere. */
	var terracottaAnywhere = false
		private set

	/** Runs Slayer Features outside the End, so it can be tried on summoned, renamed mobs. */
	var slayerAnywhere = false
		private set

	var previewPartyCommands = false
		private set

	var sampleHudValues = false
		private set

	val classNames: List<String> = DungeonClass.entries
		.filter { it != DungeonClass.UNKNOWN }
		.map { it.name.lowercase(Locale.ROOT) }

	fun toggleOutlineEveryWither(): Boolean {
		outlineEveryWither = !outlineEveryWither
		return outlineEveryWither
	}

	fun toggleWitherCloak(): Boolean {
		forceWitherCloak = !forceWitherCloak
		return forceWitherCloak
	}

	fun toggleLeapPreview(): Boolean {
		previewLeapMessage = !previewLeapMessage
		return previewLeapMessage
	}

	fun toggleScorePreview(): Boolean {
		previewScoreMessage = !previewScoreMessage
		return previewScoreMessage
	}

	fun toggleArmorStandKeys(): Boolean {
		highlightEveryArmorStand = !highlightEveryArmorStand
		return highlightEveryArmorStand
	}

	fun toggleTerracottaAnywhere(): Boolean {
		terracottaAnywhere = !terracottaAnywhere
		return terracottaAnywhere
	}

	fun toggleSlayerAnywhere(): Boolean {
		slayerAnywhere = !slayerAnywhere
		return slayerAnywhere
	}

	fun togglePartyPreview(): Boolean {
		previewPartyCommands = !previewPartyCommands
		return previewPartyCommands
	}

	fun toggleSampleHudValues(): Boolean {
		sampleHudValues = !sampleHudValues
		return sampleHudValues
	}

	/**
	 * Turns "E", "F7" or "M3" into a floor override, or clears it when it is already set. Returns
	 * the floor in force, or null once off.
	 */
	fun toggleDungeonFloor(name: String): Pair<Int, Boolean>? {
		val text = name.trim().uppercase(Locale.ROOT)
		val floor: Int
		val master: Boolean

		when {
			text == "E" -> { floor = 0; master = false }
			text.length == 2 && text[0] == 'F' && text[1].isDigit() -> { floor = text[1].digitToInt(); master = false }
			text.length == 2 && text[0] == 'M' && text[1].isDigit() -> { floor = text[1].digitToInt(); master = true }
			else -> return null
		}

		if (dungeonFloor == floor && dungeonMasterMode == master) {
			dungeonFloor = null
			dungeonMasterMode = false
			return null
		}

		dungeonFloor = floor
		dungeonMasterMode = master
		return floor to master
	}

	/**
	 * Overrides the Crystal Hollows zone the sidebar names (and counts you as in the Hollows). Null
	 * reads the real one.
	 */
	var hollowsZone: String? = null

	val hollowsZones: List<String> = listOf("Crystal Nucleus", "Mines of Divan", "Goblin Queen's Den", "Jungle Temple", "Khazad-dûm", "Lost Precursor City")

	/** Sets [hollowsZone], or clears it when it is already that zone. */
	fun toggleHollowsZone(zone: String): String? {
		hollowsZone = if (hollowsZone.equals(zone, ignoreCase = true)) null else zone
		return hollowsZone
	}

	val floorNames: List<String> = listOf("E") + (1..7).map { "F$it" } + (1..7).map { "M$it" }

	fun parseClass(name: String): DungeonClass? =
		DungeonClass.entries.firstOrNull { it != DungeonClass.UNKNOWN && it.name.equals(name, ignoreCase = true) }

	/** Sets the class override, or clears it when it is already [dungeonClass]. */
	fun toggleDungeonClass(dungeonClass: DungeonClass): DungeonClass? {
		this.dungeonClass = if (dungeonClass == this.dungeonClass) null else dungeonClass
		return this.dungeonClass
	}
}
