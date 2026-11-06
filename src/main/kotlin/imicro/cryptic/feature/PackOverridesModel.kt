package imicro.cryptic.feature

import com.google.gson.JsonElement
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.Dynamic
import com.mojang.serialization.JsonOps
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.CuboidItemModelWrapper
import net.minecraft.client.renderer.item.ItemModel
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemModels
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.resources.model.ResolvableModel
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.ItemOwner
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4fc
import java.util.Optional

/**
 * An item model with predicate overrides, the format's `firmament:predicates/legacy`: `fallback`,
 * then `overrides` of `{"predicate": {...}, "model": ...}`. As with vanilla's old model overrides,
 * the last override that matches wins. Old-format models' `overrides` are turned into this too.
 */
class PackOverridesModel(
	private val fallback: ItemModel,
	private val overrides: List<Pair<PackPredicates.Cached, ItemModel>>,
) : ItemModel {
	override fun update(
		output: ItemStackRenderState,
		item: ItemStack,
		resolver: ItemModelResolver,
		displayContext: ItemDisplayContext,
		level: ClientLevel?,
		owner: ItemOwner?,
		seed: Int,
	) {
		output.appendModelIdentityElement(this)
		val entity = owner?.asLivingEntity()
		var chosen = fallback
		if (PackTweaks.module.enabled && PackTweaks.predicates.value) {
			for (i in overrides.indices.reversed()) {
				if (overrides[i].first.test(item, entity, level)) {
					chosen = overrides[i].second
					break
				}
			}
		}
		chosen.update(output, item, resolver, displayContext, level, owner, seed)
	}

	class Override(val predicateJson: JsonElement, val model: ItemModel.Unbaked)

	class Unbaked(val fallback: ItemModel.Unbaked, val overrides: List<Override>, val where: String = "item model") : ItemModel.Unbaked {
		override fun type(): MapCodec<out ItemModel.Unbaked> = MAP_CODEC

		override fun bake(context: ItemModel.BakingContext, transformation: Matrix4fc): ItemModel = PackOverridesModel(
			fallback.bake(context, transformation),
			overrides.map { PackPredicates.Cached(PackPredicates.parseRoot(it.predicateJson, where)) to it.model.bake(context, transformation) },
		)

		override fun resolveDependencies(resolver: ResolvableModel.Resolver) {
			fallback.resolveDependencies(resolver)
			overrides.forEach { it.model.resolveDependencies(resolver) }
		}
	}

	companion object {
		@JvmField
		val TYPE_ID: Identifier = Identifier.fromNamespaceAndPath("firmament", "predicates/legacy")

		/** A model id (as old-format overrides give) or a whole item model. */
		private val MODEL: Codec<ItemModel.Unbaked> = Codec.either(Identifier.CODEC, ItemModels.CODEC).xmap(
			{ either -> either.map({ id -> plain(id) }, { it }) },
			{ Either.right(it) },
		)

		private val JSON: Codec<JsonElement> = Codec.PASSTHROUGH.comapFlatMap(
			{ dynamic -> DataResult.success(dynamic.convert(JsonOps.INSTANCE).value) },
			{ Dynamic(JsonOps.INSTANCE, it) },
		)

		private val OVERRIDE: Codec<Override> = RecordCodecBuilder.create { i ->
			i.group(
				JSON.fieldOf("predicate").forGetter(Override::predicateJson),
				MODEL.fieldOf("model").forGetter(Override::model),
			).apply(i) { predicate, model -> Override(predicate, model) }
		}

		@JvmField
		val MAP_CODEC: MapCodec<Unbaked> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				MODEL.fieldOf("fallback").forGetter(Unbaked::fallback),
				OVERRIDE.listOf().fieldOf("overrides").forGetter(Unbaked::overrides),
			).apply(i) { fallback, overrides -> Unbaked(fallback, overrides) }
		}

		fun plain(model: Identifier): ItemModel.Unbaked = CuboidItemModelWrapper.Unbaked(model, Optional.empty(), listOf())
	}
}
