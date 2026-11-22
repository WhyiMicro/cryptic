package imicro.cryptic.chunkcache

import imicro.cryptic.Cryptic
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Shows fake chunks in one level. A chunk the server unloads is packed into the [store] and, while
 * it is in view, swapped for a fake copy; chunks already in the store fill the rest of the view.
 *
 * The technique is Bobby's (fake chunks served from the client chunk cache, their light answered
 * for the light engine), by way of SBO's Chunk Cache, which this is ported from.
 *
 * Client thread, except [getChunk] and the fake light, which the chunk builders read too.
 */
class FakeChunkManager(val level: ClientLevel, val store: ChunkStore) {
	val chunkCache: ClientChunkCache = level.chunkSource

	private val fakes = ColumnTable<FakeChunk>()
	private val fakeKeys = LongOpenHashSet()
	private val light = FakeLight()
	private val air = AirSections()

	/** The view plus a margin, so walking along a chunk border doesn't load and drop a row each step. */
	private val kept = ChunkSquare()
	private var viewRadius = 0

	/** Real chunks copied as vanilla lets go of them, faked once it has. */
	private val pending = Long2ObjectOpenHashMap<ChunkData>()

	/** Chunks being unpacked on a worker, and those it has finished. */
	private val requested = LongOpenHashSet()
	private val unpacked = ConcurrentLinkedQueue<Unpacked>()
	private var dirty = true

	@Volatile
	private var closed = false

	private class Unpacked(val key: Long, val source: PackedChunk, val data: ChunkData?)

	init {
		attachLight(light)
	}

	fun getChunk(chunkX: Int, chunkZ: Int): LevelChunk? {
		if (fakes.count == 0) return null
		return fakes.get(chunkX, chunkZ)
	}

	val fakeChunkCount: Int get() = fakeKeys.size

	fun tick(radius: Int) {
		val player = Minecraft.getInstance().player ?: return
		val center = player.chunkPosition()
		viewRadius = radius
		val moved = kept.update(center.x(), center.z(), radius + KEEP_MARGIN) { x, z -> unload(x, z, false) }
		showUnpacked()
		if (moved || dirty) requestMissing()
	}

	/** Asks for the stored chunks the view lacks, nearest first. */
	private fun requestMissing() {
		dirty = false
		if (store.size == 0) return
		val missing = LongArrayList()
		for (x in kept.centerX - viewRadius..kept.centerX + viewRadius) {
			for (z in kept.centerZ - viewRadius..kept.centerZ + viewRadius) {
				val key = ChunkPos.pack(x, z)
				if (fakeKeys.contains(key) || requested.contains(key) || store[key] == null) continue
				if (chunkCache.getChunk(x, z, ChunkStatus.FULL, false) != null) continue
				missing.add(key)
			}
		}
		if (missing.isEmpty) return

		val nearestFirst = missing.toLongArray().sortedBy { key ->
			val dx = ChunkPos.getX(key) - kept.centerX
			val dz = ChunkPos.getZ(key) - kept.centerZ
			dx * dx + dz * dz
		}
		val factory = level.palettedContainerFactory()
		for (key in nearestFirst) {
			// The rest wait a tick: by then the player may have moved on.
			if (requested.size >= MAX_REQUESTS) {
				dirty = true
				return
			}
			val source = store[key] ?: continue
			requested.add(key)
			ChunkWorker.run {
				if (closed) return@run
				val data = try {
					source.unpack(factory, air)
				} catch (error: Exception) {
					Cryptic.LOGGER.warn("Chunk Cache dropped a chunk it couldn't read back", error)
					null
				}
				unpacked.add(Unpacked(key, source, data))
			}
		}
	}

