package imicro.cryptic.feature

import imicro.cryptic.experiment.ChronomatronHandler
import imicro.cryptic.experiment.ExperimentCell
import imicro.cryptic.experiment.ExperimentDebug
import imicro.cryptic.experiment.ExperimentHandler
import imicro.cryptic.experiment.ExperimentRules
import imicro.cryptic.experiment.ExperimentRunner
import imicro.cryptic.experiment.ExperimentTracker
import imicro.cryptic.experiment.SuperpairsHandler
import imicro.cryptic.experiment.UltrasequencerHandler
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.ItemPrices
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * The Experimentation Table, in two modes. **Legit** helps you play: the next note in Chronomatron
 * and Ultrasequencer, wrong clicks stopped, and an alert at the chain that pays the most Superpairs
 * clicks; in Superpairs, pairs highlighted by where they stand, each card's experience and worth,
 * cards kept in sight after they turn back over, what the unseen ones must be once that's certain, a
 * panel of it all, and an Ultra-Rare alert. **Auto** plays the games and can work the table between
 * them (semi-legit), keeping every display. Rules are in [ExperimentRules], the table loop in [ExperimentRunner]. SkyHanni's ideas,
 * Cryptic's own code.
 */
object ExperimentSolver {
	private const val MODE_LEGIT = 0
	private const val MODE_AUTO = 1

	private const val FOCUS_XP = 0
	private const val FOCUS_ITEMS = 1

	/** How long a Superpairs click may go unanswered before it's sent again; the board refreshes about once a second. */
	private const val CLICK_TIMEOUT_MILLIS = 3_000L

	/** How long Superpairs may have nothing to click, with clicks left, before the board is read afresh. */
	private const val STALL_MILLIS = 4_000L

	/** A click of your own in Superpairs holds the auto clicker off this long. */
	private const val HAND_PAUSE_MILLIS = 2_500L

	private const val SLOT_SIZE = 16

	private const val TAKEN_COLOR = 0x5055FF55
	private const val PAIR_COLOR = 0x70FFAA00
	private const val WAITING_COLOR = 0x40FFFF55
	private const val POWERUP_COLOR = 0x50AA00AA
	private const val KNOWN_COLOR = 0x4055FFFF
	private const val REMEMBERED_COLOR = 0xE0101418.toInt()
	private const val NEXT_COLOR = 0x8055FF55.toInt()
	private const val SECOND_COLOR = 0x60FFFF55

	private const val PANEL_PADDING = 6

