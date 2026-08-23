package imicro.cryptic.terminal

import imicro.cryptic.feature.TerminalSolver
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.StainedGlassPaneBlock
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

/**
 * The six solvers, ported from Odin (BSD 3-Clause, Copyright (c) 2025
 * odtheking). Each one is asked for a solution whenever Hypixel finishes
 * sending a window, and for a colour whenever a slot of that window is drawn.
 */

/** Marked as clicked by Hypixel, whichever way round the mod that set it meant. */
private fun ItemStack.isMarked(): Boolean =
	components.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)

private fun ItemStack.paneColor(): DyeColor? =
	((item as? BlockItem)?.block as? StainedGlassPaneBlock)?.color

/** Every red pane is wrong and has to be turned green. */
class PanesHandler : TerminalHandler(TerminalType.PANES) {
	override fun solve(items: List<ItemStack>): List<Int> =
		items.mapIndexedNotNull { index, item ->
			index.takeIf { item.item == Items.RED_STAINED_GLASS_PANE }
		}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.panesColor.argb)
}

/**
 * Click the fourteen panes in the order of the numbers on them.
 *
 * The stack size is the number, so sorting by it is the whole solve. Only the
 * first of the remaining clicks is allowed, because clicking out of order
 * resets the puzzle.
 */
class NumbersHandler : TerminalHandler(TerminalType.NUMBERS) {
	override fun solve(items: List<ItemStack>): List<Int> =
		items.mapIndexedNotNull { index, item ->
			index.takeIf { item.item == Items.RED_STAINED_GLASS_PANE }
		}.sortedBy { items[it].count }

	override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex == solution.firstOrNull()

	/** The clicked pane is always the one at the front of the queue. */
	override fun predict(slotIndex: Int, button: Int) {
		if (solution.isNotEmpty()) solution.removeAt(0)
	}

	override fun highlight(slotIndex: Int): SlotOverlay {
		val position = solution.indexOf(slotIndex)
		// Only the next three are worth aiming at; the rest keep their number
		// so the run can be read ahead, but nothing is painted under it.
		val color = when (position) {
			0 -> TerminalSolver.orderColor1.argb
			1 -> TerminalSolver.orderColor2.argb
			2 -> TerminalSolver.orderColor3.argb
			else -> UNPAINTED
		}
		// The label is the number written on the pane, not how far down the
		// remaining list it is, so it keeps counting up as the puzzle empties.
		val label = if (TerminalSolver.showNumbers.value) {
			(abs((solution.size - GRID_SLOTS) - position) + 1).toString()
		} else {
			null
		}
		return SlotOverlay(color, label)
	}

	private companion object {
		const val GRID_SLOTS = 14

		/** Fully transparent: the label still draws, the highlight does not. */
		const val UNPAINTED = 0
	}
}

/**
 * Cycle every pane to one colour, in as few clicks as possible.
 *
 * The five colours are a loop: a left click steps forward, a right click steps
 * back. For each candidate colour the solver counts the forward steps every
 * pane needs, and keeps whichever target costs least once backwards clicks are
 * priced in — three forward clicks are two backwards ones. The chosen colour
 * is remembered so a re-solve mid-puzzle does not switch targets halfway.
 */
class RubixHandler : TerminalHandler(TerminalType.RUBIX) {
	private var target: DyeColor? = null

	override fun solve(items: List<ItemStack>): List<Int> {
		// Black panes are the filler around the grid, not part of the puzzle.
		val panes = items.filter { stack ->
			val color = stack.paneColor()
			color != null && color != DyeColor.BLACK
		}

		target?.let { chosen ->
			return clicksToward(items, panes, ORDER.indexOf(chosen))
		}

		var best: List<Int> = List(FAR_TOO_MANY) { it }
		for (color in ORDER) {
			val candidate = clicksToward(items, panes, ORDER.indexOf(color))
			if (cost(candidate) < cost(best)) {
				best = candidate
				target = color
			}
		}
		return best
	}

	/** One entry per click, so a pane needing two clicks appears twice. */
	private fun clicksToward(items: List<ItemStack>, panes: List<ItemStack>, goal: Int): List<Int> =
		panes.flatMap { pane ->
			val index = ORDER.indexOf(pane.paneColor() ?: return@flatMap emptyList())
			if (index == goal) emptyList() else List(stepsBetween(index, goal)) { items.indexOf(pane) }
		}

