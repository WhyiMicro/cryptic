package imicro.cryptic.feature

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.puzzle.BeamsSolver
import imicro.cryptic.puzzle.BlazeSolver
import imicro.cryptic.puzzle.BoulderSolver
import imicro.cryptic.puzzle.IceFillSolver
import imicro.cryptic.puzzle.PuzzleRecords
import imicro.cryptic.puzzle.PuzzleRender
import imicro.cryptic.puzzle.PuzzleRooms
import imicro.cryptic.puzzle.QuizSolver
import imicro.cryptic.puzzle.SilverfishSolver
import imicro.cryptic.puzzle.TicTacToeSolver
import imicro.cryptic.puzzle.TpMazeSolver
import imicro.cryptic.puzzle.WaterPreview
import imicro.cryptic.puzzle.WaterSolver
import imicro.cryptic.puzzle.WeirdosSolver
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.Locale

/**
 * Every Catacombs puzzle, answered.
 *
 * Ported from Odin's Puzzle Solvers (BSD 3-Clause, Copyright (c) 2025
 * odtheking) — Water Board, Teleport Maze, Ice Fill, Blaze, Creeper Beams,
 * Three Weirdos, Quiz and Boulder — with the two it does not have taken from
 * Skyblocker (LGPL-3.0): Tic Tac Toe and the silverfish's ice path. The water
 * board's two previews, the path the water will take and what a lever would
 * change, are Skyblocker's as well.
 *
 * Every solver works in the coordinates of its room's schematic, which is what
 * makes one answer fit every copy of the room ever built; `PuzzleRooms` turns
 * those into the world. None of them touches the game: each draws what it
 * knows and leaves the clicking to you.
 */
object PuzzleSolver {
	// ---- Water Board -----------------------------------------------------

	private val waterSection = SectionModuleSetting("water_section", "Water Board", startsCollapsed = true)

	@JvmField
	val waterEnabled = ToggleModuleSetting(
		id = "water",
		label = "Toggle",
		defaultValue = true,
		description = "Says which lever to pull, and counts down the timed ones.",
	)

	@JvmField
	val waterOptimized = ToggleModuleSetting(
		id = "water_optimized",
		label = "Optimized route",
		description = "Faster solutions that leave less room for a late pull.",
		visibleIf = { waterEnabled.value },
	)

	@JvmField
	val waterMinGap = SliderModuleSetting(
		id = "water_min_gap",
		label = "Minimum gap",
		defaultValue = 1.0,
		min = 0.0,
		max = 3.0,
		step = 0.1,
		description = "How close two timed pulls may be before the safe route is used instead.",
		visibleIf = { waterEnabled.value && waterOptimized.value },
	)

	@JvmField
	val waterTracer = ToggleModuleSetting(
		id = "water_tracer",
		label = "Lever tracer",
		defaultValue = true,
		description = "A line to the lever that is next.",
		visibleIf = { waterEnabled.value },
	)

	@JvmField
	val waterTracerFirst = ColorModuleSetting(
		id = "water_tracer_first",
		label = "Next lever",
		defaultRgb = 0x55FF55,
		visibleIf = { waterEnabled.value && waterTracer.value },
	)

	@JvmField
	val waterTracerSecond = ColorModuleSetting(
		id = "water_tracer_second",
		label = "The one after",
		defaultRgb = 0xFFAA00,
		visibleIf = { waterEnabled.value && waterTracer.value },
	)

	@JvmField
	val waterLeverHighlight = ToggleModuleSetting(
		id = "water_lever_highlight",
		label = "Highlight the lever",
		defaultValue = true,
		description = "Marks the next lever, and the one after it, in the tracer colors.",
		visibleIf = { waterEnabled.value },
	)

	@JvmField
	val waterLeverStyle = DropdownModuleSetting(
		id = "water_lever_style",
		label = "Lever style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_BOTH,
		visibleIf = { waterEnabled.value && waterLeverHighlight.value },
	)

	@JvmField
	val waterPreviewPath = ToggleModuleSetting(
		id = "water_preview_path",
		label = "Preview the water",
		description = "Traces where the water will run on the board as it stands.",
	)

