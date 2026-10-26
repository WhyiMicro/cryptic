package imicro.cryptic.feature

import imicro.cryptic.skyblock.SkyblockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Stops a shortbow playing the draw animation: the server has already fired, but the client holds a
 * vanilla bow. Only the use state is cleared; the shot is untouched. Ported from NoammAddons. Part
 * of [BowTweaks].
 */
object ArrowFix {
	/** Bows already checked, by SkyBlock id, since it's read every tick while one is held. */
	private val shortbows = HashSet<String>()
	private val longbows = HashSet<String>()

	private const val SHORTBOW_LORE = "Shortbow: Instantly shoots!"

	/** Whether Arrow fix should clear the use state for this bow. */
	@JvmStatic
	fun isShortbow(stack: ItemStack?): Boolean =
		BowTweaks.module.enabled && BowTweaks.arrowFix.value && isShortbowItem(stack)

	@JvmStatic
	fun isShortbowItem(stack: ItemStack?): Boolean {
		if (stack == null || stack.isEmpty || !stack.`is`(Items.BOW)) return false

		val id = SkyblockItem.id(stack)
		if (id in shortbows) return true
		if (id in longbows) return false

		return if (SkyblockItem.loreContains(stack, SHORTBOW_LORE)) {
			shortbows.add(id)
			true
		} else {
			longbows.add(id)
			false
		}
	}
}
