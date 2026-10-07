package imicro.cryptic.feature

import imicro.cryptic.dungeon.BossTimings
import imicro.cryptic.dungeon.BossTimings.Timing
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
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
 *
 * How long each one runs is in [imicro.cryptic.dungeon.BossTimings], where it
 * can be changed with `/cryptic debug timing`: SkyBlock 0.27.2 sped the Floor 7
 * fight up, and the new numbers are still being measured.
 */
object SmartTickTimer {
	/** The gap between the prefix column and the number column, in pixels. */
	private const val COLUMN_GAP = 4

	private const val WHITE = 0xFFFFFFFF.toInt()

	/** How far into the Professor window the ability actually goes out. */
	private const val FIRE_FREEZE_CAST = 100

	private val fireFreezePattern =
		Regex("""^\[BOSS] The Professor: Oh? You found my Guardians. one weakness\?$""")

	private val goldorPattern = Regex("""^\[BOSS] Goldor: Who dares trespass into my domain\?$""")
	private val corePattern = Regex("""^The Core entrance is opening!$""")
	private val stormEndPattern = Regex("""^\[BOSS] Storm: I should have known that I stood no chance\.$""")
	private val stormStartPattern = Regex("""^\[BOSS] Storm: Pathetic Maxor, just like expected\.$""")
	private val stormPyPattern =
		Regex("""^\[BOSS] Storm: (?:ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$""")

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
		label = "Secret spawn timer",
		description = "Ticks until the next second, when a secret can register. Placed on its own.",
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
	val lightningTimer = ToggleModuleSetting(
		id = "lightning_timer",
		label = "Storm lightning timer",
		description = "Counts down to Storm calling the lightning.",
	)

	@JvmField
	val fireFreezeTimer = ToggleModuleSetting(
		id = "fire_freeze_timer",
		label = "Fire Freeze timer",
		description = "Counts down to using Fire Freeze on the Professor.",
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
				stormPadTimer, stormPyTimer, stormTickTimer, lightningTimer, fireFreezeTimer,
			),
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
	private var lightningTicks = -1
	private var fireFreezeTicks = -1

	/** Fired once per Storm phase: the call comes more than once, the window does not. */
	private var pyTriggered = false

	/** How long the PY count was when it started, for its colours. */
	private var pyMax = Timing.PY.ticks

	/** How long the Necron count was when it started, for its colours. */
	private var necronMax = Timing.NECRON.ticks