	@JvmField
	val waterPreviewLevers = ToggleModuleSetting(
		id = "water_preview_levers",
		label = "Preview a lever",
		description = "Look at a block to see what its lever would move, and the path it would make.",
	)

	/**
	 * Solves the board again from where it stands.
	 *
	 * One button, because the solve reads every gate against where it started:
	 * a board left half played and one put back by hand come out the same way,
	 * with the pulls already made taken off and any made wrong put back on.
	 */
	@JvmField
	val waterReset = ButtonModuleSetting(
		"water_reset",
		"Reset Water Board",
		action = { WaterSolver.reset() },
		visibleIf = { waterEnabled.value },
	)

	// ---- Teleport Maze ---------------------------------------------------

	private val mazeSection = SectionModuleSetting("maze_section", "Teleport Maze", startsCollapsed = true)

	@JvmField
	val mazeEnabled = ToggleModuleSetting(
		id = "maze",
		label = "Toggle",
		defaultValue = true,
		description = "Marks the pads you have used and the ones still worth taking.",
	)

	@JvmField
	val mazeColorOne = ColorModuleSetting(
		id = "maze_color_one",
		label = "Correct",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { mazeEnabled.value },
	)

	@JvmField
	val mazeColorMultiple = ColorModuleSetting(
		id = "maze_color_multiple",
		label = "Not visited",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { mazeEnabled.value },
	)

	@JvmField
	val mazeColorVisited = ColorModuleSetting(
		id = "maze_color_visited",
		label = "Wrong",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { mazeEnabled.value },
	)

	@JvmField
	val mazeStyle = DropdownModuleSetting(
		id = "maze_style",
		label = "Style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_FILLED,
		visibleIf = { mazeEnabled.value },
	)

	@JvmField
	val mazeTracer = ToggleModuleSetting(
		id = "maze_tracer",
		label = "Pad tracer",
		defaultValue = true,
		description = "A line to the best pad to take next.",
		visibleIf = { mazeEnabled.value },
	)

	@JvmField
	val mazeTracerColor = ColorModuleSetting(
		id = "maze_tracer_color",
		label = "Tracer",
		defaultRgb = 0x55FFFF,
		visibleIf = { mazeEnabled.value && mazeTracer.value },
	)

	@JvmField
	val mazeReset = ButtonModuleSetting(
		"maze_reset",
		"Reset Teleport Maze",
		action = { TpMazeSolver.reset() },
		visibleIf = { mazeEnabled.value },
	)

	// ---- Ice Fill --------------------------------------------------------

	private val iceSection = SectionModuleSetting("ice_section", "Ice Fill", startsCollapsed = true)

	@JvmField
	val iceFillEnabled = ToggleModuleSetting(
		id = "ice_fill",
		label = "Toggle",
		defaultValue = true,
		description = "Draws the line to walk on each of the three floors.",
	)

	@JvmField
	val iceFillColor = ColorModuleSetting(
		id = "ice_fill_color",
		label = "Route",
		defaultRgb = 0xFF55FF,
		visibleIf = { iceFillEnabled.value },
	)

	@JvmField
	val iceFillOptimized = ToggleModuleSetting(
		id = "ice_fill_optimized",
		label = "Optimized route",
		description = "Shorter routes that are easier to fall off.",
		visibleIf = { iceFillEnabled.value },
	)

	@JvmField
	val iceFillReset = ButtonModuleSetting(
		"ice_reset",
		"Reset Ice Fill",
		action = { IceFillSolver.reset() },
		visibleIf = { iceFillEnabled.value },
	)

	// ---- Blaze -----------------------------------------------------------

	private val blazeSection = SectionModuleSetting("blaze_section", "Blaze", startsCollapsed = true)

	@JvmField
	val blazeEnabled = ToggleModuleSetting(
		id = "blaze",
		label = "Toggle",
		defaultValue = true,
		description = "Boxes the blazes in the order they have to die.",
	)

	@JvmField
	val blazeNextLine = ToggleModuleSetting(
		id = "blaze_next_line",
		label = "Line to the next",
		defaultValue = true,
		description = "Joins each blaze to the one after it.",
		visibleIf = { blazeEnabled.value },
	)

