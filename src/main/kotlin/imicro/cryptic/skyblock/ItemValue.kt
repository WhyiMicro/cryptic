package imicro.cryptic.skyblock

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.Locale

/**
 * What an item is worth with everything applied to it: a base price plus each upgrade on it
 * (books, stars, gemstones and their slots, enchantments, scrolls, runes, dyes, skins, the reforge
 * stone and the rest; not the fee for applying a reforge). Two ways to count:
 * - [Mode.MARKET], what it would sell for: prices averaged over three days, so one odd listing
 *   doesn't swing it (but never above today's lowest BIN), and each upgrade at the share of its
 *   price an applied one keeps.
 *   The shares are SkyHelper Networth's (MIT), as Skyblocker's estimate uses them.
 * - [Mode.REBUILD], what making it again costs: today's prices, every upgrade in full.
 */
object ItemValue {
	enum class Mode { MARKET, REBUILD }

	/** The base price and each upgrade's worth, in the order a tooltip lists them. */
	class Estimate(val base: Double, val parts: List<Pair<String, Double>>) {
		val total: Double get() = base + parts.sumOf { it.second }
	}

	// What share of its price an upgrade keeps once applied, by SkyHelper Networth's table.
	private const val RECOMBOBULATOR = 0.8
	private const val FUMING_BOOK = 0.6
	private const val ART_OF_WAR = 0.6
	private const val ART_OF_PEACE = 0.8
	private const val WOOD_SINGULARITY = 0.5
	private const val JALAPENO_BOOK = 0.8
	private const val DIVAN_COATING = 0.8
	private const val FARMING_FOR_DUMMIES = 0.5
	private const val TRANSMISSION_TUNER = 0.7
	private const val MANA_DISINTEGRATOR = 0.8
	private const val ENRICHMENT = 0.5
	private const val POWER_SCROLL = 0.5
	private const val ESSENCE = 0.75
	private const val GEMSTONE_SLOTS = 0.6
	private const val ENCHANTMENTS = 0.85
	private const val ENCHANTMENT_UPGRADE = 0.8
	private const val RUNES = 0.6
	private const val DYE = 0.9

	/** Enchantments that keep less than the rest. */
	private val ENCHANTMENT_SHARES = mapOf(
		"COUNTER_STRIKE" to 0.2, "BIG_BRAIN" to 0.35, "ULTIMATE_INFERNO" to 0.35, "OVERLOAD" to 0.35,
		"ULTIMATE_SOUL_EATER" to 0.35, "ULTIMATE_FATAL_TEMPO" to 0.65,
	)

	/**
	 * One of [id] priced for [mode]: from the Bazaar when it's sold there (what buying costs), else
	 * its three-day average or lowest BIN, whichever the mode prefers, falling back on the other.
	 */
	fun price(id: String, mode: Mode = Mode.REBUILD): Double? {
		BazaarMarket.product(id)?.let { bazaar(it, mode) }?.let { return it }
		return auction(id, mode)
	}

	/**
	 * Which side of the book each mode reads, as the mod it follows does. The market takes what
	 * buying now costs, the shares already allowing for resale (Skyblocker: the other side can be
	 * far off). A rebuild takes the top buy order, what a patient buyer pays (SkyHanni). Thin
	 * products' sides are far apart, a Chain of the End Times' by four times.
	 */
	private fun bazaar(product: BazaarMarket.Product, mode: Mode): Double? {
		val buy = product.instantBuy.takeIf { it > 0 }
		val order = product.instantSell.takeIf { it > 0 }
		return if (mode == Mode.MARKET) buy ?: order else order ?: buy
	}

	/** For the market, the average unless it's on sale for less now: nothing sells above that. */
	private fun auction(key: String, mode: Mode): Double? = when (mode) {
		Mode.MARKET -> listOfNotNull(AuctionPrices.threeDayAverage(key), AuctionPrices.lowestBin(key)).minOrNull()
		Mode.REBUILD -> AuctionPrices.lowestBin(key) ?: AuctionPrices.threeDayAverage(key)
	}

