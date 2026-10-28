package imicro.cryptic.skyblock

import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * The Auction House's prices: each item's lowest BIN now, and its lowest BIN averaged over three
 * days. Keyed as NEU names items ("HYPERION", "PET-ENDER_DRAGON-LEGENDARY-100",
 * "ENCHANTED_BOOK-ULTIMATE_WISE-5"), from Odin's price service; Skyblocker's is asked when Odin's
 * is down, and knows plain ids only. Nothing about the player is sent.
 */
object AuctionPrices {
	private class Table(val name: String, val urls: List<String>, val maxAgeMillis: Long) {
		@Volatile
		var prices: Map<String, Double> = emptyMap()

		@Volatile
		var updatedAt = 0L

		@Volatile
		var fetching = false

		@Volatile
		var failedAt = 0L

		fun refresh() {
			val now = System.currentTimeMillis()
			if (fetching || now - updatedAt < maxAgeMillis || now - failedAt < RETRY_MILLIS) return
			fetching = true
			fetch(0)
		}

		/** Each source in turn until one answers. */
		private fun fetch(index: Int) {
			get(urls[index])
				.thenAccept { body ->
					val table = HashMap<String, Double>()
					JsonParser.parseString(body).asJsonObject.entrySet().forEach { (key, value) ->
						runCatching { value.asDouble }.getOrNull()?.takeIf { it > 0 }?.let { table[key] = it }
					}
					prices = table
					updatedAt = System.currentTimeMillis()
					fetching = false
				}
				.exceptionally { error ->
					if (index + 1 < urls.size) {
						fetch(index + 1)
					} else {
						failedAt = System.currentTimeMillis()
						fetching = false
						Cryptic.LOGGER.warn("Could not fetch {}: {}", name, error.message)
					}
					null
				}
		}
	}

	private val lowest = Table(
		"lowest BIN prices",
		listOf("https://lb.odtheking.com/lowestbins", "https://hysky.de/api/auctions/lowestbins"),
		5 * 60 * 1000L,
	)

	private val average = Table(
		"3-day average prices",
		listOf("https://lb.odtheking.com/averages/3day", "https://hysky.de/api/auctions/lowestbins/average?days=3"),
		60 * 60 * 1000L,
	)

	private const val RETRY_MILLIS = 60 * 1000L

	/** Whether the lowest BINs have arrived, which an estimate builds on. */
	val loaded: Boolean get() = lowest.prices.isNotEmpty()

	/** Starts whichever requests are due. Never blocks. */
	fun refresh(lowestBin: Boolean, threeDay: Boolean) {
		if (lowestBin) lowest.refresh()
		if (threeDay) average.refresh()
	}

	/** The lowest BIN for a NEU [key], or for its base when the exact one isn't listed. */
	fun lowestBin(key: String): Double? = lookUp(lowest.prices, key)

	fun threeDayAverage(key: String): Double? = lookUp(average.prices, key)

	/** A level 100 pet nobody is selling at 100 is priced at its base level. */
	private fun lookUp(table: Map<String, Double>, key: String): Double? =
		table[key] ?: if (key.startsWith("PET-") && key.count { it == '-' } == 3) table[key.substringBeforeLast('-')] else null

	val describe: String
		get() = "Auction prices: ${lowest.prices.size} lowest BINs (${age(lowest.updatedAt)}), ${average.prices.size} averages (${age(average.updatedAt)})"

	private fun age(at: Long) = if (at == 0L) "never fetched" else "${(System.currentTimeMillis() - at) / 1000}s old"

	// ---- Keys --------------------------------------------------------------------------------

	private val PET_LEVEL = Regex("""\[Lvl (\d+)]""")

	/**
	 * A stack's NEU key: its SkyBlock id, or for pets, books, runes and cakes the id with what
	 * tells one from another. Null for anything that isn't a SkyBlock item.
	 */
	fun key(stack: ItemStack): String? {
		if (stack.isEmpty) return null
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
		val id = data.getString("id").orElse("").ifEmpty { return null }
		return when (id) {
			"PET" -> petKey(data, stack.hoverName.string)
			"ENCHANTED_BOOK" -> data.getCompoundOrEmpty("enchantments").single()?.let { (name, level) -> "ENCHANTED_BOOK-${name.uppercase(Locale.ROOT)}-$level" } ?: id
			"RUNE", "UNIQUE_RUNE" -> data.getCompoundOrEmpty("runes").single()?.let { (name, level) -> "RUNE-${name.uppercase(Locale.ROOT)}-$level" } ?: id
			"NEW_YEAR_CAKE" -> data.getIntOr("new_years_cake", 0).takeIf { it > 0 }?.let { "NEW_YEAR_CAKE-$it" } ?: id
			else -> id
		}
	}

	/** A compound holding exactly one name and level, as a book's enchantments or a rune's. */
	private fun CompoundTag.single(): Pair<String, Int>? {
		val name = keySet().singleOrNull() ?: return null
		return name to getIntOr(name, 0)
	}

	private fun petKey(data: CompoundTag, name: String): String? {
		val info = runCatching { JsonParser.parseString(data.getString("petInfo").orElse("")).asJsonObject }.getOrNull() ?: return null
		val type = info.get("type")?.asString ?: return null
		val tier = info.get("tier")?.asString ?: return null
		val level = PET_LEVEL.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
		val suffix = when {
			level >= 200 -> "-200"
			level >= 100 -> "-100"
			else -> ""
		}
		return "PET-$type-$tier$suffix"
	}

	// ---- Fetching ----------------------------------------------------------------------------

	private val http: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
	}

	private fun get(url: String): CompletableFuture<String> = http.sendAsync(
		HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "Cryptic").GET().build(),
		HttpResponse.BodyHandlers.ofString(),
	).thenApply { response ->
		if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
		response.body()
	}
}
