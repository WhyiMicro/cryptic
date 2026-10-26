package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Leaves armour off players as they're drawn: everyone's, everyone else's, or only yours, and only
 * the pieces picked. Nothing changes in the world; the pieces are just left out of the picture.
 * Helmets that are heads (most of SkyBlock's) count as helmets. An elytra stays visible.
 */
object HideArmor {
	private const val WHO_ALL = 0
	private const val WHO_OTHERS = 1
	private const val WHO_YOU = 2

	@JvmField
	val who = DropdownModuleSetting(
		id = "who",
		label = "Whose armour",
		options = listOf("All", "Others", "Your own"),
		description = "Everyone's, everyone's but yours, or only yours.",
	)

	private val piecesSection = SectionModuleSetting("pieces_section", "Pieces")

	@JvmField
	val helmet = ToggleModuleSetting("helmet", "Helmet", true)

	@JvmField
	val chestplate = ToggleModuleSetting("chestplate", "Chestplate", true)

	@JvmField
	val leggings = ToggleModuleSetting("leggings", "Leggings", true)

	@JvmField
	val boots = ToggleModuleSetting("boots", "Boots", true)

	@JvmField
	val module = Module(
		id = "hide_armor",
		name = "Hide Armor",
		description = "Leaves armour off players",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(who, piecesSection, helmet, chestplate, leggings, boots),
	)

	/** Clears the picked pieces from a player's render state, after it's filled in. */
	@JvmStatic
	fun apply(entity: Any, state: AvatarRenderState) {
		if (!module.enabled || entity !is Player) return
		val yours = entity === Minecraft.getInstance().player
		when (who.selectedIndex) {
			WHO_OTHERS -> if (yours) return
			WHO_YOU -> if (!yours) return
		}
		if (helmet.value) {
			state.headEquipment = ItemStack.EMPTY
			state.headItem.clear()
			state.wornHeadType = null
			state.wornHeadProfile = null
		}
		// An elytra is worn in the chest slot; it isn't armour.
		if (chestplate.value && !state.chestEquipment.has(DataComponents.GLIDER)) state.chestEquipment = ItemStack.EMPTY
		if (leggings.value) state.legsEquipment = ItemStack.EMPTY
		if (boots.value) state.feetEquipment = ItemStack.EMPTY
	}
}
