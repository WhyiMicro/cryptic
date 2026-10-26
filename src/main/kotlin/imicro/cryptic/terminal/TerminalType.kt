package imicro.cryptic.terminal

import net.minecraft.world.item.DyeColor
import java.util.Locale

/**
 * The six Floor 7 terminals, told apart by chest title. Titles, sizes and grid bounds are Odin's.
 * [rows], [columns], [firstRow] and [firstColumn] describe the puzzle area, so the custom GUI can
 * draw it without the filler.
 */
enum class TerminalType(
	val termName: String,
	val pattern: Regex,
	val windowSize: Int,
	val rows: Int,
	val columns: Int,
	val firstRow: Int,
	val firstColumn: Int,
) {
	PANES("Correct all the panes!", Regex("^Correct all the panes!$"), 45, 3, 5, 1, 2),
	RUBIX("Change all to same color!", Regex("^Change all to same color!$"), 45, 3, 3, 1, 3),
	// Ten numbers since SkyBlock 0.27.2, five to a row.
	NUMBERS("Click in order!", Regex("^Click in order!$"), 36, 2, 5, 1, 2),
	STARTS_WITH("What starts with: '?'?", Regex("^What starts with: '(\\w)'\\?$"), 45, 3, 7, 1, 1),
	SELECT("Select all the ? items!", Regex("^Select all the ([\\w ]+) items!$"), 54, 4, 7, 1, 1),
	MELODY("Click the button on time!", Regex("^Click the button on time!$"), 54, 5, 7, 0, 1);

	/** Every slot of the puzzle area: what the custom GUI draws and the only part a click can reach. */
	val slots: Set<Int> = buildSet {
		for (row in 0 until rows) {
			for (column in 0 until columns) {
				add((firstRow + row) * 9 + (firstColumn + column))
			}
		}
	}

	companion object {
		/**
		 * A solver for the chest called [title], or null. Two terminals need the letter or colour
		 * from the title, so handlers are built here.
		 */
		fun handlerFor(title: String): TerminalHandler? {
			val type = entries.firstOrNull { it.pattern.matches(title) } ?: return null
			return when (type) {
				PANES -> PanesHandler()
				RUBIX -> RubixHandler()
				NUMBERS -> NumbersHandler()
				MELODY -> MelodyHandler()
				STARTS_WITH -> type.pattern.find(title)?.groupValues?.get(1)?.let(::StartsWithHandler)
				SELECT -> type.pattern.find(title)?.groupValues?.get(1)?.let { wanted ->
					// Hypixel still says "silver" for light gray.
					val name = wanted.replace("SILVER", "LIGHT GRAY", ignoreCase = true)
					DyeColor.entries
						.firstOrNull { it.name.replace('_', ' ').equals(name, ignoreCase = true) }
						?.let(::SelectAllHandler)
				}
			}
		}

		fun of(title: String): TerminalType? = entries.firstOrNull { it.pattern.matches(title) }

		fun displayName(type: TerminalType): String =
			type.name.split('_').joinToString(" ") { word ->
				word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
			}
	}
}
