package imicro.cryptic.feature

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TabModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import java.util.Locale
import java.util.Optional
import kotlin.math.abs

/**
 * The Rift, in three tabs.
 *
 * Ubik's game can be played once each time you enter: Hypixel starts a new timeline on entry
 * ("Venturing in the Rift breaches spacetime."), and Ubik turns you away for the rest of that one.
 * The reminder goes out on entry, and stops once the game is played that visit.
 *
 * The Rift time is read off the action bar ("44m 12sф Left"); souls, effigies, the dance steps and
 * the Mirrorverse paths come from the SkyHanni repository (MIT), in `assets/cryptic/rift/rift.json`.
 * Found souls are saved, from Hypixel's lines and from the Rift Guide's Enigma Souls menu.
 */
object Rift {
	private const val SOURCE = "Rift"
	private const val RIFT_AREA = "The Rift"

	/** Entry's own lines come in a burst; the reminder waits for them to pass. */
	private const val ENTRY_DELAY_MILLIS = 4_000L

	private const val LINE_WIDTH = 2f

	// ---- General -------------------------------------------------------------------------------

	private val generalTab = TabModuleSetting("general_tab", "General")

	private val ubikSection = SectionModuleSetting("ubik_section", "Ubik's Cube")

	@JvmField
	val ubikRepeat = SliderModuleSetting(
		id = "ubik_repeat",
		label = "Remind again every",
		unit = "min",
		defaultValue = 0.0,
		min = 0.0,
		max = 30.0,
		step = 1.0,
		description = "Until you've played it, in the Rift and outside it. 0 reminds once: on entry, or when the wait runs out.",
	)

	@JvmField
	val ubikOutside = ToggleModuleSetting(
		id = "ubik_outside",
		label = "Outside the Rift",
		defaultValue = true,
		description = "Also says when Ubik's game is ready again while you're elsewhere on SkyBlock, two hours after you last played it, even if the game was closed in between.",
	)

	@JvmField
	val ubikReminder = ToggleModuleSetting(
		id = "ubik_reminder",
		label = "Ubik's Cube reminder",
		defaultValue = true,
		description = "Reminds you to play Ubik's game when you enter the Rift and it's off cooldown, until you've played it that visit. A notification, or a chat line with notifications off.",
	).withMore(ubikRepeat, ubikOutside)

	@JvmField
	val ubikTips = ToggleModuleSetting(
		id = "ubik_tips",
		label = "Ubik's game tips",
		defaultValue = true,
		description = "When Ubik asks whether to keep your box, says to switch: it wins two times in three. When he gives the last revolution's average, says what half of it is.",
	)

	private val timeSection = SectionModuleSetting("time_section", "Rift time")

	@JvmField
	val timeWarnAt = SliderModuleSetting(
		id = "time_warn_at",
		label = "Warn at",
		unit = "s",
		defaultValue = 60.0,
		min = 10.0,
		max = 600.0,
		step = 10.0,
	)

	@JvmField
	val timeWarning = ToggleModuleSetting(
		id = "time_warning",
		label = "Low Rift time warning",
		defaultValue = true,
		description = "A title and a sound when your Rift time runs down to the chosen seconds. Getting time back above it re-arms it.",
	).withMore(timeWarnAt)

	// ---- Souls and effigies --------------------------------------------------------------------

	private val soulsTab = TabModuleSetting("souls_tab", "Souls & Effigies")

	private val soulSection = SectionModuleSetting("soul_section", "Enigma Souls")

	private const val SOULS_ALL = 0
	private const val SOULS_NEAREST = 1

	@JvmField
	val soulsShown = DropdownModuleSetting(
		id = "souls_shown",
		label = "Show",
		options = listOf("Every missing soul", "The nearest missing soul"),
		defaultIndex = SOULS_ALL,
	)

	@JvmField
	val soulColor = ColorModuleSetting("soul_color", "Colour", defaultRgb = 0xAA00AA, supportsAlpha = true, defaultAlpha = 0x60)

	@JvmField
	val soulTracer = ToggleModuleSetting(
		id = "soul_tracer",
		label = "Line to the nearest",
		defaultValue = false,
	)

	@JvmField
	val soulWaypoints = ToggleModuleSetting(
		id = "soul_waypoints",
		label = "Enigma Soul waypoints",
		defaultValue = true,
		description = "Marks the souls you haven't found. Opening the Rift Guide's Enigma Souls menu, or finding one, keeps the list right.",
	).withMore(soulsShown, soulColor, soulTracer)

