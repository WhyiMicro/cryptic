package imicro.cryptic.feature

import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import imicro.cryptic.mixin.ChatScreenAccessor
import imicro.cryptic.skyblock.HypixelServer

/**
 * Better Chat's current chat display: which channel what you type goes to, written in front of it
 * in the chat bar ("Party: "), and the typing moved along to make room. Commands go nowhere, so a
 * line starting with "/" has none. Read from the lines Hypixel sends when it changes, as SkyHanni
 * does, and from your own `/chat` when Hypixel only says you're already there. Hypixel keeps your
 * channel between sessions, so Cryptic does too. Private conversations name the player.
 */
object CurrentChat {
	@JvmField
	val enabled = ToggleModuleSetting(
		id = "current_chat",
		label = "Current chat display",
		defaultValue = false,
		description = "Writes in front of what you type which chat it goes to: All, Party, Guild, Officer, Co-op, or a private conversation.",
	)

	private val FORMATTING = Regex("§.")
	private val changed = Regex("""^You are now in the (.+) channel$""")
	private val movedToAll = listOf(
		"You are not in a party and were moved to the ALL channel.",
		"The conversation you were in expired and you have been moved back to the ALL channel.",
	)
	private const val ALREADY = "You're already in this channel!"

	/** `/chat p` and the like: the channel each short name means, as Hypixel names it. */
	private val CHAT_COMMAND = Regex("""^chat\s+(\S+)""", RegexOption.IGNORE_CASE)
	private val ALIASES = mapOf(
		"a" to "ALL", "all" to "ALL", "p" to "PARTY", "party" to "PARTY", "g" to "GUILD", "guild" to "GUILD",
		"o" to "OFFICER", "officer" to "OFFICER", "coop" to "SKYBLOCK CO-OP", "co-op" to "SKYBLOCK CO-OP", "skyblock-coop" to "SKYBLOCK CO-OP",
	)

	/** The channel your last `/chat` asked for, in case Hypixel answers that you're already in it. */
	private var asked: String? = null
	private var askedAt = 0L
	private const val ASKED_MILLIS = 5_000L

	private val file get() = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("cryptic").resolve("current-chat.txt")

	/** The channel as Hypixel names it, saved; a private conversation isn't, since it lapses. */
	private var saved = "ALL"

	private val privateChat = Regex("""^Opened a chat conversation with (?:\[[^\]]+] )?(\w{1,16}) for the next 5 minutes\.""")

	/** The channel's name and colour, as last known. */
	private var channel = "All"
	private var color = 0xFFFFFFFF.toInt()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		saved = runCatching { java.nio.file.Files.readString(file).trim() }.getOrNull()?.ifEmpty { null } ?: "ALL"
		set(saved)
		// Hypixel keeps the channel you left in; only a private conversation is over.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> set(saved) }
		net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.register { command ->
			CHAT_COMMAND.find(command.trim())?.let { match ->
				asked = ALIASES[match.groupValues[1].lowercase()]
				askedAt = System.currentTimeMillis()
			}
		}
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen is ChatScreen) {
				ScreenEvents.beforeExtract(screen).register { drawn, _, _, _, _ -> place(drawn as ChatScreen) }
				ScreenEvents.afterExtract(screen).register { drawn, graphics, _, _, _ -> draw(drawn as ChatScreen, graphics) }
			}
		}
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		val line = message.string.replace(FORMATTING, "").trim()
		changed.matchEntire(line)?.let { set(it.groupValues[1]); return }
		if (line in movedToAll) return set("All")
		if (line == ALREADY) {
			asked?.takeIf { System.currentTimeMillis() - askedAt < ASKED_MILLIS }?.let(::set)
			asked = null
			return
		}
		privateChat.find(line)?.let { match ->
			channel = match.groupValues[1]
			color = 0xFFFF55FF.toInt()
		}
	}

	private fun set(name: String) {
		val key = name.uppercase()
		if (key != saved) {
			saved = key
			runCatching {
				java.nio.file.Files.createDirectories(file.parent)
				java.nio.file.Files.writeString(file, key)
			}
		}
		when (key) {
			"ALL" -> show("All", 0xFFFFFFFF.toInt())
			"PARTY" -> show("Party", 0xFF5555FF.toInt())
			"GUILD" -> show("Guild", 0xFF00AA00.toInt())
			"OFFICER" -> show("Officer", 0xFF00AAAA.toInt())
			"SKYBLOCK CO-OP", "COOP", "CO-OP" -> show("Co-op", 0xFF55FFFF.toInt())
			else -> show(name.lowercase().replaceFirstChar { it.uppercase() }, 0xFFFFFFFF.toInt())
		}
	}

	private fun show(name: String, rgb: Int) {
		channel = name
		color = rgb
	}


	/** The prefix in front of [screen]'s input, or none for a command or with the display off. */
	private fun prefix(screen: ChatScreen): String? {
		if (!BetterChat.module.enabled || !enabled.value || !HypixelServer.connected) return null
		val value = (screen as ChatScreenAccessor).`cryptic$input`().value
		return if (value.startsWith("/")) null else "$channel: "
	}

	/** Moves the input along to make room for the prefix, and back when there's none. */
	private fun place(screen: ChatScreen) {
		val input = (screen as ChatScreenAccessor).`cryptic$input`()
		val shift = prefix(screen)?.let { Minecraft.getInstance().font.width(it) } ?: 0
		if (input.x != INPUT_X + shift) {
			input.x = INPUT_X + shift
			input.width = screen.width - INPUT_X - shift
		}
	}

	private fun draw(screen: ChatScreen, graphics: GuiGraphicsExtractor) {
		val text = prefix(screen) ?: return
		graphics.text(Minecraft.getInstance().font, text, INPUT_X, screen.height - 12, color)
	}

	/** Where vanilla puts the input. */
	private const val INPUT_X = 4
}
