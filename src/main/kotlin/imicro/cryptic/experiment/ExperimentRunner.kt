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
	private const val LORE_NOT_ENOUGH_XP = "Not enough experience!"

	private val STAKE_COST = Regex("""Starting\s+cost:\s*(\d+)\s*XP\s*Levels?""", RegexOption.IGNORE_CASE)
	private val ANY_COST = Regex("""(\d+)\s*XP\s*Levels?""", RegexOption.IGNORE_CASE)
	private val RENEWS_TODAY = Regex("""renewed\s*(\d+)\s*/\s*(\d+)\s*charges""", RegexOption.IGNORE_CASE)
	private val OWN_LEVEL = Regex("""Your\s+Exp\s+Level:\s*(\d+)""", RegexOption.IGNORE_CASE)

	var renewsUsed = 0
		private set

	private var maxEnchanting = false

	/** The level a stake wanted and couldn't afford, or zero. */
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
			// A game is open; the solver plays it and this only decides when to leave.
			ExperimentTracker.current != null -> leaveWhenDone(client)
			// Said out loud, since a menu with no rule looks the same as the runner doing nothing.
			else -> ExperimentDebug.note("no rule for menu \"$title\", standing by")
		}
	}

	/** The table's menu: renew if needed, otherwise open the first unfinished game. */
	private fun decide(client: Minecraft, title: String, items: List<ItemStack>) {
		if (remindAboutGuardian(client)) return

		val renewAt = renewSlot(items)
		if (renewAt != null) {
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

		if (loreOf(items.getOrNull(SLOT_SUPERPAIRS)).contains(LORE_COOLDOWN)) {
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
			click(client, SLOT_REWARD, GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return
		}
		startReopen()
	}

	/** Leaves once the target round has been played; every round past it risks the reward. */
	private fun leaveWhenDone(client: Minecraft) {
		val handler = ExperimentTracker.current ?: return
		// Rounds done is the length of the sequence learned so far.
		val round = handler.roundsDone()
		val target = targetRounds(handler)

		// Leave as soon as the target round is played out, not when the next one starts.
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
