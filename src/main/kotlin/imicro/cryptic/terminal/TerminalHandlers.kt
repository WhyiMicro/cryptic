package imicro.cryptic.terminal

import imicro.cryptic.feature.MelodyHud
import imicro.cryptic.feature.TerminalSolver
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
 * odtheking). Each is asked for a solution whenever a slot of the open terminal
 * changes, and for a colour whenever one of its slots is drawn.
 */

/** Marked as taken by Hypixel, which it does by adding an enchantment glint. */
private fun ItemStack.isMarked(): Boolean =
	components.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)

private fun ItemStack.paneColor(): DyeColor? =
	((item as? BlockItem)?.block as? StainedGlassPaneBlock)?.color

/** Every red pane is wrong and has to be turned green. */
class PanesHandler : TerminalHandler(TerminalType.PANES) {
	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> =
		items.mapIndexedNotNull { index, item ->
			index.takeIf { item.item == Items.STAINED_GLASS_PANE.red() }
		}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.panesColor.argb)
}

/**
 * Click the ten panes in the order of the numbers on them. Fourteen before
 * SkyBlock 0.27.2.
 *
 * The stack size is the number, so sorting by it is the whole solve. Only the
 * first of the remaining clicks is allowed, because clicking out of order
 * resets the puzzle — and a predicted click always takes the front of the
 * queue for the same reason.
 */
class NumbersHandler : TerminalHandler(TerminalType.NUMBERS) {
	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> =
		items.mapIndexedNotNull { index, item ->
			index.takeIf { item.item == Items.STAINED_GLASS_PANE.red() }
		}.sortedBy { items[it].count }

	override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex == solution.firstOrNull()

	override fun simulateClick(slotIndex: Int, button: Int) {
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
		// Unless the numbers are switched off, which leaves the three colours to
		// say the order on their own.
		if (TerminalSolver.hideNumbers.value) return SlotOverlay(color)
		return SlotOverlay(color, (abs((solution.size - GRID_SLOTS) - position) + 1).toString())
	}

	private companion object {
		const val GRID_SLOTS = 10

		/** Fully transparent: the label still draws, the highlight does not. */
		const val UNPAINTED = 0
	}
}

/**
 * Cycle every pane to one colour, in as few clicks as possible.
 *
 * The five colours are a loop: a left click steps forward, a right click steps
 * back. Odin's current solver prices every candidate colour once the board has
 * finished arriving and keeps whichever costs fewest clicks, counting a pane
 * more than two steps away as a cheaper walk backwards — unless **Rubix mode**
 * says left clicks only, in which case everything goes the long way round.
 *
 * The colour is locked the first time the last pane of the grid arrives, so a
 * re-solve halfway through the puzzle cannot change its mind and undo the
 * clicks already made.
 */
class RubixHandler : TerminalHandler(TerminalType.RUBIX) {
	private var lockedColor: DyeColor? = null

	/** Slots the answer wants clicked backwards, which is a right click. */
	private val rightClickSlots = HashSet<Int>()

	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> {
		val panes = items.mapIndexedNotNull { index, item ->
			item.paneColor()?.takeUnless { it == DyeColor.BLACK }?.let { index to it }
		}

		if (updatedIndex == LAST_PANE_SLOT && lockedColor == null) {
			lockedColor = ORDER.minByOrNull { goal -> clicksFor(goal, panes).values.sumOf { abs(it) } }
		}

		val clicks = lockedColor?.let { clicksFor(it, panes) }.orEmpty()

		rightClickSlots.clear()
		clicks.forEach { (slotIndex, count) -> if (count < 0) rightClickSlots.add(slotIndex) }
		// One entry per click, so a pane needing two appears twice.
		return clicks.flatMap { (slotIndex, count) -> List(abs(count)) { slotIndex } }
	}

	/**
	 * How many clicks each pane needs to reach [goal], signed: positive is
	 * forward and negative is backward.
	 */
	private fun clicksFor(goal: DyeColor, panes: List<Pair<Int, DyeColor>>): Map<Int, Int> {
		val goalIndex = ORDER.indexOf(goal)
		return panes.associate { (slotIndex, color) ->
			val forward = stepsBetween(ORDER.indexOf(color), goalIndex)
			slotIndex to if (forward > 2 && !TerminalSolver.leftClicksOnly) forward - ORDER.size else forward
		}.filterValues { it != 0 }
	}

	/** Forward steps around the loop, which is what a left click moves. */
	private fun stepsBetween(from: Int, to: Int): Int =
		if (from > to) (to + ORDER.size) - from else to - from

	/**
	 * A pane may only be clicked the way the answer wants it, which is what
	 * stops a slip of the hand sending it the long way round.
	 *
	 * **One button** is the exception: either button is accepted on any pane,
	 * because the button that goes out is chosen by [buttonFor] rather than by
	 * the hand.
	 */
	override fun canClick(slotIndex: Int, button: Int): Boolean {
		if (slotIndex !in solution) return false
		if (TerminalSolver.rubixOneButton) return true
		return (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) == (slotIndex in rightClickSlots)
	}

	/**
	 * In **One button** the answer picks the button: a pane that is quicker
	 * backwards is right-clicked whichever way it was clicked, and every other
	 * pane is left-clicked. Anywhere else the hand decides and this only maps a
	 * left click onto the middle click Hypixel expects.
	 */
	override fun buttonFor(slotIndex: Int, requested: Int): Int {
		if (!TerminalSolver.rubixOneButton) return super.buttonFor(slotIndex, requested)
		return if (slotIndex in rightClickSlots) {
			GLFW.GLFW_MOUSE_BUTTON_RIGHT
		} else {
			GLFW.GLFW_MOUSE_BUTTON_MIDDLE
		}
	}

