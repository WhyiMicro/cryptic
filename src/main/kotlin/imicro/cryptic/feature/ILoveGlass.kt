package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.map.DungeonDoor
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * Swaps walls for glass so you can see past them: wither doors (coal), blood doors (red clay), the
 * entrance door (chiselled stone), and Storm's diorite pillars. NoammAddons' I Hate Doors and I
 * Hate Diorite on one card.
 *
 * Client-side only: the glass is as solid as before, and when Hypixel opens a door it sends air,
 * which replaces the glass. Blocks the server sets are swapped as they arrive by [substitute]
 * (keeping sinking pillars glass); whole chunks are caught by a scan.
 */
object ILoveGlass {
	private const val DOOR_RADIUS = 1
	private val DOOR_HEIGHT = 69..72

	/** Only for whole chunks; single blocks are swapped as they're set. */
	private const val DOOR_INTERVAL_TICKS = 4
	private const val PILLAR_INTERVAL_TICKS = 2

	private const val STORM_PHASE = 2

	private const val PILLAR_RADIUS = 3
	private const val PILLAR_HEIGHT = 37

	/** Set without notifying neighbours, keeping a client-side swap quiet. */
	private const val UPDATE_FLAGS = 19

	@JvmField
	val doors = ToggleModuleSetting(
		id = "doors",
		label = "Replace doors with glass",
		defaultValue = true,
		description = "Draws the entrance door as white glass, wither doors as grey glass and the blood door as red " +
			"glass, so the room behind shows.",
	)

	@JvmField
	val diorite = ToggleModuleSetting(
		id = "diorite",
		label = "Replace Diorite pillars with glass",
		defaultValue = true,
		description = "Draws Storm's four pillars on Floor 7 as glass in each pillar's own colour.",
	)

	@JvmField
	val module = Module(
		id = "i_love_glass",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		name = "I love glass",
		description = "See through doors, pillars",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(doors, diorite),
	)

	val needsDoors: Boolean get() = module.enabled && doors.value

	val needsPhase: Boolean get() = module.enabled && diorite.value

	private val DOOR_GLASS: Map<Block, () -> BlockState> by lazy {
		mapOf(
			Blocks.INFESTED_CHISELED_STONE_BRICKS to { Blocks.STAINED_GLASS.white().defaultBlockState() },
			Blocks.COAL_BLOCK to { Blocks.STAINED_GLASS.gray().defaultBlockState() },
			Blocks.DYED_TERRACOTTA.red() to { Blocks.STAINED_GLASS.red().defaultBlockState() },
		)
	}

	private val DIORITE_BLOCKS: Set<Block> by lazy { setOf(Blocks.DIORITE, Blocks.POLISHED_DIORITE) }

	private class Pillar(val base: BlockPos, val glass: () -> BlockState) {
		fun contains(pos: BlockPos): Boolean =
			pos.x in base.x - PILLAR_RADIUS..base.x + PILLAR_RADIUS &&
				pos.z in base.z - PILLAR_RADIUS..base.z + PILLAR_RADIUS &&
				pos.y in base.y..base.y + PILLAR_HEIGHT
	}

	private val pillars = listOf(
		Pillar(BlockPos(46, 169, 41)) { Blocks.STAINED_GLASS.lime().defaultBlockState() },
		Pillar(BlockPos(46, 169, 65)) { Blocks.STAINED_GLASS.yellow().defaultBlockState() },
		Pillar(BlockPos(100, 169, 65)) { Blocks.STAINED_GLASS.purple().defaultBlockState() },
		Pillar(BlockPos(100, 169, 41)) { Blocks.STAINED_GLASS.red().defaultBlockState() },
	)

	/** What every glassed block was, so switching off mid-run gives the walls back. */
	private val replaced = HashMap<BlockPos, BlockState>()

