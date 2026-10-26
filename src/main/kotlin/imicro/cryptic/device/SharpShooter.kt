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
 * The Sharp Shooter device in section 4, including the berserk's early run at it. Nine blue
 * terracotta blocks; one turns emerald, you shoot it, the next lights up. [prediction] guesses the
 * next so the shot can be lined up. Ported from NoammAddons' I4 Helper.
 */
object SharpShooter {
	/** The nine targets, two blocks apart both ways, which the prediction relies on. */
	val TARGETS: List<BlockPos> = listOf(
		BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
		BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
		BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50),
	)

	private const val MAX_PREDICTION_ATTEMPTS = 2

	private val completed = LinkedHashSet<BlockPos>()

	private val predictionAttempts = HashMap<BlockPos, Int>()

	var target: BlockPos? = null
		private set

	var prediction: BlockPos? = null
		private set

	val done: Set<BlockPos> get() = completed

	private var announced = false

	/** Where to aim with a Terminator, best first. Empty unless Terminator mode is on. */
	var aims: List<BlockPos> = emptyList()
		private set

	private var wasOnPlatform = false

	fun isWatching(): Boolean = DeviceSolver.sharpShooterEnabled && Floor7.p3Section == 4

	/** Only drawn from the platform; from anywhere else the wall is just decoration. */
	fun onPlatform(): Boolean {
		val player = Minecraft.getInstance().player ?: return false
		return abs(player.y - PLATFORM_Y) < 0.5 &&
			player.x in PLATFORM_X &&
			player.z in PLATFORM_Z
	}

	/** Read as the client applies the change, so the mark moves the same frame the block does. */
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

		// Lit then unlit means shot. The target is cleared rather than moved here, so it never
		// points at a finished block.
		completed.add(at)
		if (target == at) target = null
		repredict()
	}

	/**
	 * Checks the wall once a tick, to catch blocks that were lit before the change hook could hear
	 * about them.
	 */
	fun tick(client: Minecraft) {
		if (!isWatching()) {
			wasOnPlatform = false
			return
		}
		val level = client.level ?: return

		// Stepping off the plate ends the attempt; stepping back on starts fresh.
		val on = onPlatform()
		if (on && !wasOnPlatform) restart()
		wasOnPlatform = on

		val lit = TARGETS.firstOrNull { level.getBlockState(it).block == Blocks.EMERALD_BLOCK }
		if (lit != target) {
			target = lit
			if (lit != null) completed.remove(lit)
			repredict()
		}

		if (lit != null) completed.remove(lit)
	}

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

	/** The device restarted: Hypixel's count is gone, so ours starts over. The lit block is kept. */
	private fun restart() {
		completed.clear()
		predictionAttempts.clear()
		announced = false
		repredict()
	}

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
	 * Guesses the next block: prefers one of a pair two apart in the same row, at random. A block
	 * guessed wrong twice is passed over.
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
	 * Two spots to aim a Terminator at. Its spread of three arrows hits both lights when aimed
	 * between them. The first aim covers the lit block and the most unshot ones; the next adds the
	 * most new ones. Ported from Odin's Arrows Device.
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

	private fun between(pair: Pair<BlockPos, BlockPos>): BlockPos =
		BlockPos((pair.first.x + pair.second.x) / 2, pair.first.y, pair.first.z)

	private val PAIRS: List<Pair<BlockPos, BlockPos>> = TARGETS.flatMapIndexed { i, a ->
		TARGETS.drop(i + 1).filter { b -> abs(a.x - b.x) == 2 && a.y == b.y && a.z == b.z }.map { b -> a to b }
	}

	private val DEVICE_DONE = Regex("""^(\w{3,16}) completed a device! \(\d/\d\)$""")

	private const val PLATFORM_Y = 127.0
	private val PLATFORM_X = 62.0..65.0
	private val PLATFORM_Z = 34.0..37.0
}
