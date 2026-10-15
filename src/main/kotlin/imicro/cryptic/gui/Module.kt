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
	val supportsToggle: Boolean = true,
	val supportsKeybind: Boolean = false,
	val keybind: KeybindSetting = KeybindSetting(),
	settings: List<ModuleSetting> = emptyList(),
	/**
	 * What a left click on the card does, for a module that is something to
	 * open rather than something to switch on. Right click still edits it.
	 */
	val cardAction: (() -> Unit)? = null,
) {
	/**
	 * Every setting the module has, the ones tucked into a "⋯" panel included,
	 * each straight after the row whose panel holds it. Profiles, resets and
	 * the menu all walk this one list; the menu skips a setting with a
	 * [ModuleSetting.parent] when it lays out the card.
	 */
	val settings: List<ModuleSetting> = settings.flatMap { listOf(it) + it.more }.distinct()

	/** Lowercased once so the menu can sort cards without rebuilding the key. */
	val sortKey: String = name.lowercase(Locale.ROOT)

}

/**
 * Anything the menu can present as a dropdown, so the popup is drawn by one
 * piece of code whether it belongs to a module or to a single setting.
 */
interface DropdownSource {
	val options: List<String>
	var selectedIndex: Int
	val selected: String

	/** Whether several options can be ticked at once, which keeps the list open while they are. */
	val multiple: Boolean get() = false

	/** Whether [index] is shown ticked in the open list. */
	fun isChecked(index: Int): Boolean = index == selectedIndex

	/** An option was clicked in the open list. */
	fun pick(index: Int) { selectedIndex = index }
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
	open fun isVisible() = visibleIf()

	/**
	 * Whether this setting gets a row of its own. A colour drawn beside its
	 * toggle does not: it is part of that toggle's row.
	 */
	open val hasOwnRow: Boolean get() = true

	/**
	 * The settings that open in a floating panel from the "⋯" beside this row,
	 * rather than lengthening the card. Set with [withMore].
	 */
	var more: List<ModuleSetting> = emptyList()
		private set

	/** The row whose "⋯" panel this setting lives in, or null for a row on the card. */
	var parent: ModuleSetting? = null
		private set

	/** A row on the card itself, as opposed to one inside a panel. */
	val onCard: Boolean get() = hasOwnRow && parent == null

