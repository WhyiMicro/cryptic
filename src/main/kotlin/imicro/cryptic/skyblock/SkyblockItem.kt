package imicro.cryptic.skyblock

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/** SkyBlock's own idea of an item: the `id` in its custom data, its head texture, and its lore. */
object SkyblockItem {
	fun id(stack: ItemStack): String {
		if (stack.isEmpty) return ""
		return data(stack).getString("id").orElse("")
	}

	/**
	 * The stack's custom data as it is, not a copy: asked for every slot every frame, where copying
	 * all of Hypixel's data each time showed in profiles. Never change what this returns.
	 */
	fun data(stack: ItemStack): net.minecraft.nbt.CompoundTag =
		(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY) as imicro.cryptic.mixin.CustomDataAccessor).`cryptic$tag`()

	/**
	 * A player head's texture, as the base64 Hypixel sends. Often the only way to tell Hypixel's
	 * heads apart.
	 */
	fun skullTexture(stack: ItemStack): String? =
		stack.get(DataComponents.PROFILE)
			?.partialProfile()
			?.properties
			?.get("textures")
			?.firstOrNull()
			?.value

	/** Whether any lore line contains [text]. Some item traits (a shortbow) exist only in the lore. */
	fun loreContains(stack: ItemStack, text: String): Boolean {
		val lore = stack.get(DataComponents.LORE) ?: return false
		return lore.lines().any { text in it.string }
	}
}
