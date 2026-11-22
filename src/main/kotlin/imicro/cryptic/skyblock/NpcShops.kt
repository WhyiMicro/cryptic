package imicro.cryptic.skyblock

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * What NPC shops sell that the Bazaar also trades, and for how much: the table shipped with the mod
 * (from the NEU item repo), put right by the prices a shop's own menu shows. Also today's NPC
 * trading, against Hypixel's daily limits.
 */
object NpcShops {
	/** One shop's price for an item. Read by Gson, so all defaulted. */
	class Offer(val npc: String = "", val where: String = "", val price: Double = 0.0)

	/** A day's limits: 640 of one item bought from NPCs, and 500M coins of sales to them. */
	const val BUY_LIMIT = 640
	const val SELL_LIMIT = 500_000_000.0

	private val table: Map<String, List<Offer>> by lazy(::read)

	/** The Bazaar products some shop sells. */
	val ids: Set<String> get() = table.keys

	/** The shops' names, which are also their menus' titles. */
	val npcs: Set<String> by lazy { table.values.flatMap { offers -> offers.map { it.npc } }.toSet() }

	/** Kept across restarts: the day's trading, and prices as shops' menus showed them, by "id|npc". */
	private class Saved(
		var day: Long = 0,
		var sold: Double = 0.0,
		var bought: MutableMap<String, Int>? = null,
		var prices: MutableMap<String, Double>? = null,
	)

	private var day = 0L
	private var sold = 0.0
	private val bought = HashMap<String, Int>()
	private val prices = HashMap<String, Double>()
	private var loaded = false

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("npc-trades.json")

	private fun read(): Map<String, List<Offer>> = runCatching {
		val stream = NpcShops::class.java.getResourceAsStream("/assets/cryptic/bazaar/npc_shops.json") ?: error("missing from the jar")
		val type = object : TypeToken<Map<String, List<Offer>>>() {}.type
		stream.reader(Charsets.UTF_8).use { gson.fromJson<Map<String, List<Offer>>>(it, type) }
	}.onFailure { Cryptic.LOGGER.warn("Could not read the NPC shop prices", it) }.getOrNull() ?: emptyMap()

	/** The cheapest shop for [id], at the price its menu last showed if it has been open. */
	fun cheapest(id: String): Offer? {
		load()
		return table[id]
			?.map { offer -> prices["$id|${offer.npc}"]?.let { Offer(offer.npc, offer.where, it) } ?: offer }
			?.minByOrNull { it.price }
	}

	/** A shop's menu showed [id] at [price] each. */
	fun learn(id: String, npc: String, price: Double) {
		load()
		if (price <= 0 || table[id]?.any { it.npc == npc } != true) return
		val key = "$id|$npc"
		if (abs((prices[key] ?: -1.0) - price) < 0.001) return
		prices[key] = price
		save()
	}

	private fun today(): Long = LocalDate.now(ZoneOffset.UTC).toEpochDay()

	private fun roll() {
		load()
		if (day == today()) return
		day = today()
		sold = 0.0
		bought.clear()
	}

	/** How many more of [id] NPCs sell you today. */
	fun buyRoom(id: String): Int {
		roll()
		return (BUY_LIMIT - (bought[id] ?: 0)).coerceAtLeast(0)
	}

	/** How many more coins NPCs pay you today. */
	fun sellRoom(): Double {
		roll()
		return (SELL_LIMIT - sold).coerceAtLeast(0.0)
	}

	fun noteBought(id: String, units: Int) {
		roll()
		bought[id] = (bought[id] ?: 0) + units
		save()
	}

	fun noteSold(coins: Double) {
		roll()
		sold += coins
		save()
	}

	private fun load() {
		if (loaded) return
		loaded = true
		runCatching {
			if (!Files.exists(file)) return
			val saved = gson.fromJson(Files.readString(file), Saved::class.java) ?: return
			day = saved.day
			sold = saved.sold
			saved.bought?.let(bought::putAll)
			saved.prices?.let(prices::putAll)
		}.onFailure { Cryptic.LOGGER.warn("Could not read the day's NPC trading", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(Saved(day, sold, bought, prices)))
		}.onFailure { Cryptic.LOGGER.warn("Could not save the day's NPC trading", it) }
	}

	fun describe(): String {
		roll()
		return "NPC trading today: sold ${"%,.0f".format(sold)} of ${"%,.0f".format(SELL_LIMIT)} coins; bought " +
			bought.entries.joinToString { "${it.value}x ${it.key}" }.ifEmpty { "nothing" } +
			"; ${table.size} shop items known, ${prices.size} prices read from shops"
	}
}