	/** Forward steps around the loop, which is the only direction a click moves. */
	private fun stepsBetween(from: Int, to: Int): Int =
		if (from > to) (to + ORDER.size) - from else to - from

	/** Clicks actually needed, counting a run of three or more backwards. */
	private fun cost(clicks: List<Int>): Int =
		clicks.distinct().sumOf { slot ->
			val count = clicks.count { it == slot }
			if (count >= 3) ORDER.size - count else count
		}

	override fun canClick(slotIndex: Int, button: Int): Boolean {
		if (slotIndex !in solution) return false
		val rightClick = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
		// One or two steps forward is a left click; three or four is quicker
		// the other way round, and has to be a right click.
		return if (stepsFor(slotIndex) < 3) !rightClick else rightClick
	}

	/**
	 * With **Rubix left click** on, the button the player pressed is thrown
	 * away and the one the answer needs is sent instead — so the whole terminal
	 * can be played with one finger, and a left click on a slot that has to go
	 * backwards still goes backwards.
	 */
	override fun buttonFor(slotIndex: Int, requested: Int): Int =
		if (TerminalSolver.rubixLeftClick.value) preferredButton(slotIndex) else requested

	override fun preferredButton(slotIndex: Int): Int =
		if (stepsFor(slotIndex) >= 3) GLFW.GLFW_MOUSE_BUTTON_RIGHT else GLFW.GLFW_MOUSE_BUTTON_MIDDLE

	/**
	 * A forward click takes one step off; a backward one adds a step, because
	 * the solution counts forward steps and going back the long way is the
	 * same as going forward [ORDER].size times.
	 */
	override fun predict(slotIndex: Int, button: Int) {
		if (slotIndex !in solution) return
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) solution.add(slotIndex) else solution.remove(slotIndex)
	}

	private fun stepsFor(slotIndex: Int) = solution.count { it == slotIndex }

	override fun highlight(slotIndex: Int): SlotOverlay? {
		val forward = solution.count { it == slotIndex }
		val clicks = if (forward < 3) forward else forward - ORDER.size
		if (clicks == 0) return null
		val color = when (clicks) {
			1 -> TerminalSolver.rubixColor1
			2 -> TerminalSolver.rubixColor2
			-1 -> TerminalSolver.rubixReverseColor1
			else -> TerminalSolver.rubixReverseColor2
		}
		return SlotOverlay(color.argb, clicks.toString())
	}

	private companion object {
		val ORDER = listOf(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED)

		/** A starting cost no real solution can reach, so the first one wins. */
		const val FAR_TOO_MANY = 100
	}
}

/**
 * Click every item whose name starts with a given letter.
 *
 * Hypixel marks an item as taken by adding an enchantment glint, so the solve
 * is a name test plus a glint test. A handful of items carry that component to
 * begin with, and would otherwise be invisible to the solver, so the slot that
 * was clicked last is watched until the next window arrives and recorded as
 * taken by hand if it turns out to be one of them.
 */
class StartsWithHandler(private val letter: String) : TerminalHandler(TerminalType.STARTS_WITH) {
	private val takenSlots = mutableSetOf<Int>()
	private var pendingClick: Pair<Int, Int>? = null

	override fun solve(items: List<ItemStack>): List<Int> {
		pendingClick?.let { (containerId, slot) ->
			val menu = (Minecraft.getInstance().screen as? AbstractContainerScreen<*>)?.menu
			if (containerId != menu?.containerId) {
				if (items.getOrNull(slot)?.item in ALWAYS_GLINTING) takenSlots.add(slot)
				pendingClick = null
			}
		}

		return items.mapIndexedNotNull { index, item ->
			val matches = item.hoverName.string.startsWith(letter, ignoreCase = true) &&
				index !in takenSlots &&
				(!item.isMarked() || item.item in ALWAYS_GLINTING)
			index.takeIf { matches }
		}
	}

	override fun click(slotIndex: Int, button: Int) {
		if (pendingClick == null && canClick(slotIndex, button)) {
			val menu = (Minecraft.getInstance().screen as? AbstractContainerScreen<*>)?.menu
			if (menu != null) pendingClick = menu.containerId to slotIndex
		}
		super.click(slotIndex, button)
	}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.startsWithColor.argb)

	private companion object {
		/** Items that glint before anyone has touched them. */
		val ALWAYS_GLINTING = BuiltInRegistries.ITEM
			.filter { it.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) }
			.toSet() + Items.GOLDEN_APPLE
	}
}

