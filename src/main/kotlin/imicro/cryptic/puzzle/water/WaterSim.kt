package imicro.cryptic.puzzle.water

import java.util.Random
import kotlin.math.ln

/**
 * The Water Board's water as Hypixel's 1.8 server moves it, a server tick per [step]: flowing water
 * with its scheduled ticks every five, still water woken by a change beside it, levers moving
 * their blocks, and outlets toggling their gates.
 *
 * Within a tick, in the server's order (facts measured by ADM against Hypixel's packets):
 * 1. the water lever, which the server handles before the tick, so its source's first flow is
 *    due four ticks later, not five;
 * 2. the scheduled water ticks due now, all taken off the list before any runs;
 * 3. random ticks: flowing water gets them now and then, which only ever moves it sooner;
 * 4. outlets: water newly in a pit toggles that colour's gate;
 * 5. the tick's block packets go out;
 * 6. the other levers' pistons: a pulled block leaves at once, a pushed one destroys any water
 *    in its cell and lands [LAND] ticks later.
 *
 * One way Hypixel differs from vanilla 1.8: water that dries up leaves nothing behind it.
 */
class WaterSim private constructor(val board: WaterBoard) {
	/** Per cell: [AIR], [SOLID], or a water level 0..15 (8 and up falling) with [FLOWING] while it has a tick to come. */
	val cells = IntArray(board.size)

	/** Per cell: the tick its scheduled update is due, or [NONE]. One each, like the server's. */
	private val due = IntArray(board.size) { NONE }

	// The scheduled updates, oldest first. Every delay is the same, so they're also in due order.
	private var queue = IntArray(QUEUE)
	private var queueDue = IntArray(QUEUE)
	private var head = 0
	private var count = 0
	private val running = IntArray(board.size)

	/** Per slot: its block is in the board. */
	val slotIn = BooleanArray(board.slotCell.size)

	/** Per slot: the tick a pushed block lands, or [NONE]. */
	private val landsAt = IntArray(board.slotCell.size) { NONE }

	var time = 0
		private set

	var sourceOn = false
		private set

	/** Gates closed, a bit each in outlet order. Zero: every gate open. */
	var closed = 0

	/** Outlets with water in them. */
	var wet = 0
		private set

	/** Ticks every gate has to stay open together for the chest to count it. */
	var hold = HOLD

	/** The tick every gate last opened together, or [NONE] while one is closed. */
	var openSince = NONE
		private set

	/** The first tick of the first run of open gates that lasted [hold] ticks, or [NONE]. */
	var solvedAt = NONE
		private set

	/** Times water reached an outlet whose gate was already open, closing it again. */
	var wrongHits = 0
		private set

	/** Called with each change the client can see (cell, what it shows, the tick its packet goes out with). */
	var onChange: ((Int, Int, Int) -> Unit)? = null

	/** Monte Carlo: random ticks drawn from here, each flowing cell at [randomRate] a tick. */
	var random: Random? = null
	var randomRate = RANDOM_RATE

	/** Whether the tick's packets have gone out: changes after it go with the next tick's. */
	private var afterSend = false

	private var sources = 0

	/** Nothing can change any more without a lever: no update waiting, no piston moving, the source off. */
	val settled: Boolean
		get() = !sourceOn && count == 0 && landsAt.all { it == NONE }

	/**
	 * No water will move until a lever is flipped (pistons may still be landing): a plan made now
	 * only has to be timed from its first flip.
	 */
	val quiet: Boolean
		get() = !sourceOn && count == 0

	/** What the client shows at [cell]: a water level, [AIR] or [SOLID]. */
	fun shown(cell: Int): Int = visible(cells[cell])

	fun copy(): WaterSim = WaterSim(board).also { it.copyFrom(this) }

	/** Makes this a copy of [other], a state of the same board, without allocating. */
	fun copyFrom(other: WaterSim) {
		require(other.board === board)
		other.cells.copyInto(cells)
		other.due.copyInto(due)
		if (queue.size != other.queue.size) {
			queue = IntArray(other.queue.size)
			queueDue = IntArray(other.queue.size)
		}
		other.queue.copyInto(queue)
		other.queueDue.copyInto(queueDue)
		head = other.head
		count = other.count
		other.slotIn.copyInto(slotIn)
		other.landsAt.copyInto(landsAt)
		time = other.time
		sourceOn = other.sourceOn
		closed = other.closed
		wet = other.wet
		hold = other.hold
		openSince = other.openSince
		solvedAt = other.solvedAt
		wrongHits = other.wrongHits
	}

