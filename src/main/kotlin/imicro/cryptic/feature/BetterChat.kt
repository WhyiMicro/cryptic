package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * Tidies the chat window: Hypixel channels, a longer history kept across servers, merged repeats,
 * channel tabs, search and a right-click menu. Hiding only changes the window; the rest of Cryptic
 * has already read the line. The window's side is in [ChatExtras].
 */
object BetterChat {
	private val FORMATTING = Regex("§.")

	@JvmField
	val hideGuild = ToggleModuleSetting(
		id = "hide_guild",
		label = "Hide guild messages",
		defaultValue = true,
		description = "Hides everything Hypixel prefixes with \"Guild >\": guild chat, and members joining and leaving. " +
			"They stay in history, so turning this off (or the Guild tab) shows them again.",
	)

	@JvmField
	val noConfirm = ToggleModuleSetting(
		id = "no_confirm",
		label = "Skip command confirmations",
		defaultValue = true,
		description = "Runs a command from a clicked chat line straight away, without the \"Are you sure?\" screen.",
	)

	private val historySection = SectionModuleSetting("history_section", "History")

	@JvmField
	val keepHistory = ToggleModuleSetting(
		id = "keep_history",
		label = "Keep chat history",
		defaultValue = true,
		description = "Keeps the chat when you leave a server or swap lobbies, instead of starting empty.",
	)

	@JvmField
	val longHistory = ToggleModuleSetting(
		id = "long_history",
		label = "Longer history",
		defaultValue = true,
		description = "Keeps more than the game's 100 lines to scroll back through.",
	)

	@JvmField
	val historyLength = SliderModuleSetting(
		id = "history_length",
		label = "Lines kept",
		defaultValue = 1000.0,
		min = 100.0,
		max = 2048.0,
		step = 50.0,
		visibleIf = { longHistory.value },
	)

	@JvmField
	val animate = ToggleModuleSetting(
		id = "animate",
		label = "Animate",
		defaultValue = true,
		description = "New lines slide up into place, and the chat box eases in when you open chat.",
	)

	@JvmField
	val animationTime = SliderModuleSetting(
		id = "animation_time",
		label = "Duration",
		unit = "ms",
		defaultValue = 150.0,
		min = 50.0,
		max = 500.0,
		step = 10.0,
		visibleIf = { animate.value },
	)

	@JvmField
	val compact = ToggleModuleSetting(
		id = "compact",
		label = "Merge repeated lines",
		defaultValue = true,
		description = "A line said again replaces the last one, with a count: \"Not enough mana (3)\".",
	)

	@JvmField
	val compactMode = DropdownModuleSetting(
		id = "compact_mode",
		label = "Merge",
		options = listOf("Only in a row", "Within a time"),
		defaultIndex = 0,
		description = "Only in a row merges a line with the one right before it; Within a time also merges with a recent one further up.",
		visibleIf = { compact.value },
	)

	@JvmField
	val compactWindow = SliderModuleSetting(
		id = "compact_window",
		label = "Time",
		unit = "s",
		defaultValue = 60.0,
		min = 5.0,
		max = 300.0,
		step = 5.0,
		visibleIf = { compact.value && compactMode.selectedIndex == 1 },
	)

	@JvmField
	val compactSkipClickable = ToggleModuleSetting(
		id = "compact_skip_clickable",
		label = "Skip clickable lines",
		defaultValue = true,
		description = "Leaves lines you can click alone, so every invite or link stays its own line.",
		visibleIf = { compact.value },
	)

	private val toolsSection = SectionModuleSetting("tools_section", "Chat screen")

	@JvmField
	val tabs = ToggleModuleSetting(
		id = "tabs",
		label = "Channel tabs",
		defaultValue = true,
		description = "All, User, Party, Guild, PM and Co-op buttons above the chat box, showing only that channel.",
	)

	@JvmField
	val shortTabs = ToggleModuleSetting(
		id = "short_tabs",
		label = "Short names",
		defaultValue = false,
		description = "A, User, P, G, PM and Co-op, to take less room.",
		visibleIf = { tabs.value },
	)

	@JvmField
	val search = ToggleModuleSetting(
		id = "search",
		label = "Search",
		defaultValue = true,
		description = "Ctrl+F in chat searches its history; every word typed has to be in a line.",
	)

	@JvmField
	val searchAlways = ToggleModuleSetting(
		id = "search_always",
		label = "Always show the search box",
		defaultValue = false,
		visibleIf = { search.value },
	)

	@JvmField
	val contextMenu = ToggleModuleSetting(
		id = "context_menu",
		label = "Right-click menu",
		defaultValue = true,
		description = "Right-click a line to copy it, copy just the message, copy it with & colour codes, or delete it. The line you point at is marked above and below.",
	)

	@JvmField
	val rightClickCopy = ToggleModuleSetting(
		id = "right_click_copy",
		label = "Right-click copies at once",
		defaultValue = false,
		description = "Copies the line's text without opening the menu.",
		visibleIf = { contextMenu.value },
	)

	@JvmField
	val abbreviate = ToggleModuleSetting(
		id = "abbreviate",
		label = "Abbreviate channels",
		defaultValue = false,
		description = "Shows \"Party >\" as \"P >\" and \"Guild >\" as \"G >\".",
	)

	@JvmField
	val peekKey = KeybindModuleSetting(
		id = "peek_key",
		label = "Peek",
		description = "Hold it to see the chat history, as tall as an open chat, without opening the chat.",
	)

	/** Whether the peek key is held with nothing else open. */
	@JvmStatic
	fun peeking(): Boolean {
		if (!module.enabled || !peekKey.bound) return false
		val client = net.minecraft.client.Minecraft.getInstance()
		return client.gui.screen() == null && peekKey.isDown(client.window)
	}

	@JvmField
	val module = Module(
		id = "better_chat",
		name = "Better Chat",
		description = "Tidies up chat",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			hideGuild, abbreviate, noConfirm, CurrentChat.enabled,
			historySection, keepHistory, longHistory.withMore(historyLength),
			compact.withMore(compactMode, compactWindow, compactSkipClickable),
			toolsSection, animate.withMore(animationTime), tabs.withMore(shortTabs), search.withMore(searchAlways), contextMenu.withMore(rightClickCopy), peekKey,
		),
	)

	@JvmStatic
	fun hidesChat(message: Component, overlay: Boolean): Boolean {
		if (overlay || !module.enabled) return false
		// Guild lines are hidden by the window instead, so they can come back.
		return false
	}

	private val CHANNELS = listOf("Party" to "P", "Guild" to "G")

	/**
	 * A chat line with "Party >" or "Guild >" shortened when Abbreviate is on. Only the word in the
	 * first piece holding it changes, so colours, clicks and hovers are kept.
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
