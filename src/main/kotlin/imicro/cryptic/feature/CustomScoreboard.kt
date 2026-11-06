package imicro.cryptic.feature

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TabModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import imicro.cryptic.render.RoundRect
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import java.nio.file.Files
import java.time.LocalTime
import java.util.Locale
import java.util.Optional

/**
 * The sidebar rebuilt as a movable HUD element, from a list of lines you add, remove and order
 * (its Lines page): Hypixel's kinds of sidebar line, lines taken from the tab list (bank, soulflow,
 * pet, powders, events and more), the real time, blank lines and a footer of your own. Times,
 * lobby codes, seasons and numbers can be reformatted, and it has its own title, background, border
 * and rounded corners. The vanilla sidebar is hidden while it shows.
 *
 * Settings can be copied from meowdding's CustomScoreboard, whose ideas these are (LGPL-2.1); none
 * of its code is here. Line and tab list formats are Hypixel's.
 */
object CustomScoreboard {
	private val FORMATTING = Regex("§.")

	private const val TITLE_HYPIXEL = 0
	private const val TITLE_CUSTOM = 1
	private const val TITLE_NONE = 2

	private const val ALIGN_LEFT = 0
	private const val ALIGN_CENTRE = 1

	/** The area icons Hypixel puts on the location line (the Rift has its own). */
	private const val AREA_ICON = 0xE067
	private const val RIFT_AREA_ICON = 0xE020

	/** Where a line's text comes from. */
	enum class Source { SIDEBAR, TAB, CRYPTIC }

	/**
	 * One kind of line for the Lines list. [id] is what profiles save; [example] is how it reads.
	 * Sidebar lines are found by their text; tab list lines by [tabLabel].
	 */
	enum class Line(val id: String, val label: String, val example: String, val source: Source, val tabLabel: String? = null) {
		BLANK("blank", "Blank line", "", Source.CRYPTIC),
		LOBBY("lobby", "Server and date", "11/05/26 m42AB", Source.SIDEBAR),
		DATE("date", "SkyBlock date", "Summer 16th", Source.SIDEBAR),
		TIME("time", "Time", "21:39", Source.SIDEBAR),
		ISLAND("island", "Island", "Hub", Source.SIDEBAR),
		AREA("area", "Area", "Village", Source.SIDEBAR),
		PURSE("purse", "Purse", "Purse: 12,546,978", Source.SIDEBAR),
		BANK("bank", "Bank", "Bank: 931M/0", Source.TAB, "Bank"),
		MOTES("motes", "Motes", "Motes: 5,200", Source.SIDEBAR),
		BITS("bits", "Bits", "Bits: 44,037", Source.SIDEBAR),
		COPPER("copper", "Copper", "Copper: 1,800", Source.SIDEBAR),
		SOWDUST("sowdust", "Sowdust", "Sowdust: 250", Source.SIDEBAR),
		KERNELS("kernels", "Kernels", "Kernels: 40", Source.SIDEBAR),
		GEMS("gems", "Gems", "Gems: 300", Source.TAB, "Gems"),
		HEAT("heat", "Heat", "Heat: 12", Source.SIDEBAR),
		COLD("cold", "Cold", "Cold: -5", Source.SIDEBAR),
		NORTH_STARS("north_stars", "North Stars", "North Stars: 90", Source.SIDEBAR),
		SOULFLOW("soulflow", "Soulflow", "Soulflow: 1,707", Source.TAB, "Soulflow"),
		OBJECTIVE("objective", "Objective", "Objective / Talk to the Elder", Source.SIDEBAR),
		SLAYER("slayer", "Slayer quest", "Slayer Quest / Voidgloom Seraph IV", Source.SIDEBAR),
		EVENTS("events", "Sidebar events and the rest", "Spooky Festival 12:30", Source.SIDEBAR),
		TAB_EVENT("tab_event", "Tab list event", "Event: Year of the Witch / Ends In: 3d", Source.TAB),
		POWDER("powder", "Powder", "Mithril: 903,803 / Gemstone / Glacite", Source.TAB),
		PARTY("party", "Party", "Party (3)", Source.TAB, "Party"),
		PROFILE("profile", "Profile", "Profile: Mango", Source.TAB, "Profile"),
		LEVEL("level", "SkyBlock level", "SB Level: [247]", Source.TAB, "SB Level"),
		PET("pet", "Pet", "Pet: [Lvl 100] Golden Dragon", Source.TAB, "Pet"),
		COOKIE("cookie", "Cookie buff", "Cookie Buff: 3d 2h", Source.TAB, "Cookie Buff"),
		GOD_POTION("god_potion", "God potion", "God Potion: 1d 4h", Source.TAB, "God Potion"),
		MAYOR("mayor", "Mayor", "Mayor: Diana", Source.TAB, "Mayor"),
		PLAYERS("players", "Players in the lobby", "Players (24)", Source.TAB, "Players"),
		MORE_TAB("more_tab", "More from the tab list", "the labels set on the Text tab", Source.TAB),
		REAL_TIME("real_time", "Real time", "Real time: 21:39", Source.CRYPTIC),
		WEBSITE("website", "Website", "www.hypixel.net", Source.SIDEBAR),
		FOOTER("footer", "Your footer", "the footer set on the Text tab", Source.CRYPTIC),
		;

		companion object {
			val byId: Map<String, Line> = entries.associateBy { it.id }
		}
	}

