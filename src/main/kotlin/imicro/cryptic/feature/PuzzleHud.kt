package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Lists the floor's puzzles, and which of them are done.
 *
 * Devonian's Puzzles Display (GPL-3.0, Copyright (c) Synnerz; licence in
 * `licenses/Devonian-LICENSE.txt`). Hypixel keeps this list in the tab list —
 * how many puzzles the floor has, then a row for each with a star, a tick or a
 * cross, and the name of whoever failed it — which is a place nobody looks in
 * the middle of a run. This is that list, on the HUD.
 *
 * Hypixel only names a puzzle once somebody has walked into it; until then its
 * row says `???`. Cryptic's scan of the floor knows the rooms before anybody
 * has been in them, so the same two choices the map offers are offered here:
 * as Hypixel reveals them, or all at once.
 */
object PuzzleHud {
	private const val REVEAL_ALL = 0

	private const val TITLE_COLOR = 0xFFFF55FF.toInt()
	private const val NAME_COLOR = 0xFFFFFFFF.toInt()
	private const val UNKNOWN_COLOR = 0xFFAAAAAA.toInt()
	private const val PENDING_COLOR = 0xFFFFAA00.toInt()
	private const val DONE_COLOR = 0xFF55FF55.toInt()
	private const val FAILED_COLOR = 0xFFFF5555.toInt()

	private const val PENDING = '✦'
	private const val DONE = '✔'
	private const val FAILED = '✖'

	@JvmField
	val reveal = DropdownModuleSetting(
		id = "reveal",
		label = "Reveal",
		options = listOf("Whole floor", "As you explore"),
		defaultIndex = REVEAL_ALL,
		description = "Whether a puzzle nobody has walked into yet is already named, from Cryptic's own scan of the floor.",
	)

	@JvmField
	val onlyBeforeStart = ToggleModuleSetting(
		id = "only_before_start",
		label = "Only show before run starts",
		defaultValue = false,
		description = "Takes the list down once the run begins, for seeing what the floor holds and nothing more.",
	)

	@JvmField
	val module = Module(
		id = "puzzle_hud",
		name = "Puzzle HUD",
		description = "Lists the floor's puzzles and their state",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(reveal, onlyBeforeStart),
	)

	/** True while the floor has to be scanned for room names on this module's account. */
	val needsScan: Boolean get() = module.enabled && reveal.selectedIndex == REVEAL_ALL

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(PuzzleElement())
	}

	/** One row: a puzzle's name, its mark, and who failed it if anybody did. */
	private class Row(val name: String, val mark: Char, val failedBy: String = "")

	private val EXAMPLE = listOf(
		Row("Water Board", DONE),
		Row("Three Weirdos", FAILED, "Steve"),
		Row("Boulder", PENDING),
		Row("???", PENDING),
	)

	/**
	 * The rows to show: the ones Hypixel has named, then the ones only the scan
	 * knows, then a `???` for each the floor still owes.
	 */
	private fun rows(): List<Row> {
		if (DebugOverrides.sampleHudValues) return EXAMPLE

		val named = DungeonStats.namedPuzzles.map { Row(it.first, it.second, it.third) }
		val rows = named.toMutableList()
		val total = maxOf(DungeonStats.puzzleCount, named.size)

		if (reveal.selectedIndex == REVEAL_ALL) {
			val known = named.mapTo(HashSet()) { it.name }
			DungeonFloor.rooms
				.filter { it.type == DungeonRoom.Type.PUZZLE }
				.mapNotNull { it.data?.name }
				.distinct()
				.filter { it !in known }
				.take((total - rows.size).coerceAtLeast(0))
				.forEach { rows += Row(it, PENDING) }
		}

		repeat((total - rows.size).coerceAtLeast(0)) { rows += Row("???", PENDING) }
		return rows
	}

	private fun shown(): Boolean {
		if (!module.enabled) return false
		if (DebugOverrides.sampleHudValues) return true
		if (!DungeonLocation.inDungeon || DungeonRun.inBoss || DungeonRun.ended) return false
		return !onlyBeforeStart.value || !DungeonRun.started
	}

	private class PuzzleElement : HudElement("puzzle_hud", "Puzzle HUD", 0.86, 0.62) {
		private val font get() = Minecraft.getInstance().font

		private fun text(row: Row): String =
			"${row.name} ${row.mark}" + if (row.failedBy.isNotEmpty()) " ${row.failedBy}" else ""

		private fun measured(): List<Row> = rows().ifEmpty { EXAMPLE }

		override val width: Int
			get() = maxOf(font.width("Puzzles: 5"), measured().maxOf { font.width(text(it)) })

		override val height: Int get() = font.lineHeight * (measured().size + 1)

		override fun isVisible(): Boolean = shown() && rows().isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) = draw(context, rows())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, EXAMPLE)

		private fun draw(context: GuiGraphicsExtractor, rows: List<Row>) {
			val title = "Puzzles: "
			context.text(font, title, 0, 0, TITLE_COLOR)
			// More than three is a floor worth groaning at, as Devonian has it.
			context.text(font, rows.size.toString(), font.width(title), 0, if (rows.size > 3) PENDING_COLOR else DONE_COLOR)

			rows.forEachIndexed { index, row ->
				val y = (index + 1) * font.lineHeight
				val name = "${row.name} "
				context.text(font, name, 0, y, if (row.name == "???") UNKNOWN_COLOR else NAME_COLOR)

				var x = font.width(name)
				val mark = row.mark.toString()
				context.text(font, mark, x, y, colorOf(row.mark))
				if (row.failedBy.isNotEmpty()) {
					x += font.width("$mark ")
					context.text(font, row.failedBy, x, y, FAILED_COLOR)
				}
			}
		}

		private fun colorOf(mark: Char): Int = when (mark) {
			DONE -> DONE_COLOR
			FAILED -> FAILED_COLOR
			else -> PENDING_COLOR
		}
	}
}
