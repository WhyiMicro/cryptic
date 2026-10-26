package imicro.cryptic.dungeon.map

import imicro.cryptic.feature.DungeonMap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * One room on the dungeon map and the tiles it covers. The map item gives its type and progress;
 * the world scan gives which named room it is. Ported from dtMap.
 */
class DungeonRoom(var type: Type, var shape: Shape) {
	val tiles: MutableList<Vec2i> = mutableListOf()
	var state: State = State.UNDISCOVERED

	var data: RoomData? = null

	val doors: MutableSet<DungeonDoor> = mutableSetOf()

	/** The one tile Hypixel paints while a room is shut; where it is drawn while exploring. */
	var entryTile: Vec2i? = null

	var isKnown1x1 = false

	/**
	 * Which way this room's schematic is turned. Odin's method: Hypixel buries a blue terracotta
	 * block at one roof corner, where the schematic's origin landed; that corner gives the rotation
	 * and is the origin for schematic coordinates. Larger rooms are resolved too once all their
	 * tiles are known.
	 */
	var rotation: RoomRotation? = null
		private set

	var clayPos: BlockPos? = null
		private set

	private var rotationChecked = false

	/**
	 * Looks for the marker, at most once per room. Unloaded rooms are retried; answered ones never
	 * touch the world again.
	 */
	fun resolveRotation(level: Level): Boolean {
		if (rotation != null) return true
		if (rotationChecked) return false
		if (tiles.isEmpty()) return false
		// Larger rooms wait for all their tiles, or an inside corner could look like an outside
		// one.
		if (shape != Shape.UNKNOWN && tiles.size < shape.tileCount) return false
		if (shape == Shape.UNKNOWN && tiles.size != 1) return false

		val first = tiles.first()
		val top = roofHeight(level, centerXOf(first), centerZOf(first)) ?: return false

		// The fairy room has no marker; Odin treats it as unturned.
		if (data?.name == "Fairy") {
			rotation = RoomRotation.SOUTH
			clayPos = BlockPos(centerXOf(first) + RoomRotation.SOUTH.dx, top, centerZOf(first) + RoomRotation.SOUTH.dz)
			rotationChecked = true
			return true
		}

		// Every outside corner of every tile: the marker is on a corner of the whole shape, never a
		// seam. The same corners Devonian and Skytils use, so coordinates agree.
		for (tile in tiles) {
			for (candidate in RoomRotation.entries) {
				val stepX = Integer.signum(candidate.dx)
				val stepZ = Integer.signum(candidate.dz)
				if (tiles.any { it.x == tile.x + stepX && it.z == tile.z }) continue
				if (tiles.any { it.x == tile.x && it.z == tile.z + stepZ }) continue

				val cornerX = centerXOf(tile) + candidate.dx
				val cornerZ = centerZOf(tile) + candidate.dz
				// Read at the roof height of the first tile's middle, and the corner's own column
				// too (Supertall has a tower).
				val heights = listOfNotNull(top, roofHeight(level, cornerX, cornerZ)).distinct()
				for (y in heights) {
					val probe = BlockPos(cornerX, y, cornerZ)
					if (level.getBlockState(probe).block == Blocks.DYED_TERRACOTTA.blue()) {
						rotation = candidate
						clayPos = probe
						rotationChecked = true
						return true
					}
				}
			}
		}

		// Odin treats every 2×2 as unturned from its north-west corner; once loaded with no marker,
		// so does this.
		if (shape == Shape.S2X2 && tiles.all { loaded(level, centerXOf(it) - 15, centerZOf(it) - 15) && loaded(level, centerXOf(it) + 15, centerZOf(it) + 15) }) {
			val corner = tiles.minWith(compareBy<Vec2i>({ it.x }, { it.z }))
			rotation = RoomRotation.SOUTH
			clayPos = BlockPos(centerXOf(corner) + RoomRotation.SOUTH.dx, top, centerZOf(corner) + RoomRotation.SOUTH.dz)
			rotationChecked = true
			return true
		}

		// Not found: the chunk is still loading, or the room has no marker. A later pass retries.
		return false
	}

	private fun centerXOf(tile: Vec2i): Int = DungeonFloor.WORLD_TOP_LEFT + tile.x * DungeonFloor.BLOCKS_PER_TILE

	private fun centerZOf(tile: Vec2i): Int = DungeonFloor.WORLD_TOP_LEFT + tile.z * DungeonFloor.BLOCKS_PER_TILE

