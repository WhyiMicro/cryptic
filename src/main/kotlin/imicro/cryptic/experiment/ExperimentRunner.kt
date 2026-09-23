package imicro.cryptic.experiment

import imicro.cryptic.feature.ExperimentSolver
import imicro.cryptic.mixin.KeyMappingAccessor
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import org.lwjgl.glfw.GLFW

/**
 * Works the Experimentation Table between games: picks them, stakes them,
 * renews them and re-opens the table.
 *
 * The solving is [ExperimentTracker]'s job and the clicking inside a game is
 * [ExperimentSolver]'s; this is only the part that gets from one game to the
 * next. Followed from RBDT V5's `AutoExperiments` (`V5-Client/V5`, ChatTriggers
 * for 1.8.9 — read, not ported), which is where every slot number and every
 * line of lore below came from.
 *
 * Those numbers *were* guesses from a mod nine versions old. They are not any
 * more: a dump taken off a real table confirmed every one of them — the games at
 * 22, 29 and 33, the renew button at 31, the bottle shop at 50, and the stake
 * buttons in the order they are listed here. The lore strings match too.
 *
 * What is still worth being careful about is that this menu spends things.
 * Buying experience is off until asked for, and anything unrecognised stops the
 * run rather than being clicked past, because the cost of guessing wrong here is
 * coins rather than a wasted click.
 */
object ExperimentRunner {
	/** The table's own menu: which game each button opens. */
	private const val SLOT_SUPERPAIRS = 22
	private const val SLOT_CHRONOMATRON = 29
	private const val SLOT_RENEW = 31
	private const val SLOT_ULTRASEQUENCER = 33
	private const val SLOT_BOTTLE_MENU = 50

	/**
	 * Where the table reports each game as done. V5 reads completion from these
	 * and clicks the buttons above, which are different slots — kept as it has
	 * them, because a menu nobody here can open is not one to second-guess.
	 */
	private const val SLOT_CHRONOMATRON_STATUS = 21
	private const val SLOT_ULTRASEQUENCER_STATUS = 23

	/** The bottle shop, for buying the levels a stake needs. */
	private const val SLOT_GRAND_BOTTLE = 12
	private const val SLOT_TITANIC_BOTTLE = 14

	/** Where Superpairs offers its winnings. */
	private const val SLOT_REWARD = 13

	/** Stake buttons, richest first, so the best affordable one is taken. */
	private val CHRONOMATRON_STAKES = listOf(24, 23, 22, 21, 20)
	private val ULTRASEQUENCER_STAKES = listOf(23, 22, 21)
	private val SUPERPAIRS_STAKES = listOf(32, 31, 30, 23, 22, 21)

	/** The richest Chronomatron stake, which is the one that raises the target. */
	private const val MAX_ENCHANTING_STAKE = 24

	private const val LORE_LOCKED = "Enchanting level too low!"
	private const val LORE_COMPLETED = "Experiment completed"
	private const val LORE_ADDON_LOCKED = "Add-on locked!"
	private const val LORE_COOLDOWN = "Experiments on cooldown!"
	private const val LORE_PURCHASE = "click to purchase"
	private const val LORE_CANNOT_AFFORD = "cannot afford this!"
	private const val LORE_PLAY = "Click to play!"
	private const val LORE_NOT_ENOUGH_XP = "Not enough experience!"

	private val STAKE_COST = Regex("""Starting\s+cost:\s*(\d+)\s*XP\s*Levels?""", RegexOption.IGNORE_CASE)
	private val ANY_COST = Regex("""(\d+)\s*XP\s*Levels?""", RegexOption.IGNORE_CASE)
	private val RENEWS_TODAY = Regex("""renewed\s*(\d+)\s*/\s*(\d+)\s*charges""", RegexOption.IGNORE_CASE)
	private val OWN_LEVEL = Regex("""Your\s+Exp\s+Level:\s*(\d+)""", RegexOption.IGNORE_CASE)

	/** How many renews this run has spent, against the slider's allowance. */
	var renewsUsed = 0
		private set

	/** True once the richest Chronomatron stake was taken, which is worth more rounds. */
	private var maxEnchanting = false

