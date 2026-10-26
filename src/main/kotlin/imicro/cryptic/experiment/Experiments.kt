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

	/** Superpairs shows the clicks left as a bookshelf's stack size. */
	const val CLICKS_SLOT = 4

	const val FACE_DOWN_ITEM = "minecraft:cyan_stained_glass"

	fun clicksLeft(cells: List<ExperimentCell>): Int? =
		cells.firstOrNull { it.slot == CLICKS_SLOT && !it.empty }?.count

	fun isFaceDown(cell: ExperimentCell): Boolean = cell.itemId == FACE_DOWN_ITEM

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

	abstract fun roundsDone(): Int

	open val waiting: Boolean get() = clickOrder().isEmpty()
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
}

/**
 * Superpairs: pairs matched from memory.
 *
 * Hunting items, cards are turned over in board order and only pairs where both halves aren't
 * experience are taken. Experience pairs wait until nothing is face down, since two clicks spent on
 * them could turn over two unseen cards. Hunting experience, a pair is taken as soon as both halves
 * are known.
 */
class SuperpairsHandler : ExperimentHandler(ExperimentRules.Game.SUPERPAIRS) {
	private val seen = mutableMapOf<Int, ExperimentCell>()

	private val spent = mutableSetOf<Int>()

	private var faceDown = listOf<Int>()

	/** The slot just clicked, before the board catches up. */
	private var awaiting: Int? = null

	private var plan = listOf<Int>()
	private var pairsTaken = 0

	var clicksLeft: Int = Int.MAX_VALUE
		private set

	override fun update(cells: List<ExperimentCell>, options: PlayOptions) {
		ExperimentRules.clicksLeft(cells)?.let { clicksLeft = it }

		// Sorted, so cards go in board order.
		faceDown = cells.filter { it.slot in ExperimentRules.GRID_SLOTS && ExperimentRules.isFaceDown(it) }
			.map { it.slot }
			.sorted()

		cells.forEach { cell ->
			if (cell.slot !in ExperimentRules.GRID_SLOTS) return@forEach
			if (!ExperimentRules.isRevealedPair(cell)) return@forEach
			seen[cell.slot] = cell
		}

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

		worthTaking.firstOrNull()?.let { group ->
			return group.take(2).map { it.slot }
		}

		faceDown.firstOrNull { it != awaiting }?.let { return listOf(it) }

		// Nothing left face down: now experience pairs are worth the remaining clicks.
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
			awaiting = slot
			return
		}

		spent.add(slot)
		if (spent.size / 2 > pairsTaken) pairsTaken = spent.size / 2
	}

	override fun roundsDone(): Int = pairsTaken
}
