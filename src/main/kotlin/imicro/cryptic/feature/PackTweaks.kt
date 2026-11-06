package imicro.cryptic.feature

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.color.item.Constant
import net.minecraft.client.color.item.ItemTintSource
import net.minecraft.client.color.item.ItemTintSources
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.ClientItem
import net.minecraft.client.renderer.item.CuboidItemModelWrapper
import net.minecraft.client.renderer.item.ItemModel
import net.minecraft.client.renderer.item.ItemModels
import net.minecraft.client.resources.model.ClientItemInfoLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.Locale
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads resource packs made for Firmament's `firmskyblock` format, so they work without Firmament.
 * Only the pack format is followed (paths, keys and the ID rule from Firmament's docs); the code is
 * Cryptic's own. Everything but block models is read while item models load (see
 * [onItemInfosLoaded]) and goes live with the baked models; block models are in [PackBlocks].
 */
object PackTweaks {
	const val NAMESPACE = "firmskyblock"

	@JvmField
	val itemModels = ToggleModuleSetting(
		id = "item_models",
		label = "Item models",
		defaultValue = true,
		description = "Draws SkyBlock items with the pack's model for their ID: firmskyblock:items/<id>.json, or the older firmskyblock:models/item/<id>.json.",
	)

	@JvmField
	val predicates = ToggleModuleSetting(
		id = "predicates",
		label = "Model overrides",
		defaultValue = true,
		description = "Switches models by the format's predicates (name, lore, NBT, pet, skull and more), as in a model's overrides or a firmament:predicates/legacy item.",
	)

	@JvmField
	val tintOverrides = ToggleModuleSetting(
		id = "tint_overrides",
		label = "Tint overrides",
		defaultValue = true,
		description = "Uses a model's firmament:tint_overrides in place of the item's own tint (leather dye, potion colour).",
	)

	@JvmField
	val globalOverrides = ToggleModuleSetting(
		id = "global_overrides",
		label = "Global item overrides",
		defaultValue = true,
		description = "Retextures items without a SkyBlock ID by predicate, from firmskyblock:overrides/item, in the screens each names.",
	)

	@JvmField
	val headModels = ToggleModuleSetting(
		id = "head_models",
		label = "Head models",
		defaultValue = true,
		description = "Draws a model's firmament:head_model on your head when the item is worn as a helmet.",
	)

	@JvmField
	val armour = ToggleModuleSetting(
		id = "armour",
		label = "Armour textures",
		defaultValue = true,
		description = "Retextures worn armour by SkyBlock ID, from firmskyblock:overrides/armor_models.",
	)

	@JvmField
	val skulls = ToggleModuleSetting(
		id = "placed_skulls",
		label = "Placed skulls",
		defaultValue = true,
		description = "Swaps the skin of placed heads for firmskyblock:textures/placedskulls/<hash>.png.",
	)

	@JvmField
	val screenLayouts = ToggleModuleSetting(
		id = "screen_layouts",
		label = "Screen layouts",
		defaultValue = true,
		description = "Replaces menu backgrounds and moves slots, titles and fields, from firmskyblock:overrides/screen_layout.",
	)

	@JvmField
	val textColours = ToggleModuleSetting(
		id = "text_colours",
		label = "Menu text colours",
		defaultValue = true,
		description = "Recolours the titles in menus, from firmskyblock:overrides/text_colors.json.",
	)

	@JvmField
	val textReplacements = ToggleModuleSetting(
		id = "text_replacements",
		label = "Text replacements",
		defaultValue = true,
		description = "Replaces parts of drawn text and tooltips, from firmskyblock:overrides/texts.",
	)

	@JvmField
	val blocks = ToggleModuleSetting(
		id = "blocks",
		label = "Block models",
		defaultValue = true,
		description = "Retextures blocks per island and area, from firmskyblock:overrides/blocks, with Sodium too.",
	)

	@JvmField
	val module = Module(
		id = "pack_tweaks",
		name = "Pack Tweaks",
		description = "Firmament-format packs",
		category = ModuleCategory.VISUAL,
		settings = listOf(
			itemModels, predicates, tintOverrides, globalOverrides, headModels, armour, skulls,
			screenLayouts, textColours, textReplacements, blocks,
		),
	)

