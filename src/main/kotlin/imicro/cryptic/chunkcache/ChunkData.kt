package imicro.cryptic.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainerFactory
import net.minecraft.world.level.levelgen.Heightmap
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A chunk as the game holds it, without a level: blocks, biomes, light and heightmaps. Made from a
 * real chunk as the server unloads it, or read back from a [PackedChunk]. Block entities are left
 * out; see [FakeChunk].
 */
class ChunkData(
	val minSectionY: Int,
	val sections: Array<LevelChunkSection>,
	val minLightSection: Int,
	/** One layer per light section from [minLightSection] up. */
	val blockLight: Array<DataLayer>,
	/** Null in a dimension without sky light. */
	val skyLight: Array<DataLayer>?,
	val heightmaps: Map<Heightmap.Types, LongArray>,
) {
	fun write(buffer: FriendlyByteBuf) {
		buffer.writeInt(minSectionY)
		buffer.writeVarInt(sections.size)
		for (section in sections) section.write(buffer)

		buffer.writeInt(minLightSection)
		buffer.writeVarInt(blockLight.size)
		buffer.writeBoolean(skyLight != null)
		for (layer in blockLight) writeLayer(buffer, layer)
		if (skyLight != null) for (layer in skyLight) writeLayer(buffer, layer)

		buffer.writeVarInt(heightmaps.size)
		for ((type, data) in heightmaps) {
			buffer.writeByte(type.ordinal)
			buffer.writeLongArray(data)
		}
	}

	companion object {
		private val DARK = DataLayer(0)
		private val LIT = DataLayer(15)
		private const val FULL_LAYER = 0xFF
		private val heightmapTypes = Heightmap.Types.values()

		/**
		 * Copies [chunk], on the client thread. With [detach] off the copy still points into the
		 * chunk, which is enough to pack it there and then. Null while the server hasn't sent the
		 * chunk's light: without it the copy would draw black.
		 */
		fun capture(chunk: LevelChunk, air: AirSections, detach: Boolean): ChunkData? {
			val level = chunk.level
			val pos = chunk.pos
			val lightEngine = level.lightEngine
			if (!lightEngine.lightOnInColumn(SectionPos.getZeroNode(pos.x(), pos.z()))) return null

			val source = chunk.sections
			val sections = Array(source.size) { index ->
				val section = source[index]
				when {
					!detach -> section
					section.hasOnlyAir() -> air.share(section, owned = false)
					else -> section.copy()
				}
			}

			val minLightSection = lightEngine.minLightSection
			val lightCount = lightEngine.maxLightSection - minLightSection
			val blockListener = lightEngine.getLayerListener(LightLayer.BLOCK)
			val blockLight = Array(lightCount) { index ->
				compact(blockListener.getDataLayerData(SectionPos.of(pos, minLightSection + index)), detach) ?: DARK
			}
			val skyLight = if (level.dimensionType().hasSkyLight()) {
				val skyListener = lightEngine.getLayerListener(LightLayer.SKY)
				val layers = arrayOfNulls<DataLayer>(lightCount)
				for (index in 0 until lightCount) {
					layers[index] = compact(skyListener.getDataLayerData(SectionPos.of(pos, minLightSection + index)), detach)
				}
				fillSkyLight(layers)
			} else {
				null
			}

			val heightmaps = EnumMap<Heightmap.Types, LongArray>(Heightmap.Types::class.java)
			for ((type, heightmap) in chunk.heightmaps) {
				heightmaps[type] = if (detach) heightmap.rawData.copyOf() else heightmap.rawData
			}

			return ChunkData(chunk.minSectionY, sections, minLightSection, blockLight, skyLight, heightmaps)
		}

		/** Reads what [write] wrote. Safe off the client thread. */
		fun read(buffer: FriendlyByteBuf, factory: PalettedContainerFactory, air: AirSections): ChunkData {
			val minSectionY = buffer.readInt()
			val sections = Array(buffer.readVarInt()) {
				val section = LevelChunkSection(factory)
				section.read(buffer)
				if (section.hasOnlyAir()) air.share(section, owned = true) else section
			}

			val minLightSection = buffer.readInt()
			val lightCount = buffer.readVarInt()
			val hasSky = buffer.readBoolean()
			val blockLight = Array(lightCount) { readLayer(buffer) }
			val skyLight = if (hasSky) Array(lightCount) { readLayer(buffer) } else null

			val heightmaps = EnumMap<Heightmap.Types, LongArray>(Heightmap.Types::class.java)
			repeat(buffer.readVarInt()) {
				val type = heightmapTypes[buffer.readByte().toInt()]
				heightmaps[type] = buffer.readLongArray()
			}
			return ChunkData(minSectionY, sections, minLightSection, blockLight, skyLight, heightmaps)
		}

		private fun writeLayer(buffer: FriendlyByteBuf, layer: DataLayer) {
			val level = uniformLevel(layer)
			if (level >= 0) {
				buffer.writeByte(level)
			} else {
				buffer.writeByte(FULL_LAYER)
				buffer.writeBytes(layer.data)
			}
		}

		private fun readLayer(buffer: FriendlyByteBuf): DataLayer {
			val level = buffer.readUnsignedByte().toInt()
			if (level != FULL_LAYER) return uniform(level)
			val data = ByteArray(DataLayer.SIZE)
			buffer.readBytes(data)
			return DataLayer(data)
		}

		private fun uniform(level: Int): DataLayer = when (level) {
			0 -> DARK
			15 -> LIT
			else -> DataLayer(level)
		}

		/** The one light level a whole section has, or -1. Most sections are all dark or all lit. */
		private fun uniformLevel(layer: DataLayer): Int {
			if (layer.isDefinitelyHomogenous) return layer.get(0, 0, 0)
			val data = layer.data
			val first = data[0]
			val level = first.toInt() and 15
			if (level != (first.toInt() shr 4 and 15)) return -1
			for (byte in data) if (byte != first) return -1
			return level
		}

		/** One shared layer for a section of a single level, else the layer or a copy of it. */
		private fun compact(layer: DataLayer?, copy: Boolean): DataLayer? {
			if (layer == null) return null
			val level = uniformLevel(layer)
			return when {
				level >= 0 -> uniform(level)
				copy -> layer.copy()
				else -> layer
			}
		}

		/**
		 * The server leaves out a sky light section that matches the one above it. Fills those in
		 * from the top: fully lit above the highest sent, then the bottom of the section above.
		 */
		private fun fillSkyLight(layers: Array<DataLayer?>): Array<DataLayer> {
			var fill = LIT
			for (index in layers.indices.reversed()) {
				val layer = layers[index]
				if (layer != null) fill = floodDown(layer) else layers[index] = fill
			}
			@Suppress("UNCHECKED_CAST")
			return layers as Array<DataLayer>
		}

		private fun floodDown(above: DataLayer): DataLayer {
			if (uniformLevel(above) >= 0) return above
			val source = above.data
			val below = ByteArray(DataLayer.SIZE)
			// A section's bottom layer is its first 128 bytes.
			for (y in 0 until 16) System.arraycopy(source, 0, below, y * DataLayer.LAYER_SIZE, DataLayer.LAYER_SIZE)
			return compact(DataLayer(below), copy = false) ?: DARK
		}
	}
}

