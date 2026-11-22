package imicro.cryptic.skyblock

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Your time on SkyBlock and what you did with it, for the menu's Profile page: seconds per day and
 * per island, runs finished and slayer bosses slain, all time and this session. Counted from what
 * the client already sees (the sidebar, the tab list, chat); nothing is sent anywhere. Kept per
 * Minecraft account in `stats.json`.
 */
object PlayStats {
	class Stats {
		/** Seconds on SkyBlock by day ("2026-11-22"), the last [DAYS_KEPT] days. */
		val days = sortedMapOf<String, Long>()

		/** Seconds on each island, all time, by the tab list's Area name. */
		val areas = HashMap<String, Long>()
		var seconds = 0L
		var runs = 0
		var bosses = 0
	}

	/** This session's share, since the game started. */
	object Session {
		var seconds = 0L
		var runs = 0
		var bosses = 0
	}

	/** True while the island must be read for the clock: whenever you're on Hypixel. */
	val needsSkyblock: Boolean get() = HypixelServer.connected

	private val accounts = HashMap<String, Stats>()
	private var loaded = false
	private var dirty = false
	private var ticks = 0
	private var sinceSave = 0

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("stats.json")
	private val formatting = Regex("§.")

	private fun account(): String = Minecraft.getInstance().user.profileId.toString()

	/** This account's numbers. */
	fun current(): Stats {
		load()
		return accounts.getOrPut(account()) { Stats() }
	}

	fun today(): Long = current().days[LocalDate.now().toString()] ?: 0L

	/** Seconds on SkyBlock for each of the last [count] days, oldest first, today last. */
	fun lastDays(count: Int): List<Pair<LocalDate, Long>> {
		val days = current().days
		val today = LocalDate.now()
		return (count - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to (days[it.toString()] ?: 0L) } }
	}

	fun tick(client: Minecraft) {
		if (++ticks < 20) return
		ticks = 0
		if (client.level != null && SkyblockLocation.onSkyblock) {
			val stats = current()
			val day = LocalDate.now().toString()
			stats.days[day] = (stats.days[day] ?: 0L) + 1
			val area = SkyblockLocation.area ?: "Unknown"
			stats.areas[area] = (stats.areas[area] ?: 0L) + 1
			stats.seconds++
			Session.seconds++
			dirty = true
		}
		if (++sinceSave >= SAVE_SECONDS && dirty) {
			sinceSave = 0
			save()
		}
	}

	/** A dungeon or Kuudra run just ended. */
	fun onRunFinished() {
		current().runs++
		Session.runs++
		dirty = true
	}

	/** Hypixel's line for a finished slayer quest. Client thread, from the chat packet. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !HypixelServer.connected) return
		if (message.string.replace(formatting, "").trim() != QUEST_COMPLETE) return
		current().bosses++
		Session.bosses++
		dirty = true
	}

	/** Writes what's new, for when the game closes. */
	fun flush() {
		if (dirty) save()
	}

	private fun load() {
		if (loaded) return
		loaded = true
		runCatching {
			if (!Files.exists(file)) return
			val root = JsonParser.parseString(Files.readString(file)).asJsonObject
			for ((id, element) in root.entrySet()) {
				val obj = element.asJsonObject
				accounts[id] = Stats().apply {
					obj.getAsJsonObject("days")?.entrySet()?.forEach { (day, value) -> days[day] = value.asLong }
					obj.getAsJsonObject("areas")?.entrySet()?.forEach { (area, value) -> areas[area] = value.asLong }
					seconds = obj.get("seconds")?.asLong ?: 0L
					runs = obj.get("runs")?.asInt ?: 0
					bosses = obj.get("bosses")?.asInt ?: 0
				}
			}
		}.onFailure { Cryptic.LOGGER.error("Could not read stats.json", it) }
	}

	private fun save() {
		dirty = false
		runCatching {
			val cutoff = LocalDate.now().minusDays(DAYS_KEPT.toLong()).toString()
			val root = JsonObject()
			for ((id, stats) in accounts) {
				// Day keys are ISO dates, so they sort and compare as text.
				stats.days.keys.removeIf { it < cutoff }
				root.add(id, JsonObject().apply {
					add("days", JsonObject().apply { stats.days.forEach { (day, value) -> addProperty(day, value) } })
					add("areas", JsonObject().apply { stats.areas.forEach { (area, value) -> addProperty(area, value) } })
					addProperty("seconds", stats.seconds)
					addProperty("runs", stats.runs)
					addProperty("bosses", stats.bosses)
				})
			}
			Files.createDirectories(file.parent)
			val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
			Files.writeString(temp, gson.toJson(root))
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		}.onFailure { Cryptic.LOGGER.error("Could not save stats.json", it) }
	}

	private const val QUEST_COMPLETE = "SLAYER QUEST COMPLETE!"
	private const val SAVE_SECONDS = 60
	private const val DAYS_KEPT = 90
}