	/** Null when there's no base price to build on. */
	fun estimate(stack: ItemStack, mode: Mode): Estimate? {
		val key = AuctionPrices.key(stack) ?: return null
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
		val id = data.getString("id").orElse("")
		val base = auction(key, mode) ?: price(id, mode) ?: return null
		val market = mode == Mode.MARKET
		fun price(item: String) = this@ItemValue.price(item, mode)
		val parts = ArrayList<Pair<String, Double>>()
		/** [value] counted whole when rebuilding, at [share] of it for the market. */
		fun add(label: String, value: Double?, share: Double = 1.0) {
			val worth = (value ?: return) * (if (market) share else 1.0)
			if (worth > 0) parts += label to worth
		}
		fun count(label: String, field: String, item: String, share: Double = 1.0) {
			val n = data.getIntOr(field, 0)
			if (n > 0) add(if (n > 1) "$label ($n)" else label, price(item)?.times(n), share)
		}

		if (data.getIntOr("rarity_upgrades", 0) > 0) add("Recombobulator", price("RECOMBOBULATOR_3000"), RECOMBOBULATOR)
		val potatoes = data.getIntOr("hot_potato_count", 0)
		if (potatoes > 0) {
			add("Hot Potato Books (${minOf(potatoes, 10)})", price("HOT_POTATO_BOOK")?.times(minOf(potatoes, 10)))
			if (potatoes > 10) add("Fuming Potato Books (${potatoes - 10})", price("FUMING_POTATO_BOOK")?.times(potatoes - 10), FUMING_BOOK)
		}
		count("Art of War", "art_of_war_count", "THE_ART_OF_WAR", ART_OF_WAR)
		count("Art of Peace", "artOfPeaceApplied", "THE_ART_OF_PEACE", ART_OF_PEACE)
		if (data.getIntOr("ethermerge", 0) > 0) add("Etherwarp", (price("ETHERWARP_CONDUIT") ?: 0.0) + (price("ETHERWARP_MERGER") ?: 0.0))
		count("Wood Singularity", "wood_singularity_count", "WOOD_SINGULARITY", WOOD_SINGULARITY)
		count("Jalapeno Book", "jalapeno_count", "JALAPENO_BOOK", JALAPENO_BOOK)
		count("Divan Powder Coating", "divan_powder_coating", "DIVAN_POWDER_COATING", DIVAN_COATING)
		count("Mithril Infusion", "mithril_infusion", "MITHRIL_INFUSION")
		count("Polarvoid Books", "polarvoid", "POLARVOID_BOOK")
		count("Bookworm Books", "bookworm_books", "BOOKWORM_BOOK")
		count("Farming for Dummies", "farming_for_dummies_count", "FARMING_FOR_DUMMIES", FARMING_FOR_DUMMIES)
		count("Transmission Tuners", "tuned_transmission", "TRANSMISSION_TUNER", TRANSMISSION_TUNER)
		count("Mana Disintegrators", "mana_disintegrator_count", "MANA_DISINTEGRATOR", MANA_DISINTEGRATOR)
		if (data.contains("stats_book")) add("Book of Stats", price("BOOK_OF_STATS"))
		data.getString("talisman_enrichment").ifPresent { add("Enrichment", price("TALISMAN_ENRICHMENT_" + it.uppercase(Locale.ROOT)), ENRICHMENT) }
		data.getString("power_ability_scroll").ifPresent { scroll ->
			add("Power Scroll", price(if (scroll.endsWith("_POWER_SCROLL")) scroll else scroll + "_POWER_SCROLL"), POWER_SCROLL)
		}
		data.getListOrEmpty("ability_scroll").let { scrolls ->
			val total = (0 until scrolls.size).sumOf { i -> scrolls.getString(i).map { price(it) ?: 0.0 }.orElse(0.0) }
			add("Ability Scrolls (${scrolls.size})", total)
		}

		stars(id, data, mode)?.let { (label, value) -> add(label, value) }
		gemstones(id, data, mode, ::add)

		if (id != "ENCHANTED_BOOK") {
			val (books, upgrades) = enchantments(id, data.getCompoundOrEmpty("enchantments"), mode)
			add("Enchantments", books)
			add("Enchantment upgrades", upgrades)
		}
		count("Wet Books", "wet_book_count", "WET_BOOK")
		for (type in listOf("line", "hook", "sinker")) {
			data.getCompoundOrEmpty(type).getString("part").ifPresent { add("Rod parts", price(it.uppercase(Locale.ROOT))) }
		}
		data.getString("modifier").ifPresent { reforge ->
			ItemValueTables.REFORGE_STONES[reforge.lowercase(Locale.ROOT)]?.let { add("Reforge stone", price(it)) }
		}
		if (id != "RUNE" && id != "UNIQUE_RUNE") {
			val runes = data.getCompoundOrEmpty("runes")
			add("Rune", runes.keySet().sumOf { auction("RUNE-${it.uppercase(Locale.ROOT)}-${runes.getIntOr(it, 0)}", mode) ?: 0.0 }, RUNES)
		}
		data.getString("dye_item").ifPresent { add("Dye", price(it), DYE) }
		if (id != "PET") data.getString("skin").ifPresent { add("Skin", price(it)) }
		for (part in listOf("drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module")) {
			data.getString(part).ifPresent { add("Drill part", price(it.uppercase(Locale.ROOT))) }
		}
		if (id == "PET") pet(data, mode, ::add)
		return Estimate(base, parts)
	}

