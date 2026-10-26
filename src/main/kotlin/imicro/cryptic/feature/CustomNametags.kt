package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
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
			dropShadow,
			backgroundOpacity,
		),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// Hypixel's chat arrives as system messages, which a client may rewrite.
		ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ -> decorate(message) }
	}

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

	/** The name over the player instead of the server's, or null to keep the game's. */
	@JvmStatic
	fun tagFor(entity: Entity): Component? {
		if (!showsOwnNameTag(entity)) return null
		return customName()
	}

	@JvmStatic
	fun tabName(uuid: java.util.UUID, original: Component): Component? {
		val player = Minecraft.getInstance().player ?: return null
		if (player.uuid != uuid) return null
		val name = customName() ?: return null
		// Hypixel decorates the row with rank and level; only the name inside changes.
		return replaceName(original, player.gameProfile.name, name)
	}

	@JvmStatic
	fun decorate(message: Component): Component {
		val player = Minecraft.getInstance().player ?: return message
		val name = customName() ?: return message
		return replaceName(message, player.gameProfile.name, name)
	}

	@JvmStatic
	fun dropShadow(original: Boolean): Boolean = if (module.enabled) dropShadow.value else original

	/**
	 * The plate behind a name tag. Vanilla packs its opacity into the alpha byte, so only that byte
	 * is replaced.
	 */
	@JvmStatic
	fun backgroundColor(original: Int): Int {
		if (!module.enabled) return original
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
			}
			parts
		} else {
			MutableComponent.create(contents)
		}

		rebuilt.setStyle(message.style)
		message.siblings.forEach { rebuilt.append(replaceName(it, name, replacement)) }
		return rebuilt
	}

	private fun colored(text: String): String = COLOR_CODE.replace(text) { "§${it.groupValues[1]}" }
}
