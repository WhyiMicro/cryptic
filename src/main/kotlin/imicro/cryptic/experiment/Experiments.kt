package imicro.cryptic.experiment

/**
 * One slot of an experiment chest, reduced to what solving needs, so the rules can be unit-tested
 * (`ExperimentSolverTest`).
 */
data class ExperimentCell(
	val slot: Int,
	val itemId: String,
	val count: Int,
	val foil: Boolean,
	val name: String,
	val empty: Boolean,
	/** Lore, stripped; read only in Superpairs, where a book's enchantment is in it. */
	val lore: List<String> = emptyList(),
)

/**
 * The rules the three Experimentation Table games are read by. Ported from Astrail Experiment
 * (MIT), the reference written for this Minecraft version. The phase pane is told by its item
 * (glowstone while showing, clock while entering), Chronomatron adds one note a round, and
 * Ultrasequencer's order is each note's stack size.
 */
object ExperimentRules {
	const val CONTROL_SLOT = 49

	const val SHOWING_ITEM = "minecraft:glowstone"

	const val ENTERING_ITEM = "minecraft:clock"

	val CHRONOMATRON_SLOTS = 10..43
	val GRID_SLOTS = 9..44

	/** Superpairs shows the clicks left on a bookshelf, "Remaining Clicks: 12", and as its stack size. */
	const val CLICKS_SLOT = 4

	private val REMAINING_CLICKS = Regex("""Remaining Clicks: (\d+)""")

	const val FACE_DOWN_ITEM = "minecraft:cyan_stained_glass"

	/**
	 * What a face-down card is called, whatever it's made of; the name says where the turn stands,
	 * and "?" is while a click is being answered. Facts from SkyHanni, confirmed in a 2026-11-03 run.
	 */
	const val TURN_START_NAME = "Click any button!"
	const val SECOND_CLICK_NAME = "Click a second button!"
	const val INSTANT_FIND_NAME = "Next button is instantly rewarded!"
	const val ANSWERING_NAME = "?"
	private val FACE_DOWN_NAMES = setOf(ANSWERING_NAME, TURN_START_NAME, SECOND_CLICK_NAME, INSTANT_FIND_NAME)

	/** "135k Enchanting Exp". */
	private val ENCHANTING_EXP = Regex("""^([\d.,]+)([kKmM]?) Enchanting Exp""")

	/** The Enchanting experience a card pays, or 0 for anything else. */
	fun experienceOf(cell: ExperimentCell): Double {
		val match = ENCHANTING_EXP.find(cell.name) ?: return 0.0
		val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0.0
		return number * when (match.groupValues[2].lowercase()) {
			"k" -> 1_000.0
			"m" -> 1_000_000.0
			else -> 1.0
		}
	}

	fun clicksLeft(cells: List<ExperimentCell>): Int? {
		val shelf = cells.firstOrNull { it.slot == CLICKS_SLOT && !it.empty } ?: return null
		return REMAINING_CLICKS.find(shelf.name)?.groupValues?.get(1)?.toIntOrNull() ?: shelf.count
	}

	fun isFaceDown(cell: ExperimentCell): Boolean =
		!cell.empty && (cell.itemId == FACE_DOWN_ITEM || cell.name in FACE_DOWN_NAMES)

	/**
	 * The board's slots for a Superpairs title's stakes: Beginner is 7x2 in the middle rows, High
	 * and Grand 5x4, and the rest 7x4 (facts from SkyHanni and the wiki). Unknown stakes get every
	 * row, and the face-down and card checks sort out the border.
	 */
	fun superpairsBoard(title: String): List<Int> {
		val stakes = title.substringAfter("(", "").substringBefore(")").trim().lowercase()
		val (rows, columns) = when (stakes) {
			"beginner" -> (2..3) to (1..7)
			"high", "grand" -> (1..4) to (2..6)
			"supreme", "transcendent", "metaphysical" -> (1..4) to (1..7)
			else -> return GRID_SLOTS.toList()
		}
		return rows.flatMap { row -> columns.map { column -> row * 9 + column } }
	}

