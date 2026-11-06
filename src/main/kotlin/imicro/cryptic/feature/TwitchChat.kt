package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.FormattedCharSequence
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * A Twitch channel's chat on the HUD. Read anonymously over Twitch's IRC gateway as a
 * `justinfan` guest, which needs no account and can't send. The socket lives on its own thread and
 * only hands finished lines over; everything touching the game happens in [tick].
 */
object TwitchChat {
	private const val HOST = "irc.chat.twitch.tv"
	private const val PORT = 6697

	/** Twitch pings about every five minutes; this long without a byte means the link is dead. */
	private const val SILENT_MILLIS = 7 * 60_000L

	/** A channel typed must stay put this long before it's joined, so typing doesn't rejoin per key. */
	private const val SETTLE_MILLIS = 1_500L

	private const val KEPT = 100

	private val CHANNEL = Regex("^[a-z0-9_]{2,25}$")

	@JvmField
	val channel = TextModuleSetting(
		id = "channel",
		label = "Channel",
		maxLength = 26,
		hint = "channel name",
		description = "The Twitch channel whose chat to show, as in twitch.tv/<name>. Read as a guest: no account, nothing sent.",
	)

	@JvmField
	val showHud = ToggleModuleSetting(
		id = "show_hud",
		label = "Show on HUD",
		defaultValue = true,
		description = "The chat as a HUD element. Off, it only goes to the game's chat (turn on Show in chat).",
	)

	@JvmField
	val maxLines = SliderModuleSetting(
		id = "max_lines",
		label = "Lines",
		defaultValue = 6.0,
		min = 2.0,
		max = 20.0,
		step = 1.0,
		visibleIf = { showHud.value },
	)

	@JvmField
	val width = SliderModuleSetting(
		id = "width",
		label = "Width",
		unit = "px",
		defaultValue = 220.0,
		min = 100.0,
		max = 400.0,
		step = 10.0,
		visibleIf = { showHud.value },
	)

	@JvmField
	val fade = SliderModuleSetting(
		id = "fade",
		label = "Fade after",
		unit = "s",
		defaultValue = 30.0,
		min = 0.0,
		max = 120.0,
		step = 5.0,
		description = "How long a line stays before it fades. Zero keeps every line.",
		visibleIf = { showHud.value },
	)

	@JvmField
	val nameColors = ToggleModuleSetting(
		id = "name_colors",
		label = "Name colours",
		defaultValue = true,
		description = "Each chatter's own Twitch colour, lightened where it would be too dark to read.",
	)

	@JvmField
	val background = ToggleModuleSetting(id = "background", label = "Background", defaultValue = true, visibleIf = { showHud.value })

	@JvmField
	val backgroundColor = ColorModuleSetting(
		id = "background_color",
		label = "Background color",
		defaultRgb = 0x000000,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		inlineWith = background,
	)

	@JvmField
	val alsoInChat = ToggleModuleSetting(
		id = "also_in_chat",
		label = "Show in chat",
		defaultValue = false,
		description = "Puts every line in the game's chat, marked [Twitch].",
	)

	@JvmField
	val module = Module(
		id = "twitch_chat",
		name = "Twitch Chat",
		description = "A Twitch chat in game",
		category = ModuleCategory.VISUAL,
		settings = listOf(
			channel,
			showHud.withMore(maxLines, width, fade),
			background, backgroundColor, alsoInChat, nameColors,
		),
	)

	/** One finished chat line, from the socket thread. */
	private class Incoming(val name: String, val color: Int?, val text: String, val action: Boolean, val status: Boolean)

	private class Line(val component: Component, val at: Long) {
		var wrapped: List<FormattedCharSequence> = emptyList()
		var wrappedAt = -1
	}

	private val incoming = ConcurrentLinkedQueue<Incoming>()
	private val lines = ArrayDeque<Line>()

	/** The channel the socket thread should be in; null tells it to leave. */
	@Volatile
	private var wanted: String? = null

	private var worker: Thread? = null