	/** The top of the roof at the room's middle, skipping gold (some roofs have gold on top). */
	private fun loaded(level: Level, x: Int, z: Int): Boolean =
		level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))

	private fun roofHeight(level: Level, x: Int, z: Int): Int? {
		val cursor = BlockPos.MutableBlockPos()
		for (y in ROOF_SEARCH_TOP downTo ROOF_SEARCH_BOTTOM) {
			val state = level.getBlockState(cursor.set(x, y, z))
			if (state.isAir || state.block == Blocks.GOLD_BLOCK) continue
			return y
		}
		return null
	}

	fun getRealCoords(pos: BlockPos): BlockPos? {
		val clay = clayPos ?: return null
		val rot = rotation ?: return null
		return pos.rotateAroundNorth(rot).offset(clay.x, 0, clay.z)
	}

	fun getRelativeCoords(pos: BlockPos): BlockPos? {
		val clay = clayPos ?: return null
		val rot = rotation ?: return null
		return pos.subtract(clay.atY(0)).rotateToNorth(rot)
	}

	val rotationUnavailable: Boolean
		get() = rotationChecked && rotation == null

	var specialTile = false

	var guess: List<Type> = emptyList()

	/** Secrets found in this room, from the action bar while you're in it. Kept after you leave. */
	var foundSecrets = 0

	/** Whether [foundSecrets] was ever read (or shared by a teammate), not just the starting 0. */
	var secretsRead = false

	enum class State {
		GREEN,
		CLEARED,
		FAILED,
		DISCOVERED,
		UNOPENED,
		UNDISCOVERED,
	}

	enum class Type {
		BLOOD,
		CHAMPION,
		ENTRANCE,
		FAIRY,
		NORMAL,
		PUZZLE,
		RARE,
		TRAP,
		UNKNOWN,
	}

	enum class Shape(val tileCount: Int) {
		UNKNOWN(0),
		L(3),
		S1X1(1),
		S2X1(2),
		S3X1(3),
		S4X1(4),
		S2X2(4),
	}

	/** Rooms are 16 pixels wide and 4 apart on the drawn map. */
	fun placementOf(tile: Vec2i): Vec2i = Vec2i(tile.x * (16 + 4), tile.z * (16 + 4))

	fun addTile(tile: Vec2i) {
		if (tiles.none { it == tile }) tiles.add(tile)
	}

	val hidden: Boolean get() = state == State.UNDISCOVERED

	val unopened: Boolean get() = state == State.UNOPENED

	/**
	 * Whether [render] draws anything right now. Doors check this, since a room can be known but
	 * not drawn yet.
	 */
	/**
	 * Whether the room's identity can be shown: a named room is still grey to the run until opened,
	 * so its name and the Prince crown wait unless the floor is revealed.
	 */
	fun isIdentified(revealAll: Boolean): Boolean =
		data != null && (revealAll || (!hidden && !unopened))

	fun isDrawn(revealAll: Boolean): Boolean {
		if (tiles.isEmpty()) return false
		if (revealAll) return true
		if (hidden) return false
		if (unopened) return entryTile != null
		return true
	}

	/**
	 * Reads progress from the tile's colour: white opened, green all secrets, brown entered, puzzle
	 * colours for failure. Returns true when the room moved on.
	 */
	fun updateState(tile: Vec2i, color: Int): Boolean {
		// Golden Oasis shows cleared at 1/1 and drops back to white for the two extra secrets, so
		// green sticks.
		if (state == State.GREEN && data?.name == "Golden Oasis") return false

		val previous = state
		state = when (color) {
			0 -> {
				// The entrance is seen before the map arrives, so it's never undiscovered.
				if (type == Type.ENTRANCE) return false
				// A room walked into ahead of the map isn't pushed back by a late update.
				if (state != State.UNDISCOVERED) return false
				State.UNDISCOVERED
			}
			34 -> State.CLEARED
			18 -> when (type) {
				Type.BLOOD -> State.DISCOVERED
				Type.PUZZLE -> State.FAILED
				else -> state
			}
			// A green check means every secret is found, whatever the count saw (Odin's rule).
			30 -> if (type == Type.ENTRANCE) {
				State.DISCOVERED
			} else {
				// Not the Golden Oasis, green at the first of its three.
				if (data?.name != "Golden Oasis") data?.secrets?.let {
					if (it > foundSecrets) foundSecrets = it
					secretsRead = true
				}
				State.GREEN
			}
			85, 119 -> {
				entryTile = tile
				specialTile = tile.x == RoomPrediction.column
				if (state != State.UNDISCOVERED) return false
				State.UNOPENED
			}
			else -> State.DISCOVERED
		}

		return state != previous
	}

	/**
	 * Marks the room entered without waiting for the map item, and puts the rooms behind its doors
	 * on the map as shut.
	 */
	fun enterNow(): Boolean {
		if (type == Type.ENTRANCE) return false
		if (state != State.UNDISCOVERED && state != State.UNOPENED) return false

		state = State.DISCOVERED
		doors.forEach { door ->
			door.rooms.forEach { neighbour ->
				if (neighbour === this || neighbour.state != State.UNDISCOVERED) return@forEach
				neighbour.state = State.UNOPENED
				neighbour.entryTile = neighbour.tiles.minByOrNull { tile ->
					door.tiles().minOf { (it.x - tile.x) * (it.x - tile.x) + (it.z - tile.z) * (it.z - tile.z) }
				}
			}
		}
		return true
	}

	/** The room's colours; several when it's a guess. */
	private fun colors(revealAll: Boolean): IntArray {
		if (!revealAll && unopened && guess.isNotEmpty()) {
			return IntArray(guess.size) { DungeonMapColors.darken(DungeonMapColors.room(guess[it])) }
		}

		if (type == Type.UNKNOWN) return intArrayOf(DungeonMapColors.unexplored())
		val base = DungeonMapColors.room(type)

		// Unvisited rooms are drawn dim so the map shows the route taken, except when the floor is
		// revealed, where dimming would hide trap rooms.
		if (!revealAll && (state == State.UNDISCOVERED || state == State.UNOPENED)) {
			// dtMap's way: an unopened room is grey; blood keeps a dimmed red.
			return intArrayOf(if (type == Type.BLOOD) DungeonMapColors.darken(base) else DungeonMapColors.unexplored())
		}
		return intArrayOf(base)
	}

	/** Draws the room. Unless [revealAll], only what's been seen; a shut room is drawn as its entry tile. */
	fun render(context: GuiGraphicsExtractor, revealAll: Boolean) {
		if (tiles.isEmpty()) return

		val colors = colors(revealAll)

		if (!revealAll && (hidden || unopened)) {
			if (hidden) return
			val entry = entryTile ?: return
			val at = placementOf(entry)
			renderGuess(context, at, colors)
			return
		}

		val placements = tiles.map(::placementOf)
		val topLeft = placements.minBy { it.x * 1000 + it.z }
		val bottomRight = placements.maxBy { it.x * 1000 + it.z }
		val color = colors[0]

		if (shape == Shape.L && tiles.size > 2) {
			// An L is drawn as its two arms, not its bounding box.
			placements.forEach { context.fill(it.x, it.z, it.x + 16, it.z + 16, color) }
			placements.forEach { from ->
				placements.forEach { to ->
					if (from === to) return@forEach
					if (from.x == to.x || from.z == to.z) {
						context.fill(
							minOf(from.x, to.x),
							minOf(from.z, to.z),
							maxOf(from.x, to.x) + 16,
							maxOf(from.z, to.z) + 16,
							color,
						)
					}
				}
			}
			return
		}

		context.fill(topLeft.x, topLeft.z, bottomRight.x + 16, bottomRight.z + 16, color)
	}

	private fun renderGuess(context: GuiGraphicsExtractor, at: Vec2i, colors: IntArray) {
		when (colors.size) {
			1 -> context.fill(at.x, at.z, at.x + 16, at.z + 16, colors[0])
			2 -> {
				context.fill(at.x, at.z, at.x + 16, at.z + 8, colors[0])
				context.fill(at.x, at.z + 8, at.x + 16, at.z + 16, colors[1])
			}
			else -> {
				context.fill(at.x, at.z, at.x + 16, at.z + 5, colors[0])
				context.fill(at.x, at.z, at.x + 5, at.z + 10, colors[0])
				context.fill(at.x + 10, at.z + 5, at.x + 16, at.z + 16, colors[1])
				context.fill(at.x, at.z + 10, at.x + 16, at.z + 16, colors[1])
				context.fill(at.x + 5, at.z + 5, at.x + 11, at.z + 11, colors[2])
			}
		}
	}

	/** Writes the room's name across it, a word per line. */
	/**
	 * The map's name for the room. The two blaze rooms are shown as Higher Or Lower, as players
	 * call it; the solvers still need the room data's two names.
	 */
	fun displayName(): String? {
		// The blood room isn't in the room data.
		if (type == Type.BLOOD) return "Blood Camp"
		return data?.name?.let { mapNames[it] ?: it }
	}

	fun renderName(
		context: GuiGraphicsExtractor,
		scale: Float,
		revealAll: Boolean,
		extraLine: String? = null,
	): Boolean {
		val name = (if (isIdentified(revealAll)) displayName() else null)
			?: unscannedName(revealAll)
			?: return false
		// The entrance is obvious from its colour, so it gets no name.
		if (type == Type.ENTRANCE) return false

		renderCentered(context, scale, name.split(" ") + listOfNotNull(extraLine))
		return true
	}

	/**
	 * A name from the map colour alone, for rooms the scan hasn't reached: an orange room is always
	 * a trap, so it can be called one.
	 */
	private fun unscannedName(revealAll: Boolean): String? {
		if (data != null || type != Type.TRAP) return null
		if (!revealAll && (hidden || unopened)) return null
		return "Trap"
	}

	/**
	 * True once the fairy room has been passed: both of its wither doors are open. It never turns
	 * green on its own.
	 */
	val fairyPassed: Boolean
		get() {
			val wither = doors.filter { it.type == DungeonDoor.Type.WITHER }
			return wither.size >= 2 && wither.all { it.opened }
		}

	/**
	 * The colour a room's name is written in. Most rooms go entered, cleared, then green with all
	 * secrets. Puzzles and the fairy room only have done and not done.
	 */
	private fun labelColor(): Int {
		if (state == State.FAILED) return 0xFFFF5555.toInt()

		if (type == Type.FAIRY) return if (fairyPassed) 0xFF55FF55.toInt() else 0xFFFFFFFF.toInt()
		if (type == Type.PUZZLE) return if (state == State.GREEN) 0xFF55FF55.toInt() else 0xFFA0A0A0.toInt()
		// Blood is two states: the Watcher has let you through or not.
		if (type == Type.BLOOD) {
			return if (state == State.GREEN || state == State.CLEARED) {
				0xFF55FF55.toInt()
			} else {
				0xFFA0A0A0.toInt()
			}
		}

		// Mini boss rooms and some normal ones have no secrets, so clearing finishes them.
		if (type == Type.CHAMPION || (data?.secrets ?: 0) == 0) {
			return if (state == State.CLEARED || state == State.GREEN) {
				0xFF55FF55.toInt()
			} else {
				0xFFA0A0A0.toInt()
			}
		}

		// Hypixel's colour only reports the mobs; counting secrets too shows a half-finished room.
		val clearDone = state == State.CLEARED || state == State.GREEN
		val total = data?.secrets ?: 0
		// An unnamed room reports no secrets, and 0 of 0 shouldn't read as finished.
		val secretsDone = total > 0 && foundSecrets >= total

		return when {
			state == State.GREEN || (clearDone && secretsDone) -> 0xFF55FF55.toInt()
			clearDone || secretsDone -> 0xFFFFFFFF.toInt()
			state == State.DISCOVERED -> 0xFFA0A0A0.toInt()
			else -> 0xFFA0A0A0.toInt()
		}
	}

	/**
	 * Writes lines across the room's middle in its progress colour. Each line is the font's height
	 * times [scale].
	 */
	fun renderCentered(context: GuiGraphicsExtractor, scale: Float, lines: List<String>) {
		if (lines.isEmpty()) return

		val font = Minecraft.getInstance().font
		val pose = context.pose()
		val center = center()
		val lineHeight = font.lineHeight * scale
		val top = center.z - lines.size * lineHeight / 2f

		val color = labelColor()

		lines.forEachIndexed { index, line ->
			pose.pushMatrix()
			pose.translate(center.x.toFloat(), top + index * lineHeight)
			pose.scale(scale, scale)
			context.centeredText(font, line, 0, 0, color)
			pose.popMatrix()
		}
	}

	/**
	 * The room's middle in drawn coordinates. An L uses its corner tile, since its bounding box
	 * middle is outside the room.
	 */
	fun center(): Vec2i {
		if (shape == Shape.L && tiles.size > 2) {
			// Names are written across, so an L centres on the arm that shares a row.
			val arm = tiles.groupBy { it.z }.values.firstOrNull { it.size > 1 }
			if (arm != null) {
				val placements = arm.map(::placementOf)
				return Vec2i(
					(placements.minOf { it.x } + placements.maxOf { it.x } + 16) / 2,
					placements.first().z + 8,
				)
			}
		}

		val placements = tiles.map(::placementOf)
		val topLeft = placements.minBy { it.x * 1000 + it.z }
		val bottomRight = placements.maxBy { it.x * 1000 + it.z }
		return Vec2i((topLeft.x + bottomRight.x + 16) / 2, (topLeft.z + bottomRight.z + 16) / 2)
	}

	/** The top-left of the bottom-right tile, for corner decorations; a shut room's is its entry tile. */
	fun bottomRightTile(): Vec2i {
		if (unopened) entryTile?.let { return placementOf(it) }
		return tiles.map(::placementOf).maxBy { it.x * 1000 + it.z }
	}

	fun markCenter(): Vec2i {
		if (unopened) {
			val entry = entryTile ?: return center()
			val at = placementOf(entry)
			return Vec2i(at.x + 8, at.z + 8)
		}
		return center()
	}
}

/** Rooms whose data name isn't what players call them. */
private val mapNames = mapOf(
	"Higher Blaze" to "Higher Lower",
	"Lower Blaze" to "Higher Lower",
)

/** How high to start looking for a room's roof. Odin's 140 is under Supertall's roof. */
private const val ROOF_SEARCH_TOP = 200
private const val ROOF_SEARCH_BOTTOM = 12