	/**
	 * Ticks left of the current second, for the secret spawn timer.
	 *
	 * Blade Addons' timer (CC0, BladeMasterGabe). Secrets register on Hypixel's
	 * second, and the one place that second is visible is the sidebar's "Time
	 * Elapsed" line, which is rewritten the moment it turns over — so that
	 * rewrite starts the count at twenty and each server tick takes one off.
	 * The version this replaces counted up from the start of the run instead,
	 * which drifts the moment the server skips a tick.
	 *
	 * -1 until the first rewrite is seen. Both counted down and reset from the
	 * network thread, in the order the packets arrive, which is what keeps the
	 * count in step with Odin's.
	 */
	@Volatile
	private var secretSpawnTicks = -1

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(TimerElement())
		Hud.register(SecretSpawnElement())
		// Chat is heard in [onSystemChat], from the packet: these lines are boss
		// dialogue, which other mods hide, and a hidden line never reaches
		// Fabric's chat event.
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
		lightningTicks = -1
		fireFreezeTicks = -1
		pyTriggered = false
		secretSpawnTicks = -1
	}

	/** A chat packet, on the client thread, before any mod has hidden it. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (!overlay) onMessage(message.string.replace(FORMATTING, ""))
	}

	private fun onMessage(line: String) {
		if (!module.enabled) return

		// The Necron drop, counted from Goldor's last words and set again on
		// every line between them and Necron's: started early so there is time
		// to read it, and finished on the line it is really timed from.
		BossTimings.NECRON_LEAD_IN[line]?.let { lead ->
			val ticks = lead + Timing.NECRON.ticks
			if (necronTicks < 0) necronMax = ticks
			necronTicks = ticks
			return
		}

		when {
			goldorPattern.matches(line) -> goldorCoreTicks = Timing.GOLDOR_CORE.ticks
			corePattern.matches(line) -> {
				goldorStartTicks = -1
				goldorCoreTicks = -1
			}
			stormEndPattern.matches(line) -> {
				goldorStartTicks = Timing.GOLDOR_START.ticks
				padTicks = -1
				stormTicks = -1
			}
			stormStartPattern.matches(line) -> {
				padTicks = 20
				lightningTicks = Timing.LIGHTNING.ticks
				stormTicks = 0
				pyTriggered = false
			}
			fireFreezePattern.matches(line) -> fireFreezeTicks = Timing.FIRE_FREEZE.ticks
			!pyTriggered && stormPyPattern.matches(line) -> {
				pyTriggered = true
				// From the call to the moment to lower the pillar, as Odin counts it.
				pyTicks = Timing.PY.ticks
				pyMax = pyTicks
			}
		}
	}

	/**
	 * A line of the sidebar changing, which is how the secret second is found.
	 *
	 * Called from the network thread with the line's two halves. Everything else
	 * the sidebar says is ignored.
	 */
	@JvmStatic
	fun onScoreboardLine(prefix: String, suffix: String) {
		if (!module.enabled || !secretsTimer.value || !DungeonLocation.inDungeon) return
		val line = (prefix + suffix).replace(FORMATTING, "")
		if (!line.contains("Time Elapsed:")) return
		secretSpawnTicks = SECOND_TICKS
	}

	/**
	 * Whether the secret spawn timer has anything to say.
	 *
	 * Only once a room has been opened, which is Odin's gate for a run having
	 * started: before that there is nothing to open and the count is noise.
	 */
	private fun secretSpawnShown(): Boolean =
		module.enabled && secretsTimer.value && secretSpawnTicks >= 0 &&
			DungeonLocation.inDungeon && DungeonStats.openedRooms > 0 && !DungeonRun.inBoss

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

		if (secretSpawnTicks > 0) secretSpawnTicks--
		if (goldorCoreTicks == 0 && goldorStartTicks <= 0) goldorCoreTicks = Timing.GOLDOR_CORE.ticks
		if (goldorStartTicks >= 0) goldorStartTicks--
		if (goldorCoreTicks >= 0) goldorCoreTicks--
		if (padTicks == 0) padTicks = 20
		if (padTicks >= 0) padTicks--
		if (pyTicks >= 0) pyTicks--
		if (necronTicks >= 0) necronTicks--
		if (lightningTicks >= 0) lightningTicks--
		if (fireFreezeTicks >= 0) fireFreezeTicks--
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
		"Secret spawn: $secretSpawnTicks, shown ${secretSpawnShown()}",
		"Rows switched on: ${enabledRows().size}, running: ${liveRows().size}",
	)

	/** Only ever read back by the debug command, to prove the ping is arriving. */
	private var serverTicks = 0L

	/** One line of the display: what to call it, where it is, and how far it runs. */
	private class Row(
		val prefix: String,
		val ticks: Int,
		val max: Int,
		/** Its own colours, where the thirds of [max] are not what matters. */
		val color: ((Int) -> String)? = null,
	)

	/**
	 * PY's colours: orange from 40 ticks and red from 20, because the pads
	 * move every 20 ticks and those are the last two moves before Storm's.
	 */
	private fun pyColor(ticks: Int): String = when {
		ticks > 40 -> "§a"
		ticks > 20 -> "§6"
		else -> "§c"
	}

	/**
	 * The timers switched on, in a fixed order, whether or not they are running.
	 *
	 * Used for sizing: the columns are as wide as the widest thing that could
	 * appear in them, so switching a timer on or off is the only thing that ever
	 * changes the element's size.
	 */
	private fun enabledRows(): List<Row> {
		val rows = mutableListOf<Row>()
		if (necronTimer.value) rows += Row("§4Necron:", necronTicks, necronMax)
		if (goldorStartTimer.value) rows += Row("§aStart:", goldorStartTicks, Timing.GOLDOR_START.ticks)
		if (goldorCoreTimer.value) rows += Row("§7Core:", goldorCoreTicks, Timing.GOLDOR_CORE.ticks)
		if (stormPadTimer.value) rows += Row("§bPad:", padTicks, 20)
		if (stormPyTimer.value) rows += Row("§bPY:", pyTicks, pyMax, ::pyColor)
		if (stormTickTimer.value) rows += Row("§bStorm:", stormTicks, STORM_MAX)
		if (lightningTimer.value) rows += Row("§bLightning:", lightningTicks, Timing.LIGHTNING.ticks)
		// Counted from the cast rather than from the message: the ability is
		// used a hundred ticks into the window, not at the start of it.
		if (fireFreezeTimer.value) rows += Row("§bFire Freeze:", fireFreezeTicks - FIRE_FREEZE_CAST, Timing.FIRE_FREEZE.ticks - FIRE_FREEZE_CAST)
		return rows
	}

	/** The rows with something to say, in the same order. */
	private fun liveRows(): List<Row> = enabledRows().filter { it.ticks >= 0 }

	/** Odin's ramp: green for most of the window, orange, then red at the end. */
	private fun colorFor(row: Row): String = row.color?.invoke(row.ticks) ?: when {
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
			draw(context, enabledRows().map { Row(it.prefix, it.max, it.max) })

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

	/**
	 * Blade Addons' secret spawn timer, on its own.
	 *
	 * A number and nothing else, centred in a small box so it can sit under the
	 * crosshair: green with most of the second left, gold past half, red at the
	 * end. Separate from the other timers because it is read differently — the
	 * others are glanced at, this one is watched while opening a secret.
	 */
	private class SecretSpawnElement: HudElement("secret_spawn_timer", "Secret Spawn Timer", 0.49, 0.53) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int = BOX_WIDTH
		override val height: Int = BOX_HEIGHT

		override fun isVisible(): Boolean = secretSpawnShown()

		override fun showInEditor(): Boolean = module.enabled && secretsTimer.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, secretSpawnTicks.coerceAtLeast(0))

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, SECOND_TICKS)

		private fun draw(context: GuiGraphicsExtractor, ticks: Int) {
			val text = ticks.toString()
			val color = when {
				ticks > 10 -> BLADE_GREEN
				ticks > 5 -> BLADE_GOLD
				else -> BLADE_RED
			}
			context.text(
				font,
				text,
				(BOX_WIDTH - font.width(text)) / 2,
				(BOX_HEIGHT - font.lineHeight) / 2 + 1,
				color,
			)
		}
	}

	/** How far the Storm count runs before it is red, which is Odin's, after the update. */
	private const val STORM_MAX = 540

	/** One of Hypixel's seconds, in server ticks. */
	private const val SECOND_TICKS = 20

	/** Blade's box, which is what makes the number sit centred where it is placed. */
	private const val BOX_WIDTH = 20
	private const val BOX_HEIGHT = 10

	/** Blade's three colours, which are Minecraft's own green, gold and red. */
	private const val BLADE_GREEN = 0xFF55FF55.toInt()
	private const val BLADE_GOLD = 0xFFFFAA00.toInt()
	private const val BLADE_RED = 0xFFFF5555.toInt()

	/** Hypixel's colour codes, which the sidebar is full of. */
	private val FORMATTING = Regex("§.")
}
