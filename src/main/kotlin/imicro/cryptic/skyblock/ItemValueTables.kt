package imicro.cryptic.skyblock

/**
 * Facts the item value needs that the items list doesn't hold, from SkyHelper Networth (MIT,
 * Copyright (c) 2022 Altpapier): which stone gives each reforge, which item takes an enchantment
 * past the top book, and which enchantments grow by use.
 */
internal object ItemValueTables {
	/** A reforge, as an item's `modifier` names it, to the stone that applies it. */
	val REFORGE_STONES: Map<String, String> = mapOf(
		"stiff" to "HARDENED_WOOD", "trashy" to "OVERFLOWING_TRASH_CAN", "salty" to "SALT_CUBE",
		"aote_stone" to "AOTE_STONE", "blazing" to "BLAZEN_SPHERE", "waxed" to "BLAZE_WAX", "rooted" to "BURROWING_SPORES",
		"calcified" to "CALCIFIED_HEART", "candied" to "CANDY_CORN", "perfect" to "DIAMOND_ATOM", "fleet" to "DIAMONITE",
		"fabled" to "DRAGON_CLAW", "spiked" to "DRAGON_SCALE", "royal" to "DWARVEN_TREASURE", "warped" to "ENDSTONE_GEODE",
		"coldfusion" to "ENTROPY_SUPPRESSOR", "blooming" to "FLOWERING_BOUQUET", "fanged" to "FULL_JAW_FANGING_KIT",
		"jaded" to "JADERALD", "jerry_stone" to "JERRY_STONE", "magnetic" to "LAPIS_CRYSTAL", "earthy" to "LARGE_WALNUT",
		"groovy" to "MANGROVE_GEM", "fortified" to "METEOR_SHARD", "gilded" to "MIDAS_JEWEL", "cubic" to "MOLTEN_CUBE",
		"moonglade" to "MOONGLADE_JEWEL", "lunar" to "MOONSTONE", "necrotic" to "NECROMANCER_BROOCH", "fruitful" to "ONYX",
		"precise" to "OPTICAL_LENS", "mossy" to "OVERGROWN_GRASS", "pitchin" to "PITCHIN_KOI", "undead" to "PREMIUM_FLESH",
		"blood_soaked" to "PRESUMED_GALLON_OF_RED_PAINT", "mithraic" to "PURE_MITHRIL", "reinforced" to "RARE_DIAMOND",
		"ridiculous" to "RED_NOSE", "loving" to "RED_SCARF", "auspicious" to "ROCK_GEMSTONE",
		"treacherous" to "RUSTY_ANCHOR", "headstrong" to "SALMON_OPAL", "strengthened" to "SEARING_STONE",
		"glistening" to "SHINY_PRISM", "bustling" to "SKYMART_BROCHURE", "spiritual" to "SPIRIT_DECOY",
		"squeaky" to "SQUEAKY_TOY", "sunny" to "SUNSTONE", "suspicious" to "SUSPICIOUS_VIAL", "snowy" to "TERRY_SNOWGLOBE",
		"dimensional" to "TITANIUM_TESSERACT", "ambered" to "AMBER_MATERIAL", "beady" to "BEADY_EYES",
		"blessed" to "BLESSED_FRUIT", "bulky" to "BULKY_STONE", "buzzing" to "CLIPPED_WINGS",
		"erudite" to "DAEDALUS_NOTES", "submerged" to "DEEP_SEA_ORB", "renowned" to "DRAGON_HORN",
		"festive" to "FROZEN_BAUBLE", "giant" to "GIANT_TOOTH", "lustrous" to "GLEAMING_CRYSTAL",
		"bountiful" to "GOLDEN_BALL", "chomp" to "KUUDRA_MANDIBLE", "lucky" to "LUCKY_DICE", "mantid" to "MANTID_CLAW",
		"stellar" to "PETRIFIED_STARFALL", "scraped" to "POCKET_ICEBERG", "ancient" to "PRECURSOR_GEAR",
		"refined" to "REFINED_AMBER", "empowered" to "SADAN_BROOCH", "withered" to "WITHER_BLOOD",
		"glacial" to "FRIGID_HUSK", "heated" to "HOT_STUFF", "blood_shot" to "SHRIVELED_CORNEA",
		"overpriced" to "OVERPRICED_DRINK", "deep_fried" to "HASHBROWN", "geometric" to "GEOMETRIC_ODDITY",
		"thorny" to "BLOOMING_THORNS", "sticky" to "EXTREMELY_MILD_ADHESIVE", "majestic" to "MORNING_DEW",
		"marshy" to "MARSHROOM", "dirty" to "DIRT_BOTTLE", "moil" to "MOIL_LOG", "toil" to "TOIL_LOG",
		"greater_spook" to "BOO_STONE",
	)

