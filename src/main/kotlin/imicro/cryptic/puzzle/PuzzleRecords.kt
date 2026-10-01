package imicro.cryptic.puzzle

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The fastest each puzzle has ever been solved.
 *
 * The same idea as the terminal records, and a file of its own for the same
 * reason: a personal best is a record of something that happened rather than a
 * preference, and swapping config profiles should not swap away the times.
 * Odin keeps puzzle bests too (BSD 3-Clause, Copyright (c) 2025 odtheking).
 */
object PuzzleRecords {
	private val logger = LoggerFactory.getLogger("cryptic/puzzles")

	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	private val file: Path = FabricLoader.getInstance().configDir
		.resolve("cryptic")
		.resolve("puzzle-records.json")

	private var times: MutableMap<String, Float> = mutableMapOf()
	private var loaded = false

	private fun load(): MutableMap<String, Float> {
		if (loaded) return times
		loaded = true

		if (!Files.exists(file)) return times
		try {
			val type = object : TypeToken<MutableMap<String, Float>>() {}.type
			val read: MutableMap<String, Float>? = Files.newBufferedReader(file).use { gson.fromJson(it, type) }
			if (read != null) times = read
		} catch (error: Exception) {
			logger.warn("Puzzle records could not be read, starting fresh", error)
		}
		return times
	}

	fun best(name: String): Float? = load()[name]

	/**
	 * Records [seconds] for [name] and hands back the time it beat.
	 *
	 * The old best comes back whether or not it was beaten, so the caller can
	 * say what the time was measured against either way.
	 */
	fun record(name: String, seconds: Float): Float? {
		val previous = load()[name]
		if (previous == null || seconds < previous) {
			load()[name] = seconds
			save()
		}
		return previous
	}

	private fun save() {
		try {
			Files.createDirectories(file.parent)
			val temporary = file.resolveSibling("${file.fileName}.tmp")
			Files.newBufferedWriter(temporary).use { gson.toJson(times, it) }
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
		} catch (error: Exception) {
			logger.warn("Puzzle records could not be written", error)
		}
	}
}
