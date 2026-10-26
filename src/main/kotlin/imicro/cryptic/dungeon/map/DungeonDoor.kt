package imicro.cryptic.dungeon.map

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A doorway between two rooms, drawn in the gap between their tiles. Identified by its left or
 * upper tile and direction, which both the map item and the world scan agree on. Ported from dtMap.
 */
class DungeonDoor(val tile: Vec2i, val horizontal: Boolean, var type: Type) {
	enum class Type {
		NORMAL,
		WITHER,
		BLOOD,
		ENTRANCE,
	}

	val rooms: MutableSet<DungeonRoom> = mutableSetOf()

	/**
	 * True once the doorway is open. Read from the world, since the map repaints an opened wither
	 * door as a normal one.
	 */
	var opened = false

	val worldX: Int
		get() = DungeonFloor.WORLD_TOP_LEFT + tile.x * DungeonFloor.BLOCKS_PER_TILE +
			if (horizontal) DungeonFloor.BLOCKS_PER_TILE / 2 else 0

	val worldZ: Int
		get() = DungeonFloor.WORLD_TOP_LEFT + tile.z * DungeonFloor.BLOCKS_PER_TILE +
			if (horizontal) 0 else DungeonFloor.BLOCKS_PER_TILE / 2

	fun tiles(): List<Vec2i> = listOf(tile, if (horizontal) tile.add(1, 0) else tile.add(0, 1))

	val seen: Boolean get() = rooms.any { !it.hidden }

	/**
	 * True while the door leads somewhere unexplored. Wither doors appear on the map long before
	 * the room behind them.
	 */
	private val leadsNowhereKnown: Boolean get() = rooms.any { it.hidden || it.unopened }

	fun markEntrance(entranceTiles: List<Vec2i>) {
		if (type != Type.NORMAL) return
		if (tiles().any { candidate -> entranceTiles.any { it == candidate } }) type = Type.ENTRANCE
	}

	fun render(context: GuiGraphicsExtractor, thickness: Int, revealAll: Boolean) {
		// Drawn only between two rooms that are both on the map; otherwise it is a gap inside one
		// room or a sliver announcing an undiscovered room.
		if (rooms.size < 2) return
		if (rooms.any { !it.isDrawn(revealAll) }) return

		val inset = (16 - thickness) / 2
		val x = tile.x * 20
		val z = tile.z * 20

		if (horizontal) {
			context.fill(x + 16, z + inset, x + 20, z + inset + thickness, color(revealAll))
		} else {
			context.fill(x + inset, z + 16, x + inset + thickness, z + 20, color(revealAll))
		}
	}

	/**
	 * A door's colour, dtMap's way. While either side is unopened, a normal door is unopened grey;
	 * wither and blood doors keep their colour, dimmed while locked. Once both sides are known, a
	 * normal door takes the colour of the special room it leads to. With the floor revealed,
	 * unvisited rooms' doors show their real colour, dimmed.
	 */
	private fun color(revealAll: Boolean): Int {
		val unknown = leadsNowhereKnown
		if (unknown && !revealAll) {
			return when (type) {
				Type.BLOOD -> if (opened) DungeonMapColors.door(Type.BLOOD) else DungeonMapColors.darken(DungeonMapColors.door(Type.BLOOD))
				Type.WITHER -> if (opened) DungeonMapColors.door(Type.WITHER) else DungeonMapColors.darken(DungeonMapColors.door(Type.WITHER))
				else -> DungeonMapColors.unexplored()
			}
		}

		val base = when (type) {
			Type.BLOOD -> DungeonMapColors.door(Type.BLOOD)
			Type.WITHER -> when {
				!opened -> DungeonMapColors.door(Type.WITHER)
				rooms.any { it.type == DungeonRoom.Type.FAIRY } -> DungeonMapColors.room(DungeonRoom.Type.FAIRY)
				else -> DungeonMapColors.door(Type.NORMAL)
			}
			else -> when (rooms.firstOrNull { it.type != DungeonRoom.Type.NORMAL && it.type != DungeonRoom.Type.UNKNOWN }?.type) {
				DungeonRoom.Type.ENTRANCE -> DungeonMapColors.door(Type.ENTRANCE)
				DungeonRoom.Type.BLOOD -> DungeonMapColors.door(Type.BLOOD)
				DungeonRoom.Type.CHAMPION -> DungeonMapColors.room(DungeonRoom.Type.CHAMPION)
				DungeonRoom.Type.PUZZLE -> DungeonMapColors.room(DungeonRoom.Type.PUZZLE)
				DungeonRoom.Type.RARE -> DungeonMapColors.room(DungeonRoom.Type.RARE)
				DungeonRoom.Type.TRAP -> DungeonMapColors.room(DungeonRoom.Type.TRAP)
				DungeonRoom.Type.FAIRY -> DungeonMapColors.room(DungeonRoom.Type.FAIRY)
				else -> DungeonMapColors.door(Type.NORMAL)
			}
		}
		return if (unknown) DungeonMapColors.darken(base) else base
	}
}
