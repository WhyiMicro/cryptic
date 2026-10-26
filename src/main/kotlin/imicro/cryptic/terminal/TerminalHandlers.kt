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

/** The six terminal solvers, ported from Odin. */

/** Hypixel marks a taken item with an enchantment glint. */
private fun ItemStack.isMarked(): Boolean =
	components.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)

private fun ItemStack.paneColor(): DyeColor? =
	((item as? BlockItem)?.block as? StainedGlassPaneBlock)?.color

class PanesHandler : TerminalHandler(TerminalType.PANES) {
	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> =
		items.mapIndexedNotNull { index, item ->
			index.takeIf { item.item == Items.STAINED_GLASS_PANE.red() }
		}

	override fun highlight(slotIndex: Int) = SlotOverlay(TerminalSolver.panesColor.argb)
}

/**
 * Click ten numbered panes in order (fourteen before SkyBlock 0.27.2). The stack size is the
 * number. Only the next click is allowed, since out of order resets the puzzle.
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
		// Only the next three are coloured; the rest keep their number so the run can be read
		// ahead.
		val color = when (position) {
			0 -> TerminalSolver.orderColor1.argb
			1 -> TerminalSolver.orderColor2.argb
			2 -> TerminalSolver.orderColor3.argb
			else -> UNPAINTED
		}
		// The label is the pane's own number. With numbers hidden, the three colours show the order
		// alone.
		if (TerminalSolver.hideNumbers.value) return SlotOverlay(color)
		return SlotOverlay(color, (abs((solution.size - GRID_SLOTS) - position) + 1).toString())
	}

	private companion object {
		const val GRID_SLOTS = 10

		/** Transparent: the label draws, the highlight doesn't. */
		const val UNPAINTED = 0
	}
}

/**
 * Cycle every pane to one colour in as few clicks as possible. The five colours loop: left steps
 * forward, right steps back. Each candidate colour is priced once the board is complete and the
 * cheapest kept; **Rubix mode** "No minus" forces left clicks only. The colour is locked when the grid's
 * last pane arrives, so a re-solve can't undo clicks already made.
 */
class RubixHandler : TerminalHandler(TerminalType.RUBIX) {
	private var lockedColor: DyeColor? = null

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

	/** Clicks each pane needs to reach [goal], signed: positive forward, negative backward. */
	private fun clicksFor(goal: DyeColor, panes: List<Pair<Int, DyeColor>>): Map<Int, Int> {
		val goalIndex = ORDER.indexOf(goal)
		return panes.associate { (slotIndex, color) ->
			val forward = stepsBetween(ORDER.indexOf(color), goalIndex)
			slotIndex to if (forward > 2 && !TerminalSolver.leftClicksOnly) forward - ORDER.size else forward
		}.filterValues { it != 0 }
	}

	private fun stepsBetween(from: Int, to: Int): Int =
		if (from > to) (to + ORDER.size) - from else to - from

	/**
	 * A pane can only be clicked the way the answer wants. In **One button** either button works,
	 * since [buttonFor] picks the real one.
	 */
	override fun canClick(slotIndex: Int, button: Int): Boolean {
		if (slotIndex !in solution) return false
		if (TerminalSolver.rubixOneButton) return true
		return (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) == (slotIndex in rightClickSlots)
	}

	/**
	 * In **One button**, the answer picks the button: right for panes quicker backwards, left
	 * otherwise. Elsewhere the hand decides.
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
		const val LAST_PANE_SLOT = 32

		val ORDER = listOf(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED)
	}
}

/**
 * Click every item whose name starts with a letter. Taken items glint, but some items glint
 * already, so a clicked slot is remembered until the server answers and then counts as taken.
 */
class StartsWithHandler(private val letter: String) : TerminalHandler(TerminalType.STARTS_WITH) {
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
		val ALWAYS_GLINTING = BuiltInRegistries.ITEM
			.filter { it.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) }
			.toSet() + Items.GOLDEN_APPLE
	}
}

/**
 * Click every item of one dye colour, matched by name. Some names don't contain the colour (lapis
 * is blue, cocoa brown), so each colour lists its prefixes.
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
 * Press the button as the moving pane crosses the marked column. No solution to work towards: the
 * grid is drawn whole, and the solution holds the marker's column, the note, and the button while
 * they line up.
 */
class MelodyHandler : TerminalHandler(TerminalType.MELODY) {
	init {
		MelodyHud.onTerminalOpened()
	}

	override fun solve(items: List<ItemStack>, updatedIndex: Int): List<Int> {
		val magenta = items.indexOfFirst { it.item == Items.STAINED_GLASS_PANE.magenta() }
		val lime = items.indexOfLast { it.item == Items.STAINED_GLASS_PANE.lime() }
		val button = items.indexOfLast { it.item == Items.DYED_TERRACOTTA.lime() }
		// The lit button is the row being played; the party and the relay are told as it moves.
		MelodyHud.onOwnBoard(magenta, lime, button)

		return buildList {
			if (lime >= 0) add(lime)
			// The marker shows above and below the rows, as Odin draws it.
			if (magenta >= 0) {
				add(magenta)
				if (magenta / 9 == 0) add(magenta + 9 * (BOTTOM_ROW))
			}
			// The button counts once the note reaches the marker's column.
			if (button >= 0 && lime >= 0 && magenta >= 0 && lime % 9 == magenta % 9) add(button)
		}
	}

	/** The buttons are always pressable; timing the press is the player's job. */
	override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex in BUTTON_SLOTS

	// No simulateClick override on purpose: the base one darkens the button the instant it's
	// pressed rather than a ping later.

	override fun overlay(slotIndex: Int): SlotOverlay? {
		val row = slotIndex / 9
		val column = slotIndex % 9
		val lit = slotIndex in solution
		return when {
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
		/** Three rows to play since SkyBlock 0.27.2. */
		val BUTTON_SLOTS = setOf(16, 25, 34)
		val PLAY_ROWS = 1..3
		const val BOTTOM_ROW = 4
		const val BUTTON_COLUMN = 7
		val NOTE_COLUMNS = 1..5
	}
}
