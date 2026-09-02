package imicro.cryptic.experiment

/**
 * One slot of an experiment's chest, reduced to what solving actually needs.
 *
 * Deliberately not an `ItemStack`. Everything below is arithmetic over these
 * six fields, and keeping Minecraft out of it is what lets the whole solver be
 * tested — see `ExperimentSolverTest`, which is where the rules in here were
 * checked rather than guessed at.
 */
data class ExperimentCell(
	val slot: Int,
	val itemId: String,
	val count: Int,
	val foil: Boolean,
	val name: String,
	val empty: Boolean,
)

/**
 * The rules the three Experimentation Table games are read with.
 *
 * Ported from Astrail Experiment (`AzureSky0116/astrail-experiment`, MIT,
 * Copyright (c) 2026 AzureSky0116), which targets Minecraft 26.2 and is the
 * only reference here written for a version anything like Cryptic's. Its
 * licence is vendored at `licenses/astrail-experiment-LICENSE.txt`.
 *
 * It corrected three things an older reference had wrong: the pane that says
 * which half of a round is running is identified by its **item**, glowstone
 * while the pattern is shown and a clock while it is entered — not by its name,
 * which is a translated string; Chronomatron gains **one** note per round
 * rather than replaying the lot to be re-read; and Ultrasequencer's order is
 * the **stack size** of each note, not anything in its name.
 */
object ExperimentRules {
	const val CONTROL_SLOT = 49

	/** The control pane while the pattern is being shown. */
	const val SHOWING_ITEM = "minecraft:glowstone"

	/** The control pane while the pattern is being entered against a timer. */
	const val ENTERING_ITEM = "minecraft:clock"

	/** Chronomatron's notes sit inside the border; the other two use the full rows. */
	val CHRONOMATRON_SLOTS = 10..43
	val GRID_SLOTS = 9..44

	/** Superpairs counts down the clicks left on a bookshelf, as its stack size. */
	const val CLICKS_SLOT = 4

	/** A Superpairs card nobody has turned over yet. */
	const val FACE_DOWN_ITEM = "minecraft:cyan_stained_glass"

	fun clicksLeft(cells: List<ExperimentCell>): Int? =
		cells.firstOrNull { it.slot == CLICKS_SLOT && !it.empty }?.count

	fun isFaceDown(cell: ExperimentCell): Boolean = cell.itemId == FACE_DOWN_ITEM

	/**
	 * Whether a Superpairs card is experience rather than something to keep.
	 *
	 * The board pays in three kinds of thing: enchanting experience, experience
	 * bottles of various sizes, and everything else — enchanted books, dyes and
	 * the power-ups that hand back clicks. "Everything else" is what the items
	 * mode is after, so anything unrecognised counts as worth having rather than
	 * as experience: a card this does not know is far more likely to be a book
	 * nobody has seen yet than a fourth way of writing "experience".
	 */
	fun isExperienceCard(cell: ExperimentCell): Boolean =
		cell.itemId == "minecraft:experience_bottle" ||
			cell.name.contains("Experience", ignoreCase = true) ||
			cell.name.contains("Enchanting Exp", ignoreCase = true)

	/**
	 * Drops Minecraft's colour codes.
	 *
	 * Written out by hand rather than as a regex because it runs once per slot
	 * per read, and the overwhelmingly common case — text with no codes in it at
	 * all — now hands back the string it was given instead of building a copy.
	 */
	fun stripFormatting(text: String): String {
		if (text.indexOf('§') < 0) return text

		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			if (text[i] == '§' && i + 1 < text.length) {
				i += 2
			} else {
				out.append(text[i])
				i++
			}
		}
		return out.toString()
	}

	fun controlOf(cells: List<ExperimentCell>): String? =
		cells.firstOrNull { it.slot == CONTROL_SLOT }?.itemId

	/** The notes Ultrasequencer numbers, which are dyes and two things like them. */
	fun isSequenceItem(cell: ExperimentCell): Boolean =
		cell.itemId.contains("_dye") ||
			cell.itemId == "minecraft:lapis_lazuli" ||
			cell.itemId == "minecraft:bone_meal"

	/** True for a Superpairs card that is face up and worth remembering. */
	fun isRevealedPair(cell: ExperimentCell): Boolean {
		if (cell.empty) return false
		return !cell.itemId.contains("stained_glass") &&
			cell.itemId != ENTERING_ITEM &&
			cell.itemId != SHOWING_ITEM &&
			cell.itemId != "minecraft:black_dye" &&
			cell.name.isNotBlank()
	}

	/** Which game a chest title is, or null. "Sta" keeps the stats screens out. */
	fun gameOf(title: String): Game? = when {
		title.contains("Chronomatron") && !title.contains("Sta") -> Game.CHRONOMATRON
		title.contains("Ultrasequencer") && !title.contains("Sta") -> Game.ULTRASEQUENCER
		title.startsWith("Superpairs (") -> Game.SUPERPAIRS
		else -> null
	}

	enum class Game { CHRONOMATRON, ULTRASEQUENCER, SUPERPAIRS }
}