	/**
	 * One server tick. [levers]: a [WaterLever.bit] for each lever whose flip the server handles
	 * this tick. [randomTicks]: cells to random-tick, as seen in the packets.
	 */
	fun step(levers: Int = 0, randomTicks: IntArray? = null) {
		time++
		if (levers and WaterLever.WATER_BIT != 0) {
			// Handled after the last tick's packets went out: its own goes with this tick's, and its
			// flow is due a tick early.
			afterSend = true
			setSource(!sourceOn)
		}
		afterSend = false
		runDue()
		randomTicks?.forEach(::forcedTick)
		random?.let(::randomTicks)
		checkOutlets()

		afterSend = true
		if (levers and WaterLever.WATER_BIT.inv() != 0) {
			for (slot in board.slotCell.indices) if (levers and (1 shl board.slotLever[slot]) != 0) moveSlot(slot)
		}
		for (slot in landsAt.indices) {
			if (landsAt[slot] == time) {
				landsAt[slot] = NONE
				place(board.slotCell[slot], SOLID)
			}
		}

		if (closed != 0) {
			openSince = NONE
		} else {
			if (openSince == NONE) openSince = time
			if (solvedAt == NONE && time - openSince + 1 >= hold) solvedAt = openSince
		}
	}

	// ---- The world -------------------------------------------------------------------------------

	private fun visible(value: Int): Int = if (value >= 0) value and LEVEL else value

	private fun write(cell: Int, value: Int) {
		val before = cells[cell]
		cells[cell] = value
		val listener = onChange ?: return
		if (visible(before) != visible(value)) listener(cell, visible(value), if (afterSend) time + 1 else time)
	}

	/** A block set with neighbour updates: new flowing water schedules itself, still water beside it wakes. */
	private fun place(cell: Int, value: Int) {
		val wasFlowing = isFlowing(cells[cell])
		write(cell, value)
		if (isFlowing(value) && !wasFlowing) schedule(cell, time)
		wakeNeighbours(cell)
	}

	private fun isFlowing(value: Int): Boolean = value >= 0 && value and FLOWING != 0

	private fun schedule(cell: Int, now: Int) {
		if (due[cell] != NONE) return
		val at = now + RATE
		due[cell] = at
		if (count == queue.size) grow()
		val index = (head + count) and (queue.size - 1)
		queue[index] = cell
		queueDue[index] = at
		count++
	}

	private fun grow() {
		val bigger = IntArray(queue.size * 2)
		val biggerDue = IntArray(queue.size * 2)
		for (i in 0 until count) {
			bigger[i] = queue[(head + i) and (queue.size - 1)]
			biggerDue[i] = queueDue[(head + i) and (queue.size - 1)]
		}
		queue = bigger
		queueDue = biggerDue
		head = 0
	}

	/** Still water beside [cell] starts flowing again. */
	private fun wakeNeighbours(cell: Int) {
		for (step in board.wake) {
			val next = cell + step
			val value = cells[next]
			if (value >= 0 && value and FLOWING == 0) {
				write(next, value or FLOWING)
				schedule(next, time)
			}
		}
	}

	private fun setSource(on: Boolean) {
		sourceOn = on
		// Handled before the tick, so it counts from the tick before.
		time--
		place(board.source, if (on) FLOWING else SOLID)
		time++
	}

	private fun moveSlot(slot: Int) {
		val cell = board.slotCell[slot]
		// Flipped again before it landed: it lands first.
		if (landsAt[slot] != NONE) {
			landsAt[slot] = NONE
			place(cell, SOLID)
		}
		if (slotIn[slot]) {
			place(cell, AIR)
		} else {
			if (cells[cell] >= 0) place(cell, AIR)
			// The moving piston isn't sent and wakes nothing until the block lands.
			cells[cell] = SOLID
			landsAt[slot] = time + LAND
		}
		slotIn[slot] = !slotIn[slot]
	}

