package imicro.cryptic.skyblock

import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Item prices by SkyBlock id, from Odin's table: a seven-day average of auction and bazaar prices,
 * covering everything a dungeon chest holds in one request. Fetched when needed and refreshed every
 * half hour; nothing about the player is sent.
 */
object ItemPrices {
	private const val URL = "https://lb.odtheking.com/averages/7day"

	private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

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

	val loaded: Boolean get() = prices.isNotEmpty()

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
