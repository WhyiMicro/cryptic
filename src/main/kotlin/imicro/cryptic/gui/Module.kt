package imicro.cryptic.gui

import com.mojang.blaze3d.platform.InputConstants
import java.util.Locale
import kotlin.math.roundToInt

enum class ModuleCategory(val title: String) {
	GENERAL("General"),
	DUNGEON("Dungeon"),
	// Named for the fight, so other floors' bosses can sit beside Floor 7's.
	FLOOR_7("Boss"),
	VISUAL("Render"),
	MISC("Settings"),
}

/**
 * How far past Hypixel's rules a feature goes. Client Restrictions picks the highest allowed; a
 * feature above it is hidden and reads as off, while its own on/off and settings are kept.
 */
enum class Restriction { LEGIT, SEMI_LEGIT, CHEAT }

object Restrictions {
	/** The highest level allowed, as Client Restrictions sets it. Everything until it says. */
	@JvmStatic
	var allowedLevel: () -> Int = { Restriction.CHEAT.ordinal }

	@JvmStatic
	fun allows(restriction: Restriction): Boolean =
		restriction == Restriction.LEGIT || restriction.ordinal <= allowedLevel()
}

/** Formats a number only when it changes; labels are redrawn every frame. */
internal class NumberLabel(private val format: (Double) -> String) {
	private var source = Double.NaN
	private var text: String? = null

	fun of(value: Double): String {
		val cached = text
		if (cached != null && value == source) return cached
		source = value
		return format(value).also { text = it }
	}
}

/** A module: its id, card, toggle and typed settings, shared by the menu and the config. */
class Module(
	val id: String,
	val name: String,
	val description: String,
	val category: ModuleCategory,
	enabled: Boolean = false,
	val supportsToggle: Boolean = true,
	val supportsKeybind: Boolean = false,
	val keybind: KeybindSetting = KeybindSetting(),
	settings: List<ModuleSetting> = emptyList(),
	/**
	 * What a left click on the card does, for a module that opens something rather than toggling.
	 * Right click still edits it.
	 */
	val cardAction: (() -> Unit)? = null,
	val restriction: Restriction = Restriction.LEGIT,
) {
	/** The switch as set, kept while Client Restrictions hides the module. Saved as is. */
	var storedEnabled: Boolean = enabled

	/** Whether the module runs: switched on, and allowed by Client Restrictions. */
	var enabled: Boolean
		get() = storedEnabled && Restrictions.allows(restriction)
		set(value) { storedEnabled = value }

	val allowed: Boolean get() = Restrictions.allows(restriction)

	/**
	 * Every setting, including those in "⋯" panels, each right after the row that holds it.
	 * Profiles, resets and the menu all use this list; the menu skips settings with a
	 * [ModuleSetting.parent] when laying out the card.
	 */
	val settings: List<ModuleSetting> = settings.flatMap { listOf(it) + it.more }.distinct()

	val sortKey: String = name.lowercase(Locale.ROOT)

}

/** Anything the menu can show as a dropdown, so one piece of code draws every popup. */
interface DropdownSource {
	val options: List<String>
	var selectedIndex: Int
	val selected: String

	/** Whether several options can be ticked; keeps the list open while they are. */
	val multiple: Boolean get() = false

	fun isChecked(index: Int): Boolean = index == selectedIndex

	/** The options the list offers, by index; some can be held back by Client Restrictions. */
	val shownOptions: List<Int> get() = options.indices.toList()

	fun pick(index: Int) { selectedIndex = index }
}

/**
 * A key a module can be bound to. Not part of profiles: switching profile shouldn't move keys under
 * someone's fingers. Real Minecraft key mappings are saved in Minecraft's options and can be
 * rebound in its controls screen.
 */
class KeybindSetting(
	initialKeyName: String = "None",
	private val currentKeyName: (() -> String)? = null,
	private val onKeyChanged: ((InputConstants.Key?) -> Unit)? = null,
) {
	private var storedKeyName = initialKeyName

	var keyName: String
		get() = currentKeyName?.invoke() ?: storedKeyName
		set(value) { storedKeyName = value }

	fun setKey(key: InputConstants.Key?) {
		if (onKeyChanged != null) {
			onKeyChanged.invoke(key)
		} else {
			storedKeyName = key?.name
				?.removePrefix("key.keyboard.")
				?.removePrefix("key.mouse.")
				?.uppercase()
				?: "None"
		}
	}
}

