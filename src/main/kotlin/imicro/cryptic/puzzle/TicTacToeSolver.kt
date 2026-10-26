package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.phys.AABB

/**
 * Tic Tac Toe: the square to play, and during the opponent's turn the square you'll play next. The
 * board is nine item frames whose maps show X or O, read from the colour at the map's centre.
 *
 * The game is small enough to solve completely: a board is one number, every position's result is
 * cached, and every square keeping the best result is marked. On their turn, a square that is your
 * best reply to every sensible move of theirs is marked early.
 */
object TicTacToeSolver {
	private const val PUZZLE = "Tic Tac Toe"

	// A board is nine base-3 digits, square 0 top left, row by row: 0 empty, YOU, THEM.
	private const val EMPTY = 0
	private const val YOU = 1
	private const val THEM = 2
	private val POWERS = IntArray(9) { var p = 1; repeat(it) { p *= 3 }; p }

	private val LINES = arrayOf(
		intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
		intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
		intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
	)

	/** A position's result with best play: 1 win, 0 draw, -1 loss. */
	private val results = HashMap<Int, Int>()

	private var answer: Set<Int> = emptySet()

	private var early: Set<Int> = emptySet()

	fun tick() {
		answer = emptySet()
		early = emptySet()
		if (!PuzzleSolver.ticTacToeEnabled.value) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val (board, marks) = readBoard(room) ?: return

		// No marks is unstarted, nine is finished; an odd count means it's your turn.
		if (marks == 0 || marks == 9 || winner(board) != EMPTY) return
		if (marks % 2 == 1) {
			answer = bestSquares(board, YOU).toSet()
		} else if (PuzzleSolver.ticTacToePreAim.value) {
			early = earlyAnswer(board)
		}
	}

	/** The board and its mark count; null if a frame couldn't be read. */
	private fun readBoard(room: DungeonRoom): Pair<Int, Int>? {
		val client = Minecraft.getInstance()
		val level = client.level ?: return null
		val player = client.player ?: return null
		val near = AABB(player.blockPosition()).inflate(SEARCH)

		var board = 0
		var marks = 0
		for (frame in level.getEntitiesOfClass(ItemFrame::class.java, near, ItemFrame::hasFramedMap)) {
			val square = squareAt(room, frame.blockPosition()) ?: continue
			val map = frame.getFramedMapId(frame.item)?.let(level::getMapData) ?: return null
			val mark = when (map.colors[MAP_MIDDLE].toInt() and 0xFF) {
				CROSS -> THEM
				NOUGHT -> YOU
				else -> return null
			}
			board += mark * POWERS[square]
			marks++
		}
		return board to marks
	}

	private fun at(board: Int, square: Int): Int = board / POWERS[square] % 3

	private fun winner(board: Int): Int {
		for (line in LINES) {
			val first = at(board, line[0])
			if (first != EMPTY && first == at(board, line[1]) && first == at(board, line[2])) return first
		}
		return EMPTY
	}

	private fun emptySquares(board: Int): List<Int> = (0..8).filter { at(board, it) == EMPTY }

	private fun result(board: Int, toMove: Int): Int {
		val key = board * 2 + (toMove - 1)
		results[key]?.let { return it }
		val value = when (winner(board)) {
			YOU -> 1
			THEM -> -1
			else -> {
				val moves = emptySquares(board)
				when {
					moves.isEmpty() -> 0
					toMove == YOU -> moves.maxOf { result(board + YOU * POWERS[it], THEM) }
					else -> moves.minOf { result(board + THEM * POWERS[it], YOU) }
				}
			}
		}
		results[key] = value
		return value
	}

	/** Every square giving [side] its best result. */
	private fun bestSquares(board: Int, side: Int): List<Int> {
		val next = if (side == YOU) THEM else YOU
		val scored = emptySquares(board).associateWith { result(board + side * POWERS[it], next) }
		val best = (if (side == YOU) scored.values.maxOrNull() else scored.values.minOrNull()) ?: return emptyList()
		return scored.filterValues { it == best }.keys.toList()
	}

	/**
	 * Squares that are your best reply to every sensible opponent move. Empty if any of their moves
	 * leaves you lost.
	 */
	private fun earlyAnswer(board: Int): Set<Int> {
		var common: Set<Int>? = null
		for (theirs in bestSquares(board, THEM)) {
			val after = board + THEM * POWERS[theirs]
			if (winner(after) != EMPTY || result(after, YOU) < 0) return emptySet()
			val replies = bestSquares(after, YOU).toSet()
			common = common?.intersect(replies) ?: replies
		}
		return common.orEmpty()
	}

	private fun squareAt(room: DungeonRoom, pos: BlockPos): Int? {
		val relative = room.getRelativeCoords(pos) ?: return null
		val row = ROW_Y.indexOf(relative.y)
		val column = COLUMN_Z.indexOf(relative.z)
		return if (row < 0 || column < 0) null else row * 3 + column
	}

	/** Whether to swallow a click on [pos]: a board button that isn't your answer, while there is one. */
	@JvmStatic
	fun blocksClick(pos: BlockPos): Boolean {
		if (!PuzzleSolver.ticTacToeEnabled.value || !PuzzleSolver.ticTacToeBlockWrong.value || answer.isEmpty()) return false
		val room = PuzzleRooms.named(PUZZLE) ?: return false
		if (Minecraft.getInstance().level?.getBlockState(pos)?.block !is ButtonBlock) return false
		val square = squareAt(room, pos) ?: return false
		return square !in answer
	}

	/** The button for [square]. Row and column fix two coordinates; the depth into the wall is searched. */
	private fun buttonFor(room: DungeonRoom, square: Int): BlockPos? {
		val level = Minecraft.getInstance().level ?: return null
		return BUTTON_DEPTHS.firstNotNullOfOrNull { depth ->
			room.getRealCoords(BlockPos(depth, ROW_Y[square / 3], COLUMN_Z[square % 3]))
				?.takeIf { level.getBlockState(it).block is ButtonBlock }
		}
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.ticTacToeEnabled.value) return
		val marked = answer.ifEmpty { early }
		if (marked.isEmpty()) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		// The button's own shape, not the whole block.
		for (square in marked) {
			val button = buttonFor(room, square) ?: continue
			PuzzleRender.shape(context, button, PuzzleSolver.ticTacToeColor.argb, PuzzleSolver.ticTacToeStyle.selectedIndex, phase = false)
		}
	}

	fun reset() {
		answer = emptySet()
		early = emptySet()
	}

	/** The rows (top to bottom) and columns, in room coordinates. */
	private val ROW_Y = listOf(72, 71, 70)
	private val COLUMN_Z = listOf(17, 16, 15)

	private val BUTTON_DEPTHS = 6..10

	/** Map colours of X and O at the centre of the 128×128 map. */
	private const val CROSS = 114
	private const val NOUGHT = 33
	private const val MAP_MIDDLE = 128 * 64 + 64

	private const val SEARCH = 21.0
}
