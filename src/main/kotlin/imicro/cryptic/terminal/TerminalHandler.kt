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

data class SlotOverlay(val argb: Int, val text: String? = null)

/**
 * One open terminal and its current solution. Ported from Odin: re-solved from the board whenever a
 * slot changes, with sent-but-unconfirmed clicks ([clickedSlots]) replayed on top, which is what
 * makes it feel instant on high ping. A confirmed slot retires that click and all before it;
 * unanswered clicks are dropped by [Terminals] after the resolve timeout.
 *
 * The solution is copy-on-write: the render thread reads it while packets rewrite it.
 */
abstract class TerminalHandler(val type: TerminalType) {
	val solution: CopyOnWriteArrayList<Int> = CopyOnWriteArrayList()

	val clickedSlots: MutableList<Pair<Int, Int>> = CopyOnWriteArrayList()

	var openedAt: Long = System.currentTimeMillis()
		private set

	/**
	 * Restarts the click-protection clock. Hypixel announces a window more than once, and the
	 * player has only seen the last. Only before the first click, so it can only make protection
	 * longer.
	 */
	fun restartProtection() {
		if (everClicked) return
		openedAt = System.currentTimeMillis()
		serverTicksOpen = -1
	}

	var lastClickTime: Long = 0L
		private set

	var serverTicksOpen: Int = -1

	var everClicked: Boolean = false
		private set

	/** The puzzle slots: the window minus its bottom filler row. */
	val puzzleSize: Int get() = type.windowSize - 9

	/**
	 * Re-reads the board after one slot changed. The index is passed on because two solvers care
	 * which slot moved.
	 */
	open fun updateSlot(slotIndex: Int, items: List<ItemStack>) {
		if (items.isEmpty() || slotIndex !in 0 until puzzleSize) return
		// Filler changing isn't the puzzle changing.
		if (items.getOrNull(slotIndex)?.item == Items.STAINED_GLASS_PANE.black()) return

		// Our clicked slot came back, so that click and every earlier one has landed (the server
		// answers in order).
		val landed = clickedSlots.indexOfFirst { it.first == slotIndex }
		if (landed >= 0) clickedSlots.subList(0, landed + 1).clear()

		val board = items.subList(0, minOf(items.size, puzzleSize))
		solution.clear()
		solution.addAll(solve(board, slotIndex))

		if (TerminalSolver.clickPrediction.value) {
			clickedSlots.forEach { (slot, button) -> simulateClick(slot, button) }
		}
	}

	fun forgetSentClicks() {
		clickedSlots.clear()
	}

	abstract fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int>

	protected abstract fun highlight(slotIndex: Int): SlotOverlay?

	/** What to draw over [slotIndex]. Melody overrides this to paint a resting colour under its notes too. */
	open fun overlay(slotIndex: Int): SlotOverlay? =
		if (slotIndex in solution) highlight(slotIndex) else null

	open fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex in solution

	/**
	 * The button a click is sent with. Left becomes middle, as Hypixel's terminals expect; Rubix
	 * reads the button and overrides this.
	 */
	open fun buttonFor(slotIndex: Int, requested: Int): Int =
		if (requested == GLFW.GLFW_MOUSE_BUTTON_RIGHT && type == TerminalType.RUBIX) {
			GLFW.GLFW_MOUSE_BUTTON_RIGHT
		} else {
			GLFW.GLFW_MOUSE_BUTTON_MIDDLE
		}

	/**
	 * Applies a click to the solution before the server confirms it. Wrong guesses are replaced by
	 * the next board.
	 */
	open fun simulateClick(slotIndex: Int, button: Int) {
		val at = solution.indexOf(slotIndex)
		if (at >= 0) solution.removeAt(at)
	}

	/** Sends a click, or drops it. */
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
		// Only sent clicks make a sound, so a blocked click is distinguishable from a missed one.
		if (TerminalSolver.replacesClickSound) TerminalSolver.playClickSound()

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
	 * True while a click would land too soon after the terminal opened. Hypixel bans clicks faster
	 * than a human could react, so those are dropped. The tick half covers a lagging server, which
	 * opened the window later than the clock thinks.
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
