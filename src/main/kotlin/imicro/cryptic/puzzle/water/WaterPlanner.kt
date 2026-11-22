package imicro.cryptic.puzzle.water

import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/** One lever flip, at the server tick its click should be handled on. */
data class WaterFlip(val tick: Int, val lever: WaterLever)

/**
 * A plan: the flips in tick order, the tick the gates open with them on the board as simulated
 * ([WaterSim.NONE] if they don't), and how often they still did with the server's random ticks
 * and clicks a tick or two off.
 */
class WaterPlan(val flips: List<WaterFlip>, val solvedAt: Int, val solveRate: Double, val cost: Double) {
	fun shifted(by: Int) = WaterPlan(flips.map { it.copy(tick = it.tick + by) }, if (solvedAt == WaterSim.NONE) solvedAt else solvedAt + by, solveRate, cost)
	fun without(flip: WaterFlip) = WaterPlan(flips - flip, solvedAt, solveRate, cost)
}

/**
 * Finds lever flips that open every gate from any board: fresh, half played, or with water already
 * running. A random search over timed flip lists (simulated annealing), each candidate played out
 * on [WaterSim]. Flips are spaced so a person can walk between the levers and click them.
 */
class WaterPlanner(private val spacing: Spacing = Spacing()) {
	enum class Goal(val label: String, val clickCost: Double, val trials: Boolean) {
		SAFEST("Safest", 4.0, true),
		FASTEST("Fastest", 3.0, false),
		FEWEST("Fewest clicks", 30.0, false),
	}

	/**
	 * Ticks a person needs between two flips: walking from within reach of one lever to within
	 * reach of the next at a sprint, plus a click. Room coordinates.
	 */
	class Spacing(val minGap: Int = 4, val reach: Double = 4.0, val blocksPerTick: Double = 0.28) {
		private val table = Array(WaterLever.entries.size) { a -> IntArray(WaterLever.entries.size) { b -> gap(WaterLever.entries[a], WaterLever.entries[b]) } }

		private fun gap(a: WaterLever, b: WaterLever): Int {
			val walk = max(0.0, hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble()) - 2 * reach)
			return minGap + ceil(walk / blocksPerTick).toInt()
		}

		fun between(a: WaterLever, b: WaterLever): Int = table[a.ordinal][b.ordinal]

