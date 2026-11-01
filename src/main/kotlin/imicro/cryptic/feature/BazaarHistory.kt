package imicro.cryptic.feature

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.BazaarMarket
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * A chart beside a Bazaar product's page of its prices lately, from Coflnet's public history. Only
 * the product's id is sent. Instant buy is the cheapest sell offer and instant sell the best buy
 * order, so these two lines also show where the orders stand. Skysoft's idea, Cryptic's own code.
 */
object BazaarHistory {
	enum class Range(val title: String, val label: String, val path: String, val axis: String) {
		HOUR("1 hour", "1h", "hour", "HH:mm"),
		DAY("1 day", "1d", "day", "d MMM HH:mm"),
		WEEK("1 week", "1w", "week", "d MMM"),
	}

	class Point(val at: Long, val buy: Double, val sell: Double)

	/** What came back for one product and range; no points when it failed. */
	private class Entry(val points: List<Point>?, val at: Long)

	private const val HISTORY_URL = "https://sky.coflnet.com/api/bazaar/%s/history/%s"
	private const val MAX_AGE_MS = 60_000L
	private const val RETRY_MS = 30_000L
	private const val MAX_ENTRIES = 64

	private const val MIN_WIDTH = 130
	private const val MAX_WIDTH = 240
	private const val GAP = 6
	private const val PADDING = 6
	private const val HEADER = 44
	private const val FOOTER = 14

	private const val BUY_COLOR = 0x55FFFF
	private const val SELL_COLOR = 0xFFAA00
	private const val PANEL_FILL = 0xE0101418.toInt()
	private const val PANEL_EDGE = 0x60FFFFFF
	private const val BUTTON_FILL = 0x30FFFFFF
	private const val BUTTON_HOVER = 0x60FFFFFF
	private const val BUTTON_ON = 0xB0FFAA00.toInt()
	private const val GRID_ABGR = 0x20FFFFFF
	private const val CURSOR = 0x80FFFFFF.toInt()

	private val CHART = Identifier.fromNamespaceAndPath("cryptic", "bazaar_history")

	private val client: Minecraft get() = Minecraft.getInstance()
	private val entries = ConcurrentHashMap<String, Entry>()
	private val fetching = ConcurrentHashMap.newKeySet<String>()

	private fun entry(id: String, range: Range): Entry? {
		val key = "$id|${range.path}"
		val known = entries[key]
		val maxAge = if (known?.points == null) RETRY_MS else MAX_AGE_MS
		if ((known == null || System.currentTimeMillis() - known.at > maxAge) && fetching.add(key)) fetch(key, id, range)
		return known
	}

	private fun fetch(key: String, id: String, range: Range) {
		BazaarMarket.get(HISTORY_URL.format(URLEncoder.encode(id, Charsets.UTF_8), range.path))
			.thenAccept { body ->
				if (entries.size >= MAX_ENTRIES) entries.clear()
				entries[key] = Entry(parse(body), System.currentTimeMillis())
			}
			.exceptionally { error ->
				entries[key] = Entry(entries[key]?.points, System.currentTimeMillis())
				Cryptic.LOGGER.warn("Could not fetch {}'s price history: {}", id, error.message)
				null
			}
			.whenComplete { _, _ -> fetching.remove(key) }
	}

	/** Oldest first. Its times are UTC, written without saying so. */
	private fun parse(body: String): List<Point> = JsonParser.parseString(body).asJsonArray.mapNotNull { element ->
		runCatching {
			val point = element.asJsonObject
			Point(
				LocalDateTime.parse(point.get("timestamp").asString).toInstant(ZoneOffset.UTC).toEpochMilli(),
				point.get("buy").asDouble,
				point.get("sell").asDouble,
			)
		}.getOrNull()
	}.sortedBy { it.at }

	// ---- The chart image ----------------------------------------------------------------------

	/** One image, since a chart of fills would cost hundreds of GUI elements every frame. */
	private class Chart(val points: List<Point>, val width: Int, val height: Int, val guiScale: Int) {
		val from = points.first().at
		val to = points.last().at
		val low: Double
		val high: Double

		init {
			val values = points.flatMap { listOf(it.buy, it.sell) }.filter { it > 0 }
			val least = values.minOrNull() ?: 0.0
			val most = values.maxOrNull() ?: 1.0
			val margin = (most - least).coerceAtLeast(most * 0.002) * 0.08
			low = least - margin
			high = most + margin
		}

