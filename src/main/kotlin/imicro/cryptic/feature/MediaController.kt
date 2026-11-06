package imicro.cryptic.feature

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/**
 * What's playing on the computer (Spotify, a YouTube tab, anything the Windows volume flyout lists)
 * on the HUD, with play, pause, skip and seek in the pause menu.
 *
 * Windows only: a small PowerShell script (`assets/cryptic/media/media_bridge.ps1`) reads Windows'
 * media sessions and prints changes, one line each, and takes commands on its stdin. It quits
 * when the game closes its stdin. Lines are read on a thread of their own and handed over; only
 * [tick] touches the game.
 */
object MediaController {
	private val WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
	private val COVER: Identifier = Identifier.fromNamespaceAndPath("cryptic", "media_cover")
	private const val COVER_SIZE = 96

	/** After the script fails to start or dies, wait this long before trying again. */
	private const val RETRY_MILLIS = 15_000L

	private const val CORNER_TOP_LEFT = 0
	private const val CORNER_BOTTOM_LEFT = 1
	private const val CORNER_TOP_RIGHT = 2
	private const val CORNER_BOTTOM_CENTRE = 4

	@JvmField
	val hud = ToggleModuleSetting(
		id = "hud",
		label = "Show on HUD",
		defaultValue = true,
		description = "The song or video playing, with its cover and progress. Place it with /cryptic hud.",
	)

	@JvmField
	val hudCover = ToggleModuleSetting(id = "hud_cover", label = "Cover", defaultValue = true, visibleIf = { hud.value })

	@JvmField
	val hudProgress = ToggleModuleSetting(id = "hud_progress", label = "Progress bar", defaultValue = true, visibleIf = { hud.value })

	@JvmField
	val hudWidth = SliderModuleSetting(
		id = "hud_width",
		label = "Width",
		unit = "px",
		defaultValue = 150.0,
		min = 100.0,
		max = 260.0,
		step = 10.0,
		visibleIf = { hud.value },
	)

	@JvmField
	val hideWhenPaused = ToggleModuleSetting(
		id = "hide_when_paused",
		label = "Hide while paused",
		defaultValue = false,
		description = "Shows the HUD only while something is playing.",
		visibleIf = { hud.value },
	)

	@JvmField
	val background = ColorModuleSetting(
		id = "background",
		label = "Background",
		defaultRgb = 0x000000,
		supportsAlpha = true,
		defaultAlpha = 0x70,
	)

	@JvmField
	val accent = ColorModuleSetting(
		id = "accent",
		label = "Progress color",
		defaultRgb = 0x36865B,
	)

	@JvmField
	val pauseMenu = ToggleModuleSetting(
		id = "pause_menu",
		label = "Controls in the pause menu",
		defaultValue = true,
		description = "Play, pause, skip back and forward, and click the bar to jump, from the Esc menu.",
	)

	@JvmField
	val pauseCorner = DropdownModuleSetting(
		id = "pause_corner",
		label = "Place",
		options = listOf("Top left", "Bottom left", "Top right", "Bottom right", "Bottom centre"),
		defaultIndex = CORNER_BOTTOM_CENTRE,
		visibleIf = { pauseMenu.value },
	)

	@JvmField
	val pauseCover = ToggleModuleSetting(
		id = "pause_cover",
		label = "Cover",
		defaultValue = true,
		description = "The album art or video thumbnail beside the controls.",
		visibleIf = { pauseMenu.value },
	)

	@JvmField
	val module = Module(
		id = "media_controller",
		name = "Media Controller",
		description = "Control Spotify, YouTube…",
		category = ModuleCategory.GENERAL,
		settings = listOf(
			hud.withMore(hudCover, hudProgress, hudWidth, hideWhenPaused),
			pauseMenu.withMore(pauseCorner, pauseCover),
			background, accent,
		),
	)

	/** One session as the script reported it. */
	private class Media(
		val app: String,
		val title: String,
		val artist: String,
		val playing: Boolean,
		val position: Long,
		val end: Long,
		/** When [position] was true, on the wall clock. */
		val at: Long,
		val canSeek: Boolean,
		val canNext: Boolean,
		val canPrev: Boolean,
	) {
		fun positionNow(now: Long): Long {
			val moved = if (playing) (now - at).coerceAtLeast(0) else 0
			return if (end > 0) (position + moved).coerceIn(0, end) else position + moved
		}

		fun with(playing: Boolean, position: Long, now: Long) =
			Media(app, title, artist, playing, position, end, now, canSeek, canNext, canPrev)
	}