	/** The level a stake wanted and could not have, or zero. */
	private var buyingLevel = 0
	private var boughtBottle = false

	private var nextActionAt = 0L

	/** Set when the table has nothing left to give, so nothing keeps clicking. */
	var finished = false
		private set

	private var reopenStage = ReopenStage.NONE

	private enum class ReopenStage { NONE, CLOSING, OPENING }

	/** What the runner last decided, said as it happens while watching. */
	var status: String = "idle"
		private set(value) {
			field = value
			ExperimentDebug.note("runner: $value")
		}

	fun reset() {
		renewsUsed = 0
		maxEnchanting = false
		buyingLevel = 0
		boughtBottle = false
		finished = false
		reopenStage = ReopenStage.NONE
		closingSelf = false
		forgetLastClick()
		status = "idle"
	}

	/**
	 * How many rounds the open game is worth playing.
	 *
	 * The two answers are the point of the **Play for** setting. Playing for
	 * experience means going as deep as the game goes, because the levels are in
	 * the later rounds. Playing for items means going no deeper than the chain
	 * the add-ons ask for and spending what is left on Superpairs, which is the
	 * only one of the three that pays in enchanted books — and every round past
	 * that chain is a chance to lose a run that has already earned its keep.
	 *
	 * Each serum drunk takes a round off the chain, which is what that slider is
	 * for, and is why it only matters in the items mode.
	 */
	fun targetRounds(handler: ExperimentHandler): Int {
		val serums = ExperimentSolver.serums.value.toInt()
		return when (handler.game) {
			ExperimentRules.Game.CHRONOMATRON ->
				if (ExperimentSolver.huntingItems) ((if (maxEnchanting) 12 else 9) - serums).coerceAtLeast(1) else 15
			ExperimentRules.Game.ULTRASEQUENCER ->
				if (ExperimentSolver.huntingItems) ((if (maxEnchanting) 9 else 7) - serums).coerceAtLeast(1) else 20
			// Superpairs is played out: every pair is another chance at a book.
			ExperimentRules.Game.SUPERPAIRS -> Int.MAX_VALUE
		}
	}

	private var wasRunning = false

	/**
	 * The settings a decision to stop could have depended on.
	 *
	 * Every reason this gives up on is a reason that one of these could make
	 * untrue — "Renews is set to 0" stops being a reason the moment it is set to
	 * one. Toggling the whole module off and on to be asked again is not an
	 * answer, so changing any of them is taken as being asked again.
	 */
	private fun settingsFingerprint(): String =
		"${ExperimentSolver.renews.value}|${ExperimentSolver.buyExperience.value}|" +
			"${ExperimentSolver.focus.selectedIndex}|${ExperimentSolver.serums.value}|" +
			"${ExperimentSolver.guardianReminder.value}"

	private var lastSettings = ""

	/**
	 * The last click sent, and the state of the menu when it went.
	 *
	 * Together these answer "has this click been tried against this exact menu
	 * already", which is the question that stops the runner clicking a menu that
	 * is not listening.
	 */
	private var lastClickSlot = -1
	private var lastClickRevision = -1
	private var lastClickAt = 0L

	/** How long a menu may ignore a click before the table is reopened. */
	private const val STUCK_MILLIS = 1_500L

	private fun forgetLastClick() {
		lastClickSlot = -1
		lastClickRevision = -1
		lastClickAt = 0L
	}

	/**
	 * True while the runner is the one closing the menu.
	 *
	 * Everything else that closes it is the player, and a player who shuts the
	 * table has stopped wanting it worked — so the two have to be told apart.
	 */
	private var closingSelf = false

	/** The pet whose bonus these games are worth running with. */
	private const val GUARDIAN = "Guardian"

	/** Vanilla title timing, so the reminder looks like any other. */
	private const val TITLE_FADE_TICKS = 5
	private const val TITLE_STAY_TICKS = 50

