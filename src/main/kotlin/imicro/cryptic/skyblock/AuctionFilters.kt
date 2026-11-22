package imicro.cryptic.skyblock

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Filters on an auction and the item in it, written `field:condition`: `quality:50`, `tier:10`,
 * `skin:GOLDOR_CELESTIAL`, `stars:>=5`, `enchant.ultimate_wise:5`, `gem.combat_0:PERFECT`. Each
 * field reads what Hypixel stores on the item (its "ExtraAttributes"), and `attr.<key>` reads any
 * of it by name, so a filter Hypixel adds later works without a field of its own.
 *
 * Conditions: a value (`50`, `GOLDOR_CELESTIAL`), several (`a,b`: any of them; for a list, all of
 * them), `>=n`, `<=n`, `>n`, `<n`, `n+`, a range `n-m` (or `a..b`), `any`, `none`, `yes`, `no`,
 * and `!` before any of them for the opposite. Text may use `*` as a wildcard. A missing number
 * counts as 0, as Coflnet's filters do.
 */
object AuctionFilters {
	enum class Kind { NUMBER, TEXT, CONTAINS, FLAG, LIST, RARITY, DATE, ANY }

	/**
	 * A field: [param] names what follows its dot (`enchant.<enchantment>`), or is null when it
	 * takes none. [values] are suggestions for its condition, ahead of those [more] finds in the
	 * items list; [params] suggests what follows the dot.
	 */
	class Field(
		val name: String,
		val kind: Kind,
		val help: String,
		val group: String,
		val param: String? = null,
		val values: List<String> = emptyList(),
		val more: (() -> Collection<String>)? = null,
		val params: (() -> Collection<String>)? = null,
		val normalize: ((String) -> String?)? = null,
		val extract: (AuctionItem, String) -> Any?,
	)

	// ---- The item ------------------------------------------------------------------------------

	private val FORMATTING = Regex("§.")
	private val PET_LEVEL = Regex("""\[Lvl (\d+)]""")

	/** One auction from Hypixel's list, with its item read only when a filter asks. */
	class AuctionItem(auction: JsonObject) {
		val uuid: String = auction.get("uuid")?.asString.orEmpty()
		val name: String = auction.get("item_name")?.asString?.replace(FORMATTING, "").orEmpty()
		val tier: String? = auction.get("tier")?.asString
		val bin: Boolean = auction.get("bin")?.asBoolean == true
		val startingBid: Long = auction.get("starting_bid")?.asLong ?: 0L
		val highestBid: Long = auction.get("highest_bid_amount")?.asLong ?: 0L
		val seller: String = auction.get("auctioneer")?.asString.orEmpty()
		val category: String? = auction.get("category")?.asString
		private val loreText: String? = auction.get("item_lore")?.asString
		private val bytes: String? = auction.get("item_bytes")?.asString

		val lore: String by lazy { loreText?.replace(FORMATTING, "").orEmpty() }

		/** What it sells for now: a BIN's price, or an auction's top bid once there is one. */
		val price: Long get() = if (bin) startingBid else maxOf(startingBid, highestBid)

		/** The item as Hypixel stores it: a gzipped 1.8 inventory of one. */
		private val item: CompoundTag by lazy {
			runCatching {
				NbtIo.readCompressed(ByteArrayInputStream(Base64.getDecoder().decode(bytes ?: "")), NbtAccounter.unlimitedHeap())
					.getListOrEmpty("i").getCompoundOrEmpty(0)
			}.getOrElse { CompoundTag() }
		}

		val extra: CompoundTag by lazy { item.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes") }

		val count: Int get() = item.getIntOr("Count", 1).coerceAtLeast(1)

		/** The NEU key ("HYPERION", "PET-GOLDEN_DRAGON-LEGENDARY-100"). */
		val key: String? by lazy { AuctionPrices.key(extra, name) }

		val pet: JsonObject? by lazy {
			extra.getString("petInfo").map { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }.orElse(null)
		}
	}

	// ---- Reading values ------------------------------------------------------------------------

	private fun number(tag: CompoundTag, key: String): Double? = (tag.get(key) as? NumericTag)?.doubleValue()

	private fun text(tag: CompoundTag, key: String): String? = tag.getString(key).orElse(null)?.takeIf { it.isNotEmpty() }

	/** A tag as a filter reads it: numbers, text, lists of text, and a compound's names. */
	private fun value(tag: Tag?): Any? = when (tag) {
		null -> null
		is NumericTag -> tag.doubleValue()
		is StringTag -> tag.value()
		is ListTag -> (0 until tag.size).map { (value(tag.get(it)) ?: "").toString() }
		is CompoundTag -> tag.keySet().toList()
		else -> tag.toString()
	}

