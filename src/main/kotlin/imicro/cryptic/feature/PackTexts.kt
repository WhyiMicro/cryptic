package imicro.cryptic.feature

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import imicro.cryptic.Cryptic
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.server.packs.resources.ResourceManager
import java.util.regex.Pattern

/**
 * The format's text replacements (`overrides/texts`): a whole-text match, then replacements found
 * inside each part of the text. Only what is drawn changes; other mods still read the original.
 */
object PackTexts {
	/** Finds a match anywhere in a string, unlike the anchored string matchers. */
	class Find(val preserve: Boolean, private val pattern: Pattern?, private val literal: String?) {
		/** Start, end and named groups of the first match in [text] from [from], or null. */
		fun find(text: String, from: Int): Triple<Int, Int, Map<String, String>>? {
			if (pattern != null) {
				val m = pattern.matcher(text)
				if (!m.find(from)) return null
				val groups = pattern.namedGroups().keys.associateWith { m.group(it) ?: "" }
				return Triple(m.start(), m.end(), groups)
			}
			val at = text.indexOf(literal!!, from)
			return if (at < 0 || literal.isEmpty()) null else Triple(at, at + literal.length, emptyMap())
		}

		companion object {
			fun parse(json: JsonElement): Find {
				if (json.isJsonPrimitive) return Find(false, null, json.asString)
				val obj = json.asJsonObject
				val preserve = obj.get("color")?.asString == "preserve"
				obj.get("regex")?.asString?.let { return Find(preserve, Pattern.compile(it), null) }
				obj.get("equals")?.asString?.let { return Find(preserve, null, it) }
				throw IllegalArgumentException("string matcher needs regex or equals")
			}
		}
	}

	class StyleFilter(
		val color: String?, val italic: Boolean?, val bold: Boolean?, val underlined: Boolean?,
		val strikethrough: Boolean?, val obfuscated: Boolean?,
	) {
		fun matches(style: Style): Boolean =
			(color == null || style.color?.serialize() == color) &&
				(italic == null || style.isItalic == italic) &&
				(bold == null || style.isBold == bold) &&
				(underlined == null || style.isUnderlined == underlined) &&
				(strikethrough == null || style.isStrikethrough == strikethrough) &&
				(obfuscated == null || style.isObfuscated == obfuscated)
	}

	class Replacement(val find: Find, val style: StyleFilter?, private val replaceJson: JsonElement, private val replace: Component) {
		/** The replacement text, with `${name}` filled from the match's named groups. */
		fun component(groups: Map<String, String>): Component {
			if (groups.isEmpty()) return replace
			var json = replaceJson.toString()
			if ("\${" !in json) return replace
			for ((name, value) in groups) json = json.replace("\${$name}", value.replace("\\", "\\\\").replace("\"", "\\\""))
			return parseComponent(JsonParser.parseString(json)) ?: replace
		}
	}

	class Override(val match: PackPredicates.StringMatcher, val replacements: List<Replacement>)

	fun initialize() {
		ItemTooltipCallback.EVENT.register { _, _, _, lines ->
			if (!PackTweaks.on(PackTweaks.textReplacements) || PackTweaks.data.texts.isEmpty()) return@register
			for (i in lines.indices) lines[i] = apply(lines[i])
		}
	}

