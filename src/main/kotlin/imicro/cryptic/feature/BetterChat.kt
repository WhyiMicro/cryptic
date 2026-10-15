package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

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
	val abbreviate = ToggleModuleSetting(
		id = "abbreviate",
		label = "Abbreviate channels",
		defaultValue = false,
		description = "Shows \"Party >\" as \"P >\" and \"Guild >\" as \"G >\".",
	)

	@JvmField
	val module = Module(
		id = "better_chat",
		name = "Better Chat",
		description = "Tidies up chat",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(hideGuild, abbreviate),
	)

	/** Whether a chat message should be kept out of the chat window. */
	@JvmStatic
	fun hidesChat(message: Component, overlay: Boolean): Boolean {
		if (overlay || !module.enabled) return false
		val text = message.string.replace(FORMATTING, "").trimStart()
		return hideGuild.value && text.startsWith("Guild > ")
	}

	/** The channels shortened, and what each becomes. */
	private val CHANNELS = listOf("Party" to "P", "Guild" to "G")

	/**
	 * A chat line as the chat window should show it: with "Party >" or
	 * "Guild >" at its start shortened when Abbreviate is on.
	 *
	 * Rebuilt piece by piece so every colour, and every click and hover on the
	 * player's name, is kept: only the first piece holding the channel's name
	 * is changed, and only that word in it. The colour codes Hypixel writes
	 * into the text itself sit around the word and are left where they are.
	 */
	@JvmStatic
	fun shown(message: Component): Component {
		if (!module.enabled || !abbreviate.value) return message
		val plain = message.string.replace(FORMATTING, "").trimStart()
		val (word, short) = CHANNELS.firstOrNull { plain.startsWith("${it.first} > ") } ?: return message

		val rebuilt = Component.empty()
		var replaced = false
		message.visit({ style, piece ->
			var text = piece
			if (!replaced && word in piece) {
				text = piece.replaceFirst(word, short)
				replaced = true
			}
			rebuilt.append(Component.literal(text).withStyle(style))
			java.util.Optional.empty<Unit>()
		}, Style.EMPTY)
		return if (replaced) rebuilt else message
	}
}
