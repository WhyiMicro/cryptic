package imicro.cryptic.gui

import com.mojang.blaze3d.platform.InputConstants
import java.util.Locale
import kotlin.math.roundToInt

/** The top-level pages shown by the Cryptic menu. */
enum class ModuleCategory(val title: String) {
	GENERAL("General"),
	DUNGEON("Dungeon"),
	// Named for the fight rather than the floor: what is in here started as
	// Floor 7's terminals and towers, and the other floors' bosses belong
	// beside them rather than in a tab of their own.
	FLOOR_7("Boss"),
	VISUAL("Render"),
	MISC("Settings"),
	DEVELOPER("Profiles"),
}

/**
 * Pre-built Dear ImGui identifiers for one control.
 *
 * ImGui addresses widgets by string, and the menu redraws every control each
 * frame. Building those strings per frame allocated continuously, so each one
 * is derived once from the stable module and setting ids.
 */
class WidgetIds(prefix: String) {
	/** Identifies the control itself: a toggle, slider track, button or swatch. */
	val control = "##$prefix"

	/** Identifies the inline numeric editor, which is one control in two states. */
	val editorKey = prefix
	val editorHit = "##edit_numeric_$prefix"
	val editorInput = "##numeric_$prefix"

	/** Identifies the popup opened by a color swatch. */
	val picker = "##${prefix}_picker"
}

/** Pre-built identifiers for the controls a module card owns itself. */
class ModuleWidgetIds(moduleId: String, dropdownOptionCount: Int) {
	val card = "##card_$moduleId"
	val toggle = "##toggle_$moduleId"
	val expand = "##expand_$moduleId"
	val keybind = "##bind_$moduleId"
	val demoSlider = "##slider_$moduleId"
	val demoSliderEditor = WidgetIds("${moduleId}_demo_slider")
	val demoRange = "##range_$moduleId"
	val demoRangeLower = WidgetIds("${moduleId}_range_lower")
	val demoRangeUpper = WidgetIds("${moduleId}_range_upper")
	val demoDropdown = "##dropdown_$moduleId"
	val demoDropdownOptions: List<String> = List(dropdownOptionCount) { "##option_${moduleId}_$it" }
}

/**
 * Formats a changing number once per distinct value.
 *
 * Value labels are redrawn every frame but only change while a slider is being
 * dragged, so the formatted string is kept until the value actually moves.
 */
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

/**
 * A reusable module definition. Each setting type is explicit so a future
 * config file and GUI control can handle it without special-casing modules.
 */
class Module(
	val id: String,
	val name: String,
	val description: String,
	val category: ModuleCategory,
	var enabled: Boolean = false,
	val hasDemoSettings: Boolean = true,
	val supportsToggle: Boolean = true,
	val supportsKeybind: Boolean = false,
	val slider: SliderSetting = SliderSetting("Amount", 50.0, 0.0, 100.0),
	val range: RangeSetting = RangeSetting("Range", 25.0, 75.0, 0.0, 100.0),
	val dropdown: DropdownSetting = DropdownSetting("Mode", listOf("Default", "Subtle", "Detailed")),
	val keybind: KeybindSetting = KeybindSetting(),
	val settings: List<ModuleSetting> = emptyList(),
) {
	val hasSettings: Boolean get() = hasDemoSettings || settings.isNotEmpty()

	/** True when [source] is this module's own dropdown or one of its settings. */
	fun owns(source: DropdownSource?): Boolean =
		source != null && (source === dropdown || settings.any { it === source })

	/** Lowercased once so the menu can sort cards without rebuilding the key. */
	val sortKey: String = name.lowercase(Locale.ROOT)

	val widgetIds = ModuleWidgetIds(id, dropdown.options.size)

	init {
		dropdown.attachTo(this)
		settings.forEach { it.attachTo(this) }
	}
}

class SliderSetting(val label: String, var value: Double, val min: Double, val max: Double) {
	private val valueLabel = NumberLabel { it.roundToInt().toString() }

