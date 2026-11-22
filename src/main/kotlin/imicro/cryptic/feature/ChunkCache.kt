package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.chunkcache.ChunkStore
import imicro.cryptic.chunkcache.ColumnTable
import imicro.cryptic.chunkcache.FakeChunkManager
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.MultiSelectModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.skyblock.HypixelServer
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.client.multiplayer.ClientLevel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keeps drawing the chunks the server has unloaded, out to the player's own render distance.
 *
 * A chunk is packed as the server lets go of it and held as deflated bytes; only the chunks in
 * view are unpacked into fake chunks. The bytes live per world: dropped with the server, or kept
 * per island for the islands every lobby shares, so a lobby hop arrives to a full view.
 *
 * Ported from SBO's Chunk Cache (the fake chunks and their light), which uses Bobby's technique.
 * Cryptic's own: the packed store, its worker threads, the lock-free lookups and the memory limit.
 */
object ChunkCache {
	/** Islands built the same on every server. The rest are made per lobby or per player. */
	private val SHARED_ISLANDS = listOf(
		"Hub", "Dungeon Hub", "The Park", "Spider's Den", "The End", "Crimson Isle", "Gold Mine", "Deep Caverns",
		"Dwarven Mines", "The Farming Islands", "Backwater Bayou", "Galatea", "The Rift", "Jerry's Workshop",
	)

	/** A new world's tab list still names the last island for a moment. */
	private const val ISLAND_SETTLE_TICKS = 60
	private const val ISLAND_STABLE_TICKS = 40
	private const val CHECK_INTERVAL_TICKS = 20
	private const val LIMIT_INTERVAL_TICKS = 100

	@JvmField
	val islands = MultiSelectModuleSetting(
		id = "islands",
		label = "Islands",
		options = SHARED_ISLANDS,
		description = "The islands kept. Only islands that are the same in every lobby are listed.",
	)

	@JvmField
	val keepAcrossLobbies = ToggleModuleSetting(
		"keep_across_lobbies", "Keep across lobbies", true,
		"Keeps an island's chunks until you leave Hypixel, so another lobby of it is all there when you arrive. " +
			"Off, chunks are dropped with every server change.",
	).withMore(islands)

	@JvmField
	val distance = SliderModuleSetting(
		id = "distance",
		label = "Distance",
		defaultValue = 32.0,
		min = 8.0,
		max = 32.0,
		step = 1.0,
		description = "How far cached chunks are drawn. Never further than your render distance.",
		unit = "chunks",
	)

	@JvmField
	val memoryLimit = SliderModuleSetting(
		id = "memory_limit",
		label = "Memory limit",
		defaultValue = 256.0,
		min = 32.0,
		max = 2048.0,
		step = 32.0,
		description = "The most memory spent on chunks out of view. Past it, islands you left go first, then the furthest chunks.",
		unit = "MB",
	)

	private val clear = ButtonModuleSetting("clear", "Clear cache", action = { clearRequested = true })

	@JvmField
	val module = Module(
		id = "chunk_cache",
		name = "Chunk Cache",
		description = "Keeps far chunks in view.",
		category = ModuleCategory.VISUAL,
		settings = listOf(keepAcrossLobbies, distance, memoryLimit, clear),
	)

	val needsSkyblock: Boolean get() = module.enabled && keepAcrossLobbies.value

	// Bobby does the same job with the same hooks; two would fight over every chunk.
	private val bobbyLoaded = FabricLoader.getInstance().isModLoaded("bobby")

	@Volatile
	private var manager: FakeChunkManager? = null

	/** The store in use when it belongs to an island, else null: the store dies with the server. */
	private var island: String? = null
	private val islandStores = HashMap<String, ChunkStore>()

	private var lastLevel: ClientLevel? = null
	private var levelTicks = 0
	private var lastArea: String? = null
	private var areaTicks = 0

	/** On, and in a world it has something to add to: the integrated server already sends as far as the render distance. */
	@Volatile
	private var running = false

	@Volatile
	private var disconnected = false
	private var clearRequested = false
	private var bobbyWarned = false

	fun initialize() {
	}

	@JvmStatic
	fun managerFor(cache: ClientChunkCache): FakeChunkManager? {
		val current = manager ?: return null
		return if (current.chunkCache === cache) current else null
	}

	/**
	 * The render distance while the cache runs: the player's own, not the server's. From a world's
	 * first tick rather than its first fake chunk, as a change of distance rebuilds every chunk.
	 */
	@JvmStatic
	fun viewDistance(vanilla: Int, renderDistance: Int): Int =
		if (!running) vanilla else max(vanilla, radius(renderDistance))

	@JvmStatic
	fun debugString(): String {
		val current = manager ?: return "Cryptic: off"
		return "Cryptic: F ${current.fakeChunkCount} S ${current.store.size}"
	}

	/** Any thread. A new connection may bring other registries, which the packed biomes are ids into. */
	@JvmStatic
	fun onDisconnect() {
		disconnected = true
	}