	/** `gems.COMBAT_0.quality` and the like, a dot apart. */
	private fun path(tag: CompoundTag, path: String): Tag? {
		var current: Tag = tag
		for (part in path.split('.')) {
			val compound = current as? CompoundTag ?: return null
			current = compound.get(part) ?: compound.keySet().firstOrNull { it.equals(part, ignoreCase = true) }?.let(compound::get) ?: return null
		}
		return current
	}

	/** A gem slot's quality; Hypixel writes it plain or, for some, inside a compound. */
	private fun gemQuality(gems: CompoundTag, slot: String): String? {
		val key = gems.keySet().firstOrNull { it.equals(slot, ignoreCase = true) } ?: return null
		return when (val tag = gems.get(key)) {
			is StringTag -> tag.value()
			is CompoundTag -> tag.getString("quality").orElse(null)
			else -> null
		}
	}

	private fun gemSlots(gems: CompoundTag) = gems.keySet().filter { !it.endsWith("_gem") && it != "unlocked_slots" }

	private fun petNumber(item: AuctionItem, key: String): Double? = item.pet?.get(key)?.takeIf { it.isJsonPrimitive }?.asDouble

	private fun petText(item: AuctionItem, key: String): String? = item.pet?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

	private fun attribute(name: String, key: String, help: String, group: String, kind: Kind = Kind.NUMBER, more: (() -> Collection<String>)? = null) =
		Field(name, kind, help, group, more = more) { item, _ -> if (kind == Kind.NUMBER) number(item.extra, key) else text(item.extra, key) }

	// ---- Suggestions from the items list -------------------------------------------------------

	private fun idsStarting(prefix: String): () -> Collection<String> = { BazaarMarket.itemIds().filter { it.startsWith(prefix) }.sorted() }

	/** Armour and pet skins as an item stores them: a pet's without its "PET_SKIN_". */
	private fun skins(): Collection<String> =
		BazaarMarket.itemIds().filter { BazaarMarket.nameOf(it)?.endsWith(" Skin") == true }.map { it.removePrefix("PET_SKIN_") }.sorted()

	private fun petSkins(): Collection<String> = BazaarMarket.itemIds().filter { it.startsWith("PET_SKIN_") }.map { it.removePrefix("PET_SKIN_") }.sorted()

	/** Enchantments as items store them, from the Bazaar's books ("ENCHANTMENT_ULTIMATE_WISE_5"). */
	private fun enchantments(): Collection<String> {
		if (BazaarMarket.products.isEmpty()) BazaarMarket.refresh(10 * 60_000L)
		return BazaarMarket.products.keys.asSequence()
			.filter { it.startsWith("ENCHANTMENT_") }
			.map { it.removePrefix("ENCHANTMENT_").substringBeforeLast('_').lowercase(Locale.ROOT) }
			.toSortedSet()
	}

	/**
	 * Whole filters that finish [typed] once its field is named: what follows the dot
	 * (`enchant.ultimate_w` → `enchant.ultimate_wise:`) or the value (`dye:DYE_PURE` →
	 * `dye:DYE_PURE_BLACK`). Those starting with what was typed come first.
	 */
	fun complete(typed: String, limit: Int = 40): List<String> {
		val opAt = typed.indexOfAny(OP_CHARS)
		val head = if (opAt < 0) typed else typed.substring(0, opAt)
		val field = field(head.substringBefore('.')) ?: return emptyList()
		if (field.param != null && '.' !in head) return emptyList()
		if (opAt < 0) {
			if (field.param == null || '.' !in head) return emptyList()
			// A whole name still offers itself, for the colon.
			return ranked(field.params?.invoke().orEmpty(), head.substringAfter('.'), limit, exact = true).map { "${head.substringBefore('.')}.$it:" }
		}
		var valueAt = opAt
		while (valueAt < typed.length && typed[valueAt] in OP_CHARS) valueAt++
		val value = typed.substring(valueAt)
		// A list completes its last item.
		val done = if (field.kind == Kind.LIST && ',' in value) value.substringBeforeLast(',') + "," else ""
		BazaarMarket.ensureItems()
		val options = LinkedHashSet(field.values).apply { field.more?.invoke()?.let(::addAll) }
		return ranked(options, value.substring(done.length), limit).map { typed.substring(0, valueAt) + done + it }
	}

	private val OP_CHARS = charArrayOf(':', '=', '<', '>', '!')