	val labelWithColon: String = "$label:"
	val displayValue: String get() = valueLabel.of(value)
}

class RangeSetting(
	val label: String,
	var lower: Double,
	var upper: Double,
	val min: Double,
	val max: Double,
) {
	private val lowerLabelCache = NumberLabel { it.roundToInt().toString() }
	private val upperLabelCache = NumberLabel { it.roundToInt().toString() }

	val labelWithColon: String = "$label:"
	val displayLower: String get() = lowerLabelCache.of(lower)
	val displayUpper: String get() = upperLabelCache.of(upper)
}

/**
 * Anything the menu can present as a dropdown, so the popup is drawn by one
 * piece of code whether it belongs to a module or to a single setting.
 */
interface DropdownSource {
	val options: List<String>
	var selectedIndex: Int
	val selected: String

	/** Dear ImGui identifiers for the closed control and each option row. */
	val buttonId: String
	val optionIds: List<String>
}

class DropdownSetting(
	val label: String,
	override val options: List<String>,
	override var selectedIndex: Int = 0,
): DropdownSource {
	override val selected: String get() = options[selectedIndex]

	override var buttonId: String = "##dropdown"
		private set

	override var optionIds: List<String> = emptyList()
		private set

	/** A module's own identifiers are what make this dropdown's unique. */
	internal fun attachTo(module: Module) {
		buttonId = module.widgetIds.demoDropdown
		optionIds = module.widgetIds.demoDropdownOptions
	}
}

/**
 * A key a module can be bound to.
 *
 * Deliberately not part of a profile. A profile is a set of preferences worth
 * swapping between — colours, thresholds, what is switched on — and none of
 * that should silently move the keys under someone's fingers. Where a bind is
 * a real Minecraft key mapping, Minecraft's own options file is what remembers
 * it, and the controls screen can rebind it like any other.
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

/** A real module may declare as many typed, profile-backed settings as it needs. */
sealed class ModuleSetting(
	val id: String,
	val label: String,
	val visibleIf: () -> Boolean = { true },
) {
	val labelWithColon: String = "$label:"

	/**
	 * Assigned once by the owning [Module], because a setting's identifier is
	 * only unique when it is qualified with the module that declares it.
	 */
	var widgetIds: WidgetIds = WidgetIds(id)
		private set

	internal fun attachTo(module: Module) {
		widgetIds = WidgetIds("${module.id}_$id")
	}

	fun isVisible() = visibleIf()

	/**
	 * Whether this setting gets a row of its own. A colour drawn beside its
	 * toggle does not: it is part of that toggle's row.
	 */
	open val hasOwnRow: Boolean get() = true
}

class ToggleModuleSetting(
	id: String,
	label: String,
	val defaultValue: Boolean = false,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf) {
	var value: Boolean = defaultValue
	fun reset() { value = defaultValue }

	/** A colour drawn as a swatch beside this toggle, when one is paired with it. */
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
): ModuleSetting(id, label, visibleIf) {
	private val valueLabel = NumberLabel { raw ->
		if (step >= 1.0) {
			raw.roundToInt().toString()
		} else {
			String.format(Locale.ROOT, "%.2f", raw).trimEnd('0').trimEnd('.')
		}
	}

	var value: Double = defaultValue

	/** The value as the menu shows it, formatted only when the value changes. */
	val displayValue: String get() = valueLabel.of(value)

	fun reset() { value = defaultValue }
}

