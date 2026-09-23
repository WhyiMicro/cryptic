package imicro.cryptic.experiment

import imicro.cryptic.feature.ExperimentSolver
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.item.ItemStack

/**
 * Which experiment chest is open, and the cells the solver reads it as.
 *
 * The boundary between Minecraft and [ExperimentRules]: everything below turns
 * `ItemStack`s into [ExperimentCell]s and nothing above it knows what an
 * `ItemStack` is. Kept apart from the module that draws it, the same way
 * [imicro.cryptic.terminal.Terminals] is.
 */
object ExperimentTracker {
	var current: ExperimentHandler? = null
		private set

	private var currentGame: ExperimentRules.Game? = null

	/**
	 * The chest that is open, whatever it is.
	 *
	 * Every menu around the table is worth reading, not only the three games:
	 * [ExperimentRunner] gets from one game to the next through the table
	 * itself, the stake pickers and the bottle shop, and none of those is a game.
	 */
	var menuTitle: String = ""
		private set

	var menuItems: List<ItemStack> = emptyList()
		private set

	/**
	 * Bumped every time the menu becomes anything different.
	 *
	 * What [ExperimentRunner] needs is "has the menu answered me yet", and the
	 * answer can arrive either as a new window or as the same window's contents
	 * changing under it. One counter covers both, and a click that has not
	 * moved it is a click the menu ignored.
	 */
	var revision: Int = 0
		private set

	var cells: List<ExperimentCell> = emptyList()
		private set

	/** What the last update saw, for `/cryptic debug experiments`. */
	var status: String = "no experiment seen yet"
		private set

	/**
	 * Whether the table could be here at all, read once when a menu opens.
	 *
	 * The Experimentation Table is furniture on a private island, so anywhere
	 * else there is nothing to do. Checked here rather than every tick because a
	 * chest opening is the only moment the answer matters.
	 *
	 * Deliberately fails open. A sidebar with no location line on it — a lobby, a
	 * loading screen, a Hypixel wording nobody here has seen — reads as "not
	 * sure", and not being sure allows the work rather than blocking it. Getting
	 * this wrong should cost a little performance somewhere it was not needed,
	 * never a solver that quietly does nothing.
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

		// A location was named and it was not the island: nothing to do here.
		return !sawLocation
	}

	/** How Hypixel writes the player's own island on the sidebar. */
	private val ISLAND_LINE = Regex("""Your Island|Private Island""", RegexOption.IGNORE_CASE)

	/** The marker Hypixel puts in front of every location line. */
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

		// The same game re-opening between rounds must not throw away what it
		// has learned — for Superpairs that memory *is* the solve.
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

	/** Set by a slot update, cleared by the tick that acts on it. */
	private var dirty = false

	/**
	 * A slot changed somewhere. Deliberately does almost nothing.
	 *
	 * This runs for every container packet in every menu in the game, and
	 * Hypixel sends one per slot — so opening a 90-slot chest arrives here 90
	 * times, each with all 90 slots in hand. Reading them into [ExperimentCell]s
	 * here, as this first did, meant 8,100 registry lookups, hover-name builds
	 * and regex allocations to open one menu, whether or not it was an experiment
	 * and whether or not the module was even switched on. That is what made every
	 * GUI in the game hitch.
	 *
	 * So the packet only leaves a note. The reading happens once, on the next
	 * tick, and only if there is something to read it for.
	 */
	fun slotUpdated(items: List<ItemStack>) {
		revision++
		menuItems = items
		if (!ExperimentSolver.module.enabled) return
		dirty = true
	}

	/**
	 * Reads the menu, at most once a tick and only while a game is open.
	 *
	 * A tick is 50ms and the games are paced at hundreds of milliseconds per
	 * click, so nothing is lost by waiting for one — where re-reading the board
	 * ninety times as it arrives bought nothing at all.
	 */
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

	/** Notices the player leaving any way that does not send a close packet. */
	fun tick(client: Minecraft) {
		if (menuTitle.isNotEmpty() && client.gui.screen() !is AbstractContainerScreen<*>) {
			// The screen going away is the only place a player pressing escape
			// and the runner closing the menu itself look the same, so the runner
			// is asked which it was before the state is thrown away.
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
