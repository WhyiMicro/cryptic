package imicro.cryptic.feature

import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.SkyblockItem
import net.minecraft.world.item.context.BlockPlaceContext

/**
 * Stops the items that are used by right-clicking from being placed instead.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). SkyBlock builds most of
 * its right-click items out of vanilla blocks — a Power Orb is a skull, the
 * Flower of Truth is a flower — so aiming a hair low while using one places it
 * in the world. In a dungeon that is a wither relic stuck in the floor during
 * Necron, or an Etherwarp Conduit left behind in a boss room.
 *
 * The click still happens: it is the *placement* that is dropped, and the
 * server goes on receiving the use it was sent. Nothing is faked, and an item
 * that really is meant to be placed is not in the list.
 */
object NoItemPlace {
	/** Items whose id begins this way, whatever the rest of it says. */
	private val PREFIXES = listOf("ABIPHONE")

	/** Items whose id ends this way — every tier of the same thing. */
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
		description = "Stops placing items by accident",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(relics),
	)

	/** True when this placement should be swallowed. */
	@JvmStatic
	fun blocks(context: BlockPlaceContext): Boolean {
		if (!module.enabled) return false
		val stack = context.player?.mainHandItem ?: return false
		val id = SkyblockItem.id(stack)
		if (id.isEmpty()) return false

		if (relics.value && Floor7.phase == 5 && id in RELICS) return true
		if (PREFIXES.any { id.startsWith(it) }) return true
		if (SUFFIXES.any { id.endsWith(it) }) return true
		return id in EXACT
	}
}
