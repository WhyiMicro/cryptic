package imicro.cryptic.feature

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk

/**
 * Crystal Hollows places told apart by their blocks, as their chunks arrive. Each place has a
 * column of blocks found only there, read upward from its first block, and the place itself is a
 * fixed step from that column.
 *
 * The columns, the steps and the quarter each lies in are facts about Hypixel's builds, taken from
 * quoi (GPL-3.0, after GumTuneClient's world scanner); the search is Cryptic's own. Rather than
 * reading every block, a section is only searched when its palette holds a column's rarest block,
 * and only from those blocks.
 */
object HollowsStructures {
	/** Where a place can be; the quarters overlap by four chunks around the middle. */
	private enum class Quarter(val holds: (BlockPos) -> Boolean) {
		JUNGLE({ it.x <= 576 && it.z <= 576 }),
		MITHRIL({ it.x > 448 && it.z <= 576 }),
		GOBLIN({ it.x <= 576 && it.z > 448 }),
		PRECURSOR({ it.x > 448 && it.z > 448 }),
		MAGMA({ it.y < 80 }),
		ANYWHERE({ true }),
	}

	/**
	 * A place's column, bottom first ([null] is any block), the index of its rarest block, which
	 * the search starts from, and the step from the column's foot to the place.
	 */
	private class Print(
		val place: HollowsMap.Place,
		val column: List<Block?>,
		val anchor: Int,
		val quarter: Quarter,
		val step: Vec3i,
	)

	/** Any head counts, on a wall or not. */
	private val SKULL: Block = Blocks.SKELETON_SKULL

	private val PRINTS = listOf(
		Print(
			HollowsMap.Place.GOBLIN_QUEENS_DEN,
			listOf(Blocks.STONE, Blocks.ACACIA_LOG, Blocks.ACACIA_LOG, Blocks.ACACIA_LOG, Blocks.CAULDRON, Blocks.FIRE),
			anchor = 4, Quarter.GOBLIN, Vec3i(0, 5, 0),
		),
		Print(
			HollowsMap.Place.MINES_OF_DIVAN,
			listOf(Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.CHISELED_STONE_BRICKS),
			anchor = 0, Quarter.MITHRIL, Vec3i(0, 5, 0),
		),
		Print(
			HollowsMap.Place.LOST_PRECURSOR_CITY,
			listOf(
				Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS,
				Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_STAIRS,
			),
			anchor = 7, Quarter.PRECURSOR, Vec3i(24, 0, -17),
		),
		Print(
			HollowsMap.Place.JUNGLE_TEMPLE,
			listOf(Blocks.BEDROCK, Blocks.BEDROCK, Blocks.CLAY, Blocks.CLAY),
			anchor = 2, Quarter.JUNGLE, Vec3i(-45, 47, -18),
		),
		Print(
			HollowsMap.Place.KING_YOLKAR,
			listOf(Blocks.WOOL.red(), Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS),
			anchor = 0, Quarter.GOBLIN, Vec3i(1, -1, 2),
		),
		// Khazad-dûm, by its boss Bal's lava pool under ten barriers.
		Print(
			HollowsMap.Place.KHAZAD_DUM,
			listOf(Blocks.LAVA) + List(10) { Blocks.BARRIER },
			anchor = 1, Quarter.MAGMA, Vec3i(0, 1, 0),
		),
		// Jasper, the magenta gemstone, grows only in grottos.
		Print(
			HollowsMap.Place.FAIRY_GROTTO,
			listOf(Blocks.STAINED_GLASS.magenta()),
			anchor = 0, Quarter.ANYWHERE, Vec3i(0, 0, 0),
		),
		Print(
			HollowsMap.Place.CORLEONE,
			listOf(
				Blocks.POLISHED_GRANITE, Blocks.WATER, Blocks.WATER, null, null, null, null, null, null, null,
				Blocks.STONE, Blocks.DYED_TERRACOTTA.cyan(), Blocks.STONE_BRICKS, Blocks.STONE, Blocks.FIRE,
			),
			anchor = 11, Quarter.MITHRIL, Vec3i(23, 11, 17),
		),
		Print(
			HollowsMap.Place.KEY_GUARDIAN,
			listOf(
				Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_PLANKS, Blocks.GLOWSTONE, Blocks.CARPET.white(), null, Blocks.JUNGLE_SLAB,
				null, Blocks.JUNGLE_STAIRS, Blocks.STONE, Blocks.STONE, Blocks.STONE,
			),
			anchor = 3, Quarter.JUNGLE, Vec3i(0, 0, 0),
		),
		Print(
			HollowsMap.Place.ODAWA,
			listOf(
				Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS,
				Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LOG, Blocks.HAY_BLOCK,
				Blocks.DYED_TERRACOTTA.yellow(),
			),
			anchor = 9, Quarter.JUNGLE, Vec3i(0, 0, 0),
		),
		Print(
			HollowsMap.Place.DRAGONS_LAIR,
			listOf(Blocks.STONE, Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(), SKULL, Blocks.WOOL.red()),
			anchor = 4, Quarter.ANYWHERE, Vec3i(0, -3, 5),
		),
	)

	/** Every place the scanner can find, in the order the setting lists them. */
	val places: List<HollowsMap.Place> by lazy { PRINTS.map { it.place }.distinct() }

	private fun matches(state: BlockState, expected: Block): Boolean =
		if (expected === SKULL) state.block is AbstractSkullBlock else state.`is`(expected)

	/** The Hollows' floor and ceiling. */
	private val HEIGHTS = 30..188

	/**
	 * Looks through a chunk and hands [found] each place recognised, with where it is. Client
	 * thread: it reads the world.
	 */
	fun scan(level: Level, chunk: LevelChunk, wanted: (HollowsMap.Place) -> Boolean, found: (HollowsMap.Place, BlockPos) -> Unit) {
		val prints = PRINTS.filter { wanted(it.place) }
		if (prints.isEmpty()) return
		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ
		val probe = BlockPos.MutableBlockPos()
		chunk.sections.forEachIndexed { index, section ->
			if (section.hasOnlyAir()) return@forEachIndexed
			val here = prints.filter { print -> section.maybeHas { matches(it, print.column[print.anchor]!!) } }
			if (here.isEmpty()) return@forEachIndexed
			val baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index))
			for (y in 0..15) for (z in 0..15) for (x in 0..15) {
				val state = section.getBlockState(x, y, z)
				for (print in here) {
					if (!matches(state, print.column[print.anchor]!!)) continue
					val footY = baseY + y - print.anchor
					if (footY !in HEIGHTS || footY + print.column.size - 1 !in HEIGHTS) continue
					val foot = BlockPos(baseX + x, footY, baseZ + z)
					if (!print.quarter.holds(foot)) continue
					val whole = print.column.withIndex().all { (i, block) ->
						block == null || matches(level.getBlockState(probe.set(foot.x, footY + i, foot.z)), block)
					}
					if (whole && wanted(print.place)) found(print.place, foot.offset(print.step))
				}
			}
		}
	}
}
