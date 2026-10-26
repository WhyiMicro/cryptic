package imicro.cryptic.device

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * The lights device: six levers in Goldor's tower that must all be on. Marks the ones still off.
 * Inspired by Skyblocker's "Solve Lights On"; the positions are listed as Skyblocker has them.
 */
object LightsOn {
	private val LEVERS = listOf(
		BlockPos(62, 136, 142),
		BlockPos(58, 136, 142),
		BlockPos(60, 135, 142),
		BlockPos(60, 134, 142),
		BlockPos(62, 133, 142),
		BlockPos(58, 133, 142),
	)

	/** The levers still off. A lever that isn't loaded yet is skipped rather than counted as off. */
	fun unlit(): List<BlockPos> {
		val level = Minecraft.getInstance().level ?: return emptyList()
		return LEVERS.filter { pos ->
			val state = level.getBlockState(pos)
			state.`is`(Blocks.LEVER) &&
				state.hasProperty(BlockStateProperties.POWERED) &&
				!state.getValue(BlockStateProperties.POWERED)
		}
	}
}
