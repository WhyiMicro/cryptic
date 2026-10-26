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
 * Counts down each terracotta in Sadan's room coming back. A killed terracotta leaves a flower pot
 * and stands back up 15 seconds later (12 in Master Mode); the pot appearing starts the clock.
 * Counted in server ticks, so lag delays both equally. From Odin's and NoammAddons' Terracotta
 * Timer.
 */
object TerracottaTimer {
	private const val FLOOR = 6

	private const val RESPAWN_TICKS = 300
	private const val RESPAWN_TICKS_MASTER = 240

	/** Sadan ending the phase, which wakes every terracotta. */
	private const val PHASE_END = "[BOSS] Sadan: ENOUGH!"

	/** NoammAddons waits this long after the line before dropping the timers. */
	private const val PHASE_END_TICKS = 10

	/** Odin's thresholds: green while there's time, red when not. */
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
		description = "F6 terracotta respawn timers",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf(dynamicColor, color, scale, phase),
	)

	/**
	 * Where each terracotta fell and ticks until it's back. Written on the client thread, counted
	 * down on the network thread.
	 */
	private val respawning = ConcurrentHashMap<BlockPos, AtomicInteger>()

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

	/** Sadan's room, either difficulty; the debug switch stands in elsewhere. */
	private fun inSadanFight(): Boolean =
		DebugOverrides.terracottaAnywhere ||
			(DungeonLocation.inDungeon && DungeonLocation.floor == FLOOR && DungeonRun.inBoss)

	/** Whether a block change is worth looking at. Asked for every change, so it's two booleans. */
	@JvmStatic
	fun isWatching(): Boolean = module.enabled && inSadanFight()

	/** A flower pot appearing: a terracotta going down. */
	@JvmStatic
	fun onPotPlaced(pos: BlockPos) {
		val ticks = if (DungeonLocation.masterMode) RESPAWN_TICKS_MASTER else RESPAWN_TICKS
		// The same pot is set more than once as the chunk catches up; the first was on time.
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

	/** Counts client ticks for the debug switch, since singleplayer sends no per-tick ping. */
	fun tick() {
		if (DebugOverrides.terracottaAnywhere && !ServerTicks.available) onServerTick()
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || respawning.isEmpty()) return
		if (!inSadanFight()) {
			// Left the room with timers running; they're for another fight.
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

	/** For `/cryptic debug terracotta`. */
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
