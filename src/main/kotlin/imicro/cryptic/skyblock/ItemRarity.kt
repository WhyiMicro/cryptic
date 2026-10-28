package imicro.cryptic.skyblock

/** Item rarities as colour codes, the colour Hypixel writes each item's name in. */
object ItemRarity {
	fun code(tier: String?): String? = when (tier) {
		"COMMON" -> "§f"
		"UNCOMMON" -> "§a"
		"RARE" -> "§9"
		"EPIC" -> "§5"
		"LEGENDARY" -> "§6"
		"MYTHIC" -> "§d"
		"DIVINE" -> "§b"
		"SPECIAL", "VERY_SPECIAL" -> "§c"
		"ULTIMATE", "ADMIN" -> "§4"
		else -> null
	}

	private val STARS = Regex("[✪➊➋➌➍➎]")

	/**
	 * The colour of an item written by [name], from its rarity in the items list. A name that isn't
	 * found is tried again without its first words, which can be a reforge ("Heroic Hyperion").
	 * Null when nothing matches; recombobulated items read one rarity low.
	 */
	fun codeForName(name: String): String? {
		var words = name.replace(Regex("§."), "").replace(STARS, "").trim().split(' ').filter { it.isNotEmpty() }
		repeat(3) {
			if (words.isEmpty()) return null
			BazaarMarket.idForName(words.joinToString(" "))?.let { id -> code(BazaarMarket.tier(id))?.let { return it } }
			words = words.drop(1)
		}
		return null
	}
}
