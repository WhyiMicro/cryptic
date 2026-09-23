package imicro.cryptic.terminal

import imicro.cryptic.feature.TerminalSimulator
import imicro.cryptic.feature.TerminalSolver
import imicro.cryptic.terminal.sim.TermSimScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CopyOnWriteArrayList

/** What the solver paints over one slot: a colour, and a label when it has one. */
data class SlotOverlay(val argb: Int, val text: String? = null)

/**
 * One terminal the player has open, and the solution as it currently stands.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). The shape is
 * theirs: a handler per open terminal, re-solved from the board every time a
 * slot of it changes, with the clicks that have gone out but not yet come back
 * replayed on top.
 *
 * That replay — [clickedSlots] — is what makes the solver feel instant on a
 * high ping. A click is shown as taken the moment it is sent, so the next one
 * can be aimed straight away; when the server answers, the slot that comes back
 * retires that click and everything sent before it, and the board it sent is
 * the truth. Nothing here is ever left guessing for long: a click that goes
 * unanswered is dropped by [Terminals] after the resolve timeout, and the
 * board is solved again from what is really on screen.
 *
 * The solution list is copy-on-write because the render pass reads it on the
 * render thread while packets rewrite it on the client thread.
 */
abstract class TerminalHandler(val type: TerminalType) {
	val solution: CopyOnWriteArrayList<Int> = CopyOnWriteArrayList()

	/** Clicks sent and not yet confirmed, each with the button it went out with. */
	val clickedSlots: MutableList<Pair<Int, Int>> = CopyOnWriteArrayList()

	var openedAt: Long = System.currentTimeMillis()
		private set

	/**
	 * Starts the click-protection clock again, for a window the player has not
	 * touched yet.
	 *
	 * Hypixel announces the opening window more than once, and the player can
	 * only have seen the last of them — so a clock running from the first says
	 * they have had longer to read the board than they really have. Only ever
	 * called before the first click, where there is nothing to lose by it, and
	 * it can only ever make the protection longer.
	 */
	fun restartProtection() {
		if (everClicked) return
		openedAt = System.currentTimeMillis()
		serverTicksOpen = -1
	}

	var lastClickTime: Long = 0L
		private set

	/** Server ticks since the terminal opened, or -1 before the first one. */
	var serverTicksOpen: Int = -1

	/** True once anything has been clicked in this terminal at all. */
	var everClicked: Boolean = false
		private set

	/**
	 * The slots the puzzle itself occupies.
	 *
	 * Always the window without its bottom row: every terminal keeps that row
	 * for filler, and a change to it is never a move in the puzzle.
	 */
	val puzzleSize: Int get() = type.windowSize - 9

	/**
	 * Re-reads the board after one slot of it changed, and rebuilds from it.
	 *
	 * The index that changed is passed on to [solve] because two of the six
	 * care which slot moved rather than only what the board now looks like.
	 */
	open fun updateSlot(slotIndex: Int, items: List<ItemStack>) {
		if (items.isEmpty() || slotIndex !in 0 until puzzleSize) return
		// Filler moving is not the puzzle moving, and treating it as such
		// re-solved the board off changes that mean nothing.
		if (items.getOrNull(slotIndex)?.item == Items.STAINED_GLASS_PANE.black()) return

		// The slot we clicked has come back from the server, so that click has
		// landed — and so has everything sent before it, because the server
		// answers in order.
		val landed = clickedSlots.indexOfFirst { it.first == slotIndex }
		if (landed >= 0) clickedSlots.subList(0, landed + 1).clear()

		val board = items.subList(0, minOf(items.size, puzzleSize))
		solution.clear()
		solution.addAll(solve(board, slotIndex))

		if (TerminalSolver.clickPrediction.value) {
			clickedSlots.forEach { (slot, button) -> simulateClick(slot, button) }
		}
	}

	/** Throws away clicks the server never answered, without touching the board. */
	fun forgetSentClicks() {
		clickedSlots.clear()
	}

	/** The slots to click, in the order they should be clicked. */
	abstract fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int>

	/** How a slot that is part of the solution should be painted. */
	protected abstract fun highlight(slotIndex: Int): SlotOverlay?

	/**
	 * What to draw over [slotIndex], or null to leave it alone.
	 *
	 * Only the solution is painted for five of the six; melody overrides this
	 * because its grid needs a resting colour under the notes as well.
	 */
	open fun overlay(slotIndex: Int): SlotOverlay? =
		if (slotIndex in solution) highlight(slotIndex) else null

	/** Whether clicking [slotIndex] with [button] would be a correct move. */
	open fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex in solution

	/**
	 * The button the click actually goes out with, given the one pressed.
	 *
	 * A left click is sent as a middle click, which is what Hypixel's terminals
	 * want and what Odin sends as well. Rubix is the one that reads the button,
	 * so it says more about this than anyone else.
	 */
	open fun buttonFor(slotIndex: Int, requested: Int): Int =
		if (requested == GLFW.GLFW_MOUSE_BUTTON_RIGHT && type == TerminalType.RUBIX) {
			GLFW.GLFW_MOUSE_BUTTON_RIGHT
		} else {
			GLFW.GLFW_MOUSE_BUTTON_MIDDLE
		}

	/**
	 * Applies a click to the solution before the server has confirmed it.
	 *
	 * Wrong guesses cost nothing: the next board from the server replaces the
	 * solution outright, and the clicks still in flight are replayed onto it.
	 */
	open fun simulateClick(slotIndex: Int, button: Int) {
		val at = solution.indexOf(slotIndex)
		if (at >= 0) solution.removeAt(at)
	}

	/**
	 * Sends a click, or drops it.
	 *
	 * A left click goes out as a middle click, which is what Hypixel's
	 * terminals want and what Odin sends as well; rubix is the one that reads
	 * the button, so its right clicks stay right clicks.
	 */
	open fun click(slotIndex: Int, button: Int) {
		if (!canClick(slotIndex, button) || blocksClicks()) {
			TerminalDebug.clicked(this, slotIndex, button, sent = false)
			return
		}

		val sent = buttonFor(slotIndex, button)
		clickedSlots.add(slotIndex to sent)
		lastClickTime = System.currentTimeMillis()
		everClicked = true
		if (TerminalSolver.clickPrediction.value) simulateClick(slotIndex, sent)
		TerminalDebug.clicked(this, slotIndex, sent, sent = true)

		val client = Minecraft.getInstance()
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
		if (screen is TermSimScreen) {
			screen.clickIndex(slotIndex, sent)
			return
		}

		val player = client.player ?: return
		val input = if (sent == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) ContainerInput.CLONE else ContainerInput.PICKUP
		client.gameMode?.handleContainerInput(screen.menu.containerId, slotIndex, sent, input, player)
	}

	/**
	 * True while a click would land too soon after the terminal opened.
	 *
	 * Hypixel bans for clicking a terminal faster than a human could have seen
	 * it, and a solver makes that trivially easy to do by accident, so clicks
	 * inside the window are dropped rather than sent. The clock alone is not
	 * enough when the server is behind — it opened the window late, so the
	 * player's own screen has been up for less time than the timer thinks —
	 * which is what the tick half is for.
	 */
	fun blocksClicks(): Boolean {
		if (TerminalSimulator.skipClickProtection.value && Minecraft.getInstance().gui.screen() is TermSimScreen) {
			return false
		}
		if (System.currentTimeMillis() - openedAt < TerminalSolver.firstClickProtection.value) return true

		return TerminalSolver.accountForServerLag.value &&
			ServerTicks.available &&
			serverTicksOpen < TerminalSolver.lagProtectionTicks.value
	}
}