	/** Whether the "⋯" has anything to show right now. */
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
 * Moves [children] off the card and into a floating panel opened by the "⋯"
 * beside this row: the settings that fine-tune this one, which are wanted
 * once and then never again. Returns the row itself, so it reads in place in
 * a module's list of settings.
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
	/** Shown after the number in the value box ("ms", "%", "°"), never typed. */
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
		// A one-letter unit or a sign sits against its number ("5s", "90%");
		// a word does not ("100 ms").
		unit.length == 1 -> number + unit
		else -> "$number $unit"
	}

	var value: Double = defaultValue

	/** The value as the menu shows it, formatted only when the value changes. */
	val displayValue: String get() = valueLabel.of(value)

	/** The value with its unit, as the box beside the slider shows it. */
	val boxValue: String get() = boxLabel.of(value)

	/**
	 * The widest the box's text can get, so the box keeps one size while the
	 * slider is dragged instead of growing and shrinking under the cursor.
	 */
	val widestBoxValue: String by lazy {
		listOf(min, max, min + step, max - step, defaultValue)
			.map { withUnit(format(it)) }
			.maxBy { it.length }
	}

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

	/** The widest either end can get, so both boxes keep one size while dragged. */
	val widestValue: String by lazy {
		listOf(min, max, min + step, max - step).map(format).maxBy { it.length }
	}

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
	/** Completions for what has been typed so far, listed under the field while it is being typed in. */
	val suggestions: ((String) -> List<String>)? = null,
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
	/** The key it starts on, before anybody binds it: a GLFW key, [mouse] of a button, or [UNBOUND]. */
	val defaultKey: Int = UNBOUND,
): ModuleSetting(id, label, visibleIf) {
	/**
	 * A GLFW key code, a mouse button as [mouse] makes it, or [UNBOUND]. Stable
	 * across launches, so it is what is saved.
	 */
	var keyCode: Int = defaultKey

	val bound: Boolean get() = keyCode != UNBOUND

	/** Whether the bind is a mouse button rather than a key. */
	val isMouse: Boolean get() = keyCode >= MOUSE_BASE

	/** The key the way a module's own badge names it: "R", "LEFT.SHIFT", "MOUSE.5", "None". */
	val keyName: String
		get() = if (!bound) "None" else nameOf(keyCode)

	/** A key press, by its GLFW code. Never true of a mouse bind. */
	fun matches(key: Int): Boolean = bound && !isMouse && key == keyCode

	/** A mouse button press, by its GLFW button. */
	fun matchesMouse(button: Int): Boolean = isMouse && keyCode - MOUSE_BASE == button

	/** Whether the key or button is held down right now. */
	fun isDown(window: com.mojang.blaze3d.platform.Window): Boolean = when {
		!bound -> false
		isMouse -> org.lwjgl.glfw.GLFW.glfwGetMouseButton(window.handle(), keyCode - MOUSE_BASE) == org.lwjgl.glfw.GLFW.GLFW_PRESS
		else -> InputConstants.isKeyDown(window, keyCode)
	}

	fun reset() { keyCode = defaultKey }

	companion object {
		const val UNBOUND = -1

		/**
		 * Where mouse buttons start, far above every GLFW key code (the highest
		 * is 348), so one number can hold either and old saves stay keys.
		 */
		const val MOUSE_BASE = 10_000

		/** The bind for GLFW mouse [button]: 3 is the back side button, 4 the forward one. */
		fun mouse(button: Int): Int = MOUSE_BASE + button

		/** A key or [mouse] button the way a badge names it: "R", "LEFT.SHIFT", "MOUSE.5". */
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
	override val options: List<String>,
	val defaultIndex: Int = 0,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf), DropdownSource {
	override var selectedIndex: Int = defaultIndex

	override val selected: String get() = options[selectedIndex]

	/** Profiles store the chosen label, so wording changes cannot silently reorder. */
	fun select(option: String) {
		options.indexOf(option).takeIf { it >= 0 }?.let { selectedIndex = it }
	}

	fun reset() { selectedIndex = defaultIndex }
}

/**
 * A list of options, any number of which can be ticked.
 *
 * Drawn as a dropdown whose box names everything ticked ("Head, Chest, …"),
 * opening to the whole list with a tick beside each chosen option; clicking
 * one ticks or unticks it and leaves the list open. Saved as the ticked
 * options' labels rather than their positions, as dropdowns are, so adding or
 * reordering options keeps everybody's choices.
 */
class MultiSelectModuleSetting(
	id: String,
	label: String,
	override val options: List<String>,
	/** The options ticked to begin with. Every one, unless said otherwise. */
	defaultSelected: Set<String> = options.toSet(),
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf), DropdownSource {
	private val defaultIndices: Set<Int> = options.indices.filter { options[it] in defaultSelected }.toSet()

	/** Which options are ticked, by their place in [options]. */
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

	/** No single option is "the" selection, so none is singled out. */
	override var selectedIndex: Int
		get() = -1
		set(_) {}

	/** The ticked options in list order, built once per change rather than per frame. */
	override val selected: String get() = summary ?: when {
		ticked.isEmpty() -> "None"
		ticked.size == options.size -> "All"
		else -> options.filterIndexed { index, _ -> index in ticked }.joinToString(", ")
	}.also { summary = it }

	override val multiple: Boolean get() = true

	override fun isChecked(index: Int): Boolean = index in ticked

	override fun pick(index: Int) = toggle(index)

	/** The ticked labels, joined, for the profile. */
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

/**
 * A row that is only a way into a "⋯" panel: a label, and the dots at the
 * right-hand end. For a long tail of settings that belong together but are
 * set once and left — a card's dozen colours — so they leave the card without
 * leaving the module. Clicking anywhere on the row opens the panel. Holds
 * nothing itself, and is hidden while nothing in its panel would show.
 */
class PanelModuleSetting(
	id: String,
	label: String,
	val description: String = "",
	visibleIf: () -> Boolean = { true },
): ModuleSetting(id, label, visibleIf) {
	override fun isVisible(): Boolean = visibleIf() && hasVisibleMore()
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
		imicro.cryptic.feature.LoadoutManager.module,
		imicro.cryptic.feature.BetterChat.module,
		imicro.cryptic.feature.CroesusHelper.module,
		imicro.cryptic.feature.SbKick.module,
		imicro.cryptic.feature.TimeChanger.module,
		imicro.cryptic.feature.LavaToWater.module,
		imicro.cryptic.feature.CarryManager.module,
		imicro.cryptic.feature.KeybindManager.module,
		imicro.cryptic.feature.AliasManager.module,
		imicro.cryptic.feature.SoundVolumes.module,
		imicro.cryptic.feature.UpdateChecker.module,
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
		imicro.cryptic.gui.menu.managers.ProfilesSection.module,
	)

	val byId: Map<String, Module> = modules.associateBy(Module::id)
}