	fun initialize() {
		// With Firmament installed, its own type reads these items.
		if (!FabricLoader.getInstance().isModLoaded("firmament")) {
			ItemModels.ID_MAPPER.put(PackOverridesModel.TYPE_ID, PackOverridesModel.MAP_CODEC)
		}
		ItemTintSources.ID_MAPPER.put(PackTint.TYPE_ID, PackTint.MAP_CODEC)
		PackBlocks.initialize()
		PackTexts.initialize()
	}

	fun tick(client: Minecraft) {
		PackBlocks.tick(client)
	}

	@JvmStatic
	fun on(setting: ToggleModuleSetting): Boolean = module.enabled && setting.value

	// ---- Loaded data -------------------------------------------------------------------------

	/** An old-format item: its model and the overrides read from it, kept for head models. */
	class LegacyItem(val model: Identifier, val overrides: List<Pair<PackPredicates.Cached, Identifier>>)

	class GlobalOverride(val screens: List<PackPredicates.StringMatcher?>, val predicate: PackPredicates.Cached, val item: Identifier)

	class ArmourLayer(val identifier: Identifier, val tint: Boolean, val suffix: String)

	class ArmourSpec(val layers: List<ArmourLayer>, val overrides: List<Pair<PackPredicates.Cached, List<ArmourLayer>>>)

	/** Everything read on one reload but block models. */
	class Data(
		val available: Map<String, Identifier> = emptyMap(),
		val legacyCount: Int = 0,
		val vanillaTints: Map<Identifier, List<ItemTintSource>> = emptyMap(),
		val legacyItems: Map<Identifier, LegacyItem> = emptyMap(),
		val headByModel: Map<Identifier, Identifier> = emptyMap(),
		val globals: List<GlobalOverride> = emptyList(),
		val armour: Map<String, ArmourSpec> = emptyMap(),
		val armourTextures: Set<Identifier> = emptySet(),
		val skullHashes: Set<String> = emptySet(),
		val screens: PackScreens.Data = PackScreens.Data(),
		val texts: List<PackTexts.Override> = emptyList(),
	)

	@Volatile
	@JvmField
	var data = Data()

	@Volatile
	private var pending: Data? = null

	@Volatile
	private var generation = 0

	// ---- Loading -----------------------------------------------------------------------------

	/** Reads models (and their parents) once per reload. */
	private class ModelReader(val manager: ResourceManager) {
		private val cache = HashMap<Identifier, JsonObject?>()

		fun read(model: Identifier): JsonObject? = cache.getOrPut(model) {
			val file = model.withPath { "models/$it.json" }
			manager.getResource(file).map { resource ->
				runCatching { resource.openAsReader().use { JsonParser.parseReader(it) }.asJsonObject }.getOrNull()
			}.orElse(null)
		}

		fun exists(model: Identifier) = read(model) != null

		/** The model and its parents, child first. */
		fun chain(model: Identifier): List<JsonObject> {
			val out = mutableListOf<JsonObject>()
			var id: Identifier? = model
			while (id != null && out.size < 32) {
				val json = read(id) ?: break
				out += json
				id = json.get("parent")?.takeIf { it.isJsonPrimitive }?.asString?.let(Identifier::tryParse)
			}
			return out
		}
	}

	/**
	 * Adds a client item for every old-format `firmskyblock:models/item/<id>.json` that has no
	 * `items/<id>.json`, and reads the rest of the pack format alongside.
	 */
	@JvmStatic
	fun withLegacyModels(
		manager: ResourceManager,
		loading: CompletableFuture<ClientItemInfoLoader.LoadedClientInfos>,
	): CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> = loading.thenApply { infos ->
		try {
			onItemInfosLoaded(manager, infos)
		} catch (e: Exception) {
			Cryptic.LOGGER.error("Pack Tweaks: couldn't read the packs", e)
			infos
		}
	}

