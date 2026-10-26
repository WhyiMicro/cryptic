package imicro.cryptic.dungeon.map

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Which way a room's schematic is turned. Rooms are placed at one of four rotations, and everything
 * known about a room is in schematic coordinates. [dx] and [dz] point from a one-tile room's middle
 * to the corner where the origin (and the marker) ended up. From Odin.
 */
enum class RoomRotation(val dx: Int, val dz: Int) {
	NORTH(15, 15),
	SOUTH(-15, -15),
	WEST(15, -15),
	EAST(-15, 15);

	val forward: Direction
		get() = when (this) {
			NORTH -> Direction.NORTH
			SOUTH -> Direction.SOUTH
			WEST -> Direction.WEST
			EAST -> Direction.EAST
		}
}

fun BlockPos.rotateAroundNorth(rotation: RoomRotation): BlockPos = when (rotation) {
	RoomRotation.NORTH -> BlockPos(-x, y, -z)
	RoomRotation.WEST -> BlockPos(-z, y, x)
	RoomRotation.SOUTH -> BlockPos(x, y, z)
	RoomRotation.EAST -> BlockPos(z, y, -x)
}

fun BlockPos.rotateToNorth(rotation: RoomRotation): BlockPos = when (rotation) {
	RoomRotation.NORTH -> BlockPos(-x, y, -z)
	RoomRotation.WEST -> BlockPos(z, y, -x)
	RoomRotation.SOUTH -> BlockPos(x, y, z)
	RoomRotation.EAST -> BlockPos(-z, y, x)
}

fun Vec3.rotateAroundNorth(rotation: RoomRotation): Vec3 = when (rotation) {
	RoomRotation.NORTH -> Vec3(-x, y, -z)
	RoomRotation.WEST -> Vec3(-z, y, x)
	RoomRotation.SOUTH -> Vec3(x, y, z)
	RoomRotation.EAST -> Vec3(z, y, -x)
}

fun Vec3.rotateToNorth(rotation: RoomRotation): Vec3 = when (rotation) {
	RoomRotation.NORTH -> Vec3(-x, y, -z)
	RoomRotation.WEST -> Vec3(z, y, -x)
	RoomRotation.SOUTH -> Vec3(x, y, z)
	RoomRotation.EAST -> Vec3(-z, y, x)
}
