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
 * Swaps two kinds of wall for glass, so you can see what is on the other side.
 *
 * NoammAddons' I Hate Doors and I Hate Diorite (CC0, Noamm9) on one card. A
 * wither door is a wall of coal, a blood door a wall of red clay and the
 * entrance's door a wall of chiselled stone, and what is behind one is exactly
 * what a party about to open it wants to see; Storm's four pillars are solid
 * diorite, and hide the man you are trying to crush under them.
 *
 * Only this client's picture of the world changes. The glass is as solid as
 * what it replaced — nothing can be walked through that could not be before —
 * and the server is told nothing. When Hypixel opens a door it sends the
 * doorway as air, which overwrites the glass the same as it would have the
 * coal.
 *
 * Two ways in, because walls arrive two ways. A block the server sets on its
 * own is swapped as it is set, by [substitute] — which is what keeps a sinking
 * pillar glass all the way down, since it is resent a layer at a time. A chunk
 * arriving whole is not set block by block, so a scan catches those.
 */
object ILoveGlass {
	/** A doorway is three blocks wide, four tall and three deep, centred on its tile edge. */
	private const val DOOR_RADIUS = 1
	private val DOOR_HEIGHT = 69..72

	/** Only for whole chunks arriving; single blocks are swapped as they are set. */
	private const val DOOR_INTERVAL_TICKS = 4
	private const val PILLAR_INTERVAL_TICKS = 2

	/** Storm's phase, which is where the pillars are. */
	private const val STORM_PHASE = 2

	private const val PILLAR_RADIUS = 3
	private const val PILLAR_HEIGHT = 37

	/** Set without telling the neighbours, which is what keeps a client-side swap quiet. */
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
		name = "I love glass",
		description = "See through dungeon doors and Storm's pillars",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(doors, diorite),
	)

	/** True while the doors are wanted, which is what keeps the floor being scanned for them. */
	val needsDoors: Boolean get() = module.enabled && doors.value

	/** True while the boss phase has to be known. */
	val needsPhase: Boolean get() = module.enabled && diorite.value

	/** What each kind of door is built of, and the glass it becomes. */
	private val DOOR_GLASS: Map<Block, () -> BlockState> by lazy {
		mapOf(
			Blocks.INFESTED_CHISELED_STONE_BRICKS to { Blocks.STAINED_GLASS.white().defaultBlockState() },
			Blocks.COAL_BLOCK to { Blocks.STAINED_GLASS.gray().defaultBlockState() },
			Blocks.DYED_TERRACOTTA.red() to { Blocks.STAINED_GLASS.red().defaultBlockState() },
		)
	}

	private val DIORITE_BLOCKS: Set<Block> by lazy { setOf(Blocks.DIORITE, Blocks.POLISHED_DIORITE) }

	/** One of Storm's pillars: its base, and the glass it turns into. */
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

	/**
	 * What every block turned to glass used to be, so it can be put back.
	 *
	 * Switching the card off in the middle of a run should give the walls back
	 * rather than leave them glass until the chunk happens to be sent again.
	 */
	private val replaced = HashMap<BlockPos, BlockState>()

	private var doorsApplied = false
	private var pillarsApplied = false
	private var ticks = 0
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		// A new world has none of the old one's blocks in it.
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

		// Whichever half has just been switched off gives its walls back.
		if (doorsApplied && !needsDoors) restore(level) { it in DOOR_GLASS }
		if (pillarsApplied && !needsPhase) restore(level) { it in DIORITE_BLOCKS }
		doorsApplied = needsDoors
		pillarsApplied = needsPhase
	}

	/**
	 * The block the world should take in place of [state] at [pos], as the
	 * server sets it: glass for a door or pillar block, anything else as it is.
	 *
	 * Called from inside a packet handler, where a throw disconnects, so any
	 * surprise leaves the block alone instead.
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
			// One block says what the whole door is made of, and whether it is
			// still there: an opened door is air, and one already glazed is glass.
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

	/** Turns [pos] to [glass] if it is still [source], remembering what it was. */
	private fun swap(level: ClientLevel, pos: BlockPos, source: Block, glass: BlockState) {
		val state = level.getBlockState(pos)
		if (state.block != source) return
		replaced[pos] = state
		level.setBlock(pos, glass, UPDATE_FLAGS)
	}

	/**
	 * Puts back every remembered block whose original [matches], where the
	 * glass is still standing. One the server has since changed — a door that
	 * opened — is left as the server made it.
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
