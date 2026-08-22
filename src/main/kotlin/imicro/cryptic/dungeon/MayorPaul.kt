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
 * Whether Mayor Paul's EZPZ perk is handing out ten free points.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who); the full
 * licence is in `licenses/dtMap-LICENSE.txt`. The perk is worth ten bonus
 * score, which is the difference between a 290 and a 300, so a score that
 * ignores it is wrong for a whole mayoral term. Hypixel publishes who won the
 * election on a public endpoint, which is asked once and remembered — no
 * account, key or player data is involved.
 */
object MayorPaul {
	private const val ELECTION_URL = "https://api.hypixel.net/resources/skyblock/election"

	/** A mayor holds office for days, so one answer lasts the whole session. */
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

	/** True once Hypixel has answered, which is what "Auto" waits for. */
	val known: Boolean get() = fetchedAt != 0L

	/**
	 * Whether the ten points should be counted, which the player can override
	 * either way for a mayor Cryptic could not reach the API to check.
	 */
	val active: Boolean
		get() = when (DungeonScore.paulMode.selectedIndex) {
			1 -> true
			2 -> false
			else -> elected
		}

	fun reset() {
		// The election result is not run-specific, so only the in-flight state
		// is dropped; the answer itself stays until it goes stale.
		fetching = false
	}

	/** Asks Hypixel who the mayor is, at most once per refresh interval. */
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

	/**
	 * Paul being mayor is not enough on its own: the ten points come from the
	 * EZPZ perk, and a mayor is elected with only some of their perks.
	 */
	private fun hasEzpz(body: String): Boolean {
		val mayor = JsonParser.parseString(body).asJsonObject.getAsJsonObject("mayor") ?: return false
		if (mayor.get("name")?.asString != "Paul") return false
		val perks = mayor.getAsJsonArray("perks") ?: return false
		return perks.any { (it as? JsonObject)?.get("name")?.asString == "EZPZ" }
	}
}
