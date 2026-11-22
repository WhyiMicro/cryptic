package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import imicro.cryptic.Cryptic
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.AuctionFilters
import imicro.cryptic.skyblock.AuctionFilters.AuctionItem
import imicro.cryptic.skyblock.AuctionPrices
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.HypixelServer
import imicro.cryptic.skyblock.ItemRarity
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.sounds.SoundEvents
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream

/**
 * The Auction House's watch list: items you want, each with the most you'd pay, and a notification
 * when one is put up for sale. Hypixel's first page of auctions holds every auction started in the
 * last few minutes, so that page is read about once a minute (the API refreshes no faster), and
 * only while something is watched. After a gap every page is read once, for what went up meanwhile.
 * Saved in `config/cryptic/auction-watch.json`; the newest start seen and the auctions told about
 * are kept in `auction-watch-seen.json`.
 */
object AuctionWatch {
	data class Entry(
		/** What it's called: the name typed or the hovered item's. */
		var item: String = "",
		/** The NEU key to match ("HYPERION", "PET-GOLDEN_DRAGON-LEGENDARY"), or empty to match by name. */
		var id: String = "",
		/** The most to pay; 0 for any price. */
		var maxPrice: Long = 0L,
		var binOnly: Boolean = true,
		var added: Long = 0L,
		/** [AuctionFilters] filters, each `field:condition`; all must pass. */
		var filters: MutableList<String> = mutableListOf(),
	) {
		/** No item named: any item that passes the filters. */
		val anyItem: Boolean get() = item.isBlank() && id.isEmpty()

		fun deepCopy() = copy(filters = filters.toMutableList())
	}

	/** An entry with its filters ready to test. */
	private class Watch(val entry: Entry, val filters: List<AuctionFilters.Filter>)

	val entries = mutableListOf<Entry>()

	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("auction-watch.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()

	private const val URL = "https://api.hypixel.net/v2/skyblock/auctions?page="

	/** Hypixel rebuilds the list about once a minute; when a read finds it unchanged, try again sooner. */
	private const val FRESH_WAIT_MILLIS = 60_000L
	private const val STALE_WAIT_MILLIS = 10_000L
	private const val FAILED_WAIT_MILLIS = 60_000L

	/** After a longer gap the first page has moved on; start over rather than report old listings. */
	private const val RESET_GAP_MILLIS = 5 * 60_000L
	private const val LATE_MARGIN_MILLIS = 2 * 60_000L

	/** Notifications per read; a flood beyond this is summed up. Chat takes more. */
	private const val MAX_TOLD = 5
	private const val MAX_CHAT = 20

	/** Told auctions kept; only those near [since] can turn up again. */
	private const val MAX_REMEMBERED = 2000

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
		loadSeen()
	}

	// ---- The list ------------------------------------------------------------------------------

	fun put(entry: Entry, replacing: Entry? = null) {
		if (entry.added == 0L) entry.added = System.currentTimeMillis()
		val index = entries.indexOfFirst { it === replacing }
		if (index >= 0) entries[index] = entry else entries += entry
		save()
		snapshot = entries.map { it.deepCopy() }
	}

	fun remove(entry: Entry) {
		entries.removeIf { it === entry }
		save()
		snapshot = entries.map { it.deepCopy() }
	}

	/**
	 * A watch for what was typed: an item's name ("Hyperion") becomes its id when the items list
	 * knows it, a NEU key is taken as it is, and anything else matches names containing it.
	 */
	fun resolve(typed: String): Entry {
		val text = typed.trim()
		if (text.isEmpty() || text == "*") return Entry()
		if (ID.matches(text)) return Entry(item = BazaarMarket.nameOf(text) ?: text, id = text)
		BazaarMarket.ensureItems()
		val id = BazaarMarket.idForName(text)?.let(::neuKey)
		return Entry(item = id?.let { BazaarMarket.nameOf(it) } ?: text, id = id.orEmpty())
	}

	private val ID = Regex("""^[A-Z0-9_:;\-]+$""")

	/** The Bazaar names books "ENCHANTMENT_ULTIMATE_WISE_5"; auctions are keyed as NEU does. */
	private fun neuKey(id: String): String {
		val book = Regex("""^ENCHANTMENT_(.+)_(\d+)$""").find(id) ?: return id
		return "ENCHANTED_BOOK-${book.groupValues[1]}-${book.groupValues[2]}"
	}

