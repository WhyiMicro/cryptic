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
 * Straightens the doors in section three of the Goldor phase.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). Hypixel sends s3's iron
 * doors with the wrong rotation, so the client draws them turned sideways —
 * half-open doors hanging in the wall, hiding the gap you are running for. The
 * doors themselves are fine; what arrives wrong is the block state describing
 * which way they face.
 *
 * Each of the twenty is rewritten in the client's own copy of the world, and
 * nowhere else. The server is not told anything, no packet is sent, and a door
 * that is genuinely absent is left absent — only a door that is there and drawn
 * crooked is turned the right way round.
 *
 * A switch on the F7/M7 QOL card rather than a card of its own.
 */
object DoorFix {
	@JvmField
	val enabled = ToggleModuleSetting(
		id = "door_fix",
		label = "Door fix",
		defaultValue = true,
		description = "Turns s3's iron doors the right way round, which Hypixel sends sideways.",
	)

	/** True while the doors are to be straightened. */
	val active: Boolean get() = F7Qol.module.enabled && enabled.value

	/**
	 * Where the doors stand, in three columns up the west wall of s3.
	 *
	 * NoammAddons' positions. The gaps are deliberate: the first column has no
	 * door on its second step, and the middle column stops one short.
	 */
	private val DOORS: List<BlockPos> = buildList {
		for (i in 0..6) {
			if (i != 1) add(BlockPos(1, 112 + i * 4, 104))
			if (i < 6) add(BlockPos(1, 113 + i * 4, 86))
			add(BlockPos(1, 112 + i * 4, 68))
		}
	}

	/** Facing east, lower half, hinged left, shut — which is how they really are. */
	private val CORRECTED = Blocks.IRON_DOOR.defaultBlockState()
		.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
		.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
		.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
		.setValue(BlockStateProperties.OPEN, false)

	/** True while the section that has the crooked doors is the one being run. */
	val needsPhaseTracking: Boolean get() = active

	fun tick(client: Minecraft) {
		if (!active || Floor7.p3Section != 3) return
		val level = client.level ?: return

		for (pos in DOORS) {
			val state = level.getBlockState(pos)
			// An air block here is a door already opened or never placed, and
			// putting one back would be inventing a door rather than fixing one.
			if (state.isAir || state == CORRECTED) continue
			level.setBlock(pos, CORRECTED, 0)
		}
	}
}
