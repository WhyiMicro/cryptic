package imicro.cryptic.experiment

import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * Logs the Experimentation Table as it is played: a slot-by-slot dump of each menu once it settles,
 * plus notes in chat. A command can't be typed while the table's chest is open, so it records live.
 */
object ExperimentDebug {
	var enabled = false
		private set

	private val dumped = mutableSetOf<String>()

	private var lastNote = ""

	fun toggle(): Boolean {
		enabled = !enabled
		dumped.clear()
		lastNote = ""
		if (enabled) {
			Cryptic.LOGGER.info("[experiments] watching. Play one experiment, then send logs/latest.log.")
		}
		return enabled
	}

	/** A note shown in chat and the log, once. */
	fun note(text: String) {
		if (!enabled || text == lastNote) return
		lastNote = text
		Cryptic.LOGGER.info("[experiments] $text")
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
			Component.literal("§8[Cryptic] §7$text"),
		)
	}

	private var pendingTitle: String? = null
	private var pendingAt = 0L

	/**
	 * Queues the menu to be dumped once it stops changing. Hypixel fills a chest slot by slot, so
	 * dumping every update buried the useful board.
	 */
	fun dumpMenu(title: String) {
		if (!enabled || title.isEmpty()) return
		pendingTitle = title
		pendingAt = System.currentTimeMillis()
	}

	/** Writes a queued menu once nothing has touched it for a moment. */
	fun flush() {
		val title = pendingTitle ?: return
		if (System.currentTimeMillis() - pendingAt < SETTLE_MILLIS) return

		val items = ExperimentTracker.menuItems
		pendingTitle = null

		// Keyed on the slot contents, so a changed board is a new entry and a resent one isn't.
		val key = items.joinToString("|") {
			if (it.isEmpty) "" else "${it.item}${it.count}${it.hoverName.string}"
		}
		if (!dumped.add("$title#${key.hashCode()}")) return

		Cryptic.LOGGER.info("[experiments] ---- menu \"$title\" (${items.size} slots) ----")
		items.forEachIndexed { slot, stack ->
			if (stack.isEmpty) return@forEachIndexed
			val id = BuiltInRegistries.ITEM.getKey(stack.item)
			val name = ExperimentRules.stripFormatting(stack.hoverName.string)
			val lore = loreLines(stack).joinToString(" | ")
			Cryptic.LOGGER.info("[experiments]   $slot  $id  x${stack.count}  \"$name\"  $lore")
		}
		Cryptic.LOGGER.info("[experiments] ---- end \"$title\" ----")
	}

	private const val SETTLE_MILLIS = 300L

	private fun loreLines(stack: ItemStack): List<String> {
		val player = Minecraft.getInstance().player
        val lines = runCatching {
			stack.getTooltipLines(Item.TooltipContext.EMPTY, player, TooltipFlag.NORMAL)
		}.getOrNull() ?: return emptyList()
		return lines.drop(1).map { ExperimentRules.stripFormatting(it.string) }.filter { it.isNotBlank() }
	}
}