	private fun onItemInfosLoaded(manager: ResourceManager, infos: ClientItemInfoLoader.LoadedClientInfos): ClientItemInfoLoader.LoadedClientInfos {
		val contents = HashMap(infos.contents())
		val reader = ModelReader(manager)

		val vanillaTints = HashMap<Identifier, List<ItemTintSource>>()
		for ((id, item) in contents) {
			if (id.namespace != "minecraft") continue
			plainModel(item.model(), 0)?.tints()?.takeIf { it.isNotEmpty() }?.let { vanillaTints[id] = it }
		}

		val legacyItems = HashMap<Identifier, LegacyItem>()
		val headByModel = HashMap<Identifier, Identifier>()
		var added = 0

		fun noteHead(model: Identifier) {
			if (model in headByModel) return
			val head = reader.chain(model).firstNotNullOfOrNull { it.get("firmament:head_model")?.takeIf { e -> e.isJsonPrimitive }?.asString }
				?.let(Identifier::tryParse) ?: return
			headByModel[model] = head
		}

		/** A client item from an old-format item model, with its overrides. */
		fun addLegacy(itemId: Identifier, model: Identifier): Boolean {
			if (itemId in contents) return true
			val json = reader.read(model) ?: return false
			val overrides = legacyOverrides(json, model)
			val base = legacyModel(reader, model)
			val unbaked = if (overrides.isEmpty()) {
				base
			} else {
				PackOverridesModel.Unbaked(base, overrides.map { (predicate, target) -> PackOverridesModel.Override(predicate, legacyModel(reader, target)) }, model.toString())
			}
			contents[itemId] = ClientItem(unbaked, ClientItem.Properties.DEFAULT)
			legacyItems[itemId] = LegacyItem(model, overrides.map { (predicate, target) -> PackPredicates.Cached(PackPredicates.parseRoot(predicate, model.toString())) to target })
			noteHead(model)
			overrides.forEach { noteHead(it.second) }
			added++
			return true
		}

		for (file in manager.listResources("models/item") { it.namespace == NAMESPACE && it.path.endsWith(".json") }.keys) {
			val name = file.path.removePrefix("models/item/").removeSuffix(".json")
			val itemId = Identifier.tryBuild(NAMESPACE, name) ?: continue
			addLegacy(itemId, Identifier.fromNamespaceAndPath(NAMESPACE, "item/$name"))
		}

		val globals = readGlobals(manager) { item -> addLegacy(item, item.withPrefix("item/")) || item in contents }

		// Head models are drawn through client items of their own.
		for (head in headByModel.values.toSet()) {
			contents[headItemId(head)] = ClientItem(PackOverridesModel.plain(head), ClientItem.Properties.DEFAULT)
		}

		val armourTextures = manager.listResources("textures/models/armor") { it.path.endsWith(".png") }.keys.toSet()
		val skullHashes = manager.listResources("textures/placedskulls") { it.namespace == NAMESPACE && it.path.endsWith(".png") }.keys
			.map { it.path.substringAfterLast('/').removeSuffix(".png") }.toSet()

		pending = Data(
			legacyCount = added,
			vanillaTints = vanillaTints,
			legacyItems = legacyItems,
			headByModel = headByModel,
			globals = globals,
			armour = readArmour(manager),
			armourTextures = armourTextures,
			skullHashes = skullHashes,
			screens = PackScreens.read(manager, ::readJson),
			texts = PackTexts.read(manager, ::readJson),
		)
		return ClientItemInfoLoader.LoadedClientInfos(contents)
	}

	/**
	 * The first plain model inside a vanilla item model, whose tints are the item's own. Leather
	 * armour, for one, wraps it in a select on the trim.
	 */
	private fun plainModel(value: Any?, depth: Int): CuboidItemModelWrapper.Unbaked? {
		if (depth > 8 || value == null) return null
		return when (value) {
			is CuboidItemModelWrapper.Unbaked -> value
			is Optional<*> -> plainModel(value.orElse(null), depth + 1)
			is Iterable<*> -> value.firstNotNullOfOrNull { plainModel(it, depth + 1) }
			is Record -> value.javaClass.recordComponents.firstNotNullOfOrNull { component ->
				runCatching { component.accessor.apply { isAccessible = true }.invoke(value) }.getOrNull()?.let { plainModel(it, depth + 1) }
			}
			else -> null
		}
	}

