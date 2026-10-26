package imicro.cryptic.terminal

import imicro.cryptic.terminal.sim.TermSimScreen
import imicro.cryptic.feature.MelodyHud
import imicro.cryptic.feature.TerminalSolver
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack

/**
 * Tracks which terminal is open, so the solver module only asks questions. Ported from Odin's
 * `TerminalUtils`.
 *
 * Hypixel keeps the chest open and sends only changed slots, so one handler lasts from window open
 * to close, solving each slot as it arrives.
 */
object Terminals {
	var current: TerminalHandler? = null
		private set

	/**
	 * The last terminal opened, kept after it closes, since Hypixel announces the solve in chat
	 * after the window is gone.
	 */
	var lastOpened: TerminalHandler? = null
		private set

	private var currentTitle: String? = null
	private var currentContainerId = Int.MIN_VALUE

	/**
	 * Called for every chest the server opens, and by the simulator. [containerId] tells a re-sent
	 * window apart from the next terminal of the same kind, which would otherwise inherit the old
	 * click-protection clock.
	 */
	fun windowOpened(title: String, containerId: Int) {
		val type = TerminalType.of(title)
		TerminalDebug.windowOpened(title, type)

		if (type == null) {
			// Some other menu: the terminal is over.
			closed()
			return
		}

		if (title == currentTitle && containerId == currentContainerId && current != null) {
			// The same window announced again: keep the board, restart the protection clock.
			current?.restartProtection()
			return
		}

		current = TerminalType.handlerFor(title) ?: return
		currentTitle = title
		currentContainerId = containerId
		lastOpened = current
	}

	/** Solved straight away, so the terminal feels instant. */
	fun slotUpdated(slot: Int, items: List<ItemStack>) {
		val handler = current ?: return
		handler.updateSlot(slot, items)
		TerminalDebug.solved(handler, slot)
	}

	/** A whole window at once, fed through slot by slot since solvers care which slot moved. */
	fun windowFilled(items: List<ItemStack>) {
		val handler = current ?: return
		for (slot in 0 until handler.puzzleSize) handler.updateSlot(slot, items)
		TerminalDebug.solved(handler, handler.puzzleSize - 1)
	}

	fun closed() {
		if (current?.type == TerminalType.MELODY) MelodyHud.onTerminalClosed()
		current = null
		currentTitle = null
		currentContainerId = Int.MIN_VALUE
	}

	/**
	 * Whether [screen] is the tracked terminal's window; everything the solver does checks this
	 * first. Without it, a stale terminal could swallow clicks in the next menu (the inventory
	 * numbers slots from zero too). Simulator chests share the inventory's container id, so they're
	 * matched by title.
	 */
	fun screenIsCurrent(screen: AbstractContainerScreen<*>): Boolean {
		if (current == null) return false
		if (screen.title.string != currentTitle) return false
		return screen is TermSimScreen || screen.menu.containerId == currentContainerId
	}

	fun onServerTick() {
		current?.let { it.serverTicksOpen++ }
	}

	/**
	 * Notices the player leaving without a close packet (escape), and drops clicks the server never
	 * answered after the resolve timeout.
	 */
	fun tick(client: Minecraft) {
		val screen = client.gui.screen() as? AbstractContainerScreen<*>
		// Any other window open means this terminal is over.
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
