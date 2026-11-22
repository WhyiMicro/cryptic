package imicro.cryptic.chunkcache

import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.UpgradeData
import net.minecraft.world.ticks.LevelChunkTicks

/** A chunk the server has unloaded, put back from the cache so it keeps drawing. */
class FakeChunk(level: Level, pos: ChunkPos, sections: Array<LevelChunkSection>) :
	LevelChunk(level, pos, UpgradeData.EMPTY, LevelChunkTicks(), LevelChunkTicks(), 0L, sections, null, null),
	ColumnTable.Column {
	override val columnX: Int = pos.x()
	override val columnZ: Int = pos.z()

	// Air sections are shared between chunks, so nothing may write to them. Servers also still send
	// the odd block update for a chunk they have just unloaded.
	override fun setBlockState(pos: BlockPos, state: BlockState, flags: Int): BlockState? = null

	// Chests, skulls and signs only draw within 64 blocks, where the server's own chunks are.
	override fun getBlockEntity(pos: BlockPos, creationType: LevelChunk.EntityCreationType): BlockEntity? = null
}

/** The light of one fake chunk, which the light engine has no data for. */
class FakeLightColumn(
	override val columnX: Int,
	override val columnZ: Int,
	private val minSection: Int,
	private val block: Array<DataLayer>,
	private val sky: Array<DataLayer>?,
) : ColumnTable.Column {
	/**
	 * Off once the server's chunk is taking the column over. The layers stay until its light is
	 * in, so the chunk is never drawn dark in between.
	 */
	@Volatile
	@JvmField
	var lit = true

	fun layer(skyLight: Boolean, sectionY: Int): DataLayer? {
		val layers = (if (skyLight) sky else block) ?: return null
		val index = sectionY - minSection
		return if (index >= 0 && index < layers.size) layers[index] else null
	}
}

/** Every fake chunk's light in one level. Read from the chunk builders' threads. */
class FakeLight {
	@JvmField
	val columns = ColumnTable<FakeLightColumn>()

	fun layer(skyLight: Boolean, sectionX: Int, sectionY: Int, sectionZ: Int): DataLayer? {
		if (columns.count == 0) return null
		return columns.get(sectionX, sectionZ)?.layer(skyLight, sectionY)
	}

	fun lit(chunkX: Int, chunkZ: Int): Boolean {
		if (columns.count == 0) return false
		return columns.get(chunkX, chunkZ)?.lit == true
	}
}

/** Added to the light engines by `ChunkCacheMixins`. */
interface FakeLightSink {
	fun crypticFakeLight(light: FakeLight?, skyLight: Boolean)
}

/**
 * Added to the packet listener by `ChunkCacheMixins`. Vanilla applies a new chunk's light a few
 * frames after its blocks; the fake light it replaces is dropped in that same task.
 */
interface FakeLightQueue {
	fun crypticQueueFakeLightRemoval(task: Runnable)

	/** Runs a queued removal now, so it doesn't hold on to a level that is being left. */
	fun crypticFlushFakeLightRemoval()
}

/** Added to the client chunk cache by `ChunkCacheMixins`: the square of chunks vanilla holds. */
interface ChunkStorageSquare {
	fun crypticStorageSquare(): ChunkSquare
}

fun interface ChunkConsumer {
	fun accept(chunkX: Int, chunkZ: Int)
}

/** A square of chunks around a centre. [update] reports the chunks that fell out of the old one. */
class ChunkSquare {
	var centerX = 0
		private set
	var centerZ = 0
		private set
	var radius = -1
		private set

	/** Returns true if the square changed. */
	fun update(newCenterX: Int, newCenterZ: Int, newRadius: Int, removed: ChunkConsumer?): Boolean {
		if (newCenterX == centerX && newCenterZ == centerZ && newRadius == radius) return false
		if (removed != null && radius >= 0) {
			for (x in centerX - radius..centerX + radius) {
				val xOutside = x < newCenterX - newRadius || x > newCenterX + newRadius
				for (z in centerZ - radius..centerZ + radius) {
					if (xOutside || z < newCenterZ - newRadius || z > newCenterZ + newRadius) removed.accept(x, z)
				}
			}
		}
		centerX = newCenterX
		centerZ = newCenterZ
		radius = newRadius
		return true
	}

	fun updateCenter(newCenterX: Int, newCenterZ: Int, removed: ChunkConsumer?) = update(newCenterX, newCenterZ, radius, removed)

	fun updateRadius(newRadius: Int, removed: ChunkConsumer?) = update(centerX, centerZ, newRadius, removed)

	fun contains(chunkX: Int, chunkZ: Int): Boolean =
		chunkX in centerX - radius..centerX + radius && chunkZ in centerZ - radius..centerZ + radius
}