	@JvmField
	val blazeLineCount = SliderModuleSetting(
		id = "blaze_line_count",
		label = "Lines shown",
		defaultValue = 1.0,
		min = 1.0,
		max = 10.0,
		step = 1.0,
		visibleIf = { blazeEnabled.value && blazeNextLine.value },
	)

	@JvmField
	val blazeLineWidth = SliderModuleSetting(
		id = "blaze_line_width",
		label = "Line width",
		defaultValue = 2.0,
		min = 0.5,
		max = 5.0,
		step = 0.5,
		visibleIf = { blazeEnabled.value },
	)

	@JvmField
	val blazeStyle = DropdownModuleSetting(
		id = "blaze_style",
		label = "Style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_BOTH,
		visibleIf = { blazeEnabled.value },
	)

	@JvmField
	val blazeFirstColor = ColorModuleSetting(
		id = "blaze_first",
		label = "First",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0xC0,
		visibleIf = { blazeEnabled.value },
	)

	@JvmField
	val blazeSecondColor = ColorModuleSetting(
		id = "blaze_second",
		label = "Second",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0xC0,
		visibleIf = { blazeEnabled.value },
	)

	@JvmField
	val blazeThirdColor = ColorModuleSetting(
		id = "blaze_third",
		label = "Third",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0xC0,
		visibleIf = { blazeEnabled.value },
	)

	@JvmField
	val blazeOtherColor = ColorModuleSetting(
		id = "blaze_other",
		label = "The rest",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = 0x4C,
		visibleIf = { blazeEnabled.value },
	)

	@JvmField
	val blazeSendComplete = ToggleModuleSetting(
		id = "blaze_send_complete",
		label = "Tell the party",
		description = "Sends a message when the last blaze goes down.",
		visibleIf = { blazeEnabled.value },
	)

	// ---- Creeper Beams ---------------------------------------------------

	private val beamsSection = SectionModuleSetting("beams_section", "Creeper Beams", startsCollapsed = true)

	@JvmField
	val beamsEnabled = ToggleModuleSetting(
		id = "beams",
		label = "Toggle",
		defaultValue = true,
		description = "Pairs up the lanterns each beam runs between.",
	)

	@JvmField
	val beamsStyle = DropdownModuleSetting(
		id = "beams_style",
		label = "Style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_BOTH,
		visibleIf = { beamsEnabled.value },
	)

	@JvmField
	val beamsPhase = ToggleModuleSetting(
		id = "beams_phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the boxes through the walls the lanterns are set into.",
		visibleIf = { beamsEnabled.value },
	)

	@JvmField
	val beamsTracer = ToggleModuleSetting(
		id = "beams_tracer",
		label = "Join the pairs",
		description = "Draws the line between each pair as well as boxing them.",
		visibleIf = { beamsEnabled.value },
	)

	@JvmField
	val beamsAlpha = SliderModuleSetting(
		id = "beams_alpha",
		label = "Opacity",
		defaultValue = 70.0,
		min = 5.0,
		max = 100.0,
		step = 5.0,
		visibleIf = { beamsEnabled.value },
	)

	// ---- Three Weirdos ---------------------------------------------------

	private val weirdosSection = SectionModuleSetting("weirdos_section", "Three Weirdos", startsCollapsed = true)

	@JvmField
	val weirdosEnabled = ToggleModuleSetting(
		id = "weirdos",
		label = "Toggle",
		defaultValue = true,
		description = "Marks the chest the reward is in as soon as one of them gives it away.",
	)

	@JvmField
	val weirdosColor = ColorModuleSetting(
		id = "weirdos_color",
		label = "Right chest",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0xB2,
		visibleIf = { weirdosEnabled.value },
	)

	@JvmField
	val weirdosWrongColor = ColorModuleSetting(
		id = "weirdos_wrong_color",
		label = "Wrong chest",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0xB2,
		visibleIf = { weirdosEnabled.value },
	)

