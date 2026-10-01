package imicro.cryptic.feature

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.WebBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Works out where an Instant Transmission lands.
 *
 * Ported from NoammAddons' `InstantTransmissionHelper` (CC0, Noamm9), line for
 * line: the rules here are Hypixel's, found by its author by trial, and there
 * is nothing to improve by rewriting them. The teleport steps forward a block
 * at a time along the look and stops at the last place a player fits, with a
 * handful of special cases for skimming along a floor — and Wither Impact's
 * teleport is the same walk with a different length.
 *
 * Like the etherwarp ray, this is a prediction: it says where the camera may
 * go early, and the server's own answer is what actually moves the player.
 */
object InstantTransmissionHelper {
	/** Cast from the 1.8 eye height, like every other teleport Hypixel does. */
	private const val EYE_HEIGHT = 1.62

	/** The feet's position after teleporting [distance] blocks, or null if it would not move. */
	fun predict(distance: Double, feet: Vec3, look: Vec3): Vec3? {
		val level = Minecraft.getInstance().level ?: return null
		val start = Vec3(feet.x, feet.y + EYE_HEIGHT, feet.z)
		val travelled = raycast(distance, look, start, level) ?: return null

		val end = start.add(travelled)
		// Hypixel sets you down in the middle of the block, standing on its floor.
		val eyes = Vec3(
			roundToCenter(end.x),
			ceil(end.y) + EYE_HEIGHT - 1,
			roundToCenter(end.z),
		)
		return Vec3(eyes.x, eyes.y - EYE_HEIGHT, eyes.z)
	}

	private fun raycast(distance: Double, direction: Vec3, start: Vec3, level: Level): Vec3? {
		val xDiagonal = if (direction.x > 0) BlockPos(1, 0, 0) else BlockPos(-1, 0, 0)
		val zDiagonal = if (direction.z > 0) BlockPos(0, 0, 1) else BlockPos(0, 0, -1)
		var closeFloorY = Int.MAX_VALUE

		for (offset in 0..distance.toInt()) {
			val pos = start.add(direction.scale(offset.toDouble()))
			val check = BlockPos.containing(pos)

			if (!isPassable(level, check)) {
				return if (offset == 0) null else direction.scale((offset - 1).toDouble())
			}

			if (!isPassable(level, check.above())) {
				if (offset == 0) {
					val justAhead = start.add(direction.scale(0.2))
					if ((justAhead.y - floor(justAhead.y)) <= 0.495) continue
					return null
				}
				return direction.scale((offset - 1).toDouble())
			}

			if (offset != 0 && direction.x < 0 && isFloor(level, check.east()) &&
				isFloor(level, BlockPos.containing(pos.subtract(direction)).offset(zDiagonal))
			) {
				return direction.scale((offset - 1).toDouble())
			}
			if (offset != 0 && direction.z < 0 && direction.x < 0 && isFloor(level, check.south()) &&
				isFloor(level, BlockPos.containing(pos.subtract(direction)).offset(xDiagonal))
			) {
				return direction.scale((offset - 1).toDouble())
			}

			val skimming = isFloor(level, check.below()) ||
				(isFloor(level, check.below().offset(xDiagonal)) && isFloor(level, check.below().offset(zDiagonal)))
			if (skimming && (pos.y - floor(pos.y)) < 0.31) closeFloorY = check.y - 1

			if (closeFloorY == check.y) return direction.scale((offset - 1).toDouble())
		}

		val under = BlockPos.containing(start.add(direction.scale(distance)).subtract(0.0, 1.0, 0.0))
		return if (!isFloor(level, under)) {
			direction.scale(distance).subtract(0.0, 1.0, 0.0)
		} else {
			direction.scale(distance)
		}
	}

	private fun isPassable(level: Level, pos: BlockPos): Boolean {
		val state = level.getBlockState(pos)
		if (state.isAir) return true
		val block = state.block

		return state.getCollisionShape(level, pos).isEmpty ||
			block is CarpetBlock || block is FlowerPotBlock || block is WebBlock ||
			(block == Blocks.SNOW && state.getValue(BlockStateProperties.LAYERS) <= 3)
	}

	private fun isFloor(level: Level, pos: BlockPos): Boolean {
		val state = level.getBlockState(pos)
		val shape = state.getCollisionShape(level, pos)
		if (shape.isEmpty) return false
		return shape.bounds().maxY >= 1 || state.block == Blocks.MUD
	}

	private fun roundToCenter(value: Double): Double = round(value - 0.5) + 0.5
}
