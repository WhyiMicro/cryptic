package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale

/**
 * The countdowns a dungeon runs on, all in one place on the HUD.
 *
 * Ported from Odin's `TickTimers` (BSD 3-Clause, Copyright (c) 2025 odtheking),
 * which is where every one of these numbers and the chat line that starts it
 * comes from. What is different here is the arrangement: Odin gives each timer
 * a HUD element of its own, and a player who wants four of them has four things
 * to place. These share one, and the prefix says which is speaking.
 *
 * The two columns are each right-aligned — the prefixes end where the numbers
 * begin, and the numbers end at the edge — so a digit appearing or disappearing
 * moves nothing. A timer whose value jitters sideways is one you have to read
 * twice, which for a three-second window is once too many.
 *
 * Counted in **server** ticks, not client frames: Hypixel sends a ping every
 * tick it gets through, and what these are timing is the server's clock rather
 * than the player's.
 */
object SmartTickTimer {
	/** The gap between the prefix column and the number column, in pixels. */
	private const val COLUMN_GAP = 4

	private const val WHITE = 0xFFFFFFFF.toInt()

	private val necronPattern = Regex("""^\[BOSS] Necron: I'm afraid, your journey ends now\.$""")
	private val goldorPattern = Regex("""^\[BOSS] Goldor: Who dares trespass into my domain\?$""")
	private val corePattern = Regex("""^The Core entrance is opening!$""")
	private val stormEndPattern = Regex("""^\[BOSS] Storm: I should have known that I stood no chance\.$""")
	private val stormStartPattern = Regex("""^\[BOSS] Storm: Pathetic Maxor, just like expected\.$""")
	private val stormPyPattern =
		Regex("""^\[BOSS] Storm: (?:ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$""")

	/**
	 * The lines that start a run, and with them the secret cycle.
	 *
	 * Odin matches either, and both are worth having: the first is missed by
	 * anyone who loads in a moment late, and the second is said to everybody a
	 * few seconds afterwards.
	 */
	private val mortPattern = Regex(
		"""^\[NPC] Mort: (?:Here, I found this map when I first entered the dungeon\.|""" +
			"""Right-click the Orb for spells, and Left-click \(or Drop\) to use your Ultimate!)$""",
	)

	private val displaySection = SectionModuleSetting("display_section", "Display")

	@JvmField
	val inTicks = ToggleModuleSetting(
		id = "in_ticks",
		label = "Show ticks",
		defaultValue = false,
		description = "Counts in server ticks rather than seconds.",
	)

	@JvmField
	val showUnit = ToggleModuleSetting(
		id = "show_unit",
		label = "Show unit",
		defaultValue = true,
		description = "Puts a t or an s after the number.",
	)

	@JvmField
	val showPrefix = ToggleModuleSetting(
		id = "show_prefix",
		label = "Show prefix",
		defaultValue = true,
		description = "Names which timer is speaking.",
	)

	private val generalSection = SectionModuleSetting("general_section", "General")

	@JvmField
	val secretsTimer = ToggleModuleSetting(
		id = "secrets_timer",
		label = "Secrets timer",
		description = "Counts down to when a secret can register.",
	)

	private val floor7Section = SectionModuleSetting("floor7_section", "F7/M7")

	@JvmField
	val necronTimer = ToggleModuleSetting(
		id = "necron_timer",
		label = "Necron drop timer",
		description = "Three seconds from Necron's last words until he drops down to you.",
	)

	@JvmField
	val goldorStartTimer = ToggleModuleSetting(
		id = "goldor_start_timer",
		label = "Goldor start timer",
		description = "From Storm dying until terminals and devices can actually be completed.",
	)

	@JvmField
	val goldorCoreTimer = ToggleModuleSetting(
		id = "goldor_core_timer",
		label = "Goldor core timer",
		description = "Goldor's repeating three-second cycle.",
	)

	@JvmField
	val stormPadTimer = ToggleModuleSetting(
		id = "storm_pad_timer",
		label = "Storm pad timer",
		description = "The one-second cycle the pads under Storm move on.",
	)

	@JvmField
	val stormPyTimer = ToggleModuleSetting(
		id = "storm_py_timer",
		label = "Storm PY timer",
		description = "From Storm calling the lightning until he can be crushed under the purple pillar.",
	)

	@JvmField
	val stormTickTimer = ToggleModuleSetting(
		id = "storm_tick_timer",
		label = "Storm tick timer",
		description = "Counts up from the start of the Storm phase, for parties timing the crush off it.",
	)

