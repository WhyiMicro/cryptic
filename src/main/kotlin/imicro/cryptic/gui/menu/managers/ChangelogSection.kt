package imicro.cryptic.gui.menu.managers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import imicro.cryptic.feature.UpdateChecker
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.render.RoundRect
import net.minecraft.util.Util
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Changelog: the GitHub release notes for the installed version, drawn from their Markdown, with a
 * link to every older release. A build newer than the last release shows that release's notes.
 */
class ChangelogSection : ManagerSection("Changelog") {
	init {
		ReleaseNotes.load()
	}

	/** Wrapped lines, kept until the width or the notes change. */
	private var laidOut: List<Group> = emptyList()
	private var laidOutFor: Any? = null

	override fun draw(x: Float, y: Float, width: Float): Float {
		val state = ReleaseNotes.state
		var cy = y
		cy += drawHeader(state, x, cy, width) + 14f
		if (state !is ReleaseNotes.State.Loaded) return cy - y

		val textW = width - PAD * 2
		val key = Triple(state, textW, UiText.pixelsPerUnit)
		if (laidOutFor != key) {
			laidOut = layout(state.blocks, textW)
			laidOutFor = key
		}
		for (group in laidOut) {
			if (group.title != null) {
				Sections.heading(ui, group.title, x, cy)
				cy += Sections.HEADING
			}
			val h = group.height + PAD
			if (cy < ui.area.bottom && cy + h > ui.area.y) {
				ui.card(Box(x, cy, width, h))
				for (line in group.lines) drawLine(line, x + PAD, cy + PAD / 2f)
			}
			cy += h + 14f
		}
		return cy - y
	}

