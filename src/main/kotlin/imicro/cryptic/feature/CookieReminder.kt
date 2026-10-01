package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.BoosterCookie
import net.minecraft.client.Minecraft

/**
 * Says when there is no booster cookie running, and when there is about to be.
 *
 * What makes this worth a notification rather than a HUD element is that the
 * answer is almost always "yes, for another two days", and a number saying so
 * is furniture. The two moments that matter are the cookie running out and the
 * cookie being about to, and both of them are worth interrupting somebody over:
 * dying without one costs coins.
 *
 * Nothing here fires twice for the same cookie. The reminder arms itself again
 * only when a cookie has been *seen running* since the last one, so hopping
 * between servers — which SkyBlock does constantly, and which each looks like
 * joining afresh — cannot turn one reminder into ten.
 */
object CookieReminder {
	private const val SOURCE = "Booster Cookie"

	/**
	 * Twice a second would tell nobody anything: the footer counts in whole
	 * minutes, so two seconds is already far finer than the thing being read.
	 */
	private const val POLL_TICKS = 40

	@JvmField
	val warnMinutes = SliderModuleSetting(
		id = "warn_minutes",
		label = "Warn ahead (minutes)",
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
		description = "One on joining without a cookie, one when it ends.",
	)

	@JvmField
	val seconds = SliderModuleSetting(
		id = "seconds",
		label = "Seconds shown",
		defaultValue = 30.0,
		min = 5.0,
		max = 60.0,
		step = 5.0,
		description = "How long the notification stays up.",
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
		Toasts.show(SOURCE, "Waiting for the tab list...", false, seconds.value)
	}

	/** Answers a pending check if the tab list can say anything yet. */
	private fun answerCheck(): Boolean {
		val reading = BoosterCookie.read()
		if (reading is BoosterCookie.Reading.Unknown) return false

		awaitingCheck = false
		val message = when (reading) {
			is BoosterCookie.Reading.Inactive -> "No active booster cookie"
			is BoosterCookie.Reading.Active ->
				reading.minutesLeft?.let { "Cookie active, ${describe(it)} left" } ?: "Cookie active"
			else -> return true
		}
		Toasts.show(SOURCE, message, reading is BoosterCookie.Reading.Inactive, seconds.value)
		return true
	}

	@JvmField
	val module = Module(
		id = "cookie_reminder",
		name = "Booster Cookie Reminder",
		description = "Warns before your cookie runs out",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(announceExpired, warnMinutes, seconds, check),
	)

	private var ticksUntilPoll = 0

	/**
	 * What has already been said about the cookie that is running now.
	 *
	 * Both are cleared by a cookie being seen with time left on it, which is
	 * what makes the next one's warnings arrive: nothing else re-arms them, and
	 * in particular a server hop does not.
	 */
	private var saidExpiring = false
	private var saidGone = false

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
				Toasts.show(SOURCE, "Still cannot see the tab list", true, seconds.value)
			}
		}

		if (ticksUntilPoll-- > 0) return
		ticksUntilPoll = POLL_TICKS

		when (val reading = BoosterCookie.read()) {
			// Not on SkyBlock, or the tab list has not arrived. Saying nothing is
			// the whole point of the third answer.
			is BoosterCookie.Reading.Unknown -> Unit

			is BoosterCookie.Reading.Inactive -> {
				if (saidGone) return
				saidGone = true
				// Whatever the advance warning would have said next, it is too
				// late to say it.
				saidExpiring = true
				if (announceExpired.value) {
					Toasts.show(SOURCE, "No active booster cookie", true, seconds.value)
				}
			}

			is BoosterCookie.Reading.Active -> {
				saidGone = false

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
				Toasts.show(SOURCE, "Booster cookie runs out in ${describe(minutes)}", false, seconds.value)
			}
		}
	}

	/** Forgets what has been said, so the next poll speaks up again. */
	fun forget() {
		saidExpiring = false
		saidGone = false
		ticksUntilPoll = 0
		awaitingCheck = false
	}


	/** Whole minutes as something readable, since an hour of them is not. */
	private fun describe(minutes: Long): String = when {
		minutes <= 0 -> "under a minute"
		minutes < 60 -> "${minutes}m"
		minutes % 60 == 0L -> "${minutes / 60}h"
		else -> "${minutes / 60}h ${minutes % 60}m"
	}
}
