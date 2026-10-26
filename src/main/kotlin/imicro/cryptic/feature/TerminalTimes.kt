package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.terminal.TerminalRecords
import imicro.cryptic.terminal.Terminals
import imicro.cryptic.terminal.sim.TermSimScreen
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Times terminals, their sections and the whole Goldor phase, with personal bests per terminal
 * (simulator ones kept apart). Can run on real time or server ticks, which ignore lag. Ported from
 * Odin.
 */
object TerminalTimes {
	private val timesSection = SectionModuleSetting(id = "times_section", label = "Times")

	@JvmField
	val announceTimes = ToggleModuleSetting(
		id = "announce_times",
		label = "Terminal times",
		defaultValue = true,
		description = "Says how long each terminal took, and whether it beat your best.",
	)

	@JvmField
	val splits = ToggleModuleSetting(
		id = "splits",
		label = "Terminal splits",
		defaultValue = true,
		description = "Adds section and phase clocks to each message.",
	)

	@JvmField
	val useRealTime = ToggleModuleSetting(
		id = "use_real_time",
		label = "Use real time",
		defaultValue = true,
		description = "Times in real seconds.",
	)

	@JvmField
	val resetRecords = ButtonModuleSetting(
		id = "reset_records",
		label = "Reset PBs",
		action = {
			TerminalRecords.reset(simulated = false)
			say("§6Terminal PBs §fhave been reset.")
		},
	)

	@JvmField
	val module = Module(
		id = "terminal_times",
		name = "Terminal Times",
		description = "Times terminals and phases",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf(timesSection, announceTimes, splits, useRealTime, resetRecords),
	)

	private val completedPattern =
		Regex("""^(.{1,16}) (activated|completed) a (terminal|lever|device)! \((\d)/(\d)\)$""")
	private val gateDestroyed = "The gate has been destroyed!"
	private val goldorGreeting = Regex("""^\[BOSS] Goldor: Who dares trespass into my domain\?$""")
	private val coreOpening = "The Core entrance is opening!"

	private var completed = 0
	private var total = 7

	private val sectionTimes = mutableListOf<Float>()
	private var gateBlown = false
	private var sectionStart = 0L
	private var phaseStart = 0L

	private var serverClock = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay) onMessage(message.string)
		}
		ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay ->
			if (overlay) message else decorate(message)
		}
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetSection(full = true) }
	}

	fun onServerTick() {
		serverClock += MILLIS_PER_TICK
	}

	private fun onMessage(line: String) {
		if (!module.enabled) return

		when {
			line == gateDestroyed -> if (completed >= total) resetSection() else gateBlown = true
			goldorGreeting.matches(line) -> resetSection(full = true)
			line == coreOpening -> {
				resetSection()
				if (splits.value) {
					say(
						"§bTimes: §a" + sectionTimes.joinToString(" §8| ") { "§a${format(it)}s" } +
							"§8, §bTotal: §a${format(elapsed(phaseStart))}s",
					)
				}
			}
		}

		val match = completedPattern.find(line) ?: return
		val (name, _, type, current, of) = match.destructured
		if (type == "terminal" && name == Minecraft.getInstance().player?.name?.string) onSolved()

		val currentCount = current.toIntOrNull() ?: return
		val totalCount = of.toIntOrNull() ?: return
		// The gate going before the count catches up ends the section; so does the count going
		// backwards (the next section).
		if ((currentCount == totalCount && gateBlown) || currentCount < completed) {
			resetSection()
		} else {
			completed = currentCount
			total = totalCount
		}
	}

	/** Adds the two clocks to the completion message rather than adding a line. */
	private fun decorate(message: Component): Component {
		if (!module.enabled || !splits.value) return message
		val match = completedPattern.find(message.string) ?: return message
		val (name, action, type, current, of) = match.destructured

		return Component.literal(
			"§6$name §a$action a $type! (§c$current§a/$of) " +
				"§8(§7${format(elapsed(sectionStart))}s §8| §7${format(elapsed(phaseStart))}s§8)",
		)
	}

	/**
	 * Reports the closed terminal's time against the best. Called from Hypixel's chat line, and by
	 * the simulator, which has none.
	 */
	fun onSolved() {
		if (!module.enabled || !announceTimes.value) return
		val terminal = Terminals.lastOpened ?: return
		val simulated = Minecraft.getInstance().gui.screen() is TermSimScreen
		val seconds = (System.currentTimeMillis() - terminal.openedAt) / 1000f
		val name = terminal.type.termName

		val previous = TerminalRecords.record(name, seconds, simulated)
		val against = when {
			previous == null -> "§7(§d§lNew PB§r§7)"
			previous > seconds -> "§7(§d§lNew PB§r§7) Old PB was §8${format(previous)}s"
			else -> "§8(§7${format(previous)}s§8)"
		}

		say("§a$name${if (simulated) " §7(termsim)" else ""} §7solved in §6${format(seconds)}s§7! $against")
	}

	private fun resetSection(full: Boolean = false) {
		if (full) {
			sectionTimes.clear()
			phaseStart = now()
		} else {
			sectionTimes.add(elapsed(sectionStart))
		}
		completed = 0
		total = 7
		sectionStart = now()
		gateBlown = false
	}

	private fun now(): Long = if (useRealTime.value) System.currentTimeMillis() else serverClock

	private fun elapsed(since: Long): Float = ((now() - since).coerceAtLeast(0L)) / 1000f

	private fun format(seconds: Float): String = String.format(Locale.ROOT, "%.2f", seconds)

	private fun say(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §f$text"))
	}

	private const val MILLIS_PER_TICK = 50L
}
