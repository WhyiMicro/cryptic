package imicro.cryptic.carry

/** One carry being run for one person: [done] bosses killed out of [ordered]. */
data class Carry(
	val name: String,
	val tier: Int,
	var ordered: Int,
	var done: Int = 0,
	var firstKillAt: Long = 0L,
	var lastKillAt: Long = 0L,
	/** Milliseconds spent on each boss, newest last. */
	val killTimes: MutableList<Long> = mutableListOf(),
) {
	val remaining: Int get() = (ordered - done).coerceAtLeast(0)
	val finished: Boolean get() = done >= ordered

	val elapsed: Long get() = if (firstKillAt == 0L) 0L else lastKillAt - firstKillAt
}

/**
 * A finished carry, kept for the totals. [type] and [ordered] are null on rows written before they
 * existed (Gson fills missing fields with null), so read [bossType] and [orderedOrCount].
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

	val orderedOrCount: Int get() = ordered ?: count

	companion object {
		const val VOIDGLOOM = "Voidgloom Seraph"
	}
}

/** Running totals, kept as counters because the history is capped. */
data class CarryTotals(
	var carriesCompleted: Int = 0,
	var bossesKilled: Int = 0,
	var killMillisTotal: Long = 0L,
	var fastestKillMillis: Long = 0L,
	var lastKillMillis: Long = 0L,
)
