package imicro.cryptic.debug

import imicro.cryptic.Cryptic
import imicro.cryptic.terminal.Terminals
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Fixes an inventory that can't be clicked. The game tracks the screen shown and the menu the
 * player holds separately; when they disagree, every click is silently dropped. Only the one
 * unambiguous case is repaired: your own inventory shown while another menu is held.
 */
object InventoryWatch {
	private const val HISTORY = 8
	private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

	private val history = ArrayDeque<String>()

	private var repairs = 0

	private var lastHeld = 0

	fun note(event: String) {
		history.addLast("${LocalTime.now().format(CLOCK)} $event")
		while (history.size > HISTORY) history.removeFirst()
	}

	fun tick(client: Minecraft) {
		val player = client.player ?: return
		val held = player.containerMenu

		if (held.containerId != lastHeld) {
			lastHeld = held.containerId
			note("holding menu ${held.containerId}")
		}

		if (held === player.inventoryMenu) return
		val screen = client.gui.screen() as? InventoryScreen ?: return
		if (screen.menu !== player.inventoryMenu) return

		repairs++
		val stale = held.containerId
		note("repaired: inventory open while holding menu $stale")
		Cryptic.LOGGER.warn(
			"[Cryptic/inventory] The inventory was open while the player still held menu {}, so no click " +
				"in it could land. Put right. Recent: {}",
			stale,
			history.joinToString(" | "),
		)

		// Tell the server too, in case it still holds the old menu.
		player.connection.send(ServerboundContainerClosePacket(stale))
		player.containerMenu = player.inventoryMenu
		lastHeld = player.inventoryMenu.containerId
	}

	/** For `/cryptic debug inventory`. */
	fun describe(): List<String> {
		val client = Minecraft.getInstance()
		val player = client.player ?: return listOf("§8[Cryptic] §7No player.")
		val screen = client.gui.screen()
		val shown = (screen as? AbstractContainerScreen<*>)?.menu
		val held = player.containerMenu

		val lines = mutableListOf<String>()
		lines += "§8[Cryptic] §7Screen: §f${screen?.javaClass?.simpleName ?: "none"}" +
			(shown?.let { " §7menu §f${it.containerId}" } ?: "")
		lines += "§8[Cryptic] §7Player holds menu §f${held.containerId}" +
			" §7(own inventory is §f${player.inventoryMenu.containerId}§7)" +
			if (held === player.inventoryMenu) " §a— in step" else " §c— not the inventory"
		lines += "§8[Cryptic] §7On the cursor: §f${held.carried.takeUnless { it.isEmpty }?.hoverName?.string ?: "nothing"}"
		lines += "§8[Cryptic] §7Terminal tracked: §f${Terminals.current?.type ?: "none"}"
		lines += "§8[Cryptic] §7Repairs this session: §f$repairs"
		if (history.isEmpty()) {
			lines += "§8[Cryptic] §7Nothing opened yet."
		} else {
			history.forEach { lines += "§8[Cryptic] §8$it" }
		}
		return lines
	}
}