	/** "Smite VII", a book's enchantment as its lore names it. */
	private val ENCHANTMENT = Regex("""^[A-Z][A-Za-z' -]* [IVX]+$""")

	fun isBook(cell: ExperimentCell): Boolean =
		cell.itemId == "minecraft:enchanted_book" || cell.name == "Enchanted Book"

	/** What a card pays: a book's enchantment, since every book is called Enchanted Book, or its name. */
	fun cardReward(cell: ExperimentCell): String =
		if (isBook(cell)) cell.lore.firstOrNull { ENCHANTMENT.matches(it) } ?: cell.name else cell.name

	/** Two cards match when this is the same. */
	fun cardKey(cell: ExperimentCell): String = "${cell.itemId}|${cardReward(cell)}"

	/** Powerups (Instant Find, extra clicks) are single cards, used up when turned over. */
	fun isPowerup(cell: ExperimentCell): Boolean = cell.lore.any { it.contains("powerup", ignoreCase = true) }

	fun isUltraRare(cell: ExperimentCell): Boolean = cell.lore.any { it.contains("ULTRA-RARE") }

	/** Focus XP is for levelling; only a card worth this much is taken ahead of experience. */
	const val FOCUS_XP_WORTH = 10_000_000.0

	/** "Chain of 9: +3 Clicks" in Chronomatron's stakes, "Series of 7: +2 Clicks" in Ultrasequencer's. */
	private val CLICKS_REWARD = Regex("""(?:Chain|Series) of (\d+): \+\d+ Clicks?""")

