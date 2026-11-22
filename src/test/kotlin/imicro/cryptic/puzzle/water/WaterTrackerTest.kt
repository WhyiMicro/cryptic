package imicro.cryptic.puzzle.water

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tracker against a pretend server: a second simulation with random ticks, whose changes are
 * sent as packets around per-tick pings. The tracker only sees the packets and the lever flips,
 * and has to end up with the server's board.
 */
class WaterTrackerTest {
	private var resyncs = 0

	private fun visible(sim: WaterSim, cell: Int): Int = sim.cells[cell].let { if (it >= 0) it and WaterSim.LEVEL else it }

	private fun runServer(randomRate: Double, pingFirst: Boolean, seed: Long): Pair<WaterSim, WaterTracker> {
		val (board, slotIn) = WaterBoards.board(0)
		val closed = WaterBoards.closedBits("012")
		val flips = WaterPlanner.sheetSeed(WaterBoards.sheet[false]!![0]!!["012"]!!, 5).groupBy { it.tick }
		val server = WaterSim.fresh(board, slotIn, closed)
		server.random = Random(seed)
		server.randomRate = randomRate
		val tracker = WaterTracker(WaterSim.fresh(board, slotIn, closed))
		resyncs = 0

		// Packets the server has sent, by the tick they go out with.
		val outbox = HashMap<Int, MutableList<Pair<Int, Int>>>()
		server.onChange = { cell, value, batch -> outbox.getOrPut(batch) { mutableListOf() } += cell to value }
		for (tick in 1..500) {
			val levers = flips[tick].orEmpty().fold(0) { bits, flip -> bits or flip.lever.bit }
			server.step(levers)
			val packets = outbox.remove(tick).orEmpty()
			if (pingFirst) tracker.onPing()
			flips[tick].orEmpty().forEach { tracker.onLever(it.lever) }
			packets.forEach { (cell, value) -> tracker.onCell(cell, value) }
			if (!pingFirst) tracker.onPing()
			if (tracker.needsRebuild) {
				resyncs++
				val water = IntArray(board.size) { visible(server, it).let { v -> if (v >= 0) v else -1 } }
				tracker.resync(water, server.slotIn, server.closed, server.sourceOn)
			}
		}
		return server to tracker
	}

	@Test
	fun `follows a server with no random ticks`() {
		for (pingFirst in listOf(true, false)) {
			val (server, tracker) = runServer(0.0, pingFirst, 1)
			assertEquals(0, resyncs, "pingFirst=$pingFirst: ${tracker.log}")
			for (cell in 0 until server.board.size) assertEquals(visible(server, cell), visible(tracker.sim, cell), "cell $cell")
		}
	}

	@Test
	fun `follows a server with random ticks`() {
		for (rate in listOf(1.0, 3.0)) {
			var rebuilt = 0
			var wrong = 0
			for (seed in 1L..40L) {
				val (server, tracker) = runServer(WaterSim.RANDOM_RATE * rate, true, seed)
				rebuilt += resyncs
				if ((0 until server.board.size).any { visible(server, it) != visible(tracker.sim, it) }) {
					wrong++
					println("TRACK rate=$rate seed=$seed ends wrong: ${tracker.log}")
				}
			}
			println("TRACK random ticks x$rate: board read again $rebuilt times in 40 runs, ending wrong in $wrong/40")
			assertEquals(0, wrong)
		}
	}
}