	/** "1.5m", "200k", "2b", "1,250,000": coins, or null when it isn't a price. */
	fun parsePrice(text: String): Long? {
		val match = Regex("""^([\d,]*\.?\d+)\s*([kmb]?)$""").find(text.trim().lowercase(Locale.ROOT)) ?: return null
		val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
		val scale = when (match.groupValues[2]) {
			"k" -> 1e3
			"m" -> 1e6
			"b" -> 1e9
			else -> 1.0
		}
		return (number * scale).toLong().takeIf { it > 0 }
	}

	fun describe(entry: Entry): String = buildString {
		append(if (entry.maxPrice > 0) "Up to ${Bazaar.short(entry.maxPrice.toDouble())}" else "Any price")
		append(if (entry.binOnly) ", BIN only" else ", BIN and auctions")
		if (!entry.anyItem && entry.id.isEmpty()) append(", by name")
		if (entry.filters.isNotEmpty()) append(", ").append(entry.filters.joinToString(" "))
	}

	/** How a watch is named in lists: its item, or "Any item" for one that has only filters. */
	fun title(entry: Entry): String = if (entry.anyItem) "Any item" else entry.item

	// ---- Watching the hovered item ------------------------------------------------------------

	/** The watch key over a menu: the hovered item goes on the list. True takes the key. */
	@JvmStatic
	fun handleKey(screen: AbstractContainerScreen<*>, keyCode: Int): Boolean {
		if (!AuctionHouse.module.enabled || !AuctionHouse.watch.value || !AuctionHouse.watchKey.matches(keyCode)) return false
		val stack = (screen as ContainerScreenAccessor).`cryptic$hoveredSlot`()?.item ?: return false
		if (stack.isEmpty) return false
		val name = stack.hoverName.string.replace(FORMATTING, "").trim()
		val key = AuctionPrices.key(stack)
		if (key == null) {
			Toasts.show(SOURCE, "§7That isn't a SkyBlock item.", color = Toasts.Colors.AUCTION)
			return true
		}
		// A pet is watched at any level, an item under any reforge.
		val id = if (key.startsWith("PET-") && key.count { it == '-' } == 3) key.substringBeforeLast('-') else key
		val existing = entries.firstOrNull { it.id == id }
		if (existing != null) {
			remove(existing)
			Toasts.show(SOURCE, "§7No longer watching ${named(existing.item, null)}§7.", color = Toasts.Colors.AUCTION)
			return true
		}
		val shown = if (id.startsWith("PET-")) name.replace(Regex("""^\[Lvl \d+]\s*"""), "") else BazaarMarket.nameOf(id) ?: name
		put(Entry(item = shown, id = id))
		Toasts.show(SOURCE, "§7Watching ${named(shown, null)}§7 at any price. Set a price on the Auction House page.", color = Toasts.Colors.AUCTION)
		return true
	}

	// ---- Reading the Auction House -------------------------------------------------------------

	/** The list as the fetch thread reads it. */
	@Volatile
	private var snapshot: List<Entry> = emptyList()

	@Volatile
	private var fetching = false

	@Volatile
	private var nextPoll = 0L

	@Volatile
	private var ownUuid = ""

	/** Hypixel's time of the page last read, and its newest start then: older auctions are old news. */
	private var lastUpdated = 0L
	private var lastReadAt = 0L

	/** Saved with [told], so a session picks up where the last one stopped. */
	private var since = 0L

	/** Auctions told about, saved, so each is told once even across sessions. */
	private val told = LinkedHashSet<String>()

	@Volatile
	private var status = "not started"

