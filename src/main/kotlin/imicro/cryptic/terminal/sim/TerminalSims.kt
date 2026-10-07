package imicro.cryptic.terminal.sim

import imicro.cryptic.terminal.TerminalRecords
import imicro.cryptic.terminal.TerminalType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.util.Locale
import net.minecraft.world.level.block.StainedGlassPaneBlock

/**
 * The six terminals, rebuilt client-side. Ported from Odin's term-sim screens
 * (BSD 3-Clause, Copyright (c) 2025 odtheking).
 *
 * Each one only has to be convincing to two audiences: the player, and the
 * solver reading the chest over their shoulder. So the items are the same items
 * Hypixel sends, in the same slots, and nothing else is modelled.
 */

private fun pane(item: Item) = named(ItemStack(item), "")

private val ItemStack.paneColor: DyeColor?
	get() = ((item as? BlockItem)?.block as? StainedGlassPaneBlock)?.color

private fun ItemStack.isMarked(): Boolean = components.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)

private fun Slot.row() = index / 9

private fun Slot.column() = index % 9

/** Turn every red pane green. */
class PanesSim : TermSimScreen(TerminalType.PANES.termName, TerminalType.PANES.windowSize) {
	override fun create() = rebuild { slot ->
		if (slot.row() in 1..3 && slot.column() in 2..6) {
			if (Math.random() > 0.75) pane(Items.STAINED_GLASS_PANE.lime()) else pane(Items.STAINED_GLASS_PANE.red())
		} else {
			fillerPane
		}
	}

	override fun slotClick(slot: Slot, button: Int) {
		rebuild { other ->
			if (other !== slot) {
				other.item
			} else if (slot.item.item == Items.STAINED_GLASS_PANE.red()) {
				pane(Items.STAINED_GLASS_PANE.lime())
			} else {
				pane(Items.STAINED_GLASS_PANE.red())
			}
		}

		if (gridSlots.none { it.item.item == Items.STAINED_GLASS_PANE.red() }) return completed()
		super.slotClick(slot, button)
	}
}

/** Cycle nine panes until they all match. */
class RubixSim : TermSimScreen(TerminalType.RUBIX.termName, TerminalType.RUBIX.windowSize) {
	override fun create() = rebuild { slot ->
		if (slot.row() in 1..3 && slot.column() in 3..5) pane(PANES.random()) else fillerPane
	}

	override fun slotClick(slot: Slot, button: Int) {
		val current = slot.item.paneColor?.let(ORDER::indexOf)?.takeIf { it >= 0 } ?: return
		// A right click steps back around the loop, a left click forward.
		val next = if (button == 1) (current - 1 + ORDER.size) % ORDER.size else (current + 1) % ORDER.size
		rebuild { other -> if (other === slot) pane(PANES[next]) else other.item }

		val goal = gridSlots[GRID.first()].item.item
		if (GRID.all { gridSlots[it].item.item == goal }) return completed()
		super.slotClick(slot, button)
	}

	private companion object {
		val ORDER = listOf(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED)
		val PANES = listOf(
			Items.STAINED_GLASS_PANE.orange(),
			Items.STAINED_GLASS_PANE.yellow(),
			Items.STAINED_GLASS_PANE.green(),
			Items.STAINED_GLASS_PANE.blue(),
			Items.STAINED_GLASS_PANE.red(),
		)
		val GRID = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
	}
}

/** Click ten numbered panes in order. */
class NumbersSim : TermSimScreen(TerminalType.NUMBERS.termName, TerminalType.NUMBERS.windowSize) {
	override fun create() {
		val numbers = (1..10).shuffled().iterator()
		rebuild { slot ->
			if (slot.row() in 1..2 && slot.column() in 2..6) {
				val number = numbers.next()
				named(ItemStack(Items.STAINED_GLASS_PANE.red(), number), "§a$number")
			} else {
				fillerPane
			}
		}
	}

	override fun slotClick(slot: Slot, button: Int) {
		// Out of order does nothing at all, which is what makes the real one
		// worth practising.
		val next = gridSlots.minByOrNull {
			if (it.item.item == Items.STAINED_GLASS_PANE.red()) it.item.count else Int.MAX_VALUE
		}
		if (next !== slot) return

		rebuild { other ->
			if (other === slot) named(ItemStack(Items.STAINED_GLASS_PANE.lime(), slot.item.count), "") else other.item
		}

		if (gridSlots.none { it.item.item == Items.STAINED_GLASS_PANE.red() }) return completed()
		super.slotClick(slot, button)
	}
}