/**
 * One section for all the air of a biome. A SkyBlock island is mostly sky, and an empty section of
 * its own for each sixteen blocks of it adds up to megabytes. [FakeChunk] never writes to them.
 */
class AirSections {
	private val shared = ConcurrentHashMap<Holder<Biome>, LevelChunkSection>()

	/** [owned] sections may be kept as they are; others belong to a live chunk and are copied. */
	fun share(section: LevelChunkSection, owned: Boolean): LevelChunkSection {
		var biome: Holder<Biome>? = null
		var biomes = 0
		section.biomes.forEachInPalette {
			biome = it
			biomes++
		}
		val only = biome
		if (biomes != 1 || only == null) return if (owned) section else section.copy()
		return shared.computeIfAbsent(only) { if (owned) section else section.copy() }
	}
}

/**
 * A chunk as bytes, which is all the cache holds of a chunk that isn't in view. Written on the
 * client thread, deflated on a worker shortly after: light is most of a chunk and nearly all runs.
 */
class PackedChunk private constructor(raw: ByteArray) {
	/** [inflatedSize] is -1 until the bytes are deflated. */
	private class Blob(val bytes: ByteArray, val inflatedSize: Int)

	@Volatile
	private var blob = Blob(raw, -1)

	val size: Int get() = blob.bytes.size + OVERHEAD

	/** Worker thread. Leaves the bytes alone if deflating doesn't shrink them. */
	fun deflate() {
		val current = blob
		if (current.inflatedSize >= 0) return
		val deflater = deflaters.get()
		deflater.reset()
		deflater.setInput(current.bytes)
		deflater.finish()
		var out = buffers.get()
		if (out.size < current.bytes.size) {
			out = ByteArray(current.bytes.size)
			buffers.set(out)
		}
		val length = deflater.deflate(out, 0, current.bytes.size)
		if (deflater.finished()) blob = Blob(out.copyOf(length), current.bytes.size)
	}

	/** Worker thread. Throws if the bytes don't fit the level, as after a registry change. */
	fun unpack(factory: PalettedContainerFactory, air: AirSections): ChunkData {
		val current = blob
		val raw = if (current.inflatedSize < 0) current.bytes else inflate(current)
		return ChunkData.read(FriendlyByteBuf(Unpooled.wrappedBuffer(raw)), factory, air)
	}

	private fun inflate(current: Blob): ByteArray {
		val raw = ByteArray(current.inflatedSize)
		val inflater = inflaters.get()
		inflater.reset()
		inflater.setInput(current.bytes)
		var filled = 0
		while (filled < raw.size) {
			val read = inflater.inflate(raw, filled, raw.size - filled)
			if (read == 0) error("Chunk ended ${raw.size - filled} bytes short")
			filled += read
		}
		return raw
	}

	companion object {
		/** The objects around the bytes, for the memory limit. */
		private const val OVERHEAD = 96

		private val deflaters = ThreadLocal.withInitial { Deflater(Deflater.BEST_SPEED) }
		private val inflaters = ThreadLocal.withInitial { Inflater() }
		private val buffers = ThreadLocal.withInitial { ByteArray(1 shl 16) }

		// Client thread only.
		private val scratch = FriendlyByteBuf(Unpooled.buffer(1 shl 16))

		/** Client thread. */
		fun of(data: ChunkData): PackedChunk {
			scratch.clear()
			data.write(scratch)
			val raw = ByteArray(scratch.readableBytes())
			scratch.readBytes(raw)
			return PackedChunk(raw)
		}
	}
}
