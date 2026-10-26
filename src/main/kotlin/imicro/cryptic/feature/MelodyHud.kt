package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.net.RelaySocket
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.terminal.sim.TermSimScreen
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Melody, shared with the party: says how far yours has got, and shows how far theirs has.
 *
 * **Sending** (NoammAddons' Melody Alert, Odin's Melody Message): a party chat line when the
 * terminal opens and for each row finished.
 *
 * **Showing**: Odin's Progress GUI is the live part, where every Odin user in the lobby joins a
 * room on Odin's relay during Goldor and streams their note, marker and row. Cryptic joins the same
 * room with the same messages, so it sees Odin users and they see it. NoammAddons' Melody Display
 * adds "ARCHER has melody! 1/3" from party chat, which covers teammates without the relay.
 *
 * No module of its own; these are switches on the Terminal Solver's card.
 */
object MelodyHud {
	private const val WHITE = 0xFFFFFFFF.toInt()
	private const val GREY = 0xFFAAAAAA.toInt()
	private const val PURPLE = 0xFFFF55FF.toInt()
	private const val GREEN = 0xFF55FF55.toInt()

	/** Three rows since SkyBlock 0.27.2. */
	private const val ROWS = 3

	private const val COLUMNS = 5

	/** Odin's relay; one room per lobby, named by the sidebar's server id. */
	private const val RELAY_URL = "wss://ws.odtheking.com/"

	private const val RETRY_MILLIS = 15_000L

	/** A teammate the relay has gone quiet about is removed after this long. */
	private const val LIVE_MILLIS = 10_000L

	/** The relay's message kinds, which are Odin's. */
	private const val KIND_CLOSED = 0
	private const val KIND_BUTTON = 1
	private const val KIND_MARKER = 2
	private const val KIND_NOTE = 5

	private val FORMATTING = Regex("§.")

	/** "Party > [MVP+] Name: the message", rank and emblem optional. */
	private val partyLine = Regex("""^Party > (?:\[[^\]]+] )?(\w{1,16})(?: \S)?: (.+)$""")

	/**
	 * "2/3" or "67%" standing alone. The old four-row "2/4" and "50%" are read too, for teammates
	 * with older mods, and converted to rows of three.
	 */
	private val fraction = Regex("""(?<![\d/])([0-4])/([34])(?![\d/])""")
	private val percent = Regex("""(?<!\d)(100|75|67|66|50|33|25|0)%""")

	/** Hypixel's line for a finished terminal, which ends that player's melody. */
	private val finished = Regex("""^(\w{1,16}) (?:activated|completed) a (?:terminal|device|lever)! \(\d+/\d+\)""")

	private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
	private const val CORE_OPENING = "The Core entrance is opening!"

	private const val FORMAT_QUARTERS = 0
	private const val LABEL_CLASS = 0
	private const val LABEL_NAME = 1

	private val section = SectionModuleSetting("melody_hud_section", "Melody")

	@JvmField
	val sendProgress = ToggleModuleSetting(
		id = "melody_send_progress",
		label = "Send melody progress",
		defaultValue = false,
		description = "Tells the party when you open melody and as each row is done: Melody 0/3, 1/3, 2/3.",
	)

	@JvmField
	val progressFormat = DropdownModuleSetting(
		id = "melody_progress_format",
		label = "Progress format",
		options = listOf("Melody 1/3", "Melody 33%"),
		defaultIndex = FORMAT_QUARTERS,
		description = "Rows done out of three, or the percentages Odin sends. Both are understood by every melody mod.",
		visibleIf = { sendProgress.value },
	)

	@JvmField
	val sendCoords = ToggleModuleSetting(
		id = "melody_send_coords",
		label = "Send coords",
		defaultValue = false,
		description = "Says where you are in party chat when you open melody, so the party knows which one is taken. " +
			"Odin's Melody Send Coords.",
	)

