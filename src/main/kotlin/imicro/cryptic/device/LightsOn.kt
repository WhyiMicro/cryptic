package imicro.cryptic.device

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * The lights device: six levers in Goldor's tower that all have to be flipped
 * on before the section will open.
 *
 * The device is Skyblocker's "Solve Lights On" — the six lever positions are
 * its (Skyblocker is LGPL-3.0, so its coordinates are used and none of its code
 * is); the drawing and the settings around them are Cryptic's own.
 *
 * There is nothing to solve: a lever is either on or it is not, and the only
 * hard part is that six levers on a dark wall are easy to miss one of. So the
 * ones still off are the ones marked, and each one stops being marked the
 * moment it is flicked.
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

	/**
	 * The levers still switched off, which are the ones worth marking.
	 *
	 * A lever that is not there at all is skipped rather than counted as off:
	 * the wall is out of render distance until somebody walks to it, and a
	 * highlight on an air block would only point at nothing.
	 */
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