	@JvmField
	val weirdosHideWrong = ToggleModuleSetting(
		id = "weirdos_hide_wrong",
		label = "Hide the wrong chests",
		description = "Stops drawing them once the right one is known, instead of marking them.",
		visibleIf = { weirdosEnabled.value },
	)

	@JvmField
	val weirdosStyle = DropdownModuleSetting(
		id = "weirdos_style",
		label = "Style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_BOTH,
		visibleIf = { weirdosEnabled.value },
	)

	@JvmField
	val weirdosBlockWrong = ToggleModuleSetting(
		id = "weirdos_block_wrong",
		label = "Block the wrong chests",
		description = "Swallows a click on a chest that is known to be wrong, which is how the puzzle is failed.",
		visibleIf = { weirdosEnabled.value },
	)

	// ---- Quiz ------------------------------------------------------------

	private val quizSection = SectionModuleSetting("quiz_section", "Quiz", startsCollapsed = true)

	@JvmField
	val quizEnabled = ToggleModuleSetting(
		id = "quiz",
		label = "Toggle",
		defaultValue = true,
		description = "Marks the right answer as Oruo reads the options out.",
	)

	@JvmField
	val quizColor = ColorModuleSetting(
		id = "quiz_color",
		label = "Answer",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0xC0,
		visibleIf = { quizEnabled.value },
	)

	@JvmField
	val quizStyle = DropdownModuleSetting(
		id = "quiz_style",
		label = "Style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_BOTH,
		visibleIf = { quizEnabled.value },
	)

	@JvmField
	val quizPhase = ToggleModuleSetting(
		id = "quiz_phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the answer through whatever is in front of it.",
		visibleIf = { quizEnabled.value },
	)

	@JvmField
	val quizTimer = ToggleModuleSetting(
		id = "quiz_timer",
		label = "Answer timer",
		defaultValue = true,
		description = "Counts down the time left to answer, on the HUD.",
		visibleIf = { quizEnabled.value },
	)

	// ---- Boulder ---------------------------------------------------------

	private val boulderSection = SectionModuleSetting("boulder_section", "Boulder", startsCollapsed = true)

	@JvmField
	val boulderEnabled = ToggleModuleSetting(
		id = "boulder",
		label = "Toggle",
		defaultValue = true,
		description = "Boxes the boulder to push next.",
	)

	@JvmField
	val boulderAll = ToggleModuleSetting(
		id = "boulder_all",
		label = "Show every push",
		defaultValue = true,
		description = "Marks the whole solution rather than only the next push.",
		visibleIf = { boulderEnabled.value },
	)

	@JvmField
	val boulderPhase = ToggleModuleSetting(
		id = "boulder_phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the signs through the boulders standing in front of them.",
		visibleIf = { boulderEnabled.value },
	)

	@JvmField
	val boulderStyle = DropdownModuleSetting(
		id = "boulder_style",
		label = "Style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_OUTLINE,
		visibleIf = { boulderEnabled.value },
	)

	@JvmField
	val boulderColor = ColorModuleSetting(
		id = "boulder_color",
		label = "Boulder",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { boulderEnabled.value },
	)

	// ---- Tic Tac Toe -----------------------------------------------------

	private val ticTacToeSection = SectionModuleSetting("tic_tac_toe_section", "Tic Tac Toe", startsCollapsed = true)

	@JvmField
	val ticTacToeEnabled = ToggleModuleSetting(
		id = "tic_tac_toe",
		label = "Toggle",
		defaultValue = true,
		description = "Marks the square to shoot next, on your turn.",
	)

	@JvmField
	val ticTacToeColor = ColorModuleSetting(
		id = "tic_tac_toe_color",
		label = "Next move",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { ticTacToeEnabled.value },
	)

	@JvmField
	val ticTacToeBlockWrong = ToggleModuleSetting(
		id = "tic_tac_toe_block_wrong",
		label = "Block the wrong squares",
		description = "Swallows a click on any square but the ones that hold the draw. Crouch to override.",
		visibleIf = { ticTacToeEnabled.value },
	)

