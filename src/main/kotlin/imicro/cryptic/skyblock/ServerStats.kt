package imicro.cryptic.skyblock

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket
import net.minecraft.util.Util

/**
 * How fast the server is ticking, and how far away it is.
 *
 * Both numbers exist in Odin and NoammAddons and both are measured differently
 * here, because both of theirs cost something they need not.
 *
 * **TPS.** Both mods time the gap between two of the world-time packets a
 * server sends once a second, which is one sample a second and wobbles with
 * the network: one packet held up for fifty milliseconds reads as a server
 * running at 19. Hypixel also sends a ping every single tick — the same packet
 * every countdown in Cryptic already runs on — so here the ticks themselves are
 * counted: how many arrived over the last few seconds, against how long that
 * took. Twenty times the samples, and a late packet is one in eighty rather
 * than the whole reading. A server that sends no per-tick ping falls back on
 * the time packet, so the number still means something off Hypixel.
 *
 * **Ping.** Odin turns on the game's own debug pinger for good, which asks the
 * server how far away it is on every client tick: twenty packets a second, all
 * session, whether or not anything is showing the answer. A ping does not
 * change twenty times a second. Here one is sent every two seconds by default, and only
 * while something has asked for the number in the last few — so with the HUD
 * off and nobody typing `!ping`, nothing is sent at all.
 */
object ServerStats {
	/** How far back ticks are counted over. Long enough to be steady, short enough to follow a stall. */
	private const val TPS_WINDOW_MILLIS = 4_000L

	/** A ring of when the last ticks arrived. A power of two, so the index wraps with a mask. */
	private const val RING = 128
	private val tickTimes = LongArray(RING)

	/** How many ticks have ever been written. Only the network thread writes it. */
	@Volatile
	private var tickCount = 0

	/** The last few readings off the time packet, for a server that does not tick-ping. */
	private val timeSamples = FloatArray(3) { 20f }
	private var timeSampleCount = 0
	private var lastTimePacket = 0L

	/** Worked out at most this often, so a number on the HUD is not rewritten every frame. */
	private const val TPS_REFRESH_MILLIS = 500L
	private var tpsAt = 0L
	private var cachedTps = 20f

	/**
	 * How long to leave between two pings, which whoever is showing the number
	 * may set. Two seconds unless told otherwise.
	 */
	@Volatile
	var pingIntervalMillis = 2_000L

	/**
	 * The stamp the last request went out with, which its answer carries back.
	 *
	 * This is how Cryptic's own pings are told from anybody else's. Odin keeps
	 * the game's debug pinger running, so with it installed an answer arrives
	 * twenty times a second - and counting those made the number on the HUD
	 * change twenty times a second too, whatever interval was asked for. Only
	 * the answer to the request sent from here is a sample.
	 */
	@Volatile
	private var pingStamp = Long.MIN_VALUE

	/** How long the number stays wanted after the last time anything read it. */
	private const val PING_INTEREST_MILLIS = 5_000L

	/** A request nobody answered is given up on, so one lost packet cannot stop the rest. */
	private const val PING_TIMEOUT_MILLIS = 6_000L

	private const val PING_SAMPLES = 3
	private val pingSamples = IntArray(PING_SAMPLES)

	@Volatile
	private var pingSampleCount = 0

	@Volatile
	private var lastPing = -1

	private var pingWantedUntil = 0L
	private var pingSentAt = 0L

	@Volatile
	private var awaitingPong = false

	/** One server tick, from the network thread. Only writes a timestamp. */
	@JvmStatic
	fun onServerTick() {
		val index = tickCount
		tickTimes[index and (RING - 1)] = System.currentTimeMillis()
		tickCount = index + 1
	}

	/** The world-time packet, once a second, for servers with no per-tick ping. */
	@JvmStatic
	fun onTimePacket() {
		val now = System.currentTimeMillis()
		val last = lastTimePacket
		lastTimePacket = now
		if (last == 0L) return

		val gap = now - last
		// A gap this long is a server hop or a freeze, not a tick rate.
		if (gap <= 0L || gap > 10_000L) return
		timeSamples[timeSampleCount % timeSamples.size] = (20_000f / gap).coerceIn(0f, 20f)
		timeSampleCount++
	}

	/** Ticks per second, from 0 to 20. */
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
		// No tick for seconds: this server does not send them, or has stopped.
		if (now - newest > TPS_WINDOW_MILLIS) return if (timeSampleCount > 0) fromTimePackets() else 0f

		// Walk back from the newest for as long as they are inside the window.
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

		// Up to now rather than up to the last tick once the server has gone
		// quiet, so a stall pulls the number down as it happens instead of the
		// reading freezing at whatever it was when the ticks stopped.
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

	/**
	 * The ping in milliseconds, averaged over the last few answers, or -1 before
	 * the first one. Reading it is what keeps it being measured.
	 */
	val ping: Int
		get() {
			pingWantedUntil = System.currentTimeMillis() + PING_INTEREST_MILLIS
			val count = minOf(pingSampleCount, PING_SAMPLES)
			if (count == 0) return -1
			var sum = 0
			for (index in 0 until count) sum += pingSamples[index]
			return sum / count
		}

	/** The most recent single answer, for something that wants it unsmoothed. */
	val latestPing: Int
		get() {
			pingWantedUntil = System.currentTimeMillis() + PING_INTEREST_MILLIS
			return lastPing
		}

	/** Asks for a ping if one is wanted and due. Called once a tick. */
	fun tick(client: Minecraft) {
		val now = System.currentTimeMillis()
		if (now > pingWantedUntil) return
		val connection = client.connection ?: return

		if (awaitingPong && now - pingSentAt < PING_TIMEOUT_MILLIS) return
		if (now - pingSentAt < pingIntervalMillis) return

		pingSentAt = now
		awaitingPong = true
		// The packet carries the time it left, and the server sends it straight
		// back: the difference on arrival is the whole round trip.
		val stamp = Util.getMillis()
		pingStamp = stamp
		connection.send(ServerboundPingRequestPacket(stamp))
	}

	/**
	 * The server's answer, from the network thread.
	 *
	 * Timed there rather than on the client thread, where it would wait for the
	 * next frame and be charged for the wait.
	 */
	@JvmStatic
	fun onPong(sentAt: Long) {
		if (sentAt != pingStamp) return
		pingStamp = Long.MIN_VALUE
		val millis = (Util.getMillis() - sentAt).coerceIn(0L, 60_000L).toInt()
		awaitingPong = false
		lastPing = millis
		val index = pingSampleCount
		pingSamples[index % PING_SAMPLES] = millis
		pingSampleCount = index + 1
	}

	/** A new connection is a new server, and none of the old numbers describe it. */
	fun forget() {
		tickCount = 0
		timeSampleCount = 0
		lastTimePacket = 0L
		tpsAt = 0L
		cachedTps = 20f
		pingSampleCount = 0
		lastPing = -1
		awaitingPong = false
		pingSentAt = 0L
	}
}
