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

	@JvmStatic
	fun onServerTick() {
		lastPingAt = System.currentTimeMillis()
		Terminals.current?.let { it.serverTicksOpen++ }
	}
}
