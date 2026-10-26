package imicro.cryptic.feature

import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component

/**
 * Better Chat's current chat display: which channel what you type goes to ("Chat: Party"), at the
 * right end of the chat bar while chat is open. Read from the lines Hypixel sends when it changes,
 * as SkyHanni does. Private conversations name the player.
 */
object CurrentChat {
	@JvmField
	val enabled = ToggleModuleSetting(
		id = "current_chat",
		label = "Current chat display",
		defaultValue = false,
		description = "Shows in the chat bar which chat you're typing in: All, Party, Guild, Officer, Co-op, or a private conversation.",
	)

	private val FORMATTING = Regex("§.")
	private val changed = Regex("""^You are now in the (.+) channel$""")
	private val movedToAll = listOf(
		"You are not in a party and were moved to the ALL channel.",
		"The conversation you were in expired and you have been moved back to the ALL channel.",
	)
	private val privateChat = Regex("""^Opened a chat conversation with (?:\[[^\]]+] )?(\w{1,16}) for the next 5 minutes\.""")

	/** The channel's name and colour: All until Hypixel says otherwise. */
	private var channel = "All"
	private var color = 0xFFFFFFFF.toInt()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> set("All") }
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen is ChatScreen) ScreenEvents.afterExtract(screen).register { drawn, graphics, _, _, _ -> draw(drawn, graphics) }
		}
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		val line = message.string.replace(FORMATTING, "").trim()
		changed.matchEntire(line)?.let { set(it.groupValues[1]); return }
		if (line in movedToAll) return set("All")
		privateChat.find(line)?.let { match ->
			channel = match.groupValues[1]
			color = 0xFFFF55FF.toInt()
		}
	}

	private fun set(name: String) {
		when (name.uppercase()) {
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


	/** Right-aligned in the bar, over a strip of the bar's own background so long messages stay readable. */
	private fun draw(screen: net.minecraft.client.gui.screens.Screen, graphics: GuiGraphicsExtractor) {
		if (!BetterChat.module.enabled || !enabled.value) return
		val client = Minecraft.getInstance()
		val font = client.font
		val prefix = "Chat: "
		val textWidth = font.width(prefix) + font.width(channel)
		val right = screen.width - 4
		val left = right - textWidth
		val top = screen.height - 12
		graphics.fill(left - 6, screen.height - 14, screen.width - 2, screen.height - 2, client.options.getBackgroundColor(Int.MIN_VALUE))
		graphics.fill(left - 5, screen.height - 12, left - 4, screen.height - 4, 0x40FFFFFF)
		graphics.text(font, prefix, left, top, 0xFF55FF55.toInt())
		graphics.text(font, channel, left + font.width(prefix), top, color)
	}
}
