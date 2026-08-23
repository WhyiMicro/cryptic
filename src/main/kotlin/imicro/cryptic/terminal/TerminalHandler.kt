package imicro.cryptic.terminal

import imicro.cryptic.feature.TerminalSimulator
import imicro.cryptic.feature.TerminalSolver
import imicro.cryptic.terminal.sim.TermSimScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/** What the solver paints over one slot: a colour, and a label when it has one. */
data class SlotOverlay(val argb: Int, val text: String? = null)

/**
 * One terminal the player has open, and the solution as it currently stands.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking): the solving
 * rules for all six terminals are its work, as is the shape of this class — a
 * handler per open window, re-solving whenever the chest's contents change.
 *
 * The solution list is copy-on-write because the render pass reads it on the
 * render thread while packets rewrite it on the client thread.
 */
abstract class TerminalHandler(val type: TerminalType) {
	val solution: CopyOnWriteArrayList<Int> = CopyOnWriteArrayList()

	val openedAt: Long = System.currentTimeMillis()

	/** True once anything has been clicked in the current window. */
	var clicked = false

	/**
	 * How many times Hypixel has re-sent this terminal's window.
	 *
	 * Every click is answered with a fresh window rather than a slot update, so
	 * this counts progress through the puzzle, not how many terminals were
	 * opened.
	 */
	var windowCount = 0
		private set

	/** Server ticks since the window opened, or -1 before the first one. */
	var serverTicksOpen = -1

	/**
	 * Whether a solve is worth running for a change to [changedSlot].
	 *
	 * Hypixel fills a window one slot at a time, so solving on every packet
	 * would solve a dozen half-built grids. Waiting for the last slot means
	 * solving once, on a window that is complete.
	 */
	open fun canSolve(items: List<ItemStack>, changedSlot: Int): Boolean =
		changedSlot == type.windowSize - 1

	/** True when the last update actually re-solved, so clicks can be released. */
	fun slotUpdated(changedSlot: Int, items: List<ItemStack>): Boolean {
		if (changedSlot !in 0 until type.windowSize) return false
		val window = items.subList(0, min(items.size, type.windowSize))
		if (!canSolve(window, changedSlot)) return false

		val solved = solve(window)
		solution.clear()
		solution.addAll(solved)
		// The board that came back is the server's answer to whatever was
		// clicked last, so the next click is free to go. Hypixel also opens a
		// fresh window each time, which says the same thing — but not relying
		// on that means one missing packet cannot wedge the queue shut.
		clicked = false
		return true
	}

	/**
	 * Neither clock is restarted here, on purpose: the ban risk is clicking
	 * before the terminal could have been read, and by the second window the
	 * player has been looking at it for a while already.
	 */
	fun windowOpened() {
		clicked = false
		windowCount++
	}

	/** The slots to click, in the order they should be clicked. */
	abstract fun solve(items: List<ItemStack>): List<Int>

	/** How a slot that is part of the solution should be painted. */
	protected abstract fun highlight(slotIndex: Int): SlotOverlay?

	/**
	 * What to draw over [slotIndex], or null to leave it empty.
	 *
	 * Only the solution is painted for five of the six; melody overrides this
	 * because its grid needs a resting colour under the notes as well.
	 */
	open fun overlay(slotIndex: Int): SlotOverlay? =
		if (slotIndex in solution) highlight(slotIndex) else null

	/** Whether clicking [slotIndex] with [button] would be a correct move. */
	open fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex in solution

	/**
	 * The button a click on [slotIndex] should actually be sent with, given the
	 * one the player pressed.
	 *
	 * Only rubix has anything to say here, and only when it has been told to
	 * accept a left click where the answer needs a right one.
	 */
	open fun buttonFor(slotIndex: Int, requested: Int): Int = requested

	/**
	 * The button this slot has to be clicked with, whatever anyone pressed.
	 * What the mod uses when it is clicking on the player's behalf.
	 */
	open fun preferredButton(slotIndex: Int): Int = GLFW.GLFW_MOUSE_BUTTON_MIDDLE

	/**
	 * Applies a click to the solution here and now, before the server has
	 * confirmed it.
	 *
	 * Queued and automatic clicking both need to know what the terminal will
	 * look like after the clicks already in flight, or the second click would
	 * be worked out against a board that is one move out of date. Every
	 * prediction is thrown away and rebuilt the moment the server sends the
	 * real window, so a wrong guess costs nothing.
	 */
	open fun predict(slotIndex: Int, button: Int) {
		val at = solution.indexOf(slotIndex)
		if (at >= 0) solution.removeAt(at)
	}

	/**
	 * Sends the click.
	 *
	 * A left click goes out as a middle click, which is what Hypixel's
	 * terminals want and what Odin sends as well; a right click is passed
	 * through as itself, because two of the six read the button.
	 */
	open fun click(slotIndex: Int, button: Int) {
		val client = Minecraft.getInstance()
		val screen = client.screen as? AbstractContainerScreen<*> ?: return
		clicked = true

		if (screen is TermSimScreen) {
			screen.clickIndex(slotIndex, button)
			return
		}

		val player = client.player ?: return
		val input = if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) ContainerInput.CLONE else ContainerInput.PICKUP
		client.gameMode?.handleContainerInput(screen.menu.containerId, slotIndex, button, input, player)
	}

	/**
	 * True while a click would land too soon after the window opened.
	 *
	 * Hypixel bans for clicking a terminal faster than a human could have seen
	 * it, and the solver makes that trivially easy to do by accident, so clicks
	 * inside the window are dropped rather than sent. The clock alone is not
	 * enough when the server is behind — it opened the window late, so the
	 * player's own screen has been up for less time than the timer thinks —
	 * which is what the tick half is for.
	 */
	fun blocksClicks(): Boolean {
		if (TerminalSimulator.skipClickProtection.value && Minecraft.getInstance().screen is TermSimScreen) {
			return false
		}
		if (System.currentTimeMillis() - openedAt < TerminalSolver.firstClickProtection.value) return true

		return TerminalSolver.accountForServerLag.value &&
			ServerTicks.available &&
			serverTicksOpen < TerminalSolver.lagProtectionTicks.value
	}
}
