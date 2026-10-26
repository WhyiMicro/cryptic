package imicro.cryptic.device

import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.feature.DeviceSolver
import imicro.cryptic.terminal.ServerTicks
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * The Simon Says device: a sequence lit on sixteen sea lanterns, to be pressed back in order on the
 * buttons in front. The order is recorded as each lantern turns off, since a lantern turning on can
 * just be a redraw. Ported from Odin.
 */
object SimonSays {
	private val START_BUTTON = BlockPos(110, 121, 91)

	/** The buttons; each lantern is one block east of its button. */
	private const val BUTTON_X = 110
	private const val LANTERN_X = 111
	private val GRID_Y = 120..123
	private val GRID_Z = 92..95

	private val GRID: Set<BlockPos> =
		GRID_Y.flatMap { y -> GRID_Z.map { z -> BlockPos(BUTTON_X, y, z) } }.toSet()

	private val LANTERNS: List<BlockPos> =
		GRID_Y.flatMap { y -> GRID_Z.map { z -> BlockPos(LANTERN_X, y, z) } }

	private val DEVICE_DONE = Regex("""^(?:\[[^\]]+] )?(\w{1,16}) (?:activated|completed) a device! \(\d/\d\)$""")

	/** The middle of the device, for telling its completion from another device's (an early i4). */
	private val DEVICE_CENTRE = net.minecraft.world.phys.Vec3(110.5, 121.5, 93.5)

	/** Server ticks after the last lit lantern before an empty wall can count as a failure. */
	private const val BREAK_GRACE_TICKS = 12

	/** Ticks the wall must stay empty, so a finished device is heard first. */
	private const val BREAK_CONFIRM_TICKS = 3

	/**
	 * How many of the sixteen must change together before the grid counts as rebuilt rather than
	 * flickering.
	 */
	private const val GRID_CHANGED_THRESHOLD = 8

	private const val QUIET_TICKS = 10

	/** The sequence is four long (five before SkyBlock 0.27.2). */
	private const val SEQUENCE_LENGTH = 4

	/** After this long without the server, the lag guard stops holding clicks back. */
	private const val LAG_GIVE_UP_MILLIS = 10_000L

	private val clickInOrder = ArrayList<BlockPos>()

	private var clickNeeded = 0

	private var lastLanternTick = -1

	/** True while the device is still showing the sequence rather than waiting for it. */
	private var firstPhase = true

	private var startClicks = 0

	// Failure check from NoammAddons: after a sequence has been shown, every button going while no
	// lantern is lit means the attempt was thrown away. Read on the network thread.
	@Volatile private var deviceDone = false
	private var armed = false
	private var sinceLit = 0
	private var emptyFor = 0

	val remaining: List<BlockPos>
		get() = if (clickNeeded >= clickInOrder.size) {
			emptyList()
		} else {
			clickInOrder.subList(clickNeeded, clickInOrder.size).toList()
		}

	fun reset() {
		clickInOrder.clear()
		clickNeeded = 0
		lastLanternTick = -1
	}

	fun forget() {
		reset()
		firstPhase = true
		startClicks = 0
		deviceDone = false
		armed = false
	}

	/** Goldor greeting the party means a new device. */
	fun onChatMessage(line: String) {
		if (line == "[BOSS] Goldor: Who dares trespass into my domain?") {
			startClicks = 0
			deviceDone = false
			armed = false
		}
		val name = DEVICE_DONE.matchEntire(line.replace(FORMATTING, "").trim())?.groupValues?.get(1) ?: return
		// Only Simon Says, done by whoever stands at it; someone out of sight is taken to be.
		val at = Minecraft.getInstance().level?.players()?.firstOrNull { it.gameProfile.name == name }?.position()
		if (at == null || at.distanceToSqr(DEVICE_CENTRE) <= 25.0) deviceDone = true
	}

	/** Asked on every block change, so it is kept to two field reads. */
	fun isWatching(): Boolean = DeviceSolver.simonSaysEnabled && Floor7.inGoldor

	fun onBlockChanged(pos: BlockPos, old: BlockState, updated: BlockState) {
		if (!isWatching()) return

		if (pos == START_BUTTON && updated.isButtonPressed()) {
			reset()
			firstPhase = true
			return
		}

		if (pos.y !in GRID_Y || pos.z !in GRID_Z) return

		when (pos.x) {
			LANTERN_X -> onLanternChanged(pos, old, updated)
			BUTTON_X -> onButtonChanged(pos, updated)
		}
	}

	/**
	 * A lantern going out is the next step. The first phase shows them out of order: the first two
	 * reversed, and the third preceded by a repeat of the one before it.
	 */
	private fun onLanternChanged(pos: BlockPos, old: BlockState, updated: BlockState) {
		if (!updated.isBlock(Blocks.OBSIDIAN) || !old.isBlock(Blocks.SEA_LANTERN)) return
		if (pos in clickInOrder) return

		clickInOrder.add(pos.immutable())
		lastLanternTick = 0
		if (!firstPhase) return

		when (clickInOrder.size) {
			2 -> clickInOrder.reverse()
			3 -> clickInOrder.removeAt(clickInOrder.lastIndex - 1)
		}
	}

