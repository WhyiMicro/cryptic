package imicro.cryptic.terminal

import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Watches a terminal as it is played.
 *
 * Everything that matters happens behind an open chest on a server nobody here
 * can reach, and the simulator — which drives the same solver — works. So when
 * the two disagree the difference is in what Hypixel actually sends, and the
 * only way to see that is to write it down while it happens.
 *
 * On: every window that opens, every solve and what it produced, and every
 * click with the reason it was or was not sent. Enough to tell "the solver
 * never solved" from "it solved and the click was refused", which are the two
 * very different faults that look identical from the outside.
 */
object TerminalDebug {
	var enabled = false
		private set

	private var lastNote = ""

	fun toggle(): Boolean {
		enabled = !enabled
		lastNote = ""
		if (enabled) {
			Cryptic.LOGGER.info("[terminals] watching. Play one terminal, then send logs/latest.log.")
		}
		return enabled
	}

	/** A line worth seeing as it happens, said once. */
	fun note(text: String) {
		if (!enabled) return
		Cryptic.LOGGER.info("[terminals] $text")
		if (text == lastNote) return
		lastNote = text
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
			Component.literal("§8[Cryptic] §7$text"),
		)
	}

	/** A window arriving, and whether anything recognised it. */
	fun windowOpened(title: String, type: TerminalType?) {
		if (!enabled) return
		note("window \"$title\" -> ${type?.name ?: "NOT A TERMINAL"}")
	}

	/** What a solve produced, after the slot that caused it. */
	fun solved(handler: TerminalHandler, changedSlot: Int) {
		if (!enabled) return
		note(
			"${handler.type.name} slot $changedSlot -> ${handler.solution.size} left " +
				"${handler.solution.take(12)}, ${handler.clickedSlots.size} in flight",
		)
	}

	/** A click, and what became of it. */
	fun clicked(handler: TerminalHandler, slot: Int, button: Int, sent: Boolean) {
		if (!enabled) return
		val reason = when {
			sent -> "sent"
			handler.blocksClicks() -> "blocked by first click protection"
			else -> "refused: not part of the answer"
		}
		note("${handler.type.name} click slot $slot button $button -> $reason")
	}

	/** The resolve timeout giving up on clicks the server never answered. */
	fun timedOut(handler: TerminalHandler) {
		if (!enabled) return
		note("${handler.type.name} resolve timeout: predictions dropped, board read again")
	}
}
