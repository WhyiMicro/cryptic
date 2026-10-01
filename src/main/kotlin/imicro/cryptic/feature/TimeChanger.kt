package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import java.time.LocalTime

/**
 * Holds the world at a time of day you picked.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). SkyBlock's night is dark
 * enough to hide a mob at melee range, and its dawn puts the sun directly into
 * your eyes for several minutes at a time. Fixing the clock is a comfort
 * setting that changes nothing about the world — the server's time goes on
 * running, and only the sky this client draws is held still.
 *
 * The time is reapplied whenever the server announces its own, because that
 * announcement is exactly what would otherwise undo it.
 */
object TimeChanger {
	/** Day, noon, sunset, night, midnight, sunrise — in ticks. */
	private val PRESET_TICKS = longArrayOf(1_000L, 6_000L, 12_000L, 13_000L, 18_000L, 23_000L)

	/** A full Minecraft day, which is also the slider's range. */
	private const val DAY_TICKS = 24_000L

	/** Ticks per real minute, for the setting that follows your own clock. */
	private const val TICKS_PER_MINUTE = 16.66

	/** Minecraft's day starts at 6am, so real time is offset by six hours. */
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
		description = "Holds the sky at one time of day",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(usePreset, preset, ticks),
	)

	/**
	 * Writes the chosen time into the client's copy of the world.
	 *
	 * Called both when the setting changes and when the server sends its own
	 * time, which is the only thing that would overwrite it.
	 */
	@JvmStatic
	fun apply() {
		if (!module.enabled) return
		Minecraft.getInstance().level?.setTimeFromServer(chosenTicks())
	}

	/** True while the server's own time announcement should be ignored. */
	@JvmStatic
	fun overridesServerTime(): Boolean = module.enabled

	private fun chosenTicks(): Long {
		if (!usePreset.value) return ticks.value.toLong()
		return PRESET_TICKS.getOrElse(preset.selectedIndex) { realTimeTicks() }
	}

	/** Your own wall clock, mapped onto Minecraft's day. */
	private fun realTimeTicks(): Long {
		val now = LocalTime.now()
		val raw = now.hour * 1_000L + (now.minute * TICKS_PER_MINUTE).toLong() - DAWN_OFFSET
		return if (raw < 0) raw + DAY_TICKS else raw
	}

	/**
	 * Keeps the sky moving while "Real time" is chosen, and re-asserts the
	 * choice in general.
	 *
	 * Once a second is plenty: a Minecraft minute is a little over a second of
	 * sky movement, and nothing here has to be smooth — the alternative is a
	 * sky that only updates when the server happens to mention the time.
	 */
	fun tick(client: Minecraft) {
		if (!module.enabled || client.level == null) return
		if (ticksUntilRefresh-- > 0) return
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS
		apply()
	}

	private const val REFRESH_INTERVAL_TICKS = 20
	private var ticksUntilRefresh = 0
}
