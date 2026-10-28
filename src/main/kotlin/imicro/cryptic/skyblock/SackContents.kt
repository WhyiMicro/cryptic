package imicro.cryptic.skyblock

import com.google.gson.GsonBuilder
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.player.Inventory
import java.nio.file.Files
import java.util.Locale
import java.util.Optional

/**
 * What your sacks hold, by item name. Opening a sack reads each item's "Stored:" line; in between,
 * Hypixel's "[Sacks] +64 items" lines name every change in their hover text. Items no sack menu has
 * shown start, once a session, from your profile's sacks as Hypixel's API last saved them (through
 * Odin's proxy, no key needed; a few minutes behind).
 */
object SackContents {
	private val FORMATTING = Regex("§.")
	private val SACK_TITLE = Regex("""^(?:.* Sack|Enchanted .* Sack)$""")
	private val STORED = Regex("""Stored: ([\d.,]+[kKmMbB]?)/""")

	/** "  +64 Enchanted Wheat (Agronomy Sack)", in the hover of Hypixel's sack line. */
	private val CHANGE = Regex("""([+-][\d,]+) (.+?) \((.+?)\)""")

	private val counts = HashMap<String, Int>()

	/** Items a sack menu has shown, whose numbers the API mustn't replace. Saved. */
	private val readFromMenu = HashSet<String>()
	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("sacks.json")
	private var loaded = false
	private var dirty = false

	/** How many of the item named [name] your sacks hold, or 0. */
	fun count(name: String): Int {
		load()
		return counts[key(name)] ?: 0
	}

	private fun key(name: String) = name.replace(FORMATTING, "").trim().lowercase(Locale.ROOT)

	private var menuStamp = 0

	/** Reads an open sack's items. Client thread, each tick a sack is open. */
	fun readMenu(screen: Any?) {
		val chest = screen as? AbstractContainerScreen<*> ?: return
		if (!SACK_TITLE.matches(chest.title.string.replace(FORMATTING, "").trim())) return
		load()
		val slots = chest.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }
		val stamp = slots.sumOf { (it.item.hoverName.string.hashCode() * 31 + (it.item.get(DataComponents.LORE)?.lines()?.hashCode() ?: 0)) * (it.index + 1) }
		if (stamp == menuStamp) return
		menuStamp = stamp
		for (slot in slots) {
			val lore = slot.item.get(DataComponents.LORE)?.lines() ?: continue
			val stored = lore.firstNotNullOfOrNull { STORED.find(it.string.replace(FORMATTING, ""))?.groupValues?.get(1) } ?: continue
			val name = key(slot.item.hoverName.string)
			counts[name] = parseCompact(stored)
			readFromMenu += name
			dirty = true
		}
	}

	/** Hypixel's "[Sacks]" lines; the changes are in their hover text. Client thread. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !message.string.replace(FORMATTING, "").trim().startsWith("[Sacks]")) return
		load()
		val hovers = LinkedHashSet<String>()
		message.visit({ style: Style, _: String ->
			(style.hoverEvent as? HoverEvent.ShowText)?.value?.string?.let { hovers += it.replace(FORMATTING, "") }
			Optional.empty<Unit>()
		}, Style.EMPTY)
		for (hover in hovers) {
			for (line in hover.lines()) {
				val match = CHANGE.find(line.trim()) ?: continue
				val delta = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
				val name = key(match.groupValues[2])
				counts[name] = ((counts[name] ?: 0) + delta).coerceAtLeast(0)
				dirty = true
			}
		}
	}

	/** Saves now and then, not on every change. */
	fun tick() {
		if (!dirty) return
		dirty = false
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(Saved(counts, readFromMenu)))
		}.onFailure { Cryptic.LOGGER.warn("Could not save sack contents", it) }
	}

	private class Saved(val counts: Map<String, Int> = emptyMap(), val fromMenu: Set<String> = emptySet())

	// ---- From the API -------------------------------------------------------------------------

	private const val PROFILE_URL = "https://hypixel.odtheking.com/get/"
	private var askedApi = false

	private val http: java.net.http.HttpClient by lazy {
		java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
			.followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
	}

	/**
	 * Once a session, fills in the items no sack menu has shown from your profile's sacks. Called
	 * when they're needed; never blocks.
	 */
	fun ensureFromApi() {
		if (askedApi) return
		val uuid = net.minecraft.client.Minecraft.getInstance().player?.uuid?.toString() ?: return
		askedApi = true
		load()
		val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(PROFILE_URL + uuid))
			.header("Accept", "application/json").header("User-Agent", "Cryptic")
			.timeout(java.time.Duration.ofSeconds(15)).GET().build()
		http.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())
			.thenApply { response ->
				if (response.statusCode() !in 200..299) error("HTTP ${response.statusCode()}")
				val root = com.google.gson.JsonParser.parseString(response.body()).asJsonObject
				val profile = root.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray
					?.map { it.asJsonObject }?.firstOrNull { it.get("selected")?.asBoolean == true } ?: error("no selected profile")
				val member = profile.getAsJsonObject("members")?.getAsJsonObject(uuid.replace("-", "")) ?: error("not on the profile")
				// Hypixel moved them under "inventory"; older saves keep them at the top.
				(member.getAsJsonObject("inventory")?.getAsJsonObject("sacks_counts") ?: member.getAsJsonObject("sacks_counts"))
					?.entrySet()?.associate { (id, count) -> id to count.asInt } ?: error("sacks not shared (inventory API off)")
			}
			.thenAccept { byId ->
				net.minecraft.client.Minecraft.getInstance().execute {
					for ((id, count) in byId) {
						val name = key(BazaarMarket.nameOf(id) ?: continue)
						if (name in readFromMenu) continue
						counts[name] = count
					}
					dirty = true
				}
			}
			.exceptionally { error ->
				Cryptic.LOGGER.warn("Could not read your sacks from the API: {}", error.message)
				null
			}
	}

	private fun load() {
		if (loaded) return
		loaded = true
		runCatching {
			if (!Files.exists(file)) return
			val saved = gson.fromJson(Files.readString(file), Saved::class.java) ?: return
			counts.putAll(saved.counts)
			readFromMenu.addAll(saved.fromMenu)
		}.onFailure { Cryptic.LOGGER.warn("Could not read sack contents", it) }
	}

	/** "1,234", "2.2k", "1M". */
	private fun parseCompact(text: String): Int {
		val number = text.dropLastWhile { it.isLetter() }.replace(",", "").toDoubleOrNull() ?: return 0
		val scale = when (text.lastOrNull()?.lowercaseChar()) {
			'k' -> 1e3
			'm' -> 1e6
			'b' -> 1e9
			else -> 1.0
		}
		return (number * scale).toInt()
	}

	fun describe(): String = "Sacks: ${counts.size} items known, ${counts.values.sum()} in all"
}
