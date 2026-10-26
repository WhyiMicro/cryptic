package imicro.cryptic.feature

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.WebBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/**
 * Where an Instant Transmission lands: a block at a time along the look from the eyes, each step
 * puts the player in the middle of the block the eyes reach, at the height their body fits (the
 * eyes' block, or the one under it beneath a low ceiling), set down onto a floor right under it.
 * The walk stops at the first wall on the eyes' line or where no body fits. Wither Impact is the
 * same walk, longer. A prediction only; the server's answer moves the player.
 */
object InstantTransmissionHelper {
	/** Hypixel teleports cast from the 1.8 eye height. */
	private const val EYE_HEIGHT = 1.62

	private const val HALF_WIDTH = 0.3
	private const val BODY_HEIGHT = 1.8

	/** How finely the eyes' line is checked for walls between steps. */
	private const val RAY_STEP = 0.05

	private const val EPSILON = 1e-7

	/** The feet's position after teleporting [distance] blocks, or null if it wouldn't move. */
	fun predict(distance: Double, feet: Vec3, look: Vec3): Vec3? {
		val level = Minecraft.getInstance().level ?: return null
		val eye = feet.add(0.0, EYE_HEIGHT, 0.0)
		var landing: Vec3? = null
		for (step in 1..floor(distance).toInt()) {
			if (wallBetween(level, eye, look, step - 1.0, step.toDouble())) break
			val point = eye.add(look.scale(step.toDouble()))
			val x = floor(point.x) + 0.5
			val z = floor(point.z) + 0.5
			val eyeBlock = floor(point.y)
			val y = when {
				fits(level, x, eyeBlock, z) -> eyeBlock
				fits(level, x, eyeBlock - 1, z) -> eyeBlock - 1
				else -> break
			}
			// Down onto the floor when there's a block of air under you, not further.
			landing = Vec3(x, if (fits(level, x, y - 1, z)) y - 1 else y, z)
		}
		return landing
	}

	/** Whether a solid part of a block sits on the eyes' line between [from] and [to] blocks along it. */
	private fun wallBetween(level: Level, eye: Vec3, look: Vec3, from: Double, to: Double): Boolean {
		var t = from
		while (t <= to) {
			val point = eye.add(look.scale(t))
			val pos = BlockPos.containing(point)
			if (!isPassable(level, pos)) {
				val local = point.subtract(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
				if (level.getBlockState(pos).getCollisionShape(level, pos).toAabbs().any { it.contains(local) }) return true
			}
			t += RAY_STEP
		}
		return false
	}

	/** Whether the player's 0.6 by 1.8 box fits with its feet at [y]. */
	private fun fits(level: Level, x: Double, y: Double, z: Double): Boolean {
		val body = AABB(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + BODY_HEIGHT, z + HALF_WIDTH)
		// One block lower too, for fences and walls reaching above their own block.
		for (bx in floor(body.minX).toInt()..floor(body.maxX - EPSILON).toInt())
			for (by in floor(body.minY).toInt() - 1..floor(body.maxY - EPSILON).toInt())
				for (bz in floor(body.minZ).toInt()..floor(body.maxZ - EPSILON).toInt()) {
					val pos = BlockPos(bx, by, bz)
					if (isPassable(level, pos)) continue
					val solid = level.getBlockState(pos).getCollisionShape(level, pos).toAabbs().any {
						body.intersects(it.move(pos).deflate(EPSILON))
					}
					if (solid) return false
				}
		return true
	}

	private fun isPassable(level: Level, pos: BlockPos): Boolean {
		val state = level.getBlockState(pos)
		if (state.isAir) return true
		val block = state.block

		return state.getCollisionShape(level, pos).isEmpty ||
			block is CarpetBlock || block is FlowerPotBlock || block is WebBlock ||
			(block == Blocks.SNOW && state.getValue(BlockStateProperties.LAYERS) <= 3)
	}
}
