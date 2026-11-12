package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.world.entity.Entity
import kotlin.math.roundToInt

/**
 * Restyles name tags over heads, and gives you a name only you see. Your real name is swapped for
 * it wherever the game shows it to you (over your head, tab list, chat). The style settings apply
 * to every tag, including [ClassNames]' labels.
 */
object CustomNametags {
	/** The `&` codes people type, as on signs. */
	private val COLOR_CODE = Regex("&([0-9a-fk-orA-FK-OR])")

	/** Vanilla's own tag background, so the slider starts there. */
	private const val DEFAULT_BACKGROUND_OPACITY = 25.0

	private val yourNameSection = SectionModuleSetting("your_name_section", "Your name")

	@JvmField
	val customName = ToggleModuleSetting(
		id = "custom_name",
		label = "Custom name",
		defaultValue = false,
		description = "Wears a name of your own wherever the game shows you yours.",
	)

	@JvmField
	val customTag = TextModuleSetting(
		id = "custom_tag",
		label = "Name",
		maxLength = 64,
		hint = "&bYour name",
		visibleIf = { customName.value },
	)

	private val everyTagSection = SectionModuleSetting("every_tag_section", "Every tag")

	@JvmField
	val showOwnNametag = ToggleModuleSetting(
		id = "show_own_nametag",
		label = "Show own nametag",
		defaultValue = false,
		description = "Draws your own tag above you in third person, rank and level included.",
	)

	@JvmField
	val hideHealth = ToggleModuleSetting(
		id = "hide_health",
		label = "Hide health tag",
		defaultValue = false,
		description = "Hides the health line Hypixel puts under players' names.",
	)

	@JvmField
	val dropShadow = ToggleModuleSetting(
		id = "drop_shadow",
		label = "Drop shadow",
		defaultValue = false,
	)

	@JvmField
	val backgroundOpacity = SliderModuleSetting(
		id = "background_opacity",
		label = "Background",
		defaultValue = DEFAULT_BACKGROUND_OPACITY,
		min = 0.0,
		max = 100.0,
		step = 1.0,
		description = "How solid the plate behind a name tag is.",
	)

	@JvmField
	val module = Module(
		id = "custom_nametags",
		name = "Custom Nametags",
		description = "Restyles name tags",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(
			yourNameSection,
			customName,
			customTag,
			everyTagSection,
			showOwnNametag,
			hideHealth,
			dropShadow,
			backgroundOpacity,
		),
	)

	fun initialize() {}

	private fun customName(): Component? {
		if (!module.enabled || !customName.value) return null
		val text = customTag.value.trim()
		if (text.isEmpty()) return null
		// A reset ends the name, so its colours don't run into what follows.
		return Component.literal(colored(text) + "§r")
	}

	/**
	 * Whether the game should name the player to themselves (vanilla always says no). Yes hands it
	 * back to the game, so the tag is the server's display name with rank and level.
	 */
	@JvmStatic
	fun showsOwnNameTag(entity: Entity): Boolean {
		if (!module.enabled || !showOwnNametag.value) return false
		return entity === Minecraft.getInstance().player
	}

	/**
	 * Your tag with the custom name in place of your own, keeping Hypixel's rank and level around
	 * it. Null to keep the game's.
	 */
	@JvmStatic
	fun tagFor(entity: Entity, original: Component?): Component? {
		if (original == null) return null
		val player = Minecraft.getInstance().player ?: return null
		if (entity !== player) return null
		val name = customName() ?: return null
		return replaceName(original, player.gameProfile.name, name)
	}

	@JvmStatic
	fun hidesHealth(entity: Entity): Boolean =
		module.enabled && hideHealth.value && entity is net.minecraft.world.entity.player.Player

	/**
	 * Any row naming you, not just your own entry: SkyBlock's tab rows are made-up profiles that
	 * only carry your name as text.
	 */
	@JvmStatic
	fun tabName(original: Component): Component? = decorateOrNull(original)

	/** Every chat line, whichever mod added it. */
	@JvmStatic
	fun decorate(message: Component): Component = decorateOrNull(message) ?: message

	private fun decorateOrNull(message: Component): Component? {
		val player = Minecraft.getInstance().player ?: return null
		val name = customName() ?: return null
		val own = player.gameProfile.name
		if (!message.string.contains(own)) return null
		return replaceName(message, own, name)
	}

	@JvmStatic
	fun dropShadow(original: Boolean): Boolean = if (module.enabled) dropShadow.value else original

	/**
	 * The plate behind a name tag. Vanilla packs its opacity into the alpha byte, so only that byte
	 * is replaced. A see-through tag's second, plateless pass stays plateless, or the two stack.
	 */
	@JvmStatic
	fun backgroundColor(original: Int): Int {
		if (!module.enabled || original ushr 24 == 0) return original
		return (backgroundAlpha() shl 24) or (original and 0xFFFFFF)
	}

	@JvmStatic
	fun backgroundArgb(): Int = if (module.enabled) backgroundAlpha() shl 24 else 0

	private fun backgroundAlpha(): Int =
		(backgroundOpacity.value / 100.0 * 255.0).roundToInt().coerceIn(0, 255)

	/**
	 * Rebuilds [message] with every mention of [name] replaced, walking the tree to keep colours
	 * and click handlers.
	 */
	private fun replaceName(message: Component, name: String, replacement: Component): Component {
		val contents = message.contents
		val text = (contents as? PlainTextContents)?.text()

		val rebuilt: MutableComponent = if (text != null && text.contains(name)) {
			val parts = Component.empty()
			var index = 0
			while (true) {
				val found = text.indexOf(name, index)
				if (found < 0) {
					if (index < text.length) parts.append(Component.literal(text.substring(index)))
					break
				}
				if (found > index) parts.append(Component.literal(text.substring(index, found)))
				parts.append(replacement)
				index = found + name.length
				// Hypixel colours with § codes inside one string; the rest was relying on those.
				val carried = legacyCodesBefore(text, found)
				if (carried.isNotEmpty() && index < text.length) {
					val next = text.indexOf(name, index).let { if (it < 0) text.length else it }
					parts.append(Component.literal(carried + text.substring(index, next)))
					index = next
				}
			}
			parts
		} else {
			MutableComponent.create(contents)
		}

		rebuilt.setStyle(message.style)
		message.siblings.forEach { rebuilt.append(replaceName(it, name, replacement)) }
		return rebuilt
	}

	/** The § colour and formats in effect at [end]: a colour or reset clears the formats before it. */
	private fun legacyCodesBefore(text: String, end: Int): String {
		val codes = StringBuilder()
		var i = text.indexOf('§')
		while (i in 0 until end - 1) {
			val code = text[i + 1].lowercaseChar()
			if (code in '0'..'9' || code in 'a'..'f' || code == 'r') codes.setLength(0)
			if (code != 'r') codes.append('§').append(code)
			i = text.indexOf('§', i + 2)
		}
		return codes.toString()
	}

	private fun colored(text: String): String = COLOR_CODE.replace(text) { "§${it.groupValues[1]}" }
}
