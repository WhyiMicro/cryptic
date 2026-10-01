package imicro.cryptic.debug

import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import java.util.Locale

/**
 * Switches that make dungeon-only features testable outside a dungeon.
 *
 * Several modules only do anything on a specific floor or with a party in the
 * tab list, which otherwise means a full run to see one change. These are set
 * from `/cryptic debug` and deliberately live in memory only, so a forgotten
 * switch cannot follow the player into their next session.
 */
object DebugOverrides {
	/** Outlines every wither anywhere, instead of only the Floor 7 bosses. */
	var outlineEveryWither = false
		private set

	/** Makes Cryptic read the player as being in the Catacombs on this class. */
	var dungeonClass: DungeonClass? = null
		private set

	/**
	 * Stands in for the floor the scoreboard would name, or null to read it.
	 *
	 * Everything score-shaped is gated on being in a dungeon, so without this
	 * none of it can be looked at outside a real run.
	 */
	var dungeonFloor: Int? = null
		private set

	var dungeonMasterMode = false
		private set

	/** Holds the Creeper Veil effect on without Hypixel to switch it. */
	var forceWitherCloak = false
		private set

	/** Prints leap announcements instead of sending them to a party. */
	var previewLeapMessage = false
		private set

	/** Prints score announcements instead of sending them to a party. */
	var previewScoreMessage = false
		private set

	/**
	 * Treats every armour stand as a door key, anywhere.
	 *
	 * The real thing only exists behind a wither door, so without this the
	 * highlight cannot be looked at without a run — `/summon armor_stand` can
	 * stand in for one.
	 */
	var highlightEveryArmorStand = false
		private set

	/**
	 * Lets the Terracotta Timer run anywhere, off any flower pot.
	 *
	 * The real thing needs Sadan's room and a dead terracotta, so placing a pot
	 * in the dev world stands in for one.
	 */
	var terracottaAnywhere = false
		private set

	/** Prints party commands and their replies instead of sending them. */
	var previewPartyCommands = false
		private set

	/** Makes the Spring Boots Helper and Blessing Display show sample numbers. */
	var sampleHudValues = false
		private set

	/** The class names `/cryptic debug setclass` accepts, lowercased for typing. */
	val classNames: List<String> = DungeonClass.entries
		.filter { it != DungeonClass.UNKNOWN }
		.map { it.name.lowercase(Locale.ROOT) }

	/** Flips the wither switch and reports the state it landed in. */
	fun toggleOutlineEveryWither(): Boolean {
		outlineEveryWither = !outlineEveryWither
		return outlineEveryWither
	}

	/** Flips the Creeper Veil switch and reports the state it landed in. */
	fun toggleWitherCloak(): Boolean {
		forceWitherCloak = !forceWitherCloak
		return forceWitherCloak
	}

	/** Flips leap announcements between being sent and being shown to you. */
	fun toggleLeapPreview(): Boolean {
		previewLeapMessage = !previewLeapMessage
		return previewLeapMessage
	}

	/** Flips score announcements between being sent and being shown to you. */
	fun toggleScorePreview(): Boolean {
		previewScoreMessage = !previewScoreMessage
		return previewScoreMessage
	}

	/** Flips whether any armour stand counts as a door key. */
	fun toggleArmorStandKeys(): Boolean {
		highlightEveryArmorStand = !highlightEveryArmorStand
		return highlightEveryArmorStand
	}

	fun toggleTerracottaAnywhere(): Boolean {
		terracottaAnywhere = !terracottaAnywhere
		return terracottaAnywhere
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
	 * Turns a floor written the way Hypixel writes it — "E", "F7", "M3" — into
	 * an override, or off again when it is the floor already set.
	 *
	 * Returns the floor now in force, or null once the override is off.
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

	/** The floors `/cryptic debug floor` accepts, for its suggestions. */
	val floorNames: List<String> = listOf("E") + (1..7).map { "F$it" } + (1..7).map { "M$it" }

	/** The class [name] stands for, or null when it is not one Cryptic knows. */
	fun parseClass(name: String): DungeonClass? =
		DungeonClass.entries.firstOrNull { it != DungeonClass.UNKNOWN && it.name.equals(name, ignoreCase = true) }

	/**
	 * Turns the class override on, or off again when [dungeonClass] is the one
	 * already set, so one command does both.
	 */
	fun toggleDungeonClass(dungeonClass: DungeonClass): DungeonClass? {
		this.dungeonClass = if (dungeonClass == this.dungeonClass) null else dungeonClass
		return this.dungeonClass
	}
}
