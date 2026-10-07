package imicro.cryptic.dungeon.map

import imicro.cryptic.feature.DungeonMap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * One room on the dungeon map, and the tiles of the grid it covers.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who); the full
 * licence is in `licenses/dtMap-LICENSE.txt`. A room learns what it is from two
 * places: the map item Hypixel hands out says its type and how far it has been
 * cleared, and the world scan in [DungeonWorldScan] says which named room it is.
 * The scan is what lets the map be drawn, and named, before the run starts.
 */
class DungeonRoom(var type: Type, var shape: Shape) {
	val tiles: MutableList<Vec2i> = mutableListOf()
	var state: State = State.UNDISCOVERED

	/** The room this is in `rooms.json`, once the world scan has found it. */
	var data: RoomData? = null

	/** The doors on this room's walls, which is how neighbours are reached. */
	val doors: MutableSet<DungeonDoor> = mutableSetOf()

	/**
	 * The one tile Hypixel paints while a room is still shut.
	 *
	 * A closed room shows on the map only as the tile you can see it from, so
	 * this is where it is drawn and marked while exploring.
	 */
	var entryTile: Vec2i? = null

	/** True once it is certain the room is a single tile, which the guess needs. */
	var isKnown1x1 = false

	/**
	 * Which way this room's schematic is turned, once it has been worked out.
	 *
	 * Odin's method (BSD 3-Clause, Copyright (c) 2025 odtheking): Hypixel
	 * leaves a block of blue terracotta buried at the corner of every room's
	 * roof, on the corner the schematic's own origin landed on. Finding which
	 * of the four corners it is on says how far the room was turned, and the
	 * corner itself is then the point every schematic coordinate is measured
	 * from.
	 *
	 * Larger rooms are answered too, once all of their tiles are known: the
	 * marker is on one outside corner of the whole shape, and Dungeon
	 * Waypoints needs every room's coordinates, not only the puzzles'.
	 */
	var rotation: RoomRotation? = null
		private set

	/** The corner the schematic is measured from, once [resolveRotation] has found it. */
	var clayPos: BlockPos? = null
		private set

	/** True once the rotation has been looked for, found or not. */
	private var rotationChecked = false

