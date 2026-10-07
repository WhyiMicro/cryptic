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
 * The Simon Says device: a sequence of lights is played back, and the buttons
 * in front of them have to be pressed in the same order.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). The sequence
 * is never sent to the client as a sequence — it is sixteen sea lanterns behind
 * the buttons, lit and unlit one at a time — so the order has to be recorded as
 * it is shown. Each lantern turning *off* is what marks its place in the
 * sequence, because a lantern turning on can be the display being redrawn.
 */
object SimonSays {
	/** The button that starts a round, off to the side of the grid. */
	private val START_BUTTON = BlockPos(110, 121, 91)

	/** The buttons, and one block east of each of them the lantern it belongs to. */
	private const val BUTTON_X = 110
	private const val LANTERN_X = 111
	private val GRID_Y = 120..123
	private val GRID_Z = 92..95

	private val GRID: Set<BlockPos> =
		GRID_Y.flatMap { y -> GRID_Z.map { z -> BlockPos(BUTTON_X, y, z) } }.toSet()

	private val LANTERNS: List<BlockPos> =
		GRID_Y.flatMap { y -> GRID_Z.map { z -> BlockPos(LANTERN_X, y, z) } }

	/** "Steve completed a device! (1/2)": the only device in the first section is this one. */
	private val DEVICE_DONE = Regex("""^S+ (?:activated|completed) a device! (d/d)$""")

	/** Server ticks after the last lit lantern before an empty wall can be a failure, NoammAddons' twelve. */
	private const val BREAK_GRACE_TICKS = 12

	/** Ticks the wall has to stay empty, so the device finishing is heard first. */
	private const val BREAK_CONFIRM_TICKS = 3

	/**
	 * How many of the sixteen have to have gone the same way before the grid is
	 * taken to have been rebuilt rather than merely flickering.
	 */
	private const val GRID_CHANGED_THRESHOLD = 8

	/** Server ticks of quiet after the last lantern before a reset can be called. */
	private const val QUIET_TICKS = 10

	/**
	 * The full sequence is four long, which is what the announcement counts to.
	 * Five before SkyBlock 0.27.2, which took one set of lights away.
	 */
	private const val SEQUENCE_LENGTH = 4

	/**
	 * How long the server may go quiet before the lag guard stops holding
	 * clicks back, taking it to be gone rather than slow.
	 */
	private const val LAG_GIVE_UP_MILLIS = 10_000L

	/** Lantern positions, in the order the device showed them. */
	private val clickInOrder = ArrayList<BlockPos>()

	/** How far into [clickInOrder] the player has got. */
	private var clickNeeded = 0

	private var lastLanternTick = -1

	/**
	 * True while the device is still showing the sequence rather than waiting
	 * for it to be played back.
	 *
	 * Only the first phase reorders what it shows, and only the first phase has
	 * a start button worth guarding, so nearly everything conditional here
	 * hangs off it.
	 */
	private var firstPhase = true

	private var startClicks = 0

	// The failure check, NoammAddons' (CC0): once the device has shown a
	// sequence, every button going while no lantern is lit is the device
	// throwing the attempt away. Read on the network thread, like the tick.
	@Volatile private var deviceDone = false
	private var armed = false
	private var sinceLit = 0
	private var emptyFor = 0

	/** The lanterns still to be pressed, in order, for the render pass. */
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

	/** Goldor greeting the party is a new device, and a new allowance of starts. */
	fun onChatMessage(line: String) {
		if (line == "[BOSS] Goldor: Who dares trespass into my domain?") {
			startClicks = 0
			deviceDone = false
			armed = false
		}
		if (DEVICE_DONE.matches(line.replace(FORMATTING, "").trim())) deviceDone = true
	}

	/**
	 * Whether a block change anywhere in the world is worth reading.
	 *
	 * Asked once per block change, so it is two field reads and nothing else.
	 */
	fun isWatching(): Boolean = DeviceSolver.simonSaysEnabled && Floor7.inGoldor

	/** A block changing anywhere in the world, once [isWatching] has said yes. */
	fun onBlockChanged(pos: BlockPos, old: BlockState, updated: BlockState) {
		if (!isWatching()) return

		// The start button being pressed puts the device back to showing a
		// sequence, whatever it was doing before.
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
	 * A lantern going out is one more step of the sequence.
	 *
	 * The first phase shows the sequence in an order that is not the order it
	 * wants back, and Odin's correction for that is reproduced here as it
	 * stands: the first two arrive reversed, and the third arrives with a
	 * repeat of the one before it in front of it.
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

		// The whole wall going to air is the device being rebuilt between
		// rounds, and nothing recorded from the last one still applies.
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
	 * The buttons coming back while nothing else is happening is the device
	 * having finished showing its sequence and started waiting for it.
	 */
	fun onServerTick() {
		// Never allowed to throw: this is the network thread.
		runCatching { checkBroken() }
		if (!isWatching() || !firstPhase) return

		val level = Minecraft.getInstance().level ?: return
		if (lastLanternTick++ <= QUIET_TICKS) return
		if (GRID.count { level.getBlockState(it).isBlock(Blocks.STONE_BUTTON) } > GRID_CHANGED_THRESHOLD) {
			firstPhase = false
			startClicks = 0
		}
	}

	/** Tells the party when the device has thrown the attempt away. */
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

	/**
	 * A right click on a block, answered with whether it should be swallowed.
	 *
	 * Crouching overrides every block, on the same principle as the arrow
	 * device: a solver that has lost track must never be able to stop the
	 * device being played by hand.
	 */
	fun blocksClick(pos: BlockPos): Boolean {
		if (!isWatching()) return false
		val crouching = Minecraft.getInstance().player?.isShiftKeyDown == true

		// The start button is a separate guard because pressing it early is a
		// different mistake: it does not fail the device, it restarts it, and
		// each restart costs the party the whole sequence again. A handful of
		// presses is normal — everybody taps it to open the round — so this
		// counts them rather than blocking outright.
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
	 * Whether the server has gone quiet for longer than the lag guard's
	 * threshold. Hypixel sends a ping every tick it runs, one every 50ms; when
	 * they stop, the server has stalled, and a press sent now waits for it with
	 * whatever was pressed before — and presses that reach the device together
	 * fail it. Says so on the action bar when it holds a click back.
	 *
	 * The ping rather than the button's own answer, because the game presses a
	 * button on your screen before the server has heard about it: the button
	 * coming back pressed says nothing about whether the server is there.
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
	 * Tells the party which round the device is on, once the last button of a
	 * round has been reached.
	 *
	 * Odin compares the button's own position against the recorded sequence
	 * here, which cannot match — the sequence is recorded a block east, at the
	 * lanterns — so its announcement never fires. The comparison is made
	 * against the lantern, as it is everywhere else in this file.
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