/** A typed, profile-backed module setting. */
sealed class ModuleSetting(
	val id: String,
	val label: String,
	val visibleIf: () -> Boolean = { true },
) {
	open fun isVisible() = visibleIf()

	/** Whether this setting gets its own row; a colour drawn beside its toggle doesn't. */
	open val hasOwnRow: Boolean get() = true

	/** Settings opened in a floating panel from the "⋯" beside this row. Set with [withMore]. */
	var more: List<ModuleSetting> = emptyList()
		private set

	var parent: ModuleSetting? = null
		private set

	val onCard: Boolean get() = hasOwnRow && parent == null

	fun hasVisibleMore(): Boolean {
		val children = more
		for (index in children.indices) {
			if (children[index].hasOwnRow && children[index].isVisible()) return true
		}
		return false
	}

	internal fun adopt(children: List<ModuleSetting>) {
		more = children
		children.forEach { it.parent = this }
	}
}

/**
 * Moves [children] into a "⋯" panel beside this row, for fine-tuning set once and forgotten.
 * Returns the row, so it reads in place.
 */
fun <T : ModuleSetting> T.withMore(vararg children: ModuleSetting): T {
	adopt(children.toList())
	return this
}

class ToggleModuleSetting(
	id: String,
	label: String,
	val defaultValue: Boolean = false,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
	val restriction: Restriction = Restriction.LEGIT,
): ModuleSetting(id, label, visibleIf) {
	/** The switch as set, kept while Client Restrictions hides it. Saved as is. */
	var storedValue: Boolean = defaultValue

	/** On, and allowed by Client Restrictions. */
	var value: Boolean
		get() = storedValue && Restrictions.allows(restriction)
		set(value) { storedValue = value }

	override fun isVisible() = Restrictions.allows(restriction) && visibleIf()

	fun reset() { value = defaultValue }

	var inlineColor: ColorModuleSetting? = null
		internal set
}

class SliderModuleSetting(
	id: String,
	label: String,
	val defaultValue: Double,
	val min: Double,
	val max: Double,
	val step: Double,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
	/** Shown after the number ("ms", "%", "°"), never typed. */
	val unit: String = "",
): ModuleSetting(id, label, visibleIf) {
	private fun format(raw: Double): String =
		if (step >= 1.0) {
			raw.roundToInt().toString()
		} else {
			String.format(Locale.ROOT, "%.2f", raw).trimEnd('0').trimEnd('.')
		}

	private val valueLabel = NumberLabel(::format)
	private val boxLabel = NumberLabel { raw -> withUnit(format(raw)) }

	private fun withUnit(number: String): String = when {
		unit.isEmpty() -> number
		// One-letter units hug the number ("5s", "90%"); words don't ("100 ms").
		unit.length == 1 -> number + unit
		else -> "$number $unit"
	}

	var value: Double = defaultValue

	val displayValue: String get() = valueLabel.of(value)

	val boxValue: String get() = boxLabel.of(value)

	fun reset() { value = defaultValue }
}

/**
 * Two numbers bounding each other, drawn as one slider with two handles. [random] draws from it, so
 * delays built on it vary.
 */
class RangeModuleSetting(
	id: String,
	label: String,
	val defaultLower: Double,
	val defaultUpper: Double,
	val min: Double,
	val max: Double,
	val step: Double,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf) {
	private val format: (Double) -> String = { raw ->
		if (step >= 1.0) {
			raw.roundToInt().toString()
		} else {
			String.format(Locale.ROOT, "%.2f", raw).trimEnd('0').trimEnd('.')
		}
	}

	private val lowerLabel = NumberLabel(format)
	private val upperLabel = NumberLabel(format)

	var lower: Double = defaultLower
	var upper: Double = defaultUpper

	val displayLower: String get() = lowerLabel.of(lower)
	val displayUpper: String get() = upperLabel.of(upper)

	fun random(): Double =
		if (upper <= lower) lower else lower + Math.random() * (upper - lower)

	fun reset() {
		lower = defaultLower
		upper = defaultUpper
	}
}