	private val http: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
	}

	/** One read: what the pages read so far turned up. */
	private class Scan(val watching: List<Watch>, val from: Long, val catchUp: Boolean) {
		var newest = 0L
		var count = 0
		val found = ArrayList<Pair<AuctionItem, Entry>>()

		/** A listing can move to the next page mid-read and be seen twice. */
		val seen = HashSet<String>()
	}

	fun tick(client: Minecraft) {
		if (!AuctionHouse.module.enabled || !AuctionHouse.watch.value || snapshot.isEmpty() || !HypixelServer.connected) return
		val player = client.player ?: return
		val now = System.currentTimeMillis()
		if (fetching || now < nextPoll) return
		resolveNames()
		ownUuid = player.uuid.toString().replace("-", "")
		fetching = true
		// After a gap (offline, another server, the watch off) every page is read for what went up meanwhile.
		val catchUp = now - lastReadAt > RESET_GAP_MILLIS
		// Compiled for each read: a seller's name becomes a UUID once Mojang answers.
		val watching = snapshot.map { Watch(it, AuctionFilters.compile(it.filters)) }
		val scan = Scan(watching, if (since == 0L) 0L else since - LATE_MARGIN_MILLIS, catchUp)
		fetchPage(0).thenCompose { page ->
			val first = page ?: error("no first page")
			val updated = first.get("lastUpdated")?.asLong ?: 0L
			if (!catchUp && updated == lastUpdated) {
				nextPoll = System.currentTimeMillis() + STALE_WAIT_MILLIS
				return@thenCompose CompletableFuture.completedFuture(false)
			}
			read(first, scan)
			// With nothing saved there is nothing to catch up on: the first page sets where to start.
			val pages = if (catchUp && since != 0L) first.get("totalPages")?.asInt ?: 1 else 1
			if (catchUp && pages > 1) status = "catching up: $pages pages"
			readPages(1, pages, scan).thenApply {
				lastUpdated = updated
				true
			}
		}.thenAccept { fresh ->
			if (fresh) finish(scan)
			fetching = false
		}.exceptionally { error ->
			status = "failed: ${error.message}"
			nextPoll = System.currentTimeMillis() + FAILED_WAIT_MILLIS
			Cryptic.LOGGER.warn("Auction Watch could not read the Auction House: {}", error.message)
			fetching = false
			null
		}
	}

	/** A name added before the items list arrived is matched by its id once the list knows it. */
	private fun resolveNames() {
		var changed = false
		for (entry in entries) {
			if (entry.id.isNotEmpty()) continue
			val id = BazaarMarket.idForName(entry.item)?.let(::neuKey) ?: continue
			entry.id = id
			changed = true
		}
		if (!changed) return
		save()
		snapshot = entries.map { it.deepCopy() }
	}

	/** One page as JSON, or null past the last page (the list can shrink mid-read). */
	private fun fetchPage(page: Int): CompletableFuture<JsonObject?> =
		http.sendAsync(
			HttpRequest.newBuilder().uri(URI.create(URL + page)).timeout(Duration.ofSeconds(30))
				.header("User-Agent", "Cryptic").header("Accept-Encoding", "gzip").GET().build(),
			HttpResponse.BodyHandlers.ofByteArray(),
		).thenApply { response ->
			if (response.statusCode() == 404 && page > 0) return@thenApply null
			if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
			val gzip = response.headers().firstValue("Content-Encoding").orElse("").equals("gzip", ignoreCase = true)
			val body = if (gzip) GZIPInputStream(ByteArrayInputStream(response.body())).readAllBytes() else response.body()
			JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
		}

	/** One page after another, each read and dropped, so a catch-up never holds the whole list. */
	private fun readPages(page: Int, pages: Int, scan: Scan): CompletableFuture<Unit> {
		if (page >= pages) return CompletableFuture.completedFuture(Unit)
		return fetchPage(page).thenCompose { json ->
			if (json == null) return@thenCompose CompletableFuture.completedFuture(Unit)
			read(json, scan)
			readPages(page + 1, pages, scan)
		}
	}

	/** On the HTTP thread: the page's auctions newer than [Scan.from], against the list. */
	private fun read(page: JsonObject, scan: Scan) {
		val auctions = page.getAsJsonArray("auctions") ?: return
		scan.count += auctions.size()
		for (element in auctions) {
			val auction = element.asJsonObject
			val start = auction.get("start")?.asLong ?: continue
			if (start > scan.newest) scan.newest = start
			// A margin for auctions that reach the list late; [told] keeps them from being told twice.
			if (scan.from == 0L || start <= scan.from) continue
			val uuid = auction.get("uuid")?.asString ?: continue
			if (uuid in told || !scan.seen.add(uuid) || auction.get("auctioneer")?.asString == ownUuid) continue
			val listing = AuctionItem(auction)
			// Catching up, a watch only counts auctions started after it was added.
			val watch = scan.watching.firstOrNull { (!scan.catchUp || start > it.entry.added) && matches(it, listing) } ?: continue
			scan.found += listing to watch.entry
		}
	}

	/** On the HTTP thread, once every page of a read is in. */
	private fun finish(scan: Scan) {
		val now = System.currentTimeMillis()
		nextPoll = now + FRESH_WAIT_MILLIS
		lastReadAt = now
		if (scan.newest > since) since = scan.newest
		scan.found.forEach { told += it.first.uuid }
		while (told.size > MAX_REMEMBERED) told.remove(told.first())
		saveSeen()
		status = "read ${scan.count} auctions${if (scan.catchUp) " (caught up)" else ""}, ${scan.found.size} new matches"
		if (scan.found.isNotEmpty()) Minecraft.getInstance().execute { tell(scan.found, scan.catchUp) }
	}

	private fun matches(watch: Watch, listing: AuctionItem): Boolean {
		val entry = watch.entry
		// A bin filter decides on its own.
		if (entry.binOnly && !listing.bin && watch.filters.none { it.field.name == "bin" }) return false
		if (entry.maxPrice > 0 && listing.price > entry.maxPrice) return false
		val item = when {
			entry.anyItem -> watch.filters.isNotEmpty()
			entry.id.isEmpty() -> listing.name.contains(entry.item.trim(), ignoreCase = true)
			// A pet's key ends in its level, so the pet without one takes every level.
			else -> listing.key?.let { it == entry.id || it.startsWith(entry.id + "-") } == true
		}
		return item && watch.filters.all { it.test(listing) }
	}

	/** Tests [entry] on one auction as Hypixel's list writes it. */
	internal fun test(entry: Entry, auction: JsonObject): Boolean = matches(Watch(entry, AuctionFilters.compile(entry.filters)), AuctionItem(auction))

	// ---- Telling --------------------------------------------------------------------------------

	private const val SOURCE = "Auction Watch"
	private val FORMATTING = Regex("§.")

	private fun tell(found: List<Pair<AuctionItem, Entry>>, away: Boolean) {
		if (!AuctionHouse.module.enabled || !AuctionHouse.watch.value) return
		if (AuctionHouse.quietInDungeons.value && DungeonLocation.inDungeon) return
		val client = Minecraft.getInstance()
		if (away) {
			val text = "§7${found.size} watched listing${if (found.size == 1) "" else "s"} went up while you were away."
			Toasts.show(SOURCE, text, color = Toasts.Colors.AUCTION)
			if (AuctionHouse.watchChat.value) client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] $text"))
		}
		found.forEachIndexed { index, (listing, entry) ->
			val kind = if (listing.bin) "BIN" else "Auction"
			val price = Bazaar.short(listing.price.toDouble())
			Cryptic.LOGGER.info("Auction Watch: {} {} for {} ({})", kind, listing.name, price, entry.item)
			if (index < MAX_TOLD) Toasts.show(SOURCE, "§7$kind: ${named(listing.name, listing.tier)} §7for §6$price§7.", color = Toasts.Colors.AUCTION)
			if (AuctionHouse.watchChat.value && index < MAX_CHAT) {
				val open = Component.literal(" §e[Open]").withStyle {
					it.withClickEvent(ClickEvent.RunCommand("/viewauction ${listing.uuid}"))
						.withHoverEvent(HoverEvent.ShowText(Component.literal("Open this auction")))
				}
				client.gui.hud.chat.addClientSystemMessage(
					Component.literal("§8[Cryptic] §7Watched ${kind.lowercase(Locale.ROOT)}: ${named(listing.name, listing.tier)} §7for §6${Bazaar.coins(listing.price.toDouble())} coins").append(open),
				)
			}
		}
		if (found.size > MAX_TOLD) Toasts.show(SOURCE, "§7And ${found.size - MAX_TOLD} more.", color = Toasts.Colors.AUCTION)
		if (AuctionHouse.sounds.value) SoundChoices.play(SoundEvents.NOTE_BLOCK_PLING.value(), 0.5f, 1.5f)
	}

	private fun named(name: String, tier: String?): String = (ItemRarity.code(tier) ?: ItemRarity.codeForName(name) ?: "§f") + name

	fun debugLines(): List<String> = listOf(
		"Auction Watch: ${snapshot.size} watched, ${if (fetching) "reading" else status}, Hypixel's page from ${if (lastUpdated == 0L) "never" else "${(System.currentTimeMillis() - lastUpdated) / 1000}s ago"}, newest seen ${if (since == 0L) "never" else "${(System.currentTimeMillis() - since) / 60_000}m ago"}, ${told.size} told",
	) + snapshot.map { " ${title(it)} [${it.id.ifEmpty { if (it.anyItem) "any" else "by name" }}] ${describe(it)}" }

	// ---- Commands ------------------------------------------------------------------------------

	/**
	 * `/cryptic ahwatch` opens the list; `add <item> [max price] [filters]`, `remove <item>`, `list`
	 * and `filters` work from chat.
	 */
	fun command(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("ahwatch")
			.executes {
				imicro.cryptic.gui.menu.MenuScreen.request(AuctionHouse.module)
				1
			}
			.then(
				ClientCommands.literal("add").then(
					ClientCommands.argument("item", StringArgumentType.greedyString())
						.suggests { _, builder ->
							BazaarMarket.ensureItems()
							val text = builder.remaining
							val names = BazaarMarket.itemNames().filter { it.startsWith(text, ignoreCase = true) }.take(50)
							// The last word completes as a filter: its field, then what follows the dot or colon.
							val last = text.substringAfterLast(' ')
							val filters = AuctionFilters.complete(last, 50).ifEmpty {
								if (names.isNotEmpty() || last.isEmpty() || ' ' !in text) emptyList()
								else AuctionFilters.fields.filter { it.name.startsWith(last, ignoreCase = true) }.map { it.name + if (it.param != null) "." else ":" }
							}
							if (filters.isEmpty()) return@suggests SharedSuggestionProvider.suggest(names, builder)
							val at = builder.createOffset(builder.start + text.length - last.length)
							filters.forEach(at::suggest)
							at.buildFuture()
						}
						.executes { add(it) },
				),
			)
			.then(
				ClientCommands.literal("remove").then(
					ClientCommands.argument("item", StringArgumentType.greedyString())
						.suggests { _, builder -> SharedSuggestionProvider.suggest(entries.map { it.item }, builder) }
						.executes { context ->
							val item = StringArgumentType.getString(context, "item").trim()
							val entry = entries.firstOrNull { it.item.equals(item, ignoreCase = true) || it.id.equals(item, ignoreCase = true) }
							if (entry == null) {
								reply(context, "§cYou aren't watching $item.")
							} else {
								remove(entry)
								reply(context, "§7No longer watching §f${entry.item}§7.")
							}
							1
						},
				),
			)
			.then(ClientCommands.literal("filters").executes { listFilters(it) })
			.then(
				ClientCommands.literal("list").executes { context ->
					if (entries.isEmpty()) {
						reply(context, "§7You aren't watching anything.")
					} else {
						reply(context, "§7Watching (${entries.size}):")
						entries.forEach { reply(context, " §f${title(it)} §8- §7${describe(it)}") }
					}
					1
				},
			)

	/**
	 * "Storm's Helmet 5b skin:GOLDOR_CELESTIAL": words that read as filters are filters, the last
	 * other word is the price when it reads as one, and the rest is the item (none: any item).
	 */
	private fun add(context: CommandContext<FabricClientCommandSource>): Int {
		val filters = ArrayList<String>()
		val words = ArrayList<String>()
		for (token in tokens(StringArgumentType.getString(context, "item"))) {
			if (!FILTER_START.containsMatchIn(token)) {
				words += token
				continue
			}
			val filter = AuctionFilters.parse(token).getOrElse {
				reply(context, "§c${it.message} §7/cryptic ahwatch filters lists them.")
				return 0
			}
			filters += filter.text
		}
		val price = words.lastOrNull()?.let(::parsePrice)
		val typed = (if (price != null) words.dropLast(1) else words).joinToString(" ")
		if (typed.isBlank() && filters.isEmpty()) {
			reply(context, "§cName an item, or give filters for any item.")
			return 0
		}
		val entry = resolve(typed).apply {
			maxPrice = price ?: 0L
			this.filters = filters
		}
		put(entry)
		reply(context, "§7Watching §f${title(entry)}§7: ${describe(entry)}.")
		if (!AuctionHouse.module.enabled || !AuctionHouse.watch.value) reply(context, "§8The Auction House module's watch list is off, so nothing is checked until you turn it on.")
		return 1
	}

	/** A word that starts like a filter: `quality:50`, `stars>=5`, `enchant.growth:6`. */
	private val FILTER_START = Regex("""^[A-Za-z_]+(?:\.[^:=<>!\s]+)?(?::|!=|>=|<=|=|>|<)""")

	/** Words apart, with "quoted words" kept together (`name:"Wither Goggles"`). */
	private fun tokens(text: String): List<String> {
		val out = ArrayList<String>()
		val current = StringBuilder()
		var quoted = false
		for (c in text.trim()) {
			when {
				c == '"' -> {
					quoted = !quoted
					current.append(c)
				}
				c.isWhitespace() && !quoted -> {
					if (current.isNotEmpty()) out += current.toString()
					current.clear()
				}
				else -> current.append(c)
			}
		}
		if (current.isNotEmpty()) out += current.toString()
		return out
	}

	/** Every filter by group, each with its help on hover. */
	private fun listFilters(context: CommandContext<FabricClientCommandSource>): Int {
		reply(context, "§7Filters are §ffield:condition§7, e.g. §fquality:50 tier:10 stars:>=5 skin:GOLDOR_CELESTIAL§7. Conditions: a value, §fa,b§7, §f>=n§7, §f<=n§7, §fn-m§7, §fany§7, §fnone§7, §fyes§7/§fno§7, and §f!§7 for the opposite. Hover a name for what it reads.")
		for ((group, fields) in AuctionFilters.fields.groupBy { it.group }) {
			val line = Component.literal("§8[Cryptic] §e$group: ")
			fields.forEachIndexed { index, field ->
				if (index > 0) line.append(Component.literal("§8, "))
				val shown = if (field.param != null) "${field.name}.<${field.param}>" else field.name
				val example = field.values.firstOrNull()?.let { "\n§7e.g. §f${field.name}${if (field.param != null) ".…" else ""}:$it" }.orEmpty()
				line.append(Component.literal("§f$shown").withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("§f$shown\n§7${field.help}$example"))) })
			}
			context.source.sendFeedback(line)
		}
		return 1
	}

	private fun reply(context: CommandContext<FabricClientCommandSource>, text: String) =
		context.source.sendFeedback(Component.literal("§8[Cryptic] $text"))

	// ---- Saving --------------------------------------------------------------------------------

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val loaded = gson.fromJson(Files.readString(file), Array<Entry>::class.java) ?: return
			entries.clear()
			@Suppress("SENSELESS_COMPARISON")
			loaded.filter { it != null && it.item != null }.forEach {
				entries += Entry(it.item, it.id ?: "", it.maxPrice, it.binOnly, it.added, it.filters?.filterNotNull()?.toMutableList() ?: mutableListOf())
			}
			snapshot = entries.map { it.deepCopy() }
		}.onFailure { Cryptic.LOGGER.error("Could not read the auction watch list", it) }
	}

	fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(entries))
		}.onFailure { Cryptic.LOGGER.error("Could not save the auction watch list", it) }
	}

	private val seenFile get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("auction-watch-seen.json")

	private class Seen(val since: Long = 0L, val told: List<String>? = null)

	private fun loadSeen() {
		runCatching {
			if (!Files.exists(seenFile)) return
			val seen = gson.fromJson(Files.readString(seenFile), Seen::class.java) ?: return
			since = seen.since
			seen.told?.filterNotNull()?.let(told::addAll)
		}.onFailure { Cryptic.LOGGER.error("Could not read the auctions already told about", it) }
	}

	private fun saveSeen() {
		runCatching {
			Files.createDirectories(seenFile.parent)
			Files.writeString(seenFile, gson.toJson(Seen(since, told.toList())))
		}.onFailure { Cryptic.LOGGER.error("Could not save the auctions already told about", it) }
	}
}
