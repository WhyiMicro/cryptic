package imicro.cryptic.experiment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Experimentation Table rules.
 *
 * The Chronomatron and Ultrasequencer cases are the ones Astrail Experiment
 * (`AzureSky0116/astrail-experiment`, MIT) tests its own solver with. The
 * Superpairs cases are this mod's own, and describe the order it is meant to
 * work a board in — read it along, take what is worth keeping, and leave
 * experience alone until there is nothing left to turn over.
 *
 * These are the only verification this feature has: a real table can only be
 * played a few times a day, so a test is the only way to try a rule more than
 * once.
 */
class ExperimentSolverTest {
	private val xpMode = PlayOptions(rareItemsOnly = false)
	private val itemsMode = PlayOptions(rareItemsOnly = true)

	private fun cell(slot: Int, itemId: String, count: Int = 1, foil: Boolean = false, name: String = "") =
		ExperimentCell(slot, itemId, count, foil, name, itemId.isEmpty())

	private fun showing() = cell(ExperimentRules.CONTROL_SLOT, ExperimentRules.SHOWING_ITEM, name = "Control")

	private fun entering() = cell(ExperimentRules.CONTROL_SLOT, ExperimentRules.ENTERING_ITEM, name = "Control")

	private fun faceDown(slot: Int) =
		cell(slot, ExperimentRules.FACE_DOWN_ITEM, name = "Click any button!")

	private fun clicks(left: Int) = cell(ExperimentRules.CLICKS_SLOT, "minecraft:bookshelf", count = left, name = "Remaining Clicks: $left")

	private fun book(slot: Int) = cell(slot, "minecraft:enchanted_book", name = "Enchanted Book")

	private fun xp(slot: Int) = cell(slot, "minecraft:experience_bottle", name = "Grand Experience Bottle")

	@Test
	fun `picks the game out of the chest title`() {
		assertEquals(ExperimentRules.Game.CHRONOMATRON, ExperimentRules.gameOf("Chronomatron (Metaphysical)"))
		assertEquals(ExperimentRules.Game.ULTRASEQUENCER, ExperimentRules.gameOf("Ultrasequencer (Metaphysical)"))
		assertEquals(ExperimentRules.Game.SUPERPAIRS, ExperimentRules.gameOf("Superpairs (Metaphysical)"))
		assertEquals(null, ExperimentRules.gameOf(""))
		assertEquals(null, ExperimentRules.gameOf("Experimentation Table"))
	}

	@Test
	fun `the stake pickers and stats screens are not games`() {
		assertEquals(null, ExperimentRules.gameOf("Chronomatron Stats"))
		assertEquals(null, ExperimentRules.gameOf("Ultrasequencer Stats"))
		assertEquals(null, ExperimentRules.gameOf("Chronomatron ➜ Stakes"))
		assertEquals(null, ExperimentRules.gameOf("Superpairs ➜ Stakes"))
	}

	@Test
	fun `chronomatron takes one note a round and replays them in order`() {
		val solver = ChronomatronHandler()

		solver.update(listOf(showing(), cell(20, "minecraft:red_dye", foil = true)), xpMode)
		assertTrue(solver.clickOrder().isEmpty())

		val round1 = listOf(entering(), cell(20, "minecraft:red_dye", foil = true))
		solver.update(round1, xpMode)
		assertEquals(listOf(20), solver.clickOrder())
		solver.advance()
		assertTrue(solver.clickOrder().isEmpty())

		// The glint stays put for the rest of the round; it must not be re-taken.
		solver.update(round1, xpMode)
		assertEquals(1, solver.roundsDone())

		solver.update(listOf(showing()), xpMode)
		solver.update(
			listOf(entering(), cell(20, "minecraft:red_dye"), cell(25, "minecraft:red_dye", foil = true)),
			xpMode,
		)
		assertEquals(listOf(20, 25), solver.clickOrder())
		assertEquals(2, solver.roundsDone())
	}

	@Test
	fun `chronomatron ignores a glint outside its own rows`() {
		val solver = ChronomatronHandler()
		solver.update(listOf(entering(), cell(4, "minecraft:red_dye", foil = true)), xpMode)
		assertTrue(solver.clickOrder().isEmpty())
		assertEquals(0, solver.roundsDone())
	}

	@Test
	fun `ultrasequencer orders the notes by stack size and replays them`() {
		val solver = UltrasequencerHandler()

		solver.update(
			listOf(
				showing(),
				cell(12, "minecraft:lime_dye", count = 1),
				cell(30, "minecraft:lime_dye", count = 2),
				cell(40, "minecraft:lime_dye", count = 3),
				cell(22, "minecraft:lime_dye", count = 4),
			),
			xpMode,
		)
		assertTrue(solver.clickOrder().isEmpty())

		solver.update(listOf(entering()), xpMode)
		assertEquals(listOf(12, 30, 40, 22), solver.clickOrder())

		solver.advance()
		assertEquals(listOf(30, 40, 22), solver.clickOrder())
		solver.advance()
		solver.advance()
		solver.advance()
		assertTrue(solver.clickOrder().isEmpty())
	}

	@Test
	fun `ultrasequencer takes lapis and bone meal as notes too`() {
		val solver = UltrasequencerHandler()
		solver.update(
			listOf(showing(), cell(12, "minecraft:lapis_lazuli", count = 1), cell(30, "minecraft:bone_meal", count = 2)),
			xpMode,
		)
		solver.update(listOf(entering()), xpMode)
		assertEquals(listOf(12, 30), solver.clickOrder())
	}