	/** An item that raises an enchantment to [tier] once its book is one level below. */
	class Upgrade(val item: String, val tier: Int, val oncePerItem: Boolean = false)

	private val TURBO = listOf(Upgrade("TURBO_GOURD", 6, true), Upgrade("ENCHANTED_TURBO_GOURD", 7, true))

	val ENCHANTMENT_UPGRADES: Map<String, List<Upgrade>> = mapOf(
		"SCAVENGER" to listOf(Upgrade("GOLDEN_BOUNTY", 6)),
		"PESTERMINATOR" to listOf(Upgrade("PESTHUNTING_GUIDE", 6)),
		"LUCK_OF_THE_SEA" to listOf(Upgrade("GOLD_BOTTLE_CAP", 7)),
		"PISCARY" to listOf(Upgrade("TROUBLED_BUBBLE", 7)),
		"FRAIL" to listOf(Upgrade("SEVERED_PINCER", 7)),
		"SPIKED_HOOK" to listOf(Upgrade("OCTOPUS_TENDRIL", 7)),
		"CHARM" to listOf(Upgrade("CHAIN_END_TIMES", 6)),
		"SMITE" to listOf(Upgrade("SEVERED_HAND", 7)),
		"ENDER_SLAYER" to listOf(Upgrade("ENDSTONE_IDOL", 7)),
		"BANE_OF_ARTHROPODS" to listOf(Upgrade("ENSNARED_SNAIL", 7)),
		"VENOMOUS" to listOf(Upgrade("FATEFUL_STINGER", 7)),
		"THORNS" to listOf(Upgrade("PRICKLY_CREEPER", 4)),
		"SCUBA" to listOf(Upgrade("VIBRANT_CORAL", 6)),
		"FOREST_PLEDGE" to listOf(Upgrade("WATER_HYACINTH", 6)),
		"KARMA" to listOf(Upgrade("FORESTS_FAVOR", 6)),
		"STEALTH" to listOf(Upgrade("DISTANT_ECHO", 6)),
	) + listOf(
		"WHEAT", "CARROT", "POTATO", "PUMPKIN", "MELON", "MUSHROOMS", "COCOA", "CACTI", "CANE", "WARTS",
		"SUNFLOWER", "MOONFLOWER", "ROSE",
	).associate { "TURBO_$it" to TURBO }

	/** Enchantments that level up by use; only their first book was bought. */
	val STACKING = setOf("EXPERTISE", "COMPACT", "ABSORB", "CULTIVATING", "CHAMPION", "HECATOMB", "TOXOPHILITE")

	/** Enchantments an item comes with, by item id, that weren't bought. */
	val BUILT_IN: Map<String, Set<String>> = mapOf(
		"BONE_BOOMERANG" to setOf("OVERLOAD", "POWER", "ULTIMATE_SOUL_EATER"),
		"DEATH_BOW" to setOf("OVERLOAD", "POWER", "ULTIMATE_SOUL_EATER"),
		"GARDENING_AXE" to setOf("REPLENISH"),
		"GARDENING_HOE" to setOf("REPLENISH"),
		"ADVANCED_GARDENING_AXE" to setOf("REPLENISH"),
		"ADVANCED_GARDENING_HOE" to setOf("REPLENISH"),
	)

	/** Scavenger V drops on dungeon gear already. */
	val IGNORED_LEVELS = mapOf("SCAVENGER" to 5)
}
