package imicro.cryptic.terminal

import imicro.cryptic.feature.TerminalSolver
import org.lwjgl.glfw.GLFW

/**
 * How a click reaches the server: one at a time, buffered, or without the
 * player at all.
 *
 * Hypixel answers every terminal click with a whole new window, so a terminal
 * is really a conversation: click, wait, look at what came back, click again.
 * Clicking faster than that conversation goes either does nothing or leaves the
 * board and the player disagreeing. The three modes are three answers to that,
 * ported from NoammAddons (`TerminalSolver`'s Normal/Q-Terms modes and
 * `AutoTerminal`, Copyright (c) Noamm9).
 *
 * - **Manual terms** sends each click straight out, and drops one made while
 *   the first-click protection is running.
 * - **Que terms** buffers them instead and lets them out in order. The board is
 *   moved on locally as each click is queued, so the terminal answers the
 *   player's fingers rather than their ping — the point of the mode, on a
 *   connection where waiting a round trip per click is most of the terminal.
 *   None of that waiting is skipped, only moved to the end: the queue is still
 *   draining after the last click has been made, one click per answer from the
 *   server and never faster than [TerminalSolver.clickDelay]. What the server
 *   sees is a person playing at their own pace; what the player sees is a
 *   terminal with no ping in it.
 * - **Auto terms** picks the clicks itself, at the same spacing.
 *
 * Both of the last two lean on [TerminalHandler.predict] to guess the board
 * forward. Every guess is thrown away and rebuilt the moment a real window
 * arrives, and a queue that no longer fits what came back is dropped rather
 * than replayed — which is the only thing standing between "fast" and "clicking
 * at random".
 */
object TerminalClicks {
	/** Indices into [TerminalSolver.solvingMode]'s options. */
	private const val MANUAL = 0
	private const val QUEUE = 1
	private const val AUTO = 2

	/**
	 * Melody has no windows to pace against — the note keeps moving — so a
	 * second press of the same button is held off for this long regardless.
	 */
	private const val MELODY_COOLDOWN_MILLIS = 250L

	/**
	 * How long a click may go unanswered before it is treated as lost. Long
	 * enough that a bad connection is not mistaken for a dropped packet.
	 */
	private const val LOST_CLICK_TIMEOUT_MILLIS = 800L

	/** How many times one click is worth sending before it is written off. */
	private const val MAX_SENDS = 3

	private data class PendingClick(val slot: Int, val button: Int, var sends: Int = 0)

	private val pending = ArrayDeque<PendingClick>()

	/**
	 * True while a click is on the wire that no window has answered yet.
	 *
	 * A flag and nothing more, which is what NoammAddons has and is the point.
	 * Several attempts here kept the sent clicks themselves and guessed the
	 * board forward over them, so a pane the player had dealt with would not
	 * flicker back while the answer was in transit. Every one of those attempts
	 * eventually counted a click twice: a click that has landed and a click
	 * still travelling look identical from the client, and once the delay was
	 * low enough for two to be out at once there was no telling which was which.
	 *
	 * So the board is guessed forward for exactly what is *queued* — clicks
	 * certainly not sent — and nothing else. Whatever is on the wire is the
	 * server's business, and the board it sends back is the answer. All this
	 * decides is whether a click the player makes goes out at once or gets in
	 * line behind one already gone.
	 */
	private var clickOnWire = false

	/**
	 * What the board wanted of each slot at the last solve.
	 *
	 * The difference between one board and the next is the only honest signal
	 * that a click arrived, so it has to be remembered rather than inferred.
	 */
	private var lastNeeded: Map<Int, Int> = emptyMap()

	/** The earliest the next click may go out, which is what paces all of this. */
	private var nextClickAt = 0L

	/** When the last click went out, for noticing one that never comes back. */
	private var lastSentAt = 0L

	/** Guards the re-entry the simulator causes by answering a click at once. */
	private var draining = false

	private var lastMelodySlot: Int? = null
	private var lastMelodyAt = 0L

	/** The last slot clicked, so automatic clicks travel like a hand would. */
	private var lastClickedSlot: Int? = null