	@JvmField
	val ticTacToeStyle = DropdownModuleSetting(
		id = "tic_tac_toe_style",
		label = "Style",
		options = PuzzleRender.styles,
		defaultIndex = PuzzleRender.STYLE_BOTH,
		visibleIf = { ticTacToeEnabled.value },
	)

	// ---- Ice Path --------------------------------------------------------

	private val silverfishSection = SectionModuleSetting("silverfish_section", "Ice Path", startsCollapsed = true)

	@JvmField
	val silverfishEnabled = ToggleModuleSetting(
		id = "silverfish",
		label = "Toggle",
		defaultValue = true,
		description = "Draws the silverfish's shortest way to the exit.",
	)

	@JvmField
	val silverfishColor = ColorModuleSetting(
		id = "silverfish_color",
		label = "Route",
		defaultRgb = 0xFF5555,
		visibleIf = { silverfishEnabled.value },
	)

	// ---- All of them -----------------------------------------------------

	private val generalSection = SectionModuleSetting("general_section", "All puzzles")

	@JvmField
	val puzzleTimers = ToggleModuleSetting(
		id = "puzzle_timers",
		label = "Time each puzzle",
		defaultValue = true,
		description = "Says how long a puzzle took, and keeps your best.",
	)

	@JvmField
	val draftPrompt = ToggleModuleSetting(
		id = "draft_prompt",
		label = "Draft prompt",
		defaultValue = true,
		description = "Offers a click to fetch an architect's draft when a puzzle fails.",
	)

	// In the order a person would look for them, which is alphabetical by the
	// name of the puzzle rather than the order they were written in.
	private val configurable: List<imicro.cryptic.gui.ModuleSetting> = listOf(
		blazeSection, blazeEnabled, blazeNextLine, blazeLineCount, blazeLineWidth, blazeStyle,
		blazeFirstColor, blazeSecondColor, blazeThirdColor, blazeOtherColor, blazeSendComplete,
		boulderSection, boulderEnabled, boulderAll, boulderStyle, boulderPhase, boulderColor,
		beamsSection, beamsEnabled, beamsStyle, beamsPhase, beamsTracer, beamsAlpha,
		iceSection, iceFillEnabled, iceFillColor, iceFillOptimized, iceFillReset,
		silverfishSection, silverfishEnabled, silverfishColor,
		quizSection, quizEnabled, quizColor, quizStyle, quizPhase, quizTimer,
		mazeSection, mazeEnabled, mazeColorOne, mazeColorMultiple, mazeColorVisited,
		mazeStyle, mazeTracer, mazeTracerColor, mazeReset,
		weirdosSection, weirdosEnabled, weirdosColor, weirdosWrongColor, weirdosStyle,
		weirdosHideWrong, weirdosBlockWrong,
		ticTacToeSection, ticTacToeEnabled, ticTacToeColor, ticTacToeStyle, ticTacToeBlockWrong,
		waterSection, waterEnabled, waterOptimized, waterMinGap, waterTracer, waterTracerFirst,
		waterTracerSecond, waterLeverHighlight, waterLeverStyle, waterPreviewPath, waterPreviewLevers,
		waterReset,
		generalSection, puzzleTimers, draftPrompt,
	)

