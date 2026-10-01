package imicro.cryptic.dungeon.map

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Which way a room's schematic is turned in the world.
 *
 * From Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). Hypixel builds each
 * Catacombs room from one schematic and drops it in at one of four rotations,
 * so everything anybody knows about a room — where its levers are, which block
 * hides its chest — is written in the schematic's own coordinates and has to
 * be turned before it means anything in the world.
 *
 * [dx] and [dz] point from the middle of a one-tile room at the corner the
 * schematic's origin ended up on, which is where the marker that identifies
 * the rotation is buried.
 */
enum class RoomRotation(val dx: Int, val dz: Int) {
	NORTH(15, 15),
	SOUTH(-15, -15),
	WEST(15, -15),
	EAST(-15, 15);

	/** Which way the schematic's +Z runs once the room has been turned. */
	val forward: Direction
		get() = when (this) {
			NORTH -> Direction.NORTH
			SOUTH -> Direction.SOUTH
			WEST -> Direction.WEST
			EAST -> Direction.EAST
		}
}

/** Turns a position written in the schematic into the world's directions. */
fun BlockPos.rotateAroundNorth(rotation: RoomRotation): BlockPos = when (rotation) {
	RoomRotation.NORTH -> BlockPos(-x, y, -z)
	RoomRotation.WEST -> BlockPos(-z, y, x)
	RoomRotation.SOUTH -> BlockPos(x, y, z)
	RoomRotation.EAST -> BlockPos(z, y, -x)
}

/** Turns a position in the world back into the schematic's directions. */
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