	fun readJson(manager: ResourceManager, file: Identifier): JsonElement? = manager.getResource(file).map { resource ->
		try {
			resource.openAsReader().use { JsonParser.parseReader(it) }
		} catch (e: Exception) {
			Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", file, e.message)
			null
		}
	}.orElse(null)

	/** Every JSON file under [dir] in the `firmskyblock` namespace. */
	fun readAll(manager: ResourceManager, dir: String): List<Pair<Identifier, JsonElement>> =
		manager.listResources(dir) { it.namespace == NAMESPACE && it.path.endsWith(".json") }.keys.sorted()
			.mapNotNull { file -> readJson(manager, file)?.let { file to it } }

	private fun headItemId(head: Identifier) = Identifier.fromNamespaceAndPath(Cryptic.MOD_ID, "pack_head/${head.namespace}/${head.path}")

	/** An old-format model's `overrides`: each `predicate` object and the `model` id to draw. */
	private fun legacyOverrides(json: JsonObject, model: Identifier): List<Pair<JsonElement, Identifier>> = try {
		json.getAsJsonArray("overrides")?.mapNotNull { entry ->
			val obj = entry.asJsonObject
			val predicate = obj.get("predicate") ?: return@mapNotNull null
			val target = Identifier.tryParse(obj.get("model")?.asString ?: return@mapNotNull null) ?: return@mapNotNull null
			predicate to target
		} ?: emptyList()
	} catch (e: Exception) {
		Cryptic.LOGGER.warn("Pack Tweaks: couldn't read the overrides of {}: {}", model, e.message)
		emptyList()
	}

	/**
	 * An old-format model as an item model. Its layers are tinted as the item's own model tints
	 * them, as Minecraft did before client items, unless `firmament:tint_overrides` (inherited from
	 * parents, `null` clearing a parent's) says otherwise.
	 */
	private fun legacyModel(reader: ModelReader, model: Identifier): ItemModel.Unbaked {
		val overrides = HashMap<Int, Int>()
		for (json in reader.chain(model).asReversed()) {
			val tints = json.get("firmament:tint_overrides")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
			for ((key, value) in tints.entrySet()) {
				val layer = key.toIntOrNull() ?: continue
				if (value.isJsonNull) overrides.remove(layer) else runCatching { value.asInt }.getOrNull()?.let { overrides[layer] = it }
			}
		}
		val layers = maxOf(2, (overrides.keys.maxOrNull() ?: -1) + 1)
		return CuboidItemModelWrapper.Unbaked(model, Optional.empty(), List(layers) { PackTint(it, overrides[it]) })
	}

	private fun readGlobals(manager: ResourceManager, ensureItem: (Identifier) -> Boolean): List<GlobalOverride> {
		val filters = HashMap<Identifier, PackPredicates.StringMatcher?>()
		fun filter(id: Identifier): PackPredicates.StringMatcher? = filters.getOrPut(id) {
			if (id.namespace == "firmament" && id.path == "always") return@getOrPut null
			val json = readJson(manager, id.withPath { "filters/screen/$it.json" })?.asJsonObject
			val title = json?.get("title")
			if (title == null) {
				Cryptic.LOGGER.warn("Pack Tweaks: screen filter {} has no title", id)
				PackPredicates.StringMatcher(false) { false }
			} else {
				PackPredicates.StringMatcher.parse(title)
			}
		}
		return readAll(manager, "overrides/item").mapNotNull { (file, element) ->
			try {
				val json = element.asJsonObject
				val item = Identifier.parse(json.get("model").asString)
				if (!ensureItem(item)) {
					Cryptic.LOGGER.warn("Pack Tweaks: {} names model {}, which doesn't exist", file, item)
					return@mapNotNull null
				}
				val screens = when (val s = json.get("screen")) {
					null -> listOf(null)
					else -> (if (s.isJsonArray) s.asJsonArray.map { it.asString } else listOf(s.asString)).map { filter(Identifier.parse(it)) }
				}
				val predicate = json.get("predicate")?.let { PackPredicates.parseRoot(it, file.toString()) } ?: PackPredicates.parseRoot(JsonObject(), file.toString())
				GlobalOverride(screens, PackPredicates.Cached(predicate), item)
			} catch (e: Exception) {
				Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", file, e.message)
				null
			}
		}
	}

