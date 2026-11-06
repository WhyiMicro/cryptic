package imicro.cryptic.feature

import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import imicro.cryptic.Cryptic
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedExtraModel
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher
import net.minecraft.client.resources.model.ModelBaker
import net.minecraft.client.resources.model.ModelManager
import net.minecraft.client.resources.model.ResolvableModel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.CompletableFuture

/**
 * The format's block model replacements (`overrides/blocks`): per `/locraw` mode and area, a block
 * is drawn with another blockstates file or block model, and may get another mining hit sound.
 * Models are baked with the rest through Fabric's model loading; vanilla's and Sodium's chunk
 * builders ask [modelAt] for each block.
 */
object PackBlocks {
	class Area(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
		fun contains(pos: BlockPos) = pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ
	}

	/** The baked models of one replacement for one block: per state, or one for every state. */
	class Models(val byState: Map<BlockState, ExtraModelKey<BlockStateModel>>?, val single: ExtraModelKey<BlockStateModel>?)

	class Replacement(val model: Identifier, val sound: Identifier?)

	class Override(val file: Identifier, val modes: Set<String>, val areas: List<Area>?, val replacements: Map<Block, Replacement>)

	class Loaded(val overrides: List<Override> = emptyList(), val models: Map<Pair<Identifier, Block>, Models> = emptyMap())

	/** A replacement ready to draw: its area check and its baked models. */
	private class Active(val areas: List<Area>?, val byState: Map<BlockState, BlockStateModel>?, val single: BlockStateModel?, val sound: SoundEvent?) {
		fun contains(pos: BlockPos) = areas == null || areas.any { it.contains(pos) }
	}

	@Volatile
	private var loaded = Loaded()

	@Volatile
	private var lookup: Map<Block, List<Active>> = emptyMap()

	private var bakedPending = false
	private var activeKey: Any? = null

	/** Set by tests to stand in for the island's mode. */
	@JvmField
	var forcedMode: String? = null

	fun initialize() {
		PreparableModelLoadingPlugin.register(
			{ state, executor -> CompletableFuture.supplyAsync({ read(state.resourceManager()) }, executor) },
		) { data, context ->
			loaded = data
			for (models in data.models.values) {
				models.single?.let { context.addModel(it, singleModels.getValue(it)) }
				models.byState?.values?.forEach { context.addModel(it, rootModels.getValue(it)) }
			}
		}
	}

	/** Unbaked models by key, kept only while a reload builds them. */
	private val singleModels = HashMap<ExtraModelKey<BlockStateModel>, UnbakedExtraModel<BlockStateModel>>()
	private val rootModels = HashMap<ExtraModelKey<BlockStateModel>, UnbakedExtraModel<BlockStateModel>>()

	private class RootModel(val root: BlockStateModel.UnbakedRoot, val state: BlockState) : UnbakedExtraModel<BlockStateModel> {
		override fun resolveDependencies(resolver: ResolvableModel.Resolver) = root.resolveDependencies(resolver)
		override fun bake(baker: ModelBaker): BlockStateModel = root.bake(state, baker)
	}

	private fun read(manager: ResourceManager): Loaded {
		synchronized(singleModels) {
			singleModels.clear()
			rootModels.clear()
		}
		val overrides = PackTweaks.readAll(manager, "overrides/blocks").mapNotNull { (file, element) ->
			try {
				parse(file, element)
			} catch (e: Exception) {
				Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", file, e.message)
				null
			}
		}
		val models = HashMap<Pair<Identifier, Block>, Models>()
		for (override in overrides) {
			for ((block, replacement) in override.replacements) {
				val key = replacement.model to block
				if (key in models) continue
				models[key] = buildModels(manager, replacement.model, block) ?: continue
			}
		}
		return Loaded(overrides, models)
	}