	/**
	 * A menu opening, from [ExperimentTracker].
	 *
	 * Two things hang off it: a new menu is a click that worked, so the stuck
	 * counter goes back to zero; and the table being opened by hand is the
	 * player asking for another go, which is worth honouring for the same reason
	 * a changed setting is.
	 */
	/**
	 * The menu going away with nobody here having asked for it.
	 *
	 * Which is to say: the player shut it. Pressing escape on something working
	 * on your behalf means stop, so it stops — and it says why, rather than
	 * looking like it crashed.
	 */
	fun onMenuClosed() {
		if (closingSelf) {
			closingSelf = false
			return
		}
		if (finished || reopenStage != ReopenStage.NONE) return
		if (!ExperimentSolver.module.enabled || !ExperimentSolver.runTable.value) return
		stop("you closed the table")
	}

	fun onMenuOpened(title: String) {
		closingSelf = false
		if (!finished || reopenStage != ReopenStage.NONE) return
		if (title != "Experimentation Table") return
		finished = false
		status = "table reopened, trying again"
	}

	fun tick(client: Minecraft) {
		val running = ExperimentSolver.module.enabled && ExperimentSolver.runTable.value

		// Switching it back on is how a run that stopped itself is started again,
		// so the renew count and the "finished" flag go with it.
		if (running && !wasRunning) reset()
		wasRunning = running

		val fingerprint = settingsFingerprint()
		if (fingerprint != lastSettings) {
			lastSettings = fingerprint
			if (finished) {
				finished = false
				status = "settings changed, trying again"
			}
		}

		if (!running || finished) return

		val now = System.currentTimeMillis()
		if (now < nextActionAt) return

		if (reopenStage != ReopenStage.NONE) {
			reopen(client)
			return
		}

		val title = ExperimentRules.stripFormatting(ExperimentTracker.menuTitle)
		if (title.isEmpty()) return
		val items = ExperimentTracker.menuItems
		if (items.isEmpty()) return

		when {
			title == "Experiment Over" -> {
				status = "experiment over, reopening"
				startReopen()
			}
			title == "Superpairs Rewards" -> claimReward(client, items)
			title == "Bottles of Enchanting" -> buyLevels(client, items)
			title == "Experimentation Table" || title.endsWith("Stakes") -> decide(client, title, items)
			// A game is open: the solver is playing it, and this only decides when
			// enough rounds have been won to walk away with the reward.
			ExperimentTracker.current != null -> leaveWhenDone(client)
			// Anything else is a menu with no rule for it, which is worth saying
			// out loud — a title that has changed since 1.8.9 looks from the
			// outside exactly like the runner doing nothing at all.
			else -> ExperimentDebug.note("no rule for menu \"$title\", standing by")
		}
	}

	/**
	 * The table's own menu: renew if it needs it, otherwise take the first game
	 * that is not finished.
	 */
	private fun decide(client: Minecraft, title: String, items: List<ItemStack>) {
		if (remindAboutGuardian(client)) return

		val renewAt = renewSlot(items)
		if (renewAt != null) {
			val asked = ExperimentSolver.renews.value.toInt()
			// The button itself keeps the real count — "You've renewed 1/3
			// charges today!" — and it survives relogging, where a counter kept
			// here does not. Whichever of the two allows less is the one to obey,
			// so the slider can only ever ask for fewer than the game permits.
			val today = renewsToday(items.getOrNull(renewAt))
			// What the slider still allows, not what it allows in total: leaving
			// the renews already spent out of this let a run set to one renew keep
			// renewing for as long as the day's charges lasted.
			val left = asked - renewsUsed
			val allowed = if (today != null) minOf(left, today.second - today.first) else left

			if (allowed <= 0) {
				stop(
					when {
						asked == 0 -> "the table wants renewing and Renews is set to 0"
						today != null && today.first >= today.second -> "the table's ${today.second} daily renews are gone"
						else -> "all $asked renews used"
					},
				)
				return
			}
			renew(client, items, renewAt)
			return
		}

		if (buyingLevel > 0) {
			status = "buying $buyingLevel levels"
			click(client, SLOT_BOTTLE_MENU)
			return
		}

		if (loreOf(items.getOrNull(SLOT_SUPERPAIRS)).contains(LORE_COOLDOWN)) {
			stop("experiments on cooldown")
			return
		}

		// A stake picker is the same menu with a different name, and the richest
		// one that is not locked is the one worth taking.
		if (title.contains("Chronomatron") && title.contains("Stakes")) {
			pickStake(client, items, CHRONOMATRON_STAKES)
			return
		}
		if (title.contains("Ultrasequencer") && title.contains("Stakes")) {
			pickStake(client, items, ULTRASEQUENCER_STAKES)
			return
		}
		if (title.contains("Superpairs") && title.contains("Stakes")) {
			pickSuperpairsStake(client, items)
			return
		}

		if (!completed(items.getOrNull(SLOT_CHRONOMATRON_STATUS))) {
			status = "starting Chronomatron"
			click(client, SLOT_CHRONOMATRON)
			return
		}
		if (!completed(items.getOrNull(SLOT_ULTRASEQUENCER_STATUS))) {
			status = "starting Ultrasequencer"
			click(client, SLOT_ULTRASEQUENCER)
			return
		}

		status = "starting Superpairs"
		click(client, SLOT_SUPERPAIRS)
	}

