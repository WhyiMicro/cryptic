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
 * Works the Experimentation Table between games: picks the next game, its stake, renews, and
 * reopens the table. Solving is [ExperimentTracker]'s job and clicking inside a game is
 * [ExperimentSolver]'s.
 *
 * Slot numbers and lore follow RBDT V5's `AutoExperiments` (read, not ported) and were confirmed
 * against a real table. This menu spends things, so buying experience is off unless asked for, and
 * anything unrecognised stops the run.
 */
object ExperimentRunner {
	/** The table's buttons for each game. */
	private const val SLOT_SUPERPAIRS = 22
	private const val SLOT_CHRONOMATRON = 29
	private const val SLOT_RENEW = 31
	private const val SLOT_ULTRASEQUENCER = 33
	private const val SLOT_BOTTLE_MENU = 50

	/** Where the table reports each game as done (separate slots from the buttons, as V5 has them). */
	private const val SLOT_CHRONOMATRON_STATUS = 21
	private const val SLOT_ULTRASEQUENCER_STATUS = 23

	private const val SLOT_GRAND_BOTTLE = 12
	private const val SLOT_TITANIC_BOTTLE = 14

	private const val SLOT_REWARD = 13

	/** Stake buttons, richest first. */
	private val CHRONOMATRON_STAKES = listOf(24, 23, 22, 21, 20)
	private val ULTRASEQUENCER_STAKES = listOf(23, 22, 21)
	private val SUPERPAIRS_STAKES = listOf(32, 31, 30, 23, 22, 21)

	/** The richest Chronomatron stake, which raises the target. */
	private const val MAX_ENCHANTING_STAKE = 24

	private const val LORE_LOCKED = "Enchanting level too low!"
	private const val LORE_COMPLETED = "Experiment completed"
	private const val LORE_ADDON_LOCKED = "Add-on locked!"
	private const val LORE_COOLDOWN = "Experiments on cooldown!"
	private const val LORE_PURCHASE = "click to purchase"
	private const val LORE_CANNOT_AFFORD = "cannot afford this!"
	private const val LORE_PLAY = "Click to play!"

	/** An add-on's button once it's been played this charge (case matters: Superpairs says "Experiments on cooldown!"). */
	private const val LORE_ADDON_DONE = "On cooldown!"
	private val CHARGES = Regex("""Charges:\s*(\d+)\s*/\s*\d+""")
	private const val LORE_NOT_ENOUGH_XP = "Not enough experience!"

	private val STAKE_COST = Regex("""Starting\s+cost:\s*(\d+)\s*XP\s*Levels?""", RegexOption.IGNORE_CASE)
	private val ANY_COST = Regex("""(\d+)\s*XP\s*Levels?""", RegexOption.IGNORE_CASE)
	private val RENEWS_TODAY = Regex("""renewed\s*(\d+)\s*/\s*(\d+)\s*charges""", RegexOption.IGNORE_CASE)
	private val OWN_LEVEL = Regex("""Your\s+Exp\s+Level:\s*(\d+)""", RegexOption.IGNORE_CASE)

	var renewsUsed = 0
		private set

	private var maxEnchanting = false

	/** The level a stake wanted and couldn't afford, or zero. */
	/** Set when the Superpairs rewards are claimed: Hypixel closes the menu, and the table is next. */
	private var afterClaim = false

	private var buyingLevel = 0
	private var boughtBottle = false

	private var nextActionAt = 0L

	var finished = false
		private set

	private var reopenStage = ReopenStage.NONE

	private enum class ReopenStage { NONE, CLOSING, OPENING }

	var status: String = "idle"
		private set(value) {
			field = value
			ExperimentDebug.note("runner: $value")
		}

	fun reset() {
		afterClaim = false
		renewsUsed = 0
		maxEnchanting = false
		buyingLevel = 0
		boughtBottle = false
		finished = false
		reopenStage = ReopenStage.NONE
		closingSelf = false
		played.clear()
		lastStarted = null
		refusedSuperpairs = 0
		addonsDoubted = false
		forgetLastClick()
		status = "idle"
	}

	/**
	 * Games done this run, from Hypixel's own lines. The table's status lore can lag a reopen, and
	 * clicking a game that's done only gets "This experiment is on cooldown!" back.
	 */
	private val played = HashSet<ExperimentRules.Game>()