class ColorModuleSetting(
	id: String,
	label: String,
	val defaultRgb: Int,
	val supportsAlpha: Boolean = false,
	val defaultAlpha: Int = 0xFF,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
	/**
	 * The toggle this colour belongs to, drawn as a swatch beside it instead of its own row. Only
	 * for a toggle with exactly one colour.
	 */
	val inlineWith: ToggleModuleSetting? = null,
): ModuleSetting(id, label, visibleIf) {
	init {
		inlineWith?.inlineColor = this
	}

	override val hasOwnRow: Boolean get() = inlineWith == null

	private val defaultArgb = ((defaultAlpha and 0xFF) shl 24) or (defaultRgb and 0xFFFFFF)

	var argb: Int = defaultArgb

	var rgb: Int
		get() = argb and 0xFFFFFF
		set(value) { argb = (argb and 0xFF000000.toInt()) or (value and 0xFFFFFF) }

	var alpha: Int
		get() = (argb ushr 24) and 0xFF
		set(value) { argb = ((value.coerceIn(0, 255)) shl 24) or rgb }

	val hexDigits: String
		get() = if (supportsAlpha) "%08X".format(argb.toLong() and 0xFFFFFFFFL) else "%06X".format(rgb)

	val hex: String get() = "#$hexDigits"


	fun setHex(value: String): Boolean {
		val digits = value.trim().removePrefix("#")
		val expectedLength = if (supportsAlpha) 8 else 6
		if (digits.length != expectedLength) return false
		val parsed = digits.toLongOrNull(16) ?: return false
		if (supportsAlpha) argb = parsed.toInt() else rgb = parsed.toInt()
		return true
	}

	fun reset() { argb = defaultArgb }
}

class TextModuleSetting(
	id: String,
	label: String,
	val defaultValue: String = "",
	val maxLength: Int = 128,
	val hint: String = "",
	val description: String = "",
	/** Completions for what has been typed, listed under the field. */
	val suggestions: ((String) -> List<String>)? = null,
	/** A button drawn right of the field, which Enter in the field also presses. Not saved. */
	val button: ButtonModuleSetting? = null,
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf) {
	var value: String = defaultValue
		set(text) { field = text.take(maxLength) }

	fun reset() { value = defaultValue }
}

/**
 * A key a module reacts to while one of its own screens is open, never a global hotkey. A module
 * can carry several. Keyboard keys only, since those screens already take mouse clicks.
 */
class KeybindModuleSetting(
	id: String,
	label: String,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
	/** The starting key: a GLFW key, [mouse] of a button, or [UNBOUND]. */
	val defaultKey: Int = UNBOUND,
): ModuleSetting(id, label, visibleIf) {
	/**
	 * A GLFW key code, a mouse button as [mouse] makes it, or [UNBOUND]. Stable across launches, so
	 * it's what is saved.
	 */
	var keyCode: Int = defaultKey

	val bound: Boolean get() = keyCode != UNBOUND

	val isMouse: Boolean get() = keyCode >= MOUSE_BASE

	/** The key as a badge names it: "R", "LEFT.SHIFT", "MOUSE.5", "None". */
	val keyName: String
		get() = if (!bound) "None" else nameOf(keyCode)

	/** A key press by GLFW code; never true of a mouse bind. */
	fun matches(key: Int): Boolean = bound && !isMouse && key == keyCode

	fun matchesMouse(button: Int): Boolean = isMouse && keyCode - MOUSE_BASE == button

	fun isDown(window: com.mojang.blaze3d.platform.Window): Boolean = when {
		!bound -> false
		isMouse -> org.lwjgl.glfw.GLFW.glfwGetMouseButton(window.handle(), keyCode - MOUSE_BASE) == org.lwjgl.glfw.GLFW.GLFW_PRESS
		else -> InputConstants.isKeyDown(window, keyCode)
	}

	fun reset() { keyCode = defaultKey }

	companion object {
		const val UNBOUND = -1

		/**
		 * Mouse buttons start far above every GLFW key code (the highest is 348), so one number
		 * holds either.
		 */
		const val MOUSE_BASE = 10_000

		/** The bind for GLFW mouse [button]: 3 is back, 4 forward. */
		fun mouse(button: Int): Int = MOUSE_BASE + button

		fun nameOf(code: Int): String = if (code >= MOUSE_BASE) {
			InputConstants.Type.MOUSE.getOrCreate(code - MOUSE_BASE).name.removePrefix("key.").uppercase()
		} else {
			InputConstants.Type.KEYSYM.getOrCreate(code).name.removePrefix("key.keyboard.").uppercase()
		}
	}
}

