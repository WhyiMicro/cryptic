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
 * Where to put the next noughts-and-crosses piece.
 *
 * Ported from Skyblocker (LGPL-3.0), which credits Danker's original. The
 * board is nine item frames on a wall, each holding a map that is either an X
 * or an O, and which it is can be read from the colour of the map's middle
 * pixel. Once the board is known the move is a minimax search — the same one
 * everybody writes, because the game is small enough to solve exactly.
 *
 * It answers on your turn, which is when an odd number of frames are up and
 * the board is not full. On the opponent's turn it can answer early, which is
 * Lumen's idea (AGPL-3.0, Lumen contributors): every move the opponent would
 * sensibly make is tried, with your best reply to each, and a square that is
 * your best reply to all of them is marked before they have moved — so it can
 * be aimed at while they are still deciding.
 *
 * Lumen also marks two squares when the opponent has exactly two good moves
 * and your answer to each is the other one. That case is left out here: tried
 * against every board where it is the opponent's move, it never happens — for
 * them to have only two good moves, every other has to lose, which only
 * happens when they must block, and a block is one square.
 */
object TicTacToeSolver {
	private const val PUZZLE = "Tic Tac Toe"

	/** The colour of the middle pixel, which is what says X from O. */
	private const val CROSS_COLOR = 114
	private const val NOUGHT_COLOR = 33

	/** Every square that reaches the best outcome, which is often more than one. */
	private var squares: Set<Pair<Int, Int>> = emptySet()

	/** On the opponent's turn, the squares that will be your answer whatever they play. */
	private var predicted: Set<Pair<Int, Int>> = emptySet()

	fun tick() {
		squares = emptySet()
		predicted = emptySet()
		if (!PuzzleSolver.ticTacToeEnabled.value) return

		val room = PuzzleRooms.named(PUZZLE) ?: return
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val player = client.player ?: return

		val search = AABB(
			player.x - SEARCH, player.y - SEARCH, player.z - SEARCH,
			player.x + SEARCH, player.y + SEARCH, player.z + SEARCH,
		)
		val frames = level.getEntitiesOfClass(ItemFrame::class.java, search, ItemFrame::hasFramedMap)
		// Nine frames is a finished board, and an empty one has nothing to go
		// on. An even number is the puzzle's turn rather than yours.
		if (frames.isEmpty() || frames.size == 9) return
		val yourTurn = frames.size % 2 == 1
		if (!yourTurn && !PuzzleSolver.ticTacToePreAim.value) return

		val board = Array(3) { CharArray(3) }
		frames.forEach { frame ->
			val map = level.getMapData(frame.getFramedMapId(frame.item) ?: return@forEach) ?: return@forEach
			val relative = room.getRelativeCoords(frame.blockPosition()) ?: return@forEach

			val row = when (relative.y) {
				72 -> 0
				71 -> 1
				70 -> 2
				else -> return@forEach
			}
			val column = when (relative.z) {
				17 -> 0
				16 -> 1
				15 -> 2
				else -> return@forEach
			}

			when (map.colors[MIDDLE_PIXEL].toInt() and 0xFF) {
				CROSS_COLOR -> board[row][column] = 'X'
				NOUGHT_COLOR -> board[row][column] = 'O'
			}
		}

		// A board already won is over, and one whose marks could not all be read
		// is not the board that is there.
		if (outcome(board) != 0) return
		if (board.sumOf { row -> row.count { it != '\u0000' } } != frames.size) return

		// Every square that ends as well as the best one does. A tie is a win
		// here - the puzzle is failed only by losing - so when two squares both
		// tie there is no reason to send you to one of them in particular.
		if (yourTurn) {
			squares = bestMoves(board).toSet()
			return
		}
		predict(board)
	}

