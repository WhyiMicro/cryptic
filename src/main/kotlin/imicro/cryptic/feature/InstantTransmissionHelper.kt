package imicro.cryptic.feature

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.BaseTorchBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.DiodeBlock
import net.minecraft.world.level.block.EndGatewayBlock
import net.minecraft.world.level.block.EndPortalBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.NetherPortalBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.SugarCaneBlock
import net.minecraft.world.level.block.TripWireBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/**
 * Where an Instant Transmission lands. Wither Impact is the same walk, longer. A prediction only;
 * the server's answer moves the player.
 *
 * From the eyes (1.62 standing, 1.27 sneaking), a block at a time along the look:
 * - the walk ends where the line between two steps passes through a solid block's collision, or a
 *   step's own cell is solid or has a fence or wall reaching up into it;
 * - a step lands you in the middle of the cell under it (or the step's own cell, when the one
 *   under is solid), if you fit there; a step you don't fit in is passed over, not the end;
 * - you go to the last step you fit in, except that a short move up or down your own column is
 *   refused.
 *
 * "Solid" is Hypixel's 1.8 server's sense: heads, ladders, carpets, plants and the like aren't.
 * The rules are the measured ones from ADM's teleport data (facts only).
 */
object InstantTransmissionHelper {
	private const val STANDING_EYE = 1.62
	private const val SNEAKING_EYE = 1.27

	/** How finely the eyes' line is checked for walls between steps. */
	private const val RAY_STEP = 0.05

	/** Straight up your own column, a landing this close is refused; straight down, [MIN_DROP]. */
	private const val MIN_RISE = 1.5
	private const val MIN_DROP = 0.55

	fun eyeHeight(sneaking: Boolean): Double = if (sneaking) SNEAKING_EYE else STANDING_EYE

	/** The feet's position after teleporting [distance] blocks, or null if it wouldn't move. */
	fun predict(distance: Double, feet: Vec3, look: Vec3, eyeHeight: Double = STANDING_EYE): Vec3? {
		val level = Minecraft.getInstance().level ?: return null
		val eye = feet.add(0.0, eyeHeight, 0.0)
		val cursor = BlockPos.MutableBlockPos()
		var landing: Vec3? = null
		for (step in 1..floor(distance).toInt()) {
			if (wallBetween(level, eye, look, step - 1.0, step.toDouble())) break
			val point = eye.add(look.scale(step.toDouble()))
			val x = floor(point.x).toInt()
			val y = floor(point.y).toInt()
			val z = floor(point.z).toInt()
			if (solid(level, cursor.set(x, y, z)) || onTall(level, x, y, z)) break

			val feetY = if (solid(level, cursor.set(x, y - 1, z)) || onTall(level, x, y - 1, z)) y else y - 1
			if (!fits(level, x, feetY, z)) continue
			landing = Vec3(x + 0.5, feetY.toDouble(), z + 0.5)
		}
		val to = landing ?: return null
		if (floor(to.x) == floor(feet.x) && floor(to.z) == floor(feet.z)) {
			val rise = to.y - feet.y
			if (if (rise >= 0) rise < MIN_RISE else -rise < MIN_DROP) return null
		}
		return to
	}

	/** Whether a solid block's collision sits on the eyes' line between [from] and [to] blocks along it. */
	private fun wallBetween(level: Level, eye: Vec3, look: Vec3, from: Double, to: Double): Boolean {
		val cursor = BlockPos.MutableBlockPos()
		var t = from
		while (t <= to) {
			val point = eye.add(look.scale(t))
			cursor.set(floor(point.x).toInt(), floor(point.y).toInt(), floor(point.z).toInt())
			if (solid(level, cursor)) {
				val local = point.subtract(cursor.x.toDouble(), cursor.y.toDouble(), cursor.z.toDouble())
				if (level.getBlockState(cursor).getCollisionShape(level, cursor).toAabbs().any { it.contains(local) }) return true
			}
			t += RAY_STEP
		}
		return false
	}

	/** Whether a player fits with their feet in cell ([x], [y], [z]). */
	private fun fits(level: Level, x: Int, y: Int, z: Int): Boolean {
		val cursor = BlockPos.MutableBlockPos()
		return !solid(level, cursor.set(x, y, z)) && !solid(level, cursor.set(x, y + 1, z)) && !onTall(level, x, y, z)
	}

	/** Whether the block under ([x], [y], [z]) reaches up into it: a fence, wall or gate. */
	private fun onTall(level: Level, x: Int, y: Int, z: Int): Boolean {
		val below = BlockPos(x, y - 1, z)
		val shape = level.getBlockState(below).getCollisionShape(level, below)
		return !shape.isEmpty && shape.max(Direction.Axis.Y) > 1.0
	}

	/** Solid as Hypixel's 1.8 server has it: anything but air, fluids and the blocks listed here. */
	internal fun solid(level: Level, pos: BlockPos): Boolean {
		val state = level.getBlockState(pos)
		if (state.isAir) return false
		return when (state.block) {
			is LiquidBlock, is BaseTorchBlock, is LeverBlock, is ButtonBlock, is BaseRailBlock,
			is RedStoneWireBlock, is TripWireBlock, is TripWireHookBlock, is LadderBlock,
			is AbstractSkullBlock, is FlowerPotBlock, is DiodeBlock, is CarpetBlock, is SnowLayerBlock,
			is VineBlock, is VegetationBlock, is SugarCaneBlock, is CocoaBlock, is BaseFireBlock,
			is NetherPortalBlock, is EndPortalBlock, is EndGatewayBlock,
			-> false
			else -> true
		}
	}
}