	@JvmField
	val enabled = ToggleModuleSetting(
		id = "melody_hud",
		label = "Melody HUD",
		defaultValue = true,
		description = "Shows a teammate's melody: the marker, the note moving under it, the row they are on, " +
			"and their class.",
	)

	@JvmField
	val live = ToggleModuleSetting(
		id = "melody_live",
		label = "Live through Odin's relay",
		defaultValue = false,
		description = "During the Goldor phase, joins the lobby's room on Odin's relay (ws.odtheking.com), the " +
			"way Odin does, so teammates' notes move live and yours are shared back. Sends your name and the " +
			"lobby's server id. Off, only party chat is read.",
		visibleIf = { enabled.value },
	)

	@JvmField
	val seconds = SliderModuleSetting(
		id = "melody_hud_seconds",
		label = "Stay",
		unit = "s",
		defaultValue = 5.0,
		min = 1.0,
		max = 15.0,
		step = 0.5,
		description = "How long a teammate stays up after the last thing their mod said in party chat.",
		visibleIf = { enabled.value },
	)

	@JvmField
	val playerLabel = DropdownModuleSetting(
		id = "melody_label",
		label = "Show player",
		options = listOf("Class", "Name", "Class & Name"),
		defaultIndex = LABEL_CLASS,
		description = "How the teammate is named under their melody. Odin's Show Player.",
		visibleIf = { enabled.value },
	)

	/** Listed on the Terminal Solver's card. */
	val settings: List<ModuleSetting> = listOf(section, sendProgress, progressFormat, sendCoords, enabled, live, seconds, playerLabel)

	val wanted: Boolean get() = TerminalSolver.module.enabled && (enabled.value || sendProgress.value || sendCoords.value)

	private val shown: Boolean get() = TerminalSolver.module.enabled && enabled.value

	private val relayWanted: Boolean get() = shown && live.value

	/**
	 * What's known of one teammate's melody: the first three from the relay (columns and rows of
	 * the terminal), [quarters] from party chat. Either can be missing.
	 */
	private class Melody {
		var marker: Int? = null
		var note: Int? = null
		var button: Int? = null
		var liveAt = 0L
		var quarters: Int? = null
		var chatAt = 0L

		val hasLive: Boolean get() = marker != null || note != null || button != null

		/** Rows done: what they said, or the row they're on, whichever is further. */
		val done: Int get() = maxOf(quarters ?: 0, (button ?: 1) - 1).coerceIn(0, ROWS)
	}

	/** Insertion-ordered, so two teammates on melody keep their places. Client thread only. */
	private val melodies = LinkedHashMap<String, Melody>()

	private var announcedRow = -1

	/** What was last sent to the relay, so only changes go out. */
	private var sentButton = -1
	private var sentMarker = -1
	private var sentNote = -1

	private val relay = RelaySocket { message ->
		// Parsed on the socket thread, applied on the client thread.
		val json = JsonParser.parseString(message).asJsonObject
		val name = json.get("username")?.asString ?: return@RelaySocket
		val kind = json.get("type")?.asInt ?: return@RelaySocket
		val slot = json.get("slot")?.asInt ?: 0
		Minecraft.getInstance().execute { onRelay(name, kind, slot) }
	}