	private val configurable = listOf(
		inTicks, showUnit, showPrefix, secretsTimer, necronTimer, goldorStartTimer,
		goldorCoreTimer, stormPadTimer, stormPyTimer, stormTickTimer,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurable.forEach { it.reset() }
	})

	@JvmField
	val module = Module(
		id = "smart_tick_timer",
		name = "Smart Tick Timer",
		description = "Every run countdown in one element",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(displaySection, inTicks, showUnit, showPrefix) +
			listOf(generalSection, secretsTimer) +
			listOf(
				floor7Section, necronTimer, goldorStartTimer, goldorCoreTimer,
				stormPadTimer, stormPyTimer, stormTickTimer,
			) +
			listOf(reset),
	)

	/**
	 * Every timer's remaining ticks, or -1 for one that is not running.
	 *
	 * Odin's names and Odin's numbers. [stormTicks] is the odd one out and
	 * counts *up*, because what it measures is how far into the phase the fight
	 * has got rather than how long is left of anything.
	 */
	private var necronTicks = -1
	private var goldorStartTicks = -1
	private var goldorCoreTicks = -1
	private var padTicks = -1
	private var pyTicks = -1
	private var stormTicks = -1

	/** Fired once per Storm phase: the call comes more than once, the window does not. */
	private var pyTriggered = false

	/** Server ticks since the run started, which the secret cycle is aligned to. */
	private var secretCounter = 0

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(TimerElement())
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onMessage(message.string) }
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	fun forget() {
		necronTicks = -1
		goldorStartTicks = -1
		goldorCoreTicks = -1
		padTicks = -1
		pyTicks = -1
		stormTicks = -1
		pyTriggered = false
		secretCounter = 0
	}

	private fun onMessage(line: String) {
		if (!module.enabled) return

		when {
			mortPattern.matches(line) -> secretCounter = 0
			necronPattern.matches(line) -> necronTicks = 60
			goldorPattern.matches(line) -> goldorCoreTicks = 60
			corePattern.matches(line) -> {
				goldorStartTicks = -1
				goldorCoreTicks = -1
			}
			stormEndPattern.matches(line) -> {
				goldorStartTicks = 104
				padTicks = -1
				stormTicks = -1
			}
			stormStartPattern.matches(line) -> {
				padTicks = 20
				stormTicks = 0
			}
			!pyTriggered && stormPyPattern.matches(line) -> {
				pyTriggered = true
				pyTicks = 95
			}
		}
	}

	/**
	 * One tick of Hypixel's clock, counted off its per-tick ping.
	 *
	 * Nothing here is gated on being in a boss fight, which is what the first
	 * version got wrong: every one of these timers is started by a chat line and
	 * ended by one, so a timer nobody has started is already at -1 and counting
	 * costs nothing — while gating on the boss meant a run whose greeting was
	 * missed showed timers that sat still.
	 *
	 * The two repeating cycles — the Goldor core and Storm's pads — restart
	 * themselves at zero rather than stopping, because what they count is a
	 * cycle the server never leaves for the length of the phase.
	 */
	@JvmStatic
	fun onServerTick() {
		if (!module.enabled) return
		serverTicks++

		// The secret cycle is the one thing here that is aligned to the run
		// rather than started by a message, so it only counts inside one.
		if (DungeonLocation.inDungeon) secretCounter++

		if (goldorCoreTicks == 0 && goldorStartTicks <= 0) goldorCoreTicks = 60
		if (goldorStartTicks >= 0) goldorStartTicks--
		if (goldorCoreTicks >= 0) goldorCoreTicks--
		if (padTicks == 0) padTicks = 20
		if (padTicks >= 0) padTicks--
		if (pyTicks >= 0) pyTicks--
		if (necronTicks >= 0) necronTicks--
		if (stormTicks >= 0) stormTicks++
	}

	/**
	 * Every timer's raw state, for `/cryptic debug timers`.
	 *
	 * A timer that is not moving has three possible reasons — its chat line
	 * never arrived, the server's ping is not reaching the counter, or the run
	 * is not being recognised as a run — and none of them can be told apart from
	 * looking at the HUD.
	 */
	fun describe(): List<String> = listOf(
		"Server ticks counted: $serverTicks (last ${ServerTicks.sinceLastTick ?: "never"}ms ago)",
		"In a dungeon: ${DungeonLocation.inDungeon}, floor ${DungeonLocation.floor}, " +
			"started ${DungeonRun.started}, opened rooms ${DungeonStats.openedRooms}, boss ${DungeonRun.inBoss}",
		"Necron $necronTicks, Start $goldorStartTicks, Core $goldorCoreTicks",
		"Pad $padTicks, PY $pyTicks (fired $pyTriggered), Storm $stormTicks",
		"Secret counter $secretCounter, showing ${secretTicks()}",
		"Rows switched on: ${enabledRows().size}, running: ${liveRows().size}",
	)

	/** Only ever read back by the debug command, to prove the ping is arriving. */
	private var serverTicks = 0L

	/** One line of the display: what to call it, where it is, and how far it runs. */
	private class Row(
		val prefix: String,
		val ticks: Int,
		val max: Int,
		/** Set where the fraction left is the wrong thing to colour by. */
		val color: String? = null,
	)

	/**
	 * The timers switched on, in a fixed order, whether or not they are running.
	 *
	 * Used for sizing: the columns are as wide as the widest thing that could
	 * appear in them, so switching a timer on or off is the only thing that ever
	 * changes the element's size.
	 */
	private fun enabledRows(): List<Row> {
		val rows = mutableListOf<Row>()
		if (necronTimer.value) rows += Row("§4Necron:", necronTicks, 60)
		if (goldorStartTimer.value) rows += Row("§aStart:", goldorStartTicks, 100)
		if (goldorCoreTimer.value) rows += Row("§7Core:", goldorCoreTicks, 60)
		if (stormPadTimer.value) rows += Row("§bPad:", padTicks, 20)
		if (stormPyTimer.value) rows += Row("§bPY:", pyTicks, 95)
		if (stormTickTimer.value) rows += Row("§bStorm:", stormTicks, 620)
		if (secretsTimer.value) rows += Row("§7Secret:", secretTicks(), 20, secretColor())
		return rows
	}

	/** The rows with something to say, in the same order. */
	private fun liveRows(): List<Row> = enabledRows().filter { it.ticks >= 0 }

	/**
	 * Ticks until the next second boundary, or -1 outside a run.
	 *
	 * Not a countdown to anything the server announces: secrets register on the
	 * second, so this is the wait between opening one and it counting.
	 *
	 * Shown once the tab list counts an opened room, which is Odin's gate and
	 * the right one. Mort's greeting is missed by anyone still loading, and
	 * merely being in the dungeon starts the timer during the countdown before
	 * the doors open — when there is nothing to open. The first room opening is
	 * the run starting, and the tab list says so for the whole of it.
	 */
	private fun secretTicks(): Int =
		if (!DungeonLocation.inDungeon || DungeonStats.openedRooms == 0 || DungeonRun.inBoss) {
			-1
		} else {
			20 - secretCounter % 20
		}

	/**
	 * Green when the window is close rather than when it is far.
	 *
	 * The other timers count down to something worth waiting for, so more time
	 * left is better; this one counts down to a thing about to happen, and the
	 * fraction-left ramp would have it backwards.
	 */
	private fun secretColor(): String = when (secretTicks()) {
		in 0..4 -> "§a"
		in 5..9 -> "§6"
		else -> "§c"
	}

	/** Odin's ramp: green for most of the window, orange, then red at the end. */
	private fun colorFor(row: Row): String = row.color ?: when {
		row.ticks >= row.max * 0.66f -> "§a"
		row.ticks >= row.max * 0.33f -> "§6"
		else -> "§c"
	}

	private fun valueText(ticks: Int): String {
		val unit = if (!showUnit.value) "" else if (inTicks.value) "t" else "s"
		if (inTicks.value) return "$ticks$unit"
		return String.format(Locale.ROOT, "%.2f", ticks / 20f) + unit
	}

	private class TimerElement: HudElement("smart_tick_timer", "Smart Tick Timer", 0.02, 0.32) {
		private val font get() = Minecraft.getInstance().font

		/** The prefix column, as wide as the longest name switched on. */
		private fun prefixWidth(rows: List<Row>): Int {
			if (!showPrefix.value) return 0
			val widest = rows.maxOfOrNull { font.width(it.prefix) } ?: 0
			return if (widest == 0) 0 else widest + COLUMN_GAP
		}

		/** The number column, as wide as each timer's own longest reading. */
		private fun valueWidth(rows: List<Row>): Int =
			rows.maxOfOrNull { font.width(valueText(it.max)) } ?: 0

		override val width: Int
			get() = enabledRows().let { prefixWidth(it) + valueWidth(it) }

		override val height: Int
			get() = font.lineHeight * maxOf(1, enabledRows().size)

		override fun isVisible(): Boolean = module.enabled && liveRows().isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled && enabledRows().isNotEmpty()

		override fun render(context: GuiGraphicsExtractor) = draw(context, liveRows())

		/**
		 * Everything switched on, holding still at its longest reading.
		 *
		 * The editor is for arranging the element, and an element that is empty
		 * outside a boss fight is one that cannot be arranged before the run.
		 */
		override fun renderExample(context: GuiGraphicsExtractor) =
			draw(context, enabledRows().map { Row(it.prefix, it.max, it.max, it.color) })

		private fun draw(context: GuiGraphicsExtractor, rows: List<Row>) {
			if (rows.isEmpty()) return

			// Sized from everything switched on rather than from what is running,
			// so one timer starting does not move the ones already there.
			val columns = enabledRows()
			val prefixRight = prefixWidth(columns) - COLUMN_GAP
			val right = prefixWidth(columns) + valueWidth(columns)

			var y = 0
			for (row in rows) {
				if (showPrefix.value) {
					context.text(font, row.prefix, prefixRight - font.width(row.prefix), y, WHITE)
				}
				val value = colorFor(row) + valueText(row.ticks)
				context.text(font, value, right - font.width(value), y, WHITE)
				y += font.lineHeight
			}
		}
	}
}
