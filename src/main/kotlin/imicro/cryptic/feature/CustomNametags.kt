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
 * Restyles the name tags floating over heads, and gives the player a name of
 * their own to wear.
 *
 * The name is Cryptic's, not the server's: nobody else sees it. Wherever the
 * game would show the player's real name to the player themselves — over their
 * head, in the tab list, in a line of chat — it is swapped for this one, so the
 * name does not change depending on where it is read. The look settings apply
 * to every tag on screen, including the class labels [ClassNames] draws, so the
 * two cannot end up styled differently.
 */
object CustomNametags {
	/** Hypixel writes colors the way signs do, with a code after a section sign. */
	private val COLOR_CODE = Regex("&([0-9a-fk-orA-FK-OR])")

	/** Vanilla's own name tag background, so the slider starts where the game is. */
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
		description = "Restyles name tags and gives you a name only you can see",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
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

		// Hypixel sends its chat as system messages, which is the half of the
		// chat API a client is allowed to rewrite.
		ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ -> decorate(message) }
	}

	/** The name to wear, or null when the module is off or the field is empty. */
	private fun customName(): Component? {
		if (!module.enabled || !customName.value) return null
		val text = customTag.value.trim()
		if (text.isEmpty()) return null
		// A reset closes the name off, so its colors cannot run into whatever
		// the game prints after it.
		return Component.literal(colored(text) + "§r")
	}

	/**
	 * Whether the game should name the player to themselves after all.
	 *
	 * Asked by the render mixin in place of vanilla's answer, which is always
	 * no. Saying yes hands the job back to the game, so the tag drawn is the
	 * display name the server dressed the player in — rank, colours, SkyBlock
	 * level and all — rather than a bare username reassembled here.
	 */
	@JvmStatic
	fun showsOwnNameTag(entity: Entity): Boolean {
		if (!module.enabled || !showOwnNametag.value) return false
		return entity === Minecraft.getInstance().player
	}

	/**
	 * The name to draw over the player instead of the server's, or null to keep
	 * whatever the game worked out.
	 */
	@JvmStatic
	fun tagFor(entity: Entity): Component? {
		if (!showsOwnNameTag(entity)) return null
		return customName()
	}

	/** The tab list entry to show in place of [original], or null to leave it. */
	@JvmStatic
	fun tabName(uuid: java.util.UUID, original: Component): Component? {
		val player = Minecraft.getInstance().player ?: return null
		if (player.uuid != uuid) return null
		val name = customName() ?: return null
		// Hypixel decorates the row with rank and level, and only the name
		// inside it is the player's to change.
		return replaceName(original, player.gameProfile.name, name)
	}

	/** A chat line with the player's own name swapped for the one they chose. */
	@JvmStatic
	fun decorate(message: Component): Component {
		val player = Minecraft.getInstance().player ?: return message
		val name = customName() ?: return message
		return replaceName(message, player.gameProfile.name, name)
	}

	/** Whether name tags are drawn with a shadow, asked once per tag drawn. */
	@JvmStatic
	fun dropShadow(original: Boolean): Boolean = if (module.enabled) dropShadow.value else original

	/**
	 * The plate behind a name tag. Vanilla packs the opacity it took from the
	 * video settings into the alpha byte and leaves the rest black, so only
	 * that byte is replaced.
	 */
	@JvmStatic
	fun backgroundColor(original: Int): Int {
		if (!module.enabled) return original
		return (backgroundAlpha() shl 24) or (original and 0xFFFFFF)
	}

	/** The same plate colour, for labels Cryptic draws itself. */
	@JvmStatic
	fun backgroundArgb(): Int = if (module.enabled) backgroundAlpha() shl 24 else 0

	private fun backgroundAlpha(): Int =
		(backgroundOpacity.value / 100.0 * 255.0).roundToInt().coerceIn(0, 255)

	/**
	 * Rebuilds [message] with every mention of [name] replaced.
	 *
	 * The tree is walked rather than flattened, because a chat line carries its
	 * colors and its click handlers in the styles of its parts, and flattening
	 * it to a string would throw all of that away.
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

	/** Turns the ampersand codes people type into the ones the font reads. */
	private fun colored(text: String): String = COLOR_CODE.replace(text) { "§${it.groupValues[1]}" }
}