	private val resetSouls = ButtonModuleSetting("reset_souls", "Forget found souls", action = {
		found.clear()
		saveFound()
		Toasts.show(SOURCE, "Every Enigma Soul shows again", color = Toasts.Colors.RIFT)
	})

	private val effigySection = SectionModuleSetting("effigy_section", "Blood Effigies")

	@JvmField
	val effigiesOnlyUp = ToggleModuleSetting(
		id = "effigies_only_up",
		label = "Only ones to break",
		defaultValue = false,
		description = "Leaves out the effigies that are respawning.",
	)

	@JvmField
	val effigyWaypoints = ToggleModuleSetting(
		id = "effigy_waypoints",
		label = "Blood Effigy waypoints",
		defaultValue = true,
		description = "Marks the six Blood Effigies in Stillgore Château: red to break, grey while respawning, with the time left.",
	).withMore(effigiesOnlyUp)

	// ---- Mirrorverse ---------------------------------------------------------------------------

	private val mirrorTab = TabModuleSetting("mirror_tab", "Mirrorverse")

	@JvmField
	val danceSteps = SliderModuleSetting(
		id = "dance_steps",
		label = "Steps shown",
		defaultValue = 3.0,
		min = 1.0,
		max = 8.0,
		step = 1.0,
	)

	@JvmField
	val danceHelper = ToggleModuleSetting(
		id = "dance_helper",
		label = "Dance room helper",
		defaultValue = true,
		description = "Lists the coming dance moves while you're in the dance room, with the time left on the one now. It follows Hypixel's beat and starts over when you fail.",
	).withMore(danceSteps)

	private val parkourSection = SectionModuleSetting("parkour_section", "Parkour")

	@JvmField
	val lavaMaze = ToggleModuleSetting(
		id = "lava_maze",
		label = "Lava maze path",
		defaultValue = true,
		description = "Draws the way through the Mirrorverse lava maze, and its shortcuts.",
	)

	@JvmField
	val upsideDown = ToggleModuleSetting(
		id = "upside_down",
		label = "Upside-down parkour path",
		defaultValue = true,
		description = "Draws the upside-down parkour's jumps, and its shortcuts.",
	)

	@JvmField
	val tubulator = ToggleModuleSetting(
		id = "tubulator",
		label = "Tubulator path",
		defaultValue = true,
		description = "Draws the Tubulator's climb.",
	)

	private val anyPath: () -> Boolean = { lavaMaze.value || upsideDown.value || tubulator.value }

	@JvmField
	val pathColor = ColorModuleSetting("path_color", "Path", defaultRgb = 0x55FF55, visibleIf = anyPath)

	@JvmField
	val nextColor = ColorModuleSetting("next_color", "Next block", defaultRgb = 0xFFFF55, supportsAlpha = true, defaultAlpha = 0x60, visibleIf = anyPath)

	@JvmField
	val shortcutColor = ColorModuleSetting("shortcut_color", "Shortcuts", defaultRgb = 0xFF55FF, visibleIf = anyPath)

	@JvmField
	val pathAhead = SliderModuleSetting(
		id = "path_ahead",
		label = "Blocks ahead",
		defaultValue = 6.0,
		min = 1.0,
		max = 40.0,
		step = 1.0,
		visibleIf = anyPath,
	)