	@Test
	fun `superpairs turns cards over along the board when nothing is known`() {
		val solver = SuperpairsHandler()
		solver.update(listOf(clicks(26), faceDown(19), faceDown(11), faceDown(10)), xpMode)
		// Board order, not the order the slots happened to arrive in.
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `superpairs moves on rather than clicking the card it just turned`() {
		val solver = SuperpairsHandler()
		val board = listOf(clicks(26), faceDown(10), faceDown(11))
		solver.update(board, xpMode)
		assertEquals(listOf(10), solver.clickOrder())

		solver.advance()
		// The board has not caught up yet: 10 still looks face down.
		solver.update(board, xpMode)
		assertEquals(listOf(11), solver.clickOrder())
	}

	@Test
	fun `superpairs takes a pair before turning anything else over`() {
		val solver = SuperpairsHandler()
		solver.update(listOf(clicks(26), book(10), book(11), faceDown(12)), xpMode)
		assertEquals(listOf(10, 11), solver.clickOrder())
	}

	@Test
	fun `hunting items, an experience pair is left while cards are still face down`() {
		val solver = SuperpairsHandler()
		solver.update(listOf(clicks(26), xp(10), xp(11), faceDown(12), faceDown(13)), itemsMode)
		// The experience pair is ignored; the unturned cards come first.
		assertEquals(listOf(12), solver.clickOrder())
	}

	@Test
	fun `hunting items, a book pair is taken straight away`() {
		val solver = SuperpairsHandler()
		solver.update(listOf(clicks(26), xp(10), xp(11), book(12), book(13), faceDown(14)), itemsMode)
		assertEquals(listOf(12, 13), solver.clickOrder())
	}

	@Test
	fun `hunting items, experience is taken once the board is all turned over`() {
		val solver = SuperpairsHandler()
		// Nothing face down and no book anywhere: the experience pair is all
		// that is left, and the clicks would otherwise go to waste.
		solver.update(listOf(clicks(4), xp(10), xp(11)), itemsMode)
		assertEquals(listOf(10, 11), solver.clickOrder())
	}

	@Test
	fun `hunting experience, a pair is taken as soon as it is known`() {
		val solver = SuperpairsHandler()
		solver.update(listOf(clicks(26), xp(10), xp(11), faceDown(12)), xpMode)
		assertEquals(listOf(10, 11), solver.clickOrder())
	}

	@Test
	fun `superpairs never offers a pair it has already taken`() {
		val solver = SuperpairsHandler()
		val board = listOf(clicks(26), book(10), book(11))
		solver.update(board, xpMode)
		assertEquals(listOf(10, 11), solver.clickOrder())

		solver.advance()
		solver.advance()
		solver.update(board, xpMode)
		assertTrue(solver.clickOrder().isEmpty())
		assertEquals(1, solver.roundsDone())
	}

	@Test
	fun `superpairs ignores the covers, the border and anything unnamed`() {
		val solver = SuperpairsHandler()
		solver.update(
			listOf(
				clicks(26),
				cell(10, "minecraft:black_stained_glass_pane"),
				cell(11, "minecraft:black_stained_glass_pane"),
				cell(30, "minecraft:black_dye", name = "Cover"),
				cell(31, "minecraft:black_dye", name = "Cover"),
				cell(40, "minecraft:red_dye"),
			),
			xpMode,
		)
		assertTrue(solver.clickOrder().isEmpty())
	}

	@Test
	fun `superpairs remembers a card seen in an earlier update`() {
		val solver = SuperpairsHandler()
		solver.update(listOf(clicks(26), book(11)), xpMode)
		// The board turns that one back over and shows its twin later.
		solver.update(listOf(clicks(26), book(22)), xpMode)
		assertEquals(listOf(11, 22), solver.clickOrder())
	}

	@Test
	fun `experience cards are told apart from everything else`() {
		assertTrue(ExperimentRules.isExperienceCard(xp(10)))
		assertTrue(ExperimentRules.isExperienceCard(cell(10, "minecraft:paper", name = "50k Enchanting Exp")))
		assertTrue(!ExperimentRules.isExperienceCard(book(10)))
		// Anything unrecognised counts as worth keeping, not as experience.
		assertTrue(!ExperimentRules.isExperienceCard(cell(10, "minecraft:pink_dye", name = "Nadeshiko Dye")))
		assertTrue(!ExperimentRules.isExperienceCard(cell(10, "minecraft:feather", name = "+3 Clicks")))
	}

	@Test
	fun `colour codes are stripped, and untouched text is handed straight back`() {
		assertEquals("Renew Experiments", ExperimentRules.stripFormatting("§aRenew Experiments"))
		assertEquals("Timer: 90s", ExperimentRules.stripFormatting("§c§lTimer: §f90s".replace("§f", "§f")))
		assertEquals("", ExperimentRules.stripFormatting("§a"))

		// The common case allocates nothing: the same instance comes back.
		val plain = "Remaining Clicks: 26"
		assertSame(plain, ExperimentRules.stripFormatting(plain))
	}

	@Test
	fun `the click budget is read off the bookshelf`() {
		assertEquals(26, ExperimentRules.clicksLeft(listOf(clicks(26))))
		assertEquals(null, ExperimentRules.clicksLeft(listOf(book(10))))
	}
}
