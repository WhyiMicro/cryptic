package imicro.cryptic.skyblock

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic

/**
 * Crafting recipes that turn Bazaar products into another Bazaar product, from the NEU item repo:
 * what a craft flip buys and what it sells.
 */
object BazaarRecipes {
	/**
	 * One way to craft a product: [needs] is how many of each ingredient one craft takes, [makes]
	 * how many it gives, [requires] what unlocks it ("Diamond VIII"). Read by Gson, so all defaulted.
	 */
	class Recipe(val makes: Int = 1, val needs: Map<String, Int> = emptyMap(), val requires: String? = null)

	private val table: Map<String, List<Recipe>> by lazy {
		runCatching {
			val stream = BazaarRecipes::class.java.getResourceAsStream("/assets/cryptic/bazaar/recipes.json") ?: error("missing from the jar")
			val type = object : TypeToken<Map<String, List<Recipe>>>() {}.type
			stream.reader(Charsets.UTF_8).use { Gson().fromJson<Map<String, List<Recipe>>>(it, type) }
		}.onFailure { Cryptic.LOGGER.warn("Could not read the Bazaar recipes", it) }.getOrNull() ?: emptyMap()
	}

	/** The products that can be crafted. */
	val ids: Set<String> get() = table.keys

	fun of(id: String): List<Recipe> = table[id] ?: emptyList()
}