	fun tick(client: Minecraft) {
		val level = client.level
		if (disconnected) {
			disconnected = false
			stop(level)
			islandStores.clear()
		}
		running = module.enabled && !bobbyLoaded && (!client.hasSingleplayerServer() || DebugOverrides.chunkCacheExtra > 0)
		if (!running) {
			if (module.enabled && bobbyLoaded && !bobbyWarned && client.player != null) {
				bobbyWarned = true
				Cryptic.LOGGER.info("Chunk Cache stays off: Bobby is installed and does the same job")
			}
			if (manager != null || islandStores.isNotEmpty()) {
				stop(level)
				islandStores.clear()
			}
			lastLevel = level
			return
		}
		if (clearRequested) {
			clearRequested = false
			stop(level)
			islandStores.clear()
		}

		if (level !== lastLevel) {
			lastLevel = level
			levelTicks = 0
			manager?.let(::leave)
		}
		if (level == null) return
		levelTicks++
		val area = SkyblockLocation.area
		if (area != lastArea) {
			lastArea = area
			areaTicks = 0
		}
		areaTicks++

		val current = manager
		if (current == null) {
			start(level)
		} else {
			if (levelTicks % CHECK_INTERVAL_TICKS == 0 && wantedIsland() != island) {
				// The island became known, or was switched off: move over to its store.
				restart(level, current)
				return
			}
			current.tick(radius(client.options.renderDistance().get()))
		}
		if (levelTicks % LIMIT_INTERVAL_TICKS == 0) enforceLimit(client)
	}

	private fun radius(renderDistance: Int): Int =
		min(min(renderDistance, distance.value.roundToInt()) + DebugOverrides.chunkCacheExtra, ColumnTable.MAX_RADIUS - 1)

	/** The island whose store this world should use, or null for a store of its own. */
	private fun wantedIsland(): String? {
		if (!keepAcrossLobbies.value) return null
		if (DebugOverrides.chunkCacheOneIsland) return "Debug island"
		if (!HypixelServer.connected || !SkyblockLocation.onSkyblock) return null
		if (levelTicks < ISLAND_SETTLE_TICKS || areaTicks < ISLAND_STABLE_TICKS) return island
		val area = SkyblockLocation.area ?: return null
		return if (islands.isSelected(area)) area else null
	}

	private fun start(level: ClientLevel) {
		// Wait for the island's name rather than show the last island's chunks in this one.
		if (keepAcrossLobbies.value && HypixelServer.connected && levelTicks < ISLAND_SETTLE_TICKS) return
		open(level, wantedIsland(), carried = null)
	}

	private fun restart(level: ClientLevel, current: FakeChunkManager) {
		val carried = if (island == null) current.store else null
		current.close(unloadFakes = true)
		manager = null
		open(level, wantedIsland(), carried)
	}

	/** [carried] is what this world gathered before its island was known. */
	private fun open(level: ClientLevel, name: String?, carried: ChunkStore?) {
		val store = if (name == null) ChunkStore("this server") else islandStores.getOrPut(name) { ChunkStore(name) }
		if (name != null && carried != null) store.absorb(carried)
		store.lastUsed = System.nanoTime()
		island = name
		manager = FakeChunkManager(level, store)
	}

	/** The level is gone: an island keeps what was still loaded, a server's own store goes with it. */
	private fun leave(current: FakeChunkManager) {
		if (island != null) {
			current.captureLoaded()
			current.store.lastUsed = System.nanoTime()
		}
		current.close(unloadFakes = false)
		manager = null
		island = null
	}

	private fun stop(level: ClientLevel?) {
		val current = manager ?: return
		current.close(unloadFakes = current.level === level)
		manager = null
		island = null
	}

	private fun enforceLimit(client: Minecraft) {
		val limit = memoryLimit.value.toLong() shl 20
		val active = manager?.store
		var total = islandStores.values.sumOf { it.bytes() }
		if (active != null && island == null) total += active.bytes()
		if (total <= limit) return

		for (store in islandStores.values.filter { it !== active }.sortedBy { it.lastUsed }) {
			if (total <= limit) return
			total -= store.bytes()
			islandStores.remove(store.name)
		}
		val player = client.player
		if (total > limit && active != null && player != null) {
			val center = player.chunkPosition()
			// A little under the limit, so it isn't trimmed again on the next check.
			active.trim(center.x(), center.z(), total - limit / 10 * 9)
		}
	}

	fun describe(): List<String> {
		val current = manager
		val lines = ArrayList<String>()
		lines += when {
			!module.enabled -> "Chunk Cache: off"
			bobbyLoaded -> "Chunk Cache: off, Bobby is installed"
			!running -> "Chunk Cache: idle, a single-player world already sends as far as you render"
			current == null -> "Chunk Cache: waiting (island ${SkyblockLocation.area ?: "unknown"}, $levelTicks ticks in this world)"
			else -> "Chunk Cache: ${current.fakeChunkCount} cached chunks shown, ${current.store.size} stored for " +
				"${island ?: "this server"} (${current.store.bytes() shr 10} KB)"
		}
		val options = Minecraft.getInstance().options
		lines += "Render distance ${options.renderDistance().get()}, drawn to ${options.effectiveRenderDistance}, " +
			"debug extra ${DebugOverrides.chunkCacheExtra}"
		for (store in islandStores.values) {
			if (store !== current?.store) lines += "Kept: ${store.name}, ${store.size} chunks (${store.bytes() shr 10} KB)"
		}
		return lines
	}
}
