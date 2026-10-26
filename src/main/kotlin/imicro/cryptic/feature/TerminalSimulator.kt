package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.terminal.TerminalRecords
import imicro.cryptic.terminal.sim.StartSim
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The Floor 7 terminals, playable outside a dungeon. Ported from Odin's Terminal Simulator. No
 * on-off switch: the button opens the same terminals the fight does, and [TerminalSolver] draws
 * over them as in the boss room.
 */
object TerminalSimulator {
	@JvmField
	val ping = SliderModuleSetting(
		id = "ping",
		label = "Ping",
		unit = "ms",
		defaultValue = 0.0,
		min = 0.0,
		max = 500.0,
		step = 50.0,
		description = "Answers clicks this slowly, as a server would.",
	)

	@JvmField
	val skipClickProtection = ToggleModuleSetting(
		id = "skip_click_protection",
		label = "Skip click protection",
		defaultValue = false,
		description = "Skips click protection here. A real terminal still has it.",
	)

	@JvmField
	val openSimulator = ButtonModuleSetting(
		id = "open",
		label = "Open simulator",
		action = { open() },
	)

	/** The simulator's own bests (Terminal Times keeps real ones). */
	@JvmField
	val resetRecords = ButtonModuleSetting(
		id = "reset_records",
		label = "Reset PBs",
		action = {
			TerminalRecords.reset(simulated = true)
			Toasts.show("Terminal Simulator", "Practice PBs cleared §8(in-run PBs kept)", color = Toasts.Colors.CRYPTIC)
		},
	)

	@JvmField
	val module = Module(
		id = "terminal_simulator",
		name = "Terminal Simulator",
		description = "Practise the F7 terminals",
		category = ModuleCategory.FLOOR_7,
		supportsToggle = false,
		supportsKeybind = false,
		settings = listOf(ping, skipClickProtection, openSimulator, resetRecords),
		cardAction = { open() },
	)

	/** Opens the start menu, or says why it can't (the simulated chest needs a player). */
	fun open() {
		val client = Minecraft.getInstance()
		if (client.player == null) {
			client.gui.hud.chat.addClientSystemMessage(
				Component.literal("§8[Cryptic] §fJoin a world before opening the terminal simulator."),
			)
			return
		}
		client.execute { StartSim().open(ping.value.toLong()) }
	}
}
