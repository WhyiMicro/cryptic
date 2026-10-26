package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import java.time.LocalTime

/**
 * Holds the sky at a time you pick; the server's clock keeps running. Reapplied whenever the server
 * sends its time. Ported from NoammAddons.
 */
object TimeChanger {
	/** Day, noon, sunset, night, midnight, sunrise, in ticks. */
	private val PRESET_TICKS = longArrayOf(1_000L, 6_000L, 12_000L, 13_000L, 18_000L, 23_000L)

	private const val DAY_TICKS = 24_000L

	private const val TICKS_PER_MINUTE = 16.66

	/** Minecraft's day starts at 6am. */
	private const val DAWN_OFFSET = 6_000L

	@JvmField
	val usePreset = ToggleModuleSetting(
		id = "use_preset",
		label = "Use a preset",
		defaultValue = true,
		description = "Picks from the named times of day instead of setting the tick by hand.",
	)

	@JvmField
	val preset = DropdownModuleSetting(
		id = "preset",
		label = "Time",
		options = listOf("Day", "Noon", "Sunset", "Night", "Midnight", "Sunrise", "Real time"),
		description = "Real time follows your own clock, so the sky outside matches the sky in the game.",
		visibleIf = { usePreset.value },
	)

	@JvmField
	val ticks = SliderModuleSetting(
		id = "ticks",
		label = "Tick",
		defaultValue = 6_000.0,
		min = 0.0,
		max = 23_999.0,
		step = 100.0,
		description = "The raw time of day. Zero is dawn, 6000 is noon, 18000 is midnight.",
		visibleIf = { !usePreset.value },
	)

	@JvmField
	val module = Module(
		id = "time_changer",
		name = "Time Changer",
		description = "Holds the sky at one time",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(usePreset, preset, ticks),
	)

	/**
	 * Writes the chosen time into the client's world, on setting changes and on the server's time
	 * packets.
	 */
	@JvmStatic
	fun apply() {
		if (!module.enabled) return
		Minecraft.getInstance().level?.setTimeFromServer(chosenTicks())
	}

	@JvmStatic
	fun overridesServerTime(): Boolean = module.enabled

	private fun chosenTicks(): Long {
		if (!usePreset.value) return ticks.value.toLong()
		return PRESET_TICKS.getOrElse(preset.selectedIndex) { realTimeTicks() }
	}

	private fun realTimeTicks(): Long {
		val now = LocalTime.now()
		val raw = now.hour * 1_000L + (now.minute * TICKS_PER_MINUTE).toLong() - DAWN_OFFSET
		return if (raw < 0) raw + DAY_TICKS else raw
	}

	/** Once a second: keeps "Real time" moving and reasserts the choice. */
	fun tick(client: Minecraft) {
		if (!module.enabled || client.level == null) return
		if (ticksUntilRefresh-- > 0) return
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS
		apply()
	}

	private const val REFRESH_INTERVAL_TICKS = 20
	private var ticksUntilRefresh = 0
}
