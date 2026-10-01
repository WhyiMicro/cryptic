package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.core.BlockPos

/**
 * Which of Oruo's three answers is the right one.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). The questions
 * come from a fixed set, so the answers are a lookup; what has to be watched
 * for is which of the three letters carries the right answer this time, since
 * Oruo shuffles them. The SkyBlock year is the one question nobody can write
 * down, and is worked out from the clock instead.
 *
 * The timer is the other half of the puzzle: there is a limit on answering and
 * nothing on screen counts it down.
 */
object QuizSolver {
	private const val PUZZLE = "Quiz"

	private const val START_TICKS = 220
	private const val QUESTION_TICKS = 100

	/** One of the three plates, and whether this round's answer is on it. */
	private class Option(var pos: BlockPos? = null, var correct: Boolean = false)

	private var options = List(3) { Option() }

	/** The answers to the question being asked, once it has been recognised. */
	private var answers: List<String>? = null

	/** Ticks left, the length of this round, and which of the three it is. */
	var timer: Triple<Int, Int, Int> = Triple(0, 0, 0)
		private set

	/**
	 * True from Oruo's greeting until the quiz is finished or failed.
	 *
	 * The countdown only runs between questions, so on its own it left the HUD
	 * blinking out every time a question was actually on screen.
	 */
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
		// Oruo writes his three options with the colour codes inside the text
		// rather than as style, so the line really does begin "§6 " and a test
		// for the letter at the front of it never matched. Everything here
		// reads the line with the codes taken out.
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
			if (message.contains("answered Question #")) options.forEach { it.correct = false }
		}

		// A wrong answer ends the round as surely as the last question does.
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
	 * Which of the three letters carries the answer, once it is known.
	 *
	 * The countdown only runs between questions — it is the wait for the next
	 * one rather than a deadline — so the HUD would otherwise go blank exactly
	 * when the question everybody is reading is on screen. The letter fills
	 * that gap, and is the one thing worth reading if the block itself is
	 * behind you.
	 */
	fun correctLetter(): String? {
		if (answers == null) return null
		val index = options.indexOfFirst { it.correct }
		return if (index >= 0) LETTERS.getOrNull(index) else null
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.quizEnabled.value || answers == null) return
		// Drawn through walls, the answer is for reading from wherever you have
		// wandered off to while Oruo talks, so it stays up outside the room. Not
		// drawn through them, there is nothing to see from outside anyway.
		if (!PuzzleSolver.quizPhase.value && !PuzzleRooms.inside(PUZZLE)) return

		options.forEach { option ->
			if (!option.correct) return@forEach
			// The plate itself rather than the block under it, and no beam over
			// it: the answer is a block you walk onto from two paces away, and
			// a column of light is a lot of screen for that.
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

	/** Hypixel's own colour codes, written into the text of the line. */
	private val FORMATTING = Regex("§.")

	private val LETTERS = listOf("ⓐ", "ⓑ", "ⓒ")
}
