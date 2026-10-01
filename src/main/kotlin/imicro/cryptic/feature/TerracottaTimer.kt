package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts down to each terracotta in Sadan's room coming back to life.
 *
 * Ported from Odin's Terracotta Timer (BSD 3-Clause, Copyright (c) 2025
 * odtheking) and NoammAddons' (CC0, Noamm9), which agree on everything that
 * matters. A terracotta that is killed is not gone: Hypixel leaves a flower pot
 * where it fell and stands it back up fifteen seconds later — twelve in Master
 * Mode — and the first phase of the fight is mostly a matter of knowing which
 * ones are about to. The pot appearing is the only announcement there is, so
 * that is what starts the clock.
 *
 * Counted in server ticks rather than in seconds, like every other countdown
 * in a dungeon: when the server lags, the terracotta is late by exactly as much
 * as the timer is.
 */
object TerracottaTimer {
	private const val FLOOR = 6

	/** How long a terracotta stays down, in server ticks. */
	private const val RESPAWN_TICKS = 300
	private const val RESPAWN_TICKS_MASTER = 240

	/** Sadan ending the phase, which is every terracotta waking at once. */
	private const val PHASE_END = "[BOSS] Sadan: ENOUGH!"

	/** NoammAddons waits this long after the line before dropping the timers. */
	private const val PHASE_END_TICKS = 10

	/** Odin's thresholds, in seconds: green while there is time, red when there is not. */
	private const val SOON_SECONDS = 5.0
	private const val IMMINENT_SECONDS = 2.0

	private const val GREEN = 0xFF55FF55.toInt()
	private const val GOLD = 0xFFFFAA00.toInt()
	private const val RED = 0xFFFF5555.toInt()

	private val FORMATTING = Regex("§.")

	@JvmField
	val dynamicColor = ToggleModuleSetting(
		id = "dynamic_color",
		label = "Dynamic color",
		defaultValue = true,
		description = "Green with time to spare, gold under five seconds, red under two.",
	)

	@JvmField
	val color = ColorModuleSetting(
		id = "color",
		label = "Color",
		defaultRgb = 0xFFFFFF,
		description = "The one colour every timer is drawn in.",
		visibleIf = { !dynamicColor.value },
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Scale",
		defaultValue = 2.0,
		min = 0.5,
		max = 5.0,
		step = 0.1,
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the timers through the giants and the walls in front of them.",
	)

	@JvmField
	val module = Module(
		id = "terracotta_timer",
		name = "Terracotta Timer",
		description = "Counts down each terracotta's respawn on F6/M6",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(dynamicColor, color, scale, phase),
	)

	/**
	 * Where each terracotta fell, and how many server ticks until it is back.
	 *
	 * Written by the client thread when a pot appears and counted down by the
	 * network thread, so it is a map that can take both and a counter per entry
	 * that can be decremented without a lock.
	 */
	private val respawning = ConcurrentHashMap<BlockPos, AtomicInteger>()

	/** Counts down to the timers being dropped once Sadan has ended the phase. */
	@Volatile
	private var clearIn = -1

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay && respawning.isNotEmpty() && message.string.replace(FORMATTING, "") == PHASE_END) {
				clearIn = PHASE_END_TICKS
			}
		}
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		respawning.clear()
		clearIn = -1
	}

	/** Sadan's room, on either difficulty. The debug switch stands in for it elsewhere. */
	private fun inSadanFight(): Boolean =
		DebugOverrides.terracottaAnywhere ||
			(DungeonLocation.inDungeon && DungeonLocation.floor == FLOOR && DungeonRun.inBoss)

	/**
	 * Whether a block change is worth looking at.
	 *
	 * Asked for every block that changes anywhere, so it is two booleans and
	 * nothing else.
	 */
	@JvmStatic
	fun isWatching(): Boolean = module.enabled && inSadanFight()

	/** A flower pot appearing, which is a terracotta going down. */
	@JvmStatic
	fun onPotPlaced(pos: BlockPos) {
		val ticks = if (DungeonLocation.masterMode) RESPAWN_TICKS_MASTER else RESPAWN_TICKS
		// The same pot is set more than once as the chunk catches up, and the
		// first of them is the one that was on time.
		respawning.putIfAbsent(pos.immutable(), AtomicInteger(ticks))
	}

	@JvmStatic
	fun onServerTick() {
		if (respawning.isEmpty()) return

		if (clearIn > 0 && --clearIn == 0) {
			clearIn = -1
			respawning.clear()
			return
		}

		respawning.entries.removeIf { it.value.decrementAndGet() <= 0 }
	}

	/**
	 * Counts on the client's own ticks where there is no server clock to count.
	 *
	 * Only for the debug switch: a single-player world sends no per-tick ping,
	 * so a timer started there would otherwise sit at its first number forever.
	 */
	fun tick() {
		if (DebugOverrides.terracottaAnywhere && !ServerTicks.available) onServerTick()
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || respawning.isEmpty()) return
		if (!inSadanFight()) {
			// Left the room with timers still running: they describe a fight
			// that is not this one any more.
			forget()
			return
		}

		val client = Minecraft.getInstance()
		val orientation = client.gameRenderer.mainCamera().rotation()

		respawning.forEach { (pos, ticks) ->
			val seconds = ticks.get() / 20.0
			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = orientation,
				text = Component.literal(String.format(Locale.ROOT, "%.1fs", seconds)),
				x = pos.x + 0.5,
				y = pos.y + 0.5,
				z = pos.z + 0.5,
				scale = scale.value.toFloat(),
				seeThrough = phase.value,
				argb = colorFor(seconds),
			)
		}
	}

	private fun colorFor(seconds: Double): Int = when {
		!dynamicColor.value -> color.argb
		seconds > SOON_SECONDS -> GREEN
		seconds > IMMINENT_SECONDS -> GOLD
		else -> RED
	}

	/** What is being counted, for `/cryptic debug terracotta`. */
	fun describe(): List<String> {
		val lines = mutableListOf(
			"§8[Cryptic] §7Terracotta Timer: module ${if (module.enabled) "§aon" else "§coff"}§7, " +
				"in Sadan's fight ${if (inSadanFight()) "§ayes" else "§cno"}§7, " +
				"floor §f${DungeonLocation.floor}§7, boss §f${DungeonRun.inBoss}",
		)
		if (respawning.isEmpty()) {
			lines += "§8[Cryptic] §7No terracotta is down."
		} else {
			respawning.forEach { (pos, ticks) ->
				lines += "§8[Cryptic] §f${pos.x} ${pos.y} ${pos.z}§7: §f${ticks.get()} §7ticks"
			}
		}
		return lines
	}
}
