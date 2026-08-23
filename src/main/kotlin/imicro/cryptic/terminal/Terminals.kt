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
		current?.let { if (!it.clicked && it.windowCount <= 2) closed() }

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
	}

	fun slotUpdated(slot: Int, items: List<ItemStack>) {
		val handler = current ?: return
		// Only a real re-solve is worth telling the clicking about; most slot
		// packets are one more square of a window still being drawn.
		if (handler.slotUpdated(slot, items)) TerminalClicks.onSolved(handler)
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
		TerminalClicks.reset()
	}

	/**
	 * Notices the player leaving a terminal any way that does not send a close
	 * packet, which pressing escape is.
	 */
	fun tick(client: Minecraft) {
		if (current != null && client.screen !is AbstractContainerScreen<*>) closed()
		TerminalClicks.tick()
	}
}
