package imicro.cryptic.puzzle.water

/**
 * Keeps a [WaterSim] in step with the server's board from what the client is sent, so plans are
 * made from what really happened. Pure: fed ticks, lever flips and block changes.
 *
 * Time is counted in the server's per-tick pings, so a lagging server stops the clock too. Packets
 * are tagged with the tick they arrive in. Each lever flip is played into the simulation at its
 * tick (winding back and playing the ticks since again), and the simulation's changes are checked
 * against the packets a couple of ticks behind:
 * - water arriving early that a random tick explains is given that random tick;
 * - a flip whose water keeps arriving a tick off is moved by that tick (a tick's packets can fall
 *   either side of its ping);
 * - anything else that can't be explained, a few times over, asks for the board to be read again
 *   ([needsRebuild]).
 */
class WaterTracker(start: WaterSim) {
	val board = start.board

	/** The simulation at [now]. */
	val sim: WaterSim = start.copy()

	/** Server ticks counted: the tick packets arriving now are tagged with. */
	var now = start.time
		private set

	/** The tick the simulation was last started or read again from: nothing before it is known. */
	private var first = start.time

	/** Per cell: the tick it was last seen to change, for keeping its rhythm on a re-read. */
	private val lastChange = IntArray(board.size) { WaterSim.NONE }

	/** End-of-tick states, for winding back: tick t at t % KEEP. */
	private val history = Array(KEEP) { start.copy() }

	/** Per tick: the lever flips played at it. */
	private val levers = IntArray(KEEP)

	/** Per tick: random ticks played at it. */
	private val randoms = Array(KEEP) { IntArrayList() }

	/** Per tick: the changes its step made, as (cell, value, tick its packet goes with). */
	private val changes = Array(KEEP) { IntArrayList() }

	/** Per tick: the changes seen in its packets, cell to value. */
	private val seen = Array(KEEP) { HashMap<Int, Int>() }

	/** Whether a cell is checked: board water, not a lever's block (pistons are sent unpredictably). */
	private val checked = BooleanArray(board.size) { board.open[it] && board.slotAt(it) < 0 }.also { it[board.source] = true }

	/** The tick of the last flip, and how its water has been arriving: on time, a tick early, a tick late. */
	private var lastFlipTick = -1
	private var lastFlipLever = -1
	private var onTime = 0
	private var early = 0
	private var late = 0

	/** Ticks with changes that couldn't be explained, lately. */
	private val unexplained = ArrayDeque<Int>()

	/** Set when the simulation and the board disagree past explaining: read the board again. */
	var needsRebuild = false
		private set

	/** The last tick anything changed, predicted or seen: a quiet board can be compared outright. */
	var lastActivity = start.time
		private set

	/** What the tracker did lately, for `/cryptic debug room`. */
	val log = ArrayDeque<String>()

	/** Counts every change to what the simulation was told: a plan made before one may be stale. */
	var version = 0
		private set

	init {
		history[slot(now)].copyFrom(sim)
	}

	/**
	 * Starts again from the board as the client sees it now: [water] a level per cell or -1. Each
	 * cell keeps the rhythm its last change showed.
	 */
	fun resync(water: IntArray, slotIn: BooleanArray, closed: Int, sourceOn: Boolean) {
		val read = WaterSim.observed(board, slotIn, closed, water, sourceOn, now, lastChange)
		read.hold = sim.hold
		sim.copyFrom(read)
		first = now
		for (i in 0 until KEEP) {
			levers[i] = 0
			randoms[i].clear()
			changes[i].clear()
		}
		history[slot(now)].copyFrom(sim)
		unexplained.clear()
		lastFlipTick = -1
		needsRebuild = false
		version++
		note("read the board again")
	}

	private fun slot(tick: Int): Int = Math.floorMod(tick, KEEP)

	private fun note(text: String) {
		log.addLast("t$now $text")
		while (log.size > 30) log.removeFirst()
	}

	/** A server tick: the simulation steps into it, and the tick before last is checked. */
	fun onPing() {
		now++
		val s = slot(now)
		levers[s] = 0
		randoms[s].clear()
		seen[s].clear()
		stepInto(now)
		if (now - 2 > first) check(now - 2)
	}

