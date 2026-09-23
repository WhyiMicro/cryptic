package imicro.cryptic.carry

/**
 * One carry being run for one person.
 *
 * [done] counts bosses killed, [ordered] is what they paid for. A carry with
 * [tier] null is one where the tier was not pinned down, which is not offered
 * in the menu yet but costs nothing to allow for.
 */
data class Carry(
	val name: String,
	val tier: Int,
	var ordered: Int,
	var done: Int = 0,
	/** When the first boss for this carry died, and when the last one did. */
	var firstKillAt: Long = 0L,
	var lastKillAt: Long = 0L,
	/** Milliseconds spent on each boss, newest last, for the averages. */
	val killTimes: MutableList<Long> = mutableListOf(),
) {
	val remaining: Int get() = (ordered - done).coerceAtLeast(0)
	val finished: Boolean get() = done >= ordered

	/** Milliseconds from the first kill to the last, or zero before the second. */
	val elapsed: Long get() = if (firstKillAt == 0L) 0L else lastKillAt - firstKillAt

	val averageKill: Long get() = if (killTimes.isEmpty()) 0L else killTimes.sum() / killTimes.size

	val fastestKill: Long get() = killTimes.minOrNull() ?: 0L
}

/**
 * A carry that has been finished and filed away, kept for the totals.
 *
 * [type] names the boss rather than assuming it. Voidgloom is the only one
 * offered today and every old row is one, but a history that cannot say what
 * kind of carry it was is a history that has to be thrown away the moment a
 * second kind exists.
 *
 * [type] and [ordered] are nullable because rows written before they existed do
 * not carry them, and Gson fills a missing field with null whatever the type
 * says. [bossType] and [orderedOrCount] are what the rest of the code reads.
 */
data class FinishedCarry(
	val name: String,
	val tier: Int,
	val count: Int,
	val durationMillis: Long,
	val completedAt: Long,
	val type: String? = VOIDGLOOM,
	val ordered: Int? = null,
) {
	val bossType: String get() = type ?: VOIDGLOOM

	/** What was ordered, falling back to what was done for the older rows. */
	val orderedOrCount: Int get() = ordered ?: count

	companion object {
		const val VOIDGLOOM = "Voidgloom Seraph"
	}
}

/**
 * The running totals, which are counters rather than a log.
 *
 * Deliberately not derived from [CarryStore.history] on demand: the history is
 * capped so the file cannot grow without bound, and a total that is only as old
 * as the cap is not a total. These are added to once per kill and never
 * recomputed.
 */
data class CarryTotals(
	var carriesCompleted: Int = 0,
	var bossesKilled: Int = 0,
	/** The sum and the count, which between them give the all-time average. */
	var killMillisTotal: Long = 0L,
	var fastestKillMillis: Long = 0L,
	/** How long the last boss took, which is the one you just did. */
	var lastKillMillis: Long = 0L,
)