	/** The game the runner last clicked to start, for the lines that don't say which. */
	private var lastStarted: ExperimentRules.Game? = null

	/** How often Hypixel has sent Superpairs back for unplayed add-ons, which the table showed as done. */
	private var refusedSuperpairs = 0

	/** Set once Hypixel has said so: the add-ons' "completed" lore isn't trusted for the rest of the run. */
	private var addonsDoubted = false

	/** An add-on is done once played this round, or when its own button says so ("On cooldown!" or "Click to play!"). */
	private fun done(game: ExperimentRules.Game, status: ItemStack?, button: ItemStack?): Boolean {
		if (game in played) return true
		val lore = loreOf(button)
		if (lore.contains(LORE_ADDON_DONE)) return true
		if (lore.contains(LORE_PLAY)) return false
		return !addonsDoubted && completed(status)
	}

	/** Superpairs' charges left, from "Charges: 1/3" on its button. */
	private fun charges(items: List<ItemStack>): Int? =
		CHARGES.find(loreOf(items.getOrNull(SLOT_SUPERPAIRS)))?.groupValues?.get(1)?.toIntOrNull()

	/** A charge left after a Superpairs: the add-ons open again and the whole round is played once more. */
	private fun newRound(charges: Int) {
		played.clear()
		lastStarted = null
		refusedSuperpairs = 0
		addonsDoubted = false
		forgetLastClick()
		status = "Superpairs has $charges charge${if (charges == 1) "" else "s"} left, another round"
	}

	private val CLAIMED = Regex("""^You claimed the (\w+) rewards!$""")
	private val ON_COOLDOWN = setOf("This experiment is on cooldown!", "You may only play practice mode!")
	private const val ADDONS_FIRST = "Play the add-on experiments before playing Superpairs!"

	/** A fallback: the menus are what the runner goes by, but these lines say when it got one wrong. */
	fun onChat(line: String) {
		if (!ExperimentSolver.runsTable || finished) return
		CLAIMED.matchEntire(line)?.let { match ->
			ExperimentRules.Game.entries.firstOrNull { it.name.equals(match.groupValues[1], ignoreCase = true) }?.let { played += it }
			return
		}
		if (line in ON_COOLDOWN) {
			val game = lastStarted ?: return
			ExperimentDebug.note("runner: Hypixel says ${game.name.lowercase()} is on cooldown")
			if (game == ExperimentRules.Game.SUPERPAIRS) {
				stop("experiments on cooldown")
				return
			}
			played += game
			lastStarted = null
			forgetLastClick()
			nextActionAt = System.currentTimeMillis() + delay()
			return
		}
		if (line == ADDONS_FIRST) {
			// After a Superpairs it means the round is over: the add-ons come back only with a renew.
			if (ExperimentRules.Game.SUPERPAIRS in played) {
				stop("all three games played")
				return
			}
			if (++refusedSuperpairs >= 2) {
				stop("Hypixel wants the add-on games played first, but the table shows them done")
				return
			}
			played.remove(ExperimentRules.Game.CHRONOMATRON)
			played.remove(ExperimentRules.Game.ULTRASEQUENCER)
			addonsDoubted = true
			forgetLastClick()
			status = "Hypixel wants the add-ons first, looking again"
		}
	}

	/**
	 * How many rounds the open game is worth. For experience: as deep as it goes. For items: only
	 * the chain the add-ons need (minus a round per serum), then the rest goes to Superpairs, the
	 * only game paying in books.
	 */
	fun targetRounds(handler: ExperimentHandler): Int {
		val serums = ExperimentSolver.serums.value.toInt()
		return when (handler.game) {
			ExperimentRules.Game.CHRONOMATRON ->
				if (ExperimentSolver.huntingItems) ((if (maxEnchanting) 12 else 9) - serums).coerceAtLeast(1) else 15
			ExperimentRules.Game.ULTRASEQUENCER ->
				if (ExperimentSolver.huntingItems) ((if (maxEnchanting) 9 else 7) - serums).coerceAtLeast(1) else 20
			ExperimentRules.Game.SUPERPAIRS -> Int.MAX_VALUE
		}
	}