	private val mode: Int get() = TerminalSolver.solvingMode.selectedIndex

	/** How many clicks are waiting to go out, which the solver draws. */
	val queued: Int get() = pending.size

	/** A click the player made on a slot of the custom GUI. */
	fun onPlayerClick(handler: TerminalHandler, slot: Int, requested: Int) {
		// In auto the mod is playing; a stray click would only fight it.
		if (mode == AUTO) return

		val button = handler.buttonFor(slot, requested)
		if (!handler.canClick(slot, button)) {
			TerminalDebug.playerClick(handler, slot, button, "refused: not part of the solution")
			return
		}

		if (mode == MANUAL) {
			if (handler.blocksClicks()) {
				TerminalDebug.playerClick(handler, slot, button, "dropped: click protection")
				TerminalDebug.blocked(handler)
				return
			}
			TerminalDebug.playerClick(handler, slot, button, "sending now")
			send(handler, slot, button)
			return
		}

		// Moved on straight away, so the next click is worked out against where
		// the terminal is going rather than where it still is.
		handler.predict(slot, button)

		// Every click becomes an intent. Nothing is sent from here: the queue is
		// the record of what the player has asked for, and [drain] is the only
		// thing that puts one on the wire, one at a time and never faster than the
		// delay. Sending straight from here is what let a new click overtake a
		// queue that had not drained yet.
		pending.addLast(PendingClick(slot, button))
		TerminalDebug.playerClick(handler, slot, button, "queued behind ${pending.size - 1}")
		drain(handler)
	}

	/**
	 * Hypixel answering a click by opening the terminal again.
	 *
	 * This is what lets go of the click on the wire. A slot update inside the
	 * window already open cannot do that job: the board it describes can still
	 * be the one from before the click.
	 */
	fun onWindowOpened() {
		if (!clickOnWire) return
		clickOnWire = false

	}

	/**
	 * A window the server has finished sending, and with it the first chance to
	 * know whether what is queued still makes sense.
	 */
	fun onSolved(handler: TerminalHandler) {
		// A board coming back is the server's answer now. Hypixel used to reopen
		// the whole chest for that and no longer does, so the window that used to
		// release the click never arrives — and waiting for it left every click
		// after the first looking like it was still on the wire.
		if (clickOnWire) {
			clickOnWire = false
		}

		if (mode != QUEUE) return

		settle(handler)
		drain(handler)
	}

