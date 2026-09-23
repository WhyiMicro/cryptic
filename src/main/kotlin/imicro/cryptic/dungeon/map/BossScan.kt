package imicro.cryptic.dungeon.map

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.MapColor
import kotlin.math.abs
import kotlin.math.floor

/**
 * A plan of the boss room, read off the world a block at a time.
 *
 * Hypixel stops drawing its map item the moment the boss starts, which is the
 * moment a party most wants to know where everybody is — Goldor's four
 * sections are four corners of a tower, and the terminals are done in pairs.
 * There is nothing left to read, so this draws the room instead.
 *
 * Stella does the same thing with a hand-drawn picture per floor. This reads
 * the world rather than shipping pictures: a square of blocks around you, each
 * sampled at the height you are standing at, coloured the way a map item would
 * colour it. It costs nothing in assets, it is right on every floor including
 * the ones nobody has drawn a picture of, and it follows you down through
 * Goldor's tower where one picture per floor cannot.
 *
 * Sampled at foot level rather than from above, because a dungeon has a
 * ceiling: from above, every room is a roof.
 */
object BossScan {
	/** How many blocks across the plan is, and so how many pixels it holds. */
	const val SIZE = 128

	/** How far the eye travels down looking for something to stand on. */
	private const val DEPTH = 6

	/** Far enough that the plan is worth rebuilding around you. */
	private const val MOVED_BLOCKS = 8.0

	/** A run of one colour along a row, which is how flat a room really is. */
	class Run(val top: Int, val left: Int, val right: Int, val argb: Int)

	var runs: List<Run> = emptyList()
		private set

	/** The world position the top-left of the plan stands for. */
	var originX = 0
		private set
	var originZ = 0
		private set

	private var scannedX = Double.NaN
	private var scannedZ = Double.NaN
	private var scannedY = Int.MIN_VALUE

	fun reset() {
		runs = emptyList()
		scannedX = Double.NaN
		scannedZ = Double.NaN
		scannedY = Int.MIN_VALUE
	}

	/**
	 * Rebuilds the plan if the player has walked far enough to need it, or
	 * changed floor inside the tower.
	 */
	fun update(level: Level, player: LocalPlayer) {
		val feet = floor(player.y).toInt() - 1
		val moved = scannedX.isNaN() ||
			abs(player.x - scannedX) > MOVED_BLOCKS ||
			abs(player.z - scannedZ) > MOVED_BLOCKS ||
			abs(feet - scannedY) > 3

		if (!moved) return
		scannedX = player.x
		scannedZ = player.z
		scannedY = feet
		scan(level, floor(player.x).toInt() - SIZE / 2, floor(player.z).toInt() - SIZE / 2, feet)
	}

	private fun scan(level: Level, startX: Int, startZ: Int, feet: Int) {
		originX = startX
		originZ = startZ

		val built = ArrayList<Run>(SIZE * 4)
		val cursor = BlockPos.MutableBlockPos()

		for (row in 0 until SIZE) {
			var column = 0
			var runStart = 0
			var runColor = 0

			while (column <= SIZE) {
				val color = if (column == SIZE) {
					// One past the end, which closes whatever run was open.
					Int.MIN_VALUE
				} else {
					colorAt(level, cursor, startX + column, startZ + row, feet)
				}

				if (column == 0) {
					runColor = color
					runStart = 0
				} else if (color != runColor) {
					if (runColor != 0) built += Run(row, runStart, column, runColor)
					runColor = color
					runStart = column
				}
				column++
			}
		}

		runs = built
	}

	/**
	 * The colour of whatever is underfoot at one point of the plan.
	 *
	 * The search goes down rather than up: standing in a doorway, the block
	 * above you is a wall and the one you want is the floor. Air all the way
	 * down is a hole, and a hole is drawn as nothing.
	 */
	private fun colorAt(level: Level, cursor: BlockPos.MutableBlockPos, x: Int, z: Int, feet: Int): Int {
		for (depth in 0 until DEPTH) {
			cursor.set(x, feet - depth, z)
			val state = level.getBlockState(cursor)
			if (state.isAir) continue
			val color = state.getMapColor(level, cursor)
			if (color === MapColor.NONE) continue
			// Deeper blocks are drawn darker, which is what gives a pit and a
			// step the difference they have in the room itself.
			val brightness = if (depth == 0) MapColor.Brightness.NORMAL else MapColor.Brightness.LOW
			return color.calculateARGBColor(brightness)
		}
		return 0
	}
}