	/**
	 * Shuts the table if the Guardian is not the pet that is out.
	 *
	 * The Guardian's experience bonus applies to what these games pay, so a
	 * session run on the wrong pet is a session's worth of it thrown away — and
	 * it is the kind of mistake that is only noticed afterwards.
	 *
	 * Only ever fires on a pet that was *seen* and was something else. A pet
	 * that cannot be found is a pet whose tag was out of range or not sent yet,
	 * and closing somebody's table over that would be worse than never closing
	 * it. Returns true when it acted.
	 */
	private fun remindAboutGuardian(client: Minecraft): Boolean {
		if (!ExperimentSolver.guardianReminder.value) return false
		if (!EquippedPet.isDefinitelyNot(GUARDIAN)) return false

		client.player?.closeContainer()
		ExperimentTracker.closed()
		client.gui.hud.setTimes(TITLE_FADE_TICKS, TITLE_STAY_TICKS, TITLE_FADE_TICKS)
		client.gui.hud.setTitle(Component.literal("§cGuardian pet missing"))
		stop("the Guardian pet is not out (${EquippedPet.name() ?: "unknown"} is)")
		return true
	}

	private fun pickStake(client: Minecraft, items: List<ItemStack>, slots: List<Int>) {
		for (slot in slots) {
			val stack = items.getOrNull(slot) ?: continue
			if (locked(stack)) continue
			if (slot == MAX_ENCHANTING_STAKE) maxEnchanting = true
			status = "taking stake in slot $slot"
			click(client, slot)
			return
		}
		stop("no stake available")
	}

	/**
	 * Superpairs asks for experience up front, so a stake it cannot afford is
	 * worth a trip to the bottle shop rather than giving up on.
	 */
	private fun pickSuperpairsStake(client: Minecraft, items: List<ItemStack>) {
		for (slot in SUPERPAIRS_STAKES) {
			val stack = items.getOrNull(slot) ?: continue
			if (locked(stack)) continue

			val lore = loreOf(stack)
			if (lore.contains(LORE_PLAY)) {
				status = "taking Superpairs stake in slot $slot"
				click(client, slot)
				return
			}
			if (lore.contains(LORE_NOT_ENOUGH_XP)) {
				val cost = STAKE_COST.find(lore)?.groupValues?.get(1)?.toIntOrNull() ?: continue
				if (!mayBuy(cost)) return
				buyingLevel = cost
				status = "Superpairs needs $cost levels, reopening to buy"
				startReopen()
				return
			}
		}
		stop("no Superpairs stake available")
	}

	/**
	 * Whether the table is offering a renew.
	 *
	 * The button is looked for by name across the whole menu rather than only in
	 * the slot 1.8.9 had it in — the slot numbers here are the least trustworthy
	 * thing in this file, and a button that has moved is exactly the failure
	 * that reads as "it is not renewing".
	 */
	private fun renewSlot(items: List<ItemStack>): Int? {
		items.getOrNull(SLOT_RENEW)?.let {
			if (namesRenew(it)) return SLOT_RENEW
		}
		val found = items.indexOfFirst { namesRenew(it) }
		if (found >= 0) {
			ExperimentDebug.note("renew button is in slot $found, not $SLOT_RENEW")
			return found
		}
		return null
	}

