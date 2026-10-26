package imicro.cryptic.dungeon

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import imicro.cryptic.feature.DungeonScore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Whether Mayor Paul's EZPZ perk is giving ten bonus points (the difference between 290 and 300).
 * Hypixel's public election endpoint is asked once and remembered; nothing about the player is
 * sent. Ported from dtMap.
 */
object MayorPaul {
	private const val ELECTION_URL = "https://api.hypixel.net/resources/skyblock/election"

	private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

	private val client: HttpClient by lazy {
		HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build()
	}

	@Volatile
	private var elected = false

	@Volatile
	private var fetchedAt = 0L

	@Volatile
	private var fetching = false

	val known: Boolean get() = fetchedAt != 0L

	/** Whether to count the ten points; the player can override it. */
	val active: Boolean
		get() = when (DungeonScore.paulMode.selectedIndex) {
			1 -> true
			2 -> false
			else -> elected
		}

	fun reset() {
		fetching = false
	}

	fun ensureFetched() {
		if (fetching) return
		val now = System.currentTimeMillis()
		if (fetchedAt != 0L && now - fetchedAt < REFRESH_INTERVAL_MS) return

		fetching = true
		val request = HttpRequest.newBuilder()
			.uri(URI.create(ELECTION_URL))
			.header("Accept", "application/json")
			.header("User-Agent", "Cryptic")
			.timeout(Duration.ofSeconds(10))
			.GET()
			.build()

		client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete { response, error ->
				fetching = false
				if (error != null || response == null || response.statusCode() !in 200..299) {
					Cryptic.LOGGER.warn("Could not read the Skyblock election: {}", error?.message ?: "HTTP error")
					return@whenComplete
				}

				elected = runCatching { hasEzpz(response.body()) }.getOrDefault(false)
				fetchedAt = System.currentTimeMillis()
			}
	}

	/** Paul alone isn't enough: the points come from the EZPZ perk, which he may not have. */
	private fun hasEzpz(body: String): Boolean {
		val mayor = JsonParser.parseString(body).asJsonObject.getAsJsonObject("mayor") ?: return false
		if (mayor.get("name")?.asString != "Paul") return false
		val perks = mayor.getAsJsonArray("perks") ?: return false
		return perks.any { (it as? JsonObject)?.get("name")?.asString == "EZPZ" }
	}
}
