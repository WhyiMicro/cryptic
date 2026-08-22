package imicro.cryptic.dungeon.map

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A doorway between two rooms, drawn as the gap between their tiles.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who); the full
 * licence is in `licenses/dtMap-LICENSE.txt`. A door is identified by the tile
 * on its left or above it plus the direction it joins, which is the one thing
 * both the map item and the world scan can agree on.
 */
class DungeonDoor(val tile: Vec2i, val horizontal: Boolean, var type: Type) {
	enum class Type {
		NORMAL,
		WITHER,
		BLOOD,
		ENTRANCE,
	}

	/** The rooms this door joins, filled in as they are discovered. */
	val rooms: MutableSet<DungeonRoom> = mutableSetOf()

	/**
	 * True once the doorway is standing open.
	 *
	 * Read from the world rather than the map item, because Hypixel repaints an
	 * opened wither door as an ordinary one and the map then cannot tell the
	 * difference between a door that was never locked and one somebody has
	 * already spent a key on.
	 */
	var opened = false

	/** Where the doorway stands in the world, midway between its two tiles. */
	val worldX: Int
		get() = DungeonFloor.WORLD_TOP_LEFT + tile.x * DungeonFloor.BLOCKS_PER_TILE +
			if (horizontal) DungeonFloor.BLOCKS_PER_TILE / 2 else 0

	val worldZ: Int
		get() = DungeonFloor.WORLD_TOP_LEFT + tile.z * DungeonFloor.BLOCKS_PER_TILE +
			if (horizontal) 0 else DungeonFloor.BLOCKS_PER_TILE / 2

	/** The two grid tiles the door sits between. */
	fun tiles(): List<Vec2i> = listOf(tile, if (horizontal) tile.add(1, 0) else tile.add(0, 1))

	/** True once either room beside the door is on the map. */
	val seen: Boolean get() = rooms.any { !it.hidden }

	/**
	 * True while the door leads somewhere nobody has been.
	 *
	 * Hypixel paints a wither door on the map long before the room behind it is
	 * discovered, so a door can easily be the only thing known about its corner
	 * of the floor.
	 */
	private val leadsNowhereKnown: Boolean get() = rooms.any { it.hidden || it.unopened }

	/** Paints this door in the entrance's colour when it opens onto one. */
	fun markEntrance(entranceTiles: List<Vec2i>) {
		if (type != Type.NORMAL) return
		if (tiles().any { candidate -> entranceTiles.any { it == candidate } }) type = Type.ENTRANCE
	}

	fun render(context: GuiGraphicsExtractor, thickness: Int, revealAll: Boolean) {
		// A door is only worth drawing between two rooms that are themselves on
		// the map. Fewer than two rooms is a gap inside one large room, or a
		// pixel on the map item nothing has been placed against; two rooms where
		// one is not drawn leaves a sliver hanging off the edge of the floor,
		// announcing a door to a room you have not found.
		if (rooms.size < 2) return
		if (rooms.any { !it.isDrawn(revealAll) }) return

		val inset = (16 - thickness) / 2
		val x = tile.x * 20
		val z = tile.z * 20

		// A door lives in the four-pixel gap between two rooms, and is only as
		// wide as the setting asks across the direction it joins.
		if (horizontal) {
			context.fill(x + 16, z + inset, x + 20, z + inset + thickness, color())
		} else {
			context.fill(x + inset, z + 16, x + inset + thickness, z + 20, color())
		}
	}

	/**
	 * A door is dimmed to match the rooms it joins.
	 *
	 * Rooms nobody has reached are drawn dim, so a door drawn at full strength
	 * beside them reads as a piece of map that is somehow further along than
	 * everything around it — which is exactly what it is not.
	 */
	private fun color(): Int {
		val base = DungeonMapColors.door(type)
		return if (leadsNowhereKnown) DungeonMapColors.darken(base) else base
	}
}