	private fun parseComponent(json: JsonElement): Component? =
		ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null)

	fun read(manager: ResourceManager, @Suppress("UNUSED_PARAMETER") readJson: (ResourceManager, net.minecraft.resources.Identifier) -> JsonElement?): List<Override> =
		PackTweaks.readAll(manager, "overrides/texts").mapNotNull { (file, element) ->
			try {
				val json = element.asJsonObject
				Override(
					PackPredicates.StringMatcher.parse(json.get("match")),
					json.getAsJsonArray("replacements").map { r ->
						val obj = r.asJsonObject
						val style = obj.getAsJsonObject("style")?.let { s ->
							StyleFilter(
								s.get("color")?.asString, s.get("italic")?.asBoolean, s.get("bold")?.asBoolean,
								s.get("underlined")?.asBoolean, s.get("strikethrough")?.asBoolean, s.get("obfuscated")?.asBoolean,
							)
						}
						val replaceJson = obj.get("replace")
						Replacement(Find.parse(obj.get("match")), style, replaceJson, parseComponent(replaceJson) ?: throw IllegalArgumentException("bad replace text"))
					},
				)
			} catch (e: Exception) {
				Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", file, e.message)
				null
			}
		}

	/** Texts already rewritten, by identity; many are drawn every frame from the same object. */
	private val cache: Cache<Component, Component> = CacheBuilder.newBuilder().weakKeys().maximumSize(4096).build()

	fun clearCache() = cache.invalidateAll()

	@JvmStatic
	fun apply(text: Component): Component {
		if (!PackTweaks.on(PackTweaks.textReplacements)) return text
		val overrides = PackTweaks.data.texts
		if (overrides.isEmpty()) return text
		cache.getIfPresent(text)?.let { return it }
		var result = text
		for (override in overrides) {
			if (!override.match.matches(PackPredicates.render(result, override.match.preserve))) continue
			result = rewrite(result, override.replacements, Style.EMPTY)
		}
		cache.put(text, result)
		return result
	}

	private fun rewrite(text: Component, replacements: List<Replacement>, parent: Style): Component {
		val style = text.style.applyTo(parent)
		val siblings = text.siblings
		val newSiblings = siblings.map { rewrite(it, replacements, style) }
		val contents = text.contents
		val pieces = if (contents is PlainTextContents) replaceIn(contents.text(), style, replacements) else null
		if (pieces == null && newSiblings.indices.all { newSiblings[it] === siblings[it] }) return text

		val out: MutableComponent = if (pieces != null) {
			Component.literal("").withStyle(text.style).also { c -> pieces.forEach { c.append(it) } }
		} else {
			MutableComponent.create(contents).withStyle(text.style)
		}
		newSiblings.forEach { out.append(it) }
		return out
	}

	/** [raw] split around each replacement found in it, or null if none applies. */
	private fun replaceIn(raw: String, style: Style, replacements: List<Replacement>): List<Component>? {
		val usable = replacements.filter { it.style == null || it.style.matches(style) }
		if (usable.isEmpty() || raw.isEmpty()) return null
		val pieces = mutableListOf<Component>()
		var pos = 0
		while (pos < raw.length) {
			var best: Triple<Int, Int, Map<String, String>>? = null
			var bestReplacement: Replacement? = null
			for (r in usable) {
				val found = findRaw(raw, pos, r.find) ?: continue
				if (best == null || found.first < best.first) {
					best = found
					bestReplacement = r
				}
			}
			if (best == null || bestReplacement == null) break
			if (best.first > pos) pieces += Component.literal(carriedCodes(raw, pos) + raw.substring(pos, best.first))
			pieces += bestReplacement.component(best.third)
			pos = if (best.second > best.first) best.second else best.second + 1
		}
		if (pieces.isEmpty()) return null
		if (pos < raw.length) pieces += Component.literal(carriedCodes(raw, pos) + raw.substring(pos))
		return pieces
	}

	/** A match in raw text that may hold `§` codes, found with them stripped unless kept. */
	private fun findRaw(raw: String, from: Int, find: Find): Triple<Int, Int, Map<String, String>>? {
		if (find.preserve || '§' !in raw) return find.find(raw, from)
		val stripped = StringBuilder()
		val index = ArrayList<Int>()
		var i = 0
		while (i < raw.length) {
			if (raw[i] == '§' && i + 1 < raw.length) {
				i += 2
				continue
			}
			if (i >= from) {
				stripped.append(raw[i])
				index += i
			}
			i++
		}
		val found = find.find(stripped.toString(), 0) ?: return null
		val start = index.getOrElse(found.first) { raw.length }
		val end = if (found.second > found.first) index[found.second - 1] + 1 else start
		return Triple(start, end, found.third)
	}

	/** The `§` codes in force at [at], so text after a replacement keeps its colour. */
	private fun carriedCodes(raw: String, at: Int): String {
		var colour = ""
		val formats = StringBuilder()
		var i = 0
		while (i < at - 1) {
			if (raw[i] == '§') {
				val code = raw[i + 1].lowercaseChar()
				when {
					code in '0'..'9' || code in 'a'..'f' || code == 'r' -> {
						colour = if (code == 'r') "" else "§$code"
						formats.clear()
					}
					code in 'k'..'o' -> formats.append('§').append(code)
				}
				i += 2
			} else {
				i++
			}
		}
		return colour + formats
	}
}
