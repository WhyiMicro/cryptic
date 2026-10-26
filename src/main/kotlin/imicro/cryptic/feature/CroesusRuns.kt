package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Croesus's list of runs, mirrored and kept across sessions. Croesus keeps your last sixty runs for
 * 72 hours, newest first, and shows nothing about a run's chests until it's opened, so what each
 * run was worth is recorded here as it's seen (SkyHanni's idea): a finished dungeon goes on the
 * front, the oldest fall off, and each Croesus slot is a list position (28 per page). Each run
 * keeps its floor, so a list shifted by a run Cryptic missed doesn't colour the wrong runs. Per
 * Minecraft account.
 */
object CroesusRuns {
	const val MAX_RUNS = 60
	private const val KEPT_MILLIS = 72L * 60 * 60 * 1000

	private const val PER_PAGE = 28

	class Run(
		val floor: String,
		val finishedAt: Long,
		var keyProfits: Map<String, Double>? = null,
	)

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("croesus-runs.json")

	private val lists = HashMap<String, MutableList<Run>>()
	private var loaded = false

	private fun account(): String? = Minecraft.getInstance().user.profileId.toString()

	private fun runs(): MutableList<Run>? {
		load()
		val id = account() ?: return null
		return lists.getOrPut(id) { mutableListOf() }.also(::prune)
	}

	fun onRunFinished(floor: String) {
		val list = runs() ?: return
		list.add(0, Run(floor, System.currentTimeMillis()))
		prune(list)
		save()
	}

	/** The list position of the run in [slot] on [page] (from 1), or null for an empty slot. */
	fun indexOf(page: Int, slot: Int): Int? {
		val inPage = when (slot) {
			in 10..16 -> slot - 10
			in 19..25 -> slot - 12
			in 28..34 -> slot - 14
			in 37..43 -> slot - 16
			else -> return null
		}
		return inPage + (page - 1).coerceAtLeast(0) * PER_PAGE
	}

	/** The run at [index], if its floor matches what Croesus shows. */
	fun at(index: Int, floor: String): Run? = runs()?.getOrNull(index)?.takeIf { it.floor == floor }

	/**
	 * Records what a run's chests are worth at [index]. A floor mismatch (the list shifted)
	 * replaces the old entry.
	 */
	fun learn(index: Int, floor: String, keyProfits: Map<String, Double>) {
		val list = runs() ?: return
		val known = list.getOrNull(index)
		when {
			known != null && known.floor == floor -> {
				// Read every tick the run is open; only changes are written.
				if (known.keyProfits == keyProfits) return
				known.keyProfits = keyProfits
			}
			index < list.size -> list[index] = Run(floor, known?.finishedAt ?: System.currentTimeMillis(), keyProfits)
			index < MAX_RUNS -> {
				// Past the end: padded with unknowns.
				while (list.size < index) list += Run("", System.currentTimeMillis())
				list += Run(floor, System.currentTimeMillis(), keyProfits)
			}
			else -> return
		}
		save()
	}

	private fun prune(list: MutableList<Run>) {
		val cutoff = System.currentTimeMillis() - KEPT_MILLIS
		list.removeIf { it.finishedAt < cutoff }
		while (list.size > MAX_RUNS) list.removeAt(list.lastIndex)
	}

	private fun load() {
		if (loaded) return
		loaded = true
		runCatching {
			if (!Files.exists(file)) return
			val root = JsonParser.parseString(Files.readString(file)).asJsonObject
			for ((id, element) in root.entrySet()) {
				lists[id] = element.asJsonArray.map { item ->
					val obj = item.asJsonObject
					Run(
						floor = obj.get("floor").asString,
						finishedAt = obj.get("finished").asLong,
						keyProfits = obj.getAsJsonObject("profits")?.entrySet()?.associate { (type, value) -> type to value.asDouble },
					)
				}.toMutableList()
			}
		}.onFailure { Cryptic.LOGGER.error("Could not read croesus-runs.json", it) }
	}

	private fun save() {
		runCatching {
			val root = JsonObject()
			for ((id, list) in lists) {
				root.add(id, JsonArray().apply {
					list.forEach { run ->
						add(JsonObject().apply {
							addProperty("floor", run.floor)
							addProperty("finished", run.finishedAt)
							run.keyProfits?.let { profits ->
								add("profits", JsonObject().apply { profits.forEach { (type, value) -> addProperty(type, value) } })
							}
						})
					}
				})
			}
			Files.createDirectories(file.parent)
			val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
			Files.writeString(temp, gson.toJson(root))
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		}.onFailure { Cryptic.LOGGER.error("Could not save croesus-runs.json", it) }
	}
}