	/** The lines to begin with: the layout of CustomScoreboard as set up here. */
	private val DEFAULT_LINES = listOf(
		Line.BLANK, Line.DATE, Line.TIME, Line.ISLAND, Line.AREA, Line.BLANK,
		Line.PURSE, Line.BANK, Line.MOTES, Line.BITS, Line.COPPER, Line.SOWDUST, Line.KERNELS,
		Line.HEAT, Line.COLD, Line.NORTH_STARS, Line.SOULFLOW, Line.BLANK,
		Line.OBJECTIVE, Line.SLAYER, Line.EVENTS, Line.TAB_EVENT, Line.POWDER, Line.PARTY,
	)

	private val SERVER = Regex("""^\d\d/\d\d/\d\d\b""")
	private val DATE = Regex("""^(?:Early |Late )?(Spring|Summer|Autumn|Winter) \d+(?:st|nd|rd|th)$""")
	private val TIME = Regex("""^\d{1,2}:\d\d(?:am|pm)\b""")
	private val WEBSITE = Regex("""hypixel\.net$""")
	private val CURRENCY = Regex("""^(?:Purse|Piggy|Bits|Copper|Motes|North Stars|Gems|Bank|Pelts|Fragrance|Gold|Heat|Mana|Sowdust|Cold|Kernels|Soulflow|Powder|Mithril|Gemstone|Glacite): """)

	/** Sidebar lines that start with a label, and the list entry each is. */
	private val LABELLED = listOf(
		"Purse: " to Line.PURSE, "Piggy: " to Line.PURSE, "Bits: " to Line.BITS, "Motes: " to Line.MOTES,
		"Copper: " to Line.COPPER, "Sowdust: " to Line.SOWDUST, "Kernels: " to Line.KERNELS,
		"North Stars: " to Line.NORTH_STARS, "Heat: " to Line.HEAT, "Cold: " to Line.COLD,
	)

	private val SEASON_COLOURS = mapOf("Spring" to "§a", "Summer" to "§e", "Autumn" to "§6", "Winter" to "§b")

	/**
	 * While a line is edited each § code is one stand-in character, so a code's own digit (the 7
	 * of §7) is never read as part of a time or a number.
	 */
	private const val CODE_BASE = 0xF8C0
	private const val CODE_CHARS = "0123456789abcdefklmnor"
	private val CODE = "[${Char(CODE_BASE)}-${Char(CODE_BASE + CODE_CHARS.length - 1)}]"
	private val SECTION_CODE = Regex("§([0-9a-fk-or])", RegexOption.IGNORE_CASE)
	private val NOT_A_CODE = Regex("§[^0-9a-fk-or]", RegexOption.IGNORE_CASE)

	private fun pack(coded: String): String =
		SECTION_CODE.replace(coded) { Char(CODE_BASE + CODE_CHARS.indexOf(it.groupValues[1].lowercase())).toString() }

	private fun unpack(packed: String): String = buildString {
		for (ch in packed) {
			if (ch.code in CODE_BASE until CODE_BASE + CODE_CHARS.length) append('§').append(CODE_CHARS[ch.code - CODE_BASE]) else append(ch)
		}
	}

