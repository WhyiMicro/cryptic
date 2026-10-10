package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.network.chat.Component

/**
 * Takes things out of chat that you would rather not read.
 *
 * Only the chat window is spared: every other part of Cryptic has already read
 * a line by the time it is hidden, so nothing that listens for a message stops
 * hearing it.
 */
object BetterChat {
	private val FORMATTING = Regex("§.")

	@JvmField
	val hideGuild = ToggleModuleSetting(
		id = "hide_guild",
		label = "Hide guild messages",
		defaultValue = true,
		description = "Hides everything Hypixel prefixes with \"Guild >\": guild chat, and members joining and leaving.",
	)

	@JvmField
	val module = Module(
		id = "better_chat",
		name = "Better Chat",
		description = "Tidies up chat",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(hideGuild),
	)

	/** Whether a chat message should be kept out of the chat window. */
	@JvmStatic
	fun hidesChat(message: Component, overlay: Boolean): Boolean {
		if (overlay || !module.enabled) return false
		val text = message.string.replace(FORMATTING, "").trimStart()
		return hideGuild.value && text.startsWith("Guild > ")
	}
}