	private const val PAUSE_TOAST = "experiment_pause"
	private const val PAUSE_TOAST_SECONDS = 60.0

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Legit", "Auto"),
		defaultIndex = MODE_LEGIT,
		description = "Legit helps you play each game. Auto plays them for you and can work the table.",
		optionRestrictions = mapOf("Auto" to Restriction.SEMI_LEGIT),
	)

	@JvmField
	val focus = DropdownModuleSetting(
		id = "focus",
		label = "Focus",
		options = listOf("Focus XP", "Focus valuables"),
		description = "Focus XP is for levelling: the biggest experience pairs, with only a card worth 10M or more taken first. Focus valuables turns the whole board, takes each pair worth the Rare from price as soon as both are known, and gives experience only the clicks left over.",
		renamed = mapOf("Most XP" to "Focus XP", "Rare items" to "Focus valuables"),
	)

	@JvmField
	val rareFrom = SliderModuleSetting(
		id = "rare_from",
		label = "Rare from",
		unit = "M",
		defaultValue = 1.0,
		min = 0.0,
		max = 20.0,
		step = 0.25,
		description = "A book or item, experience bottles included, worth this many million coins or more is gone for. One with no known price always is.",
		visibleIf = { focus.selectedIndex == FOCUS_ITEMS },
	)

	// ---- Superpairs ----------------------------------------------------------------------------

	private val superpairsSection = SectionModuleSetting("superpairs_section", "Superpairs")

	@JvmField
	val highlightPairs = ToggleModuleSetting(
		id = "highlight_pairs",
		label = "Highlight pairs",
		defaultValue = true,
		description = "Gold for a pair in view to take, yellow for a card waiting for its twin, green for pairs taken, purple for powerups. The panel and tooltips name cards worth going for in pink.",
	)

	@JvmField
	val rememberCards = ToggleModuleSetting(
		id = "remember_cards",
		label = "Keep cards visible",
		defaultValue = true,
		description = "Keeps showing a card after it turns back over. Once the unseen cards can only be twins of known ones, tints them and says in their tooltip what they can be.",
	)

	@JvmField
	val xpOverlay = ToggleModuleSetting(
		id = "xp_overlay",
		label = "Experience on cards",
		defaultValue = true,
		description = "Writes how much Enchanting experience each card pays over it.",
	)

	@JvmField
	val cardInfo = ToggleModuleSetting(
		id = "card_info",
		label = "Card worth in tooltips",
		defaultValue = true,
		description = "A card's tooltip says what it is (a book's enchantment) and what it sells for on the Bazaar.",
	)

	@JvmField
	val helperPanel = ToggleModuleSetting(
		id = "helper_panel",
		label = "Superpairs helper",
		defaultValue = true,
		description = "A panel beside the board: clicks left, the pairs in view best first, the cards waiting for a twin, what's been taken and what's face down.",
	)

	@JvmField
	val ultraRareAlert = ToggleModuleSetting(
		id = "ultra_rare_alert",
		label = "Ultra-Rare alert",
		defaultValue = true,
		description = "A title, a sound and a chat line when an Ultra-Rare book is turned over.",
	)

	// ---- Chronomatron and Ultrasequencer ---------------------------------------------------

	private val addonsSection = SectionModuleSetting("addons_section", "Chronomatron and Ultrasequencer")

	private val legit: () -> Boolean = { mode.selectedIndex == MODE_LEGIT }

	@JvmField
	val nextClick = ToggleModuleSetting(
		id = "next_click",
		label = "Next click helper",
		defaultValue = true,
		description = "Highlights the next note in green and the one after in yellow; in Ultrasequencer, keeps every note's number shown after it's hidden.",
	)

	@JvmField
	val preventMisclicks = ToggleModuleSetting(
		id = "prevent_misclicks",
		label = "Prevent misclicks",
		defaultValue = true,
		description = "Stops a click on the wrong colour in Chronomatron, or the wrong note in Ultrasequencer.",
		visibleIf = legit,
	)

	@JvmField
	val maxClicksAlert = ToggleModuleSetting(
		id = "max_clicks_alert",
		label = "Max clicks alert",
		defaultValue = true,
		description = "A toast and a sound once you've played the chain that pays the most Superpairs clicks, as the stake's own lore lists them. A chat line with notifications off.",
	)

	// ---- Auto ----------------------------------------------------------------------------------

	private val auto: () -> Boolean = { mode.selectedIndex == MODE_AUTO }

	private val autoSection = SectionModuleSetting("auto_section", "Auto click", visibleIf = auto)

	@JvmField
	val autoClick = ToggleModuleSetting(
		id = "auto_click",
		label = "Play automatically",
		description = "Clicks each game for you. In Superpairs a click of your own still goes through, and it carries on from there.",
		visibleIf = auto,
	)

	@JvmField
	val clickDelay = RangeModuleSetting(
		id = "click_delay",
		label = "Click delay",
		defaultLower = 180.0,
		defaultUpper = 320.0,
		min = 60.0,
		max = 800.0,
		step = 10.0,
		description = "Milliseconds between automatic clicks, drawn fresh each time.",
		visibleIf = { auto() && autoClick.value },
	)

	private val runSection = SectionModuleSetting("run_section", "Run the table", visibleIf = auto)

	@JvmField
	val runTable = ToggleModuleSetting(
		id = "run_table",
		label = "Work the table",
		description = "Plays the table itself, start to finish, and leaves each game once it can't earn more.",
		visibleIf = auto,
	)

	@JvmField
	val renews = SliderModuleSetting(
		id = "renews",
		label = "Renews",
		defaultValue = 0.0,
		min = 0.0,
		max = 3.0,
		step = 1.0,
		description = "Renews to pay for. Three is the daily limit.",
	)

	@JvmField
	val superpairsByHand = ToggleModuleSetting(
		id = "superpairs_by_hand",
		label = "Play Superpairs by hand",
		description = "Leaves Superpairs to you; the displays above still help.",
		visibleIf = { auto() && (runTable.value || autoClick.value) },
	)

	@JvmField
	val buyExperience = ToggleModuleSetting(
		id = "buy_experience",
		label = "Buy experience",
		description = "Buys the experience it needs from the bazaar.",
	)

	@JvmField
	val serums = SliderModuleSetting(
		id = "serums",
		label = "Serums drunk",
		defaultValue = 0.0,
		min = 0.0,
		max = 3.0,
		step = 1.0,
		description = "Metaphysical Serums you have consumed.",
	)

	@JvmField
	val guardianReminder = ToggleModuleSetting(
		id = "guardian_reminder",
		label = "Guardian reminder",
		defaultValue = false,
		description = "Closes the table and says so if the Guardian pet is not out.",
	)

	@JvmField
	val module = Module(
		id = "experiment_solver",
		name = "Experiment Solver",
		description = "Helps or plays experiments",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(mode, focus, rareFrom) +
			listOf(superpairsSection, highlightPairs, rememberCards, xpOverlay, cardInfo, helperPanel, ultraRareAlert) +
			listOf(addonsSection, nextClick, preventMisclicks, maxClicksAlert) +
			listOf(autoSection, autoClick, clickDelay) +
			listOf(runSection, runTable.withMore(renews, serums, buyExperience, guardianReminder), superpairsByHand),
	)

	/** True while playing for books rather than levels. */
	val huntingItems: Boolean get() = focus.selectedIndex == FOCUS_ITEMS

	private val isAuto: Boolean get() = mode.selectedIndex == MODE_AUTO

	/** Auto mode's two halves, which Client Restrictions can hold back with the mode. */
	val autoClicks: Boolean get() = module.enabled && isAuto && autoClick.value

	val runsTable: Boolean get() = module.enabled && isAuto && runTable.value

	private var nextClickAt = 0L
	private var pendingSince = 0L
	private var stalledSince = 0L
	private var handPauseUntil = 0L

	/** Auto held by Escape, so you can take over; the run itself isn't stopped. Cleared when the menu closes. */
	@JvmStatic
	var paused = false
		private set

	/** Whether Auto is doing something in the open menu right now, which Escape would pause. */
	private val autoBusy: Boolean
		get() {
			if (!module.enabled || paused || !inTableMenu()) return false
			if (runsTable && ExperimentRunner.keepsGameRunning()) return true
			val handler = ExperimentTracker.current ?: return false
			return autoClicks && !(handler is SuperpairsHandler && superpairsByHand.value)
		}

	/** The table's own menus: a game, its stakes, the table, the bottle shop or a game's end and rewards. */
	private val TABLE_MENUS = setOf("Experimentation Table", "Bottles of Enchanting", "Experiment Over", "Superpairs Rewards")

	/** Whether the open screen is one of the table's chests; your inventory is a container screen too. */
	private fun inTableMenu(): Boolean {
		val screen = Minecraft.getInstance().gui.screen() as? ContainerScreen ?: return false
		val title = ExperimentRules.stripFormatting(screen.title.string).trim()
		return title in TABLE_MENUS || ExperimentRules.gameOf(title) != null ||
			(title.endsWith("Stakes") && ExperimentRules.Game.entries.any { title.startsWith(it.name, ignoreCase = true) })
	}

	/**
	 * Escape while Auto plays stops it (clicking and working the table) instead of closing the menu,
	 * and offers Y to carry on; a second Escape closes the menu as usual and the offer goes with it.
	 * True keeps the key from the screen.
	 */
	@JvmStatic
	fun handleEscape(): Boolean {
		if (!autoBusy) return false
		paused = true
		Toasts.ask(
			module.name, PAUSE_TOAST, life = PAUSE_TOAST_SECONDS,
			keys = mapOf(GLFW.GLFW_KEY_Y to { resume() }),
			color = Toasts.Colors.GOOD, footer = "§a[Y] §fContinue",
		) { "Auto stopped: it's yours to play. §8(Escape again closes the menu)" }
		return true
	}

	private fun resume() {
		if (!paused) return
		paused = false
		// Clicks made by hand meanwhile have been counted; the next automatic one waits a moment.
		nextClickAt = System.currentTimeMillis() + clickDelay.random().toLong()
		pendingSince = 0L
	}

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ItemTooltipCallback.EVENT.register { stack, _, _, lines -> addTooltip(stack, lines) }
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		ExperimentRunner.onChat(ExperimentRules.stripFormatting(message.string).trim())
	}

	private val superpairs: SuperpairsHandler? get() = ExperimentTracker.current as? SuperpairsHandler

	// ---- Drawing -------------------------------------------------------------------------------

	/** Everything over and beside the chest, at the tail of the screen's pass. */
	@JvmStatic
	fun render(screen: Screen, context: GuiGraphicsExtractor) {
		if (!module.enabled) return
		if (screen !is AbstractContainerScreen<*>) return
		val handler = ExperimentTracker.current ?: return
		val accessor = screen as? ContainerScreenAccessor ?: return
		val left = accessor.`cryptic$leftPos`()
		val top = accessor.`cryptic$topPos`()
		val slots = screen.menu.slots

		context.nextStratum()
		fun fill(slotIndex: Int, color: Int) {
			val slot = slots.getOrNull(slotIndex) ?: return
			context.fill(left + slot.x, top + slot.y, left + slot.x + SLOT_SIZE, top + slot.y + SLOT_SIZE, color)
		}

		if (handler is SuperpairsHandler) {
			drawSuperpairs(handler, context, left, top, slots, ::fill)
			if (helperPanel.value) drawPanel(handler, context, left + accessor.`cryptic$imageWidth`() + 6, top)
			return
		}

		// The next note, whether you play it or Auto does.
		if (!nextClick.value) return
		val order = handler.clickOrder()
		if (handler is UltrasequencerHandler) {
			// Each note keeps the number it showed, after the board hides it.
			val font = Minecraft.getInstance().font
			val played = handler.roundsDone() - order.size
			order.forEachIndexed { position, slotIndex ->
				if (position == 0) fill(slotIndex, NEXT_COLOR)
				val slot = slots.getOrNull(slotIndex) ?: return@forEachIndexed
				context.centeredText(font, (played + position + 1).toString(), left + slot.x + SLOT_SIZE / 2, top + slot.y + 4, -1)
			}
		} else {
			order.getOrNull(1)?.let { fill(it, SECOND_COLOR) }
			order.firstOrNull()?.let { fill(it, NEXT_COLOR) }
		}
	}

	private fun drawSuperpairs(
		handler: SuperpairsHandler,
		context: GuiGraphicsExtractor,
		left: Int,
		top: Int,
		slots: List<net.minecraft.world.inventory.Slot>,
		fill: (Int, Int) -> Unit,
	) {
		val view = handler.view
		val font = Minecraft.getInstance().font
		for (slotIndex in handler.board) {
			if (slotIndex in view.faceDown) {
				if (!rememberCards.value) continue
				val stack = ExperimentTracker.cardStacks[slotIndex]
				if (stack == null) {
					if (view.boardKnown || view.lastTwo) fill(slotIndex, KNOWN_COLOR)
					continue
				}
				// Turned back over: drawn as it showed, over a dark square so the glass doesn't show through.
				val slot = slots.getOrNull(slotIndex) ?: continue
				fill(slotIndex, REMEMBERED_COLOR)
				context.fakeItem(stack, left + slot.x, top + slot.y)
			}
			if (highlightPairs.value) {
				when (handler.stateOf(slotIndex)) {
					SuperpairsHandler.CardState.TAKEN -> fill(slotIndex, TAKEN_COLOR)
					SuperpairsHandler.CardState.PAIR -> fill(slotIndex, PAIR_COLOR)
					SuperpairsHandler.CardState.WAITING -> fill(slotIndex, WAITING_COLOR)
					SuperpairsHandler.CardState.POWERUP -> fill(slotIndex, POWERUP_COLOR)
					null -> Unit
				}
			}
			if (xpOverlay.value) {
				val experience = handler.card(slotIndex)?.let(ExperimentRules::experienceOf) ?: 0.0
				if (experience <= 0.0) continue
				val slot = slots.getOrNull(slotIndex) ?: continue
				val text = shortExperience(experience)
				val pose = context.pose()
				pose.pushMatrix()
				pose.translate((left + slot.x + SLOT_SIZE).toFloat(), (top + slot.y + SLOT_SIZE - 5).toFloat())
				pose.scale(0.6f, 0.6f)
				context.text(font, text, -font.width(text), 0, -1)
				pose.popMatrix()
			}
		}
	}

	private fun shortExperience(value: Double): String = when {
		value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000)
		value >= 1_000 -> "${(value / 1_000).toInt()}k"
		else -> value.toInt().toString()
	}

	private fun rewardOf(handler: SuperpairsHandler, slot: Int): String {
		val cell = handler.card(slot) ?: return "?"
		val reward = ExperimentRules.cardReward(cell)
		return when {
			handler.isRare(slot) -> "§d$reward"
			ExperimentRules.isBook(cell) -> "§9$reward"
			ExperimentRules.experienceOf(cell) > 0 -> "§b$reward"
			else -> "§f$reward"
		}
	}

	private fun drawPanel(handler: SuperpairsHandler, context: GuiGraphicsExtractor, x: Int, y: Int) {
		val view = handler.view
		val lines = ArrayList<String>()
		lines += "§6Superpairs"
		if (handler.clicksLeft != Int.MAX_VALUE) lines += "§7Clicks left: §f${handler.clicksLeft}"
		if (view.pairs.isNotEmpty()) {
			lines += "§6To take:"
			view.pairs.take(6).forEach { lines += " ${rewardOf(handler, it.first())}" }
		}
		if (view.singles.isNotEmpty()) {
			lines += "§eWaiting for a twin:"
			view.singles.take(6).forEach { lines += " ${rewardOf(handler, it)}" }
		}
		if (view.taken.isNotEmpty()) lines += "§aTaken: §f${view.taken.size / 2} pairs"
		if (view.unknown.isNotEmpty()) {
			lines += "§7Not seen yet: §f${view.unknown.size}"
			when {
				view.lastTwo -> lines += "§3 The last two are a pair"
				view.boardKnown -> lines += "§3 All twins of the cards waiting"
			}
		}
		val font = Minecraft.getInstance().font
		val width = lines.maxOf { font.width(it) } + PANEL_PADDING * 2
		val height = lines.size * (font.lineHeight + 1) + PANEL_PADDING * 2 - 1
		BazaarHistory.panelBox(context, x, y, width, height)
		lines.forEachIndexed { index, line -> context.text(font, line, x + PANEL_PADDING, y + PANEL_PADDING + index * (font.lineHeight + 1), -1) }
	}

	// ---- Clicks --------------------------------------------------------------------------------

	/**
	 * A click of your own. Superpairs is read off the board, so yours go through and the auto
	 * clicker waits a moment. In Chronomatron and Ultrasequencer, Auto counts the sequence from its
	 * own clicks, so yours are dropped (OdinClient's rule); Legit counts yours and can stop a wrong one.
	 * True drops the click.
	 */
	@JvmStatic
	fun onManualClick(slot: Int): Boolean {
		if (!module.enabled) return false
		val handler = ExperimentTracker.current ?: return false
		if (handler is SuperpairsHandler) {
			handler.clickedByHand(slot)
			handPauseUntil = System.currentTimeMillis() + HAND_PAUSE_MILLIS
			return false
		}
		// Paused, you play: your clicks count as in Legit, so Auto can carry on from them.
		if (isAuto && !paused) return autoClick.value
		if (slot !in ExperimentRules.GRID_SLOTS) return false
		val next = handler.clickOrder().firstOrNull() ?: return false
		val right = slot == next || (handler is ChronomatronHandler && sameColour(slot, next))
		if (right) {
			handler.advance()
			return false
		}
		return preventMisclicks.value
	}

	/** Chronomatron takes any slot of the note's colour. */
	private fun sameColour(a: Int, b: Int): Boolean {
		val cells = ExperimentTracker.cells
		val first = cells.getOrNull(a) ?: return false
		val second = cells.getOrNull(b) ?: return false
		return !first.empty && first.itemId == second.itemId
	}

	// ---- Tick ----------------------------------------------------------------------------------

	fun tick(client: Minecraft) {
		// With the menu gone there's nothing to continue.
		if (paused && !inTableMenu()) {
			paused = false
			Toasts.dismiss(PAUSE_TOAST, slideAway = true)
		}
		ExperimentTracker.tick(client)
		ExperimentDebug.flush()
		ExperimentRunner.tick(client)
		if (!module.enabled) return

		readStakes()
		val handler = ExperimentTracker.current ?: return
		alertMaxClicks(client, handler)
		val pairs = handler as? SuperpairsHandler
		if (pairs != null) {
			alertUltraRare(client, pairs)
			if (cardInfo.value || helperPanel.value || huntingItems) {
				BazaarMarket.refresh(120_000L)
				ItemPrices.ensureFetched()
			}
		}
		if (!autoClicks || paused) return

		val now = System.currentTimeMillis()
		if (pairs != null) {
			// Superpairs can be left to you, and a click of your own pauses it.
			if (superpairsByHand.value || now < handPauseUntil) return
			// A click the board never showed is sent again, rather than waited on for good.
			if (pairs.waitingOnClick) {
				if (pendingSince == 0L) pendingSince = now
				if (now - pendingSince < CLICK_TIMEOUT_MILLIS) return
				ExperimentDebug.note("superpairs: click went unanswered, trying again")
				pairs.forgetPending()
			}
			pendingSince = 0L
			// Nothing to click with clicks left: the board's "?" never moved on. Played on regardless.
			if (pairs.stalled) {
				if (stalledSince == 0L) stalledSince = now
				if (now - stalledSince >= STALL_MILLIS) {
					ExperimentDebug.note("superpairs: nothing to click for ${STALL_MILLIS / 1000}s with ${pairs.clicksLeft} clicks left, reading the board afresh")
					pairs.unstick()
					stalledSince = 0L
				}
			} else {
				stalledSince = 0L
			}
		}
		if (handler.waiting) return
		if (now < nextClickAt) return

		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
		val player = client.player ?: return
		val slot = handler.clickOrder().firstOrNull() ?: return
		if (slot !in screen.menu.slots.indices) return

		// Button zero with CLONE, as Astrail sends.
		client.gameMode?.handleContainerInput(screen.menu.containerId, slot, 0, ContainerInput.CLONE, player)

		// Told, since nothing on the board says how far into the sequence you are.
		handler.advance()
		nextClickAt = now + clickDelay.random().toLong()
	}

	// ---- Alerts --------------------------------------------------------------------------------

	/** The longest chain each stake pays clicks for, read off the stakes menu: "Chronomatron|Metaphysical". */
	private val maxChains = HashMap<String, Int>()
	private var stakesRevision = -1

	private fun readStakes() {
		val title = ExperimentTracker.menuTitle
		if (!title.endsWith("Stakes") || ExperimentTracker.revision == stakesRevision) return
		stakesRevision = ExperimentTracker.revision
		val game = title.substringBefore(" ").trim()
		for (stack in ExperimentTracker.menuItems) {
			val name = ExperimentRules.stripFormatting(stack.hoverName.string)
			if (!name.endsWith(" Experiment")) continue
			val lore = stack.get(DataComponents.LORE)?.lines()?.map { ExperimentRules.stripFormatting(it.string).trim() } ?: continue
			ExperimentRules.maxClicksChain(lore)?.let { maxChains["$game|${name.removeSuffix(" Experiment")}"] = it }
		}
	}

	private var maxAlerted: ExperimentHandler? = null

	private fun alertMaxClicks(client: Minecraft, handler: ExperimentHandler) {
		if (!maxClicksAlert.value || handler is SuperpairsHandler || maxAlerted === handler) return
		val title = ExperimentTracker.menuTitle
		val stake = title.substringAfter("(", "").substringBefore(")")
		val max = maxChains["${title.substringBefore(" ").trim()}|$stake"] ?: return
		if (handler.roundsDone() < max || !handler.roundComplete) return
		maxAlerted = handler
		// A toast rather than a title, which the open menu would cover; with toasts off it's a chat line.
		Toasts.show(module.name, "§aMax clicks reached§7 (chain of $max). Leave now if you only play for Superpairs clicks.", color = Toasts.Colors.GOOD)
		client.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f)
	}

	private val ultraRareSeen = HashSet<Int>()
	private var ultraRareBoard: SuperpairsHandler? = null

	private fun alertUltraRare(client: Minecraft, handler: SuperpairsHandler) {
		if (ultraRareBoard !== handler) {
			ultraRareBoard = handler
			ultraRareSeen.clear()
		}
		if (!ultraRareAlert.value) return
		for ((slot, cell) in handler.cards) {
			if (!ExperimentRules.isUltraRare(cell) || !ultraRareSeen.add(slot)) continue
			val reward = ExperimentRules.cardReward(cell)
			title(client, "§d§lULTRA-RARE BOOK!", "§9$reward")
			client.player?.playSound(SoundEvents.ENDER_DRAGON_GROWL, 1f, 1f)
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §d§lULTRA-RARE! §7You turned over §9$reward§7."))
		}
	}

	private fun title(client: Minecraft, title: String, subtitle: String) {
		val hud = client.gui.hud
		hud.setTimes(5, 50, 10)
		hud.setTitle(Component.literal(title))
		hud.setSubtitle(Component.literal(subtitle))
	}

	// ---- What a card is worth -----------------------------------------------------------------

	private val ROMAN = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10)
	private val LEVELLED = Regex("""^(.+) ([IVX]+)$""")

	/**
	 * A card's Bazaar or auction id. A book trades on the Bazaar as its enchantment (ultimate ones
	 * under ULTIMATE_), and the ones that don't (Charm IV) at auction, which the price list keys
	 * ENCHANTED_BOOK-NAME-LEVEL.
	 */
	private fun cardId(cell: ExperimentCell): String? {
		val reward = ExperimentRules.cardReward(cell)
		BazaarMarket.idForName(reward)?.let { return it }
		if (!ExperimentRules.isBook(cell)) return null
		val match = LEVELLED.find(reward) ?: return null
		val level = ROMAN[match.groupValues[2]] ?: return null
		val name = match.groupValues[1].uppercase(Locale.ROOT).replace(' ', '_').replace("-", "_").replace("'", "")
		"ENCHANTMENT_ULTIMATE_${name}_$level".takeIf { BazaarMarket.product(it) != null }?.let { return it }
		return listOf("ENCHANTED_BOOK-$name-$level", "ENCHANTED_BOOK-ULTIMATE_$name-$level").firstOrNull { ItemPrices.price(it) != null }
	}

	/** What a card sells for: what buy orders pay on the Bazaar, else the week's average price; 0 when unknown. */
	@JvmStatic
	fun cardValue(cell: ExperimentCell): Double {
		// Experience bottles are items and sell; only the Enchanting Exp cards are worth no coins.
		if (ExperimentRules.experienceOf(cell) > 0 || ExperimentRules.isPowerup(cell)) return 0.0
		val id = cardId(cell) ?: return 0.0
		BazaarMarket.product(id)?.let { product ->
			val price = product.instantSell.takeIf { it > 0 } ?: product.instantBuy
			if (price > 0) return price
		}
		return ItemPrices.price(id) ?: 0.0
	}

	private fun addTooltip(stack: ItemStack, lines: MutableList<Component>) {
		if (!module.enabled) return
		val handler = superpairs ?: return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
		val slot = screen.menu.slots.firstOrNull { it.item === stack } ?: return
		if (slot.index !in handler.board) return
		val view = handler.view

		if (slot.index in view.faceDown) {
			if (!rememberCards.value) return
			if (handler.remembered(slot.index) != null) {
				lines += Component.empty()
				lines += Component.literal("§3Turned back over: ${rewardOf(handler, slot.index)}")
				if (cardInfo.value) addCardInfo(handler, slot.index, lines)
				return
			}
			when {
				view.lastTwo -> {
					lines += Component.empty()
					lines += Component.literal("§3This and the other face-down card are a pair.")
				}
				view.boardKnown -> {
					lines += Component.empty()
					lines += Component.literal("§3Must be the twin of one of:")
					view.singles.take(8).forEach { lines += Component.literal(" ${rewardOf(handler, it)}") }
					if (view.unknown.size > view.singles.size) lines += Component.literal(" §5or a powerup")
				}
			}
			return
		}

		if (!cardInfo.value || handler.card(slot.index) == null) return
		lines += Component.empty()
		addCardInfo(handler, slot.index, lines)
	}

	private fun addCardInfo(handler: SuperpairsHandler, slot: Int, lines: MutableList<Component>) {
		val card = handler.card(slot) ?: return
		if (ExperimentRules.isBook(card)) lines += Component.literal("§8Book: §9${ExperimentRules.cardReward(card)}")
		if (ExperimentRules.isUltraRare(card)) lines += Component.literal("§d§lULTRA-RARE")
		else if (handler.isRare(slot)) lines += Component.literal("§dWorth going for")
		val value = cardValue(card)
		when {
			ExperimentRules.experienceOf(card) > 0 -> lines += Component.literal("§7Experience")
			ExperimentRules.isPowerup(card) -> lines += Component.literal("§7Powerup")
			value > 0 -> lines += Component.literal("§7Worth: §6${Bazaar.short(value)} coins")
			else -> lines += Component.literal("§7Worth: §8unknown")
		}
	}
}
