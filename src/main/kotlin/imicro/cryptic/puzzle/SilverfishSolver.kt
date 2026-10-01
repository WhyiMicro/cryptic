package imicro.cryptic.puzzle

import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque

/**
 * The way out for the silverfish on the ice path.
 *
 * Ported from Skyblocker (LGPL-3.0). The room is a seventeen-by-seventeen grid
 * of ice with blocks placed on it, and the silverfish slides in whatever
 * direction it is pushed until something stops it — so the puzzle is a maze
 * where every move runs to the wall. A breadth-first search over those slides
 * finds the shortest way to the gap it has to leave through, and the answer is
 * drawn as the line it will travel.
 *
 * Both the board and the fish are re-read every tick, and the search is only
 * run again when one of them has actually changed.
 */
object SilverfishSolver {
	private const val PUZZLE = "Ice Path"
	private const val SIZE = 17

	/** True where something is standing on the ice. */
	private val board = Array(SIZE) { BooleanArray(SIZE) }

	private var fish: Cell? = null
	private val path = mutableListOf<Cell>()

	/** A square of the grid: the row runs along Z, the column along X. */
	private data class Cell(val row: Int, val column: Int)

	fun tick() {
		if (!PuzzleSolver.silverfishEnabled.value) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val level = Minecraft.getInstance().level ?: return

		var boardChanged = false
		for (row in 0 until SIZE) {
			for (column in 0 until SIZE) {
				val real = room.getRealCoords(BlockPos(23 - column, 67, 24 - row)) ?: return
				val blocked = !level.getBlockState(real).isAir
				if (board[row][column] != blocked) {
					board[row][column] = blocked
					boardChanged = true
				}
			}
		}

		val middle = room.getRealCoords(BlockPos(15, 66, 16)) ?: return
		val silverfish = level.getEntitiesOfClass(
			Silverfish::class.java,
			AABB.ofSize(Vec3.atCenterOf(middle), 16.0, 16.0, 16.0),
		) { true }.firstOrNull() ?: return

		val relative = room.getRelativeCoords(silverfish.blockPosition()) ?: return
		val cell = Cell(24 - relative.z, 23 - relative.x)
		if (cell.row !in 0 until SIZE || cell.column !in 0 until SIZE) return

		val fishMoved = cell != fish
		if (fishMoved) fish = cell
		if (fishMoved || boardChanged) solve()
	}

	/**
	 * The shortest run of slides to the gap.
	 *
	 * Each step slides until the next square is blocked or the grid runs out,
	 * which is what the fish actually does, so the path drawn is the path it
	 * will take rather than a route through open squares.
	 */
	private fun solve() {
		val start = fish ?: return
		val visited = HashSet<Cell>()
		val queue = ArrayDeque<List<Cell>>()
		queue.add(listOf(start))
		visited.add(start)

		while (queue.isNotEmpty()) {
			val route = queue.poll()
			val at = route.last()
			// The way out is the middle of the far wall.
			if (at.row == 0 && at.column in 7..9) {
				path.clear()
				path.addAll(route)
				return
			}

			slide(at, 1, 0)?.let { enqueue(visited, queue, route, it) }
			slide(at, -1, 0)?.let { enqueue(visited, queue, route, it) }
			slide(at, 0, 1)?.let { enqueue(visited, queue, route, it) }
			slide(at, 0, -1)?.let { enqueue(visited, queue, route, it) }
		}
	}

	/** Where a push in one direction leaves the fish, or null if it cannot move. */
	private fun slide(from: Cell, rowStep: Int, columnStep: Int): Cell? {
		var row = from.row
		var column = from.column
		while (true) {
			val nextRow = row + rowStep
			val nextColumn = column + columnStep
			if (nextRow !in 0 until SIZE || nextColumn !in 0 until SIZE) break
			if (board[nextRow][nextColumn]) break
			row = nextRow
			column = nextColumn
		}
		return if (row == from.row && column == from.column) null else Cell(row, column)
	}

	private fun enqueue(
		visited: MutableSet<Cell>,
		queue: ArrayDeque<List<Cell>>,
		route: List<Cell>,
		next: Cell,
	) {
		if (!visited.add(next)) return
		queue.add(route + next)
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.silverfishEnabled.value || path.size < 2) return
		val room = PuzzleRooms.named(PUZZLE) ?: return

		val points = path.mapNotNull { cell ->
			room.getRealCoords(BlockPos(23 - cell.column, 67, 24 - cell.row))?.let(Vec3::atCenterOf)
		}
		if (points.size < 2) return
		PuzzleRender.line(context, points, PuzzleSolver.silverfishColor.argb, phase = false, lineWidth = 3f)
	}

	fun reset() {
		board.forEach { it.fill(false) }
		fish = null
		path.clear()
	}
}