/** What the player asked the table to be played for, as the games need to know it. */
data class PlayOptions(
	/** True while only non-experience pairs are worth spending a click on. */
	val rareItemsOnly: Boolean = false,
)

/** One game in progress, and the slots it still wants clicked, first one first. */
abstract class ExperimentHandler(val game: ExperimentRules.Game) {
	val name: String get() = game.name.lowercase().replaceFirstChar { it.uppercase() }

	abstract fun update(cells: List<ExperimentCell>, options: PlayOptions)

	/**
	 * The slots to click now, in order.
	 *
	 * Empty while the game is showing rather than asking — which is also what
	 * stops the overlay painting hints over a pattern still being memorised.
	 */
	abstract fun clickOrder(): List<Int>

	/** Told when a click has been made, because the board does not say. */
	abstract fun advance()

	/** How many rounds have been won, which is how far in the chain this is. */
	abstract fun roundsDone(): Int

	open val waiting: Boolean get() = clickOrder().isEmpty()
}

/**
 * Simon Says with notes: one new note is added each round, then the whole
 * sequence is played back.
 *
 * The note added this round is the one carrying an enchantment glint. It is
 * taken once and latched, because it stays glinting for as long as the round
 * lasts and would otherwise be recorded on every update.
 */
class ChronomatronHandler : ExperimentHandler(ExperimentRules.Game.CHRONOMATRON) {
	private val sequence = mutableListOf<Int>()
	private var latched = false
	private var clickIndex = 0

	override fun update(cells: List<ExperimentCell>, options: PlayOptions) {
		when (ExperimentRules.controlOf(cells)) {
			ExperimentRules.SHOWING_ITEM -> {
				latched = false
				clickIndex = 0
			}
			ExperimentRules.ENTERING_ITEM -> {
				if (latched) return
				val note = cells.firstOrNull {
					it.slot in ExperimentRules.CHRONOMATRON_SLOTS && it.foil
				} ?: return
				sequence.add(note.slot)
				latched = true
				clickIndex = 0
			}
			else -> Unit
		}
	}

	override fun clickOrder(): List<Int> = if (latched) sequence.drop(clickIndex) else emptyList()

	override fun advance() {
		if (clickIndex < sequence.size) clickIndex++
	}

	override fun roundsDone(): Int = sequence.size
}

/**
 * A grid of numbered notes shown at once, then played back in order.
 *
 * The number is the stack size. Captured once per reveal, because the notes go
 * blank the moment the timer starts and there is nothing left to read.
 */
class UltrasequencerHandler : ExperimentHandler(ExperimentRules.Game.ULTRASEQUENCER) {
	private val order = sortedMapOf<Int, Int>()
	private var clickIndex = 0
	private var captured = false
	private var entering = false

	override fun update(cells: List<ExperimentCell>, options: PlayOptions) {
		when (ExperimentRules.controlOf(cells)) {
			ExperimentRules.ENTERING_ITEM -> {
				captured = false
				entering = true
			}
			ExperimentRules.SHOWING_ITEM -> {
				entering = false
				if (captured) return
				order.clear()
				cells.filter { it.slot in ExperimentRules.GRID_SLOTS && ExperimentRules.isSequenceItem(it) }
					.forEach { order[it.count - 1] = it.slot }
				clickIndex = 0
				captured = true
			}
			else -> Unit
		}
	}