	private fun showUnpacked() {
		if (unpacked.isEmpty()) return
		val deadline = System.nanoTime() + SHOW_BUDGET_NANOS
		while (System.nanoTime() < deadline) {
			val next = unpacked.poll() ?: break
			requested.remove(next.key)
			// Captured again or dropped since it was asked for.
			if (store[next.key] !== next.source) continue
			if (next.data == null) {
				store.remove(next.key)
				continue
			}
			val x = ChunkPos.getX(next.key)
			val z = ChunkPos.getZ(next.key)
			if (!kept.contains(x, z) || fakeKeys.contains(next.key)) continue
			if (chunkCache.getChunk(x, z, ChunkStatus.FULL, false) != null) continue
			if (!show(x, z, next.data)) store.remove(next.key)
		}
		// More to ask for now that some have come back.
		if (requested.size < MAX_REQUESTS) dirty = true
	}

	/** False for a chunk from a dimension of another height, which can't be shown here. */
	private fun show(chunkX: Int, chunkZ: Int, data: ChunkData): Boolean {
		if (level.sectionsCount != data.sections.size || level.minSectionY != data.minSectionY) return false
		val pos = ChunkPos(chunkX, chunkZ)
		val chunk = FakeChunk(level, pos, data.sections)
		for ((type, heights) in data.heightmaps) chunk.setHeightmap(type, heights)

		fakes.put(chunk)
		fakeKeys.add(pos.pack())
		light.columns.put(FakeLightColumn(chunkX, chunkZ, data.minLightSection, data.blockLight, data.skyLight))

		markLoaded(chunkX, chunkZ, chunk)
		level.onChunkLoaded(pos)
		level.setSectionRangeDirty(chunkX - 1, level.minSectionY, chunkZ - 1, chunkX + 1, level.maxSectionY, chunkZ + 1)
		SodiumChunks.onFakeChunkAdded(level, chunkX, chunkZ)
		return true
	}

	/**
	 * Removes a fake chunk. [willBeReplaced] means the server's chunk is about to take its place: the
	 * fake light then stays until vanilla has applied the real light.
	 */
	fun unload(chunkX: Int, chunkZ: Int, willBeReplaced: Boolean) {
		val chunk = fakes.remove(chunkX, chunkZ) ?: return
		fakeKeys.remove(ChunkPos.pack(chunkX, chunkZ))

		val column = light.columns.get(chunkX, chunkZ)
		val queue = Minecraft.getInstance().connection as? FakeLightQueue
		if (willBeReplaced && column != null && queue != null) {
			column.lit = false
			// Unless it has turned fake again meanwhile, which brought light of its own.
			queue.crypticQueueFakeLightRemoval {
				if (light.columns.get(chunkX, chunkZ) === column) light.columns.remove(chunkX, chunkZ)
			}
		} else {
			light.columns.remove(chunkX, chunkZ)
		}
		// The real chunk reports itself loaded; marking the spot unloaded as well would win.
		if (!willBeReplaced) markUnloaded(chunkX, chunkZ, chunk)
		SodiumChunks.onFakeChunkRemoved(level, chunkX, chunkZ, willBeReplaced)
	}

	/** Packs a real chunk just before vanilla unloads it, and fakes it afterwards if it is still in view. */
	fun saveRealChunk(chunkX: Int, chunkZ: Int) = saveRealChunk(chunkX, chunkZ, kept.contains(chunkX, chunkZ))