	private var wasRunning = false

	/** The settings a decision to stop depended on; changing any of them retries. */
	private fun settingsFingerprint(): String =
		"${ExperimentSolver.renews.value}|${ExperimentSolver.buyExperience.value}|" +
			"${ExperimentSolver.focus.selectedIndex}|${ExperimentSolver.serums.value}|" +
			"${ExperimentSolver.guardianReminder.value}"

	private var lastSettings = ""

	/**
	 * The last click and the menu state it was sent against, so the same click isn't resent to a
	 * menu that hasn't answered.
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

	/** True while the runner itself is closing the menu, to tell it apart from the player closing it. */
	private var closingSelf = false

	private const val GUARDIAN = "Guardian"

	private const val TITLE_FADE_TICKS = 5
	private const val TITLE_STAY_TICKS = 50

	/** A menu opening: the last click worked, and opening the table by hand asks for another go. */
	/** The player closed the menu, so the run stops and says why. */
	fun onMenuClosed() {
		if (closingSelf) {
			closingSelf = false
			return
		}
		if (finished || reopenStage != ReopenStage.NONE) return
		if (!ExperimentSolver.runsTable) return
		// Hypixel closes the rewards menu itself once they're claimed.
		if (afterClaim) return
		stop("you closed the table")
	}

	fun onMenuOpened(title: String) {
		closingSelf = false
		if (!finished || reopenStage != ReopenStage.NONE) return
		if (title != "Experimentation Table") return
		finished = false
		status = "table reopened, trying again"
	}

	/** While it runs the table, the game mustn't pause itself for being tabbed out. */
	@JvmStatic
	fun keepsGameRunning(): Boolean = ExperimentSolver.runsTable && !finished

	fun tick(client: Minecraft) {
		val running = ExperimentSolver.runsTable

		// Switching it back on restarts a run that stopped itself.
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

		if (!running || finished || ExperimentSolver.paused) return

		val now = System.currentTimeMillis()
		if (now < nextActionAt) return

		if (reopenStage != ReopenStage.NONE) {
			reopen(client)
			return
		}

		// Claiming closes the rewards menu; back to the table to see what charges are left.
		if (afterClaim && client.gui.screen() !is AbstractContainerScreen<*>) {
			afterClaim = false
			status = "rewards claimed, back to the table"
			reopenStage = ReopenStage.OPENING
			nextActionAt = now + delay()
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
			// A game is open; the solver plays it and this only decides when to leave.
			ExperimentTracker.current != null -> leaveWhenDone(client)
			// Said out loud, since a menu with no rule looks the same as the runner doing nothing.
			else -> ExperimentDebug.note("no rule for menu \"$title\", standing by")
		}
	}

