package imicro.cryptic.feature

import imicro.cryptic.gui.MultiSelectModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.skyblock.HypixelServer

/**
 * Better Chat's spam filter: Hypixel's routine lines by kind, plus the player's own phrases. Like
 * the guild filter it only changes the window, so turning a kind off brings its lines back. The
 * idea is SkyHanni's; the message formats are Hypixel's.
 */
object ChatFilter {
	private class Kind(val name: String, val matches: (String) -> Boolean)

	private fun patterns(vararg regex: String): (String) -> Boolean {
		val compiled = regex.map { Regex(it) }
		return { text -> compiled.any { it.containsMatchIn(text) } }
	}

	private val KINDS = listOf(
		Kind("Lobby joins", patterns("""(?:joined|into) the lobby!(?: <<<)?$""", """found an? .*Mystery Box!$""")),
		Kind("Warping", patterns("""^Warping\b.*\.\.\.$""", """^Sending to server \S+\.\.\.$""", """^Request join for .+\.\.\.$""", """^Evacuating to Hub\.\.\.$""")),
		Kind("Welcome and profile", patterns("""^Welcome to Hypixel SkyBlock!$""", """^You are playing on profile: """, """^Profile ID: """)),
		Kind(
			"Watchdog",
			patterns(
				"""^\[WATCHDOG ANNOUNCEMENT]$""",
				"""^Watchdog has banned [\d,]+ players in the last 7 days\.$""",
				"""^Staff have banned an additional [\d,]+ in the last 7 days\.$""",
				"""^Blacklisted modifications are a bannable offense!$""",
			),
		),
		Kind("Guild EXP", patterns("""^You earned [\d,]+ GEXP""")),
		Kind("Kill Combo", patterns("""^\+\d+ Kill Combo\b""", """^Your Kill Combo has expired!""")),
		Kind("Friend and guild joins", patterns("""^(?:Friend|Guild) > \S+ (?:joined|left)\.$""")),
		Kind("Autopet", patterns("""^Autopet equipped your """)),
		Kind("Sacks", patterns("""^\[Sacks] """)),
		Kind(
			"Ability spam",
			patterns(
				"""^Your .+ hit [\d,]+ enem(?:y|ies) for [\d,.]+ damage\.$""",
				"""^There are blocks in the way!$""",
				"""^This ability is on cooldown for \d+s\.$""",
			),
		),
		Kind(
			"Bazaar and AH progress",
			patterns("""^\[Bazaar] .*\.\.\.$""", """^(?:Putting (?:item|coins) in escrow|Setting up the auction|Processing purchase|Claiming BIN auction)\.\.\.$"""),
		),
		Kind("Empty lines") { it.isBlank() },
	)

	@JvmField
	val kinds = MultiSelectModuleSetting(
		id = "filter_kinds",
		label = "Hide",
		options = KINDS.map { it.name },
		defaultSelected = setOf("Lobby joins", "Warping", "Welcome and profile", "Watchdog", "Guild EXP", "Kill Combo", "Bazaar and AH progress"),
		description = "Which of Hypixel's routine lines to hide.",
	)

	@JvmField
	val custom = TextModuleSetting(
		id = "filter_custom",
		label = "Also hide",
		maxLength = 1024,
		hint = "phrase; another; /regex/",
		description = "Lines containing any of these, separated by ;. Case doesn't matter; wrap one in slashes for a regex.",
	)

	@JvmField
	val enabled = ToggleModuleSetting(
		id = "filter",
		label = "Chat filter",
		defaultValue = true,
		description = "Hides Hypixel's routine lines (warping, lobby joins, Watchdog, guild EXP and more) and any phrases you add. " +
			"They stay in history, so turning a kind off shows them again.",
	).withMore(kinds, custom)

	private var parsedFrom: String? = null
	private var customMatchers: List<(String) -> Boolean> = emptyList()

	private fun customMatchers(): List<(String) -> Boolean> {
		val text = custom.value
		if (text != parsedFrom) {
			parsedFrom = text
			customMatchers = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull(::matcher)
		}
		return customMatchers
	}

	private fun matcher(entry: String): ((String) -> Boolean)? {
		if (entry.length > 2 && entry.startsWith('/') && entry.endsWith('/')) {
			// A half-typed regex matches nothing until it compiles.
			val regex = runCatching { Regex(entry.substring(1, entry.length - 1), RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
			return { line -> regex.containsMatchIn(line) }
		}
		val phrase = entry.lowercase()
		return { line -> phrase in line.lowercase() }
	}

	/** Whether [text] (plain, start trimmed) is filtered out. */
	fun hides(text: String): Boolean {
		if (!enabled.value) return false
		// Hypixel's formats mean nothing elsewhere; the player's own phrases apply everywhere.
		if (HypixelServer.connected && KINDS.withIndex().any { (i, kind) -> kinds.isSelected(i) && kind.matches(text) }) return true
		return customMatchers().any { it(text) }
	}

	/** Changes when the filter would hide different lines, so the window is rebuilt. */
	fun state(): Any = Triple(enabled.value, kinds.ticked, custom.value)
}