	private val reset = ButtonModuleSetting("reset", "Reset all", action = {
		configurable.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "puzzle_solver",
		name = "Puzzle Solver",
		description = "Answers every Catacombs puzzle",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurable + reset,
	)

	/** When each puzzle was walked into, and whether its time has been said. */
	private class Attempt(val enteredAt: Long = System.currentTimeMillis(), var reported: Boolean = false)

	private val attempts = HashMap<String, Attempt>()

	/** The room the solvers were last told about, so entering is noticed once. */
	private var lastRoom: DungeonRoom? = null

	/** True once this room's solvers have been told about it. */
	private var entered = false

	private var ticks = 0

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(QuizTimerElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay) onMessage(message.string)
		}
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		attempts.clear()
		lastRoom = null
		entered = false
		WaterSolver.forget()
		TpMazeSolver.reset()
		IceFillSolver.reset()
		BlazeSolver.reset()
		BeamsSolver.reset()
		WeirdosSolver.reset()
		QuizSolver.reset()
		BoulderSolver.reset()
		TicTacToeSolver.reset()
		SilverfishSolver.reset()
	}

	fun tick(client: Minecraft) {
		if (!module.enabled) return
		if (!PuzzleRooms.clearing) {
			if (lastRoom != null) {
				lastRoom = null
				entered = false
			}
			return
		}

		val room = PuzzleRooms.room()
		if (room !== lastRoom) {
			lastRoom = room
			entered = false
		}

		// A room is told to its solvers once, but not until its turn has been
		// worked out: every position they hold is written in the room's own
		// coordinates, and a room walked into before its chunks have settled
		// has no turn yet. Waiting for one is why the quiz and the teleport
		// maze had nothing to draw - both read their positions on the way in
		// and never asked again.
		if (room != null && !entered) {
			val level = client.level
			val resolved = level != null && room.resolveRotation(level)
			if (resolved || room.rotationUnavailable) {
				entered = true
				onRoomEnter(room)
			}
		}

		ticks++
		// The two that read the world rather than a packet, on Odin's quarter
		// second: both are a scan of a room, and neither changes faster. Each
		// checks which room it is in for itself, so nothing is asked here about
		// what kind of room this is - a puzzle whose type the map has not
		// settled on yet is still the puzzle you are standing in.
		if (ticks % SCAN_INTERVAL == 0) {
			if (blazeEnabled.value) BlazeSolver.scan()
			if (waterEnabled.value) WaterSolver.scan()
		}

		if (ticTacToeEnabled.value) TicTacToeSolver.tick()
		if (silverfishEnabled.value) SilverfishSolver.tick()
	}

	private fun onRoomEnter(room: DungeonRoom) {
		BoulderSolver.onRoomEnter(room)
		IceFillSolver.onRoomEnter(room)
		TpMazeSolver.onRoomEnter(room)
		BeamsSolver.onRoomEnter(room)
		WeirdosSolver.onRoomEnter(room)
		QuizSolver.onRoomEnter(room)

		val name = room.data?.name ?: return
		if (puzzleTimers.value && room.type == DungeonRoom.Type.PUZZLE && name !in attempts) {
			attempts[name] = Attempt()
		}
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !PuzzleRooms.clearing) return

		IceFillSolver.render(context)
		WeirdosSolver.render(context)
		BoulderSolver.render(context)
		BlazeSolver.render(context)
		BeamsSolver.render(context)
		WaterSolver.render(context)
		WaterPreview.render(context)
		QuizSolver.render(context)
		TpMazeSolver.render(context)
		TicTacToeSolver.render(context)
		SilverfishSolver.render(context)
	}

	// ---- What the world tells us -----------------------------------------

	@JvmStatic
	fun onServerTick() {
		if (!module.enabled) return
		WaterSolver.onServerTick()
		QuizSolver.onServerTick()
	}

	/** A block being reached for: a lever pulled, or a boulder pushed. */
	@JvmStatic
	fun onBlockUsed(pos: BlockPos) {
		if (!module.enabled || !PuzzleRooms.clearing) return
		if (waterEnabled.value) {
			WaterSolver.onLeverUsed(pos)
			WaterSolver.onChestUsed(pos)
		}
		if (boulderEnabled.value) BoulderSolver.onBlockUsed(pos)
	}

	/**
	 * Whether a click should be swallowed rather than sent.
	 *
	 * The only thing here that touches input, and it only ever takes a click
	 * away: opening the wrong chest in Three Weirdos is how that puzzle is
	 * failed, and the three stand close enough together to catch anybody.
	 */
	@JvmStatic
	fun blocksClick(pos: BlockPos): Boolean {
		if (!module.enabled || !PuzzleRooms.clearing) return false
		// Crouching overrides every one of these, the way it does for the
		// devices: a solver that has lost track must never be able to stop a
		// puzzle being played by hand.
		if (Minecraft.getInstance().player?.isShiftKeyDown == true) return false
		return WeirdosSolver.blocksClick(pos) || TicTacToeSolver.blocksClick(pos)
	}

	@JvmStatic
	fun onTeleport(packet: ClientboundPlayerPositionPacket) {
		if (!module.enabled || !mazeEnabled.value) return
		// Packet handlers run twice: once on the network thread, which throws
		// itself out and reschedules, and once on the client thread. Only the
		// second is a real arrival, and only on it is the player's position
		// still the one they are about to be moved from.
		if (!Minecraft.getInstance().isSameThread) return
		TpMazeSolver.onTeleport(packet)
	}

	/** A block changing, which for the beams means one has been broken. */
	@JvmStatic
	fun onBlockUpdate(pos: BlockPos, old: BlockState, updated: BlockState) {
		if (!module.enabled || !beamsEnabled.value) return
		val wasLantern = old.block == Blocks.SEA_LANTERN || old.block == Blocks.PRISMARINE
		val isLantern = updated.block == Blocks.SEA_LANTERN || updated.block == Blocks.PRISMARINE
		BeamsSolver.onBlockChanged(pos, wasLantern, isLantern)
	}

	/**
	 * The animation Hypixel plays on a puzzle's own block when it is finished.
	 *
	 * Odin's trick, and the only reliable news that a puzzle is done: each of
	 * the five has a block that is knocked when it completes, and where that
	 * block is says which puzzle it was.
	 */
	@JvmStatic
	fun onBlockEvent(pos: BlockPos, block: Block) {
		if (!module.enabled || !puzzleTimers.value) return
		if (block != Blocks.CHERRY_LOG || !PuzzleRooms.inPuzzle) return
		val room = PuzzleRooms.room() ?: return
		val name = room.data?.name ?: return

		val completed = when (name) {
			"Three Weirdos" -> pos in listOfNotNull(
				room.getRealCoords(BlockPos(18, 69, 24)),
				room.getRealCoords(BlockPos(16, 69, 25)),
				room.getRealCoords(BlockPos(14, 69, 24)),
			)
			"Ice Fill" -> pos in listOfNotNull(
				room.getRealCoords(BlockPos(14, 75, 29)),
				room.getRealCoords(BlockPos(16, 75, 29)),
			)
			"Teleport Maze" -> pos == room.getRealCoords(BlockPos(15, 70, 20))
			"Water Board" -> pos == room.getRealCoords(BlockPos(15, 56, 22))
			"Boulder" -> pos == room.getRealCoords(BlockPos(15, 66, 29))
			else -> false
		}
		if (completed) onPuzzleComplete(name)
	}

	private fun onMessage(line: String) {
		if (!module.enabled || !PuzzleRooms.clearing) return

		if (draftPrompt.value && PuzzleRooms.inPuzzle && failedPattern.containsMatchIn(line)) {
			Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
				Component.literal("§8[Cryptic] §7Click §ehere §7to fetch an architect's draft").withStyle(
					Style.EMPTY
						.withClickEvent(ClickEvent.RunCommand("gfs architect's first draft 1"))
						.withHoverEvent(HoverEvent.ShowText(Component.literal("Click to fetch the architect's draft"))),
				),
			)
		}

		if (weirdosEnabled.value) {
			// Stripped before it is split. Hypixel colours the speaker's name,
			// and a name carrying a colour code matches no armour stand in the
			// room — so the line is read as plain text from here on.
			val plain = line.replace(FORMATTING, "")
			npcPattern.find(plain)?.destructured?.let { (npc, message) ->
				WeirdosSolver.onNpcMessage(npc.trim(), message)
			}
		}
		if (quizEnabled.value) QuizSolver.onMessage(line)
	}

	/**
	 * Says how long a puzzle took, once per puzzle per run.
	 *
	 * A time only counts when the room was walked into before it was solved —
	 * arriving at a puzzle somebody else has already done would otherwise be
	 * reported as an extremely good one.
	 */
	fun onPuzzleComplete(name: String) {
		val attempt = attempts[name] ?: return
		if (attempt.reported) return
		attempt.reported = true

		val seconds = (System.currentTimeMillis() - attempt.enteredAt) / 1000f
		val best = PuzzleRecords.record(name, seconds)
		val time = String.format(Locale.ROOT, "%.2f", seconds)

		val message = if (best == null || seconds < best) {
			val previous = best?.let { " §7(was §6${String.format(Locale.ROOT, "%.2f", it)}s§7)" } ?: ""
			"§a$name §7solved in §6${time}s§7 — new best!$previous"
		} else {
			"§a$name §7solved in §6${time}s§7 (best §6${String.format(Locale.ROOT, "%.2f", best)}s§7)"
		}

		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] $message"))
	}

	/**
	 * What the solvers think they are standing in, for `/cryptic debug room`.
	 *
	 * Every answer here is in the room's own coordinates, turned into the
	 * world by the marker buried at one corner of the roof. When a solver draws
	 * in the wrong place this is the line that says why: the wrong room, no
	 * marker found, or a turn that does not match what you can see.
	 */
	fun describeRoom(): List<String> {
		val room = PuzzleRooms.room() ?: return listOf("Not in a room Cryptic has placed.")
		val level = Minecraft.getInstance().level
		if (level != null) room.resolveRotation(level)

		val lines = mutableListOf(
			"Room: ${room.data?.name ?: "unnamed"} (${room.type}, ${room.shape})",
			"Tiles: ${room.tiles.joinToString(" ") { "${it.x},${it.z}" }}",
			"Rotation: ${room.rotation?.name ?: "not found"}",
			"Corner: ${room.clayPos?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "-"}",
		)

		val player = Minecraft.getInstance().player
		if (player != null) {
			val relative = room.getRelativeCoords(player.blockPosition())
			lines += "You are at: ${relative?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "unknown"} in the room"
		}

		// The one solver whose aim cannot be seen when it misses.
		if (room.data?.name == "Water Board") lines += WaterSolver.describe() + WaterPreview.describe()
		if (room.data?.name == "Teleport Maze") lines += TpMazeSolver.describe()
		if (room.data?.name == "Three Weirdos") lines += WeirdosSolver.describe()
		return lines
	}

	private val npcPattern = Regex("""\[NPC] ([^:]+): (.+)""")

	/** Hypixel's colour codes, written into the text of a line rather than its style. */
	private val FORMATTING = Regex("§.")

	private val failedPattern = Regex(
		"""^PUZZLE FAIL! (\w{1,16}) .+$|""" +
			"""^\[STATUE] Oruo the Omniscient: (\w{1,16}) chose the wrong answer! """ +
			"""I shall never forget this moment of misrememberance\.$""",
	)

	/** Four passes a second, which is Odin's rate for both room scans. */
	private const val SCAN_INTERVAL = 5

	/**
	 * The time left to answer Oruo, which exists nowhere on screen.
	 *
	 * Green while there is plenty, gold with a third gone and red at the end,
	 * which is Odin's colouring.
	 */
	private class QuizTimerElement : HudElement("quiz_timer", "Quiz Timer", 0.45, 0.55) {
		private val font get() = Minecraft.getInstance().font

		private fun text(): String {
			val (left, length, stage) = QuizSolver.timer
			val head = "§5Quiz §8(§f$stage§8/§f3§8): "

			// While the wait for the next question is running, the wait. Once
			// the question is up, the letter that answers it.
			// Between questions the wait; during one the letter that answers
			// it; and while Oruo is still reading it out, nothing to say yet.
			if (left <= 0) return head + "§a" + (QuizSolver.correctLetter() ?: "§7...")

			val color = when {
				left >= length * 0.66 -> "§a"
				left >= length * 0.33 -> "§6"
				else -> "§c"
			}
			return head + color + String.format(Locale.ROOT, "%.1f", left / 20f) + "s"
		}

		override val width: Int get() = font.width(EXAMPLE)
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && quizEnabled.value && quizTimer.value && QuizSolver.running

		override fun showInEditor(): Boolean = module.enabled && quizTimer.value

		override fun render(context: GuiGraphicsExtractor) {
			context.text(font, text(), 0, 0, 0xFFFFFFFF.toInt())
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			context.text(font, EXAMPLE, 0, 0, 0xFFFFFFFF.toInt())
		}

		private companion object {
			const val EXAMPLE = "§5Quiz §8(§f1§8/§f3§8): §a11.0s"
		}
	}
}