	/** The table's menu: renew if needed, otherwise open the first unfinished game. */
	private fun decide(client: Minecraft, title: String, items: List<ItemStack>) {
		if (remindAboutGuardian(client)) return

		// Free charges are played before anything is paid for; the renew button can show beside them.
		val freeCharges = ExperimentSolver.allCharges.value && (charges(items) ?: 0) > 0
		val renewAt = renewSlot(items)
		if (renewAt != null && !freeCharges) {
			val asked = ExperimentSolver.renews.value.toInt()
			// The button's own count ("You've renewed 1/3 charges today!") survives relogging; obey
			// whichever allows fewer.
			val today = renewsToday(items.getOrNull(renewAt))
			// What the slider still allows, after the renews already spent.
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

		if (!freeCharges && loreOf(items.getOrNull(SLOT_SUPERPAIRS)).contains(LORE_COOLDOWN)) {
			stop("experiments on cooldown")
			return
		}

		// A stake picker: take the richest unlocked stake.
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

		// The table's slots arrive a few at a time; an empty status slot would read as done.
		if (listOf(SLOT_CHRONOMATRON, SLOT_ULTRASEQUENCER, SLOT_SUPERPAIRS).any { items.getOrNull(it)?.isEmpty != false }) {
			ExperimentDebug.note("runner: table still loading")
			return
		}

		if (ExperimentRules.Game.SUPERPAIRS in played) {
			val charges = charges(items)
			if (charges == null || charges <= 0 || !ExperimentSolver.allCharges.value) {
				stop("all three games played")
				return
			}
			newRound(charges)
		}
		if (!done(ExperimentRules.Game.CHRONOMATRON, items.getOrNull(SLOT_CHRONOMATRON_STATUS), items.getOrNull(SLOT_CHRONOMATRON))) {
			start(client, ExperimentRules.Game.CHRONOMATRON, SLOT_CHRONOMATRON)
			return
		}
		if (!done(ExperimentRules.Game.ULTRASEQUENCER, items.getOrNull(SLOT_ULTRASEQUENCER_STATUS), items.getOrNull(SLOT_ULTRASEQUENCER))) {
			start(client, ExperimentRules.Game.ULTRASEQUENCER, SLOT_ULTRASEQUENCER)
			return
		}
		start(client, ExperimentRules.Game.SUPERPAIRS, SLOT_SUPERPAIRS)
	}

	private fun start(client: Minecraft, game: ExperimentRules.Game, slot: Int) {
		status = "starting ${game.name.lowercase().replaceFirstChar { it.uppercase() }}"
		lastStarted = game
		click(client, slot)
	}

	/**
	 * Closes the table if a pet other than the Guardian is out, since its experience bonus is what
	 * these games pay into. Only fires on a pet seen to be something else. Returns true when it
	 * acted.
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

	/** Superpairs needs experience up front, so an unaffordable stake means a trip to the bottle shop. */
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

	/** The renew button, found by name anywhere in the menu. */
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
				// A renew opens every game again.
				played.clear()
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
	 * Whether buying levels is allowed. Off by default, and said as a reason: a Titanic bottle
	 * costs hundreds of thousands of coins.
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
			afterClaim = true
			click(client, SLOT_REWARD, GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return
		}
		startReopen()
	}

	/** Leaves once the target round has been played; every round past it risks the reward. */
	private fun leaveWhenDone(client: Minecraft) {
		val handler = ExperimentTracker.current ?: return
		// Superpairs pays out on leaving, so it's left as soon as its clicks can't earn more.
		if (handler is SuperpairsHandler) {
			if (!handler.finished) return
			status = "Superpairs is done (${handler.clicksLeft.coerceAtLeast(0)} clicks left), taking the reward"
			startReopen()
			return
		}
		// Rounds done is the length of the sequence learned so far.
		val round = handler.roundsDone()
		val target = targetRounds(handler)

		// Leave as soon as the target round is played back, not when the next one starts. Played back,
		// not just shown: Ultrasequencer counts a round from the moment its numbers appear.
		if (round < target) return
		if (round == target && !handler.roundComplete) return

		status = "${handler.name} reached round $round of $target, taking the reward"
		startReopen()
	}

	private fun startReopen() {
		reopenStage = ReopenStage.CLOSING
		nextActionAt = System.currentTimeMillis() + delay()
	}

	/**
	 * Closes the menu and reopens the table, with a right click queued on the use key so Minecraft
	 * decides what it hits.
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
				// Hypixel opens some menus itself (Superpairs Rewards). A use click sent over one waits
				// in Minecraft until that menu closes, then reopens the table behind your back.
				if (client.gui.screen() is AbstractContainerScreen<*>) {
					status = "a menu opened by itself, carrying on there"
					reopenStage = ReopenStage.NONE
					nextActionAt = System.currentTimeMillis() + delay()
					return
				}
				// A pause menu from before the run (or another mod) would take the click instead.
				if (client.gui.screen() is net.minecraft.client.gui.screens.PauseScreen) client.gui.setScreen(null)
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

		// One click per menu state. Resending to a menu that hasn't answered makes Hypixel refuse
		// clicks, so a repeat is held back, and if the menu stays stuck the table is reopened.
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

	/** Stops the run with a reason, rather than clicking past something unknown. */
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

	private fun loreOf(stack: ItemStack?): String {
		if (stack == null || stack.isEmpty) return ""
		val player = Minecraft.getInstance().player
		val lines = runCatching {
			stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, player, TooltipFlag.NORMAL)
		}.getOrNull() ?: return ""
		return lines.joinToString(" ") { ExperimentRules.stripFormatting(it.string) }
	}
}
