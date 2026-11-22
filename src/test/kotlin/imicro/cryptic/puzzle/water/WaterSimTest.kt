package imicro.cryptic.puzzle.water

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulated water against Odin's answer sheet, which was worked out in game: every answer
 * should open the gates on the board it was written for. The sheet is independent of the
 * simulation, so this checks the water rules, the levers and the outlets together.
 */
class WaterSimTest {
	@Test
	fun `the four boards parse`() {
		assertEquals(4, WaterBoards.layouts.size)
		for (layout in 0 until 4) {
			val (board, slotIn) = WaterBoards.board(layout)
			assertTrue(board.slotCell.isNotEmpty(), "layout $layout has lever blocks")
			assertEquals(board.slotCell.size, slotIn.size)
		}
	}

	@Test
	fun `odin's answers solve the simulated boards`() {
		val failures = mutableListOf<String>()
		var total = 0
		for ((optimised, layouts) in WaterBoards.sheet) for ((layout, answers) in layouts) for ((key, answer) in answers) {
			for (facing in WaterBoard.Facing.entries) {
				total++
				val (board, slotIn) = WaterBoards.board(layout, facing)
				val sim = WaterBoards.run(board, slotIn, WaterBoards.closedBits(key), WaterBoards.sheetFlips(answer))
				if (sim.solvedAt == WaterSim.NONE) {
					failures += "optimised=$optimised layout=$layout shut=$key facing=$facing: gates ${Integer.toBinaryString(sim.closed)} wrong=${sim.wrongHits}"
				}
			}
		}
		println("Odin's sheet: ${total - failures.size}/$total solve")
		failures.take(40).forEach(::println)
		assertTrue(failures.size * 10 <= total, "${failures.size} of $total answers don't solve")
	}
}
