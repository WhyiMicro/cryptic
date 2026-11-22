package imicro.cryptic.puzzle.water

import com.google.gson.JsonParser

/** The four real boards and Odin's answer sheet, for the water tests. */
object WaterBoards {
	/** Rows per layout, in Cryptic's layout numbering. */
	val layouts: List<List<String>> by lazy {
		val text = WaterBoards::class.java.getResource("/water-boards.txt")!!.readText()
		val boards = mutableListOf<MutableList<String>>()
		for (line in text.lineSequence().map { it.trim() }) {
			when {
				line.startsWith("layout") -> boards += mutableListOf<String>()
				line.length == 21 && line.all { it == '#' || it == '.' || it.isLetter() } -> boards.last() += line
			}
		}
		boards
	}

	fun board(layout: Int, facing: WaterBoard.Facing = WaterBoard.Facing.EAST): Pair<WaterBoard, BooleanArray> =
		WaterBoard.parse(layouts[layout], facing)

	/** Odin's sheet: optimised -> layout -> the three shut outlets -> lever key -> seconds after the water. */
	val sheet: Map<Boolean, Map<Int, Map<String, Map<String, List<Double>>>>> by lazy {
		val root = JsonParser.parseString(WaterBoards::class.java.getResource("/assets/cryptic/puzzles/water-solutions.json")!!.readText()).asJsonObject
		root.entrySet().associate { (optimised, layouts) ->
			optimised.toBoolean() to layouts.asJsonObject.entrySet().associate { (layout, keys) ->
				layout.toInt() to keys.asJsonObject.entrySet().associate { (key, levers) ->
					key to levers.asJsonObject.entrySet().associate { (lever, times) -> lever to times.asJsonArray.map { it.asDouble } }
				}
			}
		}
	}

	fun closedBits(key: String): Int = key.fold(0) { bits, digit -> bits or (1 shl (digit - '0')) }

	/**
	 * One of Odin's answers as timed flips: the before-water pulls a tick apart from tick 1, the
	 * water [WATER_GAP] ticks after, the rest at their seconds after it.
	 */
	fun sheetFlips(answer: Map<String, List<Double>>): List<Pair<Int, WaterLever>> {
		val flips = mutableListOf<Pair<Int, WaterLever>>()
		var tick = 1
		for (lever in WaterLever.entries) {
			if (lever == WaterLever.WATER) continue
			answer[lever.key].orEmpty().filter { it == 0.0 }.forEach { _ -> flips += tick++ to lever }
		}
		val water = tick + WATER_GAP
		flips += water to WaterLever.WATER
		for (lever in WaterLever.entries) {
			answer[lever.key].orEmpty().filter { it > 0.0 }.forEach { flips += water + Math.round(it * 20).toInt() to lever }
		}
		return flips.sortedBy { it.first }
	}

	private const val WATER_GAP = 4

	/** Runs [flips] on a fresh board and returns the solve tick, or [WaterSim.NONE]. */
	fun run(board: WaterBoard, slotIn: BooleanArray, closed: Int, flips: List<Pair<Int, WaterLever>>, until: Int = 2000): WaterSim {
		val sim = WaterSim.fresh(board, slotIn, closed)
		val byTick = flips.groupBy({ it.first }, { it.second.bit })
		while (sim.time < until && sim.solvedAt == WaterSim.NONE) {
			sim.step(byTick[sim.time + 1]?.fold(0) { a, b -> a or b } ?: 0)
		}
		return sim
	}
}
