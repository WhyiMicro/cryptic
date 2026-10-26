package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Teleport Maze: which pad is the answer and which to step onto next. Ported from Odin.
 *
 * The answer: each landing leaves you facing back along the line you came, so the candidates are
 * narrowed to pads on that line, minus pads already used; after a few jumps one remains.
 *
 * The next pad: only the four pads in your own small room can be reached, so pads come from Odin's
 * list, grouped four at a time (the last two are the entrance and exit).
 */
object TpMazeSolver {
	private const val PUZZLE = "Teleport Maze"

	private const val GROUP = 4

	private var pads: List<BlockPos> = emptyList()

	private var candidates: List<BlockPos> = emptyList()

	/** Pads landed on. */
	private val visited = CopyOnWriteArraySet<BlockPos>()

	/** Pads stepped off. Unlike [visited], only these are moves you've made and shouldn't repeat. */
	private val taken = CopyOnWriteArraySet<BlockPos>()

	private var best: BlockPos? = null

	fun onRoomEnter(room: DungeonRoom) {
		reset()
		pads = emptyList()
		if (room.data?.name != PUZZLE) return
		place(room)
	}

	/**
	 * Places the listed pads in this room. The list's order is the only record of which four share
	 * a room.
	 */
	private fun place(room: DungeonRoom) {
		val placed = PAD_POSITIONS.map { room.getRealCoords(it) }
		if (placed.any { it == null }) return
		pads = placed.filterNotNull()
		candidates = pads
	}

	/**
	 * A teleport. Only landings on the grid count: exact halves at one height, which walking never
	 * produces.
	 */
	fun onTeleport(packet: ClientboundPlayerPositionPacket) {
		if (pads.isEmpty() || !PuzzleRooms.inside(PUZZLE)) return
		val landing = packet.change.position
		if (landing.x % 0.5 != 0.0 || landing.y != 69.5 || landing.z % 0.5 != 0.0) return

		// Matched on the floor plan, since the pad's box and a player standing on it only touch,
		// never overlap.
		val player = Minecraft.getInstance().player
		val from = player?.let { padNear(it.x, it.z) }
		val to = padNear(landing.x, landing.z)

		if (from != null) {
			taken.add(from)
			visited.add(from)
		}
		if (to != null) visited.add(to)

		narrow(landing, packet.change.yRot, packet.change.xRot)
		choose(landing, packet.change.yRot, to)
	}

	/**
	 * The pad under these coordinates: the nearest, if close enough. Measured flat, and the nearest
	 * rather than the first in range, because rooms are only two blocks apart.
	 */
	private fun padNear(x: Double, z: Double): BlockPos? {
		val pad = pads.minByOrNull { (x - (it.x + 0.5)).let { dx -> dx * dx } + (z - (it.z + 0.5)).let { dz -> dz * dz } } ?: return null
		return pad.takeIf { abs(x - (it.x + 0.5)) <= NEAR && abs(z - (it.z + 0.5)) <= NEAR }
	}

	/** Narrows the candidates to the pads this landing allows. */
	private fun narrow(from: Vec3, yaw: Float, pitch: Float) {
		if (candidates.isEmpty()) candidates = pads
		val goal = from.add(lookVector(yaw, pitch).scale(SIGHT))
		val standing = Minecraft.getInstance().player?.boundingBox

		candidates = candidates.filter { pad ->
			if (pad in visited) return@filter false
			if (standing != null && AABB(pad).inflate(0.5, 0.0, 0.5).intersects(standing)) return@filter false
			// A little wider than the pad, since pads are aimed at from across a room.
			crossesFootprint(
				AABB(pad).inflate(0.75, 0.0, 0.75),
				from,
				goal,
			)
		}
	}

	/**
	 * Whether a line crosses a box's footprint, seen from above. Height is ignored on purpose: a
	 * real ray through a floor pad misses it when looking slightly down.
	 */
	private fun crossesFootprint(box: AABB, from: Vec3, goal: Vec3): Boolean {
		fun atX(x: Double): Vec3? {
			val dx = goal.x - from.x
			if (dx * dx < FLAT) return null
			val t = (x - from.x) / dx
			return if (t in 0.0..1.0) from.add(goal.subtract(from).scale(t)) else null
		}

		fun atZ(z: Double): Vec3? {
			val dz = goal.z - from.z
			if (dz * dz < FLAT) return null
			val t = (z - from.z) / dz
			return if (t in 0.0..1.0) from.add(goal.subtract(from).scale(t)) else null
		}

		fun inZ(point: Vec3?) = point != null && point.z >= box.minZ && point.z <= box.maxZ
		fun inX(point: Vec3?) = point != null && point.x >= box.minX && point.x <= box.maxX

		return inZ(atX(box.minX)) || inZ(atX(box.maxX)) || inX(atZ(box.minZ)) || inX(atZ(box.maxZ))
	}