	private fun checkOutlets() {
		var now = 0
		for (outlet in board.outletCell.indices) if (cells[board.outletCell[outlet]] >= 0) now = now or (1 shl outlet)
		val arrived = now and wet.inv()
		wet = now
		if (arrived == 0) return
		for (outlet in board.outletCell.indices) {
			val bit = 1 shl outlet
			if (arrived and bit == 0) continue
			if (closed and bit == 0) wrongHits++
			closed = closed xor bit
		}
	}

	private fun runDue() {
		// Everything due leaves the list before any of it runs, as on the server.
		var ready = 0
		while (count > 0 && queueDue[head] <= time) {
			val cell = queue[head]
			if (due[cell] == queueDue[head]) {
				due[cell] = NONE
				running[ready++] = cell
			}
			head = (head + 1) and (queue.size - 1)
			count--
		}
		for (i in 0 until ready) {
			val cell = running[i]
			if (isFlowing(cells[cell])) update(cell)
		}
	}

	/** A random tick: for flowing water, a whole update early. */
	fun randomTick(cell: Int) {
		if (isFlowing(cells[cell])) update(cell)
	}

	/**
	 * A random tick seen in the packets. The server's water there was flowing, even if an unseen
	 * random tick earlier left this copy thinking it still, so it's updated either way.
	 */
	private fun forcedTick(cell: Int) {
		if (cells[cell] >= 0) update(cell)
	}

	private fun randomTicks(random: Random) {
		if (randomRate <= 0.0) return
		// Gaps between hit cells are geometric, so only the hits cost anything.
		val scale = 1.0 / ln(1.0 - randomRate)
		var cell = (ln(1.0 - random.nextDouble()) * scale).toInt()
		while (cell < cells.size) {
			randomTick(cell)
			cell += 1 + (ln(1.0 - random.nextDouble()) * scale).toInt()
		}
	}

	private fun levelOf(cell: Int): Int = cells[cell].let { if (it >= 0) it and LEVEL else -1 }

	/** BlockDynamicLiquid's update: the level it should have now, then where it runs. */
	private fun update(cell: Int) {
		var level = cells[cell] and LEVEL
		if (level > 0) {
			var lowest = -100
			sources = 0
			for (step in board.horizontal) lowest = lowerOf(cell + step, lowest)
			var next = lowest + 1
			if (next >= 8 || lowest < 0) next = -1
			val above = levelOf(cell + WaterBoard.WIDTH)
			if (above >= 0) next = if (above >= 8) above else above + 8
			if (sources >= 2) {
				val below = cell - WaterBoard.WIDTH
				if (cells[below] == SOLID || levelOf(below) == 0) next = 0
			}
			if (next == level) {
				write(cell, level)
			} else {
				level = next
				if (next < 0) {
					place(cell, AIR)
					return
				}
				write(cell, next or FLOWING)
				schedule(cell, time)
				wakeNeighbours(cell)
			}
		} else {
			write(cell, level)
		}

		val below = cell - WaterBoard.WIDTH
		if (cells[below] == AIR) {
			flowInto(below, if (level >= 8) level else level + 8)
		} else if (level == 0 || cells[below] == SOLID) {
			val sides = sidesToFlow(cell)
			val sideLevel = if (level >= 8) 1 else level + 1
			if (sideLevel >= 8) return
			for (step in board.spread) if (sides and sideBit(step) != 0) flowInto(cell + step, sideLevel)
		}
	}

	private fun lowerOf(cell: Int, current: Int): Int {
		var level = levelOf(cell)
		if (level < 0) return current
		if (level == 0) sources++
		if (level >= 8) level = 0
		return if (current >= 0 && level >= current) current else level
	}

	private fun flowInto(cell: Int, level: Int) {
		if (cells[cell] == AIR) place(cell, level or FLOWING)
	}

	private fun sideBit(step: Int): Int = if (step > 0) 1 else 2

