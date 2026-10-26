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
 * Which puzzle you're in, and how its room is turned. Solvers work in the room's own schematic
 * coordinates, which are the same in every copy of the room.
 */
object PuzzleRooms {
	fun room(): DungeonRoom? = DungeonMap.currentRoom()

	fun name(): String? = room()?.data?.name

	val clearing: Boolean
		get() = DungeonLocation.inDungeon && !DungeonRun.inBoss && !DungeonRun.ended

	fun inside(puzzle: String): Boolean = named(puzzle) != null

	/** The room, only when it is the named puzzle and its rotation is known. */
	fun named(puzzle: String): DungeonRoom? {
		if (!clearing) return null
		val room = room() ?: return null
		if (room.data?.name != puzzle) return null
		val level = Minecraft.getInstance().level ?: return null
		if (!room.resolveRotation(level)) return null
		return room
	}

	fun real(puzzle: String, pos: BlockPos): BlockPos? = named(puzzle)?.getRealCoords(pos)

	fun center(puzzle: String, pos: BlockPos): Vec3? = real(puzzle, pos)?.let(Vec3::atCenterOf)

	fun box(puzzle: String, pos: BlockPos): AABB? = real(puzzle, pos)?.let { AABB(it) }

	val inPuzzle: Boolean
		get() = clearing && room()?.type == DungeonRoom.Type.PUZZLE
}