	private fun ranked(options: Collection<String>, part: String, limit: Int, exact: Boolean = false): List<String> {
		if (part.isEmpty()) return options.take(limit)
		return options.filter { it.contains(part, ignoreCase = true) && (exact || !it.equals(part, ignoreCase = true)) }
			.sortedWith(compareBy({ !it.startsWith(part, ignoreCase = true) }, { it.length }))
			.take(limit)
	}

	// ---- The fields ----------------------------------------------------------------------------

	private val RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL", "ULTIMATE", "SUPREME", "ADMIN")
	private val QUALITIES = listOf("ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT")
	private val YES_NO = listOf("yes", "no")

	val fields: List<Field> = listOf(
		// The auction
		Field("bin", Kind.FLAG, "A Buy It Now auction.", "Auction", values = YES_NO) { item, _ -> item.bin },
		Field("price", Kind.NUMBER, "What it costs: a BIN's price, or an auction's top bid.", "Auction", values = listOf("<=500m", "1m-5m")) { item, _ -> item.price.toDouble() },
		Field("starting_bid", Kind.NUMBER, "The starting bid (a BIN's price).", "Auction") { item, _ -> item.startingBid.toDouble() },
		Field("highest_bid", Kind.NUMBER, "The highest bid so far.", "Auction") { item, _ -> item.highestBid.toDouble() },
		Field("count", Kind.NUMBER, "How many are in the stack.", "Auction", values = listOf("64", ">=16")) { item, _ -> item.count.toDouble() },
		Field("price_per_unit", Kind.NUMBER, "The price divided by the stack's count.", "Auction") { item, _ -> item.price.toDouble() / item.count },
		Field("seller", Kind.TEXT, "Who is selling: a player's name or UUID.", "Auction", normalize = ::sellerUuid) { item, _ -> item.seller },
		Field("rarity", Kind.RARITY, "The rarity, as listed (recombobulated counts). Takes >= and <= too.", "Auction", values = RARITIES) { item, _ -> item.tier },
		Field("name", Kind.CONTAINS, "Text the item's name contains.", "Auction") { item, _ -> item.name },
		Field("lore", Kind.CONTAINS, "Text the item's description contains.", "Auction") { item, _ -> item.lore },
		Field("category", Kind.TEXT, "The Auction House tab.", "Auction", values = listOf("weapon", "armor", "accessories", "consumables", "blocks", "misc")) { item, _ -> item.category },
		Field("id", Kind.TEXT, "The item's SkyBlock ID (wildcards work: *_CELESTIAL).", "Auction", more = { BazaarMarket.itemIds().sorted() }) { item, _ -> text(item.extra, "id") },

		// Upgrades
		Field("stars", Kind.NUMBER, "Stars, master stars included (0 to 10).", "Upgrades", values = listOf("5", "10", ">=6")) { item, _ ->
			number(item.extra, "upgrade_level") ?: number(item.extra, "dungeon_item_level")
		},
		attribute("reforge", "modifier", "The reforge's ID (heroic, ancient, withered...).", "Upgrades", Kind.TEXT),
		Field("recombobulated", Kind.FLAG, "A Recombobulator 3000 was used.", "Upgrades", values = YES_NO) { item, _ -> (number(item.extra, "rarity_upgrades") ?: 0.0) > 0 },
		attribute("hot_potato", "hot_potato_count", "Hot and Fuming Potato Books (0 to 15).", "Upgrades"),
		Field("skin", Kind.TEXT, "The skin applied (on a pet, its pet skin).", "Upgrades", values = listOf("none"), more = ::skins) { item, _ ->
			text(item.extra, "skin") ?: petText(item, "skin")
		},
		attribute("dye", "dye_item", "The dye applied (DYE_NECRON, DYE_PURE_BLACK...).", "Upgrades", Kind.TEXT, idsStarting("DYE_")),
		Field("color", Kind.TEXT, "Leather colour as r:g:b or hex (#1793C4).", "Upgrades", normalize = ::rgb) { item, _ -> text(item.extra, "color") },
		attribute("art_of_war", "art_of_war_count", "The Art of War applied.", "Upgrades"),
		Field("art_of_peace", Kind.FLAG, "The Art of Peace applied.", "Upgrades", values = YES_NO) { item, _ -> (number(item.extra, "artOfPeaceApplied") ?: 0.0) > 0 },
		Field("ethermerge", Kind.FLAG, "Etherwarp Merger applied.", "Upgrades", values = YES_NO) { item, _ -> (number(item.extra, "ethermerge") ?: 0.0) > 0 },
		attribute("tuned_transmission", "tuned_transmission", "Transmission Tuners applied.", "Upgrades"),
		attribute("mana_disintegrator", "mana_disintegrator_count", "Mana Disintegrators applied.", "Upgrades"),
		Field("shiny", Kind.FLAG, "A shiny item.", "Upgrades", values = YES_NO) { item, _ -> (number(item.extra, "is_shiny") ?: 0.0) > 0 },
		Field("ability_scroll", Kind.LIST, "Necron's Blade scrolls; all of a,b,c must be there.", "Upgrades", more = { BazaarMarket.idsIn("ABILITY_SCROLL").sorted() },
			values = listOf("IMPLOSION_SCROLL,SHADOW_WARP_SCROLL,WITHER_SHIELD_SCROLL")) { item, _ -> value(item.extra.get("ability_scroll")) },
		attribute("power_scroll", "power_ability_scroll", "The gemstone power scroll (SAPPHIRE_POWER_SCROLL...).", "Upgrades", Kind.TEXT, { BazaarMarket.itemIds().filter { it.endsWith("_POWER_SCROLL") }.sorted() }),
		attribute("enrichment", "talisman_enrichment", "An accessory's enrichment (magic_find...).", "Upgrades", Kind.TEXT),
		Field("wood_singularity", Kind.FLAG, "Wood Singularity applied.", "Upgrades", values = YES_NO) { item, _ -> (number(item.extra, "wood_singularity_count") ?: 0.0) > 0 },
		attribute("polarvoid", "polarvoid", "Polarvoid Books applied.", "Upgrades"),
		attribute("farming_for_dummies", "farming_for_dummies_count", "Farming for Dummies applied.", "Upgrades"),
		attribute("wet_books", "wet_book_count", "Wet Books applied.", "Upgrades"),
		attribute("bookworm", "bookworm_books", "Bookworm's Favorite Books applied.", "Upgrades"),
		attribute("jalapeno", "jalapeno_count", "Jalapeno Books applied.", "Upgrades"),
		Field("powder_coating", Kind.FLAG, "Divan's Powder Coating applied.", "Upgrades", values = YES_NO) { item, _ -> (number(item.extra, "divan_powder_coating") ?: 0.0) > 0 },
		Field("rune", Kind.NUMBER, "A rune's level on the item (rune.music, rune.end).", "Upgrades", param = "rune") { item, rune ->
			val runes = item.extra.getCompoundOrEmpty("runes")
			runes.keySet().firstOrNull { it.equals(rune, ignoreCase = true) }?.let { number(runes, it) }
		},
		Field("enchant", Kind.NUMBER, "An enchantment's level (enchant.ultimate_wise, enchant.growth).", "Upgrades", param = "enchantment", values = listOf("5", ">=6"), params = ::enchantments) { item, name ->
			number(item.extra.getCompoundOrEmpty("enchantments"), name.lowercase(Locale.ROOT))
		},
		Field("no_other_enchants", Kind.FLAG, "No enchantment at level 6 or more besides those in this watch's enchant filters.", "Upgrades", values = listOf("yes")) { item, named ->
			val allowed = named.split(',').filter { it.isNotEmpty() }.toSet()
			val enchants = item.extra.getCompoundOrEmpty("enchantments")
			enchants.keySet().none { it !in allowed && (number(enchants, it) ?: 0.0) >= 6 }
		},
		Field("attribute", Kind.NUMBER, "An attribute's level (attribute.mana_pool).", "Upgrades", param = "attribute") { item, name ->
			number(item.extra.getCompoundOrEmpty("attributes"), name.lowercase(Locale.ROOT))
		},

		// Gemstones
		Field("gem", Kind.TEXT, "A slot's gem quality (gem.combat_0:PERFECT).", "Gemstones", param = "slot", values = QUALITIES + "none") { item, slot ->
			gemQuality(item.extra.getCompoundOrEmpty("gems"), slot.uppercase(Locale.ROOT))
		},
		Field("gem_type", Kind.TEXT, "The gem in a universal slot (gem_type.combat_0:ONYX).", "Gemstones", param = "slot") { item, slot ->
			text(item.extra.getCompoundOrEmpty("gems"), slot.uppercase(Locale.ROOT) + "_gem")
		},
		Field("unlocked_slots", Kind.NUMBER, "How many gem slots are unlocked.", "Gemstones") { item, _ ->
			item.extra.getCompoundOrEmpty("gems").getListOrEmpty("unlocked_slots").size.toDouble()
		},
		Field("slots", Kind.LIST, "The unlocked slots; all of a,b must be there.", "Gemstones", values = listOf("COMBAT_0,SAPPHIRE_0")) { item, _ ->
			value(item.extra.getCompoundOrEmpty("gems").get("unlocked_slots"))
		},
		Field("perfect_gems", Kind.NUMBER, "How many perfect gems it holds.", "Gemstones") { item, _ ->
			val gems = item.extra.getCompoundOrEmpty("gems")
			gemSlots(gems).count { gemQuality(gems, it) == "PERFECT" }.toDouble()
		},
		Field("flawless_gems", Kind.NUMBER, "How many flawless gems it holds.", "Gemstones") { item, _ ->
			val gems = item.extra.getCompoundOrEmpty("gems")
			gemSlots(gems).count { gemQuality(gems, it) == "FLAWLESS" }.toDouble()
		},

		// Dungeons
		attribute("quality", "baseStatBoostPercentage", "A dungeon drop's quality, 0 to 50 (50/50 is the best).", "Dungeons"),
		attribute("tier", "item_tier", "The dungeon floor it dropped on: 1 to 7, up to 10 for M7.", "Dungeons"),
		attribute("dungeon_req", "dungeon_skill_req", "Its Catacombs requirement (CATACOMBS:24).", "Dungeons", Kind.TEXT),
		attribute("hecatomb_runs", "hecatomb_s_runs", "S runs counted by Hecatomb.", "Dungeons"),

		// Pets
		Field("pet_level", Kind.NUMBER, "A pet's level.", "Pets", values = listOf("100", "200", ">=100")) { item, _ -> PET_LEVEL.find(item.name)?.groupValues?.get(1)?.toDoubleOrNull() },
		Field("pet_item", Kind.TEXT, "The pet's held item (PET_ITEM_TIER_BOOST...).", "Pets", values = listOf("PET_ITEM_TIER_BOOST", "none"), more = { BazaarMarket.idsIn("PET_ITEM").sorted() }) { item, _ -> petText(item, "heldItem") },
		Field("pet_skin", Kind.TEXT, "The pet's skin.", "Pets", values = listOf("none"), more = ::petSkins) { item, _ -> petText(item, "skin") },
		Field("candy", Kind.NUMBER, "Pet candy used (0 to 10).", "Pets", values = listOf("0", "none")) { item, _ -> petNumber(item, "candyUsed") },
		Field("pet_exp", Kind.NUMBER, "The pet's experience.", "Pets") { item, _ -> petNumber(item, "exp") },
		Field("pet", Kind.ANY, "Anything else in the pet's info (pet.type).", "Pets", param = "key") { item, key ->
			item.pet?.entrySet()?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let { if (it.isJsonPrimitive) it.asJsonPrimitive.let { p -> if (p.isNumber) p.asDouble else if (p.isBoolean) p.asBoolean else p.asString } else it.toString() }
		},

		// Tools and misc
		Field("drill_engine", Kind.TEXT, "A drill's engine.", "Tools") { item, _ ->
			text(item.extra, "drill_part_engine") ?: text(item.extra.getCompoundOrEmpty("engine"), "id")
		},
		Field("drill_tank", Kind.TEXT, "A drill's fuel tank.", "Tools") { item, _ ->
			text(item.extra, "drill_part_fuel_tank") ?: text(item.extra.getCompoundOrEmpty("fuel_tank"), "id")
		},
		Field("drill_module", Kind.TEXT, "A drill's upgrade module.", "Tools") { item, _ ->
			text(item.extra, "drill_part_upgrade_module") ?: text(item.extra.getCompoundOrEmpty("upgrade_module"), "id")
		},
		Field("rod_hook", Kind.TEXT, "A rod's hook (hotspot_hook...).", "Tools") { item, _ -> text(item.extra.getCompoundOrEmpty("hook"), "part") },
		Field("rod_line", Kind.TEXT, "A rod's line (titan_line...).", "Tools") { item, _ -> text(item.extra.getCompoundOrEmpty("line"), "part") },
		Field("rod_sinker", Kind.TEXT, "A rod's sinker (hotspot_sinker...).", "Tools") { item, _ -> text(item.extra.getCompoundOrEmpty("sinker"), "part") },
		Field("axe_boosters", Kind.LIST, "An axe's boosters; all of a,b must be there.", "Tools") { item, _ -> value(item.extra.get("boosters")) },
		attribute("pickonimbus_durability", "pickonimbus_durability", "A Pickonimbus' durability left.", "Tools"),
		attribute("cake_year", "new_years_cake", "A New Year Cake's year.", "Misc"),
		attribute("edition", "edition", "An item's edition number.", "Misc"),
		attribute("party_hat_color", "party_hat_color", "A party hat's colour.", "Misc", Kind.TEXT),
		attribute("party_hat_year", "party_hat_year", "A party hat's year.", "Misc"),
		attribute("party_hat_emoji", "party_hat_emoji", "A party hat's emoji.", "Misc", Kind.TEXT),
		attribute("raffle_year", "raffle_year", "Which century's raffle it's from (300, 400, 500).", "Misc"),
		attribute("raffle_win", "raffle_win", "Which raffle roll it's from.", "Misc", Kind.TEXT),
		attribute("winning_bid", "winning_bid", "The winning bid of a Dark Auction item.", "Misc"),
		attribute("collected_coins", "collected_coins", "Coins on a Crown of Avarice.", "Misc"),
		attribute("bass_weight", "bass_weight", "A bass' weight.", "Misc"),
		attribute("chimera_found", "chimera_found", "Chimera books found with a bookshelf.", "Misc"),
		attribute("handles_found", "handles_found", "Handles found.", "Misc"),
		attribute("thunder_charge", "thunder_charge", "Thunder in a Bottle's charge.", "Misc"),
		attribute("intelligence_earned", "intelligence_earned", "Intelligence a Wizard Wand earned.", "Misc"),
		Field("uid", Kind.TEXT, "The item's unique ID (the end of its UUID).", "Misc") { item, _ -> text(item.extra, "uuid")?.substringAfterLast('-') },
		Field("created", Kind.DATE, "When the item was made (2024-06-01; use >= and <=).", "Misc") { item, _ -> number(item.extra, "timestamp") },

		// Counters
		attribute("zombie_kills", "zombie_kills", "Zombies killed (Reaper armour, etc).", "Counters"),
		attribute("spider_kills", "spider_kills", "Spiders killed.", "Counters"),
		attribute("eman_kills", "eman_kills", "Endermen killed.", "Counters"),
		attribute("expertise_kills", "expertise_kills", "Sea creatures killed (Expertise).", "Counters"),
		attribute("raider_kills", "raider_kills", "Raider kills.", "Counters"),
		attribute("blood_god_kills", "blood_god_kills", "Blood God's Crest kills.", "Counters"),
		attribute("runic_kills", "runic_kills", "Runic mobs killed.", "Counters"),
		attribute("blaze_consumer", "blaze_consumer", "Blazes consumed.", "Counters"),
		attribute("stats_book", "stats_book", "Kills counted by the Book of Stats.", "Counters"),
		attribute("champion_xp", "champion_combat_xp", "Combat XP counted by Champion.", "Counters"),
		attribute("toxophilite_xp", "toxophilite_combat_xp", "Combat XP counted by Toxophilite.", "Counters"),
		attribute("cultivating", "farmed_cultivating", "Crops counted by Cultivating.", "Counters"),
		attribute("mined_crops", "mined_crops", "Crops mined.", "Counters"),
		attribute("compact_blocks", "compact_blocks", "Blocks counted by Compact.", "Counters"),
		attribute("logs_cut", "logs_cut", "Logs cut with the axe.", "Counters"),
		attribute("absorb_logs", "absorb_logs_chopped", "Logs counted by Absorb.", "Counters"),

		// Anything else
		Field("attr", Kind.ANY, "Any SkyBlock data on the item by its key (attr.hecatomb_s_runs, attr.gems.COMBAT_0).", "Anything", param = "key") { item, key ->
			value(path(item.extra, key))
		},
	)

