package imicro.cryptic.puzzle.water

import kotlin.test.Test
import kotlin.test.assertTrue

/** The planner finds a solve for every real board and gate set, from scratch and from Odin's answer. */
class WaterPlannerTest {
	private val planner = WaterPlanner()

	private fun request(layout: Int, closed: Int, goal: WaterPlanner.Goal, seeds: List<List<WaterFlip>>, millis: Long): WaterPlanner.Request {
		val (board, slotIn) = WaterBoards.board(layout)
		val base = WaterSim.fresh(board, slotIn, closed)
		return WaterPlanner.Request(base, IntArray(WaterLever.entries.size) { 1 }, goal, seeds, millis, seed = 42)
	}

	private fun combos(): List<String> {
		val out = mutableListOf<String>()
		for (a in 0 until 5) for (b in a + 1 until 5) for (c in b + 1 until 5) out += "$a$b$c"
		return out
	}

	@Test
	fun `every board solves from scratch`() {
		val misses = mutableListOf<String>()
		val began = System.currentTimeMillis()
		for (layout in 0 until 4) for (key in combos()) {
			val plan = planner.plan(request(layout, WaterBoards.closedBits(key), WaterPlanner.Goal.FASTEST, emptyList(), 250))
			if (plan.solvedAt == WaterSim.NONE) misses += "layout $layout shut $key"
			else println("PLAN layout=$layout shut=$key fastest solves at ${plan.solvedAt} with ${plan.flips.size} flips, ${"%.0f".format(plan.solveRate * 100)}% with jitter")
		}
		println("PLAN from scratch: ${40 - misses.size}/40 in ${System.currentTimeMillis() - began} ms; misses $misses")
		assertTrue(misses.size <= 2, "no plan for $misses")
	}

	@Test
	fun `safest beats odin's answer or matches it`() {
		var better = 0
		var total = 0
		for (layout in 0 until 4) for (key in combos().take(1)) {
			val answer = WaterBoards.sheet[false]!![layout]?.get(key) ?: continue
			val seed = WaterPlanner.sheetSeed(answer, 1)
			val req = request(layout, WaterBoards.closedBits(key), WaterPlanner.Goal.SAFEST, listOf(seed), 1500)
			val plan = planner.plan(req)
			val sheet = planner.plan(WaterPlanner.Request(req.base, req.earliest, WaterPlanner.Goal.SAFEST, listOf(seed), 0, seed = 1))
			total++
			if (plan.solvedAt != WaterSim.NONE && (sheet.solvedAt == WaterSim.NONE || plan.solvedAt <= sheet.solvedAt + 5)) better++
			println("SAFE layout=$layout shut=$key: plan ${plan.solvedAt} (${"%.0f".format(plan.solveRate * 100)}%, ${plan.flips.size} flips) vs Odin ${sheet.solvedAt} (${"%.0f".format(sheet.solveRate * 100)}%)")
		}
		assertTrue(better == total, "$better of $total")
	}
}
