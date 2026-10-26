package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Asks before the pause menu's Disconnect leaves the server, so a misclick doesn't cost a run. The
 * button is swapped for one that asks; Yes does what the original did.
 */
object ConfirmDisconnect {
	@JvmField
	val hypixelOnly = ToggleModuleSetting(
		id = "hypixel_only",
		label = "Hypixel only",
		defaultValue = true,
		description = "Only asks on Hypixel; elsewhere Disconnect leaves at once.",
	)

	@JvmField
	val module = Module(
		id = "confirm_disconnect",
		name = "Confirm Disconnect",
		description = "Asks before disconnecting",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(hypixelOnly),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
			if (screen is PauseScreen && applies(client)) swapButton(client, screen)
		}
	}

	private fun applies(client: Minecraft): Boolean {
		if (!module.enabled || client.isLocalServer) return false
		return !hypixelOnly.value || client.currentServer?.ip?.lowercase()?.contains("hypixel") == true
	}

	private fun swapButton(client: Minecraft, screen: PauseScreen) {
		val label = CommonComponents.disconnectButtonLabel(false)
		val widgets = Screens.getWidgets(screen)
		val index = widgets.indexOfFirst { it is Button && it.message == label }
		if (index < 0) return
		val original = widgets[index]
		widgets[index] = Button.builder(label) { ask(client, screen) }
			.bounds(original.x, original.y, original.width, original.height)
			.build()
	}

	private fun ask(client: Minecraft, pause: PauseScreen) {
		client.gui.setScreen(
			ConfirmScreen(
				{ yes ->
					if (!yes) {
						client.gui.setScreen(pause)
						return@ConfirmScreen
					}
					client.reportingContext.draftReportHandled(client, pause, { client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE) }, true)
				},
				Component.literal("Are you sure you want to disconnect?"),
				Component.empty(),
				Component.literal("Disconnect"),
				CommonComponents.GUI_CANCEL,
			),
		)
	}
}