	private var lastAttempt = 0L
	private var coreOpen = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(MelodyElement())
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		relay.shutdown()
		melodies.clear()
		announcedRow = -1
		coreOpen = false
		resetSent()
	}

	private fun resetSent() {
		sentButton = -1
		sentMarker = -1
		sentNote = -1
	}

	private fun inTerminals(): Boolean = DungeonLocation.inFloor7 && DungeonRun.inBoss

	private fun self(): String? = Minecraft.getInstance().player?.name?.string

	/**
	 * Keeps the relay open during the Goldor phase. Goldor's first line opens it, as with Odin;
	 * this covers joining late or reconnecting.
	 */
	fun tick() {
		if (!relayWanted || !inTerminals()) {
			if (relay.connected || relay.connecting) relay.shutdown()
			return
		}
		if (coreOpen || Floor7.phase != 3 || relay.connected || relay.connecting) return
		if (System.currentTimeMillis() - lastAttempt < RETRY_MILLIS) return
		connect()
	}

	private fun connect() {
		val lobby = SkyblockLocation.lobbyId() ?: return
		lastAttempt = System.currentTimeMillis()
		relay.connect(RELAY_URL + lobby)
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !wanted || !inTerminals()) return
		try {
			onLine(message.string.replace(FORMATTING, "").trim())
		} catch (_: RuntimeException) {
			// A line not understood is ignored, never a disconnect.
		}
	}

	private fun onLine(line: String) {
		when (line) {
			GOLDOR_START -> {
				coreOpen = false
				if (relayWanted) connect()
				return
			}
			CORE_OPENING -> {
				coreOpen = true
				relay.shutdown()
				melodies.clear()
				return
			}
		}
		if (!shown) return

		finished.find(line)?.let {
			melodies.remove(it.groupValues[1])
			return
		}

		val (name, said) = partyLine.find(line)?.destructured ?: return
		if (name == self()) return

		val quarters = fraction.find(said)?.let { match ->
			val done = match.groupValues[1].toInt()
			if (match.groupValues[2] == "3") done else Math.round(done * 3 / 4.0).toInt()
		} ?: percent.find(said)?.groupValues?.get(1)?.toIntOrNull()?.let { Math.round(it * 3 / 100.0).toInt() }
		val mentionsMelody = said.contains("melody", ignoreCase = true)

		// A number only counts as melody if the line says so, or this player is already known to be
		// on it.
		if (quarters == null && !mentionsMelody) return
		if (quarters != null && !mentionsMelody && name !in melodies) return

		val melody = melodies.getOrPut(name) { Melody() }
		melody.quarters = quarters ?: melody.quarters ?: 0
		melody.chatAt = System.currentTimeMillis()
	}

	private fun onRelay(name: String, kind: Int, slot: Int) {
		if (name == self() || !inTerminals()) return
		// Only the party's own; the room is the whole lobby's.
		if (!DungeonTeam.isTeammate(name)) return

		if (kind == KIND_CLOSED) {
			melodies[name]?.let {
				it.marker = null
				it.note = null
				it.button = null
			}
			return
		}

		val melody = melodies.getOrPut(name) { Melody() }
		when (kind) {
			KIND_BUTTON -> melody.button = slot.takeIf { it in 1..ROWS }
			KIND_MARKER -> melody.marker = slot.takeIf { it in 0 until COLUMNS }
			KIND_NOTE -> melody.note = slot.takeIf { it in 0 until COLUMNS }
			else -> return
		}
		melody.liveAt = System.currentTimeMillis()
	}

	private fun sendToRelay(kind: Int, slot: Int) {
		val name = self() ?: return
		// Names are letters, digits and underscores, so no escaping is needed.
		relay.send("""{"username":"$name","type":$kind,"slot":$slot}""")
	}

	private fun ownMelodyCounts(): Boolean = Minecraft.getInstance().gui.screen() !is TermSimScreen

	/** A melody opening resets its row count, so a second melody is announced from the start too. */
	fun onTerminalOpened() {
		announcedRow = -1
		resetSent()
		if (TerminalSolver.module.enabled && sendCoords.value && ownMelodyCounts()) {
			val at = Minecraft.getInstance().player?.blockPosition() ?: return
			say("x: ${at.x}, y: ${at.y}, z: ${at.z}")
		}
	}

	/** Your melody closing, done or not, removes you from everyone's HUD. */
	fun onTerminalClosed() {
		if (relay.connected && ownMelodyCounts()) sendToRelay(KIND_CLOSED, 0)
		resetSent()
	}

	/**
	 * Your own melody's board as slot numbers (-1 for absent): the marker in the top row, the note
	 * under it, and the lit button whose row is the progress. Called several times a second, so
	 * each part checks for a change first.
	 */
	fun onOwnBoard(marker: Int, note: Int, button: Int) {
		if (button >= 0) onOwnRow(button / 9)
		if (!relay.connected || !ownMelodyCounts()) return

		if (button >= 0 && button / 9 != sentButton) {
			sentButton = button / 9
			sendToRelay(KIND_BUTTON, sentButton)
		}
		columnOf(marker)?.takeIf { it != sentMarker }?.let {
			sentMarker = it
			sendToRelay(KIND_MARKER, it)
		}
		columnOf(note)?.takeIf { it != sentNote }?.let {
			sentNote = it
			sendToRelay(KIND_NOTE, it)
		}
	}

	/** Which of the five note columns a slot is in, as the relay numbers them. */
	private fun columnOf(slot: Int): Int? {
		if (slot < 0) return null
		val column = slot % 9 - 1
		return column.takeIf { it in 0 until COLUMNS }
	}

	/** The row being played, 1 to 3. Only announced forwards, so a resent board doesn't say "0/3" midway. */
	private fun onOwnRow(row: Int) {
		if (row <= announcedRow || row !in 1..ROWS) return
		announcedRow = row

		if (!TerminalSolver.module.enabled || !sendProgress.value) return
		// The simulator's melody is nobody else's business.
		if (!ownMelodyCounts()) return

		val done = row - 1
		say(if (progressFormat.selectedIndex == FORMAT_QUARTERS) "Melody $done/$ROWS" else "Melody ${Math.round(done * 100 / 3.0)}%")
	}

	/** A party chat line, or a preview under `/cryptic debug party`. Only in the boss. */
	private fun say(line: String) {
		val client = Minecraft.getInstance()
		if (DebugOverrides.previewPartyCommands) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Would send: §f/pc $line"))
			return
		}
		if (!inTerminals()) return
		client.connection?.sendCommand("pc $line")
	}

	/** Whoever has something recent enough to show. */
	private fun current(): List<Pair<String, Melody>> {
		if (melodies.isEmpty()) return emptyList()
		val now = System.currentTimeMillis()
		val stay = (seconds.value * 1000).toLong()
		melodies.values.forEach {
			if (it.hasLive && now - it.liveAt > LIVE_MILLIS) {
				it.marker = null
				it.note = null
				it.button = null
			}
			if (it.quarters != null && now - it.chatAt > stay) it.quarters = null
		}
		melodies.entries.removeIf { !it.value.hasLive && it.value.quarters == null }
		return melodies.map { it.key to it.value }
	}

	private val EXAMPLE = Melody().apply {
		marker = 4
		note = 2
		button = 2
		quarters = 1
	}

	fun describe(): List<String> {
		val lines = mutableListOf(
			"§8[Cryptic] §7Melody relay: " + when {
				relay.connected -> "§aconnected"
				relay.connecting -> "§econnecting"
				else -> "§cnot connected"
			} + "§7, lobby §f${SkyblockLocation.lobbyId() ?: "unknown"}§7, live ${if (live.value) "§aon" else "§coff"}",
		)
		melodies.forEach { (name, it) ->
			lines += "§8[Cryptic] §7  $name: marker ${it.marker}, note ${it.note}, row ${it.button}, " +
				"said ${it.quarters}/$ROWS"
		}
		return lines
	}

	private class MelodyElement : HudElement("melody_hud", "Melody HUD", 0.44, 0.58) {
		override val layer get() = imicro.cryptic.gui.ModuleCategory.FLOOR_7

		private val font get() = Minecraft.getInstance().font

		/** A square, the gap between two, and so the step. */
		private val square = 7
		private val gap = 2
		private val step = square + gap
		private val gridWidth = COLUMNS * step - gap
		private val gridHeight = 2 * step - gap

		/** The row number is drawn at twice the size, to fill the grid's height. */
		private val numberScale = 2

		override val width: Int get() = maxOf(gridWidth + 4 + font.width("4") * numberScale, font.width(label(labelParts(EXAMPLE_NAME, EXAMPLE))))
		override val height: Int get() = blockHeight(EXAMPLE) * maxOf(1, current().size)

		override fun isVisible(): Boolean =
			shown && (DebugOverrides.sampleHudValues || (inTerminals() && current().isNotEmpty()))

		override fun showInEditor(): Boolean = shown

		override fun render(context: GuiGraphicsExtractor) {
			if (DebugOverrides.sampleHudValues && melodies.isEmpty()) return draw(context, 0, EXAMPLE_NAME, EXAMPLE)
			var y = 0
			current().forEach { (name, melody) ->
				draw(context, y, name, melody)
				y += blockHeight(melody)
			}
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 0, EXAMPLE_NAME, EXAMPLE)

		private fun blockHeight(melody: Melody): Int =
			(if (melody.hasLive) gridHeight + 3 else 0) + font.lineHeight + 4

		private fun label(parts: List<Pair<String, Int>>): String = parts.joinToString("") { it.first }

		/** "ARCHER has melody! 1/3", the class in its colour, or the name when unknown. */
		private fun labelParts(name: String, melody: Melody): List<Pair<String, Int>> {
			val dungeonClass = if (name == EXAMPLE_NAME) DungeonClass.ARCHER else DungeonTeam.classOf(name)
			val known = dungeonClass?.takeIf { it != DungeonClass.UNKNOWN }
			val color = known?.let { 0xFF000000.toInt() or (ClassColors.getClassColor(it) and 0xFFFFFF) } ?: GREY
			val shownName = if (name == EXAMPLE_NAME) "Steve" else name
			val className = known?.name?.uppercase(Locale.ROOT)
			val who: List<Pair<String, Int>> = when {
				className == null -> listOf(shownName to GREY)
				playerLabel.selectedIndex == LABEL_CLASS -> listOf(className to color)
				playerLabel.selectedIndex == LABEL_NAME -> listOf(shownName to color)
				else -> listOf(shownName to color, " (" to GREY, className to color, ")" to GREY)
			}
			return who + listOf(" has melody! " to PURPLE, "${melody.done}/$ROWS" to PURPLE)
		}

		private fun draw(context: GuiGraphicsExtractor, top: Int, name: String, melody: Melody) {
			var y = top
			if (melody.hasLive) {
				drawGrid(context, y, melody)
				y += gridHeight + 3
			}

			val parts = labelParts(name, melody)
			var x = (width - font.width(label(parts))) / 2
			for ((text, color) in parts) {
				context.text(font, text, x, y, color)
				x += font.width(text)
			}
		}

		/** The marker's row, the note's row, and the row number: the terminal shrunk to what matters. */
		private fun drawGrid(context: GuiGraphicsExtractor, top: Int, melody: Melody) {
			val numberWidth = font.width("4") * numberScale
			val left = (width - (gridWidth + 4 + numberWidth)) / 2

			melody.marker?.let { drawSquare(context, left + it * step, top, PURPLE) }
			for (column in 0 until COLUMNS) {
				drawSquare(context, left + column * step, top + step, if (column == melody.note) GREEN else WHITE)
			}

			val button = melody.button ?: return
			val pose = context.pose()
			pose.pushMatrix()
			pose.translate((left + gridWidth + 4).toFloat(), (top + (gridHeight - font.lineHeight * numberScale) / 2 + 1).toFloat())
			pose.scale(numberScale.toFloat(), numberScale.toFloat())
			context.text(font, button.toString(), 0, 0, WHITE)
			pose.popMatrix()
		}

		/** A square with the same drop shadow as text. */
		private fun drawSquare(context: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
			context.fill(x + 1, y + 1, x + square + 1, y + square + 1, shadowOf(color))
			context.fill(x, y, x + square, y + square, color)
		}

		/** The shadow the font draws under a colour: a quarter of its brightness. */
		private fun shadowOf(color: Int): Int {
			val r = (color shr 16 and 0xFF) / 4
			val g = (color shr 8 and 0xFF) / 4
			val b = (color and 0xFF) / 4
			return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
		}

		private companion object {
			const val EXAMPLE_NAME = "\u0000example"
		}
	}
}