	private var typed = ""
	private var typedSince = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(TwitchElement())
	}

	fun tick() {
		val now = System.currentTimeMillis()
		val text = channel.value.trim().removePrefix("#").lowercase()
		if (text != typed) {
			typed = text
			typedSince = now
		}
		val target = if (module.enabled && CHANNEL.matches(typed) && now - typedSince >= SETTLE_MILLIS) typed else null
		if (!module.enabled || typed.isEmpty()) {
			wanted = null
		} else if (target != null && target != wanted) {
			wanted = target
			lines.clear()
		}

		if (wanted != null && worker?.isAlive != true) {
			worker = Thread(::run, "Cryptic Twitch chat").apply {
				isDaemon = true
				start()
			}
		}

		while (true) {
			val next = incoming.poll() ?: break
			add(next, now)
		}
	}

	private fun add(next: Incoming, now: Long) {
		val component = if (next.status) {
			Component.literal(next.text).withStyle { it.withColor(0xA970FF).withItalic(true) }
		} else {
			val nameColor = next.color?.takeIf { nameColors.value }?.let(::readable) ?: 0xA970FF
			val name = Component.literal(next.name).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(nameColor)))
			val body = Component.literal(next.text).withStyle { if (next.action) it.withColor(nameColor).withItalic(true) else it.withColor(0xFFFFFF) }
			Component.empty().append(name).append(Component.literal(if (next.action) " " else ": ").withStyle { it.withColor(0xAAAAAA) }).append(body)
		}
		lines.addLast(Line(component, now))
		while (lines.size > KEPT) lines.removeFirst()
		// Without the HUD, "Joined" and errors go to chat too, or there'd be no sign it connected.
		if (alsoInChat.value && (!next.status || !showHud.value)) {
			Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
				Component.literal("§5[Twitch] ").append(component),
			)
		}
	}

	/** Lifts a colour too dark for a dark background, keeping its hue. */
	private fun readable(rgb: Int): Int {
		val r = rgb shr 16 and 0xFF
		val g = rgb shr 8 and 0xFF
		val b = rgb and 0xFF
		val luma = (r * 299 + g * 587 + b * 114) / 1000
		if (luma >= 90) return rgb
		fun lift(c: Int) = c + (255 - c) * 2 / 5
		return (lift(r) shl 16) or (lift(g) shl 8) or lift(b)
	}

	// ---- Socket thread -------------------------------------------------------------------------

	private fun status(text: String) {
		incoming.add(Incoming("", null, text, action = false, status = true))
	}

	private fun run() {
		var backoff = 2_000L
		while (true) {
			val target = wanted ?: return
			val joined = try {
				session(target)
			} catch (e: Exception) {
				if (wanted == target) status("Lost #$target (${e.message ?: e.javaClass.simpleName}), retrying")
				false
			}
			if (wanted == null) return
			if (wanted != target) {
				backoff = 2_000L
				continue
			}
			// A session that got in resets the wait; one that kept failing doubles it.
			backoff = if (joined) 2_000L else (backoff * 2).coerceAtMost(60_000L)
			val until = System.currentTimeMillis() + backoff
			while (System.currentTimeMillis() < until && wanted == target) Thread.sleep(250)
		}
	}

	/** One connection, until it drops or the channel changes. True once it had joined. */
	private fun session(target: String): Boolean {
		var joined = false
		val socket: Socket = SSLSocketFactory.getDefault().createSocket()
		socket.use {
			it.connect(InetSocketAddress(HOST, PORT), 10_000)
			it.soTimeout = 1_000
			val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.UTF_8))
			val writer = OutputStreamWriter(it.getOutputStream(), StandardCharsets.UTF_8)
			fun send(line: String) {
				writer.write(line)
				writer.write("\r\n")
				writer.flush()
			}
			send("CAP REQ :twitch.tv/tags twitch.tv/commands")
			send("NICK justinfan${Random.nextInt(10_000, 99_999)}")
			send("JOIN #$target")

			var heard = System.currentTimeMillis()
			while (wanted == target) {
				val line = try {
					reader.readLine() ?: return joined
				} catch (_: SocketTimeoutException) {
					if (System.currentTimeMillis() - heard > SILENT_MILLIS) return joined
					continue
				}
				heard = System.currentTimeMillis()
				if (handle(line, target, ::send)) joined = true
				if (line.contains(" RECONNECT")) return joined
			}
			runCatching { send("PART #$target") }
		}
		return joined
	}

	/** One IRC line. True when it says we're in the channel. */
	private fun handle(raw: String, target: String, send: (String) -> Unit): Boolean {
		if (raw.startsWith("PING")) {
			send("PONG" + raw.removePrefix("PING"))
			return false
		}
		var rest = raw
		var tags: Map<String, String> = emptyMap()
		if (rest.startsWith("@")) {
			val space = rest.indexOf(' ')
			if (space < 0) return false
			tags = rest.substring(1, space).split(';').associate { tag ->
				val eq = tag.indexOf('=')
				if (eq < 0) tag to "" else tag.substring(0, eq) to tag.substring(eq + 1)
			}
			rest = rest.substring(space + 1)
		}
		val prefix = if (rest.startsWith(":")) rest.substring(1, rest.indexOf(' ').coerceAtLeast(1)) else ""
		val afterPrefix = if (rest.startsWith(":")) rest.substringAfter(' ') else rest
		val command = afterPrefix.substringBefore(' ')
		val trailing = afterPrefix.substringAfter(" :", "")
		when (command) {
			"PRIVMSG" -> {
				val login = prefix.substringBefore('!')
				val name = tags["display-name"]?.takeIf { it.isNotEmpty() } ?: login
				val color = tags["color"]?.removePrefix("#")?.toIntOrNull(16)
				var text = trailing
				var action = false
				if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
					text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
					action = true
				}
				// Section signs would be read as Minecraft formatting.
				incoming.add(Incoming(name.replace('§', '?'), color, text.replace('§', '?'), action, status = false))
			}
			"JOIN" -> if (prefix.startsWith("justinfan")) {
				status("Joined #$target")
				return true
			}
			"NOTICE" -> status(trailing.replace('§', '?'))
			"CLEARCHAT", "CLEARMSG" -> Unit
		}
		return false
	}

	// ---- HUD -----------------------------------------------------------------------------------

	private class TwitchElement : HudElement("twitch_chat", "Twitch Chat", 1.0, 0.06) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = TwitchChat.width.value.toInt() + 4
		override val height: Int get() = maxLines.value.toInt() * font.lineHeight + 4

		override fun isVisible(): Boolean = module.enabled && showHud.value && Minecraft.getInstance().player != null && lines.isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled && showHud.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, lines.toList(), System.currentTimeMillis())

		override fun renderExample(context: GuiGraphicsExtractor) {
			val now = System.currentTimeMillis()
			val sample = listOf(
				"Joined #${typed.ifEmpty { "channel" }}" to null,
				"viewer: what floor is this" to 0x1E90FF,
				"Moderator: !pb for personal bests" to 0x00FF7F,
				"chatter: PogChamp" to 0xFF4500,
			).map { (text, color) ->
				val component = if (color == null) {
					Component.literal(text).withStyle { it.withColor(0xA970FF).withItalic(true) }
				} else {
					val name = text.substringBefore(':')
					Component.literal(name).withStyle { it.withColor(color) }
						.append(Component.literal(":").withStyle { it.withColor(0xAAAAAA) })
						.append(Component.literal(text.substringAfter(':')).withStyle { it.withColor(0xFFFFFF) })
				}
				Line(component, now)
			}
			draw(context, sample, now)
		}

		private fun draw(context: GuiGraphicsExtractor, from: List<Line>, now: Long) {
			keepOnScreen(context)
			val wrapWidth = TwitchChat.width.value.toInt()
			val rows = maxLines.value.toInt()
			val fadeMillis = (fade.value * 1000).toLong()
			val lineHeight = font.lineHeight

			// Newest at the bottom; walk back until the box is full.
			val shown = ArrayList<Pair<FormattedCharSequence, Int>>()
			for (index in from.indices.reversed()) {
				val line = from[index]
				val alpha = when {
					fadeMillis <= 0 -> 255
					now - line.at < fadeMillis -> 255
					else -> (255 - (now - line.at - fadeMillis) * 255 / 1000).toInt()
				}
				if (alpha <= 8) break
				if (line.wrappedAt != wrapWidth) {
					line.wrapped = font.split(line.component, wrapWidth)
					line.wrappedAt = wrapWidth
				}
				for (part in line.wrapped.asReversed()) {
					if (shown.size >= rows) break
					shown += part to alpha
				}
				if (shown.size >= rows) break
			}
			if (shown.isEmpty()) return

			val top = height - 2 - shown.size * lineHeight
			if (background.value) {
				val strongest = shown.maxOf { it.second }
				val bg = backgroundColor.argb
				val bgAlpha = (bg ushr 24) * strongest / 255
				context.fill(0, top - 2, width, height, (bgAlpha shl 24) or (bg and 0xFFFFFF))
			}
			var y = height - 2 - lineHeight
			for ((text, alpha) in shown) {
				context.text(font, text, 2, y, (alpha shl 24) or 0xFFFFFF)
				y -= lineHeight
			}
		}
	}
}
