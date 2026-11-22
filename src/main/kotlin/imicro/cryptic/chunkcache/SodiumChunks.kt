package imicro.cryptic.chunkcache

import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.multiplayer.ClientLevel
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Sodium keeps its own list of loaded chunks and never hears of a fake one. Reached by name, as
 * Sodium isn't on the compile path.
 */
object SodiumChunks {
	private const val HAS_BLOCKS = 1
	private const val HAS_LIGHT = 2

	@JvmField
	val loaded = FabricLoader.getInstance().isModLoaded("sodium")

	private var tracker: MethodHandle? = null
	private var added: MethodHandle? = null
	private var removed: MethodHandle? = null
	private var broken = false

	fun onFakeChunkAdded(level: ClientLevel, chunkX: Int, chunkZ: Int) {
		// A fake chunk always comes with its light.
		call(level, chunkX, chunkZ, HAS_BLOCKS or HAS_LIGHT, add = true)
	}

	/** [willBeReplaced] keeps the light flag, so Sodium doesn't hide the chunk until the real light lands. */
	fun onFakeChunkRemoved(level: ClientLevel, chunkX: Int, chunkZ: Int, willBeReplaced: Boolean) {
		call(level, chunkX, chunkZ, if (willBeReplaced) HAS_BLOCKS else HAS_BLOCKS or HAS_LIGHT, add = false)
	}

	private fun call(level: ClientLevel, chunkX: Int, chunkZ: Int, flags: Int, add: Boolean) {
		if (!loaded || broken) return
		try {
			if (tracker == null) resolve()
			val chunks = tracker!!.invoke(level)
			(if (add) added else removed)!!.invoke(chunks, chunkX, chunkZ, flags)
		} catch (error: Throwable) {
			broken = true
			Cryptic.LOGGER.warn("Chunk Cache can't reach Sodium's chunk tracker; cached chunks won't draw", error)
		}
	}

	private fun resolve() {
		val lookup = MethodHandles.publicLookup()
		val holder = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder")
		val trackerClass = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker")
		val status = MethodType.methodType(Void.TYPE, Int::class.java, Int::class.java, Int::class.java)
		tracker = lookup.findStatic(holder, "get", MethodType.methodType(trackerClass, ClientLevel::class.java))
			.asType(MethodType.methodType(Any::class.java, ClientLevel::class.java))
		val generic = MethodType.methodType(Void.TYPE, Any::class.java, Int::class.java, Int::class.java, Int::class.java)
		added = lookup.findVirtual(trackerClass, "onChunkStatusAdded", status).asType(generic)
		removed = lookup.findVirtual(trackerClass, "onChunkStatusRemoved", status).asType(generic)
	}
}