	private fun readArmour(manager: ResourceManager): Map<String, ArmourSpec> {
		fun layers(json: JsonElement): List<ArmourLayer> = json.asJsonArray.map { e ->
			val o = e.asJsonObject
			ArmourLayer(Identifier.parse(o.get("identifier").asString), o.get("tint")?.asBoolean ?: false, o.get("suffix")?.asString ?: "")
		}
		val out = HashMap<String, ArmourSpec>()
		for ((file, element) in readAll(manager, "overrides/armor_models")) {
			try {
				val json = element.asJsonObject
				val spec = ArmourSpec(
					layers(json.get("layers")),
					json.getAsJsonArray("overrides")?.map { o ->
						val obj = o.asJsonObject
						PackPredicates.Cached(PackPredicates.parseRoot(obj.get("predicate"), file.toString())) to layers(obj.get("layers"))
					} ?: emptyList(),
				)
				json.getAsJsonArray("item_ids").forEach { out[it.asString] = spec }
			} catch (e: Exception) {
				Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", file, e.message)
			}
		}
		return out
	}

	/** Called once item models are baked, so nothing is used before it can be drawn. */
	@JvmStatic
	fun onItemModelsBaked(ids: Collection<Identifier>) {
		val loaded = pending ?: Data()
		pending = null
		data = Data(
			available = ids.filter { it.namespace == NAMESPACE }.associateBy { it.path },
			legacyCount = loaded.legacyCount,
			vanillaTints = loaded.vanillaTints,
			legacyItems = loaded.legacyItems,
			headByModel = loaded.headByModel,
			globals = loaded.globals,
			armour = loaded.armour,
			armourTextures = loaded.armourTextures,
			skullHashes = loaded.skullHashes,
			screens = loaded.screens,
			texts = loaded.texts,
		)
		skullCache.clear()
		PackTexts.clearCache()
		generation++
	}

	// ---- Item models -------------------------------------------------------------------------

	private object None

	/** Each stack's model, or [None]. Stacks are replaced, not edited, when Hypixel changes an item. */
	private val cache = WeakHashMap<ItemStack, Any>()
	private var cacheGeneration = -1
	private var cacheScreen: Any? = null

	/** The item model to draw [stack] with: the pack's model for its SkyBlock ID, else [original]. */
	@JvmStatic
	fun modelFor(stack: ItemStack, original: Identifier?): Identifier? {
		if (!module.enabled || stack.isEmpty) return original
		val byId = itemModels.value
		val global = globalOverrides.value
		if (!byId && !global) return original
		val current = data
		if (current.available.isEmpty() && current.globals.isEmpty()) return original

		val screen = Minecraft.getInstance().gui.screen()
		synchronized(cache) {
			if (cacheGeneration != generation || cacheScreen !== screen) {
				cache.clear()
				cacheGeneration = generation
				cacheScreen = screen
			}
			cache[stack]?.let { return if (it === None) original else it as Identifier }
		}
		var found: Identifier? = null
		if (byId) {
			val itemData = SkyblockItem.data(stack)
			if (itemData.contains("id")) found = candidates(itemData).firstNotNullOfOrNull { current.available[mangle(it)] }
		}
		if (found == null && global && current.globals.isNotEmpty()) found = globalFor(stack, current, screen)
		synchronized(cache) { cache[stack] = found ?: None }
		return found ?: original
	}

	private fun globalFor(stack: ItemStack, current: Data, screen: net.minecraft.client.gui.screens.Screen?): Identifier? {
		val title = screen?.title
		for (override in current.globals) {
			val inScreen = override.screens.any { matcher ->
				matcher == null || (title != null && matcher.matches(PackPredicates.render(title, matcher.preserve)))
			}
			if (inScreen && override.predicate.test(stack, null, null)) return override.item
		}
		return null
	}

