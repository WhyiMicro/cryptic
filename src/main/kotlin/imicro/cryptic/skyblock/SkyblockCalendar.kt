package imicro.cryptic.skyblock

/**
 * SkyBlock's calendar, worked out from the clock alone. Year 1 began at [EPOCH]; a day lasts 20
 * real minutes, a month 31 days and a year 12 months, so every event lands on the same real-time
 * grid for everyone.
 */
object SkyblockCalendar {
	/** Early Spring 1st of Year 1. */
	const val EPOCH = 1_560_275_700_000L
	const val HOUR = 50_000L
	const val DAY = 24 * HOUR
	const val MONTH = 31 * DAY
	const val YEAR = 12 * MONTH

	val MONTHS = listOf(
		"Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
		"Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter",
	)

	fun year(now: Long = System.currentTimeMillis()): Int = ((now - EPOCH) / YEAR).toInt() + 1

	/** When [day] (1 to 31) of [month] (0 for Early Spring) of [year] begins, plus [hour] hours. */
	fun start(year: Int, month: Int, day: Int, hour: Int = 0): Long =
		EPOCH + (year - 1L) * YEAR + month * MONTH + (day - 1L) * DAY + hour * HOUR

	/** "Late Winter 29th, Year 526, 06:00", for the debug dump. */
	fun describe(now: Long = System.currentTimeMillis()): String {
		val into = now - EPOCH
		val inYear = into % YEAR
		val month = (inYear / MONTH).toInt()
		val day = (inYear % MONTH / DAY).toInt() + 1
		val hour = (inYear % DAY / HOUR).toInt()
		val minute = (inYear % HOUR * 60 / HOUR).toInt()
		val suffix = when {
			day in 11..13 -> "th"
			day % 10 == 1 -> "st"
			day % 10 == 2 -> "nd"
			day % 10 == 3 -> "rd"
			else -> "th"
		}
		return "${MONTHS[month]} $day$suffix, Year ${year(now)}, %02d:%02d".format(hour, minute)
	}
}