	private fun parse(file: Identifier, element: JsonElement): Override {
		val json = element.asJsonObject
		val modes = json.getAsJsonArray("modes").map { it.asString }.toSet()
		val areas = json.getAsJsonArray("area")?.map { a ->
			val o = a.asJsonObject
			val min = o.getAsJsonArray("min").map { it.asInt }
			val max = o.getAsJsonArray("max").map { it.asInt }
			Area(minOf(min[0], max[0]), minOf(min[1], max[1]), minOf(min[2], max[2]), maxOf(min[0], max[0]), maxOf(min[1], max[1]), maxOf(min[2], max[2]))
		}
		val replacements = LinkedHashMap<Block, Replacement>()
		for ((blockId, value) in json.getAsJsonObject("replacements").entrySet()) {
			val block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId)).orElse(null)
			if (block == null) {
				Cryptic.LOGGER.warn("Pack Tweaks: {} names unknown block {}", file, blockId)
				continue
			}
			replacements[block] = if (value.isJsonPrimitive) {
				Replacement(Identifier.parse(value.asString), null)
			} else {
				val o = value.asJsonObject
				Replacement(Identifier.parse(o.get("block").asString), o.get("sound")?.asString?.let(Identifier::parse))
			}
		}
		return Override(file, modes, areas, replacements)
	}

	/** A blockstates file at [model] if there is one, else the block model `<ns>:block/<path>`. */
	private fun buildModels(manager: ResourceManager, model: Identifier, block: Block): Models? {
		val blockstates = model.withPath { "blockstates/$it.json" }
		val json = PackTweaks.readJson(manager, blockstates)
		if (json != null) {
			return try {
				val dispatcher = BlockStateModelDispatcher.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()
				val roots = dispatcher.instantiate(block.stateDefinition) { "$blockstates for ${BuiltInRegistries.BLOCK.getKey(block)}" }
				val keys = HashMap<BlockState, ExtraModelKey<BlockStateModel>>()
				synchronized(singleModels) {
					for ((state, root) in roots) {
						val key = ExtraModelKey.create<BlockStateModel> { "cryptic pack block $model $state" }
						rootModels[key] = RootModel(root, state)
						keys[state] = key
					}
				}
				Models(keys, null)
			} catch (e: Exception) {
				Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", blockstates, e.message)
				null
			}
		}
		val blockModel = model.withPrefix("block/")
		if (manager.getResource(blockModel.withPath { "models/$it.json" }).isEmpty) {
			Cryptic.LOGGER.warn("Pack Tweaks: block replacement {} has neither {} nor a block model", model, blockstates)
			return null
		}
		val key = ExtraModelKey.create<BlockStateModel> { "cryptic pack block $blockModel" }
		synchronized(singleModels) { singleModels[key] = SimpleUnbakedExtraModel.blockStateModel(blockModel) }
		return Models(null, key)
	}

	/** Called after models are baked; the models are fetched on the next tick. */
	@JvmStatic
	fun onModelsBaked() {
		bakedPending = true
	}

	private var bakedModels: Map<ExtraModelKey<BlockStateModel>, BlockStateModel> = emptyMap()
	private var bakedGeneration = 0

	fun tick(client: Minecraft) {
		if (bakedPending) {
			bakedPending = false
			bakedModels = fetchBaked(client.modelManager)
			bakedGeneration++
			synchronized(singleModels) {
				singleModels.clear()
				rootModels.clear()
			}
			activeKey = null
		}
		val mode = currentMode()
		val on = PackTweaks.on(PackTweaks.blocks)
		val key = Triple(on, mode, bakedGeneration)
		if (key == activeKey) return
		activeKey = key
		lookup = if (on && mode != null) build(mode) else emptyMap()
		if (client.level != null) client.levelExtractor.allChanged()
	}

	private fun fetchBaked(manager: ModelManager): Map<ExtraModelKey<BlockStateModel>, BlockStateModel> {
		val fabric = manager as FabricModelManager
		val out = HashMap<ExtraModelKey<BlockStateModel>, BlockStateModel>()
		for (models in loaded.models.values) {
			models.single?.let { k -> fabric.getModel(k)?.let { out[k] = it } }
			models.byState?.values?.forEach { k -> fabric.getModel(k)?.let { out[k] = it } }
		}
		return out
	}

	private fun build(mode: String): Map<Block, List<Active>> {
		val out = HashMap<Block, MutableList<Active>>()
		val current = loaded
		for (override in current.overrides) {
			if (mode !in override.modes) continue
			for ((block, replacement) in override.replacements) {
				val models = current.models[replacement.model to block] ?: continue
				val byState = models.byState?.mapNotNull { (state, k) -> bakedModels[k]?.let { state to it } }?.toMap()
				val single = models.single?.let { bakedModels[it] }
				if (byState.isNullOrEmpty() && single == null) continue
				val sound = replacement.sound?.let { SoundEvent.createVariableRangeEvent(it) }
				out.getOrPut(block) { mutableListOf() } += Active(override.areas, byState, single, sound)
			}
		}
		return out
	}

	/** Hypixel's `/locraw` mode for the island, from the tab list's area name. */
	private val MODES = mapOf(
		"Private Island" to "dynamic",
		"Garden" to "garden",
		"Hub" to "hub",
		"Dungeon Hub" to "dungeon_hub",
		"Catacombs" to "dungeon",
		"The Farming Islands" to "farming_1",
		"The Park" to "foraging_1",
		"Galatea" to "foraging_2",
		"Gold Mine" to "mining_1",
		"Deep Caverns" to "mining_2",
		"Dwarven Mines" to "mining_3",
		"Crystal Hollows" to "crystal_hollows",
		"Mineshaft" to "mineshaft",
		"Spider's Den" to "combat_1",
		"Crimson Isle" to "crimson_isle",
		"The End" to "combat_3",
		"Kuudra" to "kuudra",
		"Jerry's Workshop" to "winter",
		"The Rift" to "rift",
		"Backwater Bayou" to "fishing_1",
		"Dark Auction" to "dark_auction",
	)

	fun currentMode(): String? {
		forcedMode?.let { return it }
		if (!SkyblockLocation.onSkyblock) return null
		return MODES[SkyblockLocation.area ?: return null]
	}

	/** The model to draw the block at [pos] with. Runs on chunk builder threads. */
	@JvmStatic
	fun modelAt(state: BlockState, pos: BlockPos, original: BlockStateModel): BlockStateModel {
		val map = lookup
		if (map.isEmpty()) return original
		val list = map[state.block] ?: return original
		for (active in list) {
			if (!active.contains(pos)) continue
			return active.byState?.get(state) ?: active.single ?: original
		}
		return original
	}

	/** The mining hit sound for the block at [pos], or null to keep its own. */
	@JvmStatic
	fun hitSound(state: BlockState, pos: BlockPos): SoundEvent? {
		val list = lookup[state.block] ?: return null
		return list.firstOrNull { it.contains(pos) }?.sound
	}

	fun describe(): String {
		val current = loaded
		return "Block overrides: ${current.overrides.size} files, ${bakedModels.size} models baked, mode ${currentMode() ?: "unknown"} (area ${SkyblockLocation.area ?: "none"}), ${lookup.size} blocks replaced here"
	}
}