	private fun drawHeader(state: ReleaseNotes.State?, x: Float, y: Float, width: Float): Float {
		val loaded = state as? ReleaseNotes.State.Loaded
		val title = "Cryptic " + short(loaded?.version ?: UpdateChecker.installed)
		val subtitle = when (state) {
			is ReleaseNotes.State.Loaded -> state.published?.let { "Released $it" } ?: "Release notes"
			is ReleaseNotes.State.Failed -> state.message
			else -> "Loading the release notes…"
		}

		var right = x + width - PAD
		val buttons = buildList {
			add("All releases" to { open(UpdateChecker.RELEASES_PAGE) })
			if (state is ReleaseNotes.State.Failed) add("Retry" to { ReleaseNotes.load() })
		}
		val headH = 76f
		val note = if (loaded != null && !loaded.exact) {
			"There are no notes for ${short(UpdateChecker.installed)} yet, so these are for ${short(loaded.version)}, the last release before it."
		} else null
		val noteLines = note?.let { UiText.wrap(it, 12f, width - PAD * 2) }.orEmpty()
		val h = headH + if (noteLines.isEmpty()) 0f else noteLines.size * 16f + 4f
		ui.card(Box(x, y, width, h))
		val buttonY = y + (headH - 34f) / 2f
		for ((label, action) in buttons) {
			val w = ui.buttonWidth(label)
			ui.button(Box(right - w, buttonY, w, 34f), label, primary = label == "Retry", action = action)
			right -= w + 8f
		}
		ui.text(title, x + PAD, y + 20f, 15f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		ui.text(ui.fit(subtitle, 12f, right - x - PAD - 8f), x + PAD, y + 42f, 12f, MenuStyle.MUTED)
		var ny = y + headH - 4f
		for (line in noteLines) {
			ui.text(line, x + PAD, ny, 12f, MenuStyle.MUTED)
			ny += 16f
		}
		return h
	}

	private fun drawLine(line: Line, x: Float, top: Float) {
		val y = top + line.y
		if (y > ui.area.bottom || y + LINE < ui.area.y) return
		if (line.bullet) {
			val dx = x + line.indent - BULLET_GAP + 1f
			RoundRect.fill(ui.graphics, dx, y + 6.5f, dx + 4f, y + 10.5f, MenuStyle.fade(MenuStyle.MUTED, ui.alpha), 2f)
		}
		for (run in line.runs) ui.text(run.text, x + line.indent + run.x, y + 2f, 12f, run.color, run.face)
	}

	private fun open(url: String) = Util.getPlatform().openUri(URI.create(url))

	private class Run(val x: Float, var text: String, val face: UiText.Face, val color: Int)

	private class Line(val y: Float, val indent: Float, val bullet: Boolean, val runs: List<Run>)

	private class Group(val title: String?, val lines: List<Line>, val height: Float)

	/** Splits the notes into a card per heading and wraps each block to [width]. */
	private fun layout(blocks: List<Block>, width: Float): List<Group> {
		val groups = ArrayList<Group>()
		var title: String? = null
		var lines = ArrayList<Line>()
		var y = 0f
		fun flush() {
			if (lines.isNotEmpty()) groups += Group(title, lines, y)
			lines = ArrayList()
			y = 0f
		}
		for (block in blocks) {
			when (block) {
				is Block.Heading -> {
					flush()
					title = block.text
				}
				is Block.Text -> {
					if (lines.isNotEmpty() && block.depth < 0) y += PARAGRAPH_GAP
					val indent = if (block.depth < 0) 0f else BULLET_GAP + block.depth * NEST
					for ((i, runs) in wrap(block.spans, width - indent).withIndex()) {
						lines += Line(y, indent, block.depth >= 0 && i == 0, runs)
						y += LINE
					}
					if (block.depth >= 0) y += BULLET_GAP_AFTER
				}
			}
		}
		flush()
		return groups
	}

	/** Word-wraps styled text; words that touch keep together, and a style change starts a run. */
	private fun wrap(spans: List<Span>, width: Float): List<List<Run>> {
		val lines = ArrayList<List<Run>>()
		var runs = ArrayList<Run>()
		var x = 0f
		for ((word, style, spaced) in words(spans)) {
			val face = if (style.bold) UiText.Face.MEDIUM else UiText.Face.REGULAR
			val color = if (style.bold || style.code) MenuStyle.TEXT else BODY
			val w = ui.width(word, 12f, face)
			var gap = if (spaced && x > 0f) ui.width(" ", 12f, face) else 0f
			if (x > 0f && x + gap + w > width) {
				lines += runs
				runs = ArrayList()
				x = 0f
				gap = 0f
			}
			val last = runs.lastOrNull()
			if (last != null && last.face == face && last.color == color) {
				last.text += (if (gap > 0f) " " else "") + word
			} else {
				runs += Run(x + gap, word, face, color)
			}
			x += gap + w
		}
		if (runs.isNotEmpty()) lines += runs
		return lines
	}

	private fun words(spans: List<Span>): List<Triple<String, Span, Boolean>> {
		val words = ArrayList<Triple<String, Span, Boolean>>()
		var spaced = false
		for (span in spans) {
			var start = 0
			val text = span.text
			for (i in 0..text.length) {
				if (i == text.length || text[i] == ' ') {
					if (i > start) {
						words += Triple(text.substring(start, i), span, spaced)
						spaced = false
					}
					if (i < text.length) spaced = true
					start = i + 1
				}
			}
		}
		return words
	}

	companion object {
		private const val PAD = 18f
		private const val LINE = 17f
		private const val PARAGRAPH_GAP = 8f
		private const val BULLET_GAP = 14f
		private const val BULLET_GAP_AFTER = 3f
		private const val NEST = 16f
		private val BODY = MenuStyle.TAB_HOVER

		/** "0.92.20-26.2" reads "0.92.20". */
		private fun short(version: String) = version.substringBefore('-')

		/** The card in Settings the Changelog page hangs from. */
		val module = Module(
			id = "changelog",
			name = "Changelog",
			description = "What's new in this version",
			category = ModuleCategory.MISC,
			supportsToggle = false,
		)
	}
}

/** A piece of a line in one style. */
internal class Span(val text: String, val bold: Boolean, val code: Boolean)

internal sealed interface Block {
	class Heading(val text: String) : Block

	/** A paragraph ([depth] -1) or a bullet nested [depth] deep. */
	class Text(val spans: List<Span>, val depth: Int) : Block
}

/** The installed version's release notes from GitHub, fetched once a session. */
internal object ReleaseNotes {
	sealed interface State {
		object Loading : State
		class Failed(val message: String) : State
		class Loaded(val version: String, val published: String?, val blocks: List<Block>, val exact: Boolean) : State
	}

	@Volatile
	var state: State? = null
		private set

	private val http: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
	}

