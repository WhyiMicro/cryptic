package imicro.cryptic.feature

import imicro.cryptic.experiment.ExperimentDebug
import imicro.cryptic.experiment.ExperimentRules
import imicro.cryptic.experiment.ExperimentRunner
import imicro.cryptic.experiment.ExperimentTracker
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.mixin.ContainerScreenAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput

/**
 * Solves the Experimentation Table's games, plays them, and can work the table
 * between them.
 *
 * The rules are in [imicro.cryptic.experiment.ExperimentRules] and the loop
 * between games is [ExperimentRunner]; this is the part the player sees. Each
 * slot still to click is painted and numbered over the real chest rather than
 * in place of it — unlike the terminal solver, the board here is worth looking
 * at, because what it shows is what has to be remembered.
 */
object ExperimentSolver {
	/** How wide a chest slot is, which is what gets painted. */
	private const val SLOT_SIZE = 16

	/** Indices into [focus]. */
	private const val FOCUS_XP = 0
	private const val FOCUS_ITEMS = 1

	@JvmField
	val nextColor = ColorModuleSetting(
		id = "next_color",
		label = "Next click",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x99,
	)

	@JvmField
	val laterColor = ColorModuleSetting(
		id = "later_color",
		label = "Later clicks",
		defaultRgb = 0x5555FF,
		supportsAlpha = true,
		defaultAlpha = 0x55,
	)

	@JvmField
	val showOrder = ToggleModuleSetting(
		id = "show_order",
		label = "Number the clicks",
		defaultValue = true,
		description = "Writes the click order over each slot, so the whole sequence reads at a glance.",
	)

	private val autoSection = SectionModuleSetting("auto_section", "Auto click")

	@JvmField
	val autoClick = ToggleModuleSetting(
		id = "auto_click",
		label = "Play automatically",
		description = "Clicks the sequence for you. Watch the highlighting for a game before trusting it.",
	)

	@JvmField
	val clickDelay = RangeModuleSetting(
		id = "click_delay",
		label = "Click delay",
		defaultLower = 180.0,
		defaultUpper = 320.0,
		min = 60.0,
		max = 800.0,
		step = 10.0,
		description = "Milliseconds between automatic clicks, drawn fresh each time.",
		visibleIf = { autoClick.value },
	)

	private val runSection = SectionModuleSetting("run_section", "Run the table")

	@JvmField
	val runTable = ToggleModuleSetting(
		id = "run_table",
		label = "Work the table",
		description = "Plays the table itself, start to finish.",
	)

	@JvmField
	val focus = DropdownModuleSetting(
		id = "focus",
		label = "Play for",
		options = listOf("Most XP", "Rare items"),
		description = "Most XP plays each game as deep as it goes, where the experience is.",
		visibleIf = { runTable.value },
	)

	@JvmField
	val renews = SliderModuleSetting(
		id = "renews",
		label = "Renews",
		defaultValue = 0.0,
		min = 0.0,
		max = 3.0,
		step = 1.0,
		description = "Renews to pay for. Three is the daily limit.",
		visibleIf = { runTable.value },
	)

	@JvmField
	val superpairsByHand = ToggleModuleSetting(
		id = "superpairs_by_hand",
		label = "Play Superpairs by hand",
		description = "Leaves Superpairs to you — it still remembers the board and paints the pairs.",
		visibleIf = { runTable.value || autoClick.value },
	)

	@JvmField
	val buyExperience = ToggleModuleSetting(
		id = "buy_experience",
		label = "Buy experience",
		description = "Buys the experience it needs from the bazaar.",
		visibleIf = { runTable.value },
	)

	@JvmField
	val serums = SliderModuleSetting(
		id = "serums",
		label = "Serums drunk",
		defaultValue = 0.0,
		min = 0.0,
		max = 3.0,
		step = 1.0,
		description = "Metaphysical Serums you have consumed.",
		visibleIf = { runTable.value },
	)

	@JvmField
	val guardianReminder = ToggleModuleSetting(
		id = "guardian_reminder",
		label = "Guardian reminder",
		defaultValue = false,
		description = "Closes the table and says so if the Guardian pet is not out.",
		visibleIf = { runTable.value },
	)

	@JvmField
	val module = Module(
		id = "experiment_solver",
		name = "Experiment Solver",
		description = "Solves and plays the Experimentation Table",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(nextColor, laterColor, showOrder) +
			listOf(autoSection, autoClick, clickDelay) +
			listOf(runSection, runTable, focus, renews, serums, superpairsByHand, buyExperience, guardianReminder),
	)

	/** True while the table should be played for books rather than for levels. */
	val huntingItems: Boolean get() = focus.selectedIndex == FOCUS_ITEMS

	private var nextClickAt = 0L

	/**
	 * Paints the slots still to click over the chest.
	 *
	 * Called at the tail of the screen's extract pass, so it lands on top of the
	 * chest rather than under it.
	 */
	@JvmStatic
	fun render(screen: Screen, context: GuiGraphicsExtractor) {
		if (!module.enabled) return
		if (screen !is AbstractContainerScreen<*>) return
		val handler = ExperimentTracker.current ?: return

		val order = handler.clickOrder()
		if (order.isEmpty()) return

		val accessor = screen as? ContainerScreenAccessor ?: return
		val left = accessor.`cryptic$leftPos`()
		val top = accessor.`cryptic$topPos`()
		val slots = screen.menu.slots
		val font = Minecraft.getInstance().font

		context.nextStratum()

		order.forEachIndexed { position: Int, slotIndex: Int ->
			val slot = slots.getOrNull(slotIndex) ?: return@forEachIndexed
			val x = left + slot.x
			val y = top + slot.y

			val color = if (position == 0) nextColor.argb else laterColor.argb
			if (color ushr 24 != 0) context.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, color)

			if (showOrder.value) {
				context.centeredText(
					font,
					(position + 1).toString(),
					x + SLOT_SIZE / 2,
					y + SLOT_SIZE / 2 - font.lineHeight / 2,
					0xFFFFFFFF.toInt(),
				)
			}
		}
	}

	/**
	 * Plays the next click, when it is time and the player asked for that.
	 *
	 * Paced the same way the terminal solver paces itself, and for the same
	 * reason: a game answered instantly is not one anybody played.
	 */
	fun tick(client: Minecraft) {
		ExperimentTracker.tick(client)
		ExperimentDebug.flush()
		ExperimentRunner.tick(client)
		if (!module.enabled || !autoClick.value) return

		val handler = ExperimentTracker.current ?: return
		if (handler.waiting) return
		// Superpairs can be left to the player without giving up the other two:
		// the pairs are still painted, they are just not clicked.
		if (superpairsByHand.value && handler.game == ExperimentRules.Game.SUPERPAIRS) return

		val now = System.currentTimeMillis()
		if (now < nextClickAt) return

		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
		val player = client.player ?: return
		val slot = handler.clickOrder().firstOrNull() ?: return
		if (slot !in screen.menu.slots.indices) return

		// Button zero with CLONE for all three, which is what Astrail sends.
		client.gameMode?.handleContainerInput(screen.menu.containerId, slot, 0, ContainerInput.CLONE, player)

		// The handler is told, rather than asked to notice: nothing on the board
		// says which of the sequence has been entered so far.
		handler.advance()
		nextClickAt = now + clickDelay.random().toLong()
	}
}