	private val byName = fields.associateBy { it.name }

	fun field(name: String): Field? = byName[name.lowercase(Locale.ROOT)]

	// ---- Conditions ----------------------------------------------------------------------------

	internal enum class Op { ANY, NONE, EQ, GE, GT, LE, LT, RANGE }

	internal class Condition(val negate: Boolean, val op: Op, val values: List<String>, val low: Double = 0.0, val high: Double = 0.0)

	/** A filter ready to test: its field, what follows the field's dot, and the condition. */
	class Filter internal constructor(val text: String, val field: Field, val param: String, internal val condition: Condition) {
		fun test(item: AuctionItem): Boolean {
			val value = runCatching { field.extract(item, param) }.getOrNull()
			val result = when (field.kind) {
				Kind.NUMBER, Kind.RARITY, Kind.DATE -> numeric(field, value, condition)
				Kind.TEXT, Kind.CONTAINS -> text(field, value?.toString(), condition)
				Kind.FLAG -> flag(value == true, condition)
				Kind.LIST -> list(value as? List<*>, condition)
				Kind.ANY -> when (value) {
					is Number -> numeric(field, value, condition)
					is Boolean -> flag(value, condition)
					is List<*> -> list(value, condition)
					null -> if (condition.op == Op.EQ) false else numeric(field, null, condition)
					else -> text(field, value.toString(), condition)
				}
			}
			return result != condition.negate
		}
	}