	/**
	 * Reconciles what the player has asked for against the board that just came
	 * back, and paints the difference away.
	 *
	 * This is the whole of the mode. A click the player made is an *intent*, and
	 * an intent outlives the packet that carried it: it is only given up when the
	 * board itself stops asking for that slot. Until then it stays queued, stays
	 * hidden from the player, and goes out again when its turn comes round.
	 *
	 * That is what makes the terminal answer a hand rather than a connection. A
	 * click the server dropped used to reappear as an un-clicked pane and had to
	 * be found and clicked a second time; now the pane stays dealt with on screen
	 * and the queue quietly sends it again.
	 *
	 * The reconciliation is one rule: nobody can have more clicks outstanding on
	 * a slot than the board still asks for. Whatever the server's own count has
	 * dropped by is what arrived, and those intents are done with.
	 */
	private fun settle(handler: TerminalHandler) {
		val serverWanted = handler.solution.size
		if (pending.isEmpty()) {
			TerminalDebug.boards(handler, serverWanted, 0)
			return
		}

		// What the board wants of each slot the player has asked about, read
		// before any prediction goes back on.
		val nowNeeded = pending.map { it.slot }.distinct().associateWith(handler::clicksNeededFor)

		// How much of that requirement has fallen away since the last board is
		// how many of the player's clicks arrived. Anything else is guesswork:
		// asking only "does this slot still want clicks?" cannot settle an intent
		// on a slot that wants several, so on rubix a click that had plainly
		// landed stayed queued and went out again — the terminal sat there being
		// clicked back and forth on the same pane.
		val landed = HashMap<Int, Int>()
		for ((slot, need) in nowNeeded) {
			val before = lastNeeded[slot] ?: need
			landed[slot] = (before - need).coerceAtLeast(0)
		}

		val kept = ArrayList<PendingClick>(pending.size)
		val takenPerSlot = HashMap<Int, Int>()
		for (intent in pending) {
			// Oldest first: the click that landed is the one that was sent.
			val arrived = landed.getOrDefault(intent.slot, 0)
			if (arrived > 0) {
				landed[intent.slot] = arrived - 1
				continue
			}

			// And never more outstanding than the board still asks for, which
			// catches anything the difference alone missed.
			val wanted = nowNeeded[intent.slot] ?: 0
			val taken = takenPerSlot.getOrDefault(intent.slot, 0)
			if (taken >= wanted) continue

			// The board can move under a queued click without finishing its slot
			// — rubix panes change which way round they are quicker to reach — so
			// it is re-aimed rather than dropped.
			val aimed = handler.reaim(intent.slot, intent.button) ?: continue

			// A click the server keeps ignoring is not going to start working.
			// Giving up hands the pane back to the player, who can see it again
			// and click it, which is better than a queue quietly clicking forever.
			if (intent.sends >= MAX_SENDS) {
				TerminalDebug.note("giving up on slot ${intent.slot} after ${intent.sends} sends")
				continue
			}

			kept.add(PendingClick(intent.slot, aimed, intent.sends))
			takenPerSlot[intent.slot] = taken + 1
		}

		lastNeeded = handler.solution.distinct().associateWith(handler::clicksNeededFor)

		val satisfied = pending.size - kept.size
		pending.clear()
		pending.addAll(kept)
		pending.forEach { handler.predict(it.slot, it.button) }

		if (satisfied > 0) TerminalDebug.settled(satisfied, pending.size)
		TerminalDebug.boards(handler, serverWanted, pending.size)
	}

	fun tick() {
		val handler = Terminals.current ?: return
		giveUpOnLostClick()
		when (mode) {
			QUEUE -> drain(handler)
			AUTO -> playAutomatically(handler)
			else -> Unit
		}
	}

	/**
	 * Lets go of a click the server never answered.
	 *
	 * A click nothing ever came back for leaves a prediction standing over a
	 * board that never changed, which is a pane shown as done when it is not.
	 * Letting go of it puts the board back to what the server actually said.
	 * NoammAddons calls the same thing its resync timeout.
	 */
	private fun giveUpOnLostClick() {
		if (!clickOnWire) return
		if (System.currentTimeMillis() - lastSentAt < LOST_CLICK_TIMEOUT_MILLIS) return
		// Only the wire is given up on, never the queue. An intent the server
		// silently dropped is exactly the one worth sending again, and the board
		// is what decides when it has finally landed.
		clickOnWire = false
		TerminalDebug.note("no board came back in ${LOST_CLICK_TIMEOUT_MILLIS}ms; re-sending ${pending.size} queued")
	}

	fun reset() {
		pending.clear()
		// Zero rather than a delay: the first click of a new terminal waits on
		// the protection, and should not then wait on this as well.
		nextClickAt = 0L
		clickOnWire = false
		lastNeeded = emptyMap()
		draining = false
		lastSentAt = System.currentTimeMillis()
		lastMelodySlot = null
		lastMelodyAt = 0L
		lastClickedSlot = null
	}

	/**
	 * Lets the next queued click out, if it is time and it is allowed to.
	 *
	 * One condition, and it is the human one: a gap from
	 * [TerminalSolver.clickDelay] has to have passed since the last click went
	 * out. There is nothing to react to any more — the board answers in place
	 * rather than being sent again — so the gap simply spaces the clicks.
	 */
	private fun drain(handler: TerminalHandler) {
		// The simulator answers a click inside the call that sent it, so this
		// can be re-entered; the queue is left for the next tick instead.
		if (draining) return
		val head = pending.firstOrNull() ?: return
		if (handler.blocksClicks()) {
			TerminalDebug.blocked(handler)
			return
		}
		// One on the wire at a time. Released by the next board, or by the resync
		// timeout when the server never answers — which is when it is sent again.
		if (clickOnWire) return
		if (System.currentTimeMillis() < nextClickAt) return

		// Left in the queue on purpose: an intent is only given up when the board
		// says the slot is done, so a click the server drops is sent again rather
		// than lost.
		head.sends++
		if (head.sends > 1) TerminalDebug.resent(head.slot, head.button, head.sends)

		draining = true
		try {
			send(handler, head.slot, head.button)
		} finally {
			draining = false
		}
	}

