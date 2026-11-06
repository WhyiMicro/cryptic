package imicro.cryptic.feature

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import imicro.cryptic.Cryptic
import imicro.cryptic.skyblock.SkyblockItem
import net.minecraft.ChatFormatting
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CollectionTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import java.util.Base64
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.WeakHashMap
import java.util.regex.Pattern

/**
 * The item predicates of the `firmskyblock` pack format (`firmament:display_name`, `firmament:lore`
 * and the rest), parsed from a model override's `predicate` object. Written from the format's
 * documentation.
 */
object PackPredicates {
	/** One test on an item. [dynamic] tests read the holder or the moment, so are never cached. */
	abstract class Predicate {
		open val dynamic: Boolean get() = false
		abstract fun test(stack: ItemStack, entity: LivingEntity?, level: ClientLevel?): Boolean
	}

	private class All(val parts: List<Predicate>) : Predicate() {
		override val dynamic = parts.any { it.dynamic }
		override fun test(stack: ItemStack, entity: LivingEntity?, level: ClientLevel?) = parts.all { it.test(stack, entity, level) }
	}

	private class AnyOf(val parts: List<Predicate>) : Predicate() {
		override val dynamic = parts.any { it.dynamic }
		override fun test(stack: ItemStack, entity: LivingEntity?, level: ClientLevel?) = parts.any { it.test(stack, entity, level) }
	}

	private class Not(val part: Predicate) : Predicate() {
		override val dynamic = part.dynamic
		override fun test(stack: ItemStack, entity: LivingEntity?, level: ClientLevel?) = !part.test(stack, entity, level)
	}

	private class Simple(override val dynamic: Boolean = false, val check: (ItemStack, LivingEntity?, ClientLevel?) -> Boolean) : Predicate() {
		override fun test(stack: ItemStack, entity: LivingEntity?, level: ClientLevel?) = check(stack, entity, level)
	}

	private val ALWAYS: Predicate = Simple { _, _, _ -> true }
	private val NEVER: Predicate = Simple { _, _, _ -> false }

	/**
	 * A whole `predicate` object: every entry must hold. Vanilla's old predicates (`pulling`,
	 * `damage` and so on) count only here at the top, as in the format.
	 */
	fun parseRoot(json: JsonElement, where: String): Predicate =
		if (json.isJsonObject) parseObject(json.asJsonObject, where, top = true) else NEVER.also { warn(where, "predicate is not an object") }

	private fun parseObject(json: JsonObject, where: String, top: Boolean): Predicate {
		val parts = json.entrySet().mapNotNull { (key, value) ->
			try {
				parseEntry(key, value, where, top)
			} catch (e: Exception) {
				warn(where, "bad $key: ${e.message}")
				NEVER
			}
		}
		return when (parts.size) {
			0 -> ALWAYS
			1 -> parts[0]
			else -> All(parts)
		}
	}

	/** Null for an entry to skip: the format ignores vanilla predicates nested in `firmament:` ones. */
	private fun parseEntry(key: String, value: JsonElement, where: String, top: Boolean): Predicate? {
		val name = key.removePrefix("firmament:")
		if (name == key) {
			if (!top) return null
			return vanilla(key, value.asFloat) ?: NEVER.also { warn(where, "unsupported predicate $key") }
		}
		return when (name) {
			"display_name" -> text(value) { stack, preserve -> render(stack.hoverName, preserve) }
			"lore" -> {
				val matcher = StringMatcher.parse(value)
				Simple { stack, _, _ ->
					stack.get(DataComponents.LORE)?.lines()?.any { matcher.matches(render(it, matcher.preserve)) } == true
				}
			}
			"item" -> {
				val id = Identifier.parse(value.asString)
				Simple { stack, _, _ -> BuiltInRegistries.ITEM.getKey(stack.item) == id }
			}
			"skull" -> skull(value.asJsonObject)
			"extra_attributes" -> {
				val (path, matcher) = pathAndMatcher(value.asJsonObject)
				Simple { stack, _, _ -> NbtPath.select(SkyblockItem.data(stack), path).any { matcher.matches(it) } }
			}
			"component" -> component(value.asJsonObject)
			"pet" -> pet(value.asJsonObject)
			"any" -> AnyOf(value.asJsonArray.map { parseObject(it.asJsonObject, where, top = false) })
			"all" -> All(value.asJsonArray.map { parseObject(it.asJsonObject, where, top = false) })
			"not" -> Not(parseObject(value.asJsonObject, where, top = false))
			else -> NEVER.also { warn(where, "unknown predicate $key") }
		}
	}

