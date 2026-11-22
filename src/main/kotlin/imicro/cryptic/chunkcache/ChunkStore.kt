package imicro.cryptic.chunkcache

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.Executors

/**
 * Every chunk remembered of one world, packed. Kept for one server, or for an island across its
 * lobbies. Client thread only; the chunks themselves are packed and unpacked on [ChunkWorker].
 */
class ChunkStore(val name: String) {
	private val chunks = Long2ObjectOpenHashMap<PackedChunk>()

	/** When the player was last in this world, for dropping the stalest first. */
	var lastUsed = System.nanoTime()

	val size: Int get() = chunks.size

	operator fun get(key: Long): PackedChunk? = chunks.get(key)

	fun put(key: Long, chunk: PackedChunk) {
		chunks.put(key, chunk)
		ChunkWorker.run(chunk::deflate)
	}

	fun remove(key: Long) {
		chunks.remove(key)
	}

	fun clear() = chunks.clear()

	fun bytes(): Long {
		var total = 0L
		for (chunk in chunks.values) total += chunk.size
		return total
	}

	/** Takes [other]'s chunks, keeping its own where both have one. */
	fun absorb(other: ChunkStore) {
		for (entry in other.chunks.long2ObjectEntrySet()) chunks.putIfAbsent(entry.longKey, entry.value)
	}

	/** Drops the chunks furthest from a spot until [bytes] are freed. Returns what was freed. */
	fun trim(centerX: Int, centerZ: Int, bytes: Long): Long {
		val furthestFirst = chunks.keys.toLongArray().sortedByDescending { key ->
			val dx = (ChunkPos.getX(key) - centerX).toLong()
			val dz = (ChunkPos.getZ(key) - centerZ).toLong()
			dx * dx + dz * dz
		}
		var freed = 0L
		for (key in furthestFirst) {
			if (freed >= bytes) break
			freed += chunks.remove(key)?.size ?: 0
		}
		return freed
	}
}

/** Two quiet threads for deflating and unpacking, so neither costs the game a frame. */
object ChunkWorker {
	private val pool = Executors.newFixedThreadPool(2) { task ->
		Thread(task, "Cryptic Chunk Cache").apply {
			isDaemon = true
			priority = Thread.NORM_PRIORITY - 1
		}
	}

	fun run(task: Runnable) = pool.execute(task)
}
