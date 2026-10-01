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
 * Which teleport pad has not been used, and which to take next.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). Three earlier
 * attempts here each got one half of it, so both halves are worth naming.
 *
 * **Which pad is the answer.** The teleporter leaves you facing back down the
 * line you arrived along, so the pad you came from is somewhere on that line.
 * The answer is therefore kept as a set and narrowed: each landing cuts it to
 * the pads the view line passes through, minus the ones already stood on.
 * Across a few jumps it collapses to one. Scoring pads by how near your gaze
 * they are is the same observation used the weak way round — it always names a
 * favourite, including pads already ruled out, and that is a loop.
 *
 * **Which pad to step onto.** Not the same question. The maze is seven small
 * rooms of four pads, and only the four in the room you are standing in can be
 * reached; a line drawn to the answer from two rooms away points through a wall
 * at something you cannot step onto. The rooms cannot be found by distance,
 * either: the nearest pad of the next room along is two blocks away, nearer
 * than the far corner of your own. So the pads are taken from Odin's
 * written-down list, where the order is the grouping — four at a time — and the
 * last two are the way in and the way out.
 */
object TpMazeSolver {
	private const val PUZZLE = "Teleport Maze"

	/** How many pads make one room of the maze. */
	private const val GROUP = 4

	/** The pads, in the order the groups are read from. */
	private var pads: List<BlockPos> = emptyList()

	/** The pads not yet ruled out, which is the answer once it holds one. */
	private var candidates: List<BlockPos> = emptyList()

	/** Pads landed on, which is what the colours report. */
	private val visited = CopyOnWriteArraySet<BlockPos>()

	/**
	 * Pads stepped off, which is what stops the walk repeating itself.
	 *
	 * Different from [visited] in exactly the way that matters: a pad you were
	 * carried to is a place you have been, and a pad you walked onto is a move
	 * you have made. Only the second kind is worth refusing to do again.
	 */
	private val taken = CopyOnWriteArraySet<BlockPos>()

	/** The pad to step onto next, set only by a landing. */
	private var best: BlockPos? = null

	fun onRoomEnter(room: DungeonRoom) {
		reset()
		pads = emptyList()
		if (room.data?.name != PUZZLE) return
		place(room)
	}

	/**
	 * Puts the written-down pads where this room actually put them.
	 *
	 * Scanning for end portal frames instead, which an earlier version did,
	 * finds the same thirty blocks but throws away the order — and the order is
	 * the only record of which four belong to a room together.
	 */
	private fun place(room: DungeonRoom) {
		val placed = PAD_POSITIONS.map { room.getRealCoords(it) }
		if (placed.any { it == null }) return
		pads = placed.filterNotNull()
		candidates = pads
	}

