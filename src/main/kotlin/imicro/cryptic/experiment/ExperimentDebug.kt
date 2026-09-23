package imicro.cryptic.experiment

import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * Watches the Experimentation Table as it is played, rather than being asked
 * afterwards what it saw.
 *
 * A one-shot command was useless here for a plain reason: the table is a chest,
 * and a chest is a screen, and a screen means chat is not available to type into
 * — so by the time the command could be run the interesting moment was several
 * menus ago.
 *
 * Everything about the table's own menus in [ExperimentRunner] came from a mod
 * for Minecraft 1.8.9 and none of it could be checked against a real table. This
 * is how it gets checked: switch it on, play one experiment, and the log holds a
 * slot-by-slot dump of every menu that opened, which is enough to correct every
 * slot number and every line of lore at once.
 */
object ExperimentDebug {
	var enabled = false
		private set

	/** Menus already dumped, so a window re-sent five times is written once. */
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

	/**
	 * A line worth seeing as it happens, said once.
	 *
	 * Chat rather than only the log, because the useful moments are the ones
	 * behind an open chest where the log cannot be read either.
	 */
	fun note(text: String) {
		if (!enabled || text == lastNote) return
		lastNote = text
		Cryptic.LOGGER.info("[experiments] $text")
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
			Component.literal("§8[Cryptic] §7$text"),
		)
	}

	/**
	 * The whole menu, slot by slot, into the log.
	 *
	 * Only to the log: fifty-four lines of item ids is not something to put in
	 * anybody's chat, and it is the file that gets sent on afterwards.
	 */
	private var pendingTitle: String? = null
	private var pendingAt = 0L

	/**
	 * Queues the menu to be written out once it stops changing.
	 *
	 * Hypixel fills a chest one slot at a time, so a menu arrives as fifty-odd
	 * updates and writing each one out buried the useful board under fifty-three
	 * copies of it being built. Worse, the first attempt keyed its "have I seen
	 * this?" check on *which* slots were filled, which never changes once a board
	 * is up — so every Superpairs board after the opening deal, the ones with
	 * cards actually turned over, was thrown away as a duplicate.
	 *
	 * Held for a moment instead, and written when the updates stop. What lands in
	 * the log is one entry per settled board, which is what was wanted.
	 */
	fun dumpMenu(title: String) {
		if (!enabled || title.isEmpty()) return
		pendingTitle = title
		pendingAt = System.currentTimeMillis()
	}

	/**
	 * Writes out a queued menu once nothing has touched it for a moment.
	 *
	 * The items are read here rather than kept when the dump was asked for: by
	 * the time this runs they are the settled board anyway, and copying the list
	 * on every update was one more thing making a menu expensive to open.
	 */
	fun flush() {
		val title = pendingTitle ?: return
		if (System.currentTimeMillis() - pendingAt < SETTLE_MILLIS) return

		val items = ExperimentTracker.menuItems
		pendingTitle = null

		// Keyed on what is actually in the slots, so a board that changed is a
		// new entry and a board re-sent unchanged is not.
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

	/** Long enough for a chest to finish arriving, short enough to catch a flip. */
	private const val SETTLE_MILLIS = 300L

	private fun loreLines(stack: ItemStack): List<String> {
		val player = Minecraft.getInstance().player
        val lines = runCatching {
			stack.getTooltipLines(Item.TooltipContext.EMPTY, player, TooltipFlag.NORMAL)
		}.getOrNull() ?: return emptyList()
		// The first line is the name, which is already printed beside it.
		return lines.drop(1).map { ExperimentRules.stripFormatting(it.string) }.filter { it.isNotBlank() }
	}
}