	private val TOKEN = Regex("""^([A-Za-z_]+)(?:\.([^:=<>!]+))?\s*(:|!=|>=|<=|=|>|<)\s*(.+)$""")

	/**
	 * A filter from what was typed (`quality:50`, `quality>=45`, `skin!=none`), written back the
	 * way it's saved, or the reason it isn't one.
	 */
	fun parse(typed: String): Result<Filter> {
		val match = TOKEN.find(typed.trim()) ?: return Result.failure(IllegalArgumentException("Write a filter as field:value, such as quality:50."))
		val (rawName, rawParam, op, rawValue) = match.destructured
		val field = field(rawName) ?: return Result.failure(IllegalArgumentException("There's no filter called $rawName."))
		val param = rawParam.trim()
		if (field.param != null && param.isEmpty()) return Result.failure(IllegalArgumentException("Say which ${field.param}: ${field.name}.<${field.param}>."))
		if (field.param == null && param.isNotEmpty()) return Result.failure(IllegalArgumentException("${field.name} takes nothing after a dot."))
		val value = rawValue.trim().removeSurrounding("\"")
		val conditionText = when (op) {
			"!=" -> "!$value"
			">=", "<=", ">", "<" -> op + value
			else -> value
		}
		val condition = condition(field, conditionText) ?: return Result.failure(IllegalArgumentException("\"$conditionText\" isn't a condition ${field.name} can take."))
		val written = if (conditionText.any { it.isWhitespace() }) "\"$conditionText\"" else conditionText
		val canonical = field.name + (if (param.isNotEmpty()) ".$param" else "") + ":" + written
		return Result.success(Filter(canonical, field, param, condition))
	}