	private fun onButtonChanged(pos: BlockPos, updated: BlockState) {
		val level = Minecraft.getInstance().level ?: return

		// The whole wall going to air is the device being rebuilt between rounds.
		if (updated.isAir) {
			if (GRID.count { level.getBlockState(it).isAir } > GRID_CHANGED_THRESHOLD) reset()
			return
		}

		if (!updated.isButtonPressed()) return

		clickNeeded = clickInOrder.indexOf(pos.east()) + 1
		if (clickNeeded >= clickInOrder.size) {
			reset()
			firstPhase = false
		}
	}

	/**
	 * The buttons coming back while nothing else happens: the sequence has been shown and is
	 * waiting for presses.
	 */
	fun onServerTick() {
		// Must never throw: this is the network thread.
		runCatching { checkBroken() }
		if (!isWatching() || !firstPhase) return

		val level = Minecraft.getInstance().level ?: return
		if (lastLanternTick++ <= QUIET_TICKS) return
		if (GRID.count { level.getBlockState(it).isBlock(Blocks.STONE_BUTTON) } > GRID_CHANGED_THRESHOLD) {
			firstPhase = false
			startClicks = 0
		}
	}

	private fun checkBroken() {
		if (!DeviceSolver.simonBreakAlertEnabled || !Floor7.inGoldor || deviceDone) {
			armed = false
			return
		}
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		if (LANTERNS.any { level.getBlockState(it).isBlock(Blocks.SEA_LANTERN) }) {
			sinceLit = 0
			emptyFor = 0
			armed = true
			return
		}
		sinceLit++
		if (!armed || sinceLit < BREAK_GRACE_TICKS) return
		if (!GRID.all { level.getBlockState(it).isAir }) {
			emptyFor = 0
			return
		}
		if (++emptyFor < BREAK_CONFIRM_TICKS) return
		armed = false
		emptyFor = 0
		client.execute {
			if (!deviceDone) client.connection?.sendCommand("pc SS broke!")
		}
	}

	/** Whether to swallow a right click. Crouching always gets through, so the device can be done by hand. */
	fun blocksClick(pos: BlockPos): Boolean {
		if (!isWatching()) return false
		val crouching = Minecraft.getInstance().player?.isShiftKeyDown == true

		// Pressing start early restarts the device and costs the whole sequence, but a few taps are
		// normal, so presses are counted rather than blocked outright.
		if (pos == START_BUTTON && firstPhase && DeviceSolver.simonBlockWrongStart.value) {
			val allowance = DeviceSolver.simonMaxStartClicks.value.toInt()
			if (startClicks++ >= allowance && !crouching) return true
		}

		if (pos.x != BUTTON_X || pos.y !in GRID_Y || pos.z !in GRID_Z) return false

		if (!crouching && DeviceSolver.simonBlockWrong.value && pos.east() != clickInOrder.getOrNull(clickNeeded)) return true
		if (!crouching && lagging()) return true

		announceProgress(pos)
		return false
	}

	/**
	 * Whether the server has stopped ticking (Hypixel pings every tick). Presses sent during a
	 * stall reach the device together and fail it, so clicks are held back and the action bar says
	 * so.
	 */
	private fun lagging(): Boolean {
		if (!DeviceSolver.simonLagGuard.value) return false
		val waited = ServerTicks.sinceLastTick ?: return false
		if (waited < DeviceSolver.simonLagMillis.value || waited > LAG_GIVE_UP_MILLIS) return false
		Minecraft.getInstance().gui.hud.setOverlayMessage(
			net.minecraft.network.chat.Component.literal("§cSS click held back: server quiet for ${waited}ms"),
			false,
		)
		return true
	}

	/**
	 * Tells the party which round the device is on. Compared against the lantern, a block east of
	 * the button (Odin compares the button, which never matches).
	 */
	private fun announceProgress(pos: BlockPos) {
		if (!DeviceSolver.simonAnnounceProgress.value) return
		val last = clickInOrder.lastOrNull() ?: return
		if (pos.east() != last) return
		Minecraft.getInstance().connection?.sendCommand("pc SS ${clickInOrder.size}/$SEQUENCE_LENGTH")
	}

	private val FORMATTING = Regex("§.")

	private fun BlockState.isBlock(block: net.minecraft.world.level.block.Block): Boolean = this.block == block

	private fun BlockState.isButtonPressed(): Boolean =
		isBlock(Blocks.STONE_BUTTON) &&
			getOptionalValue(BlockStateProperties.POWERED).orElse(false)
}
