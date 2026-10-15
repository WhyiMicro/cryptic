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
 * Says when there is no booster cookie running, and when there is about to be.
 *
 * Three moments are worth interrupting somebody over, since dying without a
 * cookie costs coins: arriving in a lobby with none, a cookie running low, and
 * its last minute. The last minute is Hypixel's own "Booster Cookie expires in
 * 60s", and gets a notification that counts the minute down and stays up until
 * the cookie is gone, when one saying so takes its place.
 *
 * Every lobby is checked once the tab list has arrived, so a missing cookie is
 * said again on each server hop rather than once and then forgotten.
 */
object CookieReminder {
	private const val SOURCE = "Booster Cookie"

	/**
	 * Twice a second would tell nobody anything: the footer counts in whole
	 * minutes, so two seconds is already far finer than the thing being read.
	 */
	private const val POLL_TICKS = 40

	/** The countdown notification's id, so it can be taken down when the cookie goes. */
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
		description = "On each lobby you join without a cookie, and when one runs out.",
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
	 * Whether a pressed "Check now" is still waiting for an answer.
	 *
	 * The tab list is a packet, not a screen, so it is normally readable from
	 * anywhere — but on a server hop, or in the first moments after joining, it
	 * has not arrived and there is nothing to read. Answering "no cookie" then
	 * would be a guess dressed as a reading, and answering nothing at all looks
	 * like a button that does not work. So the press is remembered and the
	 * answer given when there is one.
	 */
	private var awaitingCheck = false

	/** How long a pressed check keeps waiting before it gives up, in ticks. */
	private const val CHECK_PATIENCE = 200

	private var checkWaitedFor = 0

	private fun requestCheck() {
		awaitingCheck = true
		checkWaitedFor = 0
		if (answerCheck()) return
		Toasts.show(SOURCE, "Waiting for the tab list...", life = seconds.value, color = Toasts.Colors.COOKIE)
	}

	/** Answers a pending check if the tab list can say anything yet. */
	private fun answerCheck(): Boolean {
		val reading = BoosterCookie.read()
		if (reading is BoosterCookie.Reading.Unknown) return false

		awaitingCheck = false
		val message = when (reading) {
			is BoosterCookie.Reading.Inactive -> "§cNo active booster cookie"
			is BoosterCookie.Reading.Active ->
				reading.minutesLeft?.let { "Active, §f${describe(it)} §7left" } ?: "Active"
			else -> return true
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
		// A new lobby is checked again once its tab list is in.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			checkLobby = true
			ticksUntilPoll = 0
		}
	}

	private var ticksUntilPoll = 0

	/** Set on joining a lobby, until its tab list has been read once. */
	private var checkLobby = true

	/** The warning for the cookie running now has been given; a cookie seen above the line again re-arms it. */
	private var saidExpiring = false

	/** When the counted-down last minute ends, or 0 when none is being counted. */
	private var countdownEnds = 0L

	/** Whether the last reading had a cookie running, so one ending without the chat line is still said. */
	private var wasActive = false

	/** "Booster Cookie expires in 60s", and the 10, 5, 4 ... after it. */
	private val EXPIRES_IN = Regex("""^Booster Cookie expires in (\d+)s$""")

	private val FORMATTING = Regex("§.")

	/**
	 * Hypixel's own countdown, read from the chat packet. Whichever of its lines
	 * comes first starts the notification, so one that was missed — joining a
	 * lobby with ten seconds left — still gets the rest.
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
			// Every tick rather than on the poll, because somebody is standing
			// in front of the button waiting for it.
			if (!answerCheck() && ++checkWaitedFor > CHECK_PATIENCE) {
				awaitingCheck = false
				Toasts.show(SOURCE, "§cCan't see the tab list", life = seconds.value, color = Toasts.Colors.COOKIE)
			}
		}

		if (ticksUntilPoll-- > 0) return
		ticksUntilPoll = POLL_TICKS

		when (val reading = BoosterCookie.read()) {
			// Not on SkyBlock, or the tab list has not arrived. Saying nothing is
			// the whole point of the third answer.
			is BoosterCookie.Reading.Unknown -> Unit

			is BoosterCookie.Reading.Inactive -> {
				// A new lobby, or a cookie seen running that has since stopped.
				val ended = wasActive
				wasActive = false
				if (!checkLobby && !ended) return
				checkLobby = false
				saidExpiring = true
				// The countdown has said its piece, and the expired line its own.
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
					// Back above the line — a new cookie, or a longer one — so the
					// warning is worth giving again when it drops back under it.
					saidExpiring = false
					return
				}

				if (saidExpiring) return
				saidExpiring = true
				Toasts.show(SOURCE, "Runs out in §f${describe(minutes)}", life = seconds.value, color = Toasts.Colors.COOKIE)
			}
		}
	}

	/** Whole minutes as something readable, since an hour of them is not. */
	private fun describe(minutes: Long): String = when {
		minutes <= 0 -> "under a minute"
		minutes < 60 -> "${minutes}m"
		minutes % 60 == 0L -> "${minutes / 60}h"
		else -> "${minutes / 60}h ${minutes % 60}m"
	}
}
