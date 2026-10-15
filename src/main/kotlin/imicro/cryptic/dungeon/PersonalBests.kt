package imicro.cryptic.dungeon

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Personal bests that are not terminals: a time per name, lowest wins.
 *
 * A file of its own, like the terminal records, because a best is something
 * that happened rather than a preference, and switching profiles should not
 * switch it away.
 */
object PersonalBests {
	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("personal-bests.json")

	private val times = HashMap<String, Double>()
	private var loaded = false

	fun best(name: String): Double? {
		load()
		return times[name]
	}

	/** Records [seconds] for [name]. Returns the best before it, and whether this beat it. */
	fun record(name: String, seconds: Double): Pair<Double?, Boolean> {
		load()
		val previous = times[name]
		if (previous != null && previous <= seconds) return previous to false
		times[name] = seconds
		save()
		return previous to true
	}

	private fun load() {
		if (loaded) return
		loaded = true
		runCatching {
			if (!Files.exists(file)) return
			JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet().forEach { (name, value) -> times[name] = value.asDouble }
		}.onFailure { Cryptic.LOGGER.error("Could not read personal-bests.json", it) }
	}

	private fun save() {
		runCatching {
			val root = JsonObject()
			times.forEach { (name, seconds) -> root.addProperty(name, seconds) }
			Files.createDirectories(file.parent)
			val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
			Files.writeString(temp, gson.toJson(root))
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		}.onFailure { Cryptic.LOGGER.error("Could not save personal-bests.json", it) }
	}
}