	private fun saveRealChunk(chunkX: Int, chunkZ: Int, show: Boolean) {
		val chunk = chunkCache.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) ?: return
		if (chunk is FakeChunk) return
		val data = ChunkData.capture(chunk, air, detach = show)
		if (data == null) {
			// An older copy may still be in the store.
			dirty = true
			return
		}
		val key = ChunkPos.pack(chunkX, chunkZ)
		store.put(key, PackedChunk.of(data))
		if (show) pending.put(key, data)
	}

	fun showPending() {
		if (pending.isEmpty()) return
		for (entry in pending.long2ObjectEntrySet()) {
			val key = entry.longKey
			if (fakeKeys.contains(key)) continue
			val x = ChunkPos.getX(key)
			val z = ChunkPos.getZ(key)
			// Vanilla kept it after all.
			if (chunkCache.getChunk(x, z, ChunkStatus.FULL, false) != null) continue
			show(x, z, entry.value)
		}
		pending.clear()
	}

	/** Vanilla swapped its chunk storage for a new size; tell the new one about the fake chunks. */
	fun remarkAllLoaded() {
		for (key in fakeKeys) {
			val x = ChunkPos.getX(key)
			val z = ChunkPos.getZ(key)
			fakes.get(x, z)?.let { markLoaded(x, z, it) }
		}
	}

	/**
	 * Vanilla has written a spot off after a fake chunk took it: a chunk that slid out of its square
	 * is only unloaded when the slot is reused. Both renderers then stop drawing the spot.
	 */
	fun refresh(chunkX: Int, chunkZ: Int) {
		val chunk = fakes.get(chunkX, chunkZ) ?: return
		markLoaded(chunkX, chunkZ, chunk)
		SodiumChunks.onFakeChunkAdded(level, chunkX, chunkZ)
	}

	/** Packs every real chunk still loaded, as the level is left: vanilla doesn't unload them one by one. */
	fun captureLoaded() {
		val storage = (chunkCache as ChunkStorageSquare).crypticStorageSquare()
		if (storage.radius < 0) return
		for (x in storage.centerX - storage.radius..storage.centerX + storage.radius) {
			for (z in storage.centerZ - storage.radius..storage.centerZ + storage.radius) saveRealChunk(x, z, show = false)
		}
	}

	/** [unloadFakes] only matters while the level stays in use; a level being left is thrown away whole. */
	fun close(unloadFakes: Boolean) {
		closed = true
		if (unloadFakes) {
			for (key in fakeKeys.toLongArray()) unload(ChunkPos.getX(key), ChunkPos.getZ(key), false)
		}
		(Minecraft.getInstance().connection as? FakeLightQueue)?.crypticFlushFakeLightRemoval()
		attachLight(null)
		fakes.clear()
		fakeKeys.clear()
		light.columns.clear()
		pending.clear()
		requested.clear()
		unpacked.clear()
	}

	private fun attachLight(light: FakeLight?) {
		val engine = level.lightEngine
		(engine as? FakeLightSink)?.crypticFakeLight(light, false)
		(engine.getLayerListener(LightLayer.BLOCK) as? FakeLightSink)?.crypticFakeLight(light, false)
		(engine.getLayerListener(LightLayer.SKY) as? FakeLightSink)?.crypticFakeLight(light, true)
	}

	// Vanilla's chunk storage only reports the chunks it holds itself; the renderer hears of a
	// fake one here.
	private fun markLoaded(chunkX: Int, chunkZ: Int, chunk: LevelChunk) {
		val key = ChunkPos.pack(chunkX, chunkZ)
		chunkCache.addedLoadedChunks().add(key)
		chunkCache.removedLoadedChunks().remove(key)
		val sections = chunk.sections
		for (index in sections.indices) {
			if (!sections[index].hasOnlyAir()) continue
			val section = SectionPos.asLong(chunkX, chunk.getSectionYFromSectionIndex(index), chunkZ)
			chunkCache.addedEmptySections().add(section)
			chunkCache.removedEmptySections().remove(section)
		}
	}

	private fun markUnloaded(chunkX: Int, chunkZ: Int, chunk: LevelChunk) {
		val key = ChunkPos.pack(chunkX, chunkZ)
		chunkCache.removedLoadedChunks().add(key)
		chunkCache.addedLoadedChunks().remove(key)
		val sections = chunk.sections
		for (index in sections.indices) {
			if (!sections[index].hasOnlyAir()) continue
			val section = SectionPos.asLong(chunkX, chunk.getSectionYFromSectionIndex(index), chunkZ)
			chunkCache.removedEmptySections().add(section)
			chunkCache.addedEmptySections().remove(section)
		}
	}

	private companion object {
		const val KEEP_MARGIN = 1

		/** Chunks unpacking at once. Few enough that a turn of the head isn't stuck behind a queue. */
		const val MAX_REQUESTS = 48

		/** Time a tick may spend putting fake chunks in, so a fast flight doesn't drop frames. */
		val SHOW_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2)
	}
}
