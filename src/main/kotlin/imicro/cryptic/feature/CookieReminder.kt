package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.BoosterCookie
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Warns when no booster cookie is running, or one is about to run out: once a game session when
 * you play without one, when it runs low, and in its last minute (Hypixel's "Booster Cookie
 * expires in 60s"), with a countdown notification that stays until it's gone. Lobbies are checked
 * once their tab list arrives.
 */
object CookieReminder {
	private const val SOURCE = "Booster Cookie"

	/** The footer counts whole minutes, so every two seconds is plenty. */
	private const val POLL_TICKS = 40

	private const val COUNTDOWN = "cookie_countdown"

	@JvmField
	val warnMinutes = SliderModuleSetting(
		id = "warn_minutes",
		label = "Warn ahead",
		unit = "min",
		defaultValue = 10.0,
		min = 0.0,
		max = 60.0,
		step = 1.0,
		description = "How long before a cookie runs out to say so, so there is time to eat the next one.",
	)

	@JvmField
	val announceExpired = ToggleModuleSetting(
		id = "announce_expired",
		label = "Say when there is none",
		defaultValue = true,
		description = "Once a game session when you play without a cookie, not on every lobby, and whenever one runs out.",
	)

	@JvmField
	val countdown = ToggleModuleSetting(
		id = "countdown",
		label = "Last minute countdown",
		defaultValue = true,
		description = "When Hypixel says the cookie has 60 seconds left, a notification counts them down until it runs out.",
	)

	@JvmField
	val seconds = SliderModuleSetting(
		id = "seconds",
		label = "Seconds shown",
		defaultValue = 30.0,
		min = 5.0,
		max = 60.0,
		step = 5.0,
		description = "How long the other notifications stay up.",
	)

	private val check = ButtonModuleSetting("check", "Check now", action = { requestCheck() })

	/**
	 * Whether a pressed "Check now" is still waiting. Right after a server hop the tab list hasn't
	 * arrived, so the answer waits for it instead of guessing.
	 */
	private var awaitingCheck = false

	private const val CHECK_PATIENCE = 200

	private var checkWaitedFor = 0

	private fun requestCheck() {
		awaitingCheck = true
		checkWaitedFor = 0
		if (answerCheck()) return
		Toasts.show(SOURCE, "Waiting for the tab list...", life = seconds.value, color = Toasts.Colors.COOKIE)
	}

	private fun answerCheck(): Boolean {
		val reading = BoosterCookie.read()
		if (reading is BoosterCookie.Reading.Unknown) return false

		awaitingCheck = false
		val message = when (reading) {
			is BoosterCookie.Reading.Inactive -> "§cNo active booster cookie"
			is BoosterCookie.Reading.Active ->
				reading.minutesLeft?.let { "Active, §f${describe(it)} §7left" } ?: "Active"
		}
		Toasts.show(SOURCE, message, life = seconds.value, color = Toasts.Colors.COOKIE)
		return true
	}

	@JvmField
	val module = Module(
		id = "cookie_reminder",
		name = "Booster Cookie Reminder",
		description = "Warns before a cookie ends",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(announceExpired, warnMinutes, countdown, seconds, check),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			checkLobby = true
			ticksUntilPoll = 0
		}
	}

	private var ticksUntilPoll = 0

	private var checkLobby = true

	/** The low warning has been given; a cookie seen above the line again re-arms it. */
	private var saidExpiring = false

	private var countdownEnds = 0L

	/** Whether the last reading had a cookie, so one ending without the chat line is still noticed. */
	private var wasActive = false

	/** The no-cookie warning was given this game session, so new lobbies don't repeat it. */
	private var saidNoneThisSession = false

	/** "Booster Cookie expires in 60s", and the 10, 5, 4... after it. */
	private val EXPIRES_IN = Regex("""^Booster Cookie expires in (\d+)s$""")

	private val FORMATTING = Regex("§.")

	/**
	 * Hypixel's own countdown from chat. Any of its lines starts the notification, so joining with
	 * ten seconds left still shows it.
	 */
	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val text = content.string.replace(FORMATTING, "").trim()
		val now = System.currentTimeMillis()

		EXPIRES_IN.matchEntire(text)?.let { match ->
			saidExpiring = true
			if (!countdown.value || countdownEnds > now) return
			val left = match.groupValues[1].toLongOrNull() ?: return
			countdownEnds = now + left * 1000
			Toasts.ask(SOURCE, COUNTDOWN, life = left.toDouble(), keys = emptyMap(), color = Toasts.Colors.COOKIE) {
				val secondsLeft = ((countdownEnds - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
				if (countdownEnds == 0L) "§cRan out" else "Runs out in §f${secondsLeft}s"
			}
			return
		}

		if (text == "Booster Cookie has expired!") {
			countdownEnds = 0L
			Toasts.dismiss(COUNTDOWN)
			if (announceExpired.value) {
				Toasts.show(SOURCE, "§cNo active booster cookie§7, be careful", life = seconds.value, color = Toasts.Colors.COOKIE)
			}
		}
	}

	fun tick(client: Minecraft) {
		if (!module.enabled) {
			ticksUntilPoll = 0
			return
		}
		if (client.player == null) return

		if (awaitingCheck) {
			// Every tick, since someone is waiting on the button.
			if (!answerCheck() && ++checkWaitedFor > CHECK_PATIENCE) {
				awaitingCheck = false
				Toasts.show(SOURCE, "§cCan't see the tab list", life = seconds.value, color = Toasts.Colors.COOKIE)
			}
		}

		if (ticksUntilPoll-- > 0) return
		ticksUntilPoll = POLL_TICKS

		when (val reading = BoosterCookie.read()) {
			// Not on SkyBlock, or no tab list yet: say nothing.
			is BoosterCookie.Reading.Unknown -> Unit

			is BoosterCookie.Reading.Inactive -> {
				// A new lobby, or a running cookie that has since stopped.
				val ended = wasActive
				wasActive = false
				if (!checkLobby && !ended) return
				checkLobby = false
				saidExpiring = true
				if (!ended && saidNoneThisSession) return
				saidNoneThisSession = true
				// The countdown and the expired line have both been shown.
				if (System.currentTimeMillis() < countdownEnds + 5_000) return
				if (announceExpired.value) {
					Toasts.show(SOURCE, "§cNo active booster cookie§7, be careful", life = seconds.value, color = Toasts.Colors.COOKIE)
				}
			}

			is BoosterCookie.Reading.Active -> {
				wasActive = true
				checkLobby = false
				val minutes = reading.minutesLeft
				val window = warnMinutes.value.toLong()
				if (minutes == null || window <= 0 || minutes > window) {
					// Back above the line, so the warning re-arms.
					saidExpiring = false
					return
				}

				if (saidExpiring) return
				saidExpiring = true
				Toasts.show(SOURCE, "Runs out in §f${describe(minutes)}", life = seconds.value, color = Toasts.Colors.COOKIE)
			}
		}
	}

	/** Whole minutes as something readable. */
	private fun describe(minutes: Long): String = when {
		minutes <= 0 -> "under a minute"
		minutes < 60 -> "${minutes}m"
		minutes % 60 == 0L -> "${minutes / 60}h"
		else -> "${minutes / 60}h ${minutes % 60}m"
	}
}
