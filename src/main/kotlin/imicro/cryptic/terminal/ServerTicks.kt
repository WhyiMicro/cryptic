package imicro.cryptic.terminal

/**
 * A count of server ticks, for the half of the click protection that a clock
 * cannot answer.
 *
 * Hypixel sends the client a ping packet once per server tick, so counting
 * those counts ticks the server has actually got through — which is the point:
 * if it is behind, the window the player is looking at went up later than the
 * clock thinks, and the wall-clock timer would let a click through too early.
 * The same trick is Odin's (BSD 3-Clause, Copyright (c) 2025 odtheking).
 */
object ServerTicks {
	/**
	 * How long a connection may go without a ping before the count is treated
	 * as unavailable. A server that never sends them must not be able to block
	 * every click forever, so the tick half simply stops applying.
	 */
	private const val STALE_AFTER_MILLIS = 3_000L

	private var lastPingAt = 0L

	/** True while ticks are actually arriving and can be counted on. */
	val available: Boolean get() = System.currentTimeMillis() - lastPingAt < STALE_AFTER_MILLIS

	/**
	 * How long the server has been silent, or null before it has ticked at all.
	 *
	 * The same observation the click protection uses, read the other way round:
	 * there it is "has enough time passed", here it is "how much".
	 */
	val sinceLastTick: Long?
		get() = if (lastPingAt == 0L) null else System.currentTimeMillis() - lastPingAt

	/**
	 * Forgets the last tick, for a connection that has just changed.
	 *
	 * Without this the silence measured across a server hop is the length of
	 * the hop, which is not lag and would be reported as several seconds of it.
	 */
	fun forget() {
		lastPingAt = 0L
	}

	@JvmStatic
	fun onServerTick() {
		lastPingAt = System.currentTimeMillis()
		Terminals.current?.let { it.serverTicksOpen++ }
	}
}