class DropdownModuleSetting(
	id: String,
	label: String,
	options: List<String>,
	val defaultIndex: Int = 0,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
	/** Options that change (named profiles); [options] is then only the fallback. Never empty. */
	private val liveOptions: (() -> List<String>)? = null,
	/** Options above the allowed level are left out of the list and read as the default. */
	private val optionRestrictions: Map<String, Restriction> = emptyMap(),
	/** Old option labels to their new ones, so profiles saved before a rename still load. */
	private val renamed: Map<String, String> = emptyMap(),
): ModuleSetting(id, label, visibleIf), DropdownSource {
	private val fixedOptions = options

	override val options: List<String> get() = liveOptions?.invoke() ?: fixedOptions

	/** The choice as set, kept while Client Restrictions holds it back. Saved as is. */
	var storedIndex: Int = defaultIndex

	private fun allowed(index: Int): Boolean =
		optionRestrictions[options.getOrNull(index)]?.let(Restrictions::allows) ?: true

	override var selectedIndex: Int
		get() = when {
			allowed(storedIndex) -> storedIndex
			allowed(defaultIndex) -> defaultIndex
			else -> options.indices.firstOrNull(::allowed) ?: 0
		}
		set(value) { storedIndex = value }

	override val shownOptions: List<Int>
		get() = if (optionRestrictions.isEmpty()) options.indices.toList() else options.indices.filter(::allowed)

	override val selected: String get() = options.let { it.getOrNull(selectedIndex) ?: it.first() }

	val storedSelected: String get() = options.let { it.getOrNull(storedIndex) ?: it.first() }

	/** Profiles store the label, so reworded options can't silently reorder. */
	fun select(option: String) {
		options.indexOf(renamed[option] ?: option).takeIf { it >= 0 }?.let { storedIndex = it }
	}

	fun reset() { storedIndex = defaultIndex }
}

/**
 * A list of options, any number ticked. The box names everything ticked ("Head, Chest, …"); the
 * list stays open while ticking. Saved as labels, so adding or reordering options keeps choices.
 */
class MultiSelectModuleSetting(
	id: String,
	label: String,
	override val options: List<String>,
	defaultSelected: Set<String> = options.toSet(),
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf), DropdownSource {
	private val defaultIndices: Set<Int> = options.indices.filter { options[it] in defaultSelected }.toSet()

	var ticked: Set<Int> = defaultIndices
		private set(value) {
			field = value
			summary = null
		}

	private var summary: String? = null

	fun isSelected(option: String): Boolean = options.indexOf(option).let { it >= 0 && it in ticked }

	fun isSelected(index: Int): Boolean = index in ticked

	fun toggle(index: Int) {
		if (index !in options.indices) return
		ticked = if (index in ticked) ticked - index else ticked + index
	}

	override var selectedIndex: Int
		get() = -1
		set(_) {}

	/** The ticked options in list order, built once per change. */
	override val selected: String get() = summary ?: when {
		ticked.isEmpty() -> "None"
		ticked.size == options.size -> "All"
		else -> options.filterIndexed { index, _ -> index in ticked }.joinToString(", ")
	}.also { summary = it }

	override val multiple: Boolean get() = true

	override fun isChecked(index: Int): Boolean = index in ticked

	override fun pick(index: Int) = toggle(index)

	fun encode(): String = options.filterIndexed { index, _ -> index in ticked }.joinToString(SEPARATOR)

	fun decode(text: String) {
		val labels = if (text.isEmpty()) emptySet() else text.split(SEPARATOR).toSet()
		ticked = options.indices.filter { options[it] in labels }.toSet()
	}

	fun reset() { ticked = defaultIndices }

	private companion object {
		const val SEPARATOR = "|"
	}
}

/**
 * Slot-to-slot bindings, one set per named profile. Made in the inventory by pointing at slots, so
 * the menu doesn't draw it; it's a setting only so it travels with the profile. Stored as one
 * string so [ConfigManager] treats it like text.
 */
