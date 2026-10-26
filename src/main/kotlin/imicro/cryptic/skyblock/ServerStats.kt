package imicro.cryptic.skyblock

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket
import net.minecraft.util.Util

/**
 * Server TPS and ping, measured more cheaply than Odin and NoammAddons do.
 *
 * TPS counts Hypixel's per-tick pings over the last few seconds instead of timing the once-a-second
 * world-time packet, so one late packet barely moves it. Servers without tick pings fall back to
 * the time packet.
 *
 * Ping is measured every two seconds, and only while something has asked for it recently.
 */
object ServerStats {
	private const val TPS_WINDOW_MILLIS = 4_000L

	/** When the last ticks arrived. A power of two so the index wraps with a mask. */
	private const val RING = 128
	private val tickTimes = LongArray(RING)

	/** Ticks ever written. Only the network thread writes it. */
	@Volatile
	private var tickCount = 0

	private val timeSamples = FloatArray(3) { 20f }
	private var timeSampleCount = 0
	private var lastTimePacket = 0L

	/** Recomputed at most this often. */
	private const val TPS_REFRESH_MILLIS = 500L
	private var tpsAt = 0L
	private var cachedTps = 20f

	/** Time between two pings, settable by whatever shows the number. */
	@Volatile
	var pingIntervalMillis = 2_000L

	/**
	 * The stamp of our last request, so only our own answers count (Odin's debug pinger also gets
	 * answers).
	 */
	@Volatile
	private var pingStamp = Long.MIN_VALUE

	/** How long the number stays wanted after it was last read. */
	private const val PING_INTEREST_MILLIS = 5_000L

	/** An unanswered request is dropped, so one lost packet can't stall the rest. */
	private const val PING_TIMEOUT_MILLIS = 6_000L

	private const val PING_SAMPLES = 3
	private val pingSamples = IntArray(PING_SAMPLES)

	@Volatile
	private var pingSampleCount = 0

	private var pingWantedUntil = 0L

	/** Until when something wants the ping checked every [FRESH_PING_MILLIS]. */
	private var freshWantedUntil = 0L
	private const val FRESH_PING_MILLIS = 500L
	private var pingSentAt = 0L

	@Volatile
	private var awaitingPong = false

	/** One server tick, from the network thread. */
	@JvmStatic
	fun onServerTick() {
		val index = tickCount
		tickTimes[index and (RING - 1)] = System.currentTimeMillis()
		tickCount = index + 1
	}

	@JvmStatic
	fun onTimePacket() {
		val now = System.currentTimeMillis()
		val last = lastTimePacket
		lastTimePacket = now
		if (last == 0L) return

		val gap = now - last
		// A gap this long is a server hop or a freeze.
		if (gap <= 0L || gap > 10_000L) return
		timeSamples[timeSampleCount % timeSamples.size] = (20_000f / gap).coerceIn(0f, 20f)
		timeSampleCount++
	}

	val tps: Float
		get() {
			val now = System.currentTimeMillis()
			if (now - tpsAt < TPS_REFRESH_MILLIS) return cachedTps
			tpsAt = now
			cachedTps = measureTps(now)
			return cachedTps
		}

	private fun measureTps(now: Long): Float {
		val total = tickCount
		if (total < 2) return fromTimePackets()

		val newest = tickTimes[(total - 1) and (RING - 1)]
		if (now - newest > TPS_WINDOW_MILLIS) return if (timeSampleCount > 0) fromTimePackets() else 0f

		var counted = 1
		var oldest = newest
		val available = minOf(total, RING)
		while (counted < available) {
			val at = tickTimes[(total - 1 - counted) and (RING - 1)]
			if (now - at > TPS_WINDOW_MILLIS) break
			oldest = at
			counted++
		}
		if (counted < 10) return fromTimePackets()

		// Measured up to now once the server goes quiet, so a stall pulls the number down as it
		// happens.
		val quiet = now - newest
		val span = if (quiet > 150L) now - oldest else newest - oldest
		if (span <= 0L) return 20f
		return ((counted - 1) * 1000f / span).coerceIn(0f, 20f)
	}

	private fun fromTimePackets(): Float {
		val count = minOf(timeSampleCount, timeSamples.size)
		if (count == 0) return 20f
		var sum = 0f
		for (index in 0 until count) sum += timeSamples[index]
		return sum / count
	}

	/** Average ping in milliseconds, or -1 before the first answer. Reading it keeps it measured. */
	val ping: Int
		get() {
			pingWantedUntil = System.currentTimeMillis() + PING_INTEREST_MILLIS
			val count = minOf(pingSampleCount, PING_SAMPLES)
			if (count == 0) return -1
			var sum = 0
			for (index in 0 until count) sum += pingSamples[index]
			return sum / count
		}


	/** Keeps the ping measured every half second for a while, for what's timed by it. */
	fun wantFreshPing() {
		val now = System.currentTimeMillis()
		freshWantedUntil = now + PING_INTEREST_MILLIS
		pingWantedUntil = maxOf(pingWantedUntil, freshWantedUntil)
	}

	fun tick(client: Minecraft) {
		val now = System.currentTimeMillis()
		if (now > pingWantedUntil) return
		val connection = client.connection ?: return

		if (awaitingPong && now - pingSentAt < PING_TIMEOUT_MILLIS) return
		val interval = if (now < freshWantedUntil) minOf(pingIntervalMillis, FRESH_PING_MILLIS) else pingIntervalMillis
		if (now - pingSentAt < interval) return

		pingSentAt = now
		awaitingPong = true
		val stamp = Util.getMillis()
		pingStamp = stamp
		connection.send(ServerboundPingRequestPacket(stamp))
	}

	/** Timed on the network thread, so the wait for the next frame isn't counted. */
	@JvmStatic
	fun onPong(sentAt: Long) {
		if (sentAt != pingStamp) return
		pingStamp = Long.MIN_VALUE
		val millis = (Util.getMillis() - sentAt).coerceIn(0L, 60_000L).toInt()
		awaitingPong = false
		val index = pingSampleCount
		pingSamples[index % PING_SAMPLES] = millis
		pingSampleCount = index + 1
	}

	fun forget() {
		tickCount = 0
		timeSampleCount = 0
		lastTimePacket = 0L
		tpsAt = 0L
		cachedTps = 20f
		pingSampleCount = 0
		awaitingPong = false
		pingSentAt = 0L
	}
}