	/** The longest chain a stake's lore pays Superpairs clicks for, or null. */
	fun maxClicksChain(lore: List<String>): Int? = lore.mapNotNull { CLICKS_REWARD.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()

	/**
	 * Whether a Superpairs card is experience. Anything unrecognised counts as worth keeping: an
	 * unknown card is more likely a book than another kind of experience.
	 */
	fun isExperienceCard(cell: ExperimentCell): Boolean =
		cell.itemId == "minecraft:experience_bottle" ||
			cell.name.contains("Experience", ignoreCase = true) ||
			cell.name.contains("Enchanting Exp", ignoreCase = true)

	/** Strips colour codes by hand; it runs per slot, and text with no codes is returned without a copy. */
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

	fun isSequenceItem(cell: ExperimentCell): Boolean =
		cell.itemId.contains("_dye") ||
			cell.itemId == "minecraft:lapis_lazuli" ||
			cell.itemId == "minecraft:bone_meal"

	/**
	 * A Superpairs card face up: whatever sits on the board that isn't face down or a border pane.
	 * Looser than [isRevealedPair], so a card of an unexpected kind (a Chance IV book went unseen
	 * once) is still known and paired.
	 */
	fun isSuperpairsCard(cell: ExperimentCell): Boolean =
		!cell.empty && !isFaceDown(cell) && !cell.itemId.endsWith("_pane") &&
			cell.itemId != ENTERING_ITEM && cell.itemId != SHOWING_ITEM && cell.itemId != "minecraft:black_dye"

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

data class PlayOptions(
	val rareItemsOnly: Boolean = false,
	/** What a card sells for, to take the best pair first; 0 when unknown. */
	val valueOf: (ExperimentCell) -> Double = { 0.0 },
	/** Coins from which a card is worth going for when hunting items; Focus XP uses [ExperimentRules.FOCUS_XP_WORTH]. */
	val rareFrom: Double = 1_000_000.0,
)

abstract class ExperimentHandler(val game: ExperimentRules.Game) {
	val name: String get() = game.name.lowercase().replaceFirstChar { it.uppercase() }

	abstract fun update(cells: List<ExperimentCell>, options: PlayOptions)

	/**
	 * The slots to click now, in order. Empty while the pattern is being shown, which also keeps
	 * hints off it.
	 */
	abstract fun clickOrder(): List<Int>

	/** Called after a click, because the board doesn't say. */
	abstract fun advance()

	/** A click of the player's own on [slot], in a game that allows them. */
	open fun clickedByHand(slot: Int) {}

	abstract fun roundsDone(): Int

	open val waiting: Boolean get() = clickOrder().isEmpty()

	/** This round's notes have all been played back. */
	open val roundComplete: Boolean get() = false
}

/**
 * Chronomatron: one new note a round, then the whole sequence played back. The new note glints; it
 * is latched once since it glints all round.
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

	override val roundComplete: Boolean get() = latched && clickIndex >= sequence.size
}

/**
 * Ultrasequencer: numbered notes shown at once, then played back in order. The number is the stack
 * size, captured once per reveal before the notes go blank.
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

	override val roundComplete: Boolean get() = entering && order.isNotEmpty() && clickIndex >= order.size
}

/**
 * Superpairs, on a board of [board]'s slots. A pair pays only when both cards are clicked in one
 * turn (two clicks in a row). Some boards leave every turned card face up, others turn a missed
 * pair back over; either way each card is remembered from the moment it shows, and a remembered
 * card counts as known whether it's face up now or not.
 *
 * Each turn either takes a pair already in view or turns a new card and, if its twin is in view,
 * takes it with the second click. Hunting items, the aim is the whole board: a valuable pair is
 * taken as soon as it's in view, and experience only gets the clicks once nothing is left to turn.
 * On Focus XP, only a card worth 10M or more comes first, and new cards are turned while the
 * clicks still cover the pairs in view. The face-down cards can be worked out too: when each is the
 * twin of one in view, every new card turned pays. Where the turn stands is read off the face-down
 * cards' name, and from the clicks counted once none are left.
 */
class SuperpairsHandler(
	val board: List<Int> = ExperimentRules.GRID_SLOTS.toList(),
) : ExperimentHandler(ExperimentRules.Game.SUPERPAIRS) {
	/** Every card that has shown, by slot, kept after it turns back over. */
	private val seen = HashMap<Int, ExperimentCell>()

	private var hidden = listOf<Int>()

	/** Cards clicked at least once, so one that never showed isn't picked again first. */
	private val clickedSlots = HashSet<Int>()

	/** Clicks already sent again once after going unanswered. */
	private val retried = HashSet<Int>()

	/** Set by [unstick]: the "answering" prompt is ignored until the board next changes. */
	private var promptDistrusted = false
	private var lastBoard = ""

	/** What the face-down cards are called: whose turn it is, or that a click is being answered. */
	private var prompt: String? = null

	/** The turn's first card, until its second is clicked. */
	private var turnFirst: Int? = null

	/** Each finished turn's two cards; a pair when they match. */
	private val turns = ArrayList<Pair<Int, Int>>()

	/** Cards Instant Find paid out on their own. */
	private val instantlyRewarded = HashSet<Int>()

	/** The card just clicked, and the board as it stood, until the board answers. */
	private var pending: Int? = null
	private var pendingBoard = ""
	private var pendingWasHidden = false
	private var pendingClicks = 0
	private var lastClicked: Int? = null

	private var complete = false
	private var options = PlayOptions()
	private var plan = listOf<Int>()

	var clicksLeft: Int = Int.MAX_VALUE
		private set

	/** What a face-down card was seen to be, once it has turned back over. */
	fun remembered(slot: Int): ExperimentCell? = if (slot in hidden) seen[slot] else null

	override fun update(cells: List<ExperimentCell>, options: PlayOptions) {
		this.options = options
		ExperimentRules.clicksLeft(cells)?.let { clicksLeft = it }

		val bySlot = HashMap<Int, ExperimentCell>(cells.size)
		for (cell in cells) bySlot[cell.slot] = cell
		val down = ArrayList<Int>()
		var name: String? = null
		for (slot in board) {
			val cell = bySlot[slot] ?: continue
			if (ExperimentRules.isFaceDown(cell)) {
				down += slot
				name = cell.name
			} else if (ExperimentRules.isSuperpairsCard(cell)) {
				seen[slot] = cell
			}
		}
		hidden = down
		prompt = name
		// The menu arrives a few slots at a time; only a whole board can be reasoned about. A card
		// of a kind not recognised still counts as there, or the board would never be whole.
		complete = board.all { bySlot[it]?.empty == false }

		// The board refreshes about once a second; any change in it is the answer to a click.
		val now = boardState()
		if (now != lastBoard) {
			lastBoard = now
			promptDistrusted = false
		}
		pending?.let { slot ->
			// A face-down card answers by showing, or by the click being counted; other cards turning
			// back over at the same moment aren't an answer. A face-up one can only be told by the board.
			val answered = if (pendingWasHidden) slot !in hidden || clicksLeft != pendingClicks else now != pendingBoard
			if (answered) pending = null
		}
		if (pending == null) {
			when (prompt) {
				ExperimentRules.TURN_START_NAME, ExperimentRules.INSTANT_FIND_NAME -> turnFirst = null
				ExperimentRules.SECOND_CLICK_NAME -> if (turnFirst == null) turnFirst = lastClicked
			}
		}
		plan = decide()
	}

	// By slot, since a card showing and two turning back over can leave the count unchanged.
	private fun boardState(): String = "$clicksLeft|${hidden.joinToString(",")}|$prompt"

	private fun key(slot: Int): String? = seen[slot]?.let(ExperimentRules::cardKey)

	/** Cards already paid out: matched turns, and Instant Find's. */
	private fun taken(): Set<Int> {
		val out = HashSet<Int>(instantlyRewarded)
		for ((a, b) in turns) {
			if (a != b && key(a) != null && key(a) == key(b)) {
				out += a
				out += b
			}
		}
		return out
	}

	/**
	 * Worth going for: an Ultra-Rare, or a book or item (experience bottles included) that sells for
	 * at least the Rare from price when hunting items, or 10M on Focus XP. With no known price,
	 * hunting items it might be anything, so it counts; on Focus XP it doesn't.
	 */
	fun isRare(slot: Int): Boolean {
		val cell = seen[slot] ?: return false
		if (ExperimentRules.experienceOf(cell) > 0 || ExperimentRules.isPowerup(cell)) return false
		if (ExperimentRules.isUltraRare(cell)) return true
		val value = options.valueOf(cell)
		if (value > 0.0) return value >= if (options.rareItemsOnly) options.rareFrom else ExperimentRules.FOCUS_XP_WORTH
		return options.rareItemsOnly
	}

	/** Best first: what's worth going for by worth, then experience by amount, then the rest by worth. */
	private val bestFirst: Comparator<Int> = Comparator { a, b ->
		val rare = compareValues(isRare(b), isRare(a))
		val exp = compareValues(ExperimentRules.experienceOf(seen.getValue(b)), ExperimentRules.experienceOf(seen.getValue(a)))
		val worth = compareValues(options.valueOf(seen.getValue(b)), options.valueOf(seen.getValue(a)))
		if (rare != 0) rare else if (isRare(a)) worth else if (exp != 0) exp else worth
	}

	/** Worth going for, then coins whatever the Rare from price, then experience: how a known board is paid out. */
	private val byWorth: Comparator<Int> = Comparator { a, b ->
		val rare = compareValues(isRare(b), isRare(a))
		val worth = compareValues(options.valueOf(seen.getValue(b)), options.valueOf(seen.getValue(a)))
		val exp = compareValues(ExperimentRules.experienceOf(seen.getValue(b)), ExperimentRules.experienceOf(seen.getValue(a)))
		if (rare != 0) rare else if (worth != 0) worth else exp
	}

	/** The board as the turn choice sees it. */
	class View(
		val taken: Set<Int>,
		/** Known cards not yet paid out, face up or remembered, powerups left out. */
		val inView: List<Int>,
		/** Known pairs, best first. */
		val pairs: List<List<Int>>,
		/** Known cards whose twin hasn't shown yet. */
		val singles: List<Int>,
		val faceDown: List<Int>,
		/** Face-down cards that have never shown, the least tried first. */
		val unknown: List<Int>,
		/** Every unknown card is the twin of a single (bar one powerup): nothing new is left to find. */
		val boardKnown: Boolean,
		/** Two unknown and no single: they're a pair of their own. */
		val lastTwo: Boolean,
	)

	var view: View = View(emptySet(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, false)
		private set

	private fun look(): View {
		val taken = taken()
		val inView = board.filter { it in seen && it !in taken && !ExperimentRules.isPowerup(seen.getValue(it)) }
		val unknown = hidden.filter { it !in seen }.sortedBy { it in clickedSlots }
		val groups = inView.groupBy { key(it) }.values
		val pairs = groups.filter { it.size >= 2 }.map { it.take(2) }.sortedWith { a, b -> bestFirst.compare(a.first(), b.first()) }
		val singles = groups.filter { it.size == 1 }.map { it.first() }
		// An unknown card no single accounts for could be half of an unseen pair, until only one is left.
		val boardKnown = complete && unknown.size - singles.size in 0..1
		val lastTwo = complete && unknown.size == 2 && singles.isEmpty()
		return View(taken, inView, pairs, singles, hidden, unknown, boardKnown, lastTwo).also { view = it }
	}

	enum class CardState { TAKEN, PAIR, WAITING, POWERUP }

	/** A known card's standing, for the highlight. */
	fun stateOf(slot: Int): CardState? {
		val cell = seen[slot] ?: return null
		val view = view
		return when {
			slot in view.taken -> CardState.TAKEN
			ExperimentRules.isPowerup(cell) -> CardState.POWERUP
			view.pairs.any { slot in it } -> CardState.PAIR
			slot in view.singles -> CardState.WAITING
			else -> null
		}
	}

	fun card(slot: Int): ExperimentCell? = seen[slot]

	val cards: Map<Int, ExperimentCell> get() = seen

	private fun decide(): List<Int> {
		val view = look()
		val answering = prompt == ExperimentRules.ANSWERING_NAME && !promptDistrusted
		if (pending != null || answering || clicksLeft <= 0) return emptyList()
		val inView = view.inView
		val unseen = view.unknown

		// The second click: the first card's twin if it's in view, else another new card.
		turnFirst?.let { first ->
			// The first card hasn't shown yet: wait for it. Only once stalled is the turn finished blind.
			val firstKey = key(first) ?: return if (!promptDistrusted) emptyList()
			else listOfNotNull(unseen.firstOrNull { it != first } ?: inView.firstOrNull { it != first })
			val twin = inView.firstOrNull { it != first && key(it) == firstKey }
			// Hunting valuables, a twin not worth going for waits while the board is still being learnt,
			// unless no full turn would be left to use what another card shows.
			val wait = twin != null && options.rareItemsOnly && !isRare(first) && !view.boardKnown &&
				unseen.isNotEmpty() && clicksLeft > 2
			return listOfNotNull((if (wait) null else twin) ?: unseen.firstOrNull() ?: inView.firstOrNull { it != first })
		}

		val pairs = view.pairs
		val singles = view.singles

		// Instant Find pays out the next card on its own: best spent on one with no twin in view.
		if (prompt == ExperimentRules.INSTANT_FIND_NAME) {
			val best = singles.sortedWith(bestFirst).firstOrNull()
			val worthIt = best != null && (unseen.isEmpty() || !options.rareItemsOnly || isRare(best))
			return listOfNotNull(if (worthIt) best else unseen.firstOrNull() ?: best ?: inView.firstOrNull())
		}

		// A lone click can't make a pair, but a new card might be extra clicks.
		if (clicksLeft < 2) return listOfNotNull(unseen.firstOrNull())

		val lastTwo = view.lastTwo

		if (options.rareItemsOnly) {
			// A valuable pair is taken the moment both are known.
			pairs.firstOrNull { isRare(it.first()) }?.let { return it }
			if (lastTwo) return unseen
			// While an unseen pair could still be anything, cards are turned, to the last turn.
			if (unseen.isNotEmpty() && !view.boardKnown) return listOf(unseen.first())
			// The board is known: what's left is paid out by worth, below the Rare from price too,
			// and experience last. An unseen card pays one of the waiting cards' twins.
			val bestPair = pairs.minWithOrNull { a, b -> byWorth.compare(a.first(), b.first()) }
			val bestSingle = singles.minWithOrNull(byWorth)
			val turnNew = bestSingle != null && (bestPair == null || byWorth.compare(bestSingle, bestPair.first()) < 0)
			if (unseen.isNotEmpty() && turnNew) return listOf(unseen.first())
			bestPair?.let { return it }
		} else {
			// Experience, but never past a card worth going for.
			pairs.firstOrNull { isRare(it.first()) }?.let { return it }
			if (lastTwo) return unseen
			// New cards while the clicks still cover every pair in view.
			if (unseen.isNotEmpty() && clicksLeft - 2 >= 2 * pairs.size) return listOf(unseen.first())
		}
		pairs.firstOrNull()?.let { return it }
		return listOfNotNull(unseen.firstOrNull())
	}

	/** Empty while the last click hasn't shown on the board, so the next isn't sent blind. */
	override fun clickOrder(): List<Int> = if (pending != null) emptyList() else plan

	private fun clicked(slot: Int) {
		lastClicked = slot
		clickedSlots += slot
		if (prompt == ExperimentRules.INSTANT_FIND_NAME) {
			instantlyRewarded += slot
			turnFirst = null
			return
		}
		val first = turnFirst
		if (first == null) {
			turnFirst = slot
		} else {
			turns += first to slot
			turnFirst = null
		}
	}

	override fun advance() {
		val slot = plan.firstOrNull() ?: return
		plan = plan.drop(1)
		clicked(slot)
		pending = slot
		pendingBoard = boardState()
		pendingWasHidden = slot in hidden
		pendingClicks = clicksLeft
	}

	override fun clickedByHand(slot: Int) {
		if (slot !in board) return
		clicked(slot)
		pending = null
	}

	/** The last click never showed: it's tried again rather than waited on forever. */
	fun forgetPending() {
		val slot = pending ?: return
		pending = null
		// Sent again once; a card that ignores two clicks goes to the back of the queue.
		if (retried.add(slot)) clickedSlots -= slot
		// It never counted, so the turn stands where it did.
		if (turns.lastOrNull()?.second == slot) turnFirst = turns.removeAt(turns.size - 1).first
		else if (turnFirst == slot) turnFirst = null
		plan = decide()
	}

	val waitingOnClick: Boolean get() = pending != null

	/**
	 * Nothing planned while clicks are left and nothing is pending: the face-down cards still say
	 * "?" long after the last click. Plays on as if the board had answered, until it next changes.
	 */
	fun unstick() {
		promptDistrusted = true
		plan = decide()
	}

	/** Clicks left, none pending, nothing planned and not finished: the solver has stalled. */
	val stalled: Boolean
		get() = clicksLeft in 1 until Int.MAX_VALUE && pending == null && plan.isEmpty() && !finished

	/** Out of clicks, or nothing left that a click could earn: time to leave and take the rewards. */
	val finished: Boolean
		get() = pending == null && complete && clicksLeft != Int.MAX_VALUE &&
			(clicksLeft <= 0 || (turnFirst == null && plan.isEmpty() && (prompt != ExperimentRules.ANSWERING_NAME || promptDistrusted)))

	override fun roundsDone(): Int = taken().size / 2
}

