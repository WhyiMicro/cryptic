package imicro.cryptic.skyblock

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The Bazaar as Hypixel's public API reports it: each product's instant prices and the top of its
 * order book, and the names products go by in menus. Nothing about the player is sent.
 */
object BazaarMarket {
	private const val BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
	private const val ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"

	/** One price level of the book: how much is there, at what price, from how many orders. */
	class Level(val amount: Long, val price: Double, val orders: Int)

	/**
	 * A product. [instantBuy] is what buying now costs (the cheapest sell offer); [instantSell] is
	 * what selling now gets (the best buy order). [sellOffers] runs cheapest first, [buyOrders]
	 * dearest first.
	 */
	class Product(val id: String, val instantBuy: Double, val instantSell: Double, val sellOffers: List<Level>, val buyOrders: List<Level>)

	private val http: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
	}

	@Volatile
	var products: Map<String, Product> = emptyMap()
		private set

	@Volatile
	var updatedAt = 0L
		private set

	@Volatile
	private var fetching = false
	private var failedAt = 0L

	/** Called with the new data on the client thread, after each refresh. */
	var onUpdate: (() -> Unit)? = null

	fun product(id: String): Product? = products[id]

	/** The top of a product's book as a menu just showed it, newer than the last API answer. */
	fun update(id: String, sellOffers: List<Level>?, buyOrders: List<Level>?) {
		val old = products[id]
		val offers = sellOffers ?: old?.sellOffers ?: emptyList()
		val orders = buyOrders ?: old?.buyOrders ?: emptyList()
		val updated = Product(
			id = id,
			instantBuy = offers.firstOrNull()?.price ?: old?.instantBuy ?: 0.0,
			instantSell = orders.firstOrNull()?.price ?: old?.instantSell ?: 0.0,
			sellOffers = offers,
			buyOrders = orders,
		)
		products = products + (id to updated)
	}

	/** Refreshes when the data is older than [maxAgeMillis]. Never blocks. */
	fun refresh(maxAgeMillis: Long) {
		val now = System.currentTimeMillis()
		if (fetching || now - updatedAt < maxAgeMillis || now - failedAt < 30_000L) return
		fetching = true
		get(BAZAAR_URL)
			.thenAccept { body ->
				val root = JsonParser.parseString(body).asJsonObject
				if (!root.get("success").asBoolean) error("unsuccessful")
				val table = HashMap<String, Product>()
				root.getAsJsonObject("products").entrySet().forEach { (id, value) ->
					runCatching { table[id] = parseProduct(id, value.asJsonObject) }
				}
				products = table
				updatedAt = System.currentTimeMillis()
				net.minecraft.client.Minecraft.getInstance().execute { onUpdate?.invoke() }
			}
			.exceptionally { error ->
				failedAt = System.currentTimeMillis()
				Cryptic.LOGGER.warn("Could not fetch the Bazaar: {}", error.message)
				null
			}
			.whenComplete { _, _ -> fetching = false }
		ensureNames()
	}

	private fun parseProduct(id: String, json: JsonObject): Product {
		val status = json.getAsJsonObject("quick_status")
		fun levels(name: String) = json.getAsJsonArray(name)?.map {
			val level = it.asJsonObject
			Level(level.get("amount").asLong, level.get("pricePerUnit").asDouble, level.get("orders").asInt)
		} ?: emptyList()
		// Hypixel's naming is from its side: "buy_summary" is what you can buy from, the sell offers.
		val sellOffers = levels("buy_summary")
		val buyOrders = levels("sell_summary")
		return Product(
			id = id,
			instantBuy = sellOffers.firstOrNull()?.price ?: status.get("buyPrice").asDouble,
			instantSell = buyOrders.firstOrNull()?.price ?: status.get("sellPrice").asDouble,
			sellOffers = sellOffers,
			buyOrders = buyOrders,
		)
	}

	// ---- Names ----------------------------------------------------------------------------

	@Volatile
	private var idsByName: Map<String, List<String>> = emptyMap()
	private var namesAsked = false
	private val namesFile get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("cache").resolve("skyblock-items.json")
	private const val NAMES_MAX_AGE = 3L * 24 * 60 * 60 * 1000

	private val ROMAN = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10)

	/** A product id from the name a menu shows ("Enchanted Diamond", "Sharpness VI"), or null. */
	fun idForName(name: String): String? {
		val key = name.trim().lowercase(Locale.ROOT)
		idsByName[key]?.let { ids -> return ids.firstOrNull { it in products } ?: ids.first() }
		// Enchantments trade as books named for the enchantment alone.
		Regex("""^(.+) ([IVX]+)$""").find(name.trim())?.let { match ->
			val level = ROMAN[match.groupValues[2]] ?: return@let
			val id = "ENCHANTMENT_" + match.groupValues[1].uppercase(Locale.ROOT).replace(' ', '_').replace("-", "_") + "_" + level
			if (id in products) return id
		}
		Regex("""^(\w+) Essence$""").find(name.trim())?.let { match ->
			val id = "ESSENCE_" + match.groupValues[1].uppercase(Locale.ROOT)
			if (id in products) return id
		}
		return null
	}

	/** What an NPC pays for one of [id], from the same items list as the names. */
	fun npcSellPrice(id: String): Double? = npcPrices[id]

	@Volatile
	private var npcPrices: Map<String, Double> = emptyMap()

	/** An item's rarity as the items list gives it ("EPIC"), or null when it doesn't. */
	fun tier(id: String): String? = tiers[id]

	/** An item's name as the items list writes it ("Enchanted Wild Rose"), by its id. */
	fun nameOf(id: String): String? = namesById[id]

	@Volatile
	private var namesById: Map<String, String> = emptyMap()

	@Volatile
	private var tiers: Map<String, String> = emptyMap()

	/**
	 * What upgrading an item costs, for those that can be: `upgrade_costs` (one list per star) and
	 * `gemstone_slots` (with what unlocking each costs), as the items list has them.
	 */
	fun upgrades(id: String): JsonObject? = upgrades[id]

	@Volatile
	private var upgrades: Map<String, JsonObject> = emptyMap()

	/** Loads the items list (names and NPC prices) if it isn't yet, without the Bazaar. */
	fun ensureItems() = ensureNames()

	private fun ensureNames() {
		if (namesAsked) return
		namesAsked = true
		runCatching {
			if (Files.exists(namesFile)) {
				readNames(Files.readString(namesFile))
				if (System.currentTimeMillis() - Files.getLastModifiedTime(namesFile).toMillis() < NAMES_MAX_AGE) return
			}
		}.onFailure { Cryptic.LOGGER.warn("Could not read the cached SkyBlock items", it) }
		get(ITEMS_URL)
			.thenAccept { body ->
				readNames(body)
				runCatching {
					Files.createDirectories(namesFile.parent)
					Files.writeString(namesFile, body)
				}
			}
			.exceptionally { error ->
				Cryptic.LOGGER.warn("Could not fetch SkyBlock item names: {}", error.message)
				namesAsked = false
				null
			}
	}

	private fun readNames(body: String) {
		val table = HashMap<String, MutableList<String>>()
		val npc = HashMap<String, Double>()
		val tier = HashMap<String, String>()
		val byId = HashMap<String, String>()
		val upgrade = HashMap<String, JsonObject>()
		JsonParser.parseString(body).asJsonObject.getAsJsonArray("items")?.forEach { element ->
			val item = element.asJsonObject
			val id = item.get("id")?.asString ?: return@forEach
			item.get("npc_sell_price")?.takeIf { it.isJsonPrimitive }?.asDouble?.let { npc[id] = it }
			item.get("tier")?.takeIf { it.isJsonPrimitive }?.asString?.let { tier[id] = it }
			if (item.has("upgrade_costs") || item.has("gemstone_slots")) {
				upgrade[id] = JsonObject().apply {
					item.get("upgrade_costs")?.let { add("upgrade_costs", it) }
					item.get("gemstone_slots")?.let { add("gemstone_slots", it) }
				}
			}
			val shown = item.get("name")?.asString?.replace(Regex("§."), "")?.trim() ?: return@forEach
			byId[id] = shown
			table.getOrPut(shown.lowercase(Locale.ROOT)) { ArrayList() } += id
		}
		idsByName = table
		namesById = byId
		npcPrices = npc
		tiers = tier
		upgrades = upgrade
	}

	private fun get(url: String) = http.sendAsync(
		HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20))
			.header("User-Agent", "Cryptic").header("Accept-Encoding", "gzip").GET().build(),
		HttpResponse.BodyHandlers.ofByteArray(),
	).thenApply { response ->
		if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
		val gzip = response.headers().firstValue("Content-Encoding").orElse("") == "gzip"
		val bytes = if (gzip) GZIPInputStream(ByteArrayInputStream(response.body())).readAllBytes() else response.body()
		String(bytes, Charsets.UTF_8)
	}
}
