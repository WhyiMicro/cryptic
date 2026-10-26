package imicro.cryptic.feature

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import java.util.Locale

/**
 * The menu Hypixel opens while a dungeon instance is made ("Creating instance", "Starting in 5s"),
 * closed as it opens so you keep moving while you wait; the queue goes on without it. Asked for by
 * Auto Requeue and the Party Finder. An idea from Lumen's Hide Join Menu.
 */
object QueueScreen {
	private val FORMATTING = Regex("§.")
	private val countdown = Regex("""\b(?:auto-closing|starting)\s+in\b""")

	/** Ticks the menu has been seen, so its items are in before it's judged. */
	private var seenFor = 0
	private var seen: AbstractContainerScreen<*>? = null

	private fun wanted(): Boolean =
		(AutoRequeue.module.enabled && AutoRequeue.hideQueueScreen.value) ||
			(PartyFinder.module.enabled && PartyFinder.hideQueueScreen.value)

	private fun isQueueScreen(screen: AbstractContainerScreen<*>): Boolean {
		val texts = sequence {
			yield(screen.title.string)
			for (slot in screen.menu.slots) {
				if (slot.container is Inventory || slot.item.isEmpty) continue
				yield(slot.item.hoverName.string)
				slot.item.get(DataComponents.LORE)?.lines()?.forEach { yield(it.string) }
			}
		}
		val lines = texts.map { it.replace(FORMATTING, "").lowercase(Locale.ROOT) }.toList()
		if (lines.any { "creating instance" in it || "generating instance" in it }) return true
		// A countdown alone is any event timer; only one about a dungeon counts.
		return lines.any { countdown.containsMatchIn(it) } && lines.any { "instance" in it || "catacombs" in it }
	}

	fun tick(client: Minecraft) {
		val screen = client.gui.screen() as? AbstractContainerScreen<*>
		if (screen == null || !wanted()) {
			seen = null
			return
		}
		if (screen !== seen) {
			seen = screen
			seenFor = 0
		}
		if (++seenFor < 2 || !isQueueScreen(screen)) return
		// Closed properly, telling the server, so the inventory isn't left thinking it's open.
		client.player?.closeContainer()
		seen = null
	}
}
