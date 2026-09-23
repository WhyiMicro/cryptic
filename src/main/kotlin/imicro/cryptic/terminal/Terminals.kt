package imicro.cryptic.terminal

import imicro.cryptic.terminal.sim.TermSimScreen
import imicro.cryptic.feature.TerminalSolver
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack

/**
 * Which terminal is open, if any.
 *
 * Kept apart from the module that draws it, in the same way
 * [imicro.cryptic.dungeon.DungeonTeam] is kept apart from the modules that read
 * the party, so the tracking runs in one place and the feature only asks it
 * questions. Ported from Odin's `TerminalUtils` (BSD 3-Clause, Copyright (c)
 * 2025 odtheking).
 *
 * Hypixel used to answer every click by opening the whole chest again. It does
 * not any more — the chest stays open and only the slots that changed are sent
 * — so a terminal is one handler from the window opening to the window closing,
 * and each slot that arrives is solved for as it comes.
 */
object Terminals {
	var current: TerminalHandler? = null
		private set

	/**
	 * The terminal most recently opened, kept after it closes.
	 *
	 * Hypixel says a terminal was solved in chat a moment *after* the window
	 * has gone, so whoever wants to time one has to look at what was open
	 * rather than what is.
	 */
	var lastOpened: TerminalHandler? = null
		private set

	private var currentTitle: String? = null
	private var currentContainerId = Int.MIN_VALUE

	/**
	 * Called for every chest the server opens, and by the simulator for its own.
	 *
	 * [containerId] is what tells a terminal being re-sent from the next
	 * terminal of the same kind: two panes terminals in a row have the same
	 * title and nothing else to tell them apart, and carrying the first one's
	 * handler into the second would carry its click-protection clock with it —
	 * which is the clock saying how long the player has had to look at a board
	 * they have not seen yet.
	 */
	fun windowOpened(title: String, containerId: Int) {
		val type = TerminalType.of(title)
		TerminalDebug.windowOpened(title, type)

		if (type == null) {
			// Some other menu, which means the terminal is behind us — and the
			// solver must not end up painted over an auction house.
			closed()
			return
		}

		if (title == currentTitle && containerId == currentContainerId && current != null) {
			// The same window announced again, which Hypixel does. The board it
			// holds is kept — a repeat announcement does not always resend the
			// slots — but the protection clock starts over, because the player
			// has only ever seen the last of them.
			current?.restartProtection()
			return
		}

		current = TerminalType.handlerFor(title) ?: return
		currentTitle = title
		currentContainerId = containerId
		lastOpened = current
	}

	/**
	 * One slot of the open terminal changed. Solved for straight away: the
	 * board is only ever as good as the last packet, and a terminal that waits
	 * a tick to answer is a terminal that feels slow.
	 */
	fun slotUpdated(slot: Int, items: List<ItemStack>) {
		val handler = current ?: return
		handler.updateSlot(slot, items)
		TerminalDebug.solved(handler, slot)
	}

	/**
	 * A whole window arriving at once rather than a slot at a time.
	 *
	 * Fed through slot by slot rather than as one update, because the solvers
	 * are told which slot moved and two of them use it — rubix settles on the
	 * colour it is aiming for when the last pane of the grid lands.
	 */
	fun windowFilled(items: List<ItemStack>) {
		val handler = current ?: return
		for (slot in 0 until handler.puzzleSize) handler.updateSlot(slot, items)
		TerminalDebug.solved(handler, handler.puzzleSize - 1)
	}

	fun closed() {
		current = null
		currentTitle = null
		currentContainerId = Int.MIN_VALUE
	}

	/**
	 * Whether [screen] is the window the tracked terminal belongs to.
	 *
	 * Everything the solver does — hiding items, swallowing clicks, taking the
	 * screen over — has to be asked this first. Without it, a terminal left
	 * tracked after its chest has gone reaches whatever menu is opened next:
	 * the container id and the title are what tie the two together, and the
	 * player's own inventory numbers its slots from zero exactly as a terminal
	 * does, so a stale terminal meant an inventory whose clicks all vanished.
	 *
	 * The simulator's chests are the client's own and share the inventory's
	 * container id, so those are told apart by title alone — which is enough,
	 * because nothing else on the client is called "Correct all the panes!".
	 */
	fun screenIsCurrent(screen: AbstractContainerScreen<*>): Boolean {
		if (current == null) return false
		if (screen.title.string != currentTitle) return false
		return screen is TermSimScreen || screen.menu.containerId == currentContainerId
	}

	/** The server's own clock, which the lag half of click protection counts. */
	fun onServerTick() {
		current?.let { it.serverTicksOpen++ }
	}

	/**
	 * Notices the player leaving a terminal any way that does not send a close
	 * packet, which pressing escape is, and drops clicks the server never
	 * answered.
	 *
	 * A predicted click that is never confirmed would otherwise sit on the
	 * board forever, hiding a slot that still needs clicking. After the resolve
	 * timeout the guesses are thrown away and the board is solved again from
	 * what is really in the chest.
	 */
	fun tick(client: Minecraft) {
		val screen = client.gui.screen() as? AbstractContainerScreen<*>
		// Any other window being open means this terminal is behind us, however
		// it went away — escape, a menu opened in its place, or a close packet
		// that never came.
		if (current != null && (screen == null || !screenIsCurrent(screen))) {
			closed()
			return
		}

		val handler = current ?: return
		if (handler.clickedSlots.isEmpty()) return
		if (System.currentTimeMillis() - handler.lastClickTime < TerminalSolver.resolveTimeout.value) return

		handler.forgetSentClicks()
		val items = screen?.menu?.items ?: return
		for (slot in 0 until handler.puzzleSize) handler.updateSlot(slot, items)
		TerminalDebug.timedOut(handler)
	}
}
