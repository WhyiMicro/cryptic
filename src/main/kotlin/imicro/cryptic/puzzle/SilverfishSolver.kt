package imicro.cryptic.puzzle

import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Ice Path: the way out for the silverfish. A push slides it until something stops it, so the
 * fewest slides to the gap are found with a breadth-first search over where each slide ends.
 *
 * Room coordinates: x 7 to 23, z 8 to 24, the gap at x 14 to 16 on the z = 24 side. Re-read every
 * tick; the search reruns only when the board changes or the fish comes to rest. While it slides
 * the route stays as it was, and the stretch it's sliding along turns the moving colour.
 */
object SilverfishSolver {
	private const val PUZZLE = "Ice Path"

	private const val MIN_X = 7
	private const val MAX_X = 23
	private const val MIN_Z = 8
	private const val MAX_Z = 24
	private const val ICE_Y = 67
	private val EXIT_X = 14..16
	private const val EXIT_Z = MAX_Z

	private const val WIDTH = MAX_X - MIN_X + 1
	private const val DEPTH = MAX_Z - MIN_Z + 1

	private fun square(x: Int, z: Int) = (x - MIN_X) + (z - MIN_Z) * WIDTH
	private fun xOf(square: Int) = MIN_X + square % WIDTH
	private fun zOf(square: Int) = MIN_Z + square / WIDTH

	private val blocked = BooleanArray(WIDTH * DEPTH)

	private var fish = -1

	/** The squares each slide ends on, starting with the fish's. Empty when there is no way out. */
	private var route: List<Int> = emptyList()

	private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

	/** Whether the fish moved lately, and when it last did; a slide's ticks aren't all moves. */
	private var moving = false
	private var lastMovedTick = 0L
	private var ticks = 0L

	/** Ticks without a move before the fish counts as stopped. */
	private const val REST_TICKS = 3

	fun tick() {
		if (!PuzzleSolver.silverfishEnabled.value) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val level = Minecraft.getInstance().level ?: return

		var changed = false
		for (z in MIN_Z..MAX_Z) for (x in MIN_X..MAX_X) {
			val world = room.getRealCoords(BlockPos(x, ICE_Y, z)) ?: return
			val now = !level.getBlockState(world).isAir
			val index = square(x, z)
			if (blocked[index] != now) {
				blocked[index] = now
				changed = true
			}
		}

		val middle = room.getRealCoords(BlockPos(15, ICE_Y - 1, 16)) ?: return
		val silverfish = level.getEntitiesOfClass(Silverfish::class.java, AABB.ofSize(Vec3.atCenterOf(middle), 16.0, 16.0, 16.0)).firstOrNull() ?: return
		val at = room.getRelativeCoords(silverfish.blockPosition()) ?: return
		if (at.x !in MIN_X..MAX_X || at.z !in MIN_Z..MAX_Z) return
		ticks++
		if (silverfish.position().distanceToSqr(silverfish.xo, silverfish.yo, silverfish.zo) > 1e-4) lastMovedTick = ticks
		moving = ticks - lastMovedTick < REST_TICKS
		val now = square(at.x, at.z)
		if (now != fish) {
			fish = now
			// Mid-slide the route is kept; it's worked out again where the fish stops.
			if (!moving) changed = true
		} else if (!moving && route.firstOrNull() != fish) {
			changed = true
		}
		if (changed) route = findRoute(fish)
	}

	/** Whether the fish lies on the first slide of the route, between its ends. */
	private fun onFirstSlide(): Boolean {
		if (route.size < 2 || fish < 0) return false
		val (ax, az) = xOf(route[0]) to zOf(route[0])
		val (bx, bz) = xOf(route[1]) to zOf(route[1])
		val (fx, fz) = xOf(fish) to zOf(fish)
		return (ax == bx && fx == ax && fz in minOf(az, bz)..maxOf(az, bz)) ||
			(az == bz && fz == az && fx in minOf(ax, bx)..maxOf(ax, bx))
	}

	/** Where a push along (dx, dz) stops, or -1 if the fish can't move that way. */
	private fun slideEnd(from: Int, dx: Int, dz: Int): Int {
		var x = xOf(from)
		var z = zOf(from)
		while (true) {
			val nx = x + dx
			val nz = z + dz
			if (nx !in MIN_X..MAX_X || nz !in MIN_Z..MAX_Z || blocked[square(nx, nz)]) break
			x = nx
			z = nz
		}
		val end = square(x, z)
		return if (end == from) -1 else end
	}

	private fun isExit(square: Int) = zOf(square) == EXIT_Z && xOf(square) in EXIT_X

	private fun findRoute(start: Int): List<Int> {
		if (start < 0) return emptyList()
		val cameFrom = IntArray(WIDTH * DEPTH) { -2 }
		cameFrom[start] = -1
		val queue = ArrayDeque<Int>()
		queue.addLast(start)
		while (queue.isNotEmpty()) {
			val at = queue.removeFirst()
			if (isExit(at)) {
				val path = ArrayList<Int>()
				var step = at
				while (step >= 0) {
					path += step
					step = cameFrom[step]
				}
				return path.asReversed()
			}
			for ((dx, dz) in STEPS) {
				val next = slideEnd(at, dx, dz)
				if (next < 0 || cameFrom[next] != -2) continue
				cameFrom[next] = at
				queue.addLast(next)
			}
		}
		return emptyList()
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.silverfishEnabled.value || route.size < 2) return
		val room = PuzzleRooms.named(PUZZLE) ?: return
		val points = route.mapNotNull { room.getRealCoords(BlockPos(xOf(it), ICE_Y, zOf(it)))?.let(Vec3::atCenterOf) }
		if (points.size < 2) return
		if (moving && onFirstSlide()) {
			// The slide under way in the moving colour, the rest as usual.
			PuzzleRender.line(context, points.take(2), PuzzleSolver.silverfishMovingColor.argb, phase = false, lineWidth = 3f)
			if (points.size > 2) PuzzleRender.line(context, points.drop(1), PuzzleSolver.silverfishColor.argb, phase = false, lineWidth = 3f)
			return
		}
		PuzzleRender.line(context, points, PuzzleSolver.silverfishColor.argb, phase = false, lineWidth = 3f)
	}

	fun reset() {
		blocked.fill(false)
		fish = -1
		route = emptyList()
		moving = false
	}
}
