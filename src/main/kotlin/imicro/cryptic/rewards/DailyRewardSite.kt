package imicro.cryptic.rewards

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

enum class RewardRarity(val label: String, val color: Int) {
	COMMON("Common", 0xFFB4B4B4.toInt()),
	RARE("Rare", 0xFF6CDBD8.toInt()),
	EPIC("Epic", 0xFFB52ED4.toInt()),
	LEGENDARY("Legendary", 0xFFE0B551.toInt());

	companion object {
		fun of(name: String?): RewardRarity = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: COMMON
	}
}

data class RewardCard(
	val type: String,
	val rarity: RewardRarity,
	val amount: Int,
	val name: String,
	val description: String,
)

/** A day's reward: three cards to pick from, the streak, and what the claim must send back. */
data class RewardOffer(
	val id: String,
	val cards: List<RewardCard>,
	val streakStep: Int,
	val streak: Int,
	val bestStreak: Int,
	val activeAd: Int,
	/** How long Hypixel's ad runs before a card can be claimed. */
	val adSeconds: Int,
	val token: String,
	/** A made-up offer from `/cryptic debug reward`; claims nothing. */
	val sample: Boolean = false,
)

/**
 * Hypixel's daily reward site. The page's scripts hold the cards (`appData`), the names (`i18n`)
 * and a security token; a claim POSTs the chosen card back. Inspired by the DailyRewards and
 * RewardClaim mods.
 */
object DailyRewardSite {
	private const val PAGE_URL = "https://rewards.hypixel.net/claim-reward/"
	private const val CLAIM_URL = "https://rewards.hypixel.net/claim-reward/claim"
	private const val USER_AGENT =
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

	private val TOKEN = Regex("""window\.securityToken\s*=\s*"([^"]+)"""")
	private val APP_DATA = Regex("""window\.appData\s*=\s*'([\s\S]*?)';""")
	private val I18N = Regex("""window\.i18n\s*=\s*(\{[\s\S]*?\});""")
	private val I18N_LINE = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")

	/** One client keeps the page's cookies for the claim. */
	private val http: HttpClient by lazy {
		HttpClient.newBuilder()
			.cookieHandler(CookieManager())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build()
	}

	private val executor = Executors.newSingleThreadExecutor { task ->
		Thread(task, "Cryptic daily reward").apply { isDaemon = true }
	}

	fun fetch(id: String): CompletableFuture<RewardOffer> = CompletableFuture.supplyAsync({
		val request = HttpRequest.newBuilder(URI.create(PAGE_URL + id))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", USER_AGENT)
			.GET()
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() != 200) error("Hypixel's reward page answered ${response.statusCode()}")
		parse(id, response.body())
	}, executor)