	/**
	 * Picks the pad to step onto from the four in the landing room. Never a used one (either
	 * direction), unless all four are used. Prefers a remaining candidate, otherwise the one
	 * nearest your facing. Landing on the entrance pair means you've left the maze.
	 */
	private fun choose(from: Vec3, yaw: Float, landedOn: BlockPos?) {
		val index = pads.indexOf(landedOn ?: return)
		if (index < 0) return
		if (index >= pads.size - ENTRANCE_PADS) {
			best = null
			return
		}

		val start = index / GROUP * GROUP
		if (start + GROUP > pads.size) return
		val others = pads.subList(start, start + GROUP).filter { it != pads[index] }

		// A pad landed on is as used as one stepped off, since pads come in pairs. Unused pads
		// first, then pads only landed on, then any.
		val pool = others.filter { it !in visited }
			.ifEmpty { others.filter { it !in taken } }
			.ifEmpty { others }

		best = pool.firstOrNull { it in candidates }
			?: pool.minByOrNull { pad ->
				val towards = Math.toDegrees(atan2(pad.z + 0.5 - from.z, pad.x + 0.5 - from.x)).toFloat() - 90f
				abs(Mth.wrapDegrees(towards) - Mth.wrapDegrees(yaw)).toDouble()
			}
	}

	private fun lookVector(yaw: Float, pitch: Float): Vec3 {
		val yawRadians = Math.toRadians(yaw.toDouble())
		val pitchRadians = Math.toRadians(pitch.toDouble())
		val horizontal = cos(pitchRadians)
		return Vec3(-sin(yawRadians) * horizontal, -sin(pitchRadians), cos(yawRadians) * horizontal)
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.mazeEnabled.value || pads.isEmpty()) return
		if (!PuzzleRooms.inside(PUZZLE)) return

		val style = PuzzleSolver.mazeStyle.selectedIndex
		// Three states: the way on, already used, or unknown.
		val correctColor = PuzzleSolver.mazeColorOne.argb
		val severalColor = PuzzleSolver.mazeColorMultiple.argb
		val visitedColor = PuzzleSolver.mazeColorVisited.argb

		pads.forEach { pad ->
			val color = when {
				pad in candidates -> if (candidates.size == 1) correctColor else severalColor
				pad in visited -> visitedColor
				else -> severalColor
			}
			PuzzleRender.box(context, AABB(pad), color, style, phase = false)
		}

		// Only a pad a landing has named; before the first teleport nothing is known.
		if (!PuzzleSolver.mazeTracer.value) return
		val target = best ?: return
		PuzzleRender.tracer(
			context,
			Vec3(target.x + 0.5, target.y + 0.8, target.z + 0.5),
			PuzzleSolver.mazeTracerColor.argb,
		)
	}

	/** For `/cryptic debug room`. */
	fun describe(): List<String> = listOf(
		"Teleport maze: ${pads.size} pads, ${visited.size} landed on, ${taken.size} stepped off, ${candidates.size} still possible.",
		"  Next pad: ${best?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "not known until you teleport"}",
	)

	fun reset() {
		candidates = pads
		visited.clear()
		taken.clear()
		best = null
	}


	private const val SIGHT = 32.0

	private const val FLAT = 1e-8

	/** How far from a pad's centre, along either axis, someone standing on it can be. */
	private const val NEAR = 1.5

	private const val ENTRANCE_PADS = 2

	/**
	 * Every pad in room coordinates, four per room, from Odin's list; the last two are the entrance
	 * and exit.
	 */
	private val PAD_POSITIONS = listOf(
		BlockPos(4, 69, 12), BlockPos(4, 69, 6), BlockPos(10, 69, 12), BlockPos(10, 69, 6),
		BlockPos(4, 69, 20), BlockPos(4, 69, 14), BlockPos(10, 69, 20), BlockPos(10, 69, 14),
		BlockPos(4, 69, 28), BlockPos(4, 69, 22), BlockPos(10, 69, 28), BlockPos(10, 69, 22),
		BlockPos(12, 69, 28), BlockPos(12, 69, 22), BlockPos(18, 69, 28), BlockPos(18, 69, 22),
		BlockPos(20, 69, 28), BlockPos(20, 69, 22), BlockPos(26, 69, 28), BlockPos(26, 69, 22),
		BlockPos(26, 69, 20), BlockPos(26, 69, 14), BlockPos(20, 69, 20), BlockPos(20, 69, 14),
		BlockPos(26, 69, 12), BlockPos(26, 69, 6), BlockPos(20, 69, 12), BlockPos(20, 69, 6),
		BlockPos(15, 69, 14), BlockPos(15, 69, 12),
	)
}
