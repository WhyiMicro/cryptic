package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.DungeonMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Which puzzle the player is standing in, and where its parts are.
 *
 * Every solver works in the coordinates of the room's own schematic — "the
 * lever at 20, 61, 10" is true of every Water Board ever built — so all of
 * them need the same two things: the room, and the turn that was applied to it
 * when Hypixel dropped it into the floor. Both are asked for here so no solver
 * has to know how either is found.
 */
object PuzzleRooms {
	/** The room the player is in, whatever kind it is. */
	fun room(): DungeonRoom? = DungeonMap.currentRoom()

	/** The name Cryptic's room list gives the room being stood in. */
	fun name(): String? = room()?.data?.name

	/** True while there is a run being cleared, which is when puzzles matter. */
	val clearing: Boolean
		get() = DungeonLocation.inDungeon && !DungeonRun.inBoss && !DungeonRun.ended

	/** True while standing in a room of the named puzzle, with its turn known. */
	fun inside(puzzle: String): Boolean = named(puzzle) != null

	/**
	 * The room, but only when it is the named puzzle and its coordinates can
	 * actually be worked out.
	 *
	 * The rotation is looked for here, which means a room whose chunks were
	 * still arriving when it was walked into answers as soon as they land.
	 */
	fun named(puzzle: String): DungeonRoom? {
		if (!clearing) return null
		val room = room() ?: return null
		if (room.data?.name != puzzle) return null
		val level = Minecraft.getInstance().level ?: return null
		if (!room.resolveRotation(level)) return null
		return room
	}

	/** Where a schematic position is in the world, for the room being stood in. */
	fun real(puzzle: String, pos: BlockPos): BlockPos? = named(puzzle)?.getRealCoords(pos)

	fun center(puzzle: String, pos: BlockPos): Vec3? = real(puzzle, pos)?.let(Vec3::atCenterOf)

	/** The one-block box around a schematic position, ready to draw. */
	fun box(puzzle: String, pos: BlockPos): AABB? = real(puzzle, pos)?.let { AABB(it) }

	/** True while the room being stood in is a puzzle of some kind. */
	val inPuzzle: Boolean
		get() = clearing && room()?.type == DungeonRoom.Type.PUZZLE
}
