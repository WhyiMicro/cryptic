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

	private fun cell(slot: Int, itemId: String, count: Int = 1, foil: Boolean = false, name: String = "", lore: List<String> = emptyList()) =
		ExperimentCell(slot, itemId, count, foil, name, itemId.isEmpty(), lore)

	private fun showing() = cell(ExperimentRules.CONTROL_SLOT, ExperimentRules.SHOWING_ITEM, name = "Control")

	private fun entering() = cell(ExperimentRules.CONTROL_SLOT, ExperimentRules.ENTERING_ITEM, name = "Control")

	private fun faceDown(slot: Int) =
		cell(slot, ExperimentRules.FACE_DOWN_ITEM, name = "Click any button!")

	private fun clicks(left: Int) = cell(ExperimentRules.CLICKS_SLOT, "minecraft:bookshelf", count = left, name = "Remaining Clicks: $left")

	/** Every book is called Enchanted Book; the enchantment is in the lore. */
	private fun book(slot: Int, enchantment: String = "Smite VII") =
		cell(slot, "minecraft:enchanted_book", name = "Enchanted Book", lore = listOf("", enchantment))

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

	/*
	 * Superpairs as Hypixel plays it now (seen in a 2026-11-03 run): a turned card stays face up,
	 * and a pair pays only when both are clicked in one turn. Six-card boards keep these short.
	 */
	private val board = (10..15).toList()

	private fun exp(slot: Int, amount: String) = cell(slot, "minecraft:cyan_dye", name = "$amount Enchanting Exp", lore = listOf("Skill Exp Reward"))

	/** The board as Hypixel sends it: [up] face up, every other card face down and called [prompt]. */
	private fun SuperpairsHandler.see(clicks: Int, up: List<ExperimentCell>, options: PlayOptions = xpMode, prompt: String = ExperimentRules.TURN_START_NAME) {
		val shown = up.map { it.slot }.toSet()
		val down = (this.board - shown).map { cell(it, ExperimentRules.FACE_DOWN_ITEM, name = prompt) }
		update(listOf(clicks(clicks)) + up + down, options)
	}

	@Test
	fun `superpairs turns cards over along the board when nothing is known`() {
		val solver = SuperpairsHandler()
		solver.update(listOf(clicks(26), faceDown(19), faceDown(11), faceDown(10)), xpMode)
		// Board order, not the order the slots happened to arrive in.
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `superpairs waits for the board to answer before the next click`() {
		val solver = SuperpairsHandler(board)
		solver.see(26, emptyList())
		assertEquals(listOf(10), solver.clickOrder())

		solver.advance()
		// The board refreshes about once a second; until it does, nothing is sent blind.
		solver.see(26, emptyList())
		assertTrue(solver.clickOrder().isEmpty())

		// Now it shows; its twin isn't in view, so the second click turns another new card.
		solver.see(25, listOf(book(10)), prompt = ExperimentRules.SECOND_CLICK_NAME)
		assertEquals(listOf(11), solver.clickOrder())
	}

	@Test
	fun `superpairs gives up waiting on a click that never showed`() {
		val solver = SuperpairsHandler(board)
		solver.see(26, emptyList())
		solver.advance()
		solver.see(26, emptyList())
		assertTrue(solver.clickOrder().isEmpty())
		solver.forgetPending()
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `cards that turn back over are remembered, not turned again`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, emptyList())
		solver.clickedByHand(10)
		solver.see(19, listOf(book(10, "Growth VI")), prompt = ExperimentRules.SECOND_CLICK_NAME)
		solver.clickedByHand(11)
		solver.see(18, listOf(book(10, "Growth VI"), exp(11, "44k")))
		// The missed pair turns back over: the next turn goes on a card that hasn't shown.
		solver.see(18, emptyList())
		assertEquals(listOf(12), solver.clickOrder())
		assertEquals("Growth VI", solver.remembered(10)?.let(ExperimentRules::cardReward))

		// Its twin shows; the second click goes back to the remembered card, face down or not.
		solver.clickedByHand(12)
		solver.see(17, listOf(book(12, "Growth VI")), prompt = ExperimentRules.SECOND_CLICK_NAME)
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `a board left on its answering prompt is played on once stalled`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, emptyList(), prompt = ExperimentRules.ANSWERING_NAME)
		assertTrue(solver.clickOrder().isEmpty())
		assertTrue(solver.stalled)
		solver.unstick()
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `a first card that never showed doesn't hold up the turn for good`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, emptyList())
		solver.clickedByHand(10)
		solver.see(19, emptyList(), prompt = ExperimentRules.SECOND_CLICK_NAME)
		// It may still show; only once stalled is the turn finished on another card.
		assertTrue(solver.clickOrder().isEmpty())
		solver.unstick()
		assertEquals(listOf(11), solver.clickOrder())
	}

	@Test
	fun `cards turning back over aren't taken for the answer to a click`() {
		// The run of 2026-11-03: 40 was clicked, 38 and 39 turned back before 40 showed, and the last
		// click went on a new card instead of 40's twin.
		val solver = SuperpairsHandler(board)
		solver.see(2, listOf(exp(10, "138k"), exp(11, "42k")), itemsMode)
		assertEquals(listOf(12), solver.clickOrder())
		solver.advance()
		solver.see(2, emptyList(), itemsMode, prompt = ExperimentRules.ANSWERING_NAME)
		assertTrue(solver.clickOrder().isEmpty())
		solver.see(1, listOf(exp(12, "138k")), itemsMode, prompt = ExperimentRules.SECOND_CLICK_NAME)
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `a remembered pair face down is taken like one in view`() {
		val solver = SuperpairsHandler(board)
		solver.see(10, listOf(exp(10, "135k"), exp(11, "135k")))
		solver.see(3, emptyList())
		assertEquals(listOf(10, 11), solver.clickOrder())
	}

	@Test
	fun `the second click goes to the first card's twin in view`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(book(10), exp(11, "44k")))
		solver.clickedByHand(12)
		solver.see(19, listOf(book(10), exp(11, "44k"), book(12)), prompt = ExperimentRules.SECOND_CLICK_NAME)
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `playing for experience, a pair in view is taken once the clicks only cover it`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(exp(10, "135k"), exp(11, "135k")))
		// Clicks to spare: a new card first, since the pair stays where it is.
		assertEquals(listOf(12), solver.clickOrder())
		solver.see(3, listOf(exp(10, "135k"), exp(11, "135k")))
		assertEquals(listOf(10, 11), solver.clickOrder())
	}

	@Test
	fun `on focus xp, only a book worth 10M comes first, whatever rare from says`() {
		val cards = listOf(book(10, "Smite VII"), book(11, "Smite VII"), exp(12, "135k"), exp(13, "135k"), exp(14, "44k"))
		val dear = SuperpairsHandler(board)
		dear.see(3, cards, priced(12_000_000.0, xpMode.copy(rareFrom = 1_000_000.0)))
		assertEquals(listOf(10, 11), dear.clickOrder())

		val cheaper = SuperpairsHandler(board)
		cheaper.see(3, cards, priced(5_000_000.0, xpMode.copy(rareFrom = 1_000_000.0)))
		assertEquals(listOf(12, 13), cheaper.clickOrder())

		// With no known price it's levelling that counts.
		val unpriced = SuperpairsHandler(board)
		unpriced.see(3, cards)
		assertEquals(listOf(12, 13), unpriced.clickOrder())
	}

	@Test
	fun `hunting items, a valuable pair is taken as soon as it's in view`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(book(10, "Growth VI"), book(11, "Growth VI")), itemsMode)
		assertEquals(listOf(10, 11), solver.clickOrder())
	}

	@Test
	fun `hunting items, experience waits until the board is turned`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(exp(10, "135k"), exp(11, "135k")), itemsMode)
		assertEquals(listOf(12), solver.clickOrder())
		// Even on the last turn, while the unseen cards could be anything.
		solver.see(3, listOf(exp(10, "135k"), exp(11, "135k")), itemsMode)
		assertEquals(listOf(12), solver.clickOrder())
		// Once nothing is left to find, the experience is taken.
		val turned = SuperpairsHandler(board)
		turned.see(3, listOf(exp(10, "135k"), exp(11, "135k"), exp(12, "44k"), exp(13, "44k"), exp(14, "26k"), exp(15, "26k")), itemsMode)
		assertEquals(listOf(10, 11), turned.clickOrder())
	}

	@Test
	fun `hunting items, a new card's experience twin waits while cards are unseen`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(exp(10, "135k")), itemsMode)
		solver.clickedByHand(11)
		solver.see(19, listOf(exp(10, "135k"), exp(11, "135k")), itemsMode, prompt = ExperimentRules.SECOND_CLICK_NAME)
		// The second click turns another card rather than paying the experience.
		assertEquals(listOf(12), solver.clickOrder())

		// A valuable twin is taken at once.
		val books = SuperpairsHandler(board)
		books.see(20, listOf(book(10, "Growth VI")), itemsMode)
		books.clickedByHand(11)
		books.see(19, listOf(book(10, "Growth VI"), book(11, "Growth VI")), itemsMode, prompt = ExperimentRules.SECOND_CLICK_NAME)
		assertEquals(listOf(10), books.clickOrder())
	}

	@Test
	fun `the last two face down are a pair when nothing waits for a twin`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(exp(10, "44k"), exp(11, "44k"), exp(12, "135k"), exp(13, "135k")), itemsMode)
		assertEquals(listOf(14, 15), solver.clickOrder())
	}

	@Test
	fun `on a known board, the last turn still turns a card while a valuable twin is face down`() {
		// 10 and 13 wait for twins, and 14 and 15 are face down: they're those twins.
		val waiting = listOf(book(10, "Growth VI"), exp(11, "44k"), exp(12, "44k"), exp(13, "88k"))
		val solver = SuperpairsHandler(board)
		solver.see(3, waiting, itemsMode)
		assertEquals(listOf(14), solver.clickOrder())

		// Both waiting cards pay less than the pair in view: the pair is surer.
		val cheap = SuperpairsHandler(board)
		cheap.see(3, listOf(exp(10, "26k"), exp(11, "44k"), exp(12, "44k"), exp(13, "30k")), itemsMode)
		assertEquals(listOf(11, 12), cheap.clickOrder())
	}

	@Test
	fun `hunting items, the last turn turns a card while an unseen pair could be anything`() {
		// The run of 2026-11-03: two cards wait for twins, four are unseen, so one unseen pair is unknown.
		val big = SuperpairsHandler((10..17).toList())
		big.see(2, listOf(exp(10, "144k"), exp(11, "144k"), exp(12, "47k"), book(13, "Ender Slayer VI")), priced(135_000.0, itemsMode.copy(rareFrom = 1_000_000.0)))
		assertEquals(listOf(14), big.clickOrder())
	}

	@Test
	fun `hunting items, a known board pays the most valuable first, under the rare from price too`() {
		val bottle = { slot: Int -> cell(slot, "minecraft:player_head", name = "Grand Experience Bottle") }
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(exp(10, "144k"), exp(11, "144k"), bottle(12), bottle(13), exp(14, "44k"), exp(15, "44k")), priced(5_000.0, itemsMode))
		assertEquals(listOf(12, 13), solver.clickOrder())
	}

	@Test
	fun `two different books are not a pair`() {
		val solver = SuperpairsHandler(board)
		solver.see(3, listOf(book(10, "Sharpness VII"), book(11, "Smite VII")), itemsMode)
		// Both are Enchanted Books; only their enchantments tell them apart.
		assertEquals(listOf(12), solver.clickOrder())
	}

	private val fullBoard = listOf(book(10, "Growth VI"), book(11, "Growth VI"), exp(12, "135k"), exp(13, "135k"), exp(14, "44k"), exp(15, "44k"))

	@Test
	fun `with every card in view, the clicks go on the best pair`() {
		val items = SuperpairsHandler(board)
		items.see(4, fullBoard, itemsMode)
		assertEquals(listOf(10, 11), items.clickOrder())

		val experience = SuperpairsHandler(board)
		experience.see(4, fullBoard, xpMode)
		assertEquals(listOf(12, 13), experience.clickOrder())
	}

	@Test
	fun `rare is from the price set, and a book with no price always is`() {
		// Rare from decides whether a pair is taken at once or the board is turned first.
		val growth = itemsMode.copy(valueOf = { if (ExperimentRules.cardReward(it) == "Growth VI") 3_000_000.0 else 0.0 })
		val halfBoard = fullBoard.take(4)
		val solver = SuperpairsHandler(board)
		solver.see(20, halfBoard, growth)
		assertEquals(listOf(10, 11), solver.clickOrder())

		val pricier = SuperpairsHandler(board)
		// Below the price, the last two unseen (a pair, since nothing waits for a twin) come first.
		pricier.see(20, halfBoard, growth.copy(rareFrom = 5_000_000.0))
		assertEquals(listOf(14, 15), pricier.clickOrder())

		val unpriced = SuperpairsHandler(board)
		unpriced.see(20, halfBoard, itemsMode.copy(rareFrom = 5_000_000.0))
		assertEquals(listOf(10, 11), unpriced.clickOrder())
	}

	@Test
	fun `a pair taken in one turn is never offered again`() {
		val solver = SuperpairsHandler(board)
		solver.see(6, fullBoard, itemsMode)
		solver.clickedByHand(10)
		solver.clickedByHand(11)
		solver.see(4, fullBoard, itemsMode)
		assertEquals(1, solver.roundsDone())
		assertEquals(listOf(12, 13), solver.clickOrder())
	}

	@Test
	fun `two of a kind clicked in different turns are not a pair`() {
		val solver = SuperpairsHandler(board)
		solver.see(10, fullBoard)
		solver.clickedByHand(10)
		solver.clickedByHand(12)
		solver.clickedByHand(11)
		solver.clickedByHand(14)
		solver.see(6, fullBoard)
		assertEquals(0, solver.roundsDone())
	}

	@Test
	fun `superpairs is done when the clicks run out or can't earn more`() {
		val solver = SuperpairsHandler(board)
		solver.see(8, listOf(book(10)))
		assertTrue(!solver.finished)
		solver.see(0, listOf(book(10)))
		assertTrue(solver.finished)

		// One click left and nothing face down: no pair can be made.
		val lone = SuperpairsHandler(board)
		lone.see(1, fullBoard)
		assertTrue(lone.clickOrder().isEmpty())
		assertTrue(lone.finished)
	}

	@Test
	fun `a lone click still turns a new card, which might be more clicks`() {
		val solver = SuperpairsHandler(board)
		solver.see(1, listOf(book(10)))
		assertEquals(listOf(11), solver.clickOrder())
	}

	@Test
	fun `instant find goes on the best card with no twin in view`() {
		val solver = SuperpairsHandler(board)
		solver.see(10, listOf(book(10), exp(11, "44k"), exp(12, "44k")), itemsMode, prompt = ExperimentRules.INSTANT_FIND_NAME)
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `powerups are not pairs`() {
		val solver = SuperpairsHandler(board)
		val powerup = { slot: Int -> cell(slot, "minecraft:feather", name = "Gained +3 Clicks", lore = listOf("Superpairs Powerup")) }
		solver.see(3, listOf(powerup(10), powerup(11)))
		assertEquals(listOf(12), solver.clickOrder())
	}

	@Test
	fun `nothing is clicked while the board answers`() {
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(book(10)), prompt = ExperimentRules.ANSWERING_NAME)
		assertTrue(solver.clickOrder().isEmpty())
	}

	private fun priced(coins: Double, options: PlayOptions) = options.copy(valueOf = { if (ExperimentRules.experienceOf(it) > 0) 0.0 else coins })

	@Test
	fun `playing for experience, the price decides whether an item comes first`() {
		val dye = { slot: Int -> cell(slot, "minecraft:pink_dye", name = "Nadeshiko Dye") }
		val cards = listOf(dye(10), dye(11), exp(12, "135k"), exp(13, "135k"), exp(14, "44k"))

		val dear = SuperpairsHandler(board)
		dear.see(3, cards, priced(50_000_000.0, xpMode))
		assertEquals(listOf(10, 11), dear.clickOrder())

		val cheap = SuperpairsHandler(board)
		cheap.see(3, cards, priced(200_000.0, xpMode))
		assertEquals(listOf(12, 13), cheap.clickOrder())
	}

	@Test
	fun `experience bottles follow the price too`() {
		val bottle = { slot: Int -> cell(slot, "minecraft:player_head", name = "Titanic Experience Bottle") }

		val dear = SuperpairsHandler(board)
		dear.see(20, listOf(bottle(10), bottle(11)), priced(1_500_000.0, itemsMode))
		assertEquals(listOf(10, 11), dear.clickOrder())

		val cheap = SuperpairsHandler(board)
		cheap.see(20, listOf(bottle(10), bottle(11)), priced(500_000.0, itemsMode))
		assertEquals(listOf(12), cheap.clickOrder())
	}

	@Test
	fun `two unaccounted face-down cards keep the board open`() {
		// 10 and 11 wait for twins, and four are face down: two could be a pair not yet seen.
		val solver = SuperpairsHandler((10..17).toList())
		solver.update(
			listOf(clicks(20), book(10, "Growth VI"), exp(11, "88k"), exp(12, "44k"), exp(13, "44k")) +
				(14..17).map(::faceDown),
			itemsMode,
		)
		assertTrue(!solver.view.boardKnown)
		// Two face down for two singles: now they can only be those twins.
		val known = SuperpairsHandler(board)
		known.see(20, listOf(book(10, "Growth VI"), exp(11, "88k"), exp(12, "44k"), exp(13, "44k")), itemsMode)
		assertTrue(known.view.boardKnown)
	}

	@Test
	fun `the stake's lore says which chain pays the most clicks`() {
		assertEquals(9, ExperimentRules.maxClicksChain(listOf("Superpairs Rewards:", "Chain of 2: +1 Click", "Chain of 6: +2 Clicks", "Chain of 9: +3 Clicks")))
		assertEquals(7, ExperimentRules.maxClicksChain(listOf("Series of 5: +1 Click", "Series of 7: +2 Clicks")))
		assertEquals(null, ExperimentRules.maxClicksChain(listOf("Click to play!")))
	}

	@Test
	fun `enchanting experience is read off the card's name`() {
		assertEquals(135_000.0, ExperimentRules.experienceOf(exp(10, "135k")))
		assertEquals(0.0, ExperimentRules.experienceOf(book(10)))
	}

	@Test
	fun `face-down cards are known by name too`() {
		assertTrue(ExperimentRules.isFaceDown(cell(10, "minecraft:purple_stained_glass", name = "Click a second button!")))
		assertTrue(ExperimentRules.isFaceDown(cell(10, "minecraft:gray_stained_glass", name = "?")))
		assertTrue(!ExperimentRules.isFaceDown(book(10)))
	}

	@Test
	fun `each stake has its own board`() {
		assertEquals((19..25).toList() + (28..34).toList(), ExperimentRules.superpairsBoard("Superpairs (Beginner)"))
		val high = ExperimentRules.superpairsBoard("Superpairs (High)")
		assertEquals(20, high.size)
		assertEquals(listOf(11, 12, 13, 14, 15), high.take(5))
		assertEquals(28, ExperimentRules.superpairsBoard("Superpairs (Metaphysical)").size)
		assertEquals(ExperimentRules.GRID_SLOTS.toList(), ExperimentRules.superpairsBoard("Superpairs (Something New)"))
	}

	@Test
	fun `a small board ignores the slots around it`() {
		val solver = SuperpairsHandler(ExperimentRules.superpairsBoard("Superpairs (Beginner)"))
		solver.update(listOf(clicks(10), faceDown(10), faceDown(19), faceDown(20)), xpMode)
		assertEquals(listOf(19), solver.clickOrder())
	}

	@Test
	fun `superpairs ignores the covers and the border`() {
		val solver = SuperpairsHandler()
		solver.update(
			listOf(
				clicks(26),
				cell(10, "minecraft:black_stained_glass_pane"),
				cell(11, "minecraft:black_stained_glass_pane"),
				cell(30, "minecraft:black_dye", name = "Cover"),
				cell(31, "minecraft:black_dye", name = "Cover"),
			),
			xpMode,
		)
		assertTrue(solver.clickOrder().isEmpty())
	}

	@Test
	fun `a card of an unexpected kind is still known`() {
		// A Chance IV book once sat face up without being counted, and the turn waited on it.
		val odd = { slot: Int -> cell(slot, "minecraft:bookshelf", count = 4, name = "") }
		val solver = SuperpairsHandler(board)
		solver.see(20, listOf(odd(10)))
		solver.clickedByHand(11)
		solver.see(19, listOf(odd(10), odd(11)), prompt = ExperimentRules.SECOND_CLICK_NAME)
		assertEquals(listOf(10), solver.clickOrder())
	}

	@Test
	fun `a book is told by its enchantment`() {
		assertEquals("Smite VII", ExperimentRules.cardReward(book(10, "Smite VII")))
		assertEquals("Grand Experience Bottle", ExperimentRules.cardReward(xp(10)))
		assertTrue(ExperimentRules.cardKey(book(10, "Smite VII")) != ExperimentRules.cardKey(book(11, "Smite VI")))
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
		assertEquals("Timer: 90s", ExperimentRules.stripFormatting("§c§lTimer: §f90s"))
		assertEquals("", ExperimentRules.stripFormatting("§a"))

		// The common case allocates nothing: the same instance comes back.
		val plain = "Remaining Clicks: 26"
		assertSame(plain, ExperimentRules.stripFormatting(plain))
	}

	@Test
	fun `the click budget is read off the bookshelf`() {
		assertEquals(26, ExperimentRules.clicksLeft(listOf(clicks(26))))
		assertEquals(70, ExperimentRules.clicksLeft(listOf(cell(ExperimentRules.CLICKS_SLOT, "minecraft:bookshelf", count = 64, name = "Remaining Clicks: 70"))))
		assertEquals(null, ExperimentRules.clicksLeft(listOf(book(10))))
	}
}

