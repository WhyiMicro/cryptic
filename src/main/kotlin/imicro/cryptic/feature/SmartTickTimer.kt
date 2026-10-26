package imicro.cryptic.feature

import imicro.cryptic.dungeon.BossTimings.Timing
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.gui.DropdownModuleSetting
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
 * Dungeon timers on the HUD. The Floor 7 fight is one line that follows the fight: from Storm's
 * first line it counts up, turning orange then red as the crush under the purple pillar nears; when
 * Storm dies it counts down to terminals opening, then counts up again in Goldor's death-tick colours
 * until the core opens. Every number and starting chat line is from Odin's `TickTimers`.
 *
 * Counted in server ticks, from Hypixel's per-tick ping. Durations live in
 * [imicro.cryptic.dungeon.BossTimings] and can be changed with `/cryptic debug timing`.
 */
object SmartTickTimer {
	private const val WHITE = 0xFFFFFFFF.toInt()

	private val goldorPattern = Regex("""^\[BOSS] Goldor: Who dares trespass into my domain\?$""")
	private val corePattern = Regex("""^The Core entrance is opening!$""")
	private val stormEndPattern = Regex("""^\[BOSS] Storm: I should have known that I stood no chance\.$""")
	private val stormStartPattern = Regex("""^\[BOSS] Storm: Pathetic Maxor, just like expected\.$""")
	private val stormPyPattern =
		Regex("""^\[BOSS] Storm: (?:ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$""")

	private val displaySection = SectionModuleSetting("display_section", "Display")

	private const val IN_TICKS = 1

	@JvmField
	val countIn = DropdownModuleSetting(
		id = "count_in",
		label = "Count in",
		options = listOf("Seconds", "Ticks"),
		defaultIndex = 0,
		description = "Seconds, as 3.20, or server ticks, as 64.",
	)

	@JvmField
	val showSuffix = ToggleModuleSetting(
		id = "show_unit",
		label = "Show suffix",
		defaultValue = true,
		description = "An s or a t after the number.",
	)

	@JvmField
	val showPrefix = ToggleModuleSetting(
		id = "show_prefix",
		label = "Show prefix",
		defaultValue = true,
		description = "Names the timer, as \"Storm:\". Off, the number sits centred.",
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
	val bossTimer = ToggleModuleSetting(
		id = "boss_timer",
		label = "Boss timer",
		defaultValue = true,
		description = "One line through Storm and Goldor: the time into Storm, coloured toward the crush, then the countdown to terminals and Goldor's death ticks until the core opens.",
	)

	@JvmField
	val module = Module(
		id = "smart_tick_timer",
		name = "Smart Tick Timer",
		description = "Run and boss timers",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(
			displaySection, countIn, showSuffix, showPrefix,
			generalSection, secretsTimer,
			floor7Section, bossTimer,
		),
	)

	/**
	 * Each count's ticks, or -1 when not running. [stormTicks] and [goldorTicks] count up;
	 * the others count down.
	 */
	private var stormTicks = -1
	private var pyTicks = -1
	private var goldorStartTicks = -1
	private var goldorTicks = -1
	private var goldorCoreTicks = -1

	/** Fired once per Storm phase; the call repeats, the window doesn't. */
	private var pyTriggered = false

	/**
	 * Ticks left in the current second, for the secret spawn timer (Blade Addons'). Secrets
	 * register on Hypixel's second, visible as the sidebar's "Time Elapsed" line being rewritten,
	 * so each rewrite starts the count at twenty and each server tick takes one off.
	 *
	 * -1 until the first rewrite. Counted down and reset on the network thread, in packet order,
	 * which keeps it in step with Odin's.
	 */
	@Volatile
	private var secretSpawnTicks = -1

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(TimerElement())
		Hud.register(SecretSpawnElement())
		// Chat is read in [onSystemChat] from the packet, since boss dialogue is often hidden by
		// other mods.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	fun forget() {
		stormTicks = -1
		pyTicks = -1
		goldorStartTicks = -1
		goldorTicks = -1
		goldorCoreTicks = -1
		pyTriggered = false
		secretSpawnTicks = -1
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (!overlay) onMessage(message.string.replace(FORMATTING, ""))
	}

