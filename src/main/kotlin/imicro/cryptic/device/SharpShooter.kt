package imicro.cryptic.device

import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.feature.DeviceSolver
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BasePressurePlateBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import kotlin.math.abs

/**
 * The Sharp Shooter device in s4, and the berserk's early run at it.
 *
 * Ported from NoammAddons' I4 Helper (CC0, Copyright (c) Noamm9) — "i4" being
 * *early device 4*, the strat where the berserk runs here the moment p3 opens
 * and shoots the whole device before anybody has reached s1.
 *
 * Nine blue terracotta blocks on a wall. One of them turns to emerald; you
 * shoot it; it goes back to terracotta and counts as done, and another lights
 * up. So the whole device is "which one is green right now", and the helper is
 * worth having because at range, side-on, from a platform fifteen blocks away,
 * green terracotta and blue terracotta are not as different as they sound.
 *
 * [prediction] is the guess at which one lights up next, so the next shot can
 * be lined up before it does.
 */
object SharpShooter {
	/**
	 * The nine targets, three across and three up, on the s4 wall.
	 *
	 * They are two blocks apart in both directions, which is what the
	 * prediction leans on.
	 */
	val TARGETS: List<BlockPos> = listOf(
		BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
		BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
		BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50),
	)

	/** How many times one block may be guessed wrongly before it is passed over. */
	private const val MAX_PREDICTION_ATTEMPTS = 2

	private val completed = LinkedHashSet<BlockPos>()

	private val predictionAttempts = HashMap<BlockPos, Int>()

	/** The block that is lit right now, which is the one to shoot. */
	var target: BlockPos? = null
		private set

	/** The guess at which lights up next, or null while there is nothing to guess. */
	var prediction: BlockPos? = null
		private set

	/** The blocks already shot, which is how far through the device you are. */
	val done: Set<BlockPos> get() = completed

	private var announced = false

	/**
	 * Where to aim with a Terminator, best first: the block between two lights,
	 * so the spread hits both. Empty unless Terminator mode is on.
	 */
	var aims: List<BlockPos> = emptyList()
		private set

	/** Whether the player was on the platform last tick, to see them step onto it. */
	private var wasOnPlatform = false

	/** True while the device is worth watching at all. */
	fun isWatching(): Boolean = DeviceSolver.sharpShooterEnabled && Floor7.p3Section == 4

	/**
	 * True while the player is on the platform the device is shot from.
	 *
	 * Drawing is held to this because the nine blocks are only a device from
	 * where they are shot; from anywhere else in s4 they are decoration on a
	 * far wall, and marking them there is clutter over a section that already
	 * has terminals in it.
	 */
	fun onPlatform(): Boolean {
		val player = Minecraft.getInstance().player ?: return false
		return abs(player.y - PLATFORM_Y) < 0.5 &&
			player.x in PLATFORM_X &&
			player.z in PLATFORM_Z
	}

	/**
	 * A block changing anywhere in the world.
	 *
	 * This is the fast path, and it is why the helper keeps up: the change is
	 * read as the client applies it rather than at the next tick, so the mark
	 * moves in the same frame the block does.
	 */
	fun onBlockChanged(pos: BlockPos, old: BlockState, updated: BlockState) {
		if (!isWatching()) return
		if (isStartPlate(pos, old, updated)) {
			restart()
			return
		}
		val at = TARGETS.firstOrNull { it == pos } ?: return

		if (updated.block == Blocks.EMERALD_BLOCK) {
			target = at
			completed.remove(at)
			repredict()
			return
		}

		if (old.block != Blocks.EMERALD_BLOCK) return

		// Lit, then not lit, means it has been shot.
		//
		// NoammAddons moves the target *onto* this block here, which is where
		// its lag comes from: the green mark jumps to the block just shot and
		// stays there until the server lights the next one, so for a moment it
		// points at a block that is finished. The target is cleared instead,
		// and the next one to light up claims it.
		completed.add(at)
		if (target == at) target = null
		repredict()
	}

	/**
	 * Reads the wall as it actually is, once a tick.
	 *
	 * The block hook above is faster and does nearly all of the work, but it
	 * only ever hears about *changes* — so a block already lit when the player
	 * arrived, or a change that arrived while the section was not being
	 * watched, would never be noticed. This is what makes the mark right rather
	 * than merely quick, and it is nine block lookups.
	 */
	fun tick(client: Minecraft) {
		if (!isWatching()) {
			wasOnPlatform = false
			return
		}
		val level = client.level ?: return

		// Stepping off the plate throws the attempt away, so stepping back on
		// is a fresh start: nothing from the last go is still shot.
		val on = onPlatform()
		if (on && !wasOnPlatform) restart()
		wasOnPlatform = on

		val lit = TARGETS.firstOrNull { level.getBlockState(it).block == Blocks.EMERALD_BLOCK }
		if (lit != target) {
			target = lit
			if (lit != null) completed.remove(lit)
			repredict()
		}

		// A block that is lit is not finished, whatever was believed of it.
		if (lit != null) completed.remove(lit)
	}

	/** The whole device announced as done, which is the only certain finish. */
	fun onChatMessage(line: String) {
		if (!isWatching() || announced) return
		val name = DEVICE_DONE.find(line)?.groupValues?.get(1) ?: return
        if (name != Minecraft.getInstance().player?.name?.string) return

		announced = true
		DeviceSolver.announceSharpShooterDone(completed.size, TARGETS.size)
	}

	fun forget() {
		completed.clear()
		predictionAttempts.clear()
		target = null
		prediction = null
		aims = emptyList()
		announced = false
		wasOnPlatform = false
	}

	/**
	 * The device started again, by whoever stood on the plate.
	 *
	 * Whatever was counted before is gone on Hypixel's side — a lit block going
	 * dark as the last player stepped off even reads here as "shot" — so the
	 * tally starts over with the device. The lit block, if any, is kept.
	 */
	private fun restart() {
		completed.clear()
		predictionAttempts.clear()
		announced = false
		repredict()
	}

	/** The pressure plate in front of the device going from unpressed to pressed. */
	private fun isStartPlate(pos: BlockPos, old: BlockState, updated: BlockState): Boolean {
		if (updated.block !is BasePressurePlateBlock) return false
		if (pos.y != PLATFORM_Y.toInt() || pos.x + 0.5 !in PLATFORM_X || pos.z + 0.5 !in PLATFORM_Z) return false
		return !isPressed(old) && isPressed(updated)
	}

	private fun isPressed(state: BlockState): Boolean = when {
		state.block !is BasePressurePlateBlock -> false
		state.hasProperty(BlockStateProperties.POWERED) -> state.getValue(BlockStateProperties.POWERED)
		state.hasProperty(BlockStateProperties.POWER) -> state.getValue(BlockStateProperties.POWER) > 0
		else -> false
	}

	/**
	 * Picks the next block to line up on.
	 *
	 * NoammAddons' heuristic, kept: of the blocks still to do, it prefers one
	 * of a pair sitting two apart in the same row, and picks at random among
	 * those. It is a guess and is meant to be — a wrong guess costs nothing but
	 * a re-aim — so a block guessed wrongly twice is passed over rather than
	 * offered a third time.
	 */
	private fun repredict() {
		aims = if (DeviceSolver.i4Terminator.value) target?.let(::terminatorAims).orEmpty() else emptyList()

		if (!DeviceSolver.i4ShowPrediction.value) {
			prediction = null
			return
		}

		val level = Minecraft.getInstance().level
		if (level == null) {
			prediction = null
			return
		}

		val remaining = TARGETS.filter {
			it !in completed && it != target && level.getBlockState(it).block == Blocks.DYED_TERRACOTTA.blue()
		}
		if (remaining.isEmpty()) {
			prediction = null
			return
		}

		val candidates = remaining
			.filter { (predictionAttempts[it] ?: 0) < MAX_PREDICTION_ATTEMPTS }
			.ifEmpty { remaining }

		val pairs = candidates.groupBy { it.y }.flatMap { (_, row) ->
			val sorted = row.sortedBy { it.x }
			sorted.zipWithNext().filter { (left, right) -> right.x - left.x == 2 }
		}

		val chosen = if (pairs.isNotEmpty()) {
			pairs.random().toList().random()
		} else {
			candidates.random()
		}

		predictionAttempts.merge(chosen, 1, Int::plus)
		prediction = chosen
	}

	/**
	 * Two spots to aim a Terminator at: the one covering the lit block, then the next.
	 *
	 * Ported from Odin's Arrows Device (BSD-3, Copyright (c) odtheking). A
	 * Terminator fires three arrows in a flat spread, so aiming at the block
	 * between two lights in a row hits both. The first aim is the pair with the
	 * lit block that covers the most unshot blocks; the next is the pair
	 * adding the most blocks not yet covered, then the most blocks, then the
	 * nearest to the aim before it.
	 */
	private fun terminatorAims(lit: BlockPos): List<BlockPos> {
		fun covers(pair: Pair<BlockPos, BlockPos>): Set<BlockPos> =
			pair.toList().filter { it !in completed }.toSet()

		val first = PAIRS.filter { lit in it.toList() }
			.map { it to covers(it) }
			.filter { it.second.isNotEmpty() }
			.maxByOrNull { it.second.size } ?: return emptyList()

		val chosen = mutableListOf(first)
		val covered = first.second.toMutableSet()
		val others = PAIRS.filter { lit !in it.toList() }.map { it to covers(it) }.filter { it.second.isNotEmpty() }

		repeat(1) {
			val last = between(chosen.last().first)
			val next = others.filter { it !in chosen }.maxWithOrNull(compareBy(
				{ it.second.count { block -> block !in covered } },
				{ it.second.size },
				{ -between(it.first).distSqr(last) },
			)) ?: return@repeat
			chosen.add(next)
			covered.addAll(next.second)
		}
		return chosen.map { between(it.first) }
	}

	/** The block in the wall between two lights of a row. */
	private fun between(pair: Pair<BlockPos, BlockPos>): BlockPos =
		BlockPos((pair.first.x + pair.second.x) / 2, pair.first.y, pair.first.z)

	/** Every two lights side by side in a row, which is what one Terminator shot can hit. */
	private val PAIRS: List<Pair<BlockPos, BlockPos>> = TARGETS.flatMapIndexed { i, a ->
		TARGETS.drop(i + 1).filter { b -> abs(a.x - b.x) == 2 && a.y == b.y && a.z == b.z }.map { b -> a to b }
	}

	private val DEVICE_DONE = Regex("""^(\w{3,16}) completed a device! \(\d/\d\)$""")

	/** Where the device is shot from, which is the platform in front of it. */
	private const val PLATFORM_Y = 127.0
	private val PLATFORM_X = 62.0..65.0
	private val PLATFORM_Z = 34.0..37.0
}