/** Click every item starting with one letter. */
class StartsWithSim(
	private val letter: String = LETTERS.random(),
) : TermSimScreen("What starts with: '$letter'?", TerminalType.STARTS_WITH.windowSize) {
	override fun create() {
		// One match is guaranteed, so a window can never open already solved.
		val guaranteed = (10..16).random()
		rebuild { slot ->
			when {
				slot.row() !in 1..3 || slot.column() !in 1..7 -> fillerPane
				slot.index == guaranteed || Math.random() > 0.7 -> itemStartingWith(true)
				else -> itemStartingWith(false)
			}
		}
	}

	override fun slotClick(slot: Slot, button: Int) {
		val stack = slot.item
		if (!stack.hoverName.string.startsWith(letter, ignoreCase = true) || stack.isMarked()) {
			return message("§cThat item does not start with '$letter'.")
		}

		rebuild { other ->
			if (other === slot) stack.apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false) } else other.item
		}

		if (gridSlots.none { it.item.hoverName.string.startsWith(letter, true) && !it.item.isMarked() }) {
			return completed()
		}
		super.slotClick(slot, button)
	}

	/**
	 * Both pools are worked out once rather than per slot, because sweeping the
	 * item registry forty-five times to fill one chest is a visible pause.
	 */
	private val pools: Map<Boolean, List<Item>> by lazy {
		BuiltInRegistries.ITEM
			.filter { item ->
				val path = BuiltInRegistries.ITEM.getKey(item).path
				item != Items.AIR && !path.contains("pane", ignoreCase = true)
			}
			.groupBy { BuiltInRegistries.ITEM.getKey(it).path.startsWith(letter, ignoreCase = true) }
	}

	private fun itemStartingWith(matching: Boolean): ItemStack =
		pools[matching]?.randomOrNull()?.let(::ItemStack) ?: fillerPane

	private companion object {
		val LETTERS = listOf("A", "B", "C", "G", "D", "M", "N", "R", "S", "T", "W")
	}
}

/** Click every item of one colour. */
class SelectAllSim(
	private val color: DyeColor = DyeColor.entries.random(),
) : TermSimScreen(
	"Select all the ${color.name.replace("LIGHT_GRAY", "SILVER").replace('_', ' ')} items!",
	TerminalType.SELECT.windowSize,
) {
	private val wanted = itemsOf(color)

	override fun create() {
		val guaranteed = ((10..16) + (19..25) + (28..34) + (37..43)).random()
		rebuild { slot ->
			when {
				slot.row() !in 1..4 || slot.column() !in 1..7 -> fillerPane
				slot.index == guaranteed || Math.random() > 0.75 -> ItemStack(wanted.random())
				else -> ItemStack(itemsOf(DyeColor.entries.filter { it != color }.random()).random())
			}
		}
	}

	override fun slotClick(slot: Slot, button: Int) {
		if (slot.item.item !in wanted) {
			return message("§cThat item is not ${color.name.lowercase().replace('_', ' ')}.")
		}

		rebuild { other ->
			if (other === slot) slot.item.apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) } else other.item
		}

		if (gridSlots.none { !it.item.isMarked() && it.item.item in wanted }) return completed()
		super.slotClick(slot, button)
	}

	private companion object {
		/** The four items Hypixel uses for a colour, named the way it names them. */
		fun itemsOf(color: DyeColor): List<Item> {
			val name = color.name.lowercase()
			return listOf(
				vanilla("${name}_stained_glass"),
				vanilla("${name}_wool"),
				vanilla("${name}_concrete"),
				when (color) {
					DyeColor.WHITE -> Items.BONE_MEAL
					DyeColor.BLUE -> Items.LAPIS_LAZULI
					DyeColor.BLACK -> Items.INK_SAC
					DyeColor.BROWN -> Items.COCOA_BEANS
					else -> vanilla("${name}_dye")
				},
			)
		}

		fun vanilla(path: String): Item =
			BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", path))
	}
}

/** Press the button as the note crosses the marked column. */
class MelodySim : TermSimScreen(TerminalType.MELODY.termName, TerminalType.MELODY.windowSize) {
	private var markedColumn = (1..5).random()
	private var noteColumn = 1
	private var noteDirection = 1
	private var currentRow = 1
	private var ticks = 0

	override fun create() = rebuild { it.notePattern() }

	override fun containerTick() {
		super.containerTick()
		// The note slides one column every half second, and turns at the ends.
		if (ticks++ % TICKS_PER_STEP != 0) return
		noteColumn += noteDirection
		if (noteColumn == 1 || noteColumn == 5) noteDirection = -noteDirection
		refresh()
	}

	override fun slotClick(slot: Slot, button: Int) {
		if (slot.column() != BUTTON_COLUMN || slot.row() != currentRow || noteColumn != markedColumn) return

		markedColumn = (1 until 5).random()
		currentRow++
		refresh()

		if (currentRow >= ROWS) return completed()
		super.slotClick(slot, button)
	}

