package imicro.cryptic.debug

import imicro.cryptic.feature.NucleusQol
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * `/cryptic debug block`: everything about the block you're looking at. For a barrier, also the
 * whole barrier room it belongs to (how crystal spaces are found, since barriers are invisible).
 */
object BlockInfo {
	fun describe(): List<String> {
		val client = Minecraft.getInstance()
		val level = client.level ?: return listOf("Not in a world")
		val player = client.player ?: return listOf("Not in a world")
		val hit = client.hitResult
		val pos: BlockPos = if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) hit.blockPos else player.blockPosition().below()
		val looking = hit is BlockHitResult && hit.type == HitResult.Type.BLOCK
		val state = level.getBlockState(pos)

		val lines = mutableListOf(
			"${if (looking) "Looking at" else "Standing on"} ${pos.x} ${pos.y} ${pos.z}, " +
				"%.1f blocks away".format(Math.sqrt(net.minecraft.world.phys.Vec3.atCenterOf(pos).distanceToSqr(player.eyePosition))),
			"Block: ${BuiltInRegistries.BLOCK.getKey(state.block)}",
		)
		val values = state.values.toList()
		if (values.isNotEmpty()) {
			lines += "State: " + values.joinToString(", ") { "${it.property().name}=${it.value()}" }
		}
		level.getBlockEntity(pos)?.let { lines += "Block entity: ${BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(it.type)}" }
		if (looking) lines += "Face: ${hit.direction.name.lowercase()}"
		lines += "Light: block ${level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos.above())}, " +
			"sky ${level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above())}"
		if (state.`is`(Blocks.BARRIER) || BuiltInRegistries.BLOCK.getKey(state.block).path.contains("glass")) {
			lines += NucleusQol.describeBarrierAt(pos)
		}
		lines += "Island: ${SkyblockLocation.area ?: "unknown"}, zone: ${SkyblockLocation.zone() ?: "unknown"}"
		return lines
	}
}