	/** Steps the simulation (at [tick] - 1) into [tick], recording its changes. */
	private fun stepInto(tick: Int) {
		val s = slot(tick)
		val record = changes[s]
		record.clear()
		sim.onChange = { cell, value, batch ->
			if (checked[cell]) {
				record.add(cell)
				record.add(value)
				record.add(batch)
				lastActivity = maxOf(lastActivity, batch)
			}
		}
		sim.step(levers[s], if (randoms[s].size > 0) randoms[s].toArray() else null)
		sim.onChange = null
		history[s].copyFrom(sim)
	}

	/** Plays the ticks from [from] to [now] again, from the state before [from]. */
	private fun replay(from: Int) {
		val start = maxOf(from, now - KEEP + 2, first + 1)
		sim.copyFrom(history[slot(start - 1)])
		for (tick in start..now) stepInto(tick)
	}

	/** A lever flipped: its packet arrived now. */
	fun onLever(lever: WaterLever) {
		val s = slot(now)
		levers[s] = levers[s] xor lever.bit
		lastFlipTick = now
		lastFlipLever = lever.ordinal
		onTime = 0
		early = 0
		late = 0
		replay(now)
		version++
		note("flip ${lever.name}")
	}

	/** A cell on the board changed to [value] (a water level, [WaterSim.AIR] or [WaterSim.SOLID]). */
	fun onCell(cell: Int, value: Int) {
		if (!checked[cell]) return
		seen[slot(now)][cell] = value
		lastChange[cell] = now
		lastActivity = maxOf(lastActivity, now)
	}

	/** What the simulation sends with [tick]'s packets, cell to value. */
	private fun predicted(tick: Int): Map<Int, Int> {
		val out = HashMap<Int, Int>()
		for (step in intArrayOf(tick - 1, tick)) {
			if (step <= first || step > now || now - step >= KEEP) continue
			val record = changes[slot(step)]
			var i = 0
			while (i < record.size) {
				if (record[i + 2] == tick) out[record[i]] = record[i + 1]
				i += 3
			}
		}
		return out
	}

	private fun seenAt(tick: Int): Map<Int, Int> =
		if (tick <= first || tick > now || now - tick >= KEEP) emptyMap() else seen[slot(tick)]

	/** Checks [tick]'s packets against the simulation, with the ticks either side complete. */
	private fun check(tick: Int) {
		val got = seenAt(tick)
		if (got.isEmpty() && predicted(tick).isEmpty()) return

		// First anything a random tick explains, since that changes what's predicted. Random ticks
		// only ever make water early, so a late change isn't one.
		for ((cell, value) in got) {
			if (predicted(tick)[cell] == value || predicted(tick - 1)[cell] == value) continue
			explainEarly(cell, value, tick)
		}

		val expected = predicted(tick)
		val before = predicted(tick - 1)
		val after = predicted(tick + 1)
		var trouble = 0
		for ((cell, value) in got) {
			when {
				expected[cell] == value -> if (tick > lastFlipTick) onTime++
				before[cell] == value -> if (tick > lastFlipTick) late++
				after[cell] == value -> if (tick > lastFlipTick) early++
				else -> trouble++
			}
		}
		val gotBefore = seenAt(tick - 1)
		val gotAfter = seenAt(tick + 1)
		for ((cell, value) in expected) {
			if (got[cell] == value || gotBefore[cell] == value || gotAfter[cell] == value) continue
			// Already that way: the server sent nothing, since nothing changed.
			if (history[slot(tick - 1)].cells[cell].let { if (it >= 0) it and WaterSim.LEVEL else it } == value) continue
			trouble++
		}

		adjustLastFlip()
		if (trouble > 0) {
			unexplained.addLast(tick)
			note("$trouble unexplained at t$tick")
		}
		while (unexplained.isNotEmpty() && tick - unexplained.first() > TROUBLE_WINDOW) unexplained.removeFirst()
		if (unexplained.size >= TROUBLE_LIMIT && !needsRebuild) {
			needsRebuild = true
			note("rebuild")
		}
	}

