package imicro.cryptic.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which chest is a terminal, told from its title alone.
 *
 * This is the first thing that has to be right: a title that is not recognised
 * means no solver at all, and a title recognised too eagerly means the solver
 * painted over somebody's auction house. Both have happened, and both are
 * decided entirely by these six patterns — which is the part of the solver that
 * can be tested without a chest, a server or a running game.
 */
class TerminalTypeTest {
	@Test
	fun `each terminal is known by its own title`() {
		assertEquals(TerminalType.PANES, TerminalType.of("Correct all the panes!"))
		assertEquals(TerminalType.RUBIX, TerminalType.of("Change all to same color!"))
		assertEquals(TerminalType.NUMBERS, TerminalType.of("Click in order!"))
		assertEquals(TerminalType.MELODY, TerminalType.of("Click the button on time!"))
		assertEquals(TerminalType.STARTS_WITH, TerminalType.of("What starts with: 'S'?"))
		assertEquals(TerminalType.SELECT, TerminalType.of("Select all the RED items!"))
	}

	@Test
	fun `a two word colour is still a select terminal`() {
		assertEquals(TerminalType.SELECT, TerminalType.of("Select all the LIGHT GRAY items!"))
	}

	@Test
	fun `anything else is not a terminal`() {
		assertNull(TerminalType.of("Large Chest"))
		assertNull(TerminalType.of("Auction House"))
		// Close enough to matter: the real one ends in an exclamation mark.
		assertNull(TerminalType.of("Click in order"))
	}

	@Test
	fun `the puzzle is the window without its bottom row`() {
		// What every solver is handed, and what the rubix colour lock counts on
		// being able to reach: its last pane is slot 32.
		assertEquals(36, TerminalType.PANES.windowSize - 9)
		assertEquals(36, TerminalType.RUBIX.windowSize - 9)
		assertEquals(45, TerminalType.SELECT.windowSize - 9)
	}
}
