package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.skyblock.SkyblockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Stops a shortbow pulling back when it has nothing to pull back for.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). A SkyBlock shortbow
 * fires the instant you press the button — the server has already shot before
 * the string has moved — but the client is still holding a vanilla bow, so it
 * plays the draw anyway. The result is an arm that stays back and a bow that
 * blocks your view for as long as you keep firing, for an animation that is
 * describing something that did not happen.
 *
 * Clearing the use state rather than cancelling the click is what keeps this
 * honest: the shot is the server's, made from the click Minecraft really sent,
 * and only the animation of drawing it is thrown away.
 */
object ArrowFix {
	/**
	 * Bows already decided about, by SkyBlock id.
	 *
	 * The answer is a property of the item rather than of the stack, and it is
	 * read every tick while one is held — so it is worked out once per kind of
	 * bow and then remembered, both ways round.
	 */
	private val shortbows = HashSet<String>()
	private val longbows = HashSet<String>()

	/** The line SkyBlock writes on a bow that does not need drawing. */
	private const val SHORTBOW_LORE = "Shortbow: Instantly shoots!"

	@JvmField
	val module = Module(
		id = "arrow_fix",
		name = "Arrow Fix",
		description = "Stops shortbows drawing back",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
	)

	@JvmStatic
	fun isShortbow(stack: ItemStack?): Boolean {
		if (!module.enabled) return false
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