	/**
	 * Your answer before the opponent has moved, if it can be known.
	 *
	 * Each of their best moves is tried, and your best replies to it worked out.
	 * A square that is among your best replies to every one of them is safe to
	 * aim at now — none of their moves takes it, and none makes it wrong. Lumen
	 * shows the replies to their first good move; only the squares common to
	 * all of them are shown here, so a pre-aimed square is never one that their
	 * actual move turns into a mistake.
	 *
	 * It assumes they play well. If they do not, their move still lands, and
	 * the real answer replaces this one on your turn.
	 */
	private fun predict(board: Array<CharArray>) {
		val replies = theirBestMoves(board)
		if (replies.isEmpty()) return

		// A reply that leaves you lost whatever you do makes every square equally
		// "best", and marking all of them says nothing; only a board where your
		// answer holds the draw is predicted.
		val answers = replies.map { (row, column) ->
			board[row][column] = 'X'
			val (yours, score) = if (outcome(board) == 0) scoredBestMoves(board) else emptyList<Pair<Int, Int>>() to -WIN
			board[row][column] = '\u0000'
			if (score < 0) return
			(row to column) to yours.toSet()
		}

		predicted = answers.map { it.second }.reduce { all, next -> all intersect next }
	}

	/** The opponent's best moves: every square that leaves you the worst outcome. */
	private fun theirBestMoves(board: Array<CharArray>): List<Pair<Int, Int>> {
		val scores = mutableListOf<Pair<Pair<Int, Int>, Int>>()
		for (row in 0..2) {
			for (column in 0..2) {
				if (board[row][column] != '\u0000') continue
				board[row][column] = 'X'
				val score = search(board, Int.MIN_VALUE, Int.MAX_VALUE, true)
				board[row][column] = '\u0000'
				scores.add((row to column) to score)
			}
		}
		val worst = scores.minOfOrNull { it.second } ?: return emptyList()
		return scores.filter { it.second == worst }.map { it.first }
	}

	/**
	 * Whether a click on this square should be swallowed.
	 *
	 * Only the board's own squares are considered, and only while the solver
	 * has an answer: anything else is somebody clicking something that is not
	 * this puzzle. Playing a square that loses is how the room is failed, and
	 * the squares are a block apart on a wall you are shooting at.
	 */
	@JvmStatic
	fun blocksClick(pos: BlockPos): Boolean {
		if (!PuzzleSolver.ticTacToeEnabled.value || !PuzzleSolver.ticTacToeBlockWrong.value) return false
		if (squares.isEmpty()) return false
		val room = PuzzleRooms.named(PUZZLE) ?: return false

		// Asked of the block you clicked rather than of a position worked out
		// beforehand. The board's x was a written-down guess, and a guess that
		// is a block out makes the answer's positions match nothing in the
		// world, at which point the guard has to let everything through or
		// block everything. What the clicked block is, and which row and
		// column of the board it stands in, are both things the world knows.
		val level = Minecraft.getInstance().level ?: return false
		if (level.getBlockState(pos).block !is ButtonBlock) return false
		val square = squareAt(room, pos) ?: return false
		return square !in squares
	}

	/** Which row and column of the board a position is, if it is on it. */
	private fun squareAt(room: DungeonRoom, pos: BlockPos): Pair<Int, Int>? {
		val relative = room.getRelativeCoords(pos) ?: return null
		val row = ROW_Y.indexOf(relative.y).takeIf { it >= 0 } ?: return null
		val column = COLUMN_Z.indexOf(relative.z).takeIf { it >= 0 } ?: return null
		return row to column
	}

	/**
	 * The button for a square, found by looking along the wall for it.
	 *
	 * The row and column fix two of the three coordinates; the third is
	 * whichever depth the buttons were built at, so it is searched for instead
	 * of assumed.
	 */
	private fun buttonAt(room: DungeonRoom, row: Int, column: Int): BlockPos? {
		val level = Minecraft.getInstance().level ?: return null
		for (x in BOARD_X_SEARCH) {
			val pos = room.getRealCoords(BlockPos(x, ROW_Y[row], COLUMN_Z[column])) ?: continue
			if (level.getBlockState(pos).block is ButtonBlock) return pos
		}
		return null
	}

	/** The board's rows, top to bottom, and its columns, in the room's own coordinates. */
	private val ROW_Y = listOf(72, 71, 70)
	private val COLUMN_Z = listOf(17, 16, 15)