	private fun text(value: JsonElement, read: (ItemStack, Boolean) -> String): Predicate {
		val matcher = StringMatcher.parse(value)
		return Simple { stack, _, _ -> matcher.matches(read(stack, matcher.preserve)) }
	}

	/** Vanilla's pre-1.21.4 model predicates: true when the property is at least [threshold]. */
	private fun vanilla(key: String, threshold: Float): Predicate? {
		val read: (ItemStack, LivingEntity?) -> Float = when (key) {
			"pulling" -> { stack, e -> if (e != null && e.isUsingItem && e.useItem === stack) 1f else 0f }
			"pull" -> { stack, e ->
				if (e != null && e.isUsingItem && e.useItem === stack) (stack.getUseDuration(e) - e.useItemRemainingTicks) / 20f else 0f
			}
			"cast" -> { stack, e ->
				if (e is Player && e.fishing != null && (e.mainHandItem === stack || e.offhandItem === stack)) 1f else 0f
			}
			"damage" -> { stack, _ -> if (stack.maxDamage > 0) stack.damageValue.toFloat() / stack.maxDamage else 0f }
			"damaged" -> { stack, _ -> if (stack.isDamaged) 1f else 0f }
			"cooldown" -> { stack, e -> if (e is Player) e.cooldowns.getCooldownPercent(stack, 0f) else 0f }
			"lefthanded" -> { _, e -> if (e != null && e.mainArm == HumanoidArm.LEFT) 1f else 0f }
			"custom_model_data" -> { stack, _ -> stack.get(DataComponents.CUSTOM_MODEL_DATA)?.floats()?.firstOrNull() ?: 0f }
			else -> return null
		}
		return Simple(dynamic = true) { stack, e, _ -> read(stack, e) >= threshold }
	}

	private fun skull(json: JsonObject): Predicate {
		val profileId = json.get("profileId")?.asString?.let(::uuid)
		val textureProfileId = json.get("textureProfileId")?.asString?.let(::uuid)
		val skinUrl = json.get("skinUrl")?.let(StringMatcher::parse)
		val textureValue = json.get("textureValue")?.let(StringMatcher::parse)
		return Simple { stack, _, _ ->
			val profile = stack.get(DataComponents.PROFILE)
			if (profile == null) {
				false
			} else {
				val partial = profile.partialProfile()
				val value = SkyblockItem.skullTexture(stack)
				val decoded by lazy { value?.let(::decodeTextures) }
				(profileId == null || partial.id == profileId) &&
					(textureValue == null || (value != null && textureValue.matches(value))) &&
					(textureProfileId == null || decoded?.get("profileId")?.asString?.let(::uuid) == textureProfileId) &&
					(skinUrl == null || decoded?.getAsJsonObject("textures")?.getAsJsonObject("SKIN")?.get("url")?.asString?.let(skinUrl::matches) == true)
			}
		}
	}

	private fun decodeTextures(value: String): JsonObject? = runCatching {
		JsonParser.parseString(String(Base64.getDecoder().decode(value.trim()))).asJsonObject
	}.getOrNull()

	private fun uuid(text: String): UUID? = runCatching {
		val plain = text.replace("-", "")
		if (plain.length != 32) return@runCatching UUID.fromString(text)
		UUID(java.lang.Long.parseUnsignedLong(plain.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(plain.substring(16), 16))
	}.getOrNull()

	private fun component(json: JsonObject): Predicate {
		val type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(json.get("component").asString))
			?: throw IllegalArgumentException("no component ${json.get("component").asString}")
		val (path, matcher) = pathAndMatcher(json)
		return Simple { stack, _, level ->
			val tag = encode(type, stack, level)
			tag != null && NbtPath.select(tag, path).any { matcher.matches(it) }
		}
	}

	private fun <T : Any> encode(type: DataComponentType<T>, stack: ItemStack, level: ClientLevel?): Tag? {
		val value = stack.get(type) ?: return null
		val codec = type.codec() ?: return null
		val ops = level?.registryAccess()?.createSerializationContext(NbtOps.INSTANCE) ?: NbtOps.INSTANCE
		return codec.encodeStart(ops, value).result().orElse(null)
	}

	private val PET_LEVEL = Regex("""\[Lvl (\d+)]""")
	private val RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL")