		/** Ticks to walk from ([x], [z]) to within reach of [lever]. */
		fun from(x: Double, z: Double, lever: WaterLever): Int =
			ceil(max(0.0, hypot(lever.x + 0.5 - x, lever.z + 0.5 - z) - reach) / blocksPerTick).toInt()
	}

	/** What the search is asked: the board, the soonest each lever can be flipped, and how. */
	class Request(
		val base: WaterSim,
		/** Per lever, the first tick it can be flipped (reaction time and walking). */
		val earliest: IntArray,
		val goal: Goal,
		val seeds: List<List<WaterFlip>>,
		val budgetMillis: Long,
		val horizon: Int = HORIZON,
		val seed: Long = System.nanoTime(),
	)

	/** Plans [request] on [threads] searches at once and returns the best. */
	fun plan(request: Request, threads: Int = THREADS, cancelled: () -> Boolean = { false }): WaterPlan {
		val levers = WaterLever.entries.filter { lever ->
			lever == WaterLever.WATER || request.base.board.slotLever.any { it == lever.ordinal }
		}
		val tasks = (0 until threads).map { index ->
			Callable { Search(request, levers, Random(request.seed + index * 7919L), cancelled).run() }
		}
		val found = if (threads <= 1) tasks.map { it.call() } else pool.invokeAll(tasks).map { it.get() }
		val judge = Evaluator(request, if (request.goal.trials) CHECK_TRIALS else 0, CHECK_SEED)
		val best = found.flatten().distinct().minByOrNull { judge.cost(it) } ?: emptyList()
		return judge.plan(polish(Evaluator(request, if (request.goal.trials) SEARCH_TRIALS else 0, SEARCH_SEED), best, cancelled))
	}

	/** Drops every flip, and every flip-and-back pair, that the plan does as well without. */
	private fun polish(eval: Evaluator, start: List<WaterFlip>, cancelled: () -> Boolean): List<WaterFlip> {
		var flips = start
		var cost = eval.cost(flips)
		var changed = true
		while (changed && !cancelled()) {
			changed = false
			for (i in flips.indices.reversed()) {
				if (i >= flips.size) continue
				val single = flips.filterIndexed { index, _ -> index != i }
				val pairWith = flips.indexOfFirst { it.lever == flips[i].lever && it.tick > flips[i].tick }
				val pair = if (pairWith >= 0) flips.filterIndexed { index, _ -> index != i && index != pairWith } else null
				for (candidate in listOfNotNull(single, pair)) {
					val c = eval.cost(candidate)
					if (c <= cost) {
						flips = candidate
						cost = c
						changed = true
						break
					}
				}
			}
		}
		return flips
	}

	/** Scores a flip list: deterministic, or averaged over trials with random ticks and jitter. */
	private inner class Evaluator(private val request: Request, private val trials: Int, private val trialSeed: Long) {
		private val scratch = request.base.copy()
		private val start = request.base.time
		private val fail = request.horizon + FAIL_EXTRA
		private val jitterRandom = Random()
		private val tickRandom = Random()
		private val bits = IntArray(request.horizon + 2)

		/** Ticks from the start to the solve with [flips], or [fail]. */
		fun solveTicks(flips: List<WaterFlip>, trial: Int = -1, bound: Int = fail): Int {
			scratch.copyFrom(request.base)
			val end = start + request.horizon
			java.util.Arrays.fill(bits, 0)
			var last = start
			if (trial >= 0) {
				jitterRandom.setSeed(trialSeed + trial * 104729L)
				tickRandom.setSeed(trialSeed + trial * 1299709L)
				scratch.random = tickRandom
			} else {
				scratch.random = null
			}
			for (flip in flips) {
				var tick = flip.tick
				if (trial >= 0) tick += jitterRandom.nextInt(2 * JITTER + 1) - JITTER
				tick = tick.coerceIn(start + 1, end)
				bits[tick - start] = bits[tick - start] or flip.lever.bit
				last = max(last, tick)
			}
			val stopAt = minOf(end, start + bound)
			while (scratch.time < stopAt) {
				scratch.step(bits[scratch.time + 1 - start])
				if (scratch.solvedAt != WaterSim.NONE) return scratch.solvedAt - start
				// Nothing left to happen: it won't solve.
				if (scratch.time >= last && scratch.settled) return fail
			}
			return if (scratch.solvedAt != WaterSim.NONE) scratch.solvedAt - start else fail
		}

		fun cost(flips: List<WaterFlip>): Double {
			val clicks = flips.size * request.goal.clickCost
			if (!request.goal.trials || trials == 0) return solveTicks(flips) + clicks + crowding(flips)
			val exact = solveTicks(flips)
			if (exact >= fail) return fail * 2.0 + clicks
			var total = 0.0
			for (trial in 0 until trials) total += solveTicks(flips, trial, bound = exact + TRIAL_SLACK).let { if (it >= exact + TRIAL_SLACK) fail.toDouble() else it.toDouble() }
			return total / trials + clicks
		}

		/** For Fewest clicks: flips with little room between them cost a little. */
		private fun crowding(flips: List<WaterFlip>): Double {
			if (request.goal != Goal.FEWEST) return 0.0
			var cost = 0.0
			for (i in 1 until flips.size) {
				val room = flips[i].tick - flips[i - 1].tick - spacing.between(flips[i - 1].lever, flips[i].lever)
				if (room < EASY_ROOM) cost += (EASY_ROOM - room) * 0.5
			}
			return cost
		}

		fun plan(flips: List<WaterFlip>): WaterPlan {
			val exact = solveTicks(flips)
			val solvedAt = if (exact >= fail) WaterSim.NONE else start + exact
			var solved = 0
			if (solvedAt != WaterSim.NONE) for (trial in 0 until RATE_TRIALS) if (solveTicks(flips, trial) < fail) solved++
			return WaterPlan(flips, solvedAt, if (solvedAt == WaterSim.NONE) 0.0 else solved.toDouble() / RATE_TRIALS, cost(flips))
		}
	}

	private inner class Search(
		private val request: Request,
		private val levers: List<WaterLever>,
		private val random: Random,
		private val cancelled: () -> Boolean,
	) {
		private val eval = Evaluator(request, if (request.goal.trials) SEARCH_TRIALS else 0, SEARCH_SEED)
		private val start = request.base.time + 1

		fun run(): List<List<WaterFlip>> {
			val began = System.nanoTime()
			val budget = request.budgetMillis * 1_000_000
			val kept = java.util.TreeMap<Double, List<WaterFlip>>()
			fun keep(flips: List<WaterFlip>, cost: Double) {
				if (kept.size >= KEEP && cost >= kept.lastKey()) return
				if (kept.containsValue(flips)) return
				kept[cost] = flips
				while (kept.size > KEEP) kept.pollLastEntry()
			}

			var current: List<WaterFlip> = emptyList()
			var currentCost = Double.MAX_VALUE
			for (seed in request.seeds.map(::repair) + listOf(emptyList())) {
				val cost = eval.cost(seed)
				keep(seed, cost)
				if (cost < currentCost) {
					current = seed
					currentCost = cost
				}
			}
			var best = current
			var bestCost = currentCost
			var steps = 0
			while (!cancelled()) {
				val elapsed = System.nanoTime() - began
				if (elapsed >= budget) break
				steps++
				val temperature = HOT * Math.pow(COLD / HOT, elapsed.toDouble() / budget)
				val candidate = repair(mutate(current))
				val cost = eval.cost(candidate)
				if (cost < currentCost || random.nextDouble() < exp((currentCost - cost) / temperature)) {
					current = candidate
					currentCost = cost
					keep(candidate, cost)
					if (cost < bestCost) {
						best = candidate
						bestCost = cost
					}
				}
				// Wandering far from the best rarely pays on this board.
				if (steps % 300 == 0 && currentCost > bestCost + 25) {
					current = best
					currentCost = bestCost
				}
			}
			return kept.values.toList()
		}

		private fun randomTick(): Int = start + random.nextInt(SPAN)

		private fun mutate(flips: List<WaterFlip>): List<WaterFlip> {
			val list = flips.toMutableList()
			when (if (list.isEmpty()) 2 else random.nextInt(7)) {
				0, 1 -> {
					val i = random.nextInt(list.size)
					val shift = (random.nextInt(12) + 1) * (if (random.nextBoolean()) 1 else -1)
					list[i] = list[i].copy(tick = list[i].tick + shift)
				}
				2 -> list += WaterFlip(randomTick(), levers[random.nextInt(levers.size)])
				3 -> list.removeAt(random.nextInt(list.size))
				4 -> {
					val i = random.nextInt(list.size)
					list[i] = list[i].copy(lever = levers[random.nextInt(levers.size)])
				}
				5 -> {
					// A lever flipped and flipped back: a block in for a while, or out.
					val lever = levers[random.nextInt(levers.size)]
					val tick = randomTick()
					list += WaterFlip(tick, lever)
					list += WaterFlip(tick + spacing.minGap + random.nextInt(60), lever)
				}
				else -> {
					val i = random.nextInt(list.size)
					val shift = (random.nextInt(60) + 1) * (if (random.nextBoolean()) 1 else -1)
					list[i] = list[i].copy(tick = list[i].tick + shift)
				}
			}
			return list
		}

		/** Sorts the flips and pushes each late enough to be reachable and clickable after the last. */
		fun repair(flips: List<WaterFlip>): List<WaterFlip> {
			val sorted = flips.sortedWith(compareBy({ it.tick }, { it.lever.ordinal }))
			val out = ArrayList<WaterFlip>(sorted.size)
			for (flip in sorted) {
				var tick = max(flip.tick, max(start, request.earliest[flip.lever.ordinal]))
				out.lastOrNull()?.let { tick = max(tick, it.tick + spacing.between(it.lever, flip.lever)) }
				if (tick > start + request.horizon) break
				out += if (tick == flip.tick) flip else flip.copy(tick = tick)
			}
			return out
		}
	}

	companion object {
		/**
		 * One of Odin's answers (seconds after the water, zero meaning before it) as flips from
		 * [start]: the before-water pulls spaced to be walkable, the water after them, the rest
		 * timed from the water.
		 */
		fun sheetSeed(answer: Map<String, List<Double>>, start: Int, spacing: Spacing = Spacing()): List<WaterFlip> {
			val flips = mutableListOf<WaterFlip>()
			var tick = start
			var last: WaterLever? = null
			for (lever in WaterLever.entries) {
				if (lever == WaterLever.WATER) continue
				repeat(answer[lever.key].orEmpty().count { it == 0.0 }) {
					last?.let { tick += spacing.between(it, lever) }
					flips += WaterFlip(tick, lever)
					last = lever
				}
			}
			last?.let { tick += spacing.between(it, WaterLever.WATER) }
			val water = tick
			flips += WaterFlip(water, WaterLever.WATER)
			for (lever in WaterLever.entries) {
				if (lever == WaterLever.WATER) continue
				answer[lever.key].orEmpty().filter { it > 0.0 }.forEach { flips += WaterFlip(water + Math.round(it * 20).toInt(), lever) }
			}
			return flips.sortedBy { it.tick }
		}

		/** Ticks simulated at most: a board solves well inside 40 seconds. */
		const val HORIZON = 800
		private const val FAIL_EXTRA = 400

		/** Clicks land up to this many ticks either side of their tick. */
		private const val JITTER = 2

		/** A trial more than this behind the exact solve counts as failed. */
		private const val TRIAL_SLACK = 60

		private const val SEARCH_TRIALS = 6
		private const val CHECK_TRIALS = 30
		private const val RATE_TRIALS = 60
		private const val SEARCH_SEED = 0x5eedL
		private const val CHECK_SEED = 0xc4ec4L

		/** How far ahead a new flip is put. */
		private const val SPAN = 400

		private const val HOT = 60.0
		private const val COLD = 0.5
		private const val KEEP = 12
		private const val EASY_ROOM = 10

		private val THREADS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 3)

		private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
			Thread(runnable, "Cryptic water planner").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
		}
	}
}