	/**
	 * A teleport. Anything that is not a landing on the grid is ignored: the
	 * numbers are exact halves at one height, which walking never produces.
	 */
	fun onTeleport(packet: ClientboundPlayerPositionPacket) {
		if (pads.isEmpty() || !PuzzleRooms.inside(PUZZLE)) return
		val landing = packet.change.position
		if (landing.x % 0.5 != 0.0 || landing.y != 69.5 || landing.z % 0.5 != 0.0) return

		// Matched on the floor plan, the way NoammAddons does it, rather than by
		// box overlap. A pad's block runs from y 69 to 70 and somebody standing
		// on it runs from 70 up, so the two boxes meet at a plane and never
		// actually overlap — the pad you left was never once recognised, which
		// is why refusing to repeat a move changed nothing.
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
	 * The pad somebody at these coordinates is standing on.
	 *
	 * Measured flat, across the floor, because height says nothing here: every
	 * pad is at one height and whoever is on one is above it, never in it.
	 */
	private fun padNear(x: Double, z: Double): BlockPos? =
		pads.firstOrNull { abs(x - (it.x + 0.5)) + abs(z - (it.z + 0.5)) <= NEAR }

	/**
	 * Cuts the answer down to the pads this landing is compatible with.
	 *
	 * Filtering the set rather than rebuilding it is the whole point: what
	 * survives every jump is the answer.
	 */
	private fun narrow(from: Vec3, yaw: Float, pitch: Float) {
		if (candidates.isEmpty()) candidates = pads
		val goal = from.add(lookVector(yaw, pitch).scale(SIGHT))
		val standing = Minecraft.getInstance().player?.boundingBox

		candidates = candidates.filter { pad ->
			if (pad in visited) return@filter false
			if (standing != null && AABB(pad).inflate(0.5, 0.0, 0.5).intersects(standing)) return@filter false
			// A little wider than the frame, because a pad is aimed at from
			// across a room and a line passing beside a one-block block at that
			// range was still pointed at it.
			crossesFootprint(
				AABB(pad).inflate(0.75, 0.0, 0.75),
				from,
				goal,
			)
		}
	}

	/**
	 * Whether a line crosses a box's footprint, looking straight down on it.
	 *
	 * Odin's test, and the height being thrown away is the point of it rather
	 * than a shortcut. A pad is a block on the floor with four blocks of air
	 * over it, aimed at from up to thirty-two blocks away; a proper ray through
	 * that box misses it for a few degrees of downward pitch, which is how
	 * somebody looking slightly at their feet on landing ruled out the very pad
	 * the landing was evidence for. Rule pads out by where you are pointing on
	 * the floor plan, and a glance at the ground costs nothing.
	 *
	 * The line is tested against the four walls of the footprint: it crosses if
	 * it passes through either x wall inside the z range, or either z wall
	 * inside the x range.
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
	 * Picks the pad to step onto, out of the four in the room landed in.
	 *
	 * Never one that has already been stepped on. A pad always sends you to the
	 * same place, so stepping on one a second time repeats a move whose outcome
	 * is already known — and a maze walked by repeating known moves is a maze
	 * walked in circles. That is the loop, and it is a loop Odin and NoammAddons
	 * both have: they only rule out pads you have *landed on*, which is not the
	 * same set. Ruling out the ones you have *left from* makes going round twice
	 * impossible, because the room has four pads and the supply runs out.
	 *
	 * Of what is left, a pad the narrowing still believes in is the one worth
	 * taking; failing that, the one nearest the way you are already facing is a
	 * guess, which beats no line at all. Landing on the entrance pair means the
	 * maze has been left and there is nothing to point at.
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

		// Untaken first, and only fall back to the whole room when every pad in
		// it has been used — at which point a repeat is the only move there is,
		// and a line to one of them beats no line at all.
		val pool = others.filter { it !in taken }.ifEmpty { others }

		best = pool.firstOrNull { it in candidates }
			?: pool.firstOrNull { it !in visited }
			?: pool.minByOrNull { pad ->
				val towards = Math.toDegrees(atan2(pad.z + 0.5 - from.z, pad.x + 0.5 - from.x)).toFloat() - 90f
				abs(Mth.wrapDegrees(towards) - Mth.wrapDegrees(yaw)).toDouble()
			}
	}

	/** The same view vector the game builds from a pair of angles. */
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
		// Three states and no more, which is all a pad can be: the way on, one
		// already stood on, or one nothing is known about yet.
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

		// Only a pad a landing has named. Before the first teleport nothing has
		// been learnt, and a line drawn then points across the room at whichever
		// pad happens to be nearest rather than one that can be stepped onto.
		if (!PuzzleSolver.mazeTracer.value) return
		val target = best ?: return
		PuzzleRender.tracer(
			context,
			Vec3(target.x + 0.5, target.y + 0.8, target.z + 0.5),
			PuzzleSolver.mazeTracerColor.argb,
		)
	}

	/** What the solver can see, in words, for `/cryptic debug room`. */
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


	/** How far a pad can be aimed at from, which is across the room and then some. */
	private const val SIGHT = 32.0

	/** A line this flat in one axis never crosses that axis's walls. */
	private const val FLAT = 1e-8

	/** How near a pad somebody has to be to be standing on it, measured flat. */
	private const val NEAR = 3.0

	/** The last entries are the way in and the way out, not part of the maze. */
	private const val ENTRANCE_PADS = 2

	/**
	 * Every pad, in the room's own coordinates, four to a room.
	 *
	 * Odin's list, and the order matters: each run of four is one room of the
	 * maze, and the last two are the entrance and the exit.
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
