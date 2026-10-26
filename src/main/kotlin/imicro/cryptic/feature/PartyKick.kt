package imicro.cryptic.feature

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Party Finder's checks on who joins: Odin's auto kick (S+ time, secrets, magical power, API), the
 * [PartyBlacklist], and NoFrills' duplicate class alert before a run starts.
 *
 * Profiles come from Odin's Hypixel API proxy, which needs no key; only the joiner's UUID is sent.
 */
object PartyKick {
	private val kickSection = SectionModuleSetting("auto_kick_section", "Auto kick")

	@JvmField
	val autoKick = ToggleModuleSetting(
		id = "auto_kick",
		label = "Auto kick",
		defaultValue = false,
		description = "Kicks a player who joins from the party finder without your requirements, while you lead. " +
			"Their profile is read through Odin's Hypixel API proxy (hypixel.odtheking.com), so no API key is needed.",
	)

	@JvmField
	val floor = DropdownModuleSetting(
		id = "kick_floor",
		label = "Floor",
		options = (1..7).map { "Floor $it" },
		defaultIndex = 6,
		description = "The floor whose S+ time is checked.",
		visibleIf = { autoKick.value },
	)

	@JvmField
	val master = ToggleModuleSetting("kick_master", "Master Mode", true, "Checks the Master Mode floor.", visibleIf = { autoKick.value })

	@JvmField
	val slowestTime = SliderModuleSetting(
		id = "kick_time",
		label = "Slowest S+",
		defaultValue = 0.0, min = 0.0, max = 600.0, step = 5.0, unit = "s",
		description = "Kicks when their fastest S+ on the floor is slower than this, or missing. 0 doesn't check.",
		visibleIf = { autoKick.value },
	)

	@JvmField
	val minSecrets = SliderModuleSetting(
		id = "kick_secrets",
		label = "Fewest secrets",
		defaultValue = 0.0, min = 0.0, max = 200.0, step = 1.0, unit = "k",
		description = "Kicks below this many thousand secrets found. 0 doesn't check.",
		visibleIf = { autoKick.value },
	)

	@JvmField
	val minMagicalPower = SliderModuleSetting(
		id = "kick_mp",
		label = "Lowest magical power",
		defaultValue = 0.0, min = 0.0, max = 2000.0, step = 10.0,
		description = "Kicks below this magical power (their highest). 0 doesn't check.",
		visibleIf = { autoKick.value },
	)

	@JvmField
	val apiOff = ToggleModuleSetting(
		id = "kick_api_off",
		label = "Kick if API is off",
		defaultValue = false,
		description = "Kicks a player whose inventory API is off.",
		visibleIf = { autoKick.value },
	)

	@JvmField
	val inform = ToggleModuleSetting(
		id = "kick_inform",
		label = "Say why in party chat",
		defaultValue = false,
		description = "Before kicking, says in party chat which requirements they missed.",
		visibleIf = { autoKick.value },
	)

	@JvmField
	val remember = ToggleModuleSetting(
		id = "kick_remember",
		label = "Remember kicked players",
		defaultValue = true,
		description = "Kicks a player again straight away if they rejoin, until the game closes.",
		visibleIf = { autoKick.value },
	)

	private val alertSection = SectionModuleSetting("party_alert_section", "Alerts")

	@JvmField
	val dupeClass = ToggleModuleSetting(
		id = "dupe_class_alert",
		label = "Duplicate class alert",
		defaultValue = true,
		description = "When you enter a dungeon with two players on the same class, and when someone switches into a clash before the start.",
	)

	val settings = listOf(
		kickSection, autoKick.withMore(floor, master, slowestTime, minSecrets, minMagicalPower, apiOff, inform, remember),
		alertSection, dupeClass,
	)

	private val enabled get() = PartyFinder.module.enabled

	val needsTeam: Boolean get() = enabled && dupeClass.value

	/** "Party Finder > Name joined the dungeon group! (Mage Level 38)", and an invited join. */
	private val finderJoined = Regex("""^Party Finder > (?:\[[^\]]+] )?(\w{1,16}) joined the dungeon group!""")
	private val partyJoined = Regex("""^(?:\[[^\]]+] )?(\w{1,16}) joined the party\.$""")
	private val FORMATTING = Regex("§.")