	override fun highlight(slotIndex: Int): SlotOverlay? {
		val remaining = solution.count { it == slotIndex }.takeIf { it > 0 } ?: return null
		val clicks = if (slotIndex in rightClickSlots) -remaining else remaining
		val color = when (clicks) {
			1 -> TerminalSolver.rubixColor1
			2 -> TerminalSolver.rubixColor2
			-1, 4 -> TerminalSolver.rubixReverseColor1
			else -> TerminalSolver.rubixReverseColor2
		}
		return SlotOverlay(color.argb, clicks.toString())
	}

	private companion object {
		/** The last slot of the three-by-three grid, so the board is complete. */
		const val LAST_PANE_SLOT = 32

		val ORDER = listOf(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED)
	}
}

/**
 * Click every item whose name starts with a given letter.
 *
 * Hypixel marks an item as taken by adding an enchantment glint, so the solve
 * is a name test plus a glint test. A handful of items carry that component
 * already and would be invisible to the solver, so a slot that has been clicked
 * is remembered until it comes back from the server, and then written off as
 * taken whatever it looks like.
 */
class StartsWithHandler(private val letter: String) : TerminalHandler(TerminalType.STARTS_WITH) {
	/** Slot to "has the server answered for it yet". */
	private val clickedOverrides = HashMap<Int, Boolean>()

	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> {
		clickedOverrides.computeIfPresent(updatedIndex) { _, _ -> true }

		return items.mapIndexedNotNull { index, item ->
			val matches = item.hoverName.string.startsWith(letter, ignoreCase = true) &&
				clickedOverrides[index] != true &&
				(!item.isMarked() || item.item in ALWAYS_GLINTING)
			index.takeIf { matches }
		}
	}

	override fun click(slotIndex: Int, button: Int) {
		if (canClick(slotIndex, button) && slotIndex !in clickedOverrides) clickedOverrides[slotIndex] = false
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

	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> =
		items.mapIndexedNotNull { index, item ->
			if (item.isMarked() || item.item == Items.STAINED_GLASS_PANE.black()) return@mapIndexedNotNull null
			val name = item.hoverName.string.lowercase()
			index.takeIf { prefixes.any(name::startsWith) }
		}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.selectColor.argb)
}

/**
 * Press the button as the moving pane crosses the marked column.
 *
 * Unlike the other five this has no solution to work towards: it is a moving
 * target, so the grid is drawn whole, with a resting colour under the slots
 * that are not lit. The solution holds three things — the marker's column, the
 * note's position, and the button itself, but only while the two line up.
 */
class MelodyHandler : TerminalHandler(TerminalType.MELODY) {
	init {
		// A new melody starts its count of rows again.
		MelodyHud.onTerminalOpened()
	}

	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> {
		val magenta = items.indexOfFirst { it.item == Items.STAINED_GLASS_PANE.magenta() }
		val lime = items.indexOfLast { it.item == Items.STAINED_GLASS_PANE.lime() }
		val button = items.indexOfLast { it.item == Items.DYED_TERRACOTTA.lime() }
		// The one lit button is the row being played, which is how far the
		// melody has got; the party is told as it moves down, and the relay
		// as the note moves along.
		MelodyHud.onOwnBoard(magenta, lime, button)

		return buildList {
			if (lime >= 0) add(lime)
			// The marker is shown above the rows and again below them, as Odin
			// draws it since the board lost a row.
			if (magenta >= 0) {
				add(magenta)
				if (magenta / 9 == 0) add(magenta + 9 * (BOTTOM_ROW))
			}
			// The button only counts once the note has reached the column the
			// marker sits in, which is the moment to press it.
			if (button >= 0 && lime >= 0 && magenta >= 0 && lime % 9 == magenta % 9) add(button)
		}
	}

	/**
	 * The three buttons are always pressable, and pressing one at the wrong
	 * moment is how melody is failed — so the answer is not allowed to say
	 * otherwise, and the press is the player's to time.
	 */
	override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex in BUTTON_SLOTS

	// No simulateClick override, on purpose: the base one takes the pressed
	// button out of the answer, so with Client prediction on it goes dark the
	// instant it is pressed rather than a round trip later. This used to opt
	// out ("the note moves whether or not the button was pressed"), which is
	// true of the note but not of the button — and the button staying lit for
	// the length of your ping after you hit it is what made melody feel slower
	// than Odin's, which never opted out.

	override fun overlay(slotIndex: Int): SlotOverlay? {
		val row = slotIndex / 9
		val column = slotIndex % 9
		val lit = slotIndex in solution
		return when {
			// The rows above and below are the marker, only drawn where it is.
			row == 0 || row == BOTTOM_ROW -> if (lit) SlotOverlay(TerminalSolver.melodyColumnColor.argb) else null
			row !in PLAY_ROWS -> null
			column == BUTTON_COLUMN || column in NOTE_COLUMNS ->
				SlotOverlay(
					if (lit) TerminalSolver.melodyPointerColor.argb else TerminalSolver.melodySlotColor.argb,
				)
			else -> null
		}
	}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.melodyPointerColor.argb)

	private companion object {
		/** Three rows to play since SkyBlock 0.27.2, between the marker's two rows. */
		val BUTTON_SLOTS = setOf(16, 25, 34)
		val PLAY_ROWS = 1..3
		const val BOTTOM_ROW = 4
		const val BUTTON_COLUMN = 7
		val NOTE_COLUMNS = 1..5
	}
}