	/** Which sides water runs to: those nearest a way down within four blocks, or every open side if none is. */
	private fun sidesToFlow(cell: Int): Int {
		var best = 1000
		var sides = 0
		for (step in board.horizontal) {
			val next = cell + step
			if (!passable(next)) continue
			val cost = if (cells[next - WaterBoard.WIDTH] == SOLID) distanceToDrop(next, 1, -step) else 0
			if (cost < best) sides = 0
			if (cost <= best) {
				sides = sides or sideBit(step)
				best = cost
			}
		}
		return sides
	}

	private fun distanceToDrop(cell: Int, distance: Int, cameFrom: Int): Int {
		var best = 1000
		for (step in board.horizontal) {
			if (step == cameFrom) continue
			val next = cell + step
			if (!passable(next)) continue
			if (cells[next - WaterBoard.WIDTH] != SOLID) return distance
			if (distance < SLOPE_REACH) {
				val further = distanceToDrop(next, distance + 1, -step)
				if (further < best) best = further
			}
		}
		return best
	}

	/** Not a wall, and not source water. */
	private fun passable(cell: Int): Boolean {
		val value = cells[cell]
		return value != SOLID && !(value >= 0 && value and LEVEL == 0)
	}

	companion object {
		const val AIR = -1
		const val SOLID = -2
		const val FLOWING = 16
		const val LEVEL = 15
		const val NONE = Int.MIN_VALUE

		/** Water's tick rate. */
		const val RATE = 5

		/** A pushed block lands this many ticks after its piston moves. */
		const val LAND = 2

		/** How far water looks for a way down before spreading both ways. */
		private const val SLOPE_REACH = 4

		/** Random ticks Hypixel gives flowing water: about one in 350 a tick, measured by ADM. */
		const val RANDOM_RATE = 1.0 / 350

		/** Ticks the gates have to stay open together. */
		const val HOLD = 10

		private const val QUEUE = 1024

		/** A board at rest: the open cells empty, the slots per [slotIn], the gates per [closed]. */
		fun fresh(board: WaterBoard, slotIn: BooleanArray, closed: Int): WaterSim {
			val sim = WaterSim(board)
			for (cell in 0 until board.size) sim.cells[cell] = if (board.open[cell]) AIR else SOLID
			for (slot in board.slotCell.indices) sim.cells[board.slotCell[slot]] = if (slotIn[slot]) SOLID else AIR
			sim.cells[board.source] = SOLID
			slotIn.copyInto(sim.slotIn)
			sim.closed = closed
			return sim
		}

		/**
		 * A board as seen mid-flow, [water] a level per cell or -1. Which water is still and when
		 * its next update falls can't be seen, so all of it is taken as flowing and due in a full
		 * tick rate; anything wrong for its neighbours puts itself right from there.
		 */
		fun observed(
			board: WaterBoard,
			slotIn: BooleanArray,
			closed: Int,
			water: IntArray,
			sourceOn: Boolean,
			time: Int,
			lastChanged: IntArray? = null,
		): WaterSim {
			val sim = fresh(board, slotIn, closed)
			sim.time = time
			for (cell in water.indices) if (water[cell] >= 0 && sim.cells[cell] == AIR) sim.cells[cell] = water[cell]
			if (sourceOn) {
				sim.cells[board.source] = 0
				sim.sourceOn = true
			}
			for (outlet in board.outletCell.indices) if (sim.cells[board.outletCell[outlet]] >= 0) sim.wet = sim.wet or (1 shl outlet)
			// A cell that changed lately keeps its rhythm: its next update falls a whole number of
			// tick rates after that change. The rest are due a full rate from now.
			val wake = ArrayList<Long>()
			for (cell in sim.cells.indices) {
				if (sim.cells[cell] < 0) continue
				sim.cells[cell] = sim.cells[cell] or FLOWING
				val changed = lastChanged?.get(cell) ?: NONE
				val at = if (changed != NONE && time - changed < RHYTHM_MEMORY) time + 1 + Math.floorMod(changed - time - 1, RATE) else time + RATE
				wake += (at.toLong() shl 32) or cell.toLong()
			}
			wake.sort()
			for (entry in wake) sim.schedule((entry and 0xffffffffL).toInt(), (entry shr 32).toInt() - RATE)
			return sim
		}

		/** How long after a cell's last change its update rhythm is still trusted. */
		private const val RHYTHM_MEMORY = 60
	}
}