		fun share(at: Long): Double = (at - from).toDouble() / (to - from).coerceAtLeast(1)
	}

	private var chart: Chart? = null

	private fun abgr(rgb: Int): Int =
		(0xFF shl 24) or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

	private fun bake(chart: Chart) {
		val image = NativeImage(chart.width, chart.height, true)
		for (x in 0 until chart.width) for (y in 0 until chart.height) image.setPixelABGR(x, y, 0)
		for (line in 1..3) {
			val y = chart.height * line / 4
			for (x in 0 until chart.width) image.setPixelABGR(x, y, GRID_ABGR)
		}
		plot(image, chart, abgr(SELL_COLOR)) { it.sell }
		plot(image, chart, abgr(BUY_COLOR)) { it.buy }
		client.textureManager.register(CHART, DynamicTexture({ "Cryptic Bazaar history" }, image))
		this.chart = chart
	}

	private fun plot(image: NativeImage, chart: Chart, color: Int, value: (Point) -> Double) {
		val thickness = (chart.guiScale + 1) / 2
		fun pixelX(point: Point) = chart.share(point.at) * (chart.width - thickness)
		fun pixelY(point: Point) = (1 - (value(point) - chart.low) / (chart.high - chart.low)) * (chart.height - thickness)
		fun stamp(x: Int, y: Int) {
			for (px in x until x + thickness) for (py in y until y + thickness) {
				if (px in 0 until chart.width && py in 0 until chart.height) image.setPixelABGR(px, py, color)
			}
		}
		// A product can have nothing on offer for a while, which leaves a gap rather than a plunge.
		var last: Point? = null
		for (point in chart.points) {
			if (value(point) <= 0) {
				last = null
				continue
			}
			val previous = last ?: point
			val startX = pixelX(previous)
			val startY = pixelY(previous)
			val steps = maxOf(abs(pixelX(point) - startX), abs(pixelY(point) - startY)).toInt().coerceAtLeast(1)
			for (step in 0..steps) {
				val share = step.toDouble() / steps
				stamp((startX + (pixelX(point) - startX) * share).toInt(), (startY + (pixelY(point) - startY) * share).toInt())
			}
			last = point
		}
	}

	// ---- The panel ----------------------------------------------------------------------------

	private class Box(val x: Int, val y: Int, val width: Int, val height: Int) {
		fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var panel: Box? = null
	private var buttons: List<Pair<Box, Range>> = emptyList()

	private fun price(value: Double): String = if (value >= 1_000_000) Bazaar.short(value) else Bazaar.coins(value)

	private fun time(at: Long, pattern: String): String =
		DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

	private fun change(now: Double, then: Double?): String {
		if (then == null || then <= 0 || now <= 0) return ""
		val percent = (now - then) / then * 100
		return String.format(Locale.US, " %s%+.1f%%", if (percent < 0) "§c" else "§a", percent)
	}

	private fun row(graphics: GuiGraphicsExtractor, box: Box, y: Int, label: String, value: String) {
		val font = client.font
		graphics.text(font, label, box.x + PADDING, y, -1)
		graphics.text(font, value, box.x + box.width - PADDING - font.width(value), y, -1)
	}

