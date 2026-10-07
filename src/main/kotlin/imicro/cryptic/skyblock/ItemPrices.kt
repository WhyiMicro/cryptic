package imicro.cryptic.skyblock

import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What SkyBlock items sell for, by item id.
 *
 * Odin's price table (Odin is BSD 3-Clause, Copyright (c) 2025 odtheking): a
 * seven-day average of the auction house's lowest prices and the bazaar's, in
 * one map. It names everything a dungeon chest can hold — enchanted books as
 * `ENCHANTED_BOOK-ULTIMATE_ONE_FOR_ALL-1`, essences as `ESSENCE_WITHER`,
 * shards as `SHARD_WITHER`, and the Dungeon Chest Key — so one request covers
 * a whole chest. A seven-day average rather than the lowest price of the
 * minute, so one underpriced listing does not make a chest look worth a key.
 *
 * Asked for only when something needs a price, and again after half an hour.
 * No account, key or player data is sent.
 */
object ItemPrices {
	private const val URL = "https://lb.odtheking.com/averages/7day"

	private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

	/** How long to wait before asking again after a request failed. */
	private const val RETRY_INTERVAL_MS = 60 * 1000L

	private val client: HttpClient by lazy {
		HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build()
	}

	@Volatile
	private var prices: Map<String, Double> = emptyMap()

	@Volatile
	private var fetchedAt = 0L

	@Volatile
	private var failedAt = 0L

	@Volatile
	private var fetching = false

	/** True once there is a table to read from. */
	val loaded: Boolean get() = prices.isNotEmpty()

	/** The price of one [id], or null when the table does not have it. */
	fun price(id: String): Double? = prices[id]

	/** Starts a request when the table is missing or old. Never blocks. */
	fun ensureFetched() {
		val now = System.currentTimeMillis()
		if (fetching) return
		if (fetchedAt != 0L && now - fetchedAt < REFRESH_INTERVAL_MS) return
		if (failedAt != 0L && now - failedAt < RETRY_INTERVAL_MS) return
		fetching = true

		val request = HttpRequest.newBuilder()
			.uri(URI.create(URL))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", "Cryptic")
			.GET()
			.build()

		client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenAccept { response ->
				if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
				val table = HashMap<String, Double>()
				JsonParser.parseString(response.body()).asJsonObject.entrySet().forEach { (id, value) ->
					runCatching { value.asDouble }.getOrNull()?.let { table[id] = it }
				}
				prices = table
				fetchedAt = System.currentTimeMillis()
				failedAt = 0L
			}
			.exceptionally { error ->
				failedAt = System.currentTimeMillis()
				Cryptic.LOGGER.warn("Could not fetch item prices: {}", error.message)
				null
			}
			.whenComplete { _, _ -> fetching = false }
	}
}