	/**
	 * Unlike the queue, the gap here is waited out whatever the server is
	 * doing: there is no player being kept in step with, so the gap is the only
	 * thing deciding how fast this clicks.
	 */
	private fun playAutomatically(handler: TerminalHandler) {
		val now = System.currentTimeMillis()
		if (handler.blocksClicks() || now < nextClickAt) return

		if (handler.type == TerminalType.MELODY) {
			playMelody(handler, now)
			return
		}

		val slot = chooseSlot(handler) ?: return
		// The button has to be read off the board before the board is moved on.
		val button = handler.preferredButton(slot)
		handler.predict(slot, button)
		send(handler, slot, button)
	}

	/**
	 * Melody's button only enters the solution while the note is on the marked
	 * column, so "is it in the solution" and "is now the moment" are the same
	 * question.
	 */
	private fun playMelody(handler: TerminalHandler, now: Long) {
		val slot = handler.solution.firstOrNull { it in MELODY_BUTTONS } ?: return
		if (slot == lastMelodySlot && now - lastMelodyAt < MELODY_COOLDOWN_MILLIS) return

		lastMelodySlot = slot
		lastMelodyAt = now
		send(handler, slot, GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
	}

	/**
	 * The one place a click leaves, so nothing can go out without resetting the
	 * clock the next one waits on.
	 */
	private fun send(handler: TerminalHandler, slot: Int, button: Int) {
		lastClickedSlot = slot
		clickOnWire = true
		lastSentAt = System.currentTimeMillis()
		// Always set, whatever the toggle says: it is the floor that stops a
		// click going out before the last one has had time to matter, and with
		// Reaction delay on the window arriving pushes it further out still.
		nextClickAt = System.currentTimeMillis() + gap()
		TerminalDebug.sent(slot, button)
		handler.click(slot, button)
	}

	/**
	 * Which slot to click next.
	 *
	 * The order terminal has only one answer. For the rest, the nearest
	 * remaining slot to the last one clicked is taken, with ties going to
	 * whichever has the most neighbours still to do — which is roughly what a
	 * hand does, and nothing like the reading order a list would give.
	 * NoammAddons calls this its "Human" click order.
	 */
	private fun chooseSlot(handler: TerminalHandler): Int? {
		if (handler.type == TerminalType.NUMBERS) return handler.solution.firstOrNull()

		val candidates = handler.solution.distinct()
		if (candidates.isEmpty()) return null

		val from = lastClickedSlot ?: (handler.type.windowSize / 2)
		return candidates.shuffled().minWithOrNull(
			compareBy({ distanceSquared(it, from) }, { neighbours(it, candidates) }),
		)
	}

	/** One draw from the click delay range, re-rolled every time it is asked. */
	private fun gap(): Long = TerminalSolver.clickDelay.random().toLong()

	private fun neighbours(slot: Int, others: List<Int>): Int =
		others.count { it != slot && distanceSquared(slot, it) <= NEIGHBOUR_RADIUS_SQUARED }

	/** Slots sit on an even grid, so rows and columns stand in for pixels. */
	private fun distanceSquared(a: Int, b: Int): Int {
		val dx = (a % 9 - b % 9) * SLOT_PITCH
		val dy = (a / 9 - b / 9) * SLOT_PITCH
		return dx * dx + dy * dy
	}

	private val MELODY_BUTTONS = setOf(16, 25, 34, 43)

	private const val SLOT_PITCH = 18
	private const val NEIGHBOUR_RADIUS_SQUARED = 20 * 20
}