	/**
	 * The IDs a pack may name the item by, most specific first. Packs use NEU repo IDs, which add
	 * the tier or level to pets, books and runes (`WOLF;4`, `ULTIMATE_WISE;5`, `MUSIC_RUNE;3`).
	 */
	private fun candidates(data: CompoundTag): List<String> {
		val id = data.getString("id").orElse("")
		if (id.isEmpty()) return emptyList()
		val specific = when (id) {
			"PET" -> petId(data)
			"ENCHANTED_BOOK" -> data.getCompoundOrEmpty("enchantments").single()?.let { (name, level) -> "${name.uppercase(Locale.ROOT)};$level" }
			"RUNE", "UNIQUE_RUNE" -> data.getCompoundOrEmpty("runes").single()?.let { (name, level) -> "${name.uppercase(Locale.ROOT)}_RUNE;$level" }
			else -> null
		}
		return if (specific == null) listOf(id) else listOf(specific, id)
	}

	private val TIERS = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

	private fun petId(data: CompoundTag): String? {
		val info = runCatching { JsonParser.parseString(data.getString("petInfo").orElse("")).asJsonObject }.getOrNull() ?: return null
		val type = info.get("type")?.asString ?: return null
		val tier = TIERS.indexOf(info.get("tier")?.asString ?: return null)
		return if (tier < 0) null else "$type;$tier"
	}

	/** The one entry of a compound such as `enchantments`, or null if it has none or several. */
	private fun CompoundTag.single(): Pair<String, Int>? {
		val key = keySet().singleOrNull() ?: return null
		return key to getIntOr(key, 0)
	}

	/**
	 * A SkyBlock ID as a resource path, by the format's rule: lower case, `:` as `___`, `;` as `__`,
	 * and any other character a path can't hold as `__` and its four-digit hex code.
	 */
	@JvmStatic
	fun mangle(id: String): String {
		val out = StringBuilder(id.length + 4)
		for (c in id.lowercase(Locale.ROOT)) {
			when {
				c == ':' -> out.append("___")
				c == ';' -> out.append("__")
				c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '/' -> out.append(c)
				else -> out.append("__").append(String.format(Locale.ROOT, "%04x", c.code))
			}
		}
		return out.toString()
	}

	// ---- Head models -------------------------------------------------------------------------

	/** The model the next item lookup must use, while a head model is drawn. Render thread only. */
	@JvmField
	var forcedModel: Identifier? = null

	/** The client item to draw on the head for [stack], after its overrides, or null. */
	@JvmStatic
	fun headModelFor(stack: ItemStack, entity: LivingEntity?, level: ClientLevel?): Identifier? {
		if (!on(headModels) || stack.isEmpty) return null
		val current = data
		if (current.headByModel.isEmpty()) return null
		val item = current.legacyItems[modelFor(stack, null) ?: return null] ?: return null
		var model = item.model
		if (predicates.value) {
			for (i in item.overrides.indices.reversed()) {
				if (item.overrides[i].first.test(stack, entity, level)) {
					model = item.overrides[i].second
					break
				}
			}
		}
		return current.headByModel[model]?.let(::headItemId)
	}

	// ---- Armour ------------------------------------------------------------------------------

	/** A layer's texture, by vanilla's old armour paths, and whether it takes the dye colour. */
	class ArmourTexture(@JvmField val texture: Identifier, @JvmField val tint: Boolean)

	/**
	 * The pack's layers for worn armour, or null to draw it as usual. [legs] picks the
	 * `_layer_2` textures, as vanilla's old leggings did.
	 */
	@JvmStatic
	fun armourFor(stack: ItemStack, legs: Boolean): List<ArmourTexture>? {
		if (!on(armour)) return null
		val id = SkyblockItem.id(stack).ifEmpty { return null }
		val current = data
		val number = if (legs) 2 else 1
		val spec = current.armour[id]
		if (spec == null) {
			// Older packs put the texture straight at the ID, with an optional overlay.
			val base = Identifier.fromNamespaceAndPath(NAMESPACE, "textures/models/armor/${mangle(id)}_layer_$number.png")
			if (base !in current.armourTextures) return null
			val overlay = base.withPath { it.removeSuffix(".png") + "_overlay.png" }
			return if (overlay in current.armourTextures) listOf(ArmourTexture(base, true), ArmourTexture(overlay, false)) else listOf(ArmourTexture(base, true))
		}
		var layers = spec.layers
		for (i in spec.overrides.indices.reversed()) {
			if (spec.overrides[i].first.test(stack, null, null)) {
				layers = spec.overrides[i].second
				break
			}
		}
		return layers.map {
			ArmourTexture(it.identifier.withPath { path -> "textures/models/armor/${path}_layer_$number${it.suffix}.png" }, it.tint)
		}
	}