class SlotMapModuleSetting(
	id: String,
	label: String,
): ModuleSetting(id, label, visibleIf = { false }) {
	private val profiles = LinkedHashMap<String, LinkedHashMap<Int, Int>>()

	fun binds(profile: String): MutableMap<Int, Int> = profiles.getOrPut(profile) { LinkedHashMap() }

	/** The profiles that have binds, oldest first. */
	fun names(): List<String> = profiles.filterValues { it.isNotEmpty() }.keys.toList()

	fun remove(profile: String) {
		profiles.remove(profile)
	}

	fun reset() = profiles.clear()

	/** `main=5>36,6>37;alt=9>44`. */
	fun encode(): String = profiles.entries
		.filter { it.value.isNotEmpty() }
		.joinToString(";") { (profile, binds) ->
			"$profile=" + binds.entries.joinToString(",") { "${it.key}>${it.value}" }
		}

	fun decode(text: String) {
		profiles.clear()
		if (text.isBlank()) return

		for (group in text.split(';')) {
			val name = group.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: continue
			val target = binds(name)
			for (pair in group.substringAfter('=').split(',')) {
				val from = pair.substringBefore('>').trim().toIntOrNull() ?: continue
				val to = pair.substringAfter('>', "").trim().toIntOrNull() ?: continue
				target[from] = to
			}
		}
	}
}

/**
 * A row that only opens a "⋯" panel, for a long tail of related settings set once (a card's dozen
 * colours). Hidden while its panel would be empty.
 */
class PanelModuleSetting(
	id: String,
	label: String,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
	/** Words shown where the dots would be, for a row that only says something ("Coming soon"). */
	val note: String? = null,
): ModuleSetting(id, label, visibleIf) {
	override fun isVisible(): Boolean = visibleIf() && (note != null || hasVisibleMore())
}

class ButtonModuleSetting(
	id: String,
	label: String,
	val action: () -> Unit,
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf)

/**
 * A heading that splits a card's settings into groups. A module with none is drawn under one "Main"
 * heading; declaring the first replaces it.
 */
class SectionModuleSetting(
	id: String,
	label: String,
	visibleIf: () -> Boolean = { true },
	/** Whether the group starts folded, for settings most people set once. */
	val startsCollapsed: Boolean = false,
): ModuleSetting(id, label, visibleIf)

/**
 * The registry shared by the menu and the config. Ids are separate from display names, so wording
 * can change without breaking profiles.
 */
object ModuleRegistry {
	val autoSprint = Module(
		id = "auto_sprint",
		name = "Auto Sprint",
		description = "Holds sprint for you",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
	)

