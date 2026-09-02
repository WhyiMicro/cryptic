package imicro.cryptic.terminal

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack

/**
 * Which terminal is open, if any.
 *
 * Kept apart from the module that draws it, in the same way [imicro.cryptic.dungeon.DungeonTeam]
 * is kept apart from the modules that read the party, so the tracking runs in
 * one place and the feature only asks it questions. Ported from Odin's
 * `TerminalUtils` (BSD 3-Clause, Copyright (c) 2025 odtheking).
 */
object Terminals {
	var current: TerminalHandler? = null
		private set

	private var currentTitle: String? = null

	/**
	 * Called for every chest the server opens, and by the simulator for its own.
	 *
	 * Hypixel answers each click with a whole new window rather than a slot
	 * update, so the same terminal opens over and over; the handler is kept
	 * across those so the state it has built up survives, which is what lets
	 * rubix keep aiming at one colour and the letter terminal remember what it
	 * has already taken.
	 */
	fun windowOpened(title: String) {
		// Hypixel sends the opening window more than once, and the player can
		// only have seen the last of them, so the handler — and with it the
		// clock the click protection runs on — starts again until the first
		// click says the terminal is really being played.
		current?.let { if (!it.everClicked && it.windowCount <= 2) closed() }

		TerminalDebug.windowOpened(title, TerminalType.of(title))
		if (TerminalType.of(title) == null) {
			// Some other menu, which means the terminal is behind us — and the
			// solver must not end up painted over an auction house.
			closed()
			return
		}

		if (title != currentTitle) {
			current = TerminalType.handlerFor(title) ?: return
			currentTitle = title
			TerminalClicks.reset()
		}

		current?.windowOpened()
		// Hypixel answers a click by opening the terminal again, so this — and
		// not a slot update inside the window already open — is the click being
		// answered. Told after the handler, so the window has been counted.
		TerminalClicks.onWindowOpened()
	}

	/** Set by a slot update, cleared by the tick that solves for it. */
	private var dirty = false
	private var dirtyItems: List<ItemStack> = emptyList()
	private var dirtySlot = 0

	/**
	 * A slot of the open terminal changed.
	 *
	 * Nothing is solved here, only noted. Hypixel used to answer a click by
	 * opening the whole chest again, so waiting for the last slot of a window
	 * was a reliable "the board is complete, solve it". It does not do that any
	 * more — the chest stays open and the slots that changed are sent on their
	 * own — and waiting for a last slot that never arrives meant never solving
	 * at all.
	 *
	 * So every change counts, and the tick decides when to act on it. That still
	 * collapses the opening fill, which is fifty-odd packets in a row, into one
	 * solve.
	 */
	fun slotUpdated(slot: Int, items: List<ItemStack>) {
		val handler = current ?: return
		if (slot !in 0 until handler.type.windowSize) return
		dirty = true
		dirtySlot = slot
		dirtyItems = items
	}

	/** Solves for whatever changed since the last tick, once. */
	private fun solvePending() {
		if (!dirty) return
		dirty = false

		val handler = current ?: return
		val solved = handler.resolve(dirtyItems)
		TerminalDebug.solved(handler, dirtySlot, dirtyItems, solved)
		if (solved) TerminalClicks.onSolved(handler)
	}

	/**
	 * A whole window arriving at once, rather than a slot at a time.
	 *
	 * Reported as the last slot changing, because that is what the solvers
	 * treat as "the window is complete, solve it now".
	 */
	fun windowFilled(items: List<ItemStack>) {
		slotUpdated((current ?: return).type.windowSize - 1, items)
	}

	fun closed() {
		current = null
		currentTitle = null
		dirty = false
		dirtyItems = emptyList()
		TerminalClicks.reset()
	}

	/**
	 * Notices the player leaving a terminal any way that does not send a close
	 * packet, which pressing escape is.
	 */
	fun tick(client: Minecraft) {
		if (current != null && client.screen !is AbstractContainerScreen<*>) closed()
		solvePending()
		TerminalClicks.tick()
	}
}
