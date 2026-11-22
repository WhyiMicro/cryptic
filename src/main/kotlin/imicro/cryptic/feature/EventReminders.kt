package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.SkyblockCalendar
import imicro.cryptic.skyblock.SkyblockCalendar.DAY
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import kotlin.math.max

/**
 * SkyBlock's calendar events and the New Year Cake, for [Reminders]. Every time comes from the
 * calendar ([SkyblockCalendar]), so nothing is fetched. Events Hypixel now runs off the calendar
 * (Fishing Festivals under any mayor, Fallen Stars) are left out rather than guessed.
 */
internal object EventReminders {
	/** Checked once a second; the calendar moves in 50-second hours. */
	private const val POLL_TICKS = 20

	/** A start is still announced this long after it, for a lobby joined just then. */
	private const val START_GRACE = 2 * 60_000L

	private const val REAL_HOUR = 60 * 60_000L

	/**
	 * One kind of event. [next] gives the start of the run that is under way or comes next, where
	 * "under way" lasts at least [START_GRACE].
	 */
	class Event(
		val setting: ToggleModuleSetting,
		val title: String,
		val startText: String,
		val next: (now: Long) -> Long,
	) {
		var warnedFor = 0L
		var startedFor = 0L
	}

	/** Every [period] from the epoch plus [offset], as the Dark Auction and Jacob's contests run. */
	private fun hourly(offset: Long, duration: Long, period: Long = REAL_HOUR): (Long) -> Long = { now ->
		val first = now - max(duration, START_GRACE) - SkyblockCalendar.EPOCH - offset
		SkyblockCalendar.EPOCH + offset + (Math.floorDiv(first, period) + 1) * period
	}

	/** On the given (month, day) pairs each year, starting at [hour]. */
	private fun yearly(dates: List<Pair<Int, Int>>, duration: Long, hour: Int = 0): (Long) -> Long = { now ->
		val year = SkyblockCalendar.year(now)
		val lasts = max(duration, START_GRACE)
		var best = Long.MAX_VALUE
		for (y in year - 1..year + 1) {
			for ((month, day) in dates) {
				val start = SkyblockCalendar.start(y, month, day, hour)
				if (start + lasts > now && start < best) best = start
			}
		}
		best
	}

	val events: List<Event> = listOf(
		Event(Reminders.darkAuction, "Dark Auction", "§aStarting §7at Sirius in the Wilderness", hourly(0, 2 * 60_000L)),
		Event(Reminders.jacobContest, "Jacob's Farming Contest", "§aStarted§7, for 20 minutes", hourly(20 * 60_000L, 20 * 60_000L)),
		Event(Reminders.travelingZoo, "Traveling Zoo", "§aIn the Hub§7, Oringo's pets are for sale", yearly(listOf(3 to 1, 9 to 1), 3 * DAY)),
		Event(Reminders.spookyFestival, "Spooky Festival", "§aStarted§7, kill mobs for Candy", yearly(listOf(7 to 29), 3 * DAY)),
		Event(Reminders.seasonOfJerry, "Season of Jerry", "§aStarted §7in Jerry's Workshop", yearly(listOf(11 to 24), 3 * DAY)),
		Event(Reminders.hoppityHunt, "Hoppity's Hunt", "§aStarted§7, eggs all through Spring", yearly(listOf(0 to 1), 93 * DAY)),
		Event(Reminders.election, "Mayor election", "§aThe booth is open §7in the Hub", yearly(listOf(5 to 27), 0)),
		Event(Reminders.election, "Mayor election", "§aVotes are closed§7, the results are in", yearly(listOf(2 to 27), 0)),
	)

	private var ticksUntilPoll = 0

	fun initialize() {
		runCatching { cakeYear = Files.readString(cakeFile).trim().toInt() }
	}