	private fun pet(json: JsonObject): Predicate {
		val id = json.get("id")?.let(StringMatcher::parse)
		val exp = json.get("exp")?.let { NumberMatcher.parse(it) }
		val tier = json.get("tier")?.let { NumberMatcher.parse(it) { name -> RARITIES.indexOf(name.uppercase(Locale.ROOT)).toDouble().takeIf { it >= 0 } } }
		val level = json.get("level")?.let { NumberMatcher.parse(it) }
		val candy = json.get("candyUsed")?.let { NumberMatcher.parse(it) }
		return Simple { stack, _, _ ->
			val info = runCatching { JsonParser.parseString(SkyblockItem.data(stack).getString("petInfo").orElse("")).asJsonObject }.getOrNull()
			if (info == null) {
				false
			} else {
				(id == null || id.matches(info.get("type")?.asString ?: "")) &&
					(exp == null || exp.matches(info.get("exp")?.asDouble ?: 0.0)) &&
					(tier == null || tier.matches(RARITIES.indexOf(info.get("tier")?.asString ?: "").toDouble())) &&
					(candy == null || candy.matches(info.get("candyUsed")?.asDouble ?: 0.0)) &&
					(level == null || PET_LEVEL.find(stack.hoverName.string)?.groupValues?.get(1)?.toDouble()?.let(level::matches) == true)
			}
		}
	}

	/** A `path` and either a `match` object or the matcher's keys inline. */
	private fun pathAndMatcher(json: JsonObject): Pair<List<String>, NbtMatcher> {
		val path = when (val p = json.get("path")) {
			null -> emptyList()
			is JsonArray -> p.map { it.asString }
			else -> p.asString.split('.').filter { it.isNotEmpty() }
		}
		val match = json.get("match")?.asJsonObject ?: json
		return path to NbtMatcher.parse(match)
	}

	/** A text as a string: plain, or with `§` codes for its styles when the matcher keeps colour. */
	fun render(text: Component, preserve: Boolean): String {
		if (!preserve) return strip(text.string)
		val out = StringBuilder()
		text.visit(FormattedText.StyledContentConsumer<Unit> { style, part ->
			style.color?.let { colour -> COLOUR_CODES[colour.serialize()]?.let(out::append) }
			if (style.isObfuscated) out.append("§k")
			if (style.isBold) out.append("§l")
			if (style.isStrikethrough) out.append("§m")
			if (style.isUnderlined) out.append("§n")
			if (style.isItalic) out.append("§o")
			out.append(part)
			Optional.empty()
		}, net.minecraft.network.chat.Style.EMPTY)
		return out.toString()
	}

	/** Named text colours to their `§` code (ChatFormatting's toString is the code). */
	private val COLOUR_CODES: Map<String, String> = ChatFormatting.entries.associate { it.name.lowercase(Locale.ROOT) to it.toString() }

	private val CODES = Regex("§.")
	fun strip(text: String): String = if ('§' in text) text.replace(CODES, "") else text

	/** A string test: a plain string must equal, or `{"regex"|"equals": ..., "color": "strip"|"preserve"}`. */
	class StringMatcher(val preserve: Boolean, private val check: (String) -> Boolean) {
		fun matches(text: String): Boolean = check(if (preserve) text else strip(text))

		companion object {
			fun parse(json: JsonElement): StringMatcher {
				if (json.isJsonPrimitive) {
					val wanted = json.asString
					return StringMatcher(false) { it == wanted }
				}
				val obj = json.asJsonObject
				val preserve = obj.get("color")?.asString == "preserve"
				obj.get("regex")?.asString?.let { regex ->
					val pattern = Pattern.compile(regex)
					return StringMatcher(preserve) { pattern.matcher(it).matches() }
				}
				obj.get("equals")?.asString?.let { wanted -> return StringMatcher(preserve) { it == wanted } }
				throw IllegalArgumentException("string matcher needs regex or equals")
			}
		}
	}

	/** A number test: `5`, `"<50"`, `">=10"`, or an interval such as `"(50,100]"` or `"[50,)"`. */
	class NumberMatcher(private val check: (Double) -> Boolean) {
		fun matches(value: Double): Boolean = check(value)

