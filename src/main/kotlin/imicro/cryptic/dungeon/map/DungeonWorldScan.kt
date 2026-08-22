package imicro.cryptic.dungeon.map

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Works out which named room is on each tile by looking at the world.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who); the full
 * licence is in `licenses/dtMap-LICENSE.txt`. Hypixel builds every Catacombs
 * room from a fixed schematic, so the column of blocks under a tile's middle is
 * a fingerprint of the room: hash it, look the hash up in `rooms.json`, and the
 * room has a name. Doors are read the same way, from the block sitting in the
 * gap between two tiles.
 *
 * This is the half of the map that does not need Hypixel to hand you anything,
 * which is why the floor can be drawn as soon as you walk in rather than when
 * the run starts.
 */
object DungeonWorldScan {
	/** The hash of a column of nothing, which is every tile off the floor. */
	private const val AIR_CORE = 48696

	/** Chests move between runs of the same room, so they cannot go in the hash. */
	private val blacklisted = arrayOf(Blocks.CHEST, Blocks.TRAPPED_CHEST)

	/** Tiles already settled, either named or known to be off the floor. */
	private val resolved = BooleanArray(DungeonFloor.GRID * DungeonFloor.GRID)

	/** How many passes have read a tile without recognising what is on it. */
	private val attempts = IntArray(DungeonFloor.GRID * DungeonFloor.GRID)

	/** After this many failures a tile is given up on as a room Cryptic lacks. */
	private const val MAX_ATTEMPTS = 5

	/** How often an unfinished scan tries again without being asked to. */
	private const val RETRY_INTERVAL_TICKS = 20

	private var pending = false
	private var ticksUntilRetry = 0

	/** True once every tile has been decided, after which the scan idles. */
	var complete = false
		private set

	fun reset() {
		resolved.fill(false)
		attempts.fill(0)
		pending = false
		ticksUntilRetry = 0
		complete = false
	}

	/** Chunks arriving is the usual reason there is more of the floor to read. */
	fun requestScan() {
		if (!complete) pending = true
	}

	/**
	 * Reads whatever chunks have turned up since the last pass.
	 *
	 * Tiles whose chunk is still missing are left for a later pass, so this can
	 * be called every tick and costs nothing once the floor is known. It also
	 * retries on its own once a second: chunks can finish loading before the
	 * scoreboard admits you are in a dungeon, and waiting only on chunk arrivals
	 * would then mean waiting forever.
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
					// Nothing stands here: either the void beyond the floor or a
					// tile this floor does not use.
					resolved[x * DungeonFloor.GRID + z] = true
					continue
				}

				val data = RoomData.byCore(core)
				if (data == null) {
					// Either a room Cryptic has no entry for, or a chunk that has
					// only just arrived and is still settling. Retry a few times
					// before writing the tile off to the map item.
					if (++attempts[x * DungeonFloor.GRID + z] >= MAX_ATTEMPTS) {
						resolved[x * DungeonFloor.GRID + z] = true
					} else {
						outstanding = true
					}
					continue
				}

				resolved[x * DungeonFloor.GRID + z] = true
				changed = true

				// Every tile of one room hashes to the same entry, so a room
				// already found elsewhere on the grid grows rather than repeats.
				val siblings = DungeonFloor.rooms.firstOrNull { it.data === data }?.tiles.orEmpty()
				val room = DungeonFloor.claim(siblings + tile, data.type, data.shape)
				DungeonFloor.describe(room, data)

				// The entrance is where you are standing while the party fills
				// up, so it goes on the map as seen rather than waiting for a
				// map item that only arrives when the run starts.
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

	/** The world position of the middle of a grid tile. */
	private fun worldCenterOf(tile: Vec2i): Vec2i = Vec2i(
		DungeonFloor.WORLD_TOP_LEFT + tile.x * DungeonFloor.BLOCKS_PER_TILE,
		DungeonFloor.WORLD_TOP_LEFT + tile.z * DungeonFloor.BLOCKS_PER_TILE,
	)

	/**
	 * The highest block in a column, or null when the column is off the map.
	 *
	 * Void air means the chunk is outside the dungeon, which is how the scan
	 * tells the edge of a small floor from a room it has not seen yet.
	 */
	private fun topOf(chunk: ChunkAccess, pos: Vec2i): Int? {
		for (y in 160 downTo 11) {
			val block = chunk.getBlockState(BlockPos(pos.x and 15, y, pos.z and 15)).block
			if (block == Blocks.VOID_AIR) return null
			if (block != Blocks.AIR) return y
		}
		return 0
	}

	/**
	 * Hashes one column of blocks into the number `rooms.json` is keyed by.
	 *
	 * The exact recipe matters far more than it looks: it has to match dtMap's
	 * character for character, because the hashes in the asset were generated by
	 * it. Each block contributes the first letter of its name, a run of bedrock
	 * followed by air is padded out, and the height the column starts at is
	 * written in front.
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
	 * Finds the doors between the rooms the scan has placed.
	 *
	 * A doorway is a four-block gap with a distinctive block in it: coal for a
	 * wither door, red terracotta for the blood door, anything else for a plain
	 * one. Gaps inside a single large room are skipped, because both sides
	 * belong to the same room.
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

		// A doorway's ceiling is one of two heights; anything else in the gap is
		// solid ground between two halves of the same structure.
		if (top != 73 && top != 81) return null

		return when (level.getBlockState(BlockPos(world.x, 69, world.z)).block) {
			Blocks.COAL_BLOCK -> DungeonDoor.Type.WITHER
			Blocks.RED_TERRACOTTA -> DungeonDoor.Type.BLOOD
			else -> DungeonDoor.Type.NORMAL
		}
	}
}
