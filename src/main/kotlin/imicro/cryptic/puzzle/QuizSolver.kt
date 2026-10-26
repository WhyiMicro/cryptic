package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.core.BlockPos

/**
 * Quiz: which of Oruo's three shuffled answers is right. The questions are a lookup; the SkyBlock
 * year question is worked out from the clock. Also times the answer window, which nothing on screen
 * shows. Ported from Odin.
 */
object QuizSolver {
	private const val PUZZLE = "Quiz"

	private const val START_TICKS = 220
	private const val QUESTION_TICKS = 100

	private class Option(var pos: BlockPos? = null, var correct: Boolean = false)

	private var options = List(3) { Option() }

	private var answers: List<String>? = null

	/** The right answer's words, from its line; and whether this question's was sent to the party. */
	private var answerText: String? = null
	private var announced = false

	/** Ticks left, the round's length, and which of the three rounds. */
	var timer: Triple<Int, Int, Int> = Triple(0, 0, 0)
		private set

	/** True from Oruo's greeting until the quiz ends. The countdown alone only runs between questions. */
	var running = false
		private set

	fun onRoomEnter(room: DungeonRoom) {
		if (room.data?.name != PUZZLE) return
		options[0].pos = room.getRealCoords(BlockPos(20, 70, 6))
		options[1].pos = room.getRealCoords(BlockPos(15, 70, 9))
		options[2].pos = room.getRealCoords(BlockPos(10, 70, 6))
	}

	@JvmStatic
	fun onServerTick() {
		val (left, length, stage) = timer
		if (left <= 0) return
		timer = Triple(left - 1, length, stage)
	}

	fun onMessage(raw: String) {
		// Oruo's options carry colour codes inside the text, so lines are matched with codes
		// stripped.
		val message = FORMATTING.replace(raw, "")

		when (message) {
			GREETING -> {
				running = true
				timer = Triple(START_TICKS, START_TICKS, 1)
			}
			TWO_LEFT -> timer = Triple(QUESTION_TICKS, QUESTION_TICKS, 2)
			ONE_LEFT -> timer = Triple(QUESTION_TICKS, QUESTION_TICKS, 3)
		}

		if (message.startsWith(ORUO) && message.endsWith("correctly!")) {
			if (message.contains("answered the final question")) {
				PuzzleSolver.onPuzzleComplete(PUZZLE)
				reset()
				return
			}
			if (message.contains("answered Question #")) {
				options.forEach { it.correct = false }
				answerText = null
				announced = false
			}
		}

		if (message.startsWith(ORUO) && message.contains("chose the wrong answer")) {
			reset()
			return
		}

		val trimmed = message.trim()
		if (LETTERS.any { trimmed.startsWith(it) } && answers?.any { message.endsWith(it) } == true) {
			when (trimmed.first()) {
				'ⓐ' -> options[0].correct = true
				'ⓑ' -> options[1].correct = true
				'ⓒ' -> options[2].correct = true
			}
			answerText = trimmed.drop(1).trim()
			if (PuzzleSolver.quizParty.value && !announced) {
				announced = true
				val letter = "ABC"[LETTERS.indexOfFirst { trimmed.startsWith(it) }.coerceIn(0, 2)]
				imicro.cryptic.feature.PartyFeatures.enqueue("pc Quiz: $letter - $answerText")
			}
		}

		answers = when {
			trimmed == YEAR_QUESTION -> listOf("Year ${skyblockYear()}")
			else -> {
				PuzzleAssets.ensureLoaded()
				PuzzleAssets.quiz.entries.find { message.contains(it.key) }?.value ?: return
			}
		}
	}

	/** SkyBlock years are 446400 seconds long and started in June 2019. */
	private fun skyblockYear(): Int =
		(((System.currentTimeMillis() / 1000) - 1_560_276_000) / 446_400).toInt() + 1

	/**
	 * The letter of the right answer, once known; shown while a question is up, when the countdown
	 * isn't running.
	 */
	fun correctLetter(): String? {
		if (answers == null) return null
		val index = options.indexOfFirst { it.correct }
		return if (index >= 0) LETTERS.getOrNull(index) else null
	}

	/** The right answer's words, once known. */
	fun correctAnswer(): String? = answerText.takeIf { correctLetter() != null }

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.quizEnabled.value || answers == null) return
		// Drawn through walls, the answer stays up after you leave the room.
		if (!PuzzleSolver.quizPhase.value && !PuzzleRooms.inside(PUZZLE)) return

		options.forEach { option ->
			if (!option.correct) return@forEach
			val pos = option.pos ?: return@forEach
			PuzzleRender.block(
				context,
				pos,
				PuzzleSolver.quizColor.argb,
				PuzzleSolver.quizStyle.selectedIndex,
				phase = PuzzleSolver.quizPhase.value,
			)
		}
	}

	fun reset() {
		options = List(3) { Option() }
		answers = null
		answerText = null
		announced = false
		timer = Triple(0, 0, 0)
		running = false
	}

	private const val ORUO = "[STATUE] Oruo the Omniscient: "
	private const val GREETING =
		"[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know."
	private const val TWO_LEFT =
		"[STATUE] Oruo the Omniscient: 2 questions left... Then you will have proven your worth to me!"
	private const val ONE_LEFT = "[STATUE] Oruo the Omniscient: One more question!"
	private const val YEAR_QUESTION = "What SkyBlock year is it?"

	private val FORMATTING = Regex("§.")

	private val LETTERS = listOf("ⓐ", "ⓑ", "ⓒ")
}