	// In lines with their codes packed.
	private val TWELVE_HOUR = Regex("""(\d{1,2}):(\d\d)(am|pm)""")
	private val LOBBY = Regex("""(\d\d/\d\d/\d\d)(?:\s|$CODE)+\S+""")
	private val GAIN = Regex("""\s*$CODE*\([+-][\d,.]+\)""")
	private val BIG_NUMBER = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d{4,}(?:\.\d+)?""")
	private val LEADING_SPACE = Regex("""^($CODE*)\s+""")
	private val TRAILING_SPACE = Regex("""\s+($CODE*)$""")

	/**
	 * What sits between a number's digits where Hypixel split the line: codes, and the invisible
	 * marker it adds to keep each line unique (an emoji-like symbol). Anything not a letter, digit,
	 * space or ordinary punctuation.
	 */
	private val SPLIT_NUMBER = Regex("""(?<=[\d,.])(?:$CODE|[^\p{L}\p{N}\s(),.+\-/:])+(?=[\d,.])""")

	// ---- Text ----------------------------------------------------------------------------------

	private val linesTab = TabModuleSetting("lines_tab", "Text")

	/** The Lines page, saved with the profile: line ids top to bottom ("blank" can repeat). */
	@JvmField
	val lineOrder = TextModuleSetting(
		id = "line_order",
		label = "Line order",
		defaultValue = DEFAULT_LINES.joinToString(",") { it.id },
		maxLength = 2048,
		visibleIf = { false },
	)

	/** Set once CustomScoreboard's settings have been copied in on their own, so it happens once. */
	@JvmField
	val copiedOnce = ToggleModuleSetting(id = "copied_customscoreboard", label = "Copied", defaultValue = false, visibleIf = { false })

	@JvmField
	val tidyBlanks = ToggleModuleSetting(
		id = "tidy_blanks",
		label = "Tidy blank lines",
		defaultValue = true,
		description = "Never two blank lines in a row, and none at the bottom, when lines between them have nothing to show.",
	)

	@JvmField
	val extraLabels = TextModuleSetting(
		id = "extra_labels",
		label = "More from the tab list",
		maxLength = 128,
		hint = "Motes, Copper, Pelts",
		description = "Any other tab list lines, by the words before their colon, separated by commas. Shown where \"More from the tab list\" is on the Lines page.",
	)

	@JvmField
	val titleMode = DropdownModuleSetting(
		id = "title_mode",
		label = "Title",
		options = listOf("Hypixel's", "Custom", "None"),
		defaultIndex = TITLE_HYPIXEL,
	)

	@JvmField
	val customTitle = TextModuleSetting(
		id = "custom_title",
		label = "Custom title",
		defaultValue = "&e&lSKYBLOCK",
		maxLength = 48,
		description = "& colour codes work.",
		visibleIf = { titleMode.selectedIndex == TITLE_CUSTOM },
	)

	@JvmField
	val footer = TextModuleSetting(
		id = "footer",
		label = "Footer",
		maxLength = 48,
		hint = "&ewww.hypixel.net",
		description = "Your own line, & colour codes and all, shown where \"Your footer\" is on the Lines page. Empty for none.",
	)

	// ---- Format --------------------------------------------------------------------------------

	private val formatTab = TabModuleSetting("format_tab", "Format")

	@JvmField
	val time24 = ToggleModuleSetting(id = "time_24", label = "24-hour time", defaultValue = true, description = "9:40pm becomes 21:40.")

	@JvmField
	val colouredSeason = ToggleModuleSetting(
		id = "coloured_season",
		label = "Coloured season",
		defaultValue = true,
		description = "The SkyBlock date in its season's colour: spring green, summer yellow, autumn gold, winter aqua.",
	)

	@JvmField
	val hideLobby = ToggleModuleSetting(
		id = "hide_lobby",
		label = "Hide lobby code",
		defaultValue = false,
		description = "Keeps the date on the server line and drops the lobby's name (m42AB).",
	)

	@JvmField
	val shortNumbers = ToggleModuleSetting(
		id = "short_numbers",
		label = "Short numbers",
		defaultValue = false,
		description = "Purse, bits, bank, gems and other amounts as 12.5M instead of 12,546,978.",
	)

	@JvmField
	val hideGains = ToggleModuleSetting(
		id = "hide_gains",
		label = "Hide purse changes",
		defaultValue = false,
		description = "Drops the (+5) Hypixel puts after your purse when coins come in.",
	)

	// ---- Look ----------------------------------------------------------------------------------

	private val lookTab = TabModuleSetting("look_tab", "Look")

	@JvmField
	val titleAlign = DropdownModuleSetting(id = "title_align", label = "Align title", options = listOf("Left", "Centre", "Right"), defaultIndex = ALIGN_CENTRE)

	@JvmField
	val align = DropdownModuleSetting(id = "align", label = "Align lines", options = listOf("Left", "Centre", "Right"), defaultIndex = ALIGN_LEFT)

	@JvmField
	val shadow = ToggleModuleSetting(id = "shadow", label = "Text shadow", defaultValue = true)

	@JvmField
	val padding = SliderModuleSetting(id = "padding", label = "Padding", unit = "px", defaultValue = 5.0, min = 0.0, max = 20.0, step = 1.0)

	@JvmField
	val spacing = SliderModuleSetting(id = "spacing", label = "Line spacing", unit = "px", defaultValue = 0.0, min = 0.0, max = 10.0, step = 1.0)

	@JvmField
	val minWidth = SliderModuleSetting(id = "min_width", label = "Minimum width", unit = "px", defaultValue = 0.0, min = 0.0, max = 300.0, step = 5.0)

	@JvmField
	val backgroundColor = ColorModuleSetting(id = "background", label = "Background", defaultRgb = 0x000000, supportsAlpha = true, defaultAlpha = 0x55)

	@JvmField
	val corners = SliderModuleSetting(id = "corners", label = "Rounded corners", unit = "px", defaultValue = 5.0, min = 0.0, max = 20.0, step = 1.0)

	@JvmField
	val border = ToggleModuleSetting(id = "border", label = "Border", defaultValue = false)

	@JvmField
	val borderColor = ColorModuleSetting(id = "border_color", label = "Border color", defaultRgb = 0x36865B, supportsAlpha = true, inlineWith = border)

	@JvmField
	val borderWidth = SliderModuleSetting(
		id = "border_width", label = "Border width", unit = "px", defaultValue = 1.0, min = 1.0, max = 10.0, step = 1.0,
		visibleIf = { border.value },
	)

	@JvmField
	val accent = ToggleModuleSetting(id = "accent", label = "Top line", defaultValue = false, description = "A thin coloured line along the top edge.")

	@JvmField
	val accentColor = ColorModuleSetting(id = "accent_color", label = "Top line color", defaultRgb = 0x36865B, inlineWith = accent)

	// ---- Behaviour -----------------------------------------------------------------------------

	private val behaviourTab = TabModuleSetting("behaviour_tab", "Behaviour")

	@JvmField
	val onlySkyblock = ToggleModuleSetting(
		id = "only_skyblock",
		label = "Only on SkyBlock",
		defaultValue = true,
		description = "Leaves every other sidebar as the game draws it.",
	)

	@JvmField
	val hideInChat = ToggleModuleSetting(id = "hide_chat", label = "Hide while chat is open", defaultValue = false)

	@JvmField
	val hideWithF3 = ToggleModuleSetting(id = "hide_f3", label = "Hide with F3", defaultValue = false)

	@JvmField
	val hideWithTab = ToggleModuleSetting(id = "hide_tab", label = "Hide while the tab list shows", defaultValue = false)

	@JvmField
	val move = ButtonModuleSetting(
		id = "move",
		label = "Move and resize",
		action = {
			val parent = Minecraft.getInstance().gui.screen()
			Minecraft.getInstance().execute { Hud.openEditor(parent) }
		},
	)

	@JvmField
	val copySettings = ButtonModuleSetting(
		id = "copy_customscoreboard",
		label = "Copy CustomScoreboard's settings",
		action = { copyFromCustomScoreboard(announce = true) },
		visibleIf = { customScoreboardConfig().let(Files::exists) },
	)

	@JvmField
	val module = Module(
		id = "custom_scoreboard",
		name = "Custom Scoreboard",
		description = "A tidier, movable sidebar",
		category = ModuleCategory.VISUAL,
		settings = listOf(
			linesTab,
			lineOrder, copiedOnce, tidyBlanks, extraLabels,
			titleMode.withMore(customTitle),
			footer,
			formatTab,
			time24, colouredSeason, hideLobby, shortNumbers, hideGains,
			lookTab,
			titleAlign, align, shadow,
			padding.withMore(spacing, minWidth),
			backgroundColor, corners,
			border, borderColor, borderWidth,
			accent, accentColor,
			behaviourTab,
			onlySkyblock, hideInChat, hideWithF3, hideWithTab, move, copySettings,
		),
	)

	// ---- The list ------------------------------------------------------------------------------

	/** The lines, top to bottom. An older save's "location" is the island and area. */
	fun lines(): List<Line> {
		val out = ArrayList<Line>()
		for (raw in lineOrder.value.split(',')) {
			val token = raw.trim()
			if (token.isEmpty() || token.startsWith("!")) continue
			if (token == "location") {
				out += Line.ISLAND
				out += Line.AREA
				continue
			}
			val line = if (token.startsWith("blank")) Line.BLANK else Line.byId[token] ?: continue
			// Each line once, but as many blank lines as wanted.
			if (line == Line.BLANK || line !in out) out += line
		}
		return out
	}

	private fun save(list: List<Line>) {
		lineOrder.value = list.joinToString(",") { it.id }
	}

	/** The lines not in the list, to add; a blank line can always be added. */
	fun addable(): List<Line> {
		val shown = lines()
		return Line.entries.filter { it == Line.BLANK || it !in shown }
	}

	fun add(line: Line) = save(lines() + line)

	fun remove(index: Int) {
		val list = lines().toMutableList()
		if (index in list.indices) list.removeAt(index)
		save(list)
	}

	/** Moves the line at [index] one place up (-1) or down (1). */
	fun move(index: Int, by: Int) {
		val list = lines().toMutableList()
		val to = index + by
		if (index !in list.indices || to !in list.indices) return
		list.add(to, list.removeAt(index))
		save(list)
	}

	/** Moves the line at [from] to [to]; from dragging on the Lines page. */
	fun moveTo(from: Int, to: Int) {
		val list = lines().toMutableList()
		if (from !in list.indices) return
		val entry = list.removeAt(from)
		list.add(to.coerceIn(0, list.size), entry)
		save(list)
	}

	fun resetOrder() {
		lineOrder.value = lineOrder.defaultValue
	}

	// ---- Reading -------------------------------------------------------------------------------

	private class Row(val component: Component, val width: Int)

	/** Read once a tick; drawn every frame. */
	private var title: Row? = null
	private var rows: List<Row> = emptyList()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(ScoreboardElement())
	}

	val needsSkyblock: Boolean get() = module.enabled && onlySkyblock.value

	private fun applies(): Boolean = module.enabled && (!onlySkyblock.value || SkyblockLocation.onSkyblock)

	/** Whether the vanilla sidebar is hidden; from `HudMixin`. */
	@JvmStatic
	fun replacesSidebar(): Boolean = applies() && rows.isNotEmpty()

	/** Chat, F3 or the tab list in the way, as the player chose. */
	private fun hiddenNow(): Boolean {
		val client = Minecraft.getInstance()
		return (hideInChat.value && client.gui.screen() is ChatScreen) ||
			(hideWithF3.value && client.debugEntries.isOverlayVisible) ||
			(hideWithTab.value && client.options.keyPlayerList.isDown)
	}

	/** The objective vanilla would show: the team colour's slot first, then the sidebar. */
	private fun objective(client: Minecraft): Objective? {
		val level = client.level ?: return null
		val player = client.player ?: return null
		val scoreboard = level.scoreboard
		val team = scoreboard.getPlayersTeam(player.scoreboardName)
		val teamSlot = team?.color?.orElse(null)?.let { scoreboard.getDisplayObjective(it.displaySlot()) }
		return teamSlot ?: scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
	}

	fun tick(client: Minecraft) {
		// Once, after the profile has loaded: CustomScoreboard's setup, if it's installed here.
		if (!copiedOnce.value && client.player != null && Files.exists(customScoreboardConfig())) {
			copyFromCustomScoreboard(announce = false)
		}
		val objective = if (applies()) objective(client) else null
		if (objective == null) {
			rows = emptyList()
			title = null
			return
		}
		val scoreboard = objective.scoreboard
		val font = client.font
		val sidebar = scoreboard.listPlayerScores(objective)
			.filter { !it.isHidden }
			.sortedWith(compareByDescending<PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() })
			.take(15)
			.map { PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(it.owner()), it.ownerName()) as Component }

		val bySidebar = sort(sidebar)
		val tab = lazy { tabRows(client) }
		val out = ArrayList<Component?>()
		for (line in lines()) {
			when {
				line == Line.BLANK -> out += null
				line.source == Source.SIDEBAR -> bySidebar[line]?.let { out.addAll(it) }
				else -> out.addAll(built(line, tab))
			}
		}
		title = when (titleMode.selectedIndex) {
			TITLE_CUSTOM -> Component.literal(DungeonScore.colored(customTitle.value, "§e"))
			TITLE_NONE -> null
			else -> objective.displayName
		}?.let { Row(it, font.width(it)) }
		rows = tidy(out, title != null).map { Row(it, font.width(it)) }
	}

	/**
	 * Blank lines (null) as the list has them, tidied when wanted: never two in a row and none at
	 * the bottom, and none at the top unless there's a title above it.
	 */
	private fun tidy(lines: List<Component?>, titled: Boolean): List<Component> {
		if (!tidyBlanks.value) return lines.map { it ?: Component.empty() }
		val out = ArrayList<Component>(lines.size)
		var lastBlank = !titled
		for (line in lines) {
			if (line == null) {
				if (lastBlank) continue
				out += Component.empty()
				lastBlank = true
			} else {
				out += line
				lastBlank = false
			}
		}
		while (out.isNotEmpty() && out.last().string.isBlank()) out.removeAt(out.size - 1)
		return out
	}

	// ---- Colour codes --------------------------------------------------------------------------

	/** The sixteen colours with a § code, by their colour. */
	private val CODES = mapOf(
		0x000000 to '0', 0x0000AA to '1', 0x00AA00 to '2', 0x00AAAA to '3', 0xAA0000 to '4', 0xAA00AA to '5',
		0xFFAA00 to '6', 0xAAAAAA to '7', 0x555555 to '8', 0x5555FF to '9', 0x55FF55 to 'a', 0x55FFFF to 'b',
		0xFF5555 to 'c', 0xFF55FF to 'd', 0xFFFF55 to 'e', 0xFFFFFF to 'f',
	)

	/**
	 * A line written out with § codes, colours set as styles included, so it can be changed as text
	 * and shown again just as it was. Null when a colour has no § code (a hex colour).
	 */
	private fun legacy(component: Component): String? {
		val out = StringBuilder()
		var ok = true
		component.visit({ style: Style, text: String ->
			if (text.isNotEmpty()) {
				out.append("§r")
				style.color?.let { color ->
					val code = CODES[color.value and 0xFFFFFF]
					if (code == null) ok = false else out.append('§').append(code)
				}
				if (style.isBold) out.append("§l")
				if (style.isItalic) out.append("§o")
				if (style.isUnderlined) out.append("§n")
				if (style.isStrikethrough) out.append("§m")
				if (style.isObfuscated) out.append("§k")
				// Hypixel names each line with a code that isn't one (§j, §t), which shows as nothing and can
				// land inside a number; it goes, so nothing reads it as a letter.
				out.append(NOT_A_CODE.replace(text, ""))
			}
			Optional.empty<Unit>()
		}, Style.EMPTY)
		return if (ok) out.toString() else null
	}

	/** Spaces at either end taken off, keeping the codes before and after them. */
	private fun trimmed(coded: String): String = TRAILING_SPACE.replace(LEADING_SPACE.replace(coded, "$1"), "$1")

	/** A line with the chosen formats applied and its edge spaces trimmed, colours kept. */
	private fun formatted(line: Component, kind: Line?, plain: String): Component {
		var coded = pack(legacy(line) ?: return line)
		if (kind == Line.TIME && time24.value) {
			coded = TWELVE_HOUR.replace(coded) { match ->
				val hour = match.groupValues[1].toInt() % 12 + if (match.groupValues[3] == "pm") 12 else 0
				String.format(Locale.ROOT, "%02d:%s", hour, match.groupValues[2])
			}
		}
		if (kind == Line.DATE && colouredSeason.value) {
			DATE.find(plain)?.let { coded = pack((SEASON_COLOURS[it.groupValues[1]] ?: "") + plain) }
		}
		if (kind == Line.LOBBY && hideLobby.value) coded = LOBBY.replace(coded) { it.groupValues[1] }
		if (kind == Line.PURSE && hideGains.value) coded = GAIN.replace(coded, "")
		if (shortNumbers.value && CURRENCY.containsMatchIn(plain)) {
			// Hypixel splits a long line in two, often inside a big number, with codes at the join.
			coded = BIG_NUMBER.replace(SPLIT_NUMBER.replace(coded, "")) { short(it.value) }
		}
		return Component.literal(unpack(trimmed(coded)))
	}

	private fun short(number: String): String {
		val value = number.replace(",", "").toDoubleOrNull() ?: return number
		return when {
			value >= 1e12 -> trim(value / 1e12) + "T"
			value >= 1e9 -> trim(value / 1e9) + "B"
			value >= 1e6 -> trim(value / 1e6) + "M"
			value >= 1e3 -> trim(value / 1e3) + "k"
			else -> number
		}
	}

	private fun trim(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

	/**
	 * `/cryptic debug sidebar`: each sidebar line as it arrives, piece by piece with its colour,
	 * any character outside plain ASCII written as \uXXXX, then as this shows it.
	 */
	fun dumpSidebar(): List<String> {
		val client = Minecraft.getInstance()
		val objective = objective(client) ?: return listOf("No sidebar.")
		val scoreboard = objective.scoreboard
		val lines = scoreboard.listPlayerScores(objective)
			.filter { !it.isHidden }
			.sortedWith(compareByDescending<PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() })
			.take(15)
		fun escape(text: String) = buildString {
			text.codePoints().forEach { cp ->
				when {
					cp == '§'.code -> append('&')
					cp in 0x20..0x7E -> append(cp.toChar())
					else -> append(String.format(Locale.ROOT, "\\u%04X", cp))
				}
			}
		}
		val out = ArrayList<String>()
		for ((i, entry) in lines.withIndex()) {
			val line = PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())
			val pieces = StringBuilder()
			line.visit({ style: Style, text: String ->
				if (text.isNotEmpty()) pieces.append("[").append(style.color?.serialize() ?: "-").append("|").append(escape(text)).append("]")
				Optional.empty<Unit>()
			}, Style.EMPTY)
			out += "$i owner=${escape(entry.owner())} $pieces"
			val plain = line.string.replace(FORMATTING, "").trim()
			out += "$i shown: ${escape(formatted(line, null, plain).string)}"
		}
		return out
	}

	// ---- Hypixel's sidebar ---------------------------------------------------------------------

	private fun hasAreaIcon(text: String) = text.any { it == '⏣' || it == 'ф' || it.code == AREA_ICON || it.code == RIFT_AREA_ICON }

	/** The sidebar's lines sorted into the list's entries, each kept in the sidebar's order. */
	private fun sort(lines: List<Component>): Map<Line, List<Component>> {
		val out = LinkedHashMap<Line, MutableList<Component>>()
		// Blocks run from their heading to the next blank line.
		var block: Line? = null
		var previous: Line? = null
		for (line in lines) {
			val text = line.string.replace(FORMATTING, "").trim()
			if (text.isEmpty()) {
				block = null
				previous = null
				continue
			}
			val kind = when {
				text == "Objective" -> Line.OBJECTIVE
				text == "Slayer Quest" -> Line.SLAYER
				block != null -> block
				SERVER.containsMatchIn(text) -> Line.LOBBY
				DATE.matches(text) -> Line.DATE
				TIME.containsMatchIn(text) -> Line.TIME
				WEBSITE.containsMatchIn(text) -> Line.WEBSITE
				// Hypixel writes the island on one line and the area under it, each behind its icon.
				previous == Line.ISLAND && (text.first().isLetter() || text.first().code in 0xE000..0xF8FF || hasAreaIcon(text)) -> Line.AREA
				hasAreaIcon(text) -> Line.ISLAND
				else -> LABELLED.firstOrNull { text.startsWith(it.first) }?.second ?: Line.EVENTS
			}
			if (kind == Line.OBJECTIVE || kind == Line.SLAYER) block = kind
			previous = kind
			out.getOrPut(kind) { ArrayList() } += formatted(line, kind, text)
		}
		return out
	}

	// ---- Tab list and Cryptic's own lines ------------------------------------------------------

	private fun tabRows(client: Minecraft): List<Component> {
		val connection = client.connection ?: return emptyList()
		return connection.listedOnlinePlayers
			.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
			.mapNotNull { it.tabListDisplayName }
	}

	private fun plain(component: Component) = component.string.replace(FORMATTING, "").trim()

	/**
	 * The tab list row labelled [label], as shown there. A heading with nothing after its colon
	 * ("Pet:", "Cookie Buff") has its value on the next row, which is joined on.
	 */
	private fun tabLine(rows: List<Component>, label: String): Component? {
		val index = rows.indexOfFirst { plain(it).startsWith(label, ignoreCase = true) }
		if (index < 0) return null
		val row = rows[index]
		val after = plain(row).substring(label.length).trimStart(':').trim()
		if (after.isNotEmpty()) return formatted(row, null, plain(row))
		val next = rows.getOrNull(index + 1)?.takeIf { plain(it).isNotEmpty() } ?: return null
		val joined = Component.literal("§f$label: ").append(next.copy())
		return formatted(joined, null, plain(joined))
	}

	/** A tab list or Cryptic line's text; none when there's nothing to show. */
	private fun built(line: Line, tab: Lazy<List<Component>>): List<Component> = when (line) {
		Line.REAL_TIME -> {
			val now = LocalTime.now()
			val text = if (time24.value) {
				String.format(Locale.ROOT, "%02d:%02d", now.hour, now.minute)
			} else {
				String.format(Locale.ROOT, "%d:%02d%s", (now.hour + 11) % 12 + 1, now.minute, if (now.hour < 12) "am" else "pm")
			}
			listOf(Component.literal("§7Real time: §f$text"))
		}
		Line.FOOTER -> if (footer.value.isBlank()) emptyList() else listOf(Component.literal(DungeonScore.colored(footer.value, "§e")))
		// Each powder on its own line, as the tab list has it under "Powders:".
		Line.POWDER -> listOf("Mithril", "Gemstone", "Glacite").mapNotNull { name ->
			tab.value.firstOrNull { plain(it).startsWith("$name:") }?.let { formatted(it, null, plain(it)) }
		}
		Line.TAB_EVENT -> {
			// The event and, under it, when it ends or starts.
			val rows = tab.value
			val at = rows.indexOfFirst { plain(it).startsWith("Event:") }
			if (at < 0) {
				emptyList()
			} else {
				listOfNotNull(
					formatted(rows[at], null, plain(rows[at])),
					rows.getOrNull(at + 1)?.takeIf { plain(it).startsWith("Ends In") || plain(it).startsWith("Starts In") }
						?.let { formatted(it, null, plain(it)) },
				)
			}
		}
		Line.MORE_TAB -> extraLabels.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { tabLine(tab.value, it) }
		else -> line.tabLabel?.let { tabLine(tab.value, it) }?.let { listOf(it) }.orEmpty()
	}

	// ---- Copying CustomScoreboard's settings ---------------------------------------------------

	private fun customScoreboardConfig() = FabricLoader.getInstance().configDir.resolve("customscoreboard").resolve("config.jsonc")

	/** Its lines, by the names in its config, as this list's. */
	private val CS_LINES = mapOf(
		"SEPARATOR" to listOf(Line.BLANK), "DATE" to listOf(Line.DATE), "TIME" to listOf(Line.TIME),
		"ISLAND" to listOf(Line.ISLAND), "AREA" to listOf(Line.AREA), "LOBBY" to listOf(Line.LOBBY),
		"PURSE" to listOf(Line.PURSE), "BANK" to listOf(Line.BANK), "MOTES" to listOf(Line.MOTES), "BITS" to listOf(Line.BITS),
		"COPPER" to listOf(Line.COPPER), "SOWDUST" to listOf(Line.SOWDUST), "KERNELS" to listOf(Line.KERNELS),
		"GEMS" to listOf(Line.GEMS), "HEAT" to listOf(Line.HEAT), "COLD" to listOf(Line.COLD),
		"NORTH_STARS" to listOf(Line.NORTH_STARS), "SOULFLOW" to listOf(Line.SOULFLOW),
		"OBJECTIVE" to listOf(Line.OBJECTIVE), "SLAYER" to listOf(Line.SLAYER),
		"EVENTS" to listOf(Line.EVENTS, Line.TAB_EVENT), "POWDER" to listOf(Line.POWDER), "PARTY" to listOf(Line.PARTY),
		"PROFILE" to listOf(Line.PROFILE), "SKYBLOCK_LEVEL" to listOf(Line.LEVEL), "PET" to listOf(Line.PET),
		"COOKIE_BUFF" to listOf(Line.COOKIE), "GOD_POTION" to listOf(Line.GOD_POTION), "MAYOR" to listOf(Line.MAYOR),
		"PLAYER_COUNT" to listOf(Line.PLAYERS), "IRL_TIME" to listOf(Line.REAL_TIME), "FOOTER" to listOf(Line.WEBSITE),
	)

	private fun alignment(name: String?) = when (name) {
		"START" -> ALIGN_LEFT
		"END" -> 2
		else -> ALIGN_CENTRE
	}

	/**
	 * Copies CustomScoreboard's setup from its config here: its lines in its order, title, footer,
	 * alignment, spacing, background, border, corners, time and number formats, when it hides, and
	 * where it sits. Saved at once.
	 */
	fun copyFromCustomScoreboard(announce: Boolean) {
		copiedOnce.value = true
		val result = runCatching {
			// JSON with comments: each comment is a line of its own.
			val text = Files.readAllLines(customScoreboardConfig()).filterNot { it.trim().startsWith("//") }.joinToString("\n")
			val root = JsonParser.parseString(text).asJsonObject
			fun JsonObject.bool(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean
			fun JsonObject.int(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asNumber?.toInt()
			fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
			fun JsonObject.firstText(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.asString.orEmpty()

			root.bool("hideWhenTab")?.let { hideWithTab.value = it }
			root.bool("hideWhenChat")?.let { hideInChat.value = it }
			root.bool("hideWhenF3")?.let { hideWithF3.value = it }
			root.bool("text_shadow")?.let { shadow.value = it }
			root.bool("outsideSkyBlock")?.let { onlySkyblock.value = !it }

			root.getAsJsonObject("customization")?.let { c ->
				c.getAsJsonArray("appearance")?.let { array ->
					val names = array.map { it.asString }
					lineOrder.value = names.flatMap { CS_LINES[it].orEmpty() }.joinToString(",") { it.id }
					if ("TITLE" !in names) titleMode.selectedIndex = TITLE_NONE
				}
				if ("TITLE" in (c.getAsJsonArray("appearance")?.map { it.asString } ?: listOf("TITLE"))) {
					if (c.bool("titleUseCustomText") == true && c.firstText("titleText").isNotBlank()) {
						titleMode.selectedIndex = TITLE_CUSTOM
						customTitle.value = c.firstText("titleText")
					} else {
						titleMode.selectedIndex = TITLE_HYPIXEL
					}
				}
				titleAlign.selectedIndex = alignment(c.str("titleAlignment"))
				align.selectedIndex = alignment(c.str("defaultTextAlignment"))
				if (c.bool("footerUseCustomText") == true) footer.value = c.firstText("footerText")
				c.int("lineSpacing")?.let { spacing.value = it.toDouble() }

				// Where it sits: its screen edges, at its scale.
				val element = Hud.byId("custom_scoreboard")
				val window = Minecraft.getInstance().window
				if (element != null && window.guiScaledWidth > 0) {
					c.get("scale")?.asDouble?.let { element.scale = it }
					// Anchored to that edge or centre, so it stays there as the board changes size.
					fun side(name: String?): Double? = when (name) {
						"LEFT", "TOP" -> 0.0
						"CENTER" -> 0.5
						"RIGHT", "BOTTOM" -> 1.0
						else -> null
					}
					side(c.str("horizontal_alignment"))?.let { element.x = it; element.anchorX = it }
					side(c.str("vertical_alignment"))?.let { element.y = it; element.anchorY = it }
					element.clampTo(window.guiScaledWidth, window.guiScaledHeight)
				}
			}

			root.getAsJsonObject("Background")?.let { b ->
				val enabled = b.bool("enabled") ?: true
				b.int("color")?.let { backgroundColor.argb = if (enabled) it else 0 }
				b.int("padding")?.let { padding.value = it.toDouble() }
				b.int("radius")?.let { corners.value = it.toDouble() }
				b.int("minWidth")?.let { minWidth.value = it.toDouble() }
				b.bool("borderEnabled")?.let { border.value = it }
				b.int("borderSize")?.let { borderWidth.value = it.coerceIn(1, 10).toDouble() }
				b.int("borderColorTopLeft")?.let { borderColor.argb = it }
			}

			root.getAsJsonObject("line_modification")?.let { m ->
				m.bool("time24hFormat")?.let { time24.value = it }
				m.bool("colored_month")?.let { colouredSeason.value = it }
				m.str("number_format")?.let { shortNumbers.value = it == "SHORT" }
				m.bool("showCurrencyGain")?.let { hideGains.value = !it }
				val tidyEnds = m.bool("separators_start_end") ?: true
				val tidyRuns = m.bool("consecutive_separator") ?: true
				tidyBlanks.value = tidyEnds || tidyRuns
			}
			accent.value = false
		}
		imicro.cryptic.config.ConfigManager.flush()
		if (announce || result.isFailure) {
			val message = result.fold(
				{ "Copied CustomScoreboard's lines and settings." },
				{ "Couldn't read CustomScoreboard's config: ${it.message}" },
			)
			Toasts.show("Custom Scoreboard", message, error = result.isFailure)
		}
	}

	// ---- Drawing -------------------------------------------------------------------------------

	private class ScoreboardElement : HudElement("custom_scoreboard", "Custom Scoreboard", 0.80, 0.32) {
		private val font get() = Minecraft.getInstance().font

		/** Each line in the list, shown with its example text, for the editor. */
		private fun example(): List<Row> {
			val out = lines().map { line -> if (line == Line.BLANK) null else Component.literal("§7" + line.example) }
			return tidy(out, titleMode.selectedIndex != TITLE_NONE).map { Row(it, font.width(it)) }
		}

		private fun pad() = padding.value.toInt()
		private fun lineHeight() = font.lineHeight + spacing.value.toInt()
		private fun edge() = if (border.value) borderWidth.value.toInt() else 0

		private fun bodyWidth(list: List<Row>, head: Row?): Int =
			maxOf(list.maxOfOrNull { it.width } ?: 0, head?.width ?: 0, 40, minWidth.value.toInt() - pad() * 2 - edge() * 2)

		private fun sizeOf(list: List<Row>, head: Row?): Pair<Int, Int> {
			val headRows = if (head != null) 1 else 0
			val w = bodyWidth(list, head) + pad() * 2 + edge() * 2
			val h = (list.size + headRows) * lineHeight() + pad() * 2 + edge() * 2 + headRows
			return w to h
		}

		private fun shownTitle(): Row? = if (rows.isNotEmpty()) title else exampleTitle()

		private fun exampleTitle(): Row? = when (titleMode.selectedIndex) {
			TITLE_NONE -> null
			TITLE_CUSTOM -> Component.literal(DungeonScore.colored(customTitle.value, "§e"))
			else -> Component.literal("§e§lSKYBLOCK")
		}?.let { Row(it, font.width(it)) }

		override val width: Int get() = sizeOf(rows.ifEmpty { example() }, shownTitle()).first
		override val height: Int get() = sizeOf(rows.ifEmpty { example() }, shownTitle()).second

		override fun isVisible(): Boolean =
			applies() && rows.isNotEmpty() && Minecraft.getInstance().player != null && !hiddenNow()

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) = draw(context, rows, title)

		override fun renderExample(context: GuiGraphicsExtractor) {
			if (rows.isNotEmpty()) render(context) else draw(context, example(), exampleTitle())
		}

		private fun draw(context: GuiGraphicsExtractor, list: List<Row>, head: Row?) {
			keepOnScreen(context)
			val pad = pad()
			val edge = edge()
			val inner = bodyWidth(list, head)
			val (totalWidth, totalHeight) = sizeOf(list, head)
			val radius = corners.value.toFloat()
			val w = totalWidth.toFloat()
			val h = totalHeight.toFloat()
			// Smooth, shader-drawn corners, as the menu's.
			if ((backgroundColor.argb ushr 24) != 0) RoundRect.fill(context, 0f, 0f, w, h, backgroundColor.argb, radius)
			if (edge > 0) RoundRect.outline(context, 0f, 0f, w, h, borderColor.argb, radius, edge.toFloat())
			if (accent.value) {
				val inset = maxOf(edge.toFloat(), radius * 0.6f)
				context.fill((inset).toInt(), edge, (w - inset).toInt(), edge + 1, accentColor.argb or (0xFF shl 24))
			}

			val left = edge + pad
			fun xFor(width: Int, alignment: Int) = when (alignment) {
				ALIGN_LEFT -> left
				ALIGN_CENTRE -> left + (inner - width) / 2
				else -> left + inner - width
			}
			var y = edge + pad
			if (head != null) {
				context.text(font, head.component, xFor(head.width, titleAlign.selectedIndex), y + 1, -1, shadow.value)
				y += lineHeight() + 1
			}
			for (row in list) {
				context.text(font, row.component, xFor(row.width, align.selectedIndex), y, -1, shadow.value)
				y += lineHeight()
			}
		}
	}
}
