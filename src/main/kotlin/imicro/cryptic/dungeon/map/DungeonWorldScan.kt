package imicro.cryptic.dungeon.map

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Names each tile's room from the world. Every room is built from a fixed schematic, so the block
 * column under a tile's middle is a fingerprint: hash it and look it up in `rooms.json`. Doors are
 * read from the block in the gap between tiles. This works before the run starts. Ported from
 * dtMap.
 */
object DungeonWorldScan {
	private const val AIR_CORE = 48696

	/** Chests move between runs of the same room, so they're left out of the hash. */
	private val blacklisted = arrayOf(Blocks.CHEST, Blocks.TRAPPED_CHEST)

	private val resolved = BooleanArray(DungeonFloor.GRID * DungeonFloor.GRID)

	private val attempts = IntArray(DungeonFloor.GRID * DungeonFloor.GRID)

	/** After this many failures a tile is left to the map item. */
	private const val MAX_ATTEMPTS = 5

	private const val RETRY_INTERVAL_TICKS = 20

	private var pending = false
	private var ticksUntilRetry = 0

	var complete = false
		private set

	fun reset() {
		resolved.fill(false)
		attempts.fill(0)
		pending = false
		ticksUntilRetry = 0
		complete = false
	}

	fun requestScan() {
		if (!complete) pending = true
	}

	/**
	 * Reads whatever chunks have arrived. Missing ones wait for a later pass, so this is cheap
	 * every tick once the floor is known. Also retries once a second, since chunks can load before
	 * the sidebar says you're in a dungeon.
	 */
	fun scan(level: Level) {
		if (complete) return
		if (!pending && ticksUntilRetry-- > 0) return
		ticksUntilRetry = RETRY_INTERVAL_TICKS
		pending = false

		var changed = false
		var outstanding = false

		for (x in 0 until DungeonFloor.GRID) {
			for (z in 0 until DungeonFloor.GRID) {
				val tile = Vec2i(x, z)
				if (resolved[x * DungeonFloor.GRID + z]) continue

				val world = worldCenterOf(tile)
				val chunk = level.chunkSource.getChunk(world.x shr 4, world.z shr 4, ChunkStatus.FULL, false)
				if (chunk == null) {
					outstanding = true
					continue
				}

				val core = coreOf(chunk, world)
				if (core == null || core == AIR_CORE) {
					// Nothing here: void beyond the floor, or an unused tile.
					resolved[x * DungeonFloor.GRID + z] = true
					continue
				}

				val data = RoomData.byCore(core)
				if (data == null) {
					// A room Cryptic doesn't know, or a chunk still settling; retry a few times
					// first.
					if (++attempts[x * DungeonFloor.GRID + z] >= MAX_ATTEMPTS) {
						resolved[x * DungeonFloor.GRID + z] = true
					} else {
						outstanding = true
					}
					continue
				}

				resolved[x * DungeonFloor.GRID + z] = true
				changed = true

				// Every tile of one room hashes the same, so a room already found grows instead of
				// repeating.
				val siblings = DungeonFloor.rooms.firstOrNull { it.data === data }?.tiles.orEmpty()
				val room = DungeonFloor.claim(siblings + tile, data.type, data.shape)
				DungeonFloor.describe(room, data)

				// The entrance goes on the map as seen, before the map item arrives.
				if (room.type == DungeonRoom.Type.ENTRANCE && room.hidden) {
					room.state = DungeonRoom.State.DISCOVERED
				}
			}
		}

		if (changed) {
			scanDoors(level)
			DungeonFloor.relinkDoors()
			RoomPrediction.update()
		}

		if (!outstanding) complete = true
	}

	private fun worldCenterOf(tile: Vec2i): Vec2i = Vec2i(
		DungeonFloor.WORLD_TOP_LEFT + tile.x * DungeonFloor.BLOCKS_PER_TILE,
		DungeonFloor.WORLD_TOP_LEFT + tile.z * DungeonFloor.BLOCKS_PER_TILE,
	)

	/** The highest block in a column, or null off the floor (void air is outside the dungeon). */
	private fun topOf(chunk: ChunkAccess, pos: Vec2i): Int? {
		for (y in 160 downTo 11) {
			val block = chunk.getBlockState(BlockPos(pos.x and 15, y, pos.z and 15)).block
			if (block == Blocks.VOID_AIR) return null
			if (block != Blocks.AIR) return y
		}
		return 0
	}

	/**
	 * Hashes one block column into `rooms.json`'s key. Must match dtMap's recipe exactly, since the
	 * asset's hashes came from it: each block adds its name's first letter, bedrock followed by air
	 * is padded, and the starting height is written in front.
	 */
	private fun coreOf(chunk: ChunkAccess, pos: Vec2i): Int? {
		val height = topOf(chunk, pos) ?: return null
		val scanHeight = height.coerceIn(11, 140)

		val builder = StringBuilder(150)
		builder.append(140 - scanHeight)

		var bedrock = 0
		for (y in scanHeight downTo 12) {
			val block = chunk.getBlockState(BlockPos(pos.x and 15, y, pos.z and 15)).block

			if (bedrock >= 2 && block == Blocks.AIR) {
				builder.append(CharArray(y - 11) { 'a' })
			}

			if (block == Blocks.BEDROCK) {
				bedrock++
			} else {
				bedrock = 0
				if (blacklisted.any { it == block }) continue
			}

			builder.append(BuiltInRegistries.BLOCK.getKey(block).path[0].lowercaseChar())
		}

		return builder.toString().hashCode()
	}

	/**
	 * Finds doors: a four-block gap with coal (wither), red terracotta (blood) or anything else
	 * (normal). Gaps inside one large room are skipped.
	 */
	private fun scanDoors(level: Level) {
		for (x in 0 until DungeonFloor.GRID) {
			for (z in 0 until DungeonFloor.GRID) {
				val tile = Vec2i(x, z)
				val room = DungeonFloor.roomAt(tile) ?: continue

				if (x + 1 < DungeonFloor.GRID) {
					val other = DungeonFloor.roomAt(tile.add(1, 0))
					if (other != null && other !== room) {
						val world = worldCenterOf(tile).add(DungeonFloor.BLOCKS_PER_TILE / 2, 0)
						doorTypeAt(level, world)?.let { DungeonFloor.door(tile, true, it) }
					}
				}

				if (z + 1 < DungeonFloor.GRID) {
					val other = DungeonFloor.roomAt(tile.add(0, 1))
					if (other != null && other !== room) {
						val world = worldCenterOf(tile).add(0, DungeonFloor.BLOCKS_PER_TILE / 2)
						doorTypeAt(level, world)?.let { DungeonFloor.door(tile, false, it) }
					}
				}
			}
		}
	}

	private fun doorTypeAt(level: Level, world: Vec2i): DungeonDoor.Type? {
		val chunk = level.getChunk(world.x shr 4, world.z shr 4)
		val top = topOf(chunk, world) ?: return null

		// A doorway's ceiling is at one of two heights; anything else is solid ground.
		if (top != 73 && top != 81) return null

		return when (level.getBlockState(BlockPos(world.x, 69, world.z)).block) {
			Blocks.COAL_BLOCK -> DungeonDoor.Type.WITHER
			Blocks.DYED_TERRACOTTA.red() -> DungeonDoor.Type.BLOOD
			else -> DungeonDoor.Type.NORMAL
		}
	}
}
