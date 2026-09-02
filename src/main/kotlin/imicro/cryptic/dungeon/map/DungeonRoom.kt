package imicro.cryptic.dungeon.map

import imicro.cryptic.feature.DungeonMap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

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
				if (DungeonMap.instantRoomUpdate.value && state != State.UNDISCOVERED) return false
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
				if (DungeonMap.instantRoomUpdate.value && state != State.UNDISCOVERED) return false
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
		// route travelled rather than a flat picture of the floor.
		return if (state == State.UNDISCOVERED || state == State.UNOPENED) {
			intArrayOf(DungeonMapColors.darken(base))
		} else {
			intArrayOf(base)
		}
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
	fun renderName(
		context: GuiGraphicsExtractor,
		scale: Float,
		revealAll: Boolean,
		extraLine: String? = null,
	): Boolean {
		if (!isIdentified(revealAll)) return false
		val name = data?.name ?: return false
		// The entrance and the blood room are obvious from their colour, and a
		// name over either says less than the mark it would replace.
		if (type == Type.ENTRANCE || type == Type.BLOOD) return false

		// One word per line: room names are wide and tiles are not.
		renderCentered(context, scale, name.split(" ") + listOfNotNull(extraLine))
		return true
	}

	/**
	 * True once the fairy room has been passed through.
	 *
	 * A fairy room is never cleared and holds no secrets, so it never turns
	 * green on its own. What finishes it is the wither door on its far side
	 * being opened, which is the party moving on towards blood.
	 */
	val fairyPassed: Boolean
		get() = doors.any { it.type == DungeonDoor.Type.WITHER && it.opened }

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

		if (type == Type.FAIRY) return if (fairyPassed) 0xFF55FF55.toInt() else 0xFFA0A0A0.toInt()
		if (type == Type.PUZZLE) return if (state == State.GREEN) 0xFF55FF55.toInt() else 0xFFA0A0A0.toInt()

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