	/**
	 * Looks for the marker, at most once per room per floor.
	 *
	 * Cheap to ask repeatedly: a room whose chunks had not arrived the first
	 * time is retried, and one that has been answered never touches the world
	 * again.
	 */
	fun resolveRotation(level: Level): Boolean {
		if (rotation != null) return true
		if (rotationChecked) return false
		if (tiles.isEmpty()) return false
		// A larger room is only looked at once every tile of it is known: with
		// a tile missing, a corner on the inside of the shape looks like an
		// outside one, and the marker could be read off the wrong corner.
		if (shape != Shape.UNKNOWN && tiles.size < shape.tileCount) return false
		if (shape == Shape.UNKNOWN && tiles.size != 1) return false

		val first = tiles.first()
		val top = roofHeight(level, centerXOf(first), centerZOf(first)) ?: return false

		// The fairy room carries no marker; Odin reads it as never turned.
		if (data?.name == "Fairy") {
			rotation = RoomRotation.SOUTH
			clayPos = BlockPos(centerXOf(first) + RoomRotation.SOUTH.dx, top, centerZOf(first) + RoomRotation.SOUTH.dz)
			rotationChecked = true
			return true
		}

		// Every outside corner of every tile, which for one tile is its four.
		// A corner is outside when neither neighbour towards it belongs to the
		// room — the marker sits on the corner of the whole shape, never on a
		// seam between two of its tiles. The same corners Devonian and Skytils
		// check, so their room coordinates line up with these.
		for (tile in tiles) {
			for (candidate in RoomRotation.entries) {
				val stepX = Integer.signum(candidate.dx)
				val stepZ = Integer.signum(candidate.dz)
				if (tiles.any { it.x == tile.x + stepX && it.z == tile.z }) continue
				if (tiles.any { it.x == tile.x && it.z == tile.z + stepZ }) continue

				val cornerX = centerXOf(tile) + candidate.dx
				val cornerZ = centerZOf(tile) + candidate.dz
				// The marker is at the height of the roof, read at the middle of
				// the first tile. A room with a tower in it — Supertall — is taller
				// there than at its corners, so the top of the corner's own column
				// is tried as well.
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

		// Odin never looks for a marker in a 2x2 room: it takes every one as
		// unturned, measured from its north-west corner. Once the whole room
		// has arrived and no corner had a marker, that is the answer here too.
		if (shape == Shape.S2X2 && tiles.all { level.hasChunkAt(centerXOf(it) - 15, centerZOf(it) - 15) && level.hasChunkAt(centerXOf(it) + 15, centerZOf(it) + 15) }) {
			val corner = tiles.minWith(compareBy<Vec2i>({ it.x }, { it.z }))
			rotation = RoomRotation.SOUTH
			clayPos = BlockPos(centerXOf(corner) + RoomRotation.SOUTH.dx, top, centerZOf(corner) + RoomRotation.SOUTH.dz)
			rotationChecked = true
			return true
		}

		// Not found: either the chunk is still arriving or this room does not
		// carry one. Another pass will answer it, and giving up is what the
		// solvers treat as "no coordinates for this room".
		return false
	}

	private fun centerXOf(tile: Vec2i): Int = DungeonFloor.WORLD_TOP_LEFT + tile.x * DungeonFloor.BLOCKS_PER_TILE

	private fun centerZOf(tile: Vec2i): Int = DungeonFloor.WORLD_TOP_LEFT + tile.z * DungeonFloor.BLOCKS_PER_TILE

	/**
	 * The top of the room's roof at its middle.
	 *
	 * Gold is skipped the way Odin skips it: a few rooms have gold sitting on
	 * top of the roof, and measuring from that puts the marker a block out.
	 */
	private fun roofHeight(level: Level, x: Int, z: Int): Int? {
		val cursor = BlockPos.MutableBlockPos()
		for (y in ROOF_SEARCH_TOP downTo ROOF_SEARCH_BOTTOM) {
			val state = level.getBlockState(cursor.set(x, y, z))
			if (state.isAir || state.block == Blocks.GOLD_BLOCK) continue
			return y
		}
		return null
	}

	/** Where a position written in the schematic actually is. */
	fun getRealCoords(pos: BlockPos): BlockPos? {
		val clay = clayPos ?: return null
		val rot = rotation ?: return null
		return pos.rotateAroundNorth(rot).offset(clay.x, 0, clay.z)
	}

	/** What a position in the world is called in the schematic. */
	fun getRelativeCoords(pos: BlockPos): BlockPos? {
		val clay = clayPos ?: return null
		val rot = rotation ?: return null
		return pos.subtract(clay.atY(0)).rotateToNorth(rot)
	}

	/** The middle of a block the schematic names, ready to draw at. */
	fun realCenter(pos: BlockPos): Vec3? = getRealCoords(pos)?.let { Vec3.atCenterOf(it) }

	/** True once the room has been looked at and has no turn to give. */
	val rotationUnavailable: Boolean
		get() = rotationChecked && rotation == null

	/**
	 * The same two conversions for a point rather than a block.
	 *
	 * A line of sight has to be turned into the room's directions before it can
	 * be crossed with anything written in them, and a whole point is lost by
	 * rounding it to a block first.
	 */
	fun getRelativePoint(point: Vec3): Vec3? {
		val clay = clayPos ?: return null
		val rot = rotation ?: return null
		// Turned about the middle of a block rather than its corner. A block's
		// position rotates as a lattice point, so a continuous point has to be
		// shifted half a block, turned, and shifted back for the two to agree
		// about where the middle of a block is.
		return point
			.subtract(clay.x + 0.5, 0.0, clay.z + 0.5)
			.rotateToNorth(rot)
			.add(0.5, 0.0, 0.5)
	}

	/** A direction turned into the room's own, which has no position to shift. */
	fun getRelativeDirection(direction: Vec3): Vec3? {
		val rot = rotation ?: return null
		return direction.rotateToNorth(rot)
	}

	/** True while this room sits in the floor's column of one-tile rooms. */
	var specialTile = false

	/** What this room could be, when [RoomPrediction] can narrow it down. */
	var guess: List<Type> = emptyList()

	/**
	 * How many of this room's secrets have been found.
	 *
	 * Hypixel counts them in the action bar while you stand in the room, which
	 * is the only place the number exists; [DungeonMap] copies it onto whichever
	 * room the player is in, and it stays after they leave.
	 */
	var foundSecrets = 0

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

	/** Where a grid tile sits on the drawn map: rooms are 16 wide, 4 apart. */
	fun placementOf(tile: Vec2i): Vec2i = Vec2i(tile.x * (16 + 4), tile.z * (16 + 4))

	fun addTile(tile: Vec2i) {
		if (tiles.none { it == tile }) tiles.add(tile)
	}

	/** True while nobody has been able to see this room on the map yet. */
	val hidden: Boolean get() = state == State.UNDISCOVERED

	/** True while the room is on the map but still shut. */
	val unopened: Boolean get() = state == State.UNOPENED

	/**
	 * Whether [render] will actually put anything on the map right now.
	 *
	 * Doors ask this before drawing themselves. A door is only meaningful
	 * between two rooms you can see, and a room can be known to Cryptic without
	 * being drawn — undiscovered while the map is revealing as you explore, or
	 * shut without Hypixel having said yet which tile it is seen from.
	 */
	/**
	 * Whether the map has established what this room actually is.
	 *
	 * A room the world scan has named is still a grey square to the run until
	 * somebody opens it, so anything that gives its identity away — its name,
	 * the Prince crown — waits for that, unless the whole floor is being shown.
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
	 * Reads the room's progress from the colour Hypixel painted its tile.
	 *
	 * The map item says everything: white is opened, green is cleared with all
	 * secrets, brown is entered, and the puzzle colours double as failure.
	 * Returns true when this actually moved the room on.
	 */
	fun updateState(tile: Vec2i, color: Int): Boolean {
		// Golden Oasis is marked cleared at 1/1 and drops back to white on the
		// two extra secrets, so once it is green it stays green.
		if (state == State.GREEN && data?.name == "Golden Oasis") return false

		val previous = state
		state = when (color) {
			0 -> {
				// You are standing in the entrance room before Hypixel hands out
				// the map, and you can see it, so it is never undiscovered.
				if (type == Type.ENTRANCE) return false
				// A room walked into ahead of the map item must not be pushed
				// back to undiscovered by the update that has not caught up.
				if (state != State.UNDISCOVERED) return false
				State.UNDISCOVERED
			}
			34 -> State.CLEARED
			18 -> when (type) {
				Type.BLOOD -> State.DISCOVERED
				Type.PUZZLE -> State.FAILED
				else -> state
			}
			30 -> if (type == Type.ENTRANCE) State.DISCOVERED else State.GREEN
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
	 * Marks the room entered without waiting for the map item to say so, and
	 * puts the rooms behind its doors on the map as closed.
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

	/**
	 * The colours the room is drawn in, more than one when it is a guess.
	 *
	 * A closed one-tile room can often be narrowed to two or three
	 * possibilities from what else is already on the map, and showing all of
	 * them beats showing none.
	 */
	private fun colors(revealAll: Boolean): IntArray {
		// With the whole floor shown its real type is already known, so there is
		// nothing a guess could add.
		if (!revealAll && unopened && guess.isNotEmpty()) {
			return IntArray(guess.size) { DungeonMapColors.darken(DungeonMapColors.room(guess[it])) }
		}

		if (type == Type.UNKNOWN) return intArrayOf(DungeonMapColors.unexplored())
		val base = DungeonMapColors.room(type)

		// A room nobody has been in yet is drawn dim, so the map reads as a
		// route travelled rather than a flat picture of the floor. Showing the
		// whole floor is the one case where that is wrong: a trap room dimmed
		// to half strength is the same brown as an ordinary one, and telling
		// them apart before walking in is the entire point of revealing it.
		if (!revealAll && (state == State.UNDISCOVERED || state == State.UNOPENED)) {
			// dtMap's way: a room nobody has opened is grey, whatever it turns out
			// to be, so the map reads as the route travelled. The blood room keeps
			// a dimmed red, since knowing where it is is half the point.
			return intArrayOf(if (type == Type.BLOOD) DungeonMapColors.darken(base) else DungeonMapColors.unexplored())
		}
		return intArrayOf(base)
	}

	/**
	 * Draws the room. When [revealAll] is off only what has been seen is shown,
	 * and a closed room is drawn as the single tile it is visible from.
	 */
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
			// An L never fills its bounding box, so it is drawn as the two arms
			// that actually exist rather than as one rectangle.
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

	/** Splits one tile between the two or three types the room could still be. */
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

	/**
	 * Writes the room's name across it, one word per line.
	 *
	 * The name is the whole point of the world scan: it is what turns a brown
	 * square into "Water Board" while you are still deciding where to go.
	 */
	/**
	 * What the map calls this room, which is not always what the room data
	 * does.
	 *
	 * The blaze puzzle is built as two rooms, Higher Blaze and Lower Blaze, and
	 * the solvers need to tell them apart because the order to shoot in is the
	 * opposite in each. Nobody calls them that: in the tab list and in
	 * conversation the puzzle is Higher Or Lower, so that is what goes on the
	 * map, with the room data left alone underneath.
	 */
	fun displayName(): String? {
		// The blood room is not in the room data, so it has no name to look up
		// and one is given here instead.
		if (type == Type.BLOOD) return "Blood Camp"
		return data?.name?.let { mapNames[it] ?: it }
	}

	fun renderName(
		context: GuiGraphicsExtractor,
		scale: Float,
		revealAll: Boolean,
		extraLine: String? = null,
	): Boolean {
		if (!isIdentified(revealAll)) return false
		val name = displayName() ?: return false
		// The entrance is obvious from its colour, and a name over it says less
		// than the mark it would replace. The blood room keeps its name: it is
		// the one room whose going green is worth reading at a glance, and the
		// checkmark it used to wear said the same thing in less space.
		if (type == Type.ENTRANCE) return false

		// One word per line: room names are wide and tiles are not.
		renderCentered(context, scale, name.split(" ") + listOfNotNull(extraLine))
		return true
	}

	/**
	 * True once the fairy room has been passed through.
	 *
	 * A fairy room is never cleared and holds no secrets, so it never turns
	 * green on its own. It sits between two wither doors, the one the party
	 * opens to get in and the one inside that leads on towards blood, and it
	 * is finished when that second one opens. Asking whether any of them is
	 * open answered yes the moment the party walked in, so every one has to be.
	 */
	val fairyPassed: Boolean
		get() {
			val wither = doors.filter { it.type == DungeonDoor.Type.WITHER }
			// Both of them: with only the way in known so far, "all open" would
			// be true the moment the party walked in.
			return wither.size >= 2 && wither.all { it.opened }
		}

	/**
	 * The colour a room's name is written in.
	 *
	 * Most rooms report the ordinary progression — entered, cleared, then green
	 * for every secret found. Puzzles and the fairy room have only two states
	 * worth telling apart, done and not, so they stay grey until they are
	 * finished rather than passing through white on the way.
	 */
	private fun labelColor(): Int {
		if (state == State.FAILED) return 0xFFFF5555.toInt()

		// White once entered, green once the wither door inside has opened.
		if (type == Type.FAIRY) return if (fairyPassed) 0xFF55FF55.toInt() else 0xFFFFFFFF.toInt()
		if (type == Type.PUZZLE) return if (state == State.GREEN) 0xFF55FF55.toInt() else 0xFFA0A0A0.toInt()
		// The blood room is the same two states as a puzzle: the Watcher has
		// let you through, or he has not.
		if (type == Type.BLOOD) {
			return if (state == State.GREEN || state == State.CLEARED) {
				0xFF55FF55.toInt()
			} else {
				0xFFA0A0A0.toInt()
			}
		}

		// A mini boss room holds no secrets, and neither do a handful of the
		// ordinary ones. Clearing them *is* finishing them, so they skip the
		// white the two-step rooms pass through and go straight to green.
		if (type == Type.CHAMPION || (data?.secrets ?: 0) == 0) {
			return if (state == State.CLEARED || state == State.GREEN) {
				0xFF55FF55.toInt()
			} else {
				0xFFA0A0A0.toInt()
			}
		}

		// A room is two jobs, and Hypixel's own colour only reports one of them:
		// its map stays grey until the mobs are dead, however many secrets have
		// been found. Counting the secrets as well is what lets a room that is
		// half finished say so.
		val clearDone = state == State.CLEARED || state == State.GREEN
		val total = data?.secrets ?: 0
		// A room whose name is not known yet reports no secrets, and "none found
		// out of none" would otherwise read as finished.
		val secretsDone = total > 0 && foundSecrets >= total

		return when {
			state == State.GREEN || (clearDone && secretsDone) -> 0xFF55FF55.toInt()
			clearDone || secretsDone -> 0xFFFFFFFF.toInt()
			state == State.DISCOVERED -> 0xFFA0A0A0.toInt()
			else -> 0xFFA0A0A0.toInt()
		}
	}

	/**
	 * Writes lines across the middle of the room, in the colour of its progress.
	 *
	 * A line is drawn at [scale], so on the map it takes up the font's height
	 * multiplied by it. Dividing instead spread a one-word name half a tile
	 * above the room it belongs to.
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
	 * The middle of the room in drawn coordinates, where its mark and name go.
	 *
	 * An L is measured from its corner tile instead, because the middle of its
	 * bounding box is the one place the room is not.
	 */
	fun center(): Vec2i {
		if (shape == Shape.L && tiles.size > 2) {
			// An L is two arms around a corner, and a name is written across
			// rather than down: the arm sharing a row is the one it fits on.
			// Centring on the corner tile instead reads as a name on a 1x1
			// sitting next to the room it belongs to.
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

	/**
	 * The top-left of the room's bottom-right tile, which corner decorations
	 * hang off. A closed room is only drawn on its entry tile, so that is its
	 * only corner.
	 */
	fun bottomRightTile(): Vec2i {
		if (unopened) entryTile?.let { return placementOf(it) }
		return tiles.map(::placementOf).maxBy { it.x * 1000 + it.z }
	}

	/** Where a mark goes: the entry tile while the room is still shut. */
	fun markCenter(): Vec2i {
		if (unopened) {
			val entry = entryTile ?: return center()
			val at = placementOf(entry)
			return Vec2i(at.x + 8, at.z + 8)
		}
		return center()
	}
}

/** Rooms whose data name is not the name anybody uses for them. */
private val mapNames = mapOf(
	"Higher Blaze" to "Higher Lower",
	"Lower Blaze" to "Higher Lower",
)

/**
 * How far down a room's roof is looked for. Odin starts at 140, which is under
 * Supertall's roof (its chests are at 142), so that room never found its
 * marker and had no coordinates.
 */
private const val ROOF_SEARCH_TOP = 200
private const val ROOF_SEARCH_BOTTOM = 12