	/**
	 * The books for [enchants], and apart the items that took some past their top book (a Gold
	 * Bottle Cap makes Luck of the Sea VII from VI); for the market, each at its share.
	 */
	private fun enchantments(id: String, enchants: CompoundTag, mode: Mode): Pair<Double, Double> {
		val market = mode == Mode.MARKET
		var books = 0.0
		var upgrades = 0.0
		val usedOnce = HashSet<String>()
		for (name in enchants.keySet()) {
			val upper = name.uppercase(Locale.ROOT)
			var level = enchants.getIntOr(name, 0)
			if (ItemValueTables.BUILT_IN[id]?.contains(upper) == true || ItemValueTables.IGNORED_LEVELS[upper] == level) continue
			if (upper in ItemValueTables.STACKING) level = 1
			val applied = ItemValueTables.ENCHANTMENT_UPGRADES[upper].orEmpty().filter { level >= it.tier }
			for (upgrade in applied) {
				if (upgrade.oncePerItem && !usedOnce.add(upgrade.item)) continue
				upgrades += (price(upgrade.item, mode) ?: 0.0) * (if (market) ENCHANTMENT_UPGRADE else 1.0)
			}
			val bookLevel = applied.minOfOrNull { it.tier - 1 } ?: level
			books += book(upper, bookLevel, mode) * (if (market) ENCHANTMENT_SHARES[upper] ?: ENCHANTMENTS else 1.0)
		}
		return books to upgrades
	}

	/**
	 * One book of [name] at [level]: as sold, or else built from the highest level that is, two of
	 * a level making the next. Zero when no level near it is sold.
	 */
	private fun book(name: String, level: Int, mode: Mode): Double {
		for (below in 0..MAX_LEVELS_BUILT) {
			val sold = level - below
			if (sold < 1) break
			val each = BazaarMarket.product("ENCHANTMENT_${name}_$sold")?.let { bazaar(it, mode) }
				?: auction("ENCHANTED_BOOK-$name-$sold", mode)
				?: continue
			return each * (1 shl below)
		}
		return 0.0
	}

	/** Sixteen of a book is as far down as building one is believed. */
	private const val MAX_LEVELS_BUILT = 4

