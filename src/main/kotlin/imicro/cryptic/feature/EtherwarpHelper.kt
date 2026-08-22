package imicro.cryptic.feature

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sign

/**
 * Works out where an etherwarp would land.
 *
 * Ported from NoammAddons, which is public domain (CC0). Hypixel does not tell
 * the client where the teleport ends, so the ray has to be walked the same way
 * the server walks it: voxel by voxel, stopping at the first block that is
 * solid enough to stand on and has room for the player above it.
 */
object EtherwarpHelper {
	/** Etherwarp is cast from the 1.8 eye height, not the client's own. */
	private const val EYE_HEIGHT = 1.62
	private const val SNEAK_OFFSET = 0.35

	/** Aspect of the End reaches 57 blocks, plus one per Tuned Transmission. */
	private const val BASE_RANGE = 57.0

	/** A ray that leaves the loaded world stops rather than running forever. */
	private const val MAX_STEPS = 1000

	/** [pos] is the block the ray stopped on, valid only when it can be warped to. */
	data class EtherwarpTarget(val valid: Boolean, val pos: BlockPos?) {
		companion object {
			val NONE = EtherwarpTarget(false, null)
		}
	}

	/** The item's etherwarp range, or null when it cannot etherwarp at all. */
	fun etherwarpRange(stack: ItemStack): Double? {
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
		val skyblockId = data.getString("id").orElse("")
		if (skyblockId != "ASPECT_OF_THE_VOID" && skyblockId != "ASPECT_OF_THE_END") return null
		if (data.getByteOr("ethermerge", 0) != 1.toByte()) return null
		return BASE_RANGE + data.getByteOr("tuned_transmission", 0).toInt()
	}

	fun target(position: Vec3, lookAngle: Vec3, range: Double): EtherwarpTarget {
		val player = Minecraft.getInstance().player ?: return EtherwarpTarget.NONE
		val start = position.add(0.0, EYE_HEIGHT - if (player.isCrouching) SNEAK_OFFSET else 0.0, 0.0)
		return traverseVoxels(start, start.add(lookAngle.scale(range)))
	}

	/**
	 * Amanatides-Woo voxel traversal: the ray jumps straight to the next voxel
	 * boundary instead of being sampled at a fixed step, so nothing between the
	 * player and the target can be missed however the ray is angled.
	 */
	private fun traverseVoxels(start: Vec3, end: Vec3): EtherwarpTarget {
		var x = floor(start.x).toInt()
		var y = floor(start.y).toInt()
		var z = floor(start.z).toInt()

		val endX = floor(end.x).toInt()
		val endY = floor(end.y).toInt()
		val endZ = floor(end.z).toInt()

		val directionX = end.x - start.x
		val directionY = end.y - start.y
		val directionZ = end.z - start.z

		val stepX = sign(directionX).toInt()
		val stepY = sign(directionY).toInt()
		val stepZ = sign(directionZ).toInt()

		val inverseX = if (directionX != 0.0) 1.0 / directionX else Double.MAX_VALUE
		val inverseY = if (directionY != 0.0) 1.0 / directionY else Double.MAX_VALUE
		val inverseZ = if (directionZ != 0.0) 1.0 / directionZ else Double.MAX_VALUE

		val deltaX = abs(inverseX * stepX)
		val deltaY = abs(inverseY * stepY)
		val deltaZ = abs(inverseZ * stepZ)

		var nextX = abs((x + max(stepX, 0) - start.x) * inverseX)
		var nextY = abs((y + max(stepY, 0) - start.y) * inverseY)
		var nextZ = abs((z + max(stepZ, 0) - start.z) * inverseZ)

		val level = Minecraft.getInstance().level ?: return EtherwarpTarget.NONE
		val cursor = BlockPos.MutableBlockPos()

		repeat(MAX_STEPS) {
			cursor.set(x, y, z)
			// An unloaded chunk answers as air, so the ray passes through it
			// rather than reporting a target the server would not agree with.
			val chunk = level.getChunk(
				SectionPos.blockToSectionCoord(x),
				SectionPos.blockToSectionCoord(z),
			)

			if (isValidTarget(cursor, chunk)) return EtherwarpTarget(true, cursor.immutable())
			if (!isPassable(cursor, chunk)) return EtherwarpTarget(false, cursor.immutable())
			if (x == endX && y == endY && z == endZ) {
				return if (chunk.getBlockState(cursor).isAir) {
					EtherwarpTarget.NONE
				} else {
					EtherwarpTarget(false, cursor.immutable())
				}
			}

			when {
				nextX <= nextY && nextX <= nextZ -> {
					nextX += deltaX
					x += stepX
				}
				nextY <= nextZ -> {
					nextY += deltaY
					y += stepY
				}
				else -> {
					nextZ += deltaZ
					z += stepZ
				}
			}
		}

		return EtherwarpTarget.NONE
	}

	/** A block can be warped onto when it is solid and the player fits above it. */
	private fun isValidTarget(pos: BlockPos, chunk: LevelChunk): Boolean {
		val level = Minecraft.getInstance().level ?: return false
		if (isPassable(pos, chunk)) return false

		// Landing happens on top of the block's collision box, so a slab puts the
		// player one block lower than a full block in the same position does.
		val state = chunk.getBlockState(pos)
		val collisionTop = state.getCollisionShape(level, pos, CollisionContext.empty()).max(Direction.Axis.Y)
		val feetY = pos.y + max(1, ceil(collisionTop).toInt())

		val feet = BlockPos(pos.x, feetY, pos.z)
		if (!isPassable(feet, chunk) || blocksFeet(feet, chunk)) return false

		val head = BlockPos(pos.x, feetY + 1, pos.z)
		return isPassable(head, chunk) && !blocksFeet(head, chunk)
	}

	/** Blocks a player can walk through, but Hypixel refuses to land you inside. */
	private fun blocksFeet(pos: BlockPos, chunk: LevelChunk): Boolean =
		when (chunk.getBlockState(pos).block) {
			is SkullBlock, is WallSkullBlock, is FlowerPotBlock, is LadderBlock, is VineBlock -> true
			else -> false
		}

	private fun isPassable(pos: BlockPos, chunk: LevelChunk): Boolean {
		val level = Minecraft.getInstance().level ?: return true
		val state = chunk.getBlockState(pos)
		return when (state.block) {
			// Signs have no collision box but still stop the ray.
			is SignBlock -> false
			is ButtonBlock, is SkullBlock, is WallSkullBlock, is LadderBlock,
			is BubbleColumnBlock, is FlowerPotBlock, is PistonHeadBlock, is LeverBlock,
			is NetherWartBlock, is ComparatorBlock, is RedstoneTorchBlock, is RepeaterBlock,
			-> true
			else -> state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty
		}
	}
}
