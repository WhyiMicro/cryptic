package imicro.cryptic.skyblock

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/** SkyBlock's own idea of an item: the `id` in its custom data, its head texture, and its lore. */
object SkyblockItem {
	fun id(stack: ItemStack): String {
		if (stack.isEmpty) return ""
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
		return data.getString("id").orElse("")
	}

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