	/**
	 * A watch's filters, ready to test; one that no longer reads is left out. no_other_enchants
	 * learns here which enchantments the others name.
	 */
	fun compile(texts: List<String>): List<Filter> {
		val filters = texts.mapNotNull { parse(it).getOrNull() }
		val named = filters.filter { it.field.name == "enchant" }.joinToString(",") { it.param.lowercase(Locale.ROOT) }
		return filters.map { if (it.field.name == "no_other_enchants") Filter(it.text, it.field, named, it.condition) else it }
	}

	private fun condition(field: Field, typed: String): Condition? {
		var text = typed.trim()
		val negate = text.startsWith("!")
		if (negate) text = text.substring(1).trim()
		if (text.isEmpty()) return null
		val lower = text.lowercase(Locale.ROOT)
		when (lower) {
			"any", "yes", "true" -> return Condition(negate, Op.ANY, emptyList())
			"none", "no", "false" -> return Condition(negate, Op.NONE, emptyList())
		}
		for ((prefix, op) in listOf(">=" to Op.GE, "<=" to Op.LE, ">" to Op.GT, "<" to Op.LT)) {
			if (text.startsWith(prefix)) {
				val n = parseNumber(field, text.removePrefix(prefix).trim()) ?: return null
				return Condition(negate, op, emptyList(), n)
			}
		}
		if (text.endsWith("+")) parseNumber(field, text.dropLast(1).trim())?.let { return Condition(negate, Op.GE, emptyList(), it) }
		val range = text.split("..").takeIf { it.size == 2 } ?: Regex("""^([\d.,]+[kmb]?)-([\d.,]+[kmb]?)$""").find(lower)?.groupValues?.drop(1)
		if (range != null) {
			val low = parseNumber(field, range[0].trim())
			val high = parseNumber(field, range[1].trim())
			if (low != null && high != null) return Condition(negate, Op.RANGE, emptyList(), minOf(low, high), maxOf(low, high))
		}
		val values = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
			.map { value -> field.normalize?.invoke(value) ?: value }
		if (field.kind == Kind.FLAG) return null
		if ((field.kind == Kind.NUMBER || field.kind == Kind.RARITY || field.kind == Kind.DATE) && values.any { parseNumber(field, it) == null }) return null
		return Condition(negate, Op.EQ, values)
	}

