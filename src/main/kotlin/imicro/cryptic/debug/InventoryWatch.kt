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
 * Notices an inventory that cannot be clicked, puts it right, and says why.
 *
 * The game keeps two ideas of which menu is open: the screen on display, and
 * the menu the player is holding. Every click is checked against the second,
 * and one made in any other menu is thrown away with a line in the log and
 * nothing on screen — so when the two disagree the inventory looks perfectly
 * normal and does nothing at all, until closing it puts them back in step.
 *
 * That is what "I have to open my inventory twice" was. The cause found so far
 * was Cryptic's own: the Secrets module shut a chest by removing its screen,
 * which left the player holding the chest. That is fixed where it happened, and
 * this is the net under it — for the next cause, whoever's it turns out to be.
 *
 * Only the one state that cannot be anything else is repaired: the player's own
 * inventory on display while they hold some other menu. A mod that shows a
 * screen of its own over a chest it is still using looks similar and is left
 * strictly alone.
 */
object InventoryWatch {
	private const val HISTORY = 8
	private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

	/** What has been opened and shut lately, newest last, for the debug command. */
	private val history = ArrayDeque<String>()

	private var repairs = 0

	/** The menu the player held last tick, so a change can be written down. */
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

		// The inventory is up and every click in it is being refused.
		repairs++
		val stale = held.containerId
		note("repaired: inventory open while holding menu $stale")
		Cryptic.LOGGER.warn(
			"[Cryptic/inventory] The inventory was open while the player still held menu {}, so no click " +
				"in it could land. Put right. Recent: {}",
			stale,
			history.joinToString(" | "),
		)

		// Told to the server as well, in case it is the one still holding it.
		player.connection.send(ServerboundContainerClosePacket(stale))
		player.containerMenu = player.inventoryMenu
		lastHeld = player.inventoryMenu.containerId
	}

	/** The two ideas of what is open, side by side, for `/cryptic debug inventory`. */
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
