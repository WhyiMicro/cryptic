package imicro.cryptic.feature

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.BasePressurePlateBlock
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.WallBannerBlock
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
 * Where an etherwarp lands, walking the ray voxel by voxel as the server does and stopping at the
 * first solid block with room above it. Ported from NoammAddons.
 */
object EtherwarpHelper {
	/** Cast from the 1.8 eye height, not the client's. */
	private const val EYE_HEIGHT = 1.62
	private const val SNEAK_OFFSET = 0.35

	/** Aspect of the End reaches 57 blocks, plus one per Tuned Transmission. */
	private const val BASE_RANGE = 57.0

	private const val MAX_STEPS = 1000

	/** Hypixel sets the feet a hair above what they land on. */
	private const val LANDING_GAP = 0.05

	/** [pos] is the block the ray stopped on; valid only if it can be warped to. */
	data class EtherwarpTarget(val valid: Boolean, val pos: BlockPos?) {
		companion object {
			val NONE = EtherwarpTarget(false, null)
		}
	}

	/** The item's etherwarp range, or null if it can't etherwarp. */
	fun etherwarpRange(stack: ItemStack): Double? {
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
		val skyblockId = data.getString("id").orElse("")
		if (skyblockId != "ASPECT_OF_THE_VOID" && skyblockId != "ASPECT_OF_THE_END") return null
		if (data.getByteOr("ethermerge", 0) != 1.toByte()) return null
		return BASE_RANGE + data.getByteOr("tuned_transmission", 0).toInt()
	}

	/**
	 * Where the feet end up after warping onto [pos], at the same height [isValidTarget] checks
	 * headroom from (a fence is stood on from the block above).
	 */
	fun landing(pos: BlockPos): Vec3 =
		Vec3(pos.x + 0.5, feetHeight(pos) + LANDING_GAP, pos.z + 0.5)

	/** The 1.8 sneaking eye height, which the server always casts an etherwarp from. */
	const val SNEAK_EYE_HEIGHT = EYE_HEIGHT - SNEAK_OFFSET

	fun target(position: Vec3, lookAngle: Vec3, range: Double): EtherwarpTarget {
		val player = Minecraft.getInstance().player ?: return EtherwarpTarget.NONE
		return target(position, lookAngle, range, EYE_HEIGHT - if (player.isCrouching) SNEAK_OFFSET else 0.0)
	}

	fun target(position: Vec3, lookAngle: Vec3, range: Double, eyeHeight: Double): EtherwarpTarget {
		val start = position.add(0.0, eyeHeight, 0.0)
		return traverseVoxels(start, start.add(lookAngle.scale(range)))
	}

	/**
	 * Amanatides-Woo traversal: the ray jumps from voxel boundary to boundary, so nothing is
	 * skipped at any angle.
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
			// An unloaded chunk reads as air, so the ray passes through instead of claiming a
			// target the server wouldn't agree with.
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

	private fun isValidTarget(pos: BlockPos, chunk: LevelChunk): Boolean {
		val level = Minecraft.getInstance().level ?: return false
		if (isPassable(pos, chunk)) return false
		if (noFloor(pos, chunk)) return false

		val feetY = feetHeight(pos)
		val feet = BlockPos(pos.x, feetY, pos.z)
		if (!roomFor(feet, chunk)) return false

		val head = BlockPos(pos.x, feetY + 1, pos.z)
		return roomFor(head, chunk)
	}

	/** Blocks that stop the ray but can't be stood on: the warp is refused (ADM's measured rule). */
	private fun noFloor(pos: BlockPos, chunk: LevelChunk): Boolean =
		when (chunk.getBlockState(pos).block) {
			is SignBlock, is WallBannerBlock, is TripWireHookBlock -> true
			else -> false
		}

	/** Whether a landing has room in this cell: nothing to collide with, though a chest is let by. */
	private fun roomFor(pos: BlockPos, chunk: LevelChunk): Boolean {
		val block = chunk.getBlockState(pos).block
		if (block is ChestBlock || block is EnderChestBlock) return true
		return isPassable(pos, chunk) && !blocksFeet(pos, chunk)
	}

	/**
	 * The Y a player stands at after landing on [pos], rounded up off the collision box (a fence is
	 * a block and a half).
	 */
	private fun feetHeight(pos: BlockPos): Int {
		val level = Minecraft.getInstance().level ?: return pos.y + 1
		val shape = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.empty())
		val collisionTop = if (shape.isEmpty) 0.0 else shape.max(Direction.Axis.Y)
		return pos.y + max(1, ceil(collisionTop).toInt())
	}

	/** Blocks a player can walk through but Hypixel won't land you in. */
	private fun blocksFeet(pos: BlockPos, chunk: LevelChunk): Boolean =
		when (chunk.getBlockState(pos).block) {
			is SkullBlock, is WallSkullBlock, is FlowerPotBlock, is LadderBlock, is VineBlock -> true
			else -> false
		}

	private fun isPassable(pos: BlockPos, chunk: LevelChunk): Boolean {
		val level = Minecraft.getInstance().level ?: return true
		val state = chunk.getBlockState(pos)
		return when (state.block) {
			// These have no collision but still stop the ray.
			is SignBlock, is AbstractBannerBlock, is TripWireHookBlock, is BasePressurePlateBlock -> false
			is ButtonBlock, is SkullBlock, is WallSkullBlock, is LadderBlock,
			is BubbleColumnBlock, is FlowerPotBlock, is PistonHeadBlock, is LeverBlock,
			is NetherWartBlock, is ComparatorBlock, is RedstoneTorchBlock, is RepeaterBlock,
			-> true
			else -> state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty
		}
	}
}