		companion object {
			fun parse(json: JsonElement, named: (String) -> Double? = { null }): NumberMatcher {
				val prim = json.asJsonPrimitive
				if (prim.isNumber) {
					val wanted = prim.asDouble
					return NumberMatcher { it == wanted }
				}
				val text = prim.asString.trim()
				fun num(s: String): Double = s.trim().let { it.toDoubleOrNull() ?: named(it) ?: throw IllegalArgumentException("not a number: $it") }
				for (op in listOf("<=", ">=", "<", ">")) {
					if (!text.startsWith(op)) continue
					val bound = num(text.substring(op.length))
					return when (op) {
						"<=" -> NumberMatcher { it <= bound }
						">=" -> NumberMatcher { it >= bound }
						"<" -> NumberMatcher { it < bound }
						else -> NumberMatcher { it > bound }
					}
				}
				if (text.length >= 2 && text[0] in "([" && text.last() in ")]" && ',' in text) {
					val (lowText, highText) = text.substring(1, text.length - 1).split(',', limit = 2)
					val low = lowText.takeIf { it.isNotBlank() }?.let(::num)
					val high = highText.takeIf { it.isNotBlank() }?.let(::num)
					val lowIn = text[0] == '['
					val highIn = text.last() == ']'
					return NumberMatcher {
						(low == null || if (lowIn) it >= low else it > low) && (high == null || if (highIn) it <= high else it < high)
					}
				}
				val wanted = num(text)
				return NumberMatcher { it == wanted }
			}
		}
	}

	/** One NBT value: `{"string": <string matcher>}`, or `{"int": 5}` / `{"long": {"min": 0, "max": 10}}`. */
	class NbtMatcher(private val check: (Tag) -> Boolean) {
		fun matches(tag: Tag): Boolean = check(tag)

		companion object {
			private val NUMERIC = listOf("int", "float", "double", "long", "short", "byte")

			fun parse(json: JsonObject): NbtMatcher {
				json.get("string")?.let { value ->
					val matcher = StringMatcher.parse(value)
					return NbtMatcher { it is StringTag && matcher.matches(it.value) }
				}
				for (type in NUMERIC) {
					val value = json.get(type) ?: continue
					val range: (Double) -> Boolean = if (value.isJsonObject) {
						val obj = value.asJsonObject
						val min = obj.get("min")?.asDouble
						val max = obj.get("max")?.asDouble
						val minExclusive = obj.get("minExclusive")?.asBoolean ?: false
						val maxExclusive = obj.get("maxExclusive")?.asBoolean ?: true
						{ v -> (min == null || if (minExclusive) v > min else v >= min) && (max == null || if (maxExclusive) v < max else v <= max) }
					} else {
						val wanted = value.asDouble
						{ v -> v == wanted }
					}
					// JSON decoded by *json turns bytes into ints and so on, so any number type is compared.
					return NbtMatcher { it is NumericTag && range(it.box().toDouble()) }
				}
				throw IllegalArgumentException("nbt matcher needs string or a number type")
			}
		}
	}

	/** The format's NBT paths: dot-separated keys or indices, `*` for every child, `*base64` and `*json` to decode a string. */
	object NbtPath {
		fun select(root: Tag, path: List<String>): List<Tag> {
			var current = listOf(root)
			for (part in path) {
				current = current.flatMap { step(it, part) }
				if (current.isEmpty()) break
			}
			return current
		}

		private fun step(tag: Tag, part: String): List<Tag> = when (part) {
			"*" -> when (tag) {
				is CompoundTag -> tag.keySet().mapNotNull { tag.get(it) }
				is CollectionTag -> (0 until tag.size()).map { tag.get(it) }
				else -> emptyList()
			}
			"*base64" -> (tag as? StringTag)?.let { s ->
				runCatching { StringTag.valueOf(String(Base64.getDecoder().decode(s.value.trim()))) }.getOrNull()
			}.let(::listOfNotNull)
			"*json" -> (tag as? StringTag)?.let { s ->
				runCatching { JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, JsonParser.parseString(s.value)) }.getOrNull()
			}.let(::listOfNotNull)
			else -> when (tag) {
				is CompoundTag -> listOfNotNull(tag.get(part))
				is CollectionTag -> part.toIntOrNull()?.takeIf { it in 0 until tag.size() }?.let { listOf(tag.get(it)) } ?: emptyList()
				else -> emptyList()
			}
		}
	}

	private val warned = HashSet<String>()

	private fun warn(where: String, message: String) {
		synchronized(warned) {
			if (!warned.add("$where: $message")) return
		}
		Cryptic.LOGGER.warn("Pack Tweaks: {}: {}", where, message)
	}

	/** Per-stack results of a static predicate, so lore regexes don't run every frame. */
	class Cached(private val predicate: Predicate) {
		private val results = WeakHashMap<ItemStack, Boolean>()

		fun test(stack: ItemStack, entity: LivingEntity?, level: ClientLevel?): Boolean {
			if (predicate.dynamic) return predicate.test(stack, entity, level)
			synchronized(results) { results[stack] }?.let { return it }
			val result = predicate.test(stack, entity, level)
			synchronized(results) { results[stack] = result }
			return result
		}
	}
}
