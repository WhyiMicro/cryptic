package imicro.cryptic.skyblock

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/**
 * What SkyBlock thinks an item is, as opposed to what Minecraft thinks.
 *
 * Every SkyBlock item is some vanilla item underneath — a sword is a diamond
 * sword, a shortbow is a bow — and the thing that tells them apart is an `id`
 * Hypixel writes into the item's custom data. Two different features need to
 * read it, so the reading lives here rather than in either of them.
 */
object SkyblockItem {
	/** `AOTE`, `TERMINATOR`, and so on. Empty for anything not SkyBlock's. */
	fun id(stack: ItemStack): String {
		if (stack.isEmpty) return ""
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
		return data.getString("id").orElse("")
	}

	/**
	 * The texture a player head is wearing, as the base64 Hypixel sends.
	 *
	 * Hypixel dresses a great deal in heads — a mob's model, an ability's
	 * effect, the Watcher himself — and none of it is otherwise labelled, so
	 * the skin is the only thing that says which one this is.
	 */
	fun skullTexture(stack: ItemStack): String? =
		stack.get(DataComponents.PROFILE)
			?.partialProfile()
			?.properties
			?.get("textures")
			?.firstOrNull()
			?.value

	/**
	 * Whether any line of the item's description contains [text].
	 *
	 * Some of what SkyBlock does to an item is written only in its lore — a bow
	 * being a shortbow is a line of text and nothing else — so a feature that
	 * cares has to read the description like a player does.
	 */
	fun loreContains(stack: ItemStack, text: String): Boolean {
		val lore = stack.get(DataComponents.LORE) ?: return false
		return lore.lines().any { text in it.string }
	}
}
