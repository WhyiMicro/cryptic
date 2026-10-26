package imicro.cryptic.carry

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Where carries are saved between sessions. Kept out of the profile, since profiles get shared and
 * a list of customers' names shouldn't be. Written through a temporary file so a crash can't leave
 * half a file.
 */
object CarryStore {
	private val logger = LoggerFactory.getLogger("cryptic/carry")

	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	private val file: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("carries.json")

	/** How many finished carries are kept. The totals don't depend on this. */
	private const val HISTORY_LIMIT = 20

	@Suppress("RedundantNullableReturnType")
	private data class Saved(
		val active: MutableList<Carry>? = mutableListOf(),
		val history: MutableList<FinishedCarry>? = mutableListOf(),
		val totals: CarryTotals? = CarryTotals(),
	)

	private var state = Saved()
	private var loaded = false

	val active: MutableList<Carry> get() = load().active!!
	val history: List<FinishedCarry> get() = load().history!!
	val totals: CarryTotals get() = load().totals!!

	private fun load(): Saved {
		if (loaded) return state
		loaded = true

		if (!Files.exists(file)) return state
		try {
			val type = object: TypeToken<Saved>() {}.type
			val read = gson.fromJson<Saved>(Files.readString(file), type)
			// Gson skips constructors, so a field missing from the file arrives as null whatever
			// Kotlin says.
			state = if (read == null) {
				Saved()
			} else {
				Saved(
					read.active ?: mutableListOf(),
					read.history ?: mutableListOf(),
					read.totals ?: CarryTotals(),
				)
			}
		} catch (error: JsonSyntaxException) {
			// A corrupt file is left on disk for inspection rather than overwritten.
			logger.error("Could not read carries.json; starting from an empty list", error)
			state = Saved()
		} catch (error: Exception) {
			logger.error("Could not read carries.json", error)
			state = Saved()
		}
		return state
	}

	fun complete(carry: Carry) {
		val saved = load()
		saved.history!!.add(
			FinishedCarry(
				name = carry.name,
				tier = carry.tier,
				count = carry.done,
				durationMillis = carry.elapsed,
				completedAt = System.currentTimeMillis(),
				type = FinishedCarry.VOIDGLOOM,
				ordered = carry.ordered,
			),
		)
		while (saved.history.size > HISTORY_LIMIT) saved.history.removeAt(0)
		saved.totals!!.carriesCompleted++
		saved.active!!.removeIf { it === carry }
		save()
	}

	fun recordKill(millis: Long) {
		val totals = load().totals!!
		totals.bossesKilled++
		if (millis > 0) {
			totals.lastKillMillis = millis
			totals.killMillisTotal += millis
			if (totals.fastestKillMillis == 0L || millis < totals.fastestKillMillis) {
				totals.fastestKillMillis = millis
			}
		}
	}

	fun averageKillMillis(): Long {
		val totals = load().totals!!
		if (totals.bossesKilled == 0) return 0L
		return totals.killMillisTotal / totals.bossesKilled
	}

	fun save() {
		val saved = load()
		try {
			Files.createDirectories(file.parent)
			val temporary = file.resolveSibling("carries.json.tmp")
			Files.writeString(temporary, gson.toJson(saved))
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
		} catch (error: Exception) {
			logger.error("Could not write carries.json", error)
		}
	}
}