	/** "1.5m", "250k", "45"; a rarity by its rank; a date as yyyy-mm-dd. */
	private fun parseNumber(field: Field, text: String): Double? {
		val lower = text.trim().lowercase(Locale.ROOT)
		if (field.kind == Kind.RARITY) RARITIES.indexOf(lower.uppercase(Locale.ROOT)).takeIf { it >= 0 }?.let { return it.toDouble() }
		if (field.kind == Kind.DATE) runCatching { return LocalDate.parse(lower).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli().toDouble() }
		val match = Regex("""^(-?[\d,]*\.?\d+)([kmb]?)$""").find(lower) ?: return null
		val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
		return number * when (match.groupValues[2]) {
			"k" -> 1e3
			"m" -> 1e6
			"b" -> 1e9
			else -> 1.0
		}
	}

	private fun numeric(field: Field, value: Any?, c: Condition): Boolean {
		val x = when {
			field.kind == Kind.RARITY -> (value as? String)?.let { RARITIES.indexOf(it.uppercase(Locale.ROOT)).toDouble() } ?: -1.0
			else -> (value as? Number)?.toDouble() ?: 0.0
		}
		val present = if (field.kind == Kind.RARITY) x >= 0 else x != 0.0
		return when (c.op) {
			Op.ANY -> present
			Op.NONE -> !present
			Op.EQ -> c.values.any { parseNumber(field, it) == x }
			Op.GE -> x >= c.low
			Op.GT -> x > c.low
			Op.LE -> x <= c.low
			Op.LT -> x < c.low
			Op.RANGE -> x >= c.low && x <= c.high
		}
	}

