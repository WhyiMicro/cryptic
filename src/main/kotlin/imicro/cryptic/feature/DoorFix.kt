package imicro.cryptic.feature

import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoorHingeSide
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf

/**
 * Straightens the iron doors in Goldor section 3, which Hypixel sends with the wrong rotation.
 * Rewritten in the client's world only; missing doors stay missing. A switch on the F7/M7 Features card.
 * Ported from NoammAddons.
 */
object DoorFix {
	@JvmField
	val enabled = ToggleModuleSetting(
		id = "door_fix",
		label = "Door fix",
		defaultValue = true,
		description = "Turns s3's iron doors the right way round, which Hypixel sends sideways.",
	)

	val active: Boolean get() = F7Qol.module.enabled && enabled.value

	/** The doors, in three columns up s3's west wall (NoammAddons' positions; the gaps are real). */
	private val DOORS: List<BlockPos> = buildList {
		for (i in 0..6) {
			if (i != 1) add(BlockPos(1, 112 + i * 4, 104))
			if (i < 6) add(BlockPos(1, 113 + i * 4, 86))
			add(BlockPos(1, 112 + i * 4, 68))
		}
	}

	/** Facing east, lower half, hinged left, shut: how they really are. */
	private val CORRECTED = Blocks.IRON_DOOR.defaultBlockState()
		.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
		.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
		.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
		.setValue(BlockStateProperties.OPEN, false)

	val needsPhaseTracking: Boolean get() = active

	fun tick(client: Minecraft) {
		if (!active || Floor7.p3Section != 3) return
		val level = client.level ?: return

		for (pos in DOORS) {
			val state = level.getBlockState(pos)
			// Air is an opened or absent door; putting one back would invent it.
			if (state.isAir || state == CORRECTED) continue
			level.setBlock(pos, CORRECTED, 0)
		}
	}
}