	@JvmField
	val module = Module(
		id = "rift",
		name = "Rift",
		description = "Ubik, souls, Mirrorverse",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			generalTab,
			ubikSection, ubikReminder, ubikTips,
			timeSection, timeWarning,
			soulsTab,
			soulSection, soulWaypoints, resetSouls,
			effigySection, effigyWaypoints,
			mirrorTab,
			danceHelper,
			parkourSection, lavaMaze, upsideDown, tubulator, pathColor, nextColor, shortcutColor, pathAhead,
		),
	)

	val needsSkyblock: Boolean get() = module.enabled

	private val inRift: Boolean get() = module.enabled && SkyblockLocation.area == RIFT_AREA

	// ---- Data ----------------------------------------------------------------------------------

	private class Soul(val name: String, val area: String, val pos: Vec3)

	/** A Mirrorverse path: the blocks in order, and shortcuts as pairs of indices. */
	private class Path(val points: List<Vec3>, val shortcuts: List<Pair<Int, Int>>, val bounds: AABB) {
		var reached = -1
	}

	private var souls: List<Soul> = emptyList()
	private var effigies: List<Vec3> = emptyList()
	private var dance: List<String> = emptyList()
	private var lavaPath: Path? = null
	private var upsidePath: Path? = null
	private var tubePath: Path? = null

	private fun vec(array: JsonArray) = Vec3(array[0].asDouble, array[1].asDouble, array[2].asDouble)

	private fun path(json: JsonObject): Path {
		val points = json.getAsJsonArray("points").map { vec(it.asJsonArray) }
		val shortcuts = json.getAsJsonArray("shortcuts").map { it.asJsonArray.let { pair -> pair[0].asInt to pair[1].asInt } }
		var box = AABB(points.first(), points.first())
		for (point in points) box = box.minmax(AABB(point, point))
		return Path(points, shortcuts, box.inflate(6.0))
	}

	private fun load() {
		try {
			val stream = Rift::class.java.getResourceAsStream("/assets/cryptic/rift/rift.json") ?: error("missing")
			val root = stream.reader().use { JsonParser.parseReader(it).asJsonObject }
			souls = root.getAsJsonArray("souls").map {
				val soul = it.asJsonObject
				Soul(soul.get("name").asString, soul.get("area").asString, vec(soul.getAsJsonArray("pos")))
			}
			effigies = root.getAsJsonArray("effigies").map { vec(it.asJsonArray) }
			dance = root.getAsJsonArray("dance").map { it.asString }
			lavaPath = path(root.getAsJsonObject("lavaMaze"))
			upsidePath = path(root.getAsJsonObject("upsideDown"))
			tubePath = path(root.getAsJsonObject("tubulator"))
		} catch (error: Exception) {
			Cryptic.LOGGER.error("Rift data could not be read", error)
		}
	}

	// ---- Found souls ---------------------------------------------------------------------------

	private val found = HashSet<String>()

	private val foundFile get() = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("cryptic").resolve("rift-souls.txt")

	private fun loadFound() {
		runCatching { java.nio.file.Files.readAllLines(foundFile) }.getOrNull()?.let { lines ->
			found.clear()
			lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { found += it }
		}
	}

	private fun saveFound() {
		runCatching {
			java.nio.file.Files.createDirectories(foundFile.parent)
			java.nio.file.Files.write(foundFile, found.sorted())
		}
	}

	private fun markFound(name: String) {
		if (found.add(name)) saveFound()
	}

	/** The Rift Guide's Enigma Souls menu: "Enigma: Tough Bark", and "✖ Not completed yet!" in a missing one's lore. */
	private fun readGuide(client: Minecraft) {
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
		if (!screen.title.string.replace(FORMATTING, "").endsWith("Enigma Souls")) return
		var changed = false
		for (slot in screen.menu.slots) {
			if (slot.container is Inventory) continue
			val stack = slot.item
			if (stack.isEmpty) continue
			val name = GUIDE_NAME.matchEntire(stack.hoverName.string.replace(FORMATTING, "").trim())?.groupValues?.get(1) ?: continue
			val missing = stack.get(DataComponents.LORE)?.lines()?.any { it.string.replace(FORMATTING, "").contains(NOT_COMPLETED) } == true
			changed = changed or if (missing) found.remove(name) else found.add(name)
		}
		if (changed) saveFound()
	}

	private val GUIDE_NAME = Regex("""^(?:✔ )?Enigma: (.+)$""")
	private const val NOT_COMPLETED = "Not completed yet!"

	// ---- Chat ----------------------------------------------------------------------------------

	private val FORMATTING = Regex("§.")
	private const val UBIK = "[NPC] Ubik Von Neumann: "
	private const val ENTERED = "Venturing in the Rift breaches spacetime."

	/** Lines that mean this visit's game is played, or under way. */
	private val PLAYED = listOf(
		"The game awaits your strategic touch.",
		"Congratulations, you got the IQ Point.",
		"Alas, the reward was not within your grasp this time.",
		ALREADY_PLAYED,
	)
	private const val ALREADY_PLAYED = "You've already completed my game."

	/** Facts from SkyHanni: the game's two-hour wait, and the line that says what's left of it. */
	private const val UBIK_WAIT_MILLIS = 2L * 60 * 60 * 1000
	private val UBIK_WAIT = Regex("""You need to wait (.+) before you can play again""")
	/** The cube's split-or-steal match ends with this; it starts the two-hour wait. */
	private val CUBE_MATCH_END = Regex("""^You earned [\d,]+ Motes in this match!$""")

	/** When Ubik's game opens again, or 0 when that's unknown; and when that was last said outside the Rift. */
	private var ubikReadyAt = 0L
	private var ubikSaidAt = 0L
	private val ubikFile get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("rift-ubik.txt")
	private const val KEEP_OR_SWITCH = "Will you keep the box you opened, or will you switch to the other?"
	private val REVOLUTION = Regex("""^The previous revolution had [\d,]+ participants with an average choice of ([\d.]+)\.$""")

	private val SOUL_FOUND = setOf("SOUL! You unlocked an Enigma Soul!", "You have already found that Enigma Soul!")
	private val LAVA_RESET = setOf("EEK! THE LAVA OOFED YOU!", "OH NO! THE LAVA OOFED YOU BACK TO THE START!")

	/** "44m 12sф Left" on the action bar. */
	private val RIFT_TIME = Regex("""((?:\d+[hms]\s?)+)ф""")
	private val TIME_PART = Regex("""(\d+)([hms])""")

	/** When this visit began, or 0 outside the Rift; and whether Ubik's game is done in it. */
	private var enteredAt = 0L
	private var played = false
	private var remindedAt = 0L

	/** The Rift time last read, and whether the warning has gone out for this dip. */
	private var timeLeft = -1
	private var warned = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
		loadFound()
		runCatching {
			val saved = java.nio.file.Files.readString(ubikFile).trim().lines()
			ubikReadyAt = saved[0].trim().toLong()
			ubikSaidAt = saved.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
		}
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		Hud.register(DanceElement())
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (!module.enabled) return
		val text = message.string.replace(FORMATTING, "").trim()
		if (overlay) {
			onActionBar(text)
			return
		}
		if (text == ENTERED) {
			enteredAt = System.currentTimeMillis()
			played = false
			remindedAt = 0L
			return
		}
		if (text in SOUL_FOUND) {
			nearestSoul(6.0, missingOnly = false)?.let { markFound(it.name) }
			return
		}
		if (text in LAVA_RESET) {
			lavaPath?.reached = -1
			upsidePath?.reached = -1
			return
		}
		UBIK_WAIT.find(text)?.let { match ->
			val seconds = TIME_PART.findAll(match.groupValues[1]).sumOf { part ->
				part.groupValues[1].toLong() * when (part.groupValues[2]) {
					"h" -> 3600L
					"m" -> 60L
					else -> 1L
				}
			}
			if (seconds > 0) setUbikReady(System.currentTimeMillis() + seconds * 1000)
			return
		}
		if (CUBE_MATCH_END.matches(text)) {
			played = true
			setUbikReady(System.currentTimeMillis() + UBIK_WAIT_MILLIS)
			return
		}
		if (!text.startsWith(UBIK)) return
		val said = text.removePrefix(UBIK)
		if (PLAYED.any { said.startsWith(it) || said.endsWith(it) }) {
			played = true
			if (!said.endsWith(ALREADY_PLAYED)) setUbikReady(System.currentTimeMillis() + UBIK_WAIT_MILLIS)
			// A wait that has run out is wrong; better unknown than reminding about a game already played.
			else if (ubikReadyAt <= System.currentTimeMillis()) setUbikReady(0L)
		}
		if (!ubikTips.value) return
		if (said == KEEP_OR_SWITCH) {
			title("§aSwitch!", "§7Switching wins 2 in 3")
			return
		}
		REVOLUTION.matchEntire(said)?.groupValues?.get(1)?.toDoubleOrNull()?.let { average ->
			val half = String.format(Locale.ROOT, "%.2f", average / 2)
			Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
				Component.literal("§8[Cryptic] §dUbik: §7half of that average is §f$half§7. Winners are the closest to half of each revolution's own average."),
			)
		}
	}

	private fun onActionBar(text: String) {
		if (!inRift) return
		val match = RIFT_TIME.find(text) ?: return
		var seconds = 0
		for (part in TIME_PART.findAll(match.groupValues[1])) {
			val value = part.groupValues[1].toIntOrNull() ?: continue
			seconds += value * when (part.groupValues[2]) {
				"h" -> 3600
				"m" -> 60
				else -> 1
			}
		}
		timeLeft = seconds
		if (!timeWarning.value) return
		val line = timeWarnAt.value.toInt()
		if (seconds > line) {
			warned = false
		} else if (!warned && seconds > 0) {
			warned = true
			title("§cRift time low", "§f${seconds}s §7left")
			Minecraft.getInstance().player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.5f)
		}
	}

	// ---- Dance room ----------------------------------------------------------------------------

	/** The dance room, as SkyHanni bounds it. */
	private val DANCE_ROOM = AABB(-267.0, 32.0, -110.0, -260.0, 40.0, -102.0)

	/** Each step lasts a second from Hypixel's beat. */
	private const val STEP_TICKS = 20

	private var danceIndex = 0
	private var stepTicks = 0
	private var inDanceRoom = false

	/** Hypixel's beat (a low note) moves to the next step; a burp or a high level-up is a fail. */
	@JvmStatic
	fun onSound(packet: ClientboundSoundPacket) {
		val client = Minecraft.getInstance()
		if (!client.isSameThread || !module.enabled || !danceHelper.value || !inDanceRoom) return
		val sound = packet.getSound().value().location().path
		val pitch = packet.pitch
		val volume = packet.volume
		when {
			sound == "block.note_block.bass" && volume == 1f && (near(pitch, 0.6984127f) || near(pitch, 0.52380955f)) -> {
				danceIndex++
				stepTicks = STEP_TICKS
			}
			(sound == "entity.player.burp" && volume == 0.8f) || (sound == "entity.player.levelup" && near(pitch, 1.8412699f) && volume == 1f) -> {
				danceIndex = 0
				stepTicks = 0
			}
		}
	}

	private fun near(a: Float, b: Float) = abs(a - b) < 0.001f

	private class DanceElement : HudElement("rift_dance", "Dance Room", 0.42, 0.25) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("> Sneak Jump Punch 0.95s") + 4
		override val height: Int get() = font.lineHeight * danceSteps.value.toInt()

		override fun isVisible(): Boolean = module.enabled && danceHelper.value && inDanceRoom && dance.isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled && danceHelper.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, danceIndex, stepTicks)

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 13, 12)

		private fun draw(context: GuiGraphicsExtractor, index: Int, ticks: Int) {
			for (row in 0 until danceSteps.value.toInt()) {
				val step = dance.getOrNull(index + row) ?: break
				val words = step.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
				val line = if (row == 0) {
					val left = if (ticks > 0) String.format(Locale.ROOT, " §7%.2fs", ticks / 20.0) else ""
					"§a> $words$left"
				} else {
					"${if (row == 1) "§e" else "§7"}  $words"
				}
				context.text(font, line, 0, row * font.lineHeight, -1)
			}
		}
	}

	// ---- Tick ----------------------------------------------------------------------------------

	fun tick(client: Minecraft) {
		val player = client.player
		if (!module.enabled || player == null) return
		val now = System.currentTimeMillis()

		inDanceRoom = inRift && DANCE_ROOM.contains(player.position())
		if (!inDanceRoom) {
			danceIndex = 0
			stepTicks = 0
		} else if (stepTicks > 0) {
			stepTicks--
		}

		if (inRift) {
			readGuide(client)
			for (path in listOfNotNull(lavaPath, upsidePath, tubePath)) advance(path, player.position())
			effigyStates = if (effigyWaypoints.value) readEffigies() else emptyList()
		} else {
			timeLeft = -1
		}

		remindOutside(now)
		if (enteredAt == 0L) return
		// The area read lags the entry line, so it only counts once the burst has passed.
		if (now - enteredAt < ENTRY_DELAY_MILLIS) return
		// Out of the Rift, the visit is over; a new one starts with its entry line.
		if (SkyblockLocation.area != RIFT_AREA) {
			enteredAt = 0L
			return
		}
		if (played || !ubikReminder.value) return
		// Still waiting out the last game (saved, so it holds across restarts); reminds once it's over.
		if (ubikReadyAt > now) return
		val repeat = ubikRepeat.value.toLong() * 60_000L
		val due = remindedAt == 0L || (repeat > 0 && now - remindedAt >= repeat)
		if (!due) return
		remindedAt = now
		Toasts.show(SOURCE, "§dUbik's Cube §7is ready: play it this visit", life = 10.0, color = Toasts.Colors.RIFT)
	}

	private fun setUbikReady(at: Long) {
		ubikReadyAt = at
		saveUbik()
	}

	private fun saveUbik() {
		runCatching {
			java.nio.file.Files.createDirectories(ubikFile.parent)
			java.nio.file.Files.writeString(ubikFile, "$ubikReadyAt\n$ubikSaidAt")
		}.onFailure { Cryptic.LOGGER.warn("Could not save Ubik's timer", it) }
	}

	/**
	 * Says when the game has opened again, unless you're in the Rift to see it for yourself; then
	 * again every [ubikRepeat] minutes until it's played.
	 */
	private fun remindOutside(now: Long) {
		if (ubikReadyAt == 0L || now < ubikReadyAt || inRift || !SkyblockLocation.onSkyblock) return
		if (!ubikReminder.value || !ubikOutside.value) return
		val repeat = ubikRepeat.value.toLong() * 60_000L
		val due = ubikSaidAt < ubikReadyAt || (repeat > 0 && now - ubikSaidAt >= repeat)
		if (!due) return
		ubikSaidAt = now
		saveUbik()
		Toasts.show(SOURCE, "§dUbik's Cube §7is ready in the Rift", life = 10.0, color = Toasts.Colors.RIFT)
	}

	/** The furthest point along [path] you've stood on; leaving its area starts it over. */
	private fun advance(path: Path, at: Vec3) {
		if (!path.bounds.contains(at)) {
			path.reached = -1
			return
		}
		for (index in path.points.indices.reversed()) {
			if (index <= path.reached) break
			val point = path.points[index]
			val dx = at.x - (point.x + 0.5)
			val dz = at.z - (point.z + 0.5)
			if (dx * dx + dz * dz <= 1.0 && abs(at.y - (point.y + 1)) <= 1.5) {
				path.reached = index
				break
			}
		}
	}

	// ---- Effigies ------------------------------------------------------------------------------

	/** Each effigy's colour on the sidebar: '7' standing, 'c' broken. Empty when the line isn't there. */
	private var effigyStates: List<Char> = emptyList()

	private val EFFIGY_RESPAWN = Regex("""^Respawn (.+?) \(or click!\)$""")

	private fun readEffigies(): List<Char> {
		val scoreboard = Minecraft.getInstance().level?.scoreboard ?: return emptyList()
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
		for (entry in scoreboard.listPlayerScores(objective)) {
			val team = scoreboard.getPlayersTeam(entry.owner())
			val line = legacy(PlayerTeam.formatNameForTeam(team, entry.ownerName()))
			if (!line.replace(FORMATTING, "").trim().startsWith("Effigies:")) continue
			val colours = ArrayList<Char>()
			var colour = 'f'
			var index = 0
			while (index < line.length) {
				val ch = line[index]
				if (ch == '§' && index + 1 < line.length) {
					colour = line[index + 1]
					index += 2
					continue
				}
				if (ch == '⧯') colours += colour
				index++
			}
			return colours
		}
		return emptyList()
	}

	/** A component as legacy text, its styles' colours written as § codes, so either kind can be read. */
	private fun legacy(component: Component): String {
		val out = StringBuilder()
		component.visit({ style, piece ->
			style.color?.let { color ->
				ChatFormatting.entries.firstOrNull { TextColor.fromLegacyFormat(it) == color }?.let { out.append(it.toString()) }
			}
			out.append(piece)
			Optional.empty<Unit>()
		}, Style.EMPTY)
		return out.toString()
	}

	// ---- Rendering -----------------------------------------------------------------------------

	private fun nearestSoul(within: Double, missingOnly: Boolean): Soul? {
		val at = Minecraft.getInstance().player?.position() ?: return null
		return souls.filter { !missingOnly || it.name !in found }
			.filter { it.pos.distanceTo(at) <= within }
			.minByOrNull { it.pos.distanceToSqr(at) }
	}

	private fun render(context: LevelRenderContext) {
		if (!inRift) return
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val camera = client.gameRenderer.mainCamera()

		fun box(at: Vec3, argb: Int, phase: Boolean = true) = WorldRender.drawBox(
			poseStack = poseStack, collector = collector,
			minX = at.x, minY = at.y, minZ = at.z, maxX = at.x + 1, maxY = at.y + 1, maxZ = at.z + 1,
			outlineArgb = argb or 0xFF000000.toInt(), fillArgb = argb,
			outline = true, fill = argb ushr 24 != 0xFF, phase = phase, lineWidth = LINE_WIDTH,
		)

		fun label(text: String, at: Vec3, scale: Float = 1.5f) = WorldRender.drawText(
			poseStack = poseStack, collector = collector, orientation = camera.rotation(),
			text = Component.literal(text), x = at.x + 0.5, y = at.y + 1.6, z = at.z + 0.5,
			scale = scale, seeThrough = true, centered = true,
		)

		if (soulWaypoints.value) {
			val missing = souls.filter { it.name !in found }
			val nearest = missing.minByOrNull { it.pos.distanceToSqr(player.position()) }
			val shown = if (soulsShown.selectedIndex == SOULS_NEAREST) listOfNotNull(nearest) else missing
			for (soul in shown) {
				box(soul.pos, soulColor.argb)
				val distance = soul.pos.distanceTo(player.position()).toInt()
				label("§d${soul.name} §7${distance}m", soul.pos)
			}
			if (soulTracer.value && nearest != null) {
				WorldRender.drawTracer(
					poseStack = poseStack, collector = collector,
					x = nearest.pos.x + 0.5, y = nearest.pos.y + 0.5, z = nearest.pos.z + 0.5,
					argb = soulColor.argb or 0xFF000000.toInt(), lineWidth = LINE_WIDTH, phase = true,
				)
			}
		}

		val level = client.level
		if (level != null && effigyWaypoints.value && effigies.any { it.distanceTo(player.position()) < 120 }) {
			effigies.forEachIndexed { index, at ->
				val state = effigyStates.getOrNull(index)
				val broken = state == 'c'
				if (broken && effigiesOnlyUp.value) return@forEachIndexed
				val respawn = if (broken) {
					level.getEntitiesOfClass(ArmorStand::class.java, AABB(at, at).inflate(3.0, 6.0, 3.0)) { it.customName != null }
						.firstNotNullOfOrNull { EFFIGY_RESPAWN.matchEntire(it.customName!!.string.replace(FORMATTING, "").trim())?.groupValues?.get(1) }
				} else {
					null
				}
				val text = when {
					state == null -> "§7Effigy ${index + 1}"
					broken -> "§7Effigy ${index + 1} §8· §e${respawn ?: "respawning"}"
					else -> "§cBreak effigy ${index + 1}"
				}
				box(at, if (broken) 0x40AAAAAA else 0x60FF5555)
				label(text, at)
			}
		}

		if (lavaMaze.value) lavaPath?.let { renderPath(it, poseStack, collector) }
		if (upsideDown.value) upsidePath?.let { renderPath(it, poseStack, collector) }
		if (tubulator.value) tubePath?.let { renderPath(it, poseStack, collector) }
	}

	private fun renderPath(path: Path, poseStack: com.mojang.blaze3d.vertex.PoseStack, collector: net.minecraft.client.renderer.SubmitNodeCollector) {
		val at = Minecraft.getInstance().player?.position() ?: return
		if (!path.bounds.contains(at)) return
		val from = (path.reached + 1).coerceAtMost(path.points.lastIndex)
		val to = (from + pathAhead.value.toInt()).coerceAtMost(path.points.lastIndex)
		val centres = path.points.subList(maxOf(path.reached, 0), to + 1).map { it.add(0.5, 1.05, 0.5) }
		if (centres.size >= 2) WorldRender.drawLine(poseStack, collector, centres, pathColor.argb or 0xFF000000.toInt(), LINE_WIDTH, phase = true)
		val next = path.points[from]
		WorldRender.drawBox(
			poseStack = poseStack, collector = collector,
			minX = next.x, minY = next.y, minZ = next.z, maxX = next.x + 1, maxY = next.y + 1, maxZ = next.z + 1,
			outlineArgb = nextColor.argb or 0xFF000000.toInt(), fillArgb = nextColor.argb,
			outline = true, fill = true, phase = true, lineWidth = LINE_WIDTH,
		)
		for ((start, end) in path.shortcuts) {
			if (end <= path.reached || start > to) continue
			val a = path.points.getOrNull(start) ?: continue
			val b = path.points.getOrNull(end) ?: continue
			WorldRender.drawLine(poseStack, collector, listOf(a.add(0.5, 1.05, 0.5), b.add(0.5, 1.05, 0.5)), shortcutColor.argb or 0xFF000000.toInt(), LINE_WIDTH, phase = true)
		}
	}

	private fun title(text: String, subtitle: String) {
		val hud = Minecraft.getInstance().gui.hud
		hud.setTimes(0, 40, 10)
		hud.setSubtitle(Component.literal(subtitle))
		hud.setTitle(Component.literal(text))
	}
}
