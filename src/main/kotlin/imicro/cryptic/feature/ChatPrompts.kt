package imicro.cryptic.feature

import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.skyblock.HypixelServer
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW
import java.util.Optional

/**
 * Better Chat's prompt helper: when Hypixel asks a yes-or-no question in chat with a clickable
 * [YES] (or "Click here to accept"), a notification offers it on a key, so it can be answered
 * without opening chat. The idea is Skyblocker's (LGPL-3.0); this is Cryptic's own.
 */
object ChatPrompts {
	private val FORMATTING = Regex("§.")

	/** The clickable words that answer yes. */
	private val YES = Regex("""^\[?\s*(?:YES|ACCEPT|CONFIRM|Y)\s*]?!?$""", RegexOption.IGNORE_CASE)
	private val CLICK_HERE = Regex("""click (?:here )?to (?:accept|confirm)""", RegexOption.IGNORE_CASE)

	/** Invites already have their own notifications in Party Features. */
	private val HANDLED_ELSEWHERE = Regex("""^/?(?:p|party|f|friend|g|guild) accept\b""", RegexOption.IGNORE_CASE)

	private const val ID = "chat_prompt"

	@JvmField
	val enabled = ToggleModuleSetting(
		id = "prompt_helper",
		label = "Answer prompts with a key",
		defaultValue = false,
		description = "When Hypixel asks a question in chat with a clickable [YES] or \"Click here to accept\", a notification offers it: press the key to accept.",
	)

	@JvmField
	val acceptKey = KeybindModuleSetting(
		id = "prompt_key",
		label = "Accept key",
		description = "Only taken while a prompt is waiting, and never while you type.",
		defaultKey = GLFW.GLFW_KEY_Y,
		visibleIf = { enabled.value },
	)

	@JvmField
	val time = SliderModuleSetting(
		id = "prompt_time",
		label = "Time to answer",
		unit = "s",
		defaultValue = 10.0,
		min = 3.0,
		max = 30.0,
		step = 1.0,
		visibleIf = { enabled.value },
	)

	init {
		enabled.withMore(acceptKey, time)
	}

	/** Chat, from the packet on the client thread. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !BetterChat.module.enabled || !enabled.value || !HypixelServer.connected) return
		val command = yesCommand(message) ?: return
		if (HANDLED_ELSEWHERE.containsMatchIn(command)) return

		val question = message.string.replace(FORMATTING, "")
			.replace(Regex("""\[(?:YES|NO|ACCEPT|DENY|CONFIRM|CANCEL|Y|N)]""", RegexOption.IGNORE_CASE), "")
			.replace(CLICK_HERE, "")
			.replace(Regex("""\s+"""), " ")
			.trim()
			.ifEmpty { "Accept?" }
			.let { if (it.length > 80) it.take(77) + "..." else it }

		val keys = if (acceptKey.bound && !acceptKey.isMouse) mapOf(acceptKey.keyCode to { run(command) }) else emptyMap()
		val footer = if (keys.isEmpty()) "§7Click [YES] in chat" else "§a[${acceptKey.keyName}] §fAccept"
		Toasts.ask(
			source = "Better Chat", id = ID, life = time.value, keys = keys, footer = footer,
		) { "§7$question" }
	}

	private fun run(command: String) {
		val connection = Minecraft.getInstance().connection ?: return
		connection.sendCommand(command.removePrefix("/"))
	}

	/** The command behind the line's "yes" piece, if it has one. */
	private fun yesCommand(message: Component): String? {
		var found: String? = null
		message.visit({ style: Style, piece: String ->
			val click = style.clickEvent
			if (click is ClickEvent.RunCommand) {
				val text = piece.replace(FORMATTING, "").trim()
				if (YES.matches(text) || CLICK_HERE.containsMatchIn(text)) {
					found = click.command()
					return@visit Optional.of(Unit)
				}
			}
			Optional.empty()
		}, Style.EMPTY)
		return found
	}
}