/**
 * Click every item of one dye colour.
 *
 * Names are the only clue, and several of them do not contain the colour at
 * all — lapis is the blue one, cocoa the brown one — so each colour carries the
 * prefixes it can appear under.
 */
class SelectAllHandler(color: DyeColor) : TerminalHandler(TerminalType.SELECT) {
	private val prefixes = when (color) {
		DyeColor.BLACK -> setOf("black", "ink")
		DyeColor.BLUE -> setOf("blue", "lapis")
		DyeColor.BROWN -> setOf("brown", "cocoa")
		DyeColor.WHITE -> setOf("white", "bone", "wool")
		DyeColor.GREEN -> setOf("green", "cactus")
		DyeColor.RED -> setOf("red", "rose")
		DyeColor.YELLOW -> setOf("yellow", "dandelion")
		DyeColor.LIGHT_GRAY -> setOf("silver", "light gray")
		else -> setOf(color.name.lowercase().replace('_', ' '))
	}

	override fun solve(items: List<ItemStack>): List<Int> =
		items.mapIndexedNotNull { index, item ->
			if (item.isMarked() || item.item == Items.BLACK_STAINED_GLASS_PANE) return@mapIndexedNotNull null
			val name = item.hoverName.string.lowercase()
			index.takeIf { prefixes.any(name::startsWith) }
		}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.selectColor.argb)
}

/**
 * Press the button as the moving pane crosses the marked column.
 *
 * Unlike the other five this has no solution to work towards: it is a moving
 * target, so it re-solves on every slot update and the grid is drawn whole,
 * with a resting colour under the slots that are not lit.
 */
class MelodyHandler : TerminalHandler(TerminalType.MELODY) {
	override fun canSolve(items: List<ItemStack>, changedSlot: Int): Boolean = true

	override fun solve(items: List<ItemStack>): List<Int> {
		val magenta = items.indexOfFirst { it.item == Items.MAGENTA_STAINED_GLASS_PANE }
		val lime = items.indexOfLast { it.item == Items.LIME_STAINED_GLASS_PANE }
		val button = items.indexOfLast { it.item == Items.LIME_TERRACOTTA }

		return items.mapIndexedNotNull { index, item ->
			when {
				index == lime || item.item == Items.MAGENTA_STAINED_GLASS_PANE -> index
				// The button only counts once the note has reached the column
				// the magenta marker sits in, which is the moment to press it.
				index == button && lime % 9 == magenta % 9 -> index
				else -> null
			}
		}
	}

	/**
	 * The four buttons are always pressable, and pressing one at the wrong
	 * moment is how melody is failed. **Melody click protection** takes that
	 * away: the button only enters the solution while the note is on the mark,
	 * so requiring it there means the press can only ever be the right one.
	 */
	override fun canClick(slotIndex: Int, button: Int): Boolean =
		slotIndex in BUTTON_SLOTS &&
			(!TerminalSolver.melodyClickProtection.value || slotIndex in solution)

	/**
	 * Nothing to predict: the note is moving whether or not the button was
	 * pressed, so guessing at the board would only put the pointer in the wrong
	 * place until the next update corrected it.
	 */
	override fun predict(slotIndex: Int, button: Int) = Unit

	override fun overlay(slotIndex: Int): SlotOverlay? {
		val row = slotIndex / 9
		val column = slotIndex % 9
		val lit = slotIndex in solution
		return when {
			// The top row is the marker, and is only drawn where it is.
			row == 0 -> if (lit) SlotOverlay(TerminalSolver.melodyColumnColor.argb) else null
			column == BUTTON_COLUMN || column in NOTE_COLUMNS ->
				SlotOverlay(
					if (lit) {
						TerminalSolver.melodyPointerColor.argb
					} else {
						TerminalSolver.melodySlotColor.argb
					},
				)
			else -> null
		}
	}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.melodyPointerColor.argb)

	private companion object {
		val BUTTON_SLOTS = setOf(16, 25, 34, 43)
		const val BUTTON_COLUMN = 7
		val NOTE_COLUMNS = 1..5
	}
}