	@JvmStatic
	fun render(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		panel = null
		buttons = emptyList()
		if (!Bazaar.module.enabled || !Bazaar.priceHistory.value) return
		val id = Bazaar.openProduct ?: return
		if (screen !is AbstractContainerScreen<*>) return
		val accessor = screen as? ContainerScreenAccessor ?: return
		val x = accessor.`cryptic$leftPos`() + accessor.`cryptic$imageWidth`() + GAP
		val room = graphics.guiWidth() - x - 4
		if (room < MIN_WIDTH) return
		val box = Box(x, accessor.`cryptic$topPos`(), room.coerceAtMost(MAX_WIDTH), accessor.`cryptic$imageHeight`().coerceIn(140, 190))
		panel = box
		val range = BazaarHistory.Range.entries[Bazaar.historyRange.selectedIndex]
		val entry = entry(id, range)
		val points = entry?.points

		graphics.fill(box.x, box.y, box.x + box.width, box.y + box.height, PANEL_FILL)
		graphics.fill(box.x, box.y, box.x + box.width, box.y + 1, PANEL_EDGE)
		graphics.fill(box.x, box.y + box.height - 1, box.x + box.width, box.y + box.height, PANEL_EDGE)
		graphics.fill(box.x, box.y + 1, box.x + 1, box.y + box.height - 1, PANEL_EDGE)
		graphics.fill(box.x + box.width - 1, box.y + 1, box.x + box.width, box.y + box.height - 1, PANEL_EDGE)

		val font = client.font
		graphics.text(font, "§6Price history", box.x + PADDING, box.y + PADDING, -1)
		val placed = ArrayList<Pair<Box, Range>>()
		var right = box.x + box.width - PADDING
		for (option in BazaarHistory.Range.entries.reversed()) {
			val width = font.width(option.label) + 6
			val button = Box(right - width, box.y + 4, width, font.lineHeight + 3)
			val fill = when {
				option == range -> BUTTON_ON
				button.contains(mouseX.toDouble(), mouseY.toDouble()) -> BUTTON_HOVER
				else -> BUTTON_FILL
			}
			graphics.fill(button.x, button.y, button.x + button.width, button.y + button.height, fill)
			graphics.text(font, option.label, button.x + 3, button.y + 2, -1)
			placed += button to option
			right -= width + 2
		}
		buttons = placed

		val product = BazaarMarket.product(id)
		val buyNow = product?.instantBuy?.takeIf { it > 0 } ?: points?.lastOrNull()?.buy ?: 0.0
		val sellNow = product?.instantSell?.takeIf { it > 0 } ?: points?.lastOrNull()?.sell ?: 0.0
		val first = points?.firstOrNull()
		row(graphics, box, box.y + 20, "§bInstant buy", "§f${price(buyNow)}${change(buyNow, first?.buy)}")
		row(graphics, box, box.y + 31, "§6Instant sell", "§f${price(sellNow)}${change(sellNow, first?.sell)}")

		val plot = Box(box.x + PADDING, box.y + HEADER, box.width - PADDING * 2, box.height - HEADER - FOOTER)
		val middle = plot.y + plot.height / 2 - font.lineHeight / 2
		val message = when {
			entry == null -> "§7Loading..."
			points == null -> "§cCouldn't reach Coflnet"
			points.size < 2 -> "§7No history for this item"
			else -> null
		}
		if (message != null || points == null) {
			graphics.centeredText(font, Component.literal(message ?: ""), plot.x + plot.width / 2, middle, -1)
			return
		}

		val scale = client.window.guiScale.coerceAtLeast(1)
		val known = chart
		val shown = if (known?.points === points && known.width == plot.width * scale && known.height == plot.height * scale) known else {
			Chart(points, plot.width * scale, plot.height * scale, scale).also(::bake)
		}
		graphics.blit(
			RenderPipelines.GUI_TEXTURED, CHART, plot.x, plot.y, 0f, 0f, plot.width, plot.height,
			shown.width, shown.height, shown.width, shown.height,
		)
		graphics.text(font, "§7${price(shown.high)}", plot.x + 1, plot.y + 1, -1)
		graphics.text(font, "§7${price(shown.low)}", plot.x + 1, plot.y + plot.height - font.lineHeight, -1)
		val footerY = plot.y + plot.height + 3
		graphics.text(font, "§8${time(shown.from, range.axis)}", plot.x, footerY, -1)
		val end = "§8${time(shown.to, range.axis)}"
		graphics.text(font, end, plot.x + plot.width - font.width(end), footerY, -1)

		if (plot.contains(mouseX.toDouble(), mouseY.toDouble())) {
			val at = shown.from + (mouseX - plot.x).toDouble() / plot.width * (shown.to - shown.from)
			val nearest = points.minBy { abs(it.at - at) }
			val lineX = plot.x + (shown.share(nearest.at) * (plot.width - 1)).toInt()
			graphics.fill(lineX, plot.y, lineX + 1, plot.y + plot.height, CURSOR)
			graphics.setComponentTooltipForNextFrame(
				font,
				listOf(
					Component.literal("§f${time(nearest.at, "d MMM HH:mm:ss")}"),
					Component.literal("§bInstant buy: §f${Bazaar.coins(nearest.buy)}"),
					Component.literal("§6Instant sell: §f${Bazaar.coins(nearest.sell)}"),
				),
				mouseX, mouseY,
			)
		}
	}

	/** True for any click on the panel, so none falls through to the menu behind it. */
	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double): Boolean {
		if (panel?.contains(mouseX, mouseY) != true) return false
		val range = buttons.firstOrNull { (button, _) -> button.contains(mouseX, mouseY) }?.second ?: return true
		Bazaar.historyRange.selectedIndex = range.ordinal
		client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
		return true
	}
}
