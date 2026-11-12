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
		description = "When Hypixel or an NPC asks a question in chat with clickable answers ([YES], \"Click here to accept\", \"Select an option\"), a notification offers the yes answer: press the key to give it.",
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

	/**
	 * Hypixel's own answer commands: NPC dialogue options and its yes/no prompts. Matching these
	 * catches answers worded any way ("[Sure thing, partner!]"), not just [YES].
	 */
	private val ANSWER_COMMAND = Regex("""^/?(?:selectnpcoption|chatprompt)\b""", RegexOption.IGNORE_CASE)
	private val NPC_LINE = Regex("""^\[NPC] ([^:]+): (.+)$""")
	private val SELECT_OPTION = Regex("""^Select an option:""", RegexOption.IGNORE_CASE)

	/** The NPC's last line, the question its "Select an option" answers. */
	private var lastNpc: String? = null
	private var lastNpcAt = 0L

	/** Chat, from the packet on the client thread. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !BetterChat.module.enabled || !enabled.value || !HypixelServer.connected) return
		val plain = message.string.replace(FORMATTING, "").trim()
		NPC_LINE.find(plain)?.let {
			lastNpc = "${it.groupValues[1]}: ${it.groupValues[2]}"
			lastNpcAt = System.currentTimeMillis()
			return
		}

		val (command, answer) = yesOption(message) ?: return
		if (HANDLED_ELSEWHERE.containsMatchIn(command)) return

		val npcQuestion = lastNpc?.takeIf { System.currentTimeMillis() - lastNpcAt < 15_000 }
		val question = if (SELECT_OPTION.containsMatchIn(plain) && npcQuestion != null) npcQuestion else plain
			.replace(SELECT_OPTION, "")
			.replace(Regex("""\[(?:YES|NO|ACCEPT|DENY|CONFIRM|CANCEL|Y|N)]""", RegexOption.IGNORE_CASE), "")
			.replace(CLICK_HERE, "")
			.replace("➜", "")
			.replace(Regex("""\s+"""), " ")
			.trim()
			.ifEmpty { "Accept?" }
		val shortQuestion = if (question.length > 80) question.take(77) + "..." else question
		val shortAnswer = answer.removeSurrounding("[", "]").ifEmpty { "Accept" }.let { if (it.length > 40) it.take(37) + "..." else it }

		val keys = if (acceptKey.bound && !acceptKey.isMouse) mapOf(acceptKey.keyCode to { run(command) }) else emptyMap()
		val footer = if (keys.isEmpty()) "§7Click it in chat" else "§a[${acceptKey.keyName}] §f$shortAnswer"
		Toasts.ask(
			source = "Better Chat", id = ID, life = time.value, keys = keys, footer = footer,
		) { "§7$shortQuestion" }
	}

	private fun run(command: String) {
		val connection = Minecraft.getInstance().connection ?: return
		connection.sendCommand(command.removePrefix("/"))
	}

	/**
	 * The command and text of the line's "yes" answer: a piece worded yes, else the first of
	 * Hypixel's answer commands (an NPC lists its agreeing option first).
	 */
	private fun yesOption(message: Component): Pair<String, String>? {
		var yes: Pair<String, String>? = null
		var first: Pair<String, String>? = null
		// One answer can be split over several pieces sharing a click; join them back up.
		var current: String? = null
		val text = StringBuilder()
		fun close() {
			val command = current ?: return
			val words = text.toString().replace(FORMATTING, "").replace(Regex("""\s+"""), " ").trim()
			if (yes == null && (YES.matches(words) || CLICK_HERE.containsMatchIn(words))) yes = command to words
			if (first == null && ANSWER_COMMAND.containsMatchIn(command) && words.isNotEmpty()) first = command to words
			current = null
			text.setLength(0)
		}
		message.visit({ style: Style, piece: String ->
			val command = (style.clickEvent as? ClickEvent.RunCommand)?.command()
			if (command != current) close()
			if (command != null) {
				current = command
				text.append(piece)
			}
			Optional.empty<Unit>()
		}, Style.EMPTY)
		close()
		return yes ?: first
	}
}