	override fun clickOrder(): List<Int> =
		if (entering) order.values.drop(clickIndex) else emptyList()

	override fun advance() {
		if (clickIndex < order.size) clickIndex++
	}

	override fun roundsDone(): Int = order.size
}

/**
 * Pairs, turned over and matched from memory.
 *
 * Two jobs, not one. Remembering what the board has shown is the easy half; the
 * hard half is deciding what to spend the next click on, and that is where the
 * two ways of playing the table part company.
 *
 * Hunting items, the board is read before it is harvested: cards are turned over
 * left to right and top to bottom, and a pair is only taken when both its halves
 * are something other than experience. Experience pairs are left on the board
 * while there is anything still face down, because taking one costs two clicks
 * that could have turned over two cards nobody has seen — and the book that
 * makes the run worth anything is under one of those. Only once the whole board
 * is known, with clicks still in hand, is experience worth taking; by then there
 * is nothing better left to find.
 *
 * Hunting experience, there is nothing to hold out for, so a pair is taken the
 * moment both halves are known.
 */
class SuperpairsHandler : ExperimentHandler(ExperimentRules.Game.SUPERPAIRS) {
	/** What each slot turned out to be, whenever anything turned it over. */
	private val seen = mutableMapOf<Int, ExperimentCell>()

	/** Slots already spent on a pair, which never want clicking again. */
	private val spent = mutableSetOf<Int>()

	/** Slots still face down, in board order. */
	private var faceDown = listOf<Int>()

	/** The slot just clicked, which the board has not caught up with yet. */
	private var awaiting: Int? = null

	private var plan = listOf<Int>()
	private var pairsTaken = 0

	var clicksLeft: Int = Int.MAX_VALUE
		private set

	override fun update(cells: List<ExperimentCell>, options: PlayOptions) {
		ExperimentRules.clicksLeft(cells)?.let { clicksLeft = it }

		// Sorted, because "work along the board" is the whole instruction and a
		// map's order is not the board's.
		faceDown = cells.filter { it.slot in ExperimentRules.GRID_SLOTS && ExperimentRules.isFaceDown(it) }
			.map { it.slot }
			.sorted()

		cells.forEach { cell ->
			if (cell.slot !in ExperimentRules.GRID_SLOTS) return@forEach
			if (!ExperimentRules.isRevealedPair(cell)) return@forEach
			seen[cell.slot] = cell
		}

		// The click has landed once the card it turned is no longer face down.
		awaiting?.let { if (it !in faceDown) awaiting = null }

		plan = decide(options)
	}

	private fun decide(options: PlayOptions): List<Int> {
		val known = seen.filterKeys { it !in spent }
		val pairs = known.values.groupBy { "${it.itemId}|${it.name}" }
			.values
			.filter { it.size >= 2 }

		val worthTaking = if (options.rareItemsOnly) {
			pairs.filter { group -> group.none(ExperimentRules::isExperienceCard) }
		} else {
			pairs
		}

		// A pair worth having is always taken before anything else is turned over.
		worthTaking.firstOrNull()?.let { group ->
			return group.take(2).map { it.slot }
		}

		// Otherwise keep reading the board, in the order it is laid out.
		faceDown.firstOrNull { it != awaiting }?.let { return listOf(it) }

		// Nothing left to turn over. Now, and only now, experience is worth the
		// clicks that are left over.
		if (options.rareItemsOnly) {
			pairs.firstOrNull()?.let { group -> return group.take(2).map { it.slot } }
		}

		return emptyList()
	}

	override fun clickOrder(): List<Int> = plan

	override fun advance() {
		val slot = plan.firstOrNull() ?: return
		plan = plan.drop(1)

		if (slot in faceDown) {
			// A card being turned over, not a pair being taken.
			awaiting = slot
			return
		}

		spent.add(slot)
		if (spent.size / 2 > pairsTaken) pairsTaken = spent.size / 2
	}

	override fun roundsDone(): Int = pairsTaken
}