	/**
	 * A change at [cell] at [tick] the simulation doesn't have then: a random tick explains it if
	 * playing one in makes it so and leaves fewer packets unexplained. A random tick that changes
	 * nothing at once can still show a few ticks later and a cell or two away, so the search
	 * reaches back [RANDOM_REACH] ticks and two cells around.
	 */
	private fun explainEarly(cell: Int, value: Int, tick: Int): Boolean {
		val from = maxOf(tick - RANDOM_REACH, first + 2, now - KEEP + 3)
		if (from > tick) return false
		// Judged on every packet since, not just this one: a wrong guess often fits one change.
		val until = now - 1
		var best = misses(from, until)
		var bestAt = -1
		var bestCell = -1
		for (at in tick downTo from) {
			val state = history[slot(at - 1)]
			val candidates = ArrayList<Int>()
			for (dy in -RANDOM_RADIUS..RANDOM_RADIUS) for (dx in -RANDOM_RADIUS..RANDOM_RADIUS) {
				if (Math.abs(dx) + Math.abs(dy) > RANDOM_RADIUS) continue
				val near = cell + dy * WaterBoard.WIDTH + dx
				if (near !in 0 until board.size) continue
				val v = state.cells[near]
				if (v >= 0) candidates += near
			}
			for (near in candidates) {
				randoms[slot(at)].add(near)
				replay(at)
				if (predicted(tick)[cell] == value) {
					val score = misses(from, until)
					if (score < best) {
						best = score
						bestAt = at
						bestCell = near
					}
				}
				randoms[slot(at)].removeLast()
			}
		}
		if (bestAt < 0) {
			replay(from)
			return false
		}
		randoms[slot(bestAt)].add(bestCell)
		replay(from)
		version++
		note("random tick at ${WaterBoard.x(bestCell)},${WaterBoard.y(bestCell)} t$bestAt")
		return true
	}

	/** Packets from [from] to [to] the simulation doesn't account for, a tick either way allowed. */
	private fun misses(from: Int, to: Int): Int {
		var count = 0
		for (tick in from..to) {
			val expected = predicted(tick)
			val before = predicted(tick - 1)
			val after = predicted(tick + 1)
			val got = seenAt(tick)
			val gotBefore = seenAt(tick - 1)
			val gotAfter = seenAt(tick + 1)
			// A change off by more than a tick counts far more than one a tick off, which still
			// counts a little: a random tick one tick early is otherwise invisible.
			for ((cell, value) in got) {
				if (expected[cell] == value) continue
				count += if (before[cell] == value || after[cell] == value) 1 else FAR_OFF
			}
			for ((cell, value) in expected) {
				if (got[cell] == value) continue
				count += if (gotBefore[cell] == value || gotAfter[cell] == value) 1 else FAR_OFF
			}
		}
		return count
	}

	/** Moves the last flip a tick when its water keeps arriving a tick off. */
	private fun adjustLastFlip() {
		if (lastFlipTick < 0 || now - lastFlipTick >= KEEP - 2) return
		// A misplaced flip puts all its water off; a random tick only some of it.
		if (onTime > 0) return
		val shift = when {
			late >= SHIFT_EVIDENCE && early == 0 -> 1
			early >= SHIFT_EVIDENCE && late == 0 -> -1
			else -> return
		}
		val from = slot(lastFlipTick)
		val to = slot(lastFlipTick + shift)
		val bit = 1 shl lastFlipLever
		levers[from] = levers[from] xor bit
		levers[to] = levers[to] xor bit
		replay(minOf(lastFlipTick, lastFlipTick + shift))
		version++
		note("moved ${WaterLever.entries[lastFlipLever].name} ${if (shift > 0) "a tick later" else "a tick earlier"}")
		lastFlipTick += shift
		onTime = 0
		early = 0
		late = 0
	}

	companion object {
		/** Ticks of history kept for winding back. */
		const val KEEP = 48
		private const val RANDOM_REACH = 12
		private const val RANDOM_RADIUS = 2
		private const val FAR_OFF = 10
		private const val SHIFT_EVIDENCE = 3
		private const val TROUBLE_WINDOW = 40
		private const val TROUBLE_LIMIT = 4
	}
}

/** A growable list of ints without boxing. */
class IntArrayList {
	private var items = IntArray(16)
	var size = 0
		private set

	fun add(value: Int) {
		if (size == items.size) items = items.copyOf(size * 2)
		items[size++] = value
	}

	fun removeLast() {
		if (size > 0) size--
	}

	fun clear() {
		size = 0
	}

	operator fun get(index: Int): Int = items[index]

	fun toArray(): IntArray = items.copyOf(size)
}