	/**
	 * How many of today's renews are gone and how many there are, straight off
	 * the button: "You've renewed 1/3 charges today!".
	 */
	private fun renewsToday(stack: ItemStack?): Pair<Int, Int>? {
		val match = RENEWS_TODAY.find(loreOf(stack)) ?: return null
		val used = match.groupValues[1].toIntOrNull() ?: return null
		val total = match.groupValues[2].toIntOrNull() ?: return null
		return used to total
	}

	private fun namesRenew(stack: ItemStack): Boolean {
		if (stack.isEmpty) return false
		return ExperimentRules.stripFormatting(stack.hoverName.string).contains("Renew Experiments", ignoreCase = true)
	}

	private fun renew(client: Minecraft, items: List<ItemStack>, slot: Int) {
		val lore = loreOf(items.getOrNull(slot)).lowercase()
		when {
			lore.contains(LORE_PURCHASE) -> {
				renewsUsed++
				status = "renewing ($renewsUsed of ${ExperimentSolver.renews.value.toInt()})"
				click(client, slot)
			}
			lore.contains(LORE_CANNOT_AFFORD) -> {
				val cost = ANY_COST.find(loreOf(items.getOrNull(slot)))
					?.groupValues?.get(1)?.toIntOrNull() ?: 0
				if (!mayBuy(cost)) return
				buyingLevel = cost
				status = "renew needs $buyingLevel levels"
				click(client, SLOT_BOTTLE_MENU)
			}
			else -> stop("renew button says nothing recognised: \"$lore\"")
		}
	}

	/**
	 * Whether it is allowed to go shopping for the levels something wants.
	 *
	 * Off by default and stated as a reason rather than a silent refusal: the
	 * bottles are bought from the bazaar with coins, and a Titanic one — which is
	 * what a 350-level stake needs — runs to hundreds of thousands. Spending that
	 * is not something to do on the player's behalf without being asked.
	 */
	private fun mayBuy(levels: Int): Boolean {
		if (!ExperimentSolver.buyExperience.value) {
			stop("needs $levels levels and Buy experience is off")
			return false
		}
		return true
	}

	private fun buyLevels(client: Minecraft, items: List<ItemStack>) {
		if (buyingLevel <= 0) return
		if (!mayBuy(buyingLevel)) return

		val have = OWN_LEVEL.find(loreOf(items.getOrNull(SLOT_GRAND_BOTTLE)))
			?.groupValues?.get(1)?.toIntOrNull() ?: 0
		if (have >= buyingLevel) {
			buyingLevel = 0
			if (boughtBottle) {
				boughtBottle = false
				startReopen()
			} else {
				stop("not enough bits for the levels needed")
			}
			return
		}

		val slot = if (buyingLevel <= 100) SLOT_GRAND_BOTTLE else SLOT_TITANIC_BOTTLE
		if (items.getOrNull(slot)?.isEmpty != false) {
			stop("bottle shop has nothing in slot $slot")
			return
		}
		status = "buying levels (have $have, need $buyingLevel)"
		boughtBottle = true
		click(client, slot)
	}

