package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.SkyblockItem
import net.minecraft.world.item.context.BlockPlaceContext

/**
 * Stops right-click items that are vanilla blocks underneath (Power Orbs, relics, the Flower of
 * Truth) from being placed. The use is still sent; only the placement is dropped. Dungeons only.
 * Ported from NoammAddons.
 */
object NoItemPlace {
	private val PREFIXES = listOf("ABIPHONE")

	private val SUFFIXES = listOf("_TUBA", "_POWER_ORB", "_POCKET_BLACK_HOLE", "_FISHING_NET")

	private val EXACT = setOf(
		"BOUQUET_OF_LIES",
		"FLOWER_OF_TRUTH",
		"BAT_WAND",
		"STARRED_BAT_WAND",
		"INFINITE_SPIRIT_LEAP",
		"ROYAL_PIGEON",
		"ARROW_SWAPPER",
		"JINGLE_BELLS",
		"FIRE_FREEZE_STAFF",
		"UMBERELLA",
		"ETHERWARP_CONDUIT",
		"KUUDRA_SHOP_ITEM",
	)

	/** The four relics carried through Necron's phase, which are skulls. */
	private val RELICS = setOf(
		"WITHER_RELIC",
		"WITHER_BLOOD",
		"WITHER_SOUL",
		"WITHER_CLOAK",
		"WITHER_SHIELD",
		"IMPLOSION",
		"WITHER_CATALYST",
	)

	@JvmField
	val relics = ToggleModuleSetting(
		id = "relics",
		label = "Wither relics",
		defaultValue = true,
		description = "Only during Necron's phase.",
	)

	@JvmField
	val module = Module(
		id = "no_item_place",
		name = "No Item Place",
		description = "No placing items by mistake",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(relics),
	)

	val needsDungeon: Boolean get() = module.enabled

	/** True when this placement should be swallowed. */
	@JvmStatic
	fun blocks(context: BlockPlaceContext): Boolean {
		// Only in a dungeon; elsewhere placing these is sometimes the point.
		if (!module.enabled || !DungeonLocation.inDungeon) return false
		val stack = context.player?.mainHandItem ?: return false
		val id = SkyblockItem.id(stack)
		if (id.isEmpty()) return false

		if (relics.value && Floor7.phase == 5 && id in RELICS) return true
		if (PREFIXES.any { id.startsWith(it) }) return true
		if (SUFFIXES.any { id.endsWith(it) }) return true
		return id in EXACT
	}
}
