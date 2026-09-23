package imicro.cryptic.terminal

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The fastest each terminal has ever been solved.
 *
 * A file of its own rather than a corner of the profile: a personal best is a
 * record of something that happened, not a preference, and swapping colour
 * schemes should not swap away the times. Ported in spirit from Odin's
 * `PersonalBest` (BSD 3-Clause, Copyright (c) 2025 odtheking).
 *
 * The simulator's times are kept apart from the real ones, because a terminal
 * played against a client with no server in the way is not the same terminal.
 */
object TerminalRecords {
	private val logger = LoggerFactory.getLogger("cryptic/terminals")

	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	private val file: Path = FabricLoader.getInstance().configDir
		.resolve("cryptic")
		.resolve("terminal-records.json")

	@Suppress("RedundantNullableReturnType")
	private data class Saved(
		val terminals: MutableMap<String, Float>? = mutableMapOf(),
		val simulator: MutableMap<String, Float>? = mutableMapOf(),
	)

	private var state = Saved()
	private var loaded = false

	private fun load(): Saved {
		if (loaded) return state
		loaded = true

		if (!Files.exists(file)) return state
		try {
			val type = object : TypeToken<Saved>() {}.type
			val read: Saved? = Files.newBufferedReader(file).use { gson.fromJson(it, type) }
			if (read != null) {
				state = Saved(
					read.terminals ?: mutableMapOf(),
					read.simulator ?: mutableMapOf(),
				)
			}
		} catch (error: JsonSyntaxException) {
			logger.warn("Terminal records could not be read, starting fresh", error)
		} catch (error: Exception) {
			logger.warn("Terminal records could not be read, starting fresh", error)
		}
		return state
	}

	private fun times(simulated: Boolean): MutableMap<String, Float> =
		if (simulated) load().simulator!! else load().terminals!!

	/** The best time for [name], or null when it has never been solved. */
	fun best(name: String, simulated: Boolean): Float? = times(simulated)[name]

	/**
	 * Records [seconds] for [name] and says whether it was a new best.
	 *
	 * The old best comes back with it, so the caller can say what was beaten
	 * rather than only that something was.
	 */
	fun record(name: String, seconds: Float, simulated: Boolean): Float? {
		val previous = times(simulated)[name]
		if (previous != null && previous <= seconds) return previous

		times(simulated)[name] = seconds
		save()
		return previous
	}

	fun reset(simulated: Boolean) {
		times(simulated).clear()
		save()
	}

	private fun save() {
		try {
			Files.createDirectories(file.parent)
			val temporary = file.resolveSibling("${file.fileName}.tmp")
			Files.newBufferedWriter(temporary).use { gson.toJson(state, it) }
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
		} catch (error: Exception) {
			logger.warn("Terminal records could not be written", error)
		}
	}
}
