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

	/**
	 * A player head with one of Hypixel's textures, for drawing. The hash is the tail of the skin
	 * URL; resolved locally, so nothing is fetched from Mojang.
	 */
	fun head(textureHash: String): ItemStack {
		val payload = """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/$textureHash"}}}"""
		val property = com.mojang.authlib.properties.Property(
			"textures", java.util.Base64.getEncoder().encodeToString(payload.toByteArray()),
		)
		val properties = com.mojang.authlib.properties.PropertyMap(
			com.google.common.collect.ImmutableMultimap.builder<String, com.mojang.authlib.properties.Property>()
				.put("textures", property).build(),
		)
		return ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD).apply {
			set(
				DataComponents.PROFILE,
				net.minecraft.world.item.component.ResolvableProfile.createResolved(
					com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(textureHash.toByteArray()), "_", properties),
				),
			)
		}
	}

	private val TEXTURE_HASH = Regex("""texture/([0-9a-f]+)""")

	/** The hash inside a head texture's base64, or null. Base64 differs by timestamp; the hash doesn't. */
	fun textureHash(base64: String): String? =
		runCatching { String(java.util.Base64.getDecoder().decode(base64)) }.getOrNull()
			?.let { TEXTURE_HASH.find(it)?.groupValues?.get(1) }

	/** Whether any lore line contains [text]. Some item traits (a shortbow) exist only in the lore. */
	fun loreContains(stack: ItemStack, text: String): Boolean {
		val lore = stack.get(DataComponents.LORE) ?: return false
		return lore.lines().any { text in it.string }
	}
}