/**
 * Two numbers that bound each other, drawn as one slider with two handles.
 *
 * What a range is for here is picking a number that is not always the same one:
 * [random] draws from it, so a delay built on this varies instead of repeating.
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

	/**
	 * Each end is separately editable, so each needs identifiers of its own.
	 * Built on first use, because a setting only knows its qualified identifier
	 * once the owning module has attached it.
	 */
	val lowerIds: WidgetIds by lazy { WidgetIds("${widgetIds.editorKey}_lower") }
	val upperIds: WidgetIds by lazy { WidgetIds("${widgetIds.editorKey}_upper") }

	/** A value somewhere in the range, inclusive of both ends. */
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
	 * The toggle this colour belongs to, which then draws it as a swatch beside
	 * itself instead of the colour taking a row. Only for a toggle with exactly
	 * one colour of its own.
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

	/** Dear ImGui packs colors as ABGR; the swatch is redrawn every frame. */
	val abgr: Int
		get() {
			val color = rgb
			val swatchAlpha = if (supportsAlpha) alpha else 0xFF
			return ((swatchAlpha and 0xFF) shl 24) or
				((color and 0xFF) shl 16) or
				(color and 0x00FF00) or
				((color ushr 16) and 0xFF)
		}

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
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf) {
	var value: String = defaultValue
		set(text) { field = text.take(maxLength) }

	fun reset() { value = defaultValue }
}

/**
 * A key a module reacts to, chosen in its settings.
 *
 * Unlike a module's own [KeybindSetting], this is one of several a module can
 * carry — one per corner of a menu, say — and it is read by the module while a
 * screen of its own is open, never as a global hotkey. Keyboard keys only: the
 * screens that use these are already taking mouse clicks, and a bind on a mouse
 * button would collide with the click it sits beside.
 */
class KeybindModuleSetting(
	id: String,
	label: String,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf) {
	/** A GLFW key code, or [UNBOUND]. Stable across launches, so it is what is saved. */
	var keyCode: Int = UNBOUND

	val bound: Boolean get() = keyCode != UNBOUND

	/** The key the way a module's own badge names it: "R", "LEFT.SHIFT", "None". */
	val keyName: String
		get() = if (!bound) "None" else InputConstants.Type.KEYSYM.getOrCreate(keyCode).name
			.removePrefix("key.keyboard.")
			.uppercase()

	fun matches(key: Int): Boolean = bound && key == keyCode

	fun reset() { keyCode = UNBOUND }

	companion object {
		const val UNBOUND = -1
	}
}

class DropdownModuleSetting(
	id: String,
	label: String,
	override val options: List<String>,
	val defaultIndex: Int = 0,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf), DropdownSource {
	override var selectedIndex: Int = defaultIndex

	override val selected: String get() = options[selectedIndex]

	override val buttonId: String get() = widgetIds.control

	/**
	 * Built on first use rather than in the constructor, because a setting only
	 * knows its qualified identifier once the owning module has attached it.
	 */
	override val optionIds: List<String> by lazy {
		List(options.size) { "##option_${widgetIds.editorKey}_$it" }
	}

	/** Profiles store the chosen label, so wording changes cannot silently reorder. */
	fun select(option: String) {
		options.indexOf(option).takeIf { it >= 0 }?.let { selectedIndex = it }
	}

	fun reset() { selectedIndex = defaultIndex }
}

/**
 * A set of slot-to-slot bindings, one set per named profile.
 *
 * The only setting here the menu does not draw. What it holds is made in the
 * inventory rather than in the settings screen — a pair of slots picked by
 * pointing at them — and a list of numbers in a config panel would be neither
 * editable nor readable. It is a [ModuleSetting] so that it travels with the
 * profile like everything else; it simply has no row.
 *
 * Stored as one string rather than as structured JSON, so [ConfigManager] can
 * treat it exactly as it treats a text setting.
 */
