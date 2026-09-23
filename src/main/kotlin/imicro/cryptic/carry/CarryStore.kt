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
 * Where carries live between sessions.
 *
 * A file of its own rather than a corner of the profile, for two reasons. A
 * carry is not a preference — nobody wants the list of people they owe bosses
 * to swapped out when they change colour schemes — and profiles are meant to be
 * exported and shared, which a list of customers' names should not be.
 *
 * Everything is written through a temporary file and moved into place, so a
 * crash mid-write cannot leave a half-written list behind.
 */
object CarryStore {
	private val logger = LoggerFactory.getLogger("cryptic/carry")

	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	private val file: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("carries.json")

	/**
	 * How many finished carries are kept.
	 *
	 * The totals are counters and do not depend on this, so the only thing lost
	 * past the cap is the individual rows — and a file that grows forever is a
	 * worse problem than a list that only remembers the last twenty.
	 */
	private const val HISTORY_LIMIT = 20

	/** Everything the store holds, as one object so it is one read and one write. */
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
			// Gson builds objects without running the constructor, so a field
			// missing from the file arrives as null however non-null Kotlin
			// declares it. A hand-edited or half-written file is the normal way
			// that happens, and it must not be an exception on the next read.
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
			// A corrupt file is not worth losing a session over, and it is left
			// on disk rather than overwritten so it can be looked at.
			logger.error("Could not read carries.json; starting from an empty list", error)
			state = Saved()
		} catch (error: Exception) {
			logger.error("Could not read carries.json", error)
			state = Saved()
		}
		return state
	}

	/** Files a finished carry and folds it into the totals. */
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
		while (saved.history!!.size > HISTORY_LIMIT) saved.history.removeAt(0)
		saved.totals!!.carriesCompleted++
		saved.active!!.removeIf { it === carry }
		save()
	}

	/** Records one boss dying, whichever carry it belonged to. */
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

	/** The all-time average time per boss, in milliseconds. */
	fun averageKillMillis(): Long {
		val totals = load().totals!!
		if (totals.bossesKilled == 0) return 0L
		return totals.killMillisTotal / totals.bossesKilled
	}

	fun clearHistory() {
		load().history!!.clear()
		save()
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