	// ---- Placed skulls -----------------------------------------------------------------------

	private val skullCache = ConcurrentHashMap<String, Optional<Identifier>>()

	/** The pack's texture for a placed head with [profile], by the hash in its skin URL. */
	@JvmStatic
	fun placedSkull(profile: ResolvableProfile?): Identifier? {
		if (profile == null || !on(skulls)) return null
		val current = data
		if (current.skullHashes.isEmpty()) return null
		val value = profile.partialProfile().properties()["textures"].firstOrNull()?.value ?: return null
		return skullCache.computeIfAbsent(value) {
			val hash = runCatching {
				val json = JsonParser.parseString(String(Base64.getDecoder().decode(value.trim()))).asJsonObject
				json.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").asString.substringAfterLast('/')
			}.getOrNull()
			Optional.ofNullable(hash?.lowercase(Locale.ROOT)?.takeIf { it in current.skullHashes }?.let {
				Identifier.fromNamespaceAndPath(NAMESPACE, "textures/placedskulls/$it.png")
			})
		}.orElse(null)
	}

	// ---- Debug -------------------------------------------------------------------------------

	fun describe(): List<String> {
		val current = data
		val lines = mutableListOf(
			"Pack Tweaks: ${if (module.enabled) "on" else "off"}",
			"Item models: ${current.available.size} (${current.legacyCount} old-format), global overrides: ${current.globals.size}, head models: ${current.headByModel.size}",
			"Armour: ${current.armour.size} IDs, placed skulls: ${current.skullHashes.size}, screen layouts: ${current.screens.layouts.size}, text replacements: ${current.texts.size}",
			PackBlocks.describe(),
		)
		val stack = Minecraft.getInstance().player?.mainHandItem
		if (stack == null || stack.isEmpty) {
			lines += "Hold an item to see its lookup."
			return lines
		}
		val tried = candidates(SkyblockItem.data(stack))
		if (tried.isEmpty()) lines += "Held item has no SkyBlock ID."
		for (id in tried) {
			val path = mangle(id)
			lines += "  $id -> $NAMESPACE:$path ${if (path in current.available) "(found)" else "(none)"}"
		}
		lines += "  drawn with: ${modelFor(stack, null) ?: "its own model"}"
		return lines
	}
}

/**
 * An old-format model layer's tint: the [override] from `firmament:tint_overrides`, or else what
 * the item's own model tints that layer with.
 */
class PackTint(val layer: Int, val override: Int?) : ItemTintSource {
	override fun calculate(itemStack: ItemStack, level: ClientLevel?, owner: LivingEntity?): Int {
		if (override != null && PackTweaks.on(PackTweaks.tintOverrides)) return override
		val tints = PackTweaks.data.vanillaTints[BuiltInRegistries.ITEM.getKey(itemStack.item)] ?: return -1
		return tints.getOrNull(layer)?.calculate(itemStack, level, owner) ?: -1
	}

	override fun type(): MapCodec<out ItemTintSource> = MAP_CODEC

	companion object {
		val TYPE_ID: Identifier = Identifier.fromNamespaceAndPath(Cryptic.MOD_ID, "pack_tint")

		val MAP_CODEC: MapCodec<PackTint> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Codec.INT.fieldOf("layer").forGetter(PackTint::layer),
				Codec.INT.optionalFieldOf("override").forGetter { Optional.ofNullable(it.override) },
			).apply(i) { layer, override -> PackTint(layer, override.orElse(null)) }
		}
	}
}
