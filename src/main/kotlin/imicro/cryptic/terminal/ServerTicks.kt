package imicro.cryptic.terminal

/**
 * Counts server ticks from Hypixel's once-per-tick ping. Used by click protection: if the server is
 * behind, the window opened later than the clock thinks. After Odin.
 */
object ServerTicks {
	/**
	 * Without a ping for this long, the tick count stops applying, so a server that never sends
	 * them can't block clicks forever.
	 */
	private const val STALE_AFTER_MILLIS = 3_000L

	private var lastPingAt = 0L

	val available: Boolean get() = System.currentTimeMillis() - lastPingAt < STALE_AFTER_MILLIS

	val sinceLastTick: Long?
		get() = if (lastPingAt == 0L) null else System.currentTimeMillis() - lastPingAt

	/** Forgets the last tick on a server change, so the hop isn't counted as lag. */
	fun forget() {
		lastPingAt = 0L
	}

	/** Server ticks since startup, for timers that stretch with lag (Spirit Bear, Livid). */
	@Volatile
	var total = 0L
		private set

	@JvmStatic
	fun onServerTick() {
		lastPingAt = System.currentTimeMillis()
		total++
		Terminals.current?.let { it.serverTicksOpen++ }
	}
}
