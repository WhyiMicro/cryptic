package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Reminds you to play Ubik's game, for [Reminders]. It can be played once each time you enter the
 * Rift: Hypixel starts a new timeline on entry ("Venturing in the Rift breaches spacetime."), and
 * Ubik turns you away for the rest of that one. The reminder goes out on entry, and stops once the
 * game is played that visit. Outside, it says when the two-hour wait has run out.
 */
internal object UbikReminder {
	private const val RIFT_AREA = "The Rift"
	private const val UBIK = "[NPC] Ubik Von Neumann: "
	private const val ENTERED = "Venturing in the Rift breaches spacetime."

	/** Entry's own lines come in a burst; the reminder waits for them to pass. */
	private const val ENTRY_DELAY_MILLIS = 4_000L

	/** Lines that mean this visit's game is played, or under way. */
	private val PLAYED = listOf(
		"The game awaits your strategic touch.",
		"Congratulations, you got the IQ Point.",
		"Alas, the reward was not within your grasp this time.",
		ALREADY_PLAYED,
	)
	private const val ALREADY_PLAYED = "You've already completed my game."

	/** Facts from SkyHanni: the game's two-hour wait, and the line that says what's left of it. */
	private const val WAIT_MILLIS = 2L * 60 * 60 * 1000
	/** How long the outside toast stands in for the entry one. */
	private const val OUTSIDE_COUNTS_MILLIS = 60L * 60 * 1000
	private val WAIT = Regex("""You need to wait (.+) before you can play again""")
	private val TIME_PART = Regex("""(\d+)([hms])""")
	/** The cube's split-or-steal match ends with this; it starts the two-hour wait. */
	private val CUBE_MATCH_END = Regex("""^You earned [\d,]+ Motes in this match!$""")

	/** When the game opens again, or 0 when that's unknown; and when that was last said outside the Rift. */
	private var readyAt = 0L
	private var saidAt = 0L
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("rift-ubik.txt")

	/** When this visit began, or 0 outside the Rift; and whether the game is done in it. */
	private var enteredAt = 0L
	private var played = false
	private var remindedAt = 0L

	private val inRift: Boolean get() = SkyblockLocation.area == RIFT_AREA

	fun initialize() {
		runCatching {
			val saved = Files.readString(file).trim().lines()
			readyAt = saved[0].trim().toLong()
			saidAt = saved.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
		}
	}

	/** Plain text, already stripped of § codes. */
	fun onChat(text: String) {
		if (text == ENTERED) {
			enteredAt = System.currentTimeMillis()
			played = false
			remindedAt = 0L
			return
		}
		WAIT.find(text)?.let { match ->
			val seconds = TIME_PART.findAll(match.groupValues[1]).sumOf { part ->
				part.groupValues[1].toLong() * when (part.groupValues[2]) {
					"h" -> 3600L
					"m" -> 60L
					else -> 1L
				}
			}
			if (seconds > 0) setReady(System.currentTimeMillis() + seconds * 1000)
			return
		}
		if (CUBE_MATCH_END.matches(text)) {
			played = true
			setReady(System.currentTimeMillis() + WAIT_MILLIS)
			return
		}
		if (!text.startsWith(UBIK)) return
		val said = text.removePrefix(UBIK)
		if (PLAYED.none { said.startsWith(it) || said.endsWith(it) }) return
		played = true
		if (!said.endsWith(ALREADY_PLAYED)) setReady(System.currentTimeMillis() + WAIT_MILLIS)
		// A wait that has run out is wrong; better unknown than reminding about a game already played.
		else if (readyAt <= System.currentTimeMillis()) setReady(0L)
	}

	fun tick() {
		val now = System.currentTimeMillis()
		remindOutside(now)
		if (enteredAt == 0L) return
		// The area read lags the entry line, so it only counts once the burst has passed.
		if (now - enteredAt < ENTRY_DELAY_MILLIS) return
		// Out of the Rift, the visit is over; a new one starts with its entry line.
		if (!inRift) {
			enteredAt = 0L
			return
		}
		if (played || !Reminders.ubikReminder.value) return
		// Still waiting out the last game (saved, so it holds across restarts); reminds once it's over.
		if (readyAt > now) return
		// A recent "ready" toast outside counts as this visit's reminder; repeats run on from it.
		if (remindedAt == 0L && readyAt != 0L && saidAt >= readyAt && now - saidAt < OUTSIDE_COUNTS_MILLIS) {
			remindedAt = saidAt
		}
		val repeat = Reminders.ubikRepeat.value.toLong() * 60_000L
		val due = remindedAt == 0L || (repeat > 0 && now - remindedAt >= repeat)
		if (!due) return
		remindedAt = now
		Toasts.show("Rift", "§dUbik's Cube §7is ready: play it this visit", life = 10.0, color = Toasts.Colors.RIFT)
	}

	private fun setReady(at: Long) {
		readyAt = at
		save()
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, "$readyAt\n$saidAt")
		}.onFailure { Cryptic.LOGGER.warn("Could not save Ubik's timer", it) }
	}

	/**
	 * Says when the game has opened again, unless you're in the Rift to see it for yourself; then
	 * again every [Reminders.ubikRepeat] minutes until it's played.
	 */
	private fun remindOutside(now: Long) {
		if (readyAt == 0L || now < readyAt || inRift || !SkyblockLocation.onSkyblock) return
		if (!Reminders.ubikReminder.value || !Reminders.ubikOutside.value) return
		val repeat = Reminders.ubikRepeat.value.toLong() * 60_000L
		val due = saidAt < readyAt || (repeat > 0 && now - saidAt >= repeat)
		if (!due) return
		saidAt = now
		save()
		Toasts.show("Rift", "§dUbik's Cube §7is ready in the Rift", life = 10.0, color = Toasts.Colors.RIFT)
	}
}