	private fun onMessage(line: String) {
		if (!module.enabled) return

		when {
			stormStartPattern.matches(line) -> {
				stormTicks = 0
				pyTriggered = false
			}
			!pyTriggered && stormPyPattern.matches(line) -> {
				pyTriggered = true
				// From the call to the moment to lower the pillar, as Odin counts it.
				pyTicks = Timing.PY.ticks
			}
			stormEndPattern.matches(line) -> {
				stormTicks = -1
				pyTicks = -1
				goldorStartTicks = Timing.GOLDOR_START.ticks
			}
			goldorPattern.matches(line) -> goldorCoreTicks = Timing.GOLDOR_CORE.ticks
			corePattern.matches(line) -> {
				goldorStartTicks = -1
				goldorTicks = -1
				goldorCoreTicks = -1
			}
		}
	}

	/**
	 * A sidebar line changing, which finds the secret second. Called on the network thread with the
	 * line's two halves.
	 */
	@JvmStatic
	fun onScoreboardLine(prefix: String, suffix: String) {
		if (!module.enabled || !secretsTimer.value || !DungeonLocation.inDungeon) return
		val line = (prefix + suffix).replace(FORMATTING, "")
		if (!line.contains("Time Elapsed:")) return
		secretSpawnTicks = SECOND_TICKS
	}

	/** Only once a room has been opened (Odin's sign the run started). */
	private fun secretSpawnShown(): Boolean =
		module.enabled && secretsTimer.value && secretSpawnTicks >= 0 &&
			DungeonLocation.inDungeon && DungeonStats.openedRooms > 0 && !DungeonRun.inBoss

	/**
	 * One tick of Hypixel's clock. Not gated on being in a boss fight: every count is started and
	 * ended by chat, so an unstarted one is already -1. Goldor's death-tick cycle restarts at zero,
	 * since it runs all phase.
	 */
	@JvmStatic
	fun onServerTick() {
		if (!module.enabled) return
		serverTicks++

		if (secretSpawnTicks > 0) secretSpawnTicks--
		if (stormTicks >= 0) stormTicks++
		if (pyTicks >= 0) pyTicks--
		if (goldorCoreTicks == 0 && goldorStartTicks <= 0) goldorCoreTicks = Timing.GOLDOR_CORE.ticks
		if (goldorCoreTicks >= 0) goldorCoreTicks--
		if (goldorStartTicks > 0) {
			goldorStartTicks--
			// Terminals open: the count turns round and goes up.
			if (goldorStartTicks == 0) {
				goldorStartTicks = -1
				goldorTicks = 0
			}
		} else if (goldorTicks >= 0) {
			goldorTicks++
		}
	}

	/**
	 * Every count's raw state, for `/cryptic debug timers`, to tell apart a missing chat line, a
	 * missing ping, and an unrecognised run.
	 */
	fun describe(): List<String> = listOf(
		"Server ticks counted: $serverTicks (last ${ServerTicks.sinceLastTick ?: "never"}ms ago)",
		"In a dungeon: ${DungeonLocation.inDungeon}, floor ${DungeonLocation.floor}, " +
			"started ${DungeonRun.started}, opened rooms ${DungeonStats.openedRooms}, boss ${DungeonRun.inBoss}",
		"Storm $stormTicks, PY $pyTicks (fired $pyTriggered)",
		"Goldor start $goldorStartTicks, since $goldorTicks, death tick $goldorCoreTicks",
		F3Features.describe(),
		"Secret spawn: $secretSpawnTicks, shown ${secretSpawnShown()}",
	)

	/** Only read by the debug command, to prove pings arrive. */
	private var serverTicks = 0L

	/** One display line: its name, its ticks, and its colour. */
	private class Row(val prefix: String, val ticks: Int, val color: String)

	/** Green, orange from 40 ticks before the crush, red from 20, then green again. */
	private fun stormColor(): String = when {
		pyTicks in 21..40 -> "§6"
		pyTicks in 0..20 -> "§c"
		else -> "§a"
	}

