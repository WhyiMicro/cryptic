package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.skyblock.BoosterCookie
import imicro.cryptic.skyblock.BuffReading
import imicro.cryptic.skyblock.GodPotion
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Reminders in one place: the booster cookie and God Potion, Ubik's Cube ([UbikReminder]), and
 * SkyBlock's calendar events and the New Year Cake ([EventReminders]).
 *
 * The buffs warn when none is running, or one is about to run out: once a game session when you
 * play without one, when it runs low, and in its last minute (Hypixel's "Booster Cookie expires in
 * 60s"), with a countdown notification that stays until it's gone. Lobbies are checked once their
 * tab list arrives.
 */
object Reminders {
	/** The footer counts whole minutes, so every two seconds is plenty. */
	private const val POLL_TICKS = 40

	// ---- Buffs ---------------------------------------------------------------------------------

	private val buffSection = SectionModuleSetting("buff_section", "Buffs")

	@JvmField
	val cookie = ToggleModuleSetting(
		id = "cookie",
		label = "Booster Cookie",
		defaultValue = true,
		description = "Warn about the booster cookie.",
	)

	@JvmField
	val godPotion = ToggleModuleSetting(
		id = "god_potion",
		label = "God Potion",
		defaultValue = true,
		description = "Warn about the God Potion the same way. Potions are paused in dungeons and the Rift, so it stays quiet there.",
	)

	private val anyBuff = { cookie.value || godPotion.value }

	@JvmField
	val warnMinutes = SliderModuleSetting(
		id = "warn_minutes",
		label = "Warn ahead",
		unit = "min",
		defaultValue = 10.0,
		min = 0.0,
		max = 60.0,
		step = 1.0,
		description = "How long before a cookie or potion runs out to say so, so there is time to use the next one.",
		visibleIf = anyBuff,
	)

	@JvmField
	val announceExpired = ToggleModuleSetting(
		id = "announce_expired",
		label = "Say when there is none",
		defaultValue = true,
		description = "Once a game session when you play without one, not on every lobby, and whenever one runs out.",
		visibleIf = anyBuff,
	)

	@JvmField
	val countdown = ToggleModuleSetting(
		id = "countdown",
		label = "Last minute countdown",
		defaultValue = true,
		description = "When Hypixel says there are 60 seconds left, a notification counts them down until it runs out.",
		visibleIf = anyBuff,
	)

	private val check = ButtonModuleSetting("check", "Check now", action = { requestCheck() }, visibleIf = anyBuff)

	// ---- Rift ----------------------------------------------------------------------------------

	private val riftSection = SectionModuleSetting("rift_section", "Rift")

	@JvmField
	val ubikRepeat = SliderModuleSetting(
		id = "ubik_repeat",
		label = "Remind again every",
		unit = "min",
		defaultValue = 0.0,
		min = 0.0,
		max = 30.0,
		step = 1.0,
		description = "Until you've played it, in the Rift and outside it. 0 reminds once: on entry, or when the wait runs out.",
	)

	@JvmField
	val ubikOutside = ToggleModuleSetting(
		id = "ubik_outside",
		label = "Outside the Rift",
		defaultValue = true,
		description = "Also says when Ubik's game is ready again while you're elsewhere on SkyBlock, two hours after you last played it, even if the game was closed in between.",
	)

	@JvmField
	val ubikReminder = ToggleModuleSetting(
		id = "ubik_reminder",
		label = "Ubik's Cube",
		defaultValue = true,
		description = "Reminds you to play Ubik's game when you enter the Rift and it's off cooldown, until you've played it that visit.",
	).withMore(ubikRepeat, ubikOutside)

	// ---- Events --------------------------------------------------------------------------------

	private val eventSection = SectionModuleSetting("event_section", "Events")

	@JvmField
	val cakeRepeat = SliderModuleSetting(
		id = "cake_repeat",
		label = "Remind again every",
		unit = "min",
		defaultValue = 10.0,
		min = 0.0,
		max = 30.0,
		step = 1.0,
		description = "Until you have this year's cake or the celebration ends. 0 reminds once.",
	)

	@JvmField
	val newYearCake = ToggleModuleSetting(
		id = "new_year_cake",
		label = "New Year Cake",
		defaultValue = true,
		description = "During the New Year Celebration (Late Winter 29th to 31st), reminds you to grab the free cake from the Baker until you have this year's.",
	).withMore(cakeRepeat)

	@JvmField
	val darkAuction = ToggleModuleSetting(
		id = "dark_auction",
		label = "Dark Auction",
		description = "Every hour at :55, at Sirius in the Hub's Wilderness.",
	)

	@JvmField
	val jacobContest = ToggleModuleSetting(
		id = "jacob_contest",
		label = "Jacob's Farming Contest",
		description = "Every hour at :15, for 20 minutes.",
	)

	@JvmField
	val travelingZoo = ToggleModuleSetting(
		id = "traveling_zoo",
		label = "Traveling Zoo",
		description = "Oringo's pet stand in the Hub, Early Summer and Early Winter 1st to 3rd.",
	)

	@JvmField
	val spookyFestival = ToggleModuleSetting(
		id = "spooky_festival",
		label = "Spooky Festival",
		description = "Autumn 29th to 31st.",
	)

	@JvmField
	val seasonOfJerry = ToggleModuleSetting(
		id = "season_of_jerry",
		label = "Season of Jerry",
		description = "Mount Jerry erupts in Jerry's Workshop, Late Winter 24th to 26th.",
	)

	@JvmField
	val hoppityHunt = ToggleModuleSetting(
		id = "hoppity_hunt",
		label = "Hoppity's Hunt",
		description = "The chocolate egg hunt, all through Spring.",
	)

	@JvmField
	val election = ToggleModuleSetting(
		id = "election",
		label = "Mayor election",
		description = "When the booth opens (Late Summer 27th) and when votes close (Late Spring 27th).",
	)

	private val anyEvent = { EventReminders.events.any { it.setting.value } }

	@JvmField
	val eventWarn = SliderModuleSetting(
		id = "event_warn",
		label = "Warn ahead",
		unit = "min",
		defaultValue = 5.0,
		min = 0.0,
		max = 30.0,
		step = 1.0,
		description = "How long before an event starts to say so. It is also said when it starts. 0 says only that.",
		visibleIf = anyEvent,
	)

	// ---- All -----------------------------------------------------------------------------------

	private val generalSection = SectionModuleSetting("general_section", "Notifications")

	@JvmField
	val seconds = SliderModuleSetting(
		id = "seconds",
		label = "Seconds shown",
		defaultValue = 30.0,
		min = 5.0,
		max = 60.0,
		step = 5.0,
		description = "How long the notifications stay up, apart from the countdowns.",
	)

	@JvmField
	val module = Module(
		id = "reminders",
		name = "Reminders",
		description = "Cookie, Ubik, events, cake",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			buffSection, cookie, godPotion, announceExpired, warnMinutes, countdown, check,
			riftSection, ubikReminder,
			eventSection, newYearCake, darkAuction, jacobContest, travelingZoo, spookyFestival, seasonOfJerry,
			hoppityHunt, election, eventWarn,
			generalSection, seconds,
		),
	)

	/** The God Potion and Ubik's Cube need [imicro.cryptic.skyblock.SkyblockLocation] to know the island. */
	val needsSkyblock: Boolean get() = module.enabled

	/**
	 * The reminders a pressed "Check now" is still waiting on. Right after a server hop the tab
	 * list hasn't arrived, so the answer waits for it instead of guessing.
	 */
	private val awaitingCheck = mutableSetOf<BuffReminder>()

	private const val CHECK_PATIENCE = 200

	private var checkWaitedFor = 0

	private val buffs = listOf(
		BuffReminder(
			"Booster Cookie", "booster cookie", "cookie_countdown", Toasts.Colors.COOKIE, cookie, BoosterCookie::read,
			consumed = "You consumed a Booster Cookie!",
		),
		// "GULP!" from a potion, "SLURP!" from a drink.
		BuffReminder(
			"God Potion", "God Potion", "god_potion_countdown", Toasts.Colors.GOD_POTION, godPotion, GodPotion::read,
			consumed = "The God Potion grants you powers",
		),
	)

	private fun requestCheck() {
		awaitingCheck.clear()
		awaitingCheck += buffs.filter { it.setting.value }
		checkWaitedFor = 0
		awaitingCheck.removeAll { it.answerCheck() }
		for (reminder in awaitingCheck) {
			Toasts.show(reminder.title, "Waiting for the tab list...", life = seconds.value, color = reminder.color)
		}
	}

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		UbikReminder.initialize()
		EventReminders.initialize()
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			buffs.forEach { it.checkLobby = true }
			ticksUntilPoll = 0
		}
	}

	private var ticksUntilPoll = 0

	private val FORMATTING = Regex("§.")

	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val text = content.string.replace(FORMATTING, "").trim()
		UbikReminder.onChat(text)
		EventReminders.onChat(text)
		for (reminder in buffs) {
			if (reminder.setting.value && reminder.onChat(text)) return
		}
	}

	fun tick(client: Minecraft) {
		if (!module.enabled) {
			ticksUntilPoll = 0
			return
		}
		if (client.player == null) return

		UbikReminder.tick()
		EventReminders.tick(client)

		if (awaitingCheck.isNotEmpty()) {
			// Every tick, since someone is waiting on the button.
			awaitingCheck.removeAll { it.answerCheck() }
			if (awaitingCheck.isNotEmpty() && ++checkWaitedFor > CHECK_PATIENCE) {
				for (reminder in awaitingCheck) {
					Toasts.show(reminder.title, "§cCan't see the tab list", life = seconds.value, color = reminder.color)
				}
				awaitingCheck.clear()
			}
		}

		if (ticksUntilPoll-- > 0) return
		ticksUntilPoll = POLL_TICKS

		for (reminder in buffs) {
			if (reminder.setting.value) reminder.poll()
		}
	}

	/** Whole minutes as something readable. */
	internal fun describe(minutes: Long): String = when {
		minutes <= 0 -> "under a minute"
		minutes < 60 -> "${minutes}m"
		minutes % 60 == 0L -> "${minutes / 60}h"
		else -> "${minutes / 60}h ${minutes % 60}m"
	}

	/**
	 * One buff's warnings. [title] is both the notification's heading and how Hypixel names it in
	 * chat ("God Potion expires in 60s", "God Potion has expired!").
	 */
	private class BuffReminder(
		val title: String,
		val noun: String,
		val countdownId: String,
		val color: Int,
		val setting: ToggleModuleSetting,
		val read: () -> BuffReading,
		/** Part of Hypixel's line for using one. */
		val consumed: String,
	) {
		private val expiresIn = Regex("""^${Regex.escape(title)} expires in (\d+)s$""")

		private val expired = "$title has expired!"

		/** Every warning about this buff, so using a new one can take them all down. */
		private val noticeId = "${countdownId}_notice"

		/** A new one is running, so the countdown and any warning are now wrong. */
		private fun renewed() {
			if (countdownEnds > System.currentTimeMillis()) renewedCountdown = true
			countdownEnds = 0L
			Toasts.dismiss(countdownId, slideAway = true)
			Toasts.dismiss(noticeId, slideAway = true)
		}

		var checkLobby = true

		/** The low warning has been given; a reading above the line again re-arms it. */
		private var saidExpiring = false

		private var countdownEnds = 0L

		/** The countdown on screen was cut short by a new one, so it says so while it slides out. */
		private var renewedCountdown = false

		/** Whether the last reading had one running, so one ending without the chat line is still noticed. */
		private var wasActive = false

		/** The none warning was given this game session, so new lobbies don't repeat it. */
		private var saidNoneThisSession = false

		/** When the none warning last went up. */
		private var saidNoneAt = 0L

		fun answerCheck(): Boolean {
			val reading = read()
			if (reading is BuffReading.Unknown) return false

			val message = when (reading) {
				is BuffReading.Inactive -> "§cNo active $noun"
				is BuffReading.Active ->
					reading.minutesLeft?.let { "Active, §f${describe(it)} §7left" } ?: "Active"
			}
			Toasts.show(title, message, life = seconds.value, color = color)
			return true
		}

		/**
		 * Hypixel's own countdown from chat. Any of its lines starts the notification, so joining
		 * with ten seconds left still shows it. True when the line was this buff's.
		 */
		fun onChat(text: String): Boolean {
			val now = System.currentTimeMillis()

			if (text.contains(consumed)) {
				renewed()
				return true
			}

			expiresIn.matchEntire(text)?.let { match ->
				saidExpiring = true
				if (!countdown.value || countdownEnds > now) return true
				val left = match.groupValues[1].toLongOrNull() ?: return true
				countdownEnds = now + left * 1000
				renewedCountdown = false
				Toasts.ask(title, countdownId, life = left.toDouble(), keys = emptyMap(), color = color) {
					val secondsLeft = ((countdownEnds - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
					when {
						renewedCountdown -> "§aRenewed"
						countdownEnds == 0L -> "§cRan out"
						else -> "Runs out in §f${secondsLeft}s"
					}
				}
				return true
			}

			if (text == expired) {
				countdownEnds = 0L
				Toasts.dismiss(countdownId)
				sayNone()
				return true
			}
			return false
		}

		/** Chat's line and the tab list both notice one ending, so the second is dropped. */
		private fun sayNone() {
			if (!announceExpired.value) return
			val now = System.currentTimeMillis()
			if (now - saidNoneAt < SAID_NONE_MEMORY_MS) return
			saidNoneAt = now
			Toasts.show(title, "§cNo active $noun§7, be careful", life = seconds.value, color = color, id = noticeId)
		}

		fun poll() {
			when (val reading = read()) {
				// Not on SkyBlock, no tab list yet, or paused here: say nothing.
				is BuffReading.Unknown -> Unit

				is BuffReading.Inactive -> {
					// A new lobby, or a running one that has since stopped.
					val ended = wasActive
					wasActive = false
					if (!checkLobby && !ended) return
					checkLobby = false
					saidExpiring = true
					if (!ended && saidNoneThisSession) return
					saidNoneThisSession = true
					// The countdown and the expired line have both been shown.
					if (System.currentTimeMillis() < countdownEnds + 5_000) return
					sayNone()
				}

				is BuffReading.Active -> {
					wasActive = true
					checkLobby = false
					val minutes = reading.minutesLeft
					// Missed the chat line, but the tab list shows more time than the countdown.
					if (countdownEnds > System.currentTimeMillis() && minutes != null && minutes >= 2) renewed()
					val window = warnMinutes.value.toLong()
					if (minutes == null || window <= 0 || minutes > window) {
						// Back above the line, so the warning re-arms.
						saidExpiring = false
						return
					}

					if (saidExpiring) return
					saidExpiring = true
					Toasts.show(title, "Runs out in §f${describe(minutes)}", life = seconds.value, color = color, id = noticeId)
				}
			}
		}
	}

	/** Longer than the tab list takes to catch up with chat, which both report the same buff ending. */
	private const val SAID_NONE_MEMORY_MS = 60_000L
}
