package imicro.cryptic.experiment

import imicro.cryptic.feature.ExperimentSolver
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.item.ItemStack

/**
 * Which experiment chest is open, read into [ExperimentCell]s. The only place that knows about
 * `ItemStack`s; [ExperimentRules] works on cells alone.
 */
object ExperimentTracker {
	var current: ExperimentHandler? = null
		private set

	private var currentGame: ExperimentRules.Game? = null

	/**
	 * The open chest's title. Every menu around the table matters to [ExperimentRunner], not just
	 * the games.
	 */
	var menuTitle: String = ""
		private set

	var menuItems: List<ItemStack> = emptyList()
		private set

	/**
	 * Bumped whenever the menu changes, by a new window or new contents. A click that didn't move
	 * it was ignored.
	 */
	var revision: Int = 0
		private set

	var cells: List<ExperimentCell> = emptyList()
		private set

	var status: String = "no experiment seen yet"
		private set

	/**
	 * Whether the table could be here (it only exists on a private island). Checked once per menu,
	 * and fails open: a sidebar without a location line allows the work.
	 */
	private fun couldBeHere(client: Minecraft): Boolean {
		val level = client.level ?: return true
		val scoreboard = level.scoreboard
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return true

		var sawLocation = false
		for (entry in scoreboard.listPlayerScores(objective)) {
			val team = scoreboard.getPlayersTeam(entry.owner())
			val line = ExperimentRules.stripFormatting(
				PlayerTeam.formatNameForTeam(team, entry.ownerName()).string,
			)
			if (ISLAND_LINE.containsMatchIn(line)) return true
			if (LOCATION_LINE.containsMatchIn(line)) sawLocation = true
		}

		return !sawLocation
	}

	private val ISLAND_LINE = Regex("""Your Island|Private Island""", RegexOption.IGNORE_CASE)

	private val LOCATION_LINE = Regex("""⏣|Ⓑ""")

	fun windowOpened(rawTitle: String) {
		val title = ExperimentRules.stripFormatting(rawTitle)
		revision++
		menuTitle = title
		menuItems = emptyList()
		cells = emptyList()

		if (!couldBeHere(Minecraft.getInstance())) {
			current = null
			currentGame = null
			status = "not on the island"
			return
		}

		val game = ExperimentRules.gameOf(title)
		if (game == null) {
			current = null
			currentGame = null
			status = "menu: $title"
			ExperimentDebug.note("menu: $title")
			ExperimentRunner.onMenuOpened(title)
			return
		}

		// The same game reopening between rounds keeps what it learned; for Superpairs that memory
		// is the solve.
		if (game != currentGame) {
			currentGame = game
			current = when (game) {
				ExperimentRules.Game.CHRONOMATRON -> ChronomatronHandler()
				ExperimentRules.Game.ULTRASEQUENCER -> UltrasequencerHandler()
				ExperimentRules.Game.SUPERPAIRS -> SuperpairsHandler()
			}
		}
		ExperimentRunner.onMenuOpened(title)
		status = "${current?.name} open"
		ExperimentDebug.note("opened: $title")
	}

	private var dirty = false

	/**
	 * Runs for every slot packet in every menu, so it only marks the menu dirty; reading it here
	 * made every GUI hitch.
	 */
	fun slotUpdated(items: List<ItemStack>) {
		revision++
		menuItems = items
		if (!ExperimentSolver.module.enabled) return
		dirty = true
	}

	/** Reads the menu at most once a tick, and only while a game is open. */
	private fun refresh() {
		if (!dirty) return
		dirty = false

		ExperimentDebug.dumpMenu(menuTitle)

		val handler = current ?: return
		cells = menuItems.mapIndexed(::cellOf)
		handler.update(cells, PlayOptions(rareItemsOnly = ExperimentSolver.huntingItems))
		status = "${handler.name}: round ${handler.roundsDone()}, " +
			"${handler.clickOrder().size} to click, control ${ExperimentRules.controlOf(cells) ?: "?"}"
		ExperimentDebug.note(status)
	}

	fun closed() {
		current = null
		currentGame = null
		menuTitle = ""
		menuItems = emptyList()
		cells = emptyList()
		status = "no experiment open"
	}

	/** Notices the player leaving without a close packet. */
	fun tick(client: Minecraft) {
		if (menuTitle.isNotEmpty() && client.gui.screen() !is AbstractContainerScreen<*>) {
			// Asks the runner whether it closed the menu before the state is cleared.
			ExperimentRunner.onMenuClosed()
			closed()
		}
		refresh()
	}

	private fun cellOf(slot: Int, stack: ItemStack): ExperimentCell {
		if (stack.isEmpty) return ExperimentCell(slot, "", 0, false, "", true)
		return ExperimentCell(
			slot = slot,
			itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString(),
			count = stack.count,
			foil = stack.hasFoil(),
			name = ExperimentRules.stripFormatting(stack.hoverName.string),
			empty = false,
		)
	}

}
