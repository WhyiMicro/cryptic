package imicro.cryptic.experiment

import net.minecraft.client.Minecraft

/**
 * Which pet is out, read off the tab list.
 *
 * SkyBlock has no client-facing way to ask, and the pet itself is no use to
 * look at: plenty of people hide theirs, and a hidden pet has no entity to read
 * a tag off. The tab list carries it either way — `[Lvl 100] Guardian` on a row
 * of its own — which is why that is what gets read.
 *
 * Everything here **fails open**. An answer of null means "could not tell", not
 * "no pet", and the one thing that acts on it treats those very differently —
 * closing somebody's menu on a guess would be worse than never closing it at
 * all.
 */
object EquippedPet {
	/**
	 * `[Lvl 100] Guardian`, which is the whole of what the pet row says.
	 *
	 * Nothing else in the tab list is written this way, so the level marker is
	 * enough on its own and no "Pet:" heading has to be found first.
	 */
	private val PET_ROW = Regex("""\[Lvl \d+]\s*(.+)""")

	private val formattingPattern = Regex("§.")

	/** The name of the pet that is out — "Guardian" — or null if none is listed. */
	fun name(): String? {
		val connection = Minecraft.getInstance().connection ?: return null

		for (info in connection.onlinePlayers) {
			val row = info.tabListDisplayName?.string?.replace(formattingPattern, "") ?: continue
			val name = PET_ROW.find(row)?.groupValues?.get(1) ?: continue
			return name.trim()
		}
		return null
	}

	/**
	 * Whether the pet that is out is definitely not [wanted].
	 *
	 * Three answers collapsed into two on purpose: "a different pet is out" is
	 * the only one that returns true. "That pet is out" and "no idea" both
	 * return false, because the caller shuts a menu on the strength of this and
	 * only one of the three is worth shutting a menu over.
	 */
	fun isDefinitelyNot(wanted: String): Boolean {
		val name = name() ?: return false
		return !name.contains(wanted, ignoreCase = true)
	}

	/**
	 * Every tab row that has anything on it, for `/cryptic debug pet`.
	 *
	 * What Hypixel writes where in the tab list is the one part of this that
	 * cannot be worked out from outside the game, and it decides whether the
	 * reminder can ever fire.
	 */
	fun describe(): List<String> {
		val connection = Minecraft.getInstance().connection ?: return listOf("Not connected.")

		val rows = connection.onlinePlayers
			.mapNotNull { it.tabListDisplayName?.string?.replace(formattingPattern, "") }
			.filter { it.isNotBlank() }

		return listOf("${rows.size} tab rows:") +
			rows.map { "  \"$it\"" } +
			"Read as pet: ${name() ?: "nothing recognised"}"
	}
}
