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
 * Times the terminals, and the sections they belong to.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). Three numbers
 * come out of a Goldor run and this says all three: how long each terminal took
 * on its own, how long the section it was part of has been running, and how
 * long the whole phase has. Personal bests are kept per terminal, and the
 * simulator's are kept apart from the real ones.
 *
 * The clock can run on real time or on server ticks. Ticks are the honest
 * measure of a laggy run — a section that took four seconds of server time took
 * four seconds however long the client spent waiting — and real time is what a
 * stopwatch would have said.
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
		description = "Times terminals, sections and phases",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(timesSection, announceTimes, splits, useRealTime, resetRecords),
	)

	private val completedPattern =
		Regex("""^(.{1,16}) (activated|completed) a (terminal|lever|device)! \((\d)/(\d)\)$""")
	private val gateDestroyed = "The gate has been destroyed!"
	private val goldorGreeting = Regex("""^\[BOSS] Goldor: Who dares trespass into my domain\?$""")
	private val coreOpening = "The Core entrance is opening!"

	/** Section progress as Hypixel counts it, so "7/7 and the gate went" is knowable. */
	private var completed = 0
	private var total = 7

	private val sectionTimes = mutableListOf<Float>()
	private var gateBlown = false
	private var sectionStart = 0L
	private var phaseStart = 0L

	/** Server time in milliseconds, counted a tick at a time. */
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

	/** Hypixel's per-tick ping is the only sight the client gets of its clock. */
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
		// The gate going before the count has caught up means the section is
		// over; so does the count going backwards, which is the next one.
		if ((currentCount == totalCount && gateBlown) || currentCount < completed) {
			resetSection()
		} else {
			completed = currentCount
			total = totalCount
		}
	}

	/**
	 * Adds the two clocks to a completion message.
	 *
	 * The message is rewritten rather than followed by a line of its own,
	 * because seven terminals, three devices and four levers is a lot of chat
	 * for something that belongs on the line that is already there.
	 */
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
	 * Says what the terminal that just closed took, against the best so far.
	 *
	 * Called by the chat line Hypixel prints, and by the simulator when one of
	 * its own terminals is finished — which has no chat line to print and is
	 * the whole reason the simulator can be practised against a clock at all.
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