	val modules = listOf(
		autoSprint,
		imicro.cryptic.feature.Zoom.module,
		imicro.cryptic.feature.NoJumpDelay.module,
		imicro.cryptic.feature.AutoClicker.module,
		imicro.cryptic.feature.ExperimentSolver.module,
		imicro.cryptic.feature.ClassColors.module,
		imicro.cryptic.feature.DungeonMap.module,
		imicro.cryptic.feature.DoorKeys.module,
		imicro.cryptic.feature.DoorHighlight.module,
		imicro.cryptic.feature.BreakerHelper.module,
		imicro.cryptic.feature.NoDebuff.module,
		imicro.cryptic.feature.HiddenMobs.module,
		imicro.cryptic.feature.InvincibilityTimer.module,
		imicro.cryptic.feature.SpiritLeapOverlay.module,
		imicro.cryptic.feature.RoomAlerts.module,
		imicro.cryptic.feature.Etherwarp.module,
		imicro.cryptic.feature.F7Qol.module,
		imicro.cryptic.feature.TerracottaTimer.module,
		imicro.cryptic.feature.F3Features.module,
		imicro.cryptic.feature.Highlight.module,
		imicro.cryptic.feature.Secrets.module,
		imicro.cryptic.feature.RenderOptimizer.module,
		imicro.cryptic.feature.HidePlayers.module,
		imicro.cryptic.feature.TerminalSolver.module,
		imicro.cryptic.feature.TerminalTimes.module,
		imicro.cryptic.feature.TerminalSimulator.module,
		imicro.cryptic.feature.TerminalEsp.module,
		imicro.cryptic.feature.TerminalOrder.module,
		imicro.cryptic.feature.DeviceSolver.module,
		imicro.cryptic.feature.Animations.module,
		imicro.cryptic.feature.BetterGlow.module,
		imicro.cryptic.feature.CustomNametags.module,
		imicro.cryptic.feature.WitherCloakEffect.module,
		imicro.cryptic.feature.CustomScale.module,
		imicro.cryptic.feature.LagDetector.module,
		imicro.cryptic.feature.PartyFeatures.module,
		imicro.cryptic.feature.AutoGfs.module,
		imicro.cryptic.feature.AutoRequeue.module,
		imicro.cryptic.feature.ClassAbilities.module,
		imicro.cryptic.feature.SpiritBear.module,
		imicro.cryptic.feature.LividSolver.module,
		imicro.cryptic.feature.PositionalMessages.module,
		imicro.cryptic.feature.DungeonWaypoints.module,
		imicro.cryptic.feature.BossWaypoints.module,
		imicro.cryptic.feature.SpringBootsHelper.module,
		imicro.cryptic.feature.PerformanceHud.module,
		imicro.cryptic.feature.DarkMode.module,
		imicro.cryptic.feature.BlessingDisplay.module,
		imicro.cryptic.feature.PuzzleHud.module,
		imicro.cryptic.feature.DungeonWarpCooldown.module,
		imicro.cryptic.feature.ILoveGlass.module,
		imicro.cryptic.feature.CookieReminder.module,
		imicro.cryptic.feature.ConfirmDisconnect.module,
		imicro.cryptic.feature.WitherDragons.module,
		imicro.cryptic.feature.Relics.module,
		imicro.cryptic.feature.PartyFinder.module,
		imicro.cryptic.feature.PartyBlacklist.module,
		imicro.cryptic.feature.DailyRewards.module,
		imicro.cryptic.feature.SmartTickTimer.module,
		imicro.cryptic.feature.BowTweaks.module,
		imicro.cryptic.feature.BlockOverlay.module,
		imicro.cryptic.feature.CameraTweaks.module,
		imicro.cryptic.feature.NoItemPlace.module,
		imicro.cryptic.feature.LoadoutManager.module,
		imicro.cryptic.feature.BetterChat.module,
		imicro.cryptic.feature.CroesusHelper.module,
		imicro.cryptic.feature.SbKick.module,
		imicro.cryptic.feature.TimeChanger.module,
		imicro.cryptic.feature.LavaToWater.module,
		imicro.cryptic.feature.CarryManager.module,
		imicro.cryptic.feature.SlayerFeatures.module,
		imicro.cryptic.feature.ChocolateFactory.module,
		imicro.cryptic.feature.KeybindManager.module,
		imicro.cryptic.feature.AliasManager.module,
		imicro.cryptic.feature.SoundVolumes.module,
		imicro.cryptic.feature.UpdateChecker.module,
		imicro.cryptic.feature.TooltipFeatures.module,
		imicro.cryptic.feature.GyroHelper.module,
		imicro.cryptic.feature.BloodCamp.module,
		imicro.cryptic.feature.PuzzleSolver.module,
		imicro.cryptic.feature.MageBeam.module,
		imicro.cryptic.feature.CrosshairEditor.module,
		imicro.cryptic.feature.SlotBinds.module,
		imicro.cryptic.feature.NucleusQol.module,
		imicro.cryptic.feature.ItemProtection.module,
		imicro.cryptic.feature.Bazaar.module,
		imicro.cryptic.feature.AuctionHouse.module,
		imicro.cryptic.feature.Mining.module,
		imicro.cryptic.feature.Fishing.module,
		imicro.cryptic.feature.Farming.module,
		imicro.cryptic.feature.ItemOutline.module,
		imicro.cryptic.feature.Capes.module,
		imicro.cryptic.feature.Freelook.module,
		imicro.cryptic.feature.Freecam.module,
		imicro.cryptic.feature.HideArmor.module,
		imicro.cryptic.feature.ClientRestrictions.module,
		imicro.cryptic.feature.Toasts.module,
		imicro.cryptic.feature.ExampleModule.module,
		imicro.cryptic.feature.ClickGui.module,
		imicro.cryptic.hud.HudSettings.module,
		imicro.cryptic.gui.menu.managers.ProfilesSection.module,
		imicro.cryptic.gui.menu.managers.CreditsSection.module,
	)

	val byId: Map<String, Module> = modules.associateBy(Module::id)
}
