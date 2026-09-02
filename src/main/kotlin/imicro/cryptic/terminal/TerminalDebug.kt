package imicro.cryptic.terminal

import imicro.cryptic.Cryptic
import imicro.cryptic.feature.TerminalSolver
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Watches a terminal as it is played.
 *
 * Two rounds of fixing this by reading the code have not landed, and the reason
 * is plain: everything that matters happens behind an open chest on a server
 * nobody here can reach, and the simulator — which drives the same solver —
 * works. So the difference is in what Hypixel actually sends, and the only way
 * to see that is to write it down while it happens.
 *
 * On: every window that opens, every solve and what it produced, and every click
 * with the reason it was or was not sent. Enough to tell "the solver never
 * solved" from "it solved and the click was refused", which are the two very
 * different faults that look identical from the outside.
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
		Minecraft.getInstance().gui.chat.addClientSystemMessage(
			Component.literal("§8[Cryptic] §7$text"),
		)
	}

	/** A window arriving, and whether anything recognised it. */
	fun windowOpened(title: String, type: TerminalType?) {
		if (!enabled) return
		note("window \"$title\" -> ${type?.name ?: "NOT A TERMINAL"}")
	}

	/**
	 * What a solve produced, and what it was given.
	 *
	 * The item list goes to the log alone: a terminal is fifty-odd slots and
	 * that is not something to put in chat, but it is exactly what is needed to
	 * tell whether the board even arrived.
	 */
	fun solved(handler: TerminalHandler, changedSlot: Int, items: List<ItemStack>, solved: Boolean) {
		if (!enabled) return
		if (!solved) {
			note("slot $changedSlot changed, not a solve (waiting for ${handler.type.windowSize - 1})")
			return
		}

		note("${handler.type.name} solved: ${handler.solution.size} slots -> ${handler.solution.take(12)}")
		Cryptic.LOGGER.info("[terminals] ---- board for ${handler.type.name} ----")
		items.take(handler.type.windowSize).forEachIndexed { slot, stack ->
			if (stack.isEmpty) return@forEachIndexed
			val id = BuiltInRegistries.ITEM.getKey(stack.item)
			Cryptic.LOGGER.info(
				"[terminals]   $slot  $id  x${stack.count}  " +
					"\"${stack.hoverName.string.replace("§", "&")}\"  glint=${stack.hasFoil()}",
			)
		}
		Cryptic.LOGGER.info("[terminals] ---- end board ----")
	}

	/** A click the player made, and what became of it. */
	fun playerClick(handler: TerminalHandler, slot: Int, button: Int, outcome: String) {
		if (!enabled) return
		note(
			"click slot $slot button $button -> $outcome " +
				"(mode ${TerminalSolver.solvingMode.selected}, solution ${handler.solution.size}, " +
				"blocked ${handler.blocksClicks()}, queued ${TerminalClicks.queued})",
		)
	}

	/** A click actually leaving for the server. */
	fun sent(slot: Int, button: Int) {
		if (!enabled) return
		note("sent slot $slot button $button")
	}

	/**
	 * How many of the player's clicks the board has caught up with.
	 *
	 * The one number that says whether the queue is working: a click the server
	 * took is settled and gone, and one it dropped stays queued to be sent
	 * again. A run of boards settling nothing while the queue holds steady is a
	 * terminal that has stopped listening.
	 */
	fun settled(count: Int, remaining: Int) {
		if (!enabled) return
		note("board settled $count click(s); $remaining still queued")
	}

	/** A queued click going out for a second time because the first was dropped. */
	fun resent(slot: Int, button: Int, attempt: Int) {
		if (!enabled) return
		note("re-sending slot $slot button $button (attempt $attempt)")
	}

	/**
	 * What the player sees against what the server last said.
	 *
	 * The two are meant to differ — that difference *is* the mode, and it is
	 * exactly what cannot be seen from a screenshot. Printed after each board so
	 * a run through the log reads as a conversation.
	 */
	fun boards(handler: TerminalHandler, serverWanted: Int, queued: Int) {
		if (!enabled) return
		note(
			"${handler.type.name}: server wants $serverWanted, " +
				"player sees ${handler.solution.size}, $queued queued",
		)
	}

	/**
	 * Why a click is being held back, which is the half that cannot be seen.
	 *
	 * The protection has two halves and either can hold everything shut: a
	 * wall clock since the window opened, and a count of server ticks. Saying
	 * which is what turns "nothing happens when I click" into a fixable report.
	 */
	fun blocked(handler: TerminalHandler) {
		if (!enabled) return
		val sinceOpen = System.currentTimeMillis() - handler.openedAt
		note(
			"blocked: ${sinceOpen}ms since open (needs ${TerminalSolver.firstClickProtection.value.toInt()}), " +
				"server ticks ${handler.serverTicksOpen} (needs ${TerminalSolver.lagProtectionTicks.value.toInt()}, " +
				"available ${ServerTicks.available}, accounting ${TerminalSolver.accountForServerLag.value})",
		)
	}
}