	fun claim(offer: RewardOffer, option: Int): CompletableFuture<Unit> = CompletableFuture.supplyAsync({
		val query = listOf(
			"option" to option.toString(),
			"id" to offer.id,
			"activeAd" to offer.activeAd.toString(),
			"_csrf" to offer.token,
			"watchedFallback" to "false",
		).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
		val request = HttpRequest.newBuilder(URI.create("$CLAIM_URL?$query"))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", USER_AGENT)
			.header("X-CSRF-Token", offer.token)
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Origin", "https://rewards.hypixel.net")
			.header("Referer", PAGE_URL + offer.id)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() != 200) {
			error("Hypixel refused the claim (${response.statusCode()}): ${response.body().take(120)}")
		}
	}, executor)

	fun parse(id: String, page: String): RewardOffer {
		val token = TOKEN.find(page)?.groupValues?.get(1)
			?: error("Hypixel didn't recognise this reward link. It may have run out, or been claimed already.")
		val data = APP_DATA.find(page)?.groupValues?.get(1)?.replace("\\'", "'") ?: error("No reward data on the reward page")
		val app = lenient(data)?.asJsonObject ?: error("Could not read the reward data")
		val names = translations(page)

		val cards = app.getAsJsonArray("rewards")?.map { element ->
			val reward = element.asJsonObject
			val type = reward.string("reward") ?: "unknown"
			val game = reward.string("gameType")?.let(::gameName)
			RewardCard(
				type = type,
				rarity = RewardRarity.of(reward.string("rarity")),
				amount = reward.get("amount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
				name = displayName(type, game, reward, names),
				description = description(type, game, names),
			)
		} ?: error("No cards on the reward page")

		val streak = app.getAsJsonObject("dailyStreak")
		val ad = app.getAsJsonObject("ad")
		val skippable = app.get("skippable")?.asBoolean ?: false
		return RewardOffer(
			id = app.string("id") ?: id,
			cards = cards,
			streakStep = streak?.get("value")?.asInt ?: 0,
			streak = streak?.get("score")?.asInt ?: 0,
			bestStreak = streak?.get("highScore")?.asInt ?: 0,
			activeAd = app.get("activeAd")?.asInt ?: 0,
			adSeconds = if (skippable) 0 else ad?.get("duration")?.asInt ?: 0,
			token = token,
		)
	}

	private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

	private fun lenient(text: String): JsonElement? = runCatching {
		val reader = JsonReader(StringReader(text))
		reader.strictness = Strictness.LENIENT
		JsonParser.parseReader(reader)
	}.getOrNull()

	/** The page's translations, as JSON or line by line when that fails. */
	private fun translations(page: String): Map<String, String> {
		val block = I18N.find(page)?.groupValues?.get(1)?.replace("\\'", "'") ?: return emptyMap()
		lenient(block)?.takeIf { it.isJsonObject }?.asJsonObject?.let { json ->
			return json.entrySet().filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }
		}
		return I18N_LINE.findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
	}

	private fun displayName(type: String, game: String?, reward: JsonObject, names: Map<String, String>): String {
		val base = names["type.$type"] ?: type.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
		var name = base.replace("{\$game}", game ?: "").replace("{game}", game ?: "").trim()
		if (game != null && type in setOf("coins", "tokens") && !name.contains(game, ignoreCase = true)) name = "$game $name"
		if (type == "dust") name = "Mystery Dust"
		reward.string("key")?.let { key -> names["vanity.$key"]?.let { return it } }
		reward.string("package")?.let { pack ->
			names["housing.skull.${pack.removePrefix("specialoccasion_reward_card_skull_")}"]?.let { return it }
		}
		return name
	}

	private fun description(type: String, game: String?, names: Map<String, String>): String {
		val text = names["type.$type.description"]
			?: names["string.reward_description.$type"]
			?: GENERIC_DESCRIPTIONS[type]
			?: ""
		return text.replace("{\$game}", game ?: "").replace("{game}", game ?: "").trim()
	}

	private val GENERIC_DESCRIPTIONS = mapOf(
		"dust" to "Used to craft cosmetic items",
		"coins" to "In-game currency",
		"souls" to "Soul fragments used in SkyWars",
		"experience" to "Grants Hypixel experience",
		"mystery_box" to "Holds housing items or cosmetics",
		"adsense_token" to "A daily reward token",
	)

	/** Hypixel's game ids as people know the games. */
	private val GAME_NAMES = mapOf(
		"BATTLEGROUND" to "Warlords",
		"BEDWARS" to "Bed Wars",
		"BUILD_BATTLE" to "Build Battle",
		"LEGACY" to "Classic Games",
		"MCGO" to "Cops and Crims",
		"MURDER_MYSTERY" to "Murder Mystery",
		"SKYBLOCK" to "SkyBlock",
		"SKYWARS" to "SkyWars",
		"SUPER_SMASH" to "Smash Heroes",
		"SURVIVAL_GAMES" to "Blitz SG",
		"TNTGAMES" to "TNT Games",
		"WALLS3" to "Mega Walls",
	)

	private fun gameName(id: String): String =
		GAME_NAMES[id] ?: id.lowercase().split('_').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
}