	/** Fetches the notes unless they're loaded or on their way; a failure is tried again. */
	fun load() {
		val now = state
		if (now is State.Loading || now is State.Loaded) return
		state = State.Loading
		val installed = UpdateChecker.installed
		val request = HttpRequest.newBuilder(URI.create(UpdateChecker.API))
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "Cryptic/$installed")
			.GET()
			.build()
		http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
			state = runCatching {
				if (error != null) throw error
				check(response.statusCode() == 200) { "GitHub answered ${response.statusCode()}." }
				val releases = JsonParser.parseString(response.body()).asJsonArray.map { it.asJsonObject }
					.filter { it.get("draft")?.asBoolean != true && it.get("prerelease")?.asBoolean != true && it.get("tag_name") != null }
				pick(releases, installed)
			}.getOrElse {
				Cryptic.LOGGER.warn("Could not load the changelog: {}", it.message)
				State.Failed("Couldn't reach GitHub for the release notes.")
			}
		}
	}

	/** This version's release, else the newest for this Minecraft that isn't newer (a dev build). */
	private fun pick(releases: List<JsonObject>, installed: String): State {
		val tag = { release: JsonObject -> release.get("tag_name").asString.removePrefix("v") }
		val exact = releases.firstOrNull { tag(it) == installed }
		// GitHub lists the newest first.
		val release = exact ?: releases.firstOrNull { UpdateChecker.forThisMinecraft(it) && !UpdateChecker.isNewer(tag(it), installed) }
			?: return State.Failed("GitHub has no release notes for ${installed.substringBefore('-')}.")
		val published = release.get("published_at")?.takeIf { !it.isJsonNull }?.asString?.let {
			runCatching { DATE.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull()
		}
		val body = release.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
		return State.Loaded(tag(release), published, parse(body), exact != null)
	}

	private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

	private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
	private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
	private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")

	/**
	 * The notes' Markdown as headings, paragraphs and bullets. The opening paragraphs about which
	 * jar to drop where are left out; the game is already running it.
	 */
	fun parse(markdown: String): List<Block> {
		val blocks = ArrayList<Block>()
		val paragraph = StringBuilder()
		var seenHeading = false
		fun endParagraph() {
			val text = paragraph.toString().trim()
			paragraph.clear()
			if (text.isEmpty()) return
			if (!seenHeading && (text.contains(".jar`") || text.startsWith("Cryptic "))) return
			blocks += Block.Text(inline(text), -1)
		}
		for (raw in markdown.replace("\r", "").lines()) {
			val line = raw.trimEnd()
			val heading = HEADING.find(line.trimStart())
			val bullet = BULLET.find(line)
			when {
				line.isBlank() -> endParagraph()
				heading != null -> {
					endParagraph()
					seenHeading = true
					blocks += Block.Heading(plain(heading.groupValues[1]))
				}
				bullet != null -> {
					endParagraph()
					val depth = bullet.groupValues[1].replace("\t", "  ").length / 2
					blocks += Block.Text(inline(bullet.groupValues[2]), depth)
				}
				// A wrapped bullet's next line carries on the bullet.
				raw.startsWith(" ") && paragraph.isEmpty() && blocks.lastOrNull() is Block.Text && (blocks.last() as Block.Text).depth >= 0 -> {
					val last = blocks.removeAt(blocks.size - 1) as Block.Text
					blocks += Block.Text(last.spans + inline(" " + line.trim()), last.depth)
				}
				else -> paragraph.append(' ').append(line.trim())
			}
		}
		endParagraph()
		return blocks
	}

	private fun plain(text: String): String = inline(text).joinToString("") { it.text }

	/** `**bold**` and `code` become spans; links keep their text, and italics lose their stars. */
	private fun inline(source: String): List<Span> {
		val text = LINK.replace(source) { it.groupValues[1] }
		val spans = ArrayList<Span>()
		val current = StringBuilder()
		var bold = false
		var code = false
		fun cut() {
			if (current.isNotEmpty()) spans += Span(current.toString(), bold, code)
			current.clear()
		}
		var i = 0
		while (i < text.length) {
			val c = text[i]
			when {
				c == '`' -> { cut(); code = !code }
				!code && text.startsWith("**", i) -> { cut(); bold = !bold; i++ }
				!code && (c == '*' || c == '_') && isEmphasis(text, i) -> {}
				c == '\\' && i + 1 < text.length -> { i++; current.append(text[i]) }
				else -> current.append(c)
			}
			i++
		}
		cut()
		return spans
	}

	/** A lone `*` or `_` that opens or closes emphasis, not one inside a word like snake_case. */
	private fun isEmphasis(text: String, i: Int): Boolean {
		val before = text.getOrNull(i - 1)
		val after = text.getOrNull(i + 1)
		val opens = (before == null || !before.isLetterOrDigit()) && after != null && !after.isWhitespace()
		val closes = (after == null || !after.isLetterOrDigit()) && before != null && !before.isWhitespace()
		return opens || closes
	}
}