	private val MASTER_STARS = listOf("FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR")

	/** Stars, from what each one costs in the items list; a dungeon item's sixth on are master stars. */
	private fun stars(id: String, data: CompoundTag, mode: Mode): Pair<String, Double>? {
		val level = maxOf(data.getIntOr("upgrade_level", 0), data.getIntOr("dungeon_item_level", 0))
		if (level <= 0) return null
		val costs = BazaarMarket.upgrades(id)?.getAsJsonArray("upgrade_costs") ?: return null
		var total = 0.0
		for (star in 0 until minOf(level, costs.size())) total += cost(costs[star].asJsonArray, mode)
		if (level > costs.size() && costs.size() == 5) {
			for (star in 0 until minOf(level - 5, 5)) total += price(MASTER_STARS[star], mode) ?: 0.0
		}
		return "Stars ($level)" to total
	}

	/** One list of costs from the items list: essence (at its share for the market), items and coins. */
	private fun cost(list: JsonArray, mode: Mode): Double = list.sumOf { element ->
		val cost = element.asJsonObject
		val amount = cost.get("amount")?.asDouble ?: 1.0
		when (cost.get("type")?.asString) {
			"ESSENCE" -> (price("ESSENCE_" + cost.get("essence_type").asString, mode) ?: 0.0) * amount * (if (mode == Mode.MARKET) ESSENCE else 1.0)
			"ITEM" -> (price(cost.get("item_id").asString, mode) ?: 0.0) * amount
			"COINS" -> cost.get("coins")?.asDouble ?: 0.0
			else -> 0.0
		}
	}

	/** The gemstones set in it, and what the slots it had to unlock cost. */
	private fun gemstones(id: String, data: CompoundTag, mode: Mode, add: (String, Double?, Double) -> Unit) {
		val gems = data.getCompound("gems").orElse(null) ?: return
		var stones = 0.0
		for (slot in gems.keySet()) {
			if (slot == "unlocked_slots" || slot.endsWith("_gem")) continue
			// A slot holds its quality, or a compound with it; a universal slot names its gem beside it.
			val quality = gems.getString(slot).orElse(null) ?: gems.getCompoundOrEmpty(slot).getString("quality").orElse(null) ?: continue
			val type = gems.getString(slot + "_gem").orElse(null) ?: slot.substringBeforeLast('_')
			stones += price("${quality}_${type}_GEM", mode) ?: 0.0
		}
		add("Gemstones", stones, 1.0)
		val unlocked = gems.getListOrEmpty("unlocked_slots")
		if (unlocked.size == 0) return
		val slots = BazaarMarket.upgrades(id)?.getAsJsonArray("gemstone_slots") ?: return
		val seen = HashMap<String, Int>()
		var unlocks = 0.0
		for (element in slots) {
			val slot = element.asJsonObject
			val type = slot.get("slot_type")?.asString ?: continue
			val name = type + "_" + seen.merge(type, 1, Int::plus)!!.minus(1)
			val costs = slot.getAsJsonArray("costs") ?: continue
			if ((0 until unlocked.size).any { unlocked.getStringOr(it, "") == name }) unlocks += cost(costs, mode)
		}
		add("Gemstone slots", unlocks, GEMSTONE_SLOTS)
	}

	/** A pet's held item and skin. */
	private fun pet(data: CompoundTag, mode: Mode, add: (String, Double?, Double) -> Unit) {
		val info = runCatching { JsonParser.parseString(data.getString("petInfo").orElse("")).asJsonObject }.getOrNull() ?: return
		info.get("heldItem")?.takeIf { it.isJsonPrimitive }?.asString?.let { add("Pet item", price(it, mode), 1.0) }
		info.get("skin")?.takeIf { it.isJsonPrimitive }?.asString?.let { add("Skin", price("PET_SKIN_$it", mode), 1.0) }
	}
}
