package imicro.cryptic.gui.menu

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * What the menu remembers about how you use it, rather than about how the mod
 * is set up: the modules pinned to Favorites.
 *
 * Kept in its own file (`config/cryptic/menu.json`) rather than in a profile,
 * for the same reason a folded group is not: switching profile changes what
 * the mod does, not where you keep things in its menu.
 */
object MenuPrefs {
	private val path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("menu.json")

	private val favorites: MutableSet<String> by lazy { load() }

	fun isFavorite(id: String) = id in favorites

	fun toggleFavorite(id: String) {
		if (!favorites.add(id)) favorites.remove(id)
		save()
	}

	private fun load(): MutableSet<String> {
		val result = linkedSetOf<String>()
		runCatching {
			if (!Files.exists(path)) return result
			val root = JsonParser.parseString(Files.readString(path)).asJsonObject
			root.getAsJsonArray("favorites")?.forEach { result += it.asString }
		}.onFailure { Cryptic.LOGGER.warn("Could not read {}", path, it) }
		return result
	}

	private fun save() {
		runCatching {
			val root = JsonObject()
			root.add("favorites", JsonArray().apply { favorites.forEach(::add) })
			Files.createDirectories(path.parent)
			Files.writeString(path, root.toString())
		}.onFailure { Cryptic.LOGGER.warn("Could not save {}", path, it) }
	}
}
