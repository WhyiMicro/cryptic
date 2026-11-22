package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.effect.MobEffectInstance
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hypixel's once-a-second clock in the Crystal Hollows, as ticks to its next beat, in the Smart
 * Tick Timer's secret-timer style. Stepping into Khazad-dûm or the Jungle Temple only counts on a
 * beat, so leaving before it spawns no Bal and costs no slow.
 *
 * The beat is taken from the server's time packet, which comes once a second;
 * `/cryptic debug clock` records it beside the area crossings and the sidebar to check its phase.
 */
object HollowsClock {
	@JvmField
	val setting = ToggleModuleSetting(
		id = "hollows_tick_timer",
		label = "Tick timer",
		description = "Ticks to the server's next second, when stepping into Khazad-dûm or the Jungle Temple counts. Placed on its own in the HUD editor.",
	)

	private const val SECOND_TICKS = 20

	/** Ticks to the next beat, or -1 before the first. Counted on the network thread, in packet order. */
	@Volatile
	private var ticksLeft = -1

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(TimerElement(), NucleusQol.module, setting)
	}

	/** The server's time packet, once a second. Network thread. */
	@JvmStatic
	fun onSecond() {
		ticksLeft = SECOND_TICKS
		beats++
		if (recording) events += Event(ServerTicks.total, "beat (time packet)")
	}

	/** Network thread, after [ServerTicks] counted it. */
	@JvmStatic
	fun onServerTick() {
		if (ticksLeft > 0) ticksLeft--
	}

	/** A sidebar line set. Network thread. */
	@JvmStatic
	fun onScoreboardLine(prefix: String, suffix: String) {
		if (!recording) return
		events += Event(ServerTicks.total, "sidebar '${(prefix + suffix).replace(FORMATTING, "")}'")
	}

	/** Beats since the game started, for timers that run in Hypixel's seconds. */
	@Volatile
	var beats = 0L
		private set

	/** Ticks to the next beat, 0 to 20; 20 before the first. */
	val ticksToBeat: Int get() = if (ticksLeft < 0) SECOND_TICKS else ticksLeft

	/** Ticks since the last beat, for stamping other recordings; -1 before the first. */
	val phase: Int get() = if (ticksLeft < 0) -1 else SECOND_TICKS - ticksLeft

	private val shown: Boolean
		get() = NucleusQol.module.enabled && setting.value && SkyblockLocation.inCrystalHollows && ticksLeft >= 0

	private class TimerElement : HudElement("hollows_tick_timer", "Hollows Tick Timer", 0.49, 0.56) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int = BOX_WIDTH
		override val height: Int = BOX_HEIGHT

		override fun isVisible(): Boolean = shown

		override fun showInEditor(): Boolean = NucleusQol.module.enabled && setting.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, ticksLeft.coerceAtLeast(0))

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, SECOND_TICKS)

		private fun draw(context: GuiGraphicsExtractor, ticks: Int) {
			val text = ticks.toString()
			val color = when {
				ticks > 10 -> GREEN
				ticks > 5 -> GOLD
				else -> RED
			}
			context.text(font, text, (BOX_WIDTH - font.width(text)) / 2, (BOX_HEIGHT - font.lineHeight) / 2 + 1, color)
		}
	}

	private const val BOX_WIDTH = 20
	private const val BOX_HEIGHT = 10
	private const val GREEN = 0xFF55FF55.toInt()
	private const val GOLD = 0xFFFFAA00.toInt()
	private const val RED = 0xFFFF5555.toInt()

	private val FORMATTING = Regex("§.")

	// ---- Recording ------------------------------------------------------------------------------
	//
	// `/cryptic debug clock`: every beat, sidebar line, chat and action-bar line, area crossing and
	// change to your effects or speed, each with its server tick, into config/cryptic/hollows-clock.log.

	@Volatile
	var recording = false
		private set

	private class Event(val tick: Long, val text: String)

	private val events = ConcurrentLinkedQueue<Event>()
	private var writer: BufferedWriter? = null
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("hollows-clock.log")
	private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

	private val inside = HashMap<HollowsMap.Place, Boolean>()
	private var effects: String? = null
	private var speed = Double.NaN
	private var lastActionBar: String? = null
	private var lastBeatTick = -1L

	private val WATCHED = listOf(HollowsMap.Place.KHAZAD_DUM, HollowsMap.Place.JUNGLE_TEMPLE, HollowsMap.Place.MINES_OF_DIVAN)

	fun toggleRecording(): List<String> {
		recording = !recording
		if (!recording) {
			flush()
			write("Recording stopped.")
			writer?.close()
			writer = null
			return listOf("§7Clock recording §coff§7. It's in §fconfig/cryptic/hollows-clock.log§7.")
		}
		runCatching {
			Files.createDirectories(file.parent)
			writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
		}.onFailure { Cryptic.LOGGER.warn("Could not open the clock log", it) }
		events.clear()
		inside.clear()
		effects = null
		speed = Double.NaN
		lastActionBar = null
		write("Recording started.")
		return listOf(
			"§7Clock recording §aon§7, into §fconfig/cryptic/hollows-clock.log§7. Step in and out of the Jungle Temple and",
			"§7Khazad-dûm a few times, some quick (in and back out) and some slow; §f/cryptic debug clock §7again stops it.",
		)
	}

	private fun write(line: String) {
		runCatching {
			writer?.apply {
				write("${LocalTime.now().format(TIME)} $line")
				newLine()
			}
		}
	}

	private fun flush() {
		while (true) {
			val e = events.poll() ?: break
			if (e.text.startsWith("beat")) {
				val gap = if (lastBeatTick < 0) "" else " (${e.tick - lastBeatTick} ticks after the last)"
				lastBeatTick = e.tick
				write("t${e.tick} ${e.text}$gap")
			} else {
				write("t${e.tick} phase ${phaseAt(e.tick)} ${e.text}")
			}
		}
		runCatching { writer?.flush() }
	}

	private fun phaseAt(tick: Long): String = if (lastBeatTick < 0) "?" else (tick - lastBeatTick).toString()

	/** Client tick: crossings, effects and speed, then everything queued goes to the file. */
	fun tick(client: Minecraft) {
		if (!recording) return
		val player = client.player
		if (player != null && SkyblockLocation.inCrystalHollows) {
			val now = ServerTicks.total
			val feet = player.position()
			for (place in WATCHED) {
				val box = HollowsBounds.inside(place) ?: continue
				val isIn = box.contains(feet)
				if (inside[place] != isIn) {
					if (inside[place] != null) {
						events += Event(now, "${if (isIn) "entered" else "left"} ${place.title} at %.3f %.3f %.3f".format(feet.x, feet.y, feet.z))
					}
					inside[place] = isIn
				}
			}
			val effectsNow = player.activeEffects.sortedBy { it.descriptionId }.joinToString { describe(it) }
			if (effectsNow != effects) {
				if (effects != null) events += Event(now, "effects: ${effectsNow.ifEmpty { "none" }}")
				effects = effectsNow
			}
			val speedNow = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
			if (speedNow != speed) {
				if (!speed.isNaN()) events += Event(now, "movement speed %.4f -> %.4f".format(speed, speedNow))
				speed = speedNow
			}
		}
		flush()
	}

	private fun describe(effect: MobEffectInstance): String =
		"${effect.effect.registeredName} ${effect.amplifier + 1} (${effect.duration} ticks)"

	/** Chat and the action bar. Client thread. */
	fun onChat(content: Component, overlay: Boolean) {
		if (!recording) return
		val text = content.string.replace(FORMATTING, "").trim()
		if (overlay) {
			if (text == lastActionBar) return
			lastActionBar = text
			events += Event(ServerTicks.total, "action bar: $text")
		} else if (text.isNotEmpty()) {
			events += Event(ServerTicks.total, "chat: $text")
		}
	}
}