	/** Only the slots that actually changed are re-sent, as Hypixel does. */
	private fun refresh() {
		gridSlots.forEach { slot ->
			val wanted = slot.notePattern()
			if (slot.item.item != wanted.item) slot.setSlot(wanted)
		}
	}

	private fun Slot.notePattern(): ItemStack {
		val row = row()
		val column = column()
		return when {
			column == markedColumn && (row == 0 || row == ROWS) -> pane(Items.STAINED_GLASS_PANE.magenta())
			column == noteColumn && row == currentRow -> pane(Items.STAINED_GLASS_PANE.lime())
			column in 1..5 && row == currentRow -> pane(Items.STAINED_GLASS_PANE.red())
			column == BUTTON_COLUMN && row == currentRow -> named(ItemStack(Items.DYED_TERRACOTTA.lime()), "")
			column == BUTTON_COLUMN && row in 1 until ROWS -> named(ItemStack(Items.DYED_TERRACOTTA.red()), "")
			column in 1..5 && row in 1 until ROWS -> pane(Items.STAINED_GLASS_PANE.white())
			else -> fillerPane
		}
	}

	private companion object {
		const val BUTTON_COLUMN = 7
		/** The row after the last to play, which is also where the lower marker is. */
		const val ROWS = 4
		const val TICKS_PER_STEP = 10
	}
}

/**
 * The menu the simulator opens on, and returns to when a terminal is solved.
 *
 * Its own clicks are not delayed by the ping setting — waiting out a fake round
 * trip to pick which puzzle to practise would only be annoying.
 */
class StartSim : TermSimScreen("Terminal Simulator", 27) {
	override val delaysClicks: Boolean get() = false

	/** Cleared by a second press, so a mis-click cannot wipe your times. */
	private var resetArmed = false

	override fun create() {
		rebuild { slot ->
			when (slot.index) {
				RESET_SLOT -> named(
					ItemStack(Items.DYE.black()),
					if (resetArmed) "§cClick again to wipe your PBs" else "§cReset PBs",
				)
				RANDOM_SLOT -> named(ItemStack(Items.DYE.white()), "§7Random")
				in FIRST_ROW -> button(TerminalType.entries[slot.index - FIRST_ROW.first])
				in SECOND_ROW -> button(TerminalType.entries[slot.index - SECOND_ROW.first + 3])
				else -> fillerPane
			}
		}
	}

	override fun slotClick(slot: Slot, button: Int) {
		if (slot.index == RESET_SLOT) {
			if (!resetArmed) {
				resetArmed = true
				create()
				return
			}
			TerminalRecords.reset(simulated = true)
			resetArmed = false
			create()
			message("Practice bests cleared.")
			return
		}

		val type = when (slot.index) {
			RANDOM_SLOT -> TerminalType.entries.filter { it != TerminalType.MELODY }.random()
			in FIRST_ROW -> TerminalType.entries[slot.index - FIRST_ROW.first]
			in SECOND_ROW -> TerminalType.entries[slot.index - SECOND_ROW.first + 3]
			else -> return
		}
		openSim(type, ping)
	}

	/**
	 * One terminal to start, with what you have done it in before.
	 *
	 * The time is on the item rather than in chat because this menu is where
	 * you decide which to practise, and the number you are trying to beat is
	 * the thing that decides it. Odin's simulator does the same.
	 */
	private fun button(type: TerminalType): ItemStack {
		val stack = named(
			ItemStack(DYES[type.ordinal]),
			"${COLORS[type.ordinal]}${TerminalType.displayName(type)}",
		)
		val best = TerminalRecords.best(type.termName, simulated = true)
		val line = if (best == null) {
			"§8No time yet"
		} else {
			"§7Personal best: §d${String.format(Locale.ROOT, "%.2f", best)}s"
		}
		stack.set(DataComponents.LORE, ItemLore(listOf(Component.literal(line))))
		return stack
	}

	private companion object {
		val FIRST_ROW = 10..12
		const val RANDOM_SLOT = 13
		const val RESET_SLOT = 4
		val SECOND_ROW = 14..16

		val DYES = listOf(
			Items.DYE.lime(),
			Items.DYE.red(),
			Items.DYE.cyan(),
			Items.DYE.pink(),
			Items.DYE.brown(),
			Items.DYE.purple(),
		)
		val COLORS = listOf("§a", "§6", "§3", "§5", "§b", "§d")
	}
}

/** Opens one terminal of the simulator, at the ping it should answer at. */
fun openSim(type: TerminalType, ping: Long) {
	val screen = when (type) {
		TerminalType.PANES -> PanesSim()
		TerminalType.RUBIX -> RubixSim()
		TerminalType.NUMBERS -> NumbersSim()
		TerminalType.STARTS_WITH -> StartsWithSim()
		TerminalType.SELECT -> SelectAllSim()
		TerminalType.MELODY -> MelodySim()
	}
	screen.open(ping)
}
