package imicro.cryptic.terminal

import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Logs a terminal as it is played: windows, solves, and every click with why it was or wasn't sent.
 * Tells "never solved" apart from "solved but the click was refused".
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

	/** A note shown in chat and the log, once. */
	fun note(text: String) {
		if (!enabled) return
		Cryptic.LOGGER.info("[terminals] $text")
		if (text == lastNote) return
		lastNote = text
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
			Component.literal("§8[Cryptic] §7$text"),
		)
	}

	fun windowOpened(title: String, type: TerminalType?) {
		if (!enabled) return
		note("window \"$title\" -> ${type?.name ?: "NOT A TERMINAL"}")
	}

	fun solved(handler: TerminalHandler, changedSlot: Int) {
		if (!enabled) return
		note(
			"${handler.type.name} slot $changedSlot -> ${handler.solution.size} left " +
				"${handler.solution.take(12)}, ${handler.clickedSlots.size} in flight",
		)
	}

	fun clicked(handler: TerminalHandler, slot: Int, button: Int, sent: Boolean) {
		if (!enabled) return
		val reason = when {
			sent -> "sent"
			handler.blocksClicks() -> "blocked by first click protection"
			else -> "refused: not part of the answer"
		}
		note("${handler.type.name} click slot $slot button $button -> $reason")
	}

	fun timedOut(handler: TerminalHandler) {
		if (!enabled) return
		note("${handler.type.name} resolve timeout: predictions dropped, board read again")
	}
}