	// Written by the reader thread, read on the client thread.
	@Volatile
	private var reported: Media? = null

	@Volatile
	private var reportedAt = 0L

	@Volatile
	private var pendingCover: ByteArray? = null

	@Volatile
	private var coverCleared = false

	@Volatile
	private var unsupported: String? = null

	/** What's shown; [reported] with the player's own clicks applied until the script catches up. */
	private var media: Media? = null
	private var seenReportAt = 0L
	private var hasCover = false

	private var process: Process? = null
	private var input: Writer? = null
	private var nextStart = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(MediaElement())
	}

	fun tick(client: Minecraft) {
		val wanted = module.enabled && WINDOWS && unsupported == null
		val running = process?.isAlive == true
		if (wanted && !running && System.currentTimeMillis() >= nextStart) start()
		if (!wanted && process != null) stop()

		if (reportedAt != seenReportAt) {
			seenReportAt = reportedAt
			media = reported
		}
		if (coverCleared) {
			coverCleared = false
			hasCover = false
		}
		pendingCover?.let { bytes ->
			pendingCover = null
			runCatching {
				val image = NativeImage.read(bytes)
				client.textureManager.register(COVER, DynamicTexture({ "Cryptic media cover" }, image))
				hasCover = true
			}.onFailure { Cryptic.LOGGER.warn("Media Controller: unreadable cover", it) }
		}
	}

	// ---- The script ----------------------------------------------------------------------------

	private fun start() {
		nextStart = System.currentTimeMillis() + RETRY_MILLIS
		runCatching {
			val dir = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("media")
			Files.createDirectories(dir)
			val script = dir.resolve("media_bridge.ps1")
			MediaController::class.java.getResourceAsStream("/assets/cryptic/media/media_bridge.ps1").use { stream ->
				Files.write(script, stream!!.readAllBytes())
			}
			val started = ProcessBuilder(
				"powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
			).redirectError(ProcessBuilder.Redirect.DISCARD).start()
			process = started
			input = started.outputStream.writer(StandardCharsets.UTF_8)
			Thread({ read(started) }, "Cryptic media bridge").apply {
				isDaemon = true
				start()
			}
		}.onFailure {
			Cryptic.LOGGER.warn("Media Controller: could not start PowerShell", it)
			process = null
		}
	}

	private fun stop() {
		runCatching { input?.close() }
		process?.destroy()
		process = null
		input = null
		reported = null
		reportedAt = System.nanoTime()
		coverCleared = true
	}

	/** The reader thread: parses each line and hands it over. */
	private fun read(from: Process) {
		val reader = BufferedReader(InputStreamReader(from.inputStream, StandardCharsets.UTF_8))
		runCatching {
			while (true) {
				val line = reader.readLine()?.removePrefix("﻿") ?: break
				when {
					line.startsWith("S ") -> {
						reported = parse(line.substring(2))
						reportedAt = System.nanoTime()
					}
					line == "A -" -> coverCleared = true
					line.startsWith("A ") -> pendingCover = runCatching { Base64.getDecoder().decode(line.substring(2)) }.getOrNull()
					line.startsWith("E unsupported") -> unsupported = line.removePrefix("E unsupported").trim()
					line.startsWith("E ") -> Cryptic.LOGGER.debug("Media Controller: {}", line.substring(2))
				}
			}
		}
	}

	private fun parse(json: String): Media? {
		val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
		if (o.has("none")) return null
		fun JsonObject.text(key: String) = get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
		fun JsonObject.number(key: String) = get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
		fun JsonObject.flag(key: String) = get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
		val title = o.text("title")
		if (title.isEmpty()) return null
		val updated = o.number("updated")
		val now = System.currentTimeMillis()
		return Media(
			app = appName(o.text("app")),
			// Section signs would be read as formatting.
			title = title.replace('§', '?'),
			artist = o.text("artist").replace('§', '?'),
			playing = o.text("status") == "Playing",
			position = o.number("position"),
			end = o.number("end"),
			// Windows dates the position; a date in the future or long gone is a player's bug.
			at = if (updated in (now - 6 * 3_600_000L)..now) updated else now,
			canSeek = o.flag("canSeek"),
			canNext = o.flag("canNext"),
			canPrev = o.flag("canPrev"),
		)
	}

	/** "Spotify.exe" or "308046B0AF4A39CB" to something a player would recognise. */
	private fun appName(id: String): String {
		val lower = id.lowercase()
		return when {
			"spotify" in lower -> "Spotify"
			"brave" in lower -> "Brave"
			"chrome" in lower -> "Chrome"
			"msedge" in lower || "edge" in lower -> "Edge"
			"firefox" in lower || lower == "308046b0af4a39cb" -> "Firefox"
			"opera" in lower -> "Opera"
			"vlc" in lower -> "VLC"
			"music.ui" in lower || "zunemusic" in lower -> "Media Player"
			"applemusic" in lower || "itunes" in lower -> "Apple Music"
			else -> id.substringAfterLast('\\').substringAfterLast('!').removeSuffix(".exe")
		}
	}

	private fun send(command: String) {
		val writer = input ?: return
		runCatching {
			writer.write(command)
			writer.write("\n")
			writer.flush()
		}
	}

	private fun click() =
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))

	// ---- Drawing -------------------------------------------------------------------------------

	private fun time(millis: Long): String {
		val seconds = (millis / 1000).coerceAtLeast(0)
		val hours = seconds / 3600
		return if (hours > 0) {
			String.format("%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
		} else {
			String.format("%d:%02d", seconds / 60, seconds % 60)
		}
	}

	private fun fit(text: String, width: Int): String {
		val font = Minecraft.getInstance().font
		if (font.width(text) <= width) return text
		return font.plainSubstrByWidth(text, (width - font.width("…")).coerceAtLeast(0)).trimEnd() + "…"
	}

	private fun drawCover(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
		if (hasCover) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, COVER, x, y, 0f, 0f, size, size, COVER_SIZE, COVER_SIZE, COVER_SIZE, COVER_SIZE)
		} else {
			graphics.fill(x, y, x + size, y + size, 0xFF2A2A2A.toInt())
			icon(graphics, "\uF001", x.toFloat(), y.toFloat(), size.toFloat(), size * 0.45f, 0xFF808080.toInt()) // music
		}
	}

	/** A Font Awesome glyph, sharp at the GUI scale. */
	private fun icon(graphics: GuiGraphicsExtractor, glyph: String, x: Float, y: Float, box: Float, size: Float, color: Int) {
		val saved = UiText.pixelsPerUnit
		UiText.pixelsPerUnit = Minecraft.getInstance().window.guiScale.toFloat()
		UiText.drawIconCentered(graphics, glyph, x, y, box, size, color)
		UiText.pixelsPerUnit = saved
	}

	private fun bar(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, shown: Media, now: Long) {
		graphics.fill(x, y, x + width, y + height, 0x60FFFFFF)
		if (shown.end > 0) {
			val filled = (width * shown.positionNow(now) / shown.end).toInt().coerceIn(0, width)
			graphics.fill(x, y, x + filled, y + height, accent.argb or (0xFF shl 24))
		}
	}

	private class MediaElement : HudElement("media_controller", "Media Controller", 0.01, 0.6) {
		private val font get() = Minecraft.getInstance().font

		private val cover get() = if (hudCover.value) height - 6 else 0
		private val textLeft get() = if (cover > 0) cover + 7 else 4

		override val width: Int get() = hudWidth.value.toInt()
		override val height: Int get() = if (hudProgress.value) 40 else 26

		override fun isVisible(): Boolean {
			if (!module.enabled || !hud.value || Minecraft.getInstance().player == null) return false
			val shown = media ?: return false
			return shown.playing || !hideWhenPaused.value
		}

		override fun showInEditor(): Boolean = module.enabled && hud.value

		override fun render(context: GuiGraphicsExtractor) {
			media?.let { draw(context, it) }
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			val now = System.currentTimeMillis()
			val shown = media ?: Media(
				"Spotify", if (WINDOWS) "Nothing playing" else "Needs Windows", if (WINDOWS) "Play something to see it here" else "",
				false, 83_000, 241_000, now, true, true, true,
			)
			draw(context, shown)
		}

		private fun draw(context: GuiGraphicsExtractor, shown: Media) {
			val now = System.currentTimeMillis()
			context.fill(0, 0, width, height, background.argb)
			if (cover > 0) drawCover(context, 3, 3, cover)
			val textWidth = width - textLeft - 4
			context.text(font, fit(shown.title, textWidth), textLeft, 4, 0xFFFFFFFF.toInt(), false)
			val sub = listOf(shown.artist, shown.app).filter { it.isNotEmpty() }.joinToString(" · ")
			context.text(font, fit(sub, textWidth), textLeft, 14, 0xFFAAAAAA.toInt(), false)
			if (hudProgress.value) {
				val label = if (shown.end > 0) "${time(shown.positionNow(now))} / ${time(shown.end)}" else if (shown.playing) "Playing" else "Paused"
				val state = if (shown.playing) "" else "❚❚ "
				context.text(font, state + label, textLeft, 24, 0xFF808080.toInt(), false)
				bar(context, textLeft, height - 6, textWidth, 2, shown, now)
			}
		}
	}

	// ---- Pause menu ----------------------------------------------------------------------------

	/** Narrow enough to stay clear of the pause screen's title. */
	private const val CORNER_WIDTH = 170
	private const val CORNER_HEIGHT = 64
	private const val CORNER_COVER = 52

	private const val CENTRE_WIDTH = 240
	private const val CENTRE_HEIGHT = 58
	private const val CENTRE_COVER = 46

	private const val BUTTON = 16

	/** The hotbar, the experience bar, and a row of hearts or hunger over it, plus a gap. */
	private const val ABOVE_HOTBAR = 44

	/** The panel's controls as last drawn, for clicks. */
	private class Hit(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val action: (Double) -> Unit) {
		fun contains(x: Double, y: Double) = x >= x0 && x < x1 && y >= y0 && y < y1
	}

	private var hits: List<Hit> = emptyList()

	private fun panelShown(): Boolean =
		module.enabled && pauseMenu.value && WINDOWS && Minecraft.getInstance().gui.screen() is PauseScreen

	/** The lowest edge of the pause screen's own buttons, so the centred panel sits under them. */
	private fun buttonsBottom(): Int {
		val screen = Minecraft.getInstance().gui.screen() ?: return 0
		var bottom = 0
		for (child in screen.children()) {
			if (child is net.minecraft.client.gui.components.AbstractWidget && child.visible) bottom = maxOf(bottom, child.y + child.height)
		}
		return bottom
	}

	/** Drawn over the pause screen, under its tooltips; from `ScreenMixin`. */
	@JvmStatic
	fun renderPauseMenu(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (!panelShown()) {
			hits = emptyList()
			return
		}
		val corner = pauseCorner.selectedIndex
		val centred = corner == CORNER_BOTTOM_CENTRE
		val width = if (centred) CENTRE_WIDTH else CORNER_WIDTH
		val height = if (centred) CENTRE_HEIGHT else CORNER_HEIGHT
		val margin = 6
		val x = when (corner) {
			CORNER_TOP_LEFT, CORNER_BOTTOM_LEFT -> margin
			CORNER_BOTTOM_CENTRE -> (graphics.guiWidth() - width) / 2
			else -> graphics.guiWidth() - width - margin
		}
		val y = when (corner) {
			CORNER_TOP_LEFT, CORNER_TOP_RIGHT -> margin
			// Just above the hotbar, its hearts and hunger; lower only to stay off the menu's buttons.
			CORNER_BOTTOM_CENTRE -> maxOf(graphics.guiHeight() - height - ABOVE_HOTBAR, buttonsBottom() + 4)
				.coerceAtMost(graphics.guiHeight() - height - 2)
			else -> graphics.guiHeight() - height - margin
		}
		val cover = when {
			!pauseCover.value -> 0
			centred -> CENTRE_COVER
			else -> CORNER_COVER
		}

		graphics.fill(x, y, x + width, y + height, 0xD0101010.toInt())
		graphics.outline(x, y, width, height, 0xFF303030.toInt())
		if (cover > 0) drawCover(graphics, x + 6, y + (height - cover) / 2, cover)
		val left = if (cover > 0) x + 6 + cover + 8 else x + 10
		val right = x + width - (if (centred) 10 else 6)
		val textWidth = right - left
		val font = Minecraft.getInstance().font

		fun line(text: String, lineY: Int, color: Int) {
			val shown = fit(text, textWidth)
			val lineX = if (centred) left + (textWidth - font.width(shown)) / 2 else left
			graphics.text(font, shown, lineX, lineY, color, false)
		}

		val shown = media
		if (shown == null) {
			hits = emptyList()
			line("Nothing playing", y + 10, 0xFFFFFFFF.toInt())
			line("Play something in Spotify or a browser", y + 22, 0xFF808080.toInt())
			return
		}

		val now = System.currentTimeMillis()
		line(shown.title, y + 6, 0xFFFFFFFF.toInt())
		line(listOf(shown.artist, shown.app).filter { it.isNotEmpty() }.joinToString(" · "), y + 16, 0xFFAAAAAA.toInt())

		// The bar: under the text in a corner, between the two times when centred.
		val list = ArrayList<Hit>(4)
		val elapsed = if (shown.end > 0) time(shown.positionNow(now)) else ""
		val total = if (shown.end > 0) time(shown.end) else ""
		val barY: Int
		val barLeft: Int
		val barRight: Int
		if (centred) {
			barY = y + 31
			barLeft = left + font.width(elapsed) + 5
			barRight = right - font.width(total) - 5
			if (shown.end > 0) {
				graphics.text(font, elapsed, left, barY - 3, 0xFF808080.toInt(), false)
				graphics.text(font, total, right - font.width(total), barY - 3, 0xFF808080.toInt(), false)
			}
		} else {
			barY = y + 30
			barLeft = left
			barRight = right
			if (shown.end > 0) {
				graphics.text(font, elapsed, left, barY + 5, 0xFF808080.toInt(), false)
				graphics.text(font, total, right - font.width(total), barY + 5, 0xFF808080.toInt(), false)
			}
		}
		val barWidth = barRight - barLeft
		val seekable = shown.canSeek && shown.end > 0 && barWidth > 0
		val barHovered = seekable && mouseX >= barLeft && mouseX < barRight && mouseY >= barY - 3 && mouseY < barY + 5
		bar(graphics, barLeft, barY, barWidth, if (barHovered) 3 else 2, shown, now)
		if (seekable) {
			list += Hit(barLeft, barY - 3, barRight, barY + 5) { mouse ->
				val target = ((mouse - barLeft) / barWidth * shown.end).toLong().coerceIn(0, shown.end)
				send("seek $target")
				media = shown.with(shown.playing, target, System.currentTimeMillis())
			}
		}

		// Previous, play/pause and next, centred under the bar.
		val buttonsY = y + height - BUTTON - 3
		val centre = left + textWidth / 2
		val buttons = listOf(
			Triple("\uf048", shown.canPrev) { send("prev") }, // backward-step
			Triple(if (shown.playing) "\uf04c" else "\uf04b", true) { // pause / play
				send("toggle")
				media = shown.with(!shown.playing, shown.positionNow(System.currentTimeMillis()), System.currentTimeMillis())
			},
			Triple("\uf051", shown.canNext) { send("next") }, // forward-step
		)
		buttons.forEachIndexed { index, (glyph, enabled, action) ->
			val bx = centre - BUTTON / 2 + (index - 1) * (BUTTON + 6)
			val hovered = enabled && mouseX >= bx && mouseX < bx + BUTTON && mouseY >= buttonsY && mouseY < buttonsY + BUTTON
			if (hovered) graphics.fill(bx, buttonsY, bx + BUTTON, buttonsY + BUTTON, 0x40FFFFFF)
			val color = when {
				!enabled -> 0xFF505050.toInt()
				index == 1 -> 0xFFFFFFFF.toInt()
				else -> 0xFFCCCCCC.toInt()
			}
			icon(graphics, glyph, bx.toFloat(), buttonsY.toFloat(), BUTTON.toFloat(), if (index == 1) 10f else 8f, color)
			if (enabled) list += Hit(bx, buttonsY, bx + BUTTON, buttonsY + BUTTON) { action() }
		}
		hits = list
	}

	/** A left press anywhere; from `MouseHandlerMixin`. True when a control took it. */
	@JvmStatic
	fun onMousePressed(): Boolean {
		if (!panelShown()) return false
		val client = Minecraft.getInstance()
		val x = client.mouseHandler.getScaledXPos(client.window)
		val y = client.mouseHandler.getScaledYPos(client.window)
		val hit = hits.firstOrNull { it.contains(x, y) } ?: return false
		hit.action(x)
		click()
		return true
	}
}
