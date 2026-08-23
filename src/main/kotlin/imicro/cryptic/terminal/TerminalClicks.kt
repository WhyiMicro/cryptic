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

	private data class PendingClick(val slot: Int, val button: Int)

	private val pending = ArrayDeque<PendingClick>()

	/** The earliest the next click may go out, which is what paces all of this. */
	private var nextClickAt = 0L

	/** Clicks sent that the server has yet to answer with a window. */
	private var inFlight = 0

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
		if (!handler.canClick(slot, button)) return

		if (mode == MANUAL) {
			if (handler.blocksClicks()) return
			send(handler, slot, button)
			return
		}

		pending.addLast(PendingClick(slot, button))
		// Moved on straight away, so the next click is worked out against where
		// the terminal is going rather than where it still is.
		handler.predict(slot, button)
		// The first click of a terminal has nothing ahead of it and should not
		// feel delayed, so the queue is given a chance to drain immediately.
		drain(handler)
	}

	/**
	 * A window the server has finished sending, and with it the first chance to
	 * know whether what is queued still makes sense.
	 */
	fun onSolved(handler: TerminalHandler) {
		// One window comes back per click the server got through, so this is
		// how far ahead of it the player still is.
		val answeredAClick = inFlight > 0
		if (answeredAClick) inFlight--

		// With **Reaction delay** on, the gap is counted from here instead of
		// from the send, which is what puts a reaction time between the server
		// showing a new board and the next click answering it. Only a window
		// that answered a click of ours starts one: the window a terminal opens
		// with was already reacted to, by the click that opened it.
		if (answeredAClick && TerminalSolver.reactionDelay.value) {
			nextClickAt = System.currentTimeMillis() + gap()
		}

		if (mode != QUEUE) return
		val head = pending.firstOrNull() ?: return

		// The fresh solution is the truth. If the queue no longer fits it, the
		// player and the server have diverged and replaying stale clicks would
		// only make that worse.
		if (!handler.canClick(head.slot, head.button)) {
			pending.clear()
			return
		}
		// Solving threw the predictions away, so they go back on.
		pending.forEach { handler.predict(it.slot, it.button) }

		// Straight away rather than on the next tick: the queue is already
		// paying a round trip per click, and making it wait up to another 50ms
		// for the tick to come round would be latency for its own sake. The gap
		// in [drain] still applies, so this cannot answer a window instantly.
		drain(handler)
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
	 * Nothing may go out while a click is outstanding, so one that is dropped —
	 * refused, or answered by a window that never arrives — would otherwise
	 * wedge the queue shut for the rest of the terminal. What is behind it is
	 * thrown away rather than sent, because by then nobody knows what the board
	 * looks like, and the next real window will say. NoammAddons calls the same
	 * thing its resync timeout.
	 */
	private fun giveUpOnLostClick() {
		if (inFlight == 0) return
		if (System.currentTimeMillis() - lastSentAt < LOST_CLICK_TIMEOUT_MILLIS) return
		inFlight = 0
		pending.clear()
	}

	fun reset() {
		pending.clear()
		// Zero rather than a delay: the first click of a new terminal waits on
		// the protection, and should not then wait on this as well.
		nextClickAt = 0L
		inFlight = 0
		draining = false
		lastSentAt = System.currentTimeMillis()
		lastMelodySlot = null
		lastMelodyAt = 0L
		lastClickedSlot = null
	}

	/**
	 * Lets the next queued click out, if it is time and it is allowed to.
	 *
	 * Two conditions, and both have to hold.
	 *
	 * **Nothing outstanding.** The server has to have answered the last click
	 * before the next one goes out, so there is never more than one click on
	 * the wire. Sending a second against a window the server has already
	 * replaced is the part of queueing that could not be mistaken for a person,
	 * and it is also the part that may simply be discarded — a click carries
	 * the id of the window it was made in, and that window is gone.
	 *
	 * **Not faster than a hand.** A window coming back is not licence to answer
	 * it instantly, so a gap from [TerminalSolver.clickDelay] has to have passed
	 * as well. Where that gap is counted from is
	 * [TerminalSolver.reactionDelay]'s to say, and it is the whole difference
	 * between the two timings:
	 *
	 * - On, it runs from the window arriving, so each click is spaced by the
	 *   round trip *plus* a reaction — which is what a hand produces, and the
	 *   only setting under which the gap reliably does anything.
	 * - Off, it runs from the last send, so it overlaps the round trip rather
	 *   than adding to it. On a slow connection the gap has usually elapsed
	 *   before the answer even arrives, leaving the round trip alone to space
	 *   the clicks; on a fast one the gap becomes the floor.
	 */
	private fun drain(handler: TerminalHandler) {
		// The simulator answers a click inside the call that sent it, so this
		// can be re-entered; the queue is left for the next tick instead.
		if (draining) return
		val head = pending.firstOrNull() ?: return
		if (handler.blocksClicks()) return
		if (inFlight > 0) return
		if (System.currentTimeMillis() < nextClickAt) return

		pending.removeFirst()
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
		inFlight++
		lastSentAt = System.currentTimeMillis()
		// Always set, whatever the toggle says: it is the floor that stops a
		// click going out before the last one has had time to matter, and with
		// Reaction delay on the window arriving pushes it further out still.
		nextClickAt = System.currentTimeMillis() + gap()
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
