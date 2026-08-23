package imicro.cryptic.terminal

import net.minecraft.world.item.DyeColor
import java.util.Locale

/**
 * The six Floor 7 terminals, told apart by the title Hypixel gives the chest.
 *
 * The titles, window sizes and grid bounds are Odin's (BSD 3-Clause), which is
 * also where the solving rules in [TerminalHandler] come from. [rows],
 * [columns], [firstRow] and [firstColumn] describe the part of the chest the
 * puzzle actually occupies, so the custom GUI can draw that block on its own
 * without the rows of filler around it.
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
	NUMBERS("Click in order!", Regex("^Click in order!$"), 36, 2, 7, 1, 1),
	STARTS_WITH("What starts with: '?'?", Regex("^What starts with: '(\\w)'\\?$"), 45, 3, 7, 1, 1),
	SELECT("Select all the ? items!", Regex("^Select all the ([\\w ]+) items!$"), 54, 4, 7, 1, 1),
	MELODY("Click the button on time!", Regex("^Click the button on time!$"), 54, 5, 7, 0, 1);

	companion object {
		/**
		 * A solver for the chest called [title], or null when it is not a
		 * terminal.
		 *
		 * Two of the six need something out of the title itself — which letter,
		 * or which colour — so the handler is built here rather than by the
		 * enum constant alone.
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
					// Hypixel still says "silver" where the game now says
					// light gray, which is the one name that does not line up.
					val name = wanted.replace("SILVER", "LIGHT GRAY", ignoreCase = true)
					DyeColor.entries
						.firstOrNull { it.name.replace('_', ' ').equals(name, ignoreCase = true) }
						?.let(::SelectAllHandler)
				}
			}
		}

		/** The type of a chest called [title], without building a solver for it. */
		fun of(title: String): TerminalType? = entries.firstOrNull { it.pattern.matches(title) }

		/** "Starts With" rather than "STARTS_WITH", for menus and messages. */
		fun displayName(type: TerminalType): String =
			type.name.split('_').joinToString(" ") { word ->
				word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
			}
	}
}
