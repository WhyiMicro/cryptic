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

	private val check = ButtonModuleSetting("check", "Check now", action = {
		val reading = BoosterCookie.read()
		val message = when (reading) {
			is BoosterCookie.Reading.Unknown -> "Cannot see the tab list from here"
			is BoosterCookie.Reading.Inactive -> "No active booster cookie"
			is BoosterCookie.Reading.Active ->
				reading.minutesLeft?.let { "Cookie active, ${describe(it)} left" } ?: "Cookie active"
		}
		Toasts.show(SOURCE, message, reading is BoosterCookie.Reading.Inactive, seconds.value)
	})

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		warnMinutes.reset()
		announceExpired.reset()
		seconds.reset()
		forget()
	})

	@JvmField
	val module = Module(
		id = "cookie_reminder",
		name = "Booster Cookie Reminder",
		description = "Warns before your cookie runs out",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(announceExpired, warnMinutes, seconds, check, reset),
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
	}

	/** Whole minutes as something readable, since an hour of them is not. */
	private fun describe(minutes: Long): String = when {
		minutes <= 0 -> "under a minute"
		minutes < 60 -> "${minutes}m"
		minutes % 60 == 0L -> "${minutes / 60}h"
		else -> "${minutes / 60}h ${minutes % 60}m"
	}
}