	/** How deep into the wall the buttons are looked for. */
	private val BOARD_X_SEARCH = 6..10

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.ticTacToeEnabled.value) return
		val marked = squares.ifEmpty { predicted }
		if (marked.isEmpty()) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val color = PuzzleSolver.ticTacToeColor.argb

		// The button's own shape rather than the cube around it: what you shoot
		// is a button on a wall, and a whole block marks the wall as well.
		marked.mapNotNull { (row, column) -> buttonAt(room, row, column) }.forEach {
			PuzzleRender.shape(
				context,
				it,
				color,
				PuzzleSolver.ticTacToeStyle.selectedIndex,
				phase = false,
			)
		}
	}

	fun reset() {
		squares = emptySet()
		predicted = emptySet()
	}


	/**
	 * Every square that reaches the best outcome available.
	 *
	 * Skyblocker takes the single highest-scoring square; this keeps all of
	 * them, because the board regularly offers two squares that both hold the
	 * draw and picking one of them is a decision the solver has no reason to
	 * make. Scores come back equal only when the games they lead to end the
	 * same way.
	 */
	private fun bestMoves(board: Array<CharArray>): List<Pair<Int, Int>> = scoredBestMoves(board).first

	/** [bestMoves], with the score they all reach. */
	private fun scoredBestMoves(board: Array<CharArray>): Pair<List<Pair<Int, Int>>, Int> {
		val scores = mutableListOf<Pair<Pair<Int, Int>, Int>>()

		for (row in 0..2) {
			for (column in 0..2) {
				if (board[row][column] != '\u0000') continue
				board[row][column] = 'O'
				val score = search(board, Int.MIN_VALUE, Int.MAX_VALUE, false)
				board[row][column] = '\u0000'
				scores.add((row to column) to score)
			}
		}

		val best = scores.maxOfOrNull { it.second } ?: return emptyList<Pair<Int, Int>>() to 0
		return scores.filter { it.second == best }.map { it.first } to best
	}

	/**
	 * Minimax with alpha-beta pruning: nine squares is small enough to solve.
	 *
	 * No preference for a quick win, which the usual version gets by shading
	 * the score with how deep it was found. That shading is what made two
	 * squares that both hold the draw score differently, and here they are the
	 * same answer: the puzzle is failed by losing, and a draw reached in four
	 * moves is worth exactly as much as one reached in two.
	 */
	private fun search(board: Array<CharArray>, alphaIn: Int, betaIn: Int, maximizing: Boolean): Int {
		val score = outcome(board)
		if (score == WIN || score == -WIN) return score
		if (board.none { row -> row.any { it == '\u0000' } }) return 0

		var alpha = alphaIn
		var beta = betaIn

		if (maximizing) {
			var best = Int.MIN_VALUE
			for (row in 0..2) {
				for (column in 0..2) {
					if (board[row][column] != '\u0000') continue
					board[row][column] = 'O'
					best = maxOf(best, search(board, alpha, beta, false))
					board[row][column] = '\u0000'
					alpha = maxOf(alpha, best)
					if (beta <= alpha) break
				}
			}
			return best
		}

		var best = Int.MAX_VALUE
		for (row in 0..2) {
			for (column in 0..2) {
				if (board[row][column] != '\u0000') continue
				board[row][column] = 'X'
				best = minOf(best, search(board, alpha, beta, true))
				board[row][column] = '\u0000'
				beta = minOf(beta, best)
				if (beta <= alpha) break
			}
		}
		return best
	}

	/** Ten for a win, minus ten for a loss, nothing for anything else. */
	private fun outcome(board: Array<CharArray>): Int {
		val lines = listOf(
			Triple(board[0][0], board[0][1], board[0][2]),
			Triple(board[1][0], board[1][1], board[1][2]),
			Triple(board[2][0], board[2][1], board[2][2]),
			Triple(board[0][0], board[1][0], board[2][0]),
			Triple(board[0][1], board[1][1], board[2][1]),
			Triple(board[0][2], board[1][2], board[2][2]),
			Triple(board[0][0], board[1][1], board[2][2]),
			Triple(board[0][2], board[1][1], board[2][0]),
		)

		lines.forEach { (a, b, c) ->
			if (a == b && a == c) {
				when (a) {
					'X' -> return -WIN
					'O' -> return WIN
				}
			}
		}
		return 0
	}

	private const val WIN = 10

	/** The middle of a 128 by 128 map, which is the pixel that carries the mark. */
	private const val MIDDLE_PIXEL = 8256

	/** How far to look for the board, which is Skyblocker's twenty-one blocks. */
	private const val SEARCH = 21.0
}