	private var doorsApplied = false
	private var pillarsApplied = false
	private var ticks = 0
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> replaced.clear() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> replaced.clear() }
	}

	private val wantDoors: Boolean get() = needsDoors && DungeonLocation.inDungeon && !DungeonRun.inBoss

	private val wantPillars: Boolean get() = needsPhase && Floor7.phase == STORM_PHASE

	fun tick(client: Minecraft) {
		val level = client.level ?: return
		ticks++

		if (wantDoors && ticks % DOOR_INTERVAL_TICKS == 0) glazeDoors(level)
		if (wantPillars && ticks % PILLAR_INTERVAL_TICKS == 0) glazePillars(level)

		// A half just switched off gives its walls back.
		if (doorsApplied && !needsDoors) restore(level) { it in DOOR_GLASS }
		if (pillarsApplied && !needsPhase) restore(level) { it in DIORITE_BLOCKS }
		doorsApplied = needsDoors
		pillarsApplied = needsPhase
	}

	/**
	 * The block to place instead of [state] at [pos]: glass for door or pillar blocks. Called
	 * inside a packet handler, where a throw disconnects, so surprises leave the block alone.
	 */
	@JvmStatic
	fun substitute(pos: BlockPos, state: BlockState): BlockState {
		if (!module.enabled) return state
		return try {
			val block = state.block
			val doorGlass = DOOR_GLASS[block]
			when {
				doorGlass != null && wantDoors && DungeonFloor.doors.any { inDoorway(it, pos) } ->
					remember(pos, state, doorGlass())
				block in DIORITE_BLOCKS && wantPillars ->
					pillars.firstOrNull { it.contains(pos) }?.let { remember(pos, state, it.glass()) } ?: state
				else -> state
			}
		} catch (_: RuntimeException) {
			state
		}
	}

	private fun remember(pos: BlockPos, original: BlockState, glass: BlockState): BlockState {
		replaced[pos.immutable()] = original
		return glass
	}

	private fun inDoorway(door: DungeonDoor, pos: BlockPos): Boolean =
		pos.y in DOOR_HEIGHT &&
			pos.x in door.worldX - DOOR_RADIUS..door.worldX + DOOR_RADIUS &&
			pos.z in door.worldZ - DOOR_RADIUS..door.worldZ + DOOR_RADIUS

	private fun glazeDoors(level: ClientLevel) {
		for (door in DungeonFloor.doors) {
			// One block says what the door is made of and whether it's still there (air when open,
			// glass when done).
			val source = level.getBlockState(BlockPos(door.worldX, DOOR_HEIGHT.first, door.worldZ)).block
			val glass = DOOR_GLASS[source]?.invoke() ?: continue

			for (x in door.worldX - DOOR_RADIUS..door.worldX + DOOR_RADIUS) {
				for (z in door.worldZ - DOOR_RADIUS..door.worldZ + DOOR_RADIUS) {
					for (y in DOOR_HEIGHT) swap(level, BlockPos(x, y, z), source, glass)
				}
			}
		}
	}

	private fun glazePillars(level: ClientLevel) {
		for (pillar in pillars) {
			val glass = pillar.glass()
			val cursor = BlockPos.MutableBlockPos()
			for (x in -PILLAR_RADIUS..PILLAR_RADIUS) {
				for (z in -PILLAR_RADIUS..PILLAR_RADIUS) {
					for (y in 0..PILLAR_HEIGHT) {
						cursor.set(pillar.base.x + x, pillar.base.y + y, pillar.base.z + z)
						val block = level.getBlockState(cursor).block
						if (block in DIORITE_BLOCKS) swap(level, cursor.immutable(), block, glass)
					}
				}
			}
		}
	}

	private fun swap(level: ClientLevel, pos: BlockPos, source: Block, glass: BlockState) {
		val state = level.getBlockState(pos)
		if (state.block != source) return
		replaced[pos] = state
		level.setBlock(pos, glass, UPDATE_FLAGS)
	}

	/**
	 * Puts back remembered blocks whose original [matches], where the glass still stands. Blocks
	 * the server changed since (an opened door) stay as the server made them.
	 */
	private fun restore(level: ClientLevel, matches: (Block) -> Boolean) {
		val entries = replaced.entries.iterator()
		while (entries.hasNext()) {
			val (pos, original) = entries.next()
			if (!matches(original.block)) continue
			entries.remove()
			if (level.isLoaded(pos) && level.getBlockState(pos).block in GLASS) level.setBlock(pos, original, UPDATE_FLAGS)
		}
	}

	private val GLASS: Set<Block> by lazy {
		setOf(
			Blocks.STAINED_GLASS.white(), Blocks.STAINED_GLASS.gray(), Blocks.STAINED_GLASS.red(),
			Blocks.STAINED_GLASS.lime(), Blocks.STAINED_GLASS.yellow(), Blocks.STAINED_GLASS.purple(),
		)
	}
}