	private fun text(field: Field, value: String?, c: Condition): Boolean {
		val present = !value.isNullOrEmpty()
		return when (c.op) {
			Op.ANY -> present
			Op.NONE -> !present
			Op.EQ -> present && c.values.any { wanted ->
				if (field.kind == Kind.CONTAINS) value.contains(wanted, ignoreCase = true) else glob(wanted).matches(value)
			}
			else -> value?.toDoubleOrNull()?.let { numeric(NUMBER_FIELD, it, c) } ?: false
		}
	}

	private fun flag(value: Boolean, c: Condition): Boolean = when (c.op) {
		Op.ANY -> value
		Op.NONE -> !value
		else -> false
	}

	private fun list(value: List<*>?, c: Condition): Boolean {
		val items = value.orEmpty().map { it.toString() }
		return when (c.op) {
			Op.ANY -> items.isNotEmpty()
			Op.NONE -> items.isEmpty()
			Op.EQ -> c.values.all { wanted -> items.any { glob(wanted).matches(it) } }
			Op.GE -> items.size >= c.low
			Op.GT -> items.size > c.low
			Op.LE -> items.size <= c.low
			Op.LT -> items.size < c.low
			Op.RANGE -> items.size >= c.low && items.size <= c.high
		}
	}

	private val NUMBER_FIELD = Field("", Kind.NUMBER, "", "") { _, _ -> null }

	private val globs = ConcurrentHashMap<String, Regex>()

	/** Case-blind equality, with `*` for anything. */
	private fun glob(pattern: String): Regex = globs.getOrPut(pattern) {
		Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
	}

	// ---- Values that need translating -----------------------------------------------------------

	/** "#1793C4" or "1793C4" as Hypixel writes colours, "23:147:196". */
	private fun rgb(value: String): String? {
		val hex = value.removePrefix("#")
		if (!Regex("^[0-9a-fA-F]{6}$").matches(hex)) return null
		val n = hex.toInt(16)
		return "${n shr 16 and 255}:${n shr 8 and 255}:${n and 255}"
	}

	private val sellers = ConcurrentHashMap<String, String>()
	private val asked = ConcurrentHashMap.newKeySet<String>()

	private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }

	/**
	 * A seller's UUID as the Auction House lists it. A name is looked up with Mojang once; until
	 * the answer comes, the name stands (and matches nothing).
	 */
	private fun sellerUuid(value: String): String? {
		val plain = value.replace("-", "")
		if (Regex("^[0-9a-fA-F]{32}$").matches(plain)) return plain.lowercase(Locale.ROOT)
		if (!Regex("""^\w{1,16}$""").matches(value)) return null
		val key = value.lowercase(Locale.ROOT)
		sellers[key]?.let { return it.ifEmpty { value } }
		if (asked.add(key)) {
			http.sendAsync(
				HttpRequest.newBuilder().uri(URI.create("https://api.mojang.com/users/profiles/minecraft/$key")).timeout(Duration.ofSeconds(10)).GET().build(),
				HttpResponse.BodyHandlers.ofString(),
			).thenAccept { response ->
				val id = if (response.statusCode() == 200) runCatching { JsonParser.parseString(response.body()).asJsonObject.get("id").asString }.getOrNull() else null
				sellers[key] = id?.lowercase(Locale.ROOT).orEmpty()
				if (id == null) Cryptic.LOGGER.info("Auction filters: no player called {}", value)
			}.exceptionally {
				asked.remove(key)
				null
			}
		}
		return value
	}
}
