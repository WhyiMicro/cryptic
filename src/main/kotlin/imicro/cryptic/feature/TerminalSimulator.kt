package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.terminal.sim.StartSim
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The Floor 7 terminals, playable outside a dungeon.
 *
 * Ported from Odin's Terminal Simulator (BSD 3-Clause, Copyright (c) 2025
 * odtheking). It is a practice range rather than a module that does anything
 * during a run, so it has no on-off switch: nothing happens until the button is
 * pressed, and pressing it opens the same terminals the real fight opens, which
 * [TerminalSolver] then draws over exactly as it would in the boss room.
 */
object TerminalSimulator {
	@JvmField
	val ping = SliderModuleSetting(
		id = "ping",
		label = "Ping (ms)",
		defaultValue = 0.0,
		min = 0.0,
		max = 500.0,
		step = 50.0,
		description = "Delays every click by a round trip, so the terminals answer as slowly as the server would.",
	)

	@JvmField
	val skipClickProtection = ToggleModuleSetting(
		id = "skip_click_protection",
		label = "Skip click protection",
		defaultValue = false,
		description = "Ignores the solver's first click protection while practising. It still applies in a real dungeon.",
	)

	@JvmField
	val openSimulator = ButtonModuleSetting(
		id = "open",
		label = "Open simulator",
		action = { open() },
	)

	@JvmField
	val module = Module(
		id = "terminal_simulator",
		name = "Terminal Simulator",
		description = "Practise the Floor 7 terminals anywhere",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsToggle = false,
		supportsKeybind = false,
		settings = listOf(ping, skipClickProtection, openSimulator),
	)

	/**
	 * Opens the start menu, or says why it cannot.
	 *
	 * The simulated chest is built around an inventory of the player's, so
	 * there has to be a player: pressing the button at the title screen is
	 * otherwise a crash rather than a refusal.
	 */
	fun open() {
		val client = Minecraft.getInstance()
		if (client.player == null) {
			client.gui.chat.addClientSystemMessage(
				Component.literal("§8[Cryptic] §fJoin a world before opening the terminal simulator."),
			)
			return
		}
		client.execute { StartSim().open(ping.value.toLong()) }
	}
}