	fun tick(client: Minecraft) {
		if (ticksUntilPoll-- > 0) return
		ticksUntilPoll = POLL_TICKS
		if (!SkyblockLocation.onSkyblock) return
		val now = System.currentTimeMillis()

		val warn = Reminders.eventWarn.value.toLong() * 60_000L
		for (event in events) {
			if (!event.setting.value) continue
			val start = event.next(now)
			if (now >= start) {
				if (event.startedFor != start && now - start < START_GRACE) {
					Toasts.show(event.title, event.startText, life = Reminders.seconds.value, color = Toasts.Colors.EVENT)
				}
				event.startedFor = start
				event.warnedFor = start
			} else if (warn > 0 && start - now <= warn && event.warnedFor != start) {
				event.warnedFor = start
				val minutes = (start - now + 59_999) / 60_000
				Toasts.show(event.title, "Starts in §f${minutes}m", life = Reminders.seconds.value, color = Toasts.Colors.EVENT)
			}
		}

		cake(client, now)
	}

	// ---- New Year Cake -------------------------------------------------------------------------

	private const val CAKE_ID = "NEW_YEAR_CAKE"
	private const val CAKE_TOAST = "new_year_cake"

	/** The last year whose cake you have, saved so a restart doesn't ask again. */
	private var cakeYear = 0
	private var cakeSaidAt = 0L
	private val cakeFile get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("reminders-cake.txt")

	/** The celebration's year, while it runs (Late Winter 29th to 31st), or 0. */
	private fun celebrationYear(now: Long): Int {
		val year = SkyblockCalendar.year(now)
		val start = SkyblockCalendar.start(year, 11, 29)
		return if (now >= start && now < start + 3 * DAY) year else 0
	}

	private fun cake(client: Minecraft, now: Long) {
		val year = celebrationYear(now)
		if (year == 0 || !Reminders.newYearCake.value) {
			cakeSaidAt = 0L
			return
		}
		if (cakeYear == year) return
		if (holdsCake(client, year)) {
			claimed(year)
			return
		}
		val repeat = Reminders.cakeRepeat.value.toLong() * 60_000L
		if (cakeSaidAt != 0L && (repeat <= 0 || now - cakeSaidAt < repeat)) return
		cakeSaidAt = now
		Toasts.show(
			"New Year Cake", "Grab your free §cNew Year Cake §7from the Baker in the Hub",
			life = Reminders.seconds.value, color = Toasts.Colors.EVENT, id = CAKE_TOAST,
		)
	}

	/** The cake's custom data names its year; this year's or the coming one both count. */
	private fun holdsCake(client: Minecraft, year: Int): Boolean {
		val inventory = client.player?.inventory ?: return false
		for (slot in 0 until inventory.containerSize) {
			val stack = inventory.getItem(slot)
			if (SkyblockItem.id(stack) != CAKE_ID) continue
			val cake = SkyblockItem.data(stack).getInt("new_years_cake").orElse(0)
			if (cake == year || cake == year + 1) return true
		}
		return false
	}

	private fun claimed(year: Int) {
		cakeYear = year
		Toasts.dismiss(CAKE_TOAST, slideAway = true)
		runCatching {
			Files.createDirectories(cakeFile.parent)
			Files.writeString(cakeFile, year.toString())
		}.onFailure { Cryptic.LOGGER.warn("Could not save the New Year Cake year", it) }
	}

	/** The Baker's lines on handing one over, or on having done so already; not players' chat. */
	fun onChat(text: String) {
		if (!text.contains("New Year Cake") || text.contains(": ")) return
		if (!text.contains("claimed") && !text.contains("already")) return
		val year = celebrationYear(System.currentTimeMillis())
		if (year != 0 && cakeYear != year) claimed(year)
	}

	/** For `/cryptic debug calendar`. */
	fun describe(): List<String> {
		val now = System.currentTimeMillis()
		val lines = mutableListOf("SkyBlock time: ${SkyblockCalendar.describe(now)}")
		for (event in events) {
			val start = event.next(now)
			val state = if (now >= start) "under way" else "in ${Reminders.describe((start - now) / 60_000)}"
			lines += "${event.title}: $state, at ${SkyblockCalendar.describe(start)}" + if (event.setting.value) "" else " (off)"
		}
		lines += "New Year Cake: celebration ${celebrationYear(now).takeIf { it != 0 } ?: "not on"}, have Year ${cakeYear.takeIf { it != 0 } ?: "none"}"
		return lines
	}
}