	/** Thirds of Goldor's death-tick cycle: green, orange, red. */
	private fun deathTickColor(): String {
		val cycle = Timing.GOLDOR_CORE.ticks
		return when {
			goldorCoreTicks >= cycle * 0.66f -> "§a"
			goldorCoreTicks >= cycle * 0.33f -> "§6"
			else -> "§c"
		}
	}

	private fun bossRow(): Row? {
		if (!bossTimer.value) return null
		return when {
			stormTicks >= 0 -> Row("§bStorm:", stormTicks, stormColor())
			goldorStartTicks > 0 -> Row("§eStart:", goldorStartTicks, "§e")
			goldorTicks >= 0 -> Row("§7Core:", goldorTicks, deathTickColor())
			else -> null
		}
	}

	private fun liveRows(): List<Row> = listOfNotNull(bossRow())

	/** The lines the element is sized for and shows in the editor: every timer switched on. */
	private fun sampleRows(): List<Row> = buildList {
		if (bossTimer.value) add(Row("§bStorm:", SAMPLE_TICKS, "§a"))
	}

	private fun valueText(ticks: Int): String {
		val inTicks = countIn.selectedIndex == IN_TICKS
		val suffix = if (!showSuffix.value) "" else if (inTicks) "t" else "s"
		if (inTicks) return "$ticks$suffix"
		return String.format(Locale.ROOT, "%.2f", ticks / 20f) + suffix
	}

	/**
	 * With prefixes, each name ends on the same colon and its number starts just after it, so the
	 * colon never moves as names and numbers change length. Without, each number is centred.
	 */
	private class TimerElement: HudElement("smart_tick_timer", "Smart Tick Timer", 0.02, 0.32) {
		private val font get() = Minecraft.getInstance().font

		private val gap get() = font.width(" ")

		private fun prefixWidth(rows: List<Row>): Int =
			if (!showPrefix.value) 0 else rows.maxOfOrNull { font.width(it.prefix) } ?: 0

		private fun valueWidth(rows: List<Row>): Int = rows.maxOfOrNull { font.width(valueText(it.ticks)) } ?: 0

		override val width: Int
			get() = sampleRows().let { rows ->
				if (showPrefix.value) prefixWidth(rows) + gap + valueWidth(rows) else valueWidth(rows)
			}.coerceAtLeast(1)

		override val height: Int
			get() = font.lineHeight * maxOf(1, sampleRows().size)

		override fun isVisible(): Boolean = module.enabled && liveRows().isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled && sampleRows().isNotEmpty()

		override fun render(context: GuiGraphicsExtractor) = draw(context, liveRows())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, sampleRows())

		private fun draw(context: GuiGraphicsExtractor, rows: List<Row>) {
			// The colon's place comes from every timer switched on, so it stays put.
			val colon = prefixWidth(sampleRows())
			var y = 0
			for (row in rows) {
				val value = row.color + valueText(row.ticks)
				if (showPrefix.value) {
					context.text(font, row.prefix, colon - font.width(row.prefix), y, WHITE)
					context.text(font, value, colon + gap, y, WHITE)
				} else {
					context.text(font, value, (width - font.width(value)) / 2, y, WHITE)
				}
				y += font.lineHeight
			}
		}
	}

	/**
	 * Blade Addons' secret spawn timer on its own: a number centred in a small box to sit under the
	 * crosshair, green, then gold past half, then red. Separate because it's watched while opening
	 * a secret rather than glanced at.
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

	/** A reading as wide as the longest Storm phase, for sizing and the editor. */
	private const val SAMPLE_TICKS = 999

	private const val SECOND_TICKS = 20

	/** Blade's box width, which centres the number where it's placed. */
	private const val BOX_WIDTH = 20
	private const val BOX_HEIGHT = 10

	/** Blade's three colours: Minecraft's green, gold and red. */
	private const val BLADE_GREEN = 0xFF55FF55.toInt()
	private const val BLADE_GOLD = 0xFFFFAA00.toInt()
	private const val BLADE_RED = 0xFFFF5555.toInt()

	private val FORMATTING = Regex("§.")
}