	private fun claimReward(client: Minecraft, items: List<ItemStack>) {
		if (loreOf(items.getOrNull(SLOT_REWARD)).contains("Click to claim rewards")) {
			status = "claiming Superpairs rewards"
			click(client, SLOT_REWARD, GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return
		}
		startReopen()
	}

	/**
	 * Walks away once the game has given up what it is worth.
	 *
	 * Every round past the target is a chance to lose the lot, and the reward is
	 * already banked, so there is nothing to gain by going on. Each serum drunk
	 * takes one round off that target, which is what the slider is for.
	 */
	private fun leaveWhenDone(client: Minecraft) {
		val handler = ExperimentTracker.current ?: return
		// How many rounds are done is the length of the sequence learned so far,
		// which is read off the board rather than off a header nobody verified.
		val round = handler.roundsDone()
		val target = targetRounds(handler)

		// Leave the moment the target round has been *played out*, not when the
		// next one starts. Waiting for the round after to appear meant sitting
		// through its whole reveal before closing, which is both a wait for
		// nothing and one more round of risk on a run already worth keeping.
		if (round < target) return
		if (round == target && !handler.waiting) return

		status = "${handler.name} reached round $round of $target, taking the reward"
		startReopen()
	}

	private fun startReopen() {
		reopenStage = ReopenStage.CLOSING
		nextActionAt = System.currentTimeMillis() + delay()
	}

	/**
	 * Closes the menu and opens the table again.
	 *
	 * The second half is a right click sent the way [imicro.cryptic.feature.AutoClicker]
	 * sends one — queued onto the use mapping, so Minecraft decides what it hit
	 * — rather than an interact packet aimed at a block this has not looked for.
	 */
	private fun reopen(client: Minecraft) {
		when (reopenStage) {
			ReopenStage.CLOSING -> {
				closingSelf = true
				client.player?.closeContainer()
				ExperimentTracker.closed()
				reopenStage = ReopenStage.OPENING
				nextActionAt = System.currentTimeMillis() + delay()
			}
			ReopenStage.OPENING -> {
				val use = client.options.keyUse
				val key = (use as? KeyMappingAccessor)?.`cryptic$key`()
				if (use.isUnbound || key == null) {
					stop("use key is unbound, cannot reopen the table")
					return
				}
				KeyMapping.click(key)
				status = "reopening the table"
				reopenStage = ReopenStage.NONE
				nextActionAt = System.currentTimeMillis() + delay()
			}
			ReopenStage.NONE -> Unit
		}
	}

	private fun click(client: Minecraft, slot: Int, button: Int = GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
		val player = client.player ?: return
		if (slot !in screen.menu.slots.indices) {
			stop("slot $slot is not in this menu")
			return
		}

		// One click per state of the menu, and no more.
		//
		// A click that lands changes something — a new window, or the contents of
		// this one. Until that happens the menu has not answered, and sending the
		// same click again is what makes Hypixel start refusing them out loud.
		// Counting the repeats was not enough: four of them is still four clicks
		// and four refusals. So a click on the same slot is simply not sent again
		// while the menu is exactly as it was, and if it stays that way for long
		// enough the table is closed and reopened, which is what a person does.
		if (slot == lastClickSlot && ExperimentTracker.revision == lastClickRevision) {
			if (System.currentTimeMillis() - lastClickAt > STUCK_MILLIS) {
				status = "menu ignored the click on slot $slot, reopening the table"
				forgetLastClick()
				startReopen()
			}
			return
		}

		lastClickSlot = slot
		lastClickRevision = ExperimentTracker.revision
		lastClickAt = System.currentTimeMillis()

		val input = if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) ContainerInput.CLONE else ContainerInput.PICKUP
		client.gameMode?.handleContainerInput(screen.menu.containerId, slot, button, input, player)
		nextActionAt = System.currentTimeMillis() + delay()
	}

	/**
	 * Gives up, loudly enough to be found in the debug line.
	 *
	 * Everything this drives costs something to get wrong, so anything it does
	 * not recognise stops it rather than being clicked past.
	 */
	private fun stop(reason: String) {
		finished = true
		status = "stopped: $reason"
	}

	private fun delay(): Long = ExperimentSolver.clickDelay.random().toLong()

	private fun locked(stack: ItemStack): Boolean = loreOf(stack).contains(LORE_LOCKED)

	private fun completed(stack: ItemStack?): Boolean {
		if (stack == null || stack.isEmpty) return true
		val lore = loreOf(stack)
		return lore.contains(LORE_COMPLETED) || lore.contains(LORE_ADDON_LOCKED)
	}

	/** Every line of an item's tooltip, run together, with the colours stripped. */
	private fun loreOf(stack: ItemStack?): String {
		if (stack == null || stack.isEmpty) return ""
		val player = Minecraft.getInstance().player
		val lines = runCatching {
			stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, player, TooltipFlag.NORMAL)
		}.getOrNull() ?: return ""
		return lines.joinToString(" ") { ExperimentRules.stripFormatting(it.string) }
	}
}