class SlotMapModuleSetting(
	id: String,
	label: String,
): ModuleSetting(id, label, visibleIf = { false }) {
	/** Profile name to the binds made under it, each a slot pointing at a slot. */
	private val profiles = LinkedHashMap<String, LinkedHashMap<Int, Int>>()

	fun binds(profile: String): MutableMap<Int, Int> = profiles.getOrPut(profile) { LinkedHashMap() }

	fun reset() = profiles.clear()

	/** `main=5>36,6>37;alt=9>44`, which is short enough to sit in a config file. */
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

class ButtonModuleSetting(
	id: String,
	label: String,
	val action: () -> Unit,
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf)

/**
 * A heading, with a rule under it, that splits a card's settings into groups.
 *
 * A module that declares none is drawn under one "Main" heading, so cards with
 * a handful of settings need not say anything. Declaring the first one replaces
 * that heading rather than adding to it.
 */
class SectionModuleSetting(
	id: String,
	label: String,
	visibleIf: () -> Boolean = { true },
	/**
	 * Whether the group starts folded away.
	 *
	 * For the long tail of a card: a dozen colour swatches that most people set
	 * once are worth having, and worth having out of the way until they are
	 * wanted.
	 */
	val startsCollapsed: Boolean = false,
): ModuleSetting(id, label, visibleIf)

/**
 * One stable registry shared by the GUI and config system. IDs are deliberately
 * separate from display names, so wording can change without breaking profiles.
 */
object ModuleRegistry {
	val autoSprint = Module(
		id = "auto_sprint",
		name = "Auto Sprint",
		description = "Automatically sprints while moving forward",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
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
		imicro.cryptic.feature.LeapMessage.module,
		imicro.cryptic.feature.Etherwarp.module,
		imicro.cryptic.feature.F7Qol.module,
		imicro.cryptic.feature.TerracottaTimer.module,
		imicro.cryptic.feature.Highlight.module,
		imicro.cryptic.feature.Secrets.module,
		imicro.cryptic.feature.RenderOptimizer.module,
		imicro.cryptic.feature.HidePlayers.module,
		imicro.cryptic.feature.TerminalSolver.module,
		imicro.cryptic.feature.TerminalTimes.module,
		imicro.cryptic.feature.Tooltips.module,
		imicro.cryptic.feature.TerminalSimulator.module,
		imicro.cryptic.feature.TerminalEsp.module,
		imicro.cryptic.feature.TerminalOrder.module,
		imicro.cryptic.feature.DeviceSolver.module,
		imicro.cryptic.feature.Animations.module,
		imicro.cryptic.feature.BetterGlow.module,
		imicro.cryptic.feature.CustomNametags.module,
		imicro.cryptic.feature.WitherCloakEffect.module,
		imicro.cryptic.feature.ArrowHitboxes.module,
		imicro.cryptic.feature.CustomScale.module,
		imicro.cryptic.feature.LagDetector.module,
		imicro.cryptic.feature.PartyFeatures.module,
		imicro.cryptic.feature.AutoGfs.module,
		imicro.cryptic.feature.AutoRequeue.module,
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
		imicro.cryptic.feature.SmartTickTimer.module,
		imicro.cryptic.feature.ArrowFix.module,
		imicro.cryptic.feature.ArrowHitSound.module,
		imicro.cryptic.feature.BlockOverlay.module,
		imicro.cryptic.feature.CameraTweaks.module,
		imicro.cryptic.feature.NoItemPlace.module,
		imicro.cryptic.feature.SbKick.module,
		imicro.cryptic.feature.TimeChanger.module,
		imicro.cryptic.feature.LavaToWater.module,
		imicro.cryptic.feature.CarryManager.module,
		imicro.cryptic.feature.ScrollableTooltips.module,
		imicro.cryptic.feature.GyroHelper.module,
		imicro.cryptic.feature.BloodCamp.module,
		imicro.cryptic.feature.PuzzleSolver.module,
		imicro.cryptic.feature.MageBeam.module,
		imicro.cryptic.feature.CrosshairEditor.module,
		imicro.cryptic.feature.SlotBinds.module,
		imicro.cryptic.feature.NucleusQol.module,
		imicro.cryptic.feature.Toasts.module,
		imicro.cryptic.feature.ExampleModule.module,
		imicro.cryptic.feature.ClickGui.module,
	)

	val byId: Map<String, Module> = modules.associateBy(Module::id)
}