	/** Names kicked this session, lower case. */
	private val kicked = HashSet<String>()

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		try {
			for (raw in message.string.split('\n')) {
				val line = raw.replace(FORMATTING, "").trim()
				readClass(line)
				finderJoined.find(line)?.let { onJoined(it.groupValues[1], fromFinder = true) }
					?: partyJoined.find(line)?.let { onJoined(it.groupValues[1], fromFinder = false) }
			}
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Party Finder could not read a join", error)
		}
	}

	private fun onJoined(name: String, fromFinder: Boolean) {
		if (name.equals(Minecraft.getInstance().player?.gameProfile?.name, ignoreCase = true)) return
		if (PartyBlacklist.module.enabled && PartyBlacklist.find(name) != null) {
			PartyBlacklist.onJoined(name)
			return
		}
		if (!enabled || !autoKick.value || !fromFinder || !PartyFeatures.mayLeadParty()) return
		if (remember.value && name.lowercase(Locale.ROOT) in kicked) {
			kick(name, listOf("kicked before"))
			return
		}
		if (slowestTime.value <= 0 && minSecrets.value <= 0 && minMagicalPower.value <= 0 && !apiOff.value) return
		Profiles.fetch(name, withSecrets = minSecrets.value > 0).whenComplete { profile, error ->
			Minecraft.getInstance().execute {
				if (error != null || profile == null) {
					note("Couldn't check $name: ${(error?.cause ?: error)?.message ?: "no profile"}")
					return@execute
				}
				val reasons = missed(profile)
				if (reasons.isNotEmpty() && PartyFeatures.mayLeadParty()) kick(name, reasons)
			}
		}
	}

	/** Which requirements [profile] misses, worded for party chat. */
	private fun missed(profile: Profile): List<String> {
		val member = profile.member
		val reasons = mutableListOf<String>()
		val dungeons = member.obj("dungeons")
		val number = floor.selectedIndex + 1
		val floorName = (if (master.value) "M" else "F") + number

		if (slowestTime.value > 0) {
			val type = dungeons?.obj("dungeon_types")?.obj(if (master.value) "master_catacombs" else "catacombs")
			val millis = type?.obj("fastest_time_s_plus")?.num(number.toString())
			val limit = slowestTime.value.toLong()
			when {
				millis == null -> reasons += "no $floorName S+"
				millis / 1000 > limit -> reasons += "$floorName S+ ${time(millis.toLong() / 1000)} (needs ${time(limit)})"
			}
		}
		if (minSecrets.value > 0) {
			val secrets = profile.secrets ?: 0L
			val needed = minSecrets.value.toLong() * 1000
			if (secrets < needed) reasons += "${thousands(secrets)} secrets (needs ${thousands(needed)})"
		}
		if (minMagicalPower.value > 0) {
			val mp = member.obj("accessory_bag_storage")?.num("highest_magical_power")?.toInt()
			val needed = minMagicalPower.value.toInt()
			if (mp != null && mp < needed) reasons += "$mp MP (needs $needed)"
		}
		if (apiOff.value) {
			val data = member.obj("inventory")?.obj("ender_chest_contents")?.get("data")
			if (data == null || !data.isJsonPrimitive || data.asString.isEmpty()) reasons += "inventory API off"
		}
		return reasons
	}

	private fun kick(name: String, reasons: List<String>) {
		kicked += name.lowercase(Locale.ROOT)
		val why = reasons.joinToString(", ")
		note("Kicking $name: $why")
		if (inform.value) PartyFeatures.enqueue("pc Kicking $name: $why")
		PartyFeatures.enqueue("party kick $name")
	}

	private fun time(seconds: Long): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

	private fun thousands(count: Long): String =
		if (count < 1000) count.toString() else String.format(Locale.ROOT, "%.1fk", count / 1000.0).replace(".0k", "k")

	private fun note(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$text"))
	}

	// ---- Duplicate classes -------------------------------------------------------------

	/** The duplicates last told about, so each set is told once. */
	private var toldDupes = ""

	/**
	 * Each party member's class as Party Finder and the dungeon's lobby say it, before the tab list
	 * has them: joining the group, changing class, picking one in the lobby, and the listing you
	 * joined from.
	 */
	private val partyClasses = HashMap<String, DungeonTeam.DungeonClass>()

	private val joinedWithClass = Regex("""^Party Finder > (?:\[[^\]]+] )?(\w{1,16}) joined the dungeon group! \((\w+) Level \d+\)""")
	private val setClass = Regex("""^Party Finder > (?:\[[^\]]+] )?(\w{1,16}) set their class to (\w+) Level \d+!""")
	private val selectedClass = Regex("""^(?:\[[^\]]+] )?(\w{1,16}) selected the (\w+) Class!$""")
	private val ownClass = Regex("""^You have selected the (\w+) Dungeon Class!$""")
	private val leftParty = Regex("""^(?:\[[^\]]+] )?(\w{1,16}) (?:has left the party|has been removed from the party)\.$""")
	private val partyGone = listOf("You left the party.", "The party was disbanded", "You have been kicked from the party", "You are not currently in a party.")

	private fun classNamed(name: String): DungeonTeam.DungeonClass? =
		DungeonTeam.DungeonClass.entries.firstOrNull { it.name.equals(name, ignoreCase = true) && it != DungeonTeam.DungeonClass.UNKNOWN }

	/** The class you last picked yourself, which the tab list can be slow to show. */
	private var ownPick: DungeonTeam.DungeonClass? = null

	private fun readClass(line: String) {
		ownClass.find(line)?.let { match ->
			val clazz = classNamed(match.groupValues[1]) ?: return
			ownPick = clazz
			Minecraft.getInstance().player?.gameProfile?.name?.let { partyClasses[it] = clazz }
			return
		}
		(joinedWithClass.find(line) ?: setClass.find(line) ?: selectedClass.find(line))?.let { match ->
			classNamed(match.groupValues[2])?.let { partyClasses[match.groupValues[1]] = it }
			return
		}
		leftParty.find(line)?.let { partyClasses.remove(it.groupValues[1]) }
		if (partyGone.any { line.startsWith(it) }) {
			partyClasses.clear()
			ownPick = null
		}
	}

	/** The members and classes of the Party Finder listing you're joining, filling in the rest. */
	fun rememberListing(members: Map<String, String>) {
		for ((name, clazz) in members) classNamed(clazz)?.let { partyClasses.putIfAbsent(name, it) }
	}

	/** Whether the run has begun in this dungeon, after which there's no point warning. */
	private var runBegan = false

	fun tick() {
		if (!needsTeam || !DungeonTeam.inDungeons) {
			toldDupes = ""
			runBegan = false
			return
		}
		if (DungeonRun.started) {
			runBegan = true
			ownPick = null
		}
		if (runBegan) return
		// What the lobby lines said, then the tab list where it knows better, except for your own
		// pick, which is newer than the tab list for a while.
		val known = HashMap(partyClasses)
		DungeonTeam.classes.forEach { (name, clazz) -> if (clazz != DungeonTeam.DungeonClass.UNKNOWN) known[name] = clazz }
		ownPick?.let { pick -> Minecraft.getInstance().player?.gameProfile?.name?.let { known[it] = pick } }
		val dupes = known.entries
			.groupBy({ it.value }, { it.key })
			.filterValues { it.size > 1 }
		val key = dupes.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.sorted()}" }
		if (key == toldDupes) return
		toldDupes = key
		if (dupes.isEmpty()) return
		val text = dupes.entries.joinToString("; ") { (dungeonClass, names) ->
			"${names.size} ${className(dungeonClass)}s (${names.joinToString(", ")})"
		}
		Toasts.show("Duplicate class", text, error = true)
	}

	private fun className(dungeonClass: DungeonTeam.DungeonClass): String =
		dungeonClass.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

	private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
	private fun JsonObject.num(key: String): Double? =
		get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

	/** A joiner's member data on their selected profile, and their secrets on every profile. */
	private class Profile(val member: JsonObject, val secrets: Long?)

	/** Name to UUID through Mojang, then the profile (and secrets) through Odin's proxy. */
	private object Profiles {
		private const val PROFILE_URL = "https://hypixel.odtheking.com/get/"
		private const val SECRETS_URL = "https://hypixel.odtheking.com/secrets/"
		private const val UUID_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/"
		private const val CACHE_MILLIS = 10 * 60 * 1000L

		private val http: HttpClient by lazy {
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
		}

		private class Cached(val profile: Profile, val at: Long)

		private val cache = ConcurrentHashMap<String, Cached>()

		fun fetch(name: String, withSecrets: Boolean): CompletableFuture<Profile> {
			val key = name.lowercase(Locale.ROOT)
			cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MILLIS && (!withSecrets || it.profile.secrets != null) }
				?.let { return CompletableFuture.completedFuture(it.profile) }
			return get(UUID_URL + URLEncoder.encode(name, StandardCharsets.UTF_8))
				.thenCompose { body ->
					val uuid = body.asJsonObject.get("id")?.asString ?: throw IllegalStateException("no such player")
					val member = get(PROFILE_URL + uuid).thenApply { member(it, uuid) }
					if (!withSecrets) return@thenCompose member.thenApply { Profile(it, null) }
					member.thenCombine(get(SECRETS_URL + uuid)) { data, secrets -> Profile(data, secrets.asLong) }
				}
				.whenComplete { profile, _ -> if (profile != null) cache[key] = Cached(profile, System.currentTimeMillis()) }
		}

		private fun member(body: JsonElement, uuid: String): JsonObject {
			val root = body.asJsonObject
			root.get("cause")?.takeIf { it.isJsonPrimitive }?.let { throw IllegalStateException(it.asString) }
			val profiles = root.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray
				?: throw IllegalStateException("no SkyBlock profiles")
			val selected = profiles.map { it.asJsonObject }.firstOrNull { it.get("selected")?.asBoolean == true }
				?: throw IllegalStateException("no selected profile")
			return selected.getAsJsonObject("members")?.getAsJsonObject(uuid.replace("-", ""))
				?: throw IllegalStateException("not on their selected profile")
		}

		private fun get(url: String): CompletableFuture<JsonElement> {
			val request = HttpRequest.newBuilder(URI.create(url))
				.header("Accept", "application/json")
				.header("User-Agent", "Cryptic")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build()
			return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
				if (response.statusCode() == 404) throw IllegalStateException("no such player")
				if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
				JsonParser.parseString(response.body())
			}
		}
	}
}
