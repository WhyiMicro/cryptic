package imicro.cryptic.gui.menu

import imicro.cryptic.feature.BazaarFlips
import imicro.cryptic.feature.UpdateChecker
import imicro.cryptic.gui.ModuleRegistry
import imicro.cryptic.gui.menu.MenuStyle.Icons
import imicro.cryptic.gui.menu.MenuStyle.approach
import imicro.cryptic.gui.menu.MenuStyle.blend
import imicro.cryptic.gui.menu.MenuStyle.fade
import imicro.cryptic.gui.menu.managers.Block
import imicro.cryptic.gui.menu.managers.ReleaseNotes
import imicro.cryptic.render.RoundRect
import imicro.cryptic.skyblock.PlayStats
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.world.item.component.ResolvableProfile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The menu's Profile view: a welcome, five numbers, a month of SkyBlock time, where that time went,
 * the latest release notes and this session. Everything comes from [PlayStats] and what other
 * modules already keep; nothing is fetched but the release notes.
 */
class ProfilePage(private val openChangelog: () -> Unit) {
	private var scroll = 0f
	private var scrollTarget = 0f
	private var maxScroll = 0f
	private val scrollbar = Scrollbar()
	private var changelogButton = Box.NONE
	private var buttonLift = 0f

	init {
		ReleaseNotes.load()
	}

	fun draw(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val shown = MenuStyle.clipList(graphics, box)
		val pointerInside = shown.contains(mx, my)
		val x = box.x
		val w = box.w
		var y = box.y + REST - scroll
		val stats = PlayStats.current()

		y += drawWelcome(graphics, x, y, w, alpha) + GAP
		y += drawNumbers(graphics, stats, x, y, w, alpha) + GAP

		val leftW = ((w - GAP) * 0.62f).roundToInt().toFloat()
		val rightW = w - GAP - leftW
		drawActivity(graphics, x, y, leftW, ROW_H, if (pointerInside) mx else -1e6f, my, alpha)
		drawIslands(graphics, stats, x + leftW + GAP, y, rightW, ROW_H, alpha)
		y += ROW_H + GAP

		drawChangelog(graphics, x, y, leftW, ROW_H, mx, my, pointerInside, dt, alpha)
		drawSession(graphics, stats, x + leftW + GAP, y, rightW, ROW_H, alpha)
		y += ROW_H

		val contentH = y + scroll - box.y + REST
		maxScroll = max(0f, contentH - box.h)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)
		MenuStyle.drawScrollFades(graphics, shown, scroll, maxScroll, alpha, topRamp = REST, bottomRamp = REST)
		graphics.disableScissor()
		val trackTop = box.y + 6f
		scrollbar.draw(graphics, box.right + Scrollbar.GAP, trackTop, box.bottom - 6f - trackTop, contentH, scroll, maxScroll, mx, my, dt, alpha)
	}

	private fun card(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(MenuStyle.CARD_OFF, alpha), RADIUS)
		RoundRect.outline(graphics, x, y, x + w, y + h, fade(MenuStyle.CARD_EDGE, alpha), RADIUS, 1f)
	}

	/** Small grey capitals over a card, and a line under them; returns where the card's body starts. */
	private fun cardTitle(graphics: GuiGraphicsExtractor, title: String, subtitle: String, x: Float, y: Float, alpha: Float): Float {
		UiText.draw(graphics, title, x + PAD, y + PAD, 10.5f, fade(MenuStyle.GROUP_LABEL, alpha), UiText.Face.MEDIUM)
		UiText.draw(graphics, subtitle, x + PAD, y + PAD + 18f, 12.5f, fade(MenuStyle.CARD_ICON, alpha))
		return y + PAD + 44f
	}

	// ---- Welcome ----

	private fun drawWelcome(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, alpha: Float): Float {
		val h = 80f
		card(graphics, x, y, w, h, alpha)
		val client = Minecraft.getInstance()
		val name = client.user.name
		val face = 44f
		val faceX = x + PAD
		val faceY = y + (h - face) / 2f
		if (alpha > 0.5f) {
			val skin = client.playerSkinRenderCache().getOrDefault(ResolvableProfile.createUnresolved(name)).playerSkin()
			val pose = graphics.pose()
			pose.pushMatrix()
			pose.translate(faceX, faceY)
			pose.scale(face / 8f, face / 8f)
			PlayerFaceExtractor.extractRenderState(graphics, skin, 0, 0, 8)
			pose.popMatrix()
		}
		RoundRect.outline(graphics, faceX - 1f, faceY - 1f, faceX + face + 1f, faceY + face + 1f, fade(MenuStyle.CARD_EDGE_HOVER, alpha), 3f, 1f)

		val textX = faceX + face + 16f
		val title = "Welcome back, $name!"
		val titleSize = 20f
		UiText.draw(graphics, title, textX, y + 20f, titleSize, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		// The installed version, in a pill after the greeting.
		val version = "V" + UpdateChecker.installed.substringBefore('-')
		val pillX = textX + UiText.width(title, titleSize, UiText.Face.MEDIUM) + 12f
		val pillW = UiText.width(version, 9.5f, UiText.Face.MEDIUM) + 16f
		val pillY = y + 20f + (titleSize - 17f) / 2f
		RoundRect.fill(graphics, pillX, pillY, pillX + pillW, pillY + 17f, fade(blend(MenuStyle.CARD_OFF, MenuStyle.accent(), 0.15f), alpha), 8.5f)
		RoundRect.outline(graphics, pillX, pillY, pillX + pillW, pillY + 17f, fade(blend(MenuStyle.CARD_OFF, MenuStyle.accent(), 0.7f), alpha), 8.5f, 1f)
		UiText.draw(graphics, version, pillX + 8f, pillY + (17f - 9.5f) / 2f, 9.5f, fade(blend(MenuStyle.accent(), 0xFFFFFFFF.toInt(), 0.35f), alpha), UiText.Face.MEDIUM)

		val today = PlayStats.today()
		val line = if (today >= 60) "You've spent ${duration(today)} on SkyBlock today." else "You haven't been on SkyBlock today yet."
		UiText.draw(graphics, line, textX, y + 46f, 13f, fade(MenuStyle.MUTED, alpha))

		// Where you are now, on the right.
		val island = if (SkyblockLocation.onSkyblock) SkyblockLocation.area ?: "SkyBlock" else "Not on SkyBlock"
		val islandW = max(UiText.width(island, 14f, UiText.Face.MEDIUM), UiText.width("ISLAND", 10f, UiText.Face.MEDIUM))
		val islandX = x + w - PAD - islandW
		val iconBox = Box(islandX - 46f, y + (h - 36f) / 2f, 36f, 36f)
		RoundRect.fill(graphics, iconBox.x, iconBox.y, iconBox.right, iconBox.bottom, fade(blend(MenuStyle.CARD_OFF, MenuStyle.accent(), 0.16f), alpha), 8f)
		UiText.drawIconCentered(graphics, Icons.MAP, iconBox.x, iconBox.y, iconBox.w, 15f, fade(MenuStyle.accent(), alpha))
		UiText.draw(graphics, "ISLAND", islandX, y + 24f, 10f, fade(MenuStyle.GROUP_LABEL, alpha), UiText.Face.MEDIUM)
		UiText.draw(graphics, island, islandX, y + 40f, 14f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		return h
	}

	// ---- The five numbers ----

	private class Number(val label: String, val value: String, val icon: String, val color: Int)

	private fun drawNumbers(graphics: GuiGraphicsExtractor, stats: PlayStats.Stats, x: Float, y: Float, w: Float, alpha: Float): Float {
		val modules = ModuleRegistry.modules.filter { it.allowed && it.supportsToggle && it.category != imicro.cryptic.gui.ModuleCategory.MISC }
		val profit = BazaarFlips.weekProfit()
		val numbers = listOf(
			Number("SkyBlock Time", duration(stats.seconds), Icons.CLOCK, MenuStyle.accent()),
			Number("Runs Finished", "%,d".format(Locale.US, stats.runs), Icons.DUNGEON, 0xFFFB923C.toInt()),
			Number("Slayer Bosses", "%,d".format(Locale.US, stats.bosses), Icons.SKULL, 0xFFF43F5E.toInt()),
			Number("Flip Profit (7d)", coins(profit), Icons.COINS, 0xFF34D399.toInt()),
			Number("Modules On", "${modules.count { it.enabled }} / ${modules.size}", Icons.POWER, 0xFF60A5FA.toInt()),
		)
		val h = 92f
		val cardW = (w - GAP * (numbers.size - 1)) / numbers.size
		for ((index, number) in numbers.withIndex()) {
			val cx = x + index * (cardW + GAP)
			card(graphics, cx, y, cardW, h, alpha)
			val icon = Box(cx + PAD, y + PAD, 26f, 26f)
			RoundRect.fill(graphics, icon.x, icon.y, icon.right, icon.bottom, fade(blend(MenuStyle.CARD_OFF, number.color, 0.16f), alpha), 6f)
			UiText.drawIconCentered(graphics, number.icon, icon.x, icon.y, icon.w, 12f, fade(number.color, alpha))
			UiText.draw(graphics, UiText.fit(number.label, 13f, cardW - PAD * 2f - 36f), icon.right + 10f, icon.y + (icon.h - 13f) / 2f, 13f, fade(MenuStyle.CARD_ICON, alpha))
			UiText.draw(graphics, UiText.fit(number.value, 22f, cardW - PAD * 2f, UiText.Face.MEDIUM), cx + PAD, y + 54f, 22f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		}
		return h
	}

	// ---- Activity ----

	private fun drawActivity(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, mx: Float, my: Float, alpha: Float) {
		card(graphics, x, y, w, h, alpha)
		val body = cardTitle(graphics, "ACTIVITY", "Daily SkyBlock time over the last $DAYS days. Hover a day for details.", x, y, alpha)
		val days = PlayStats.lastDays(DAYS)
		val most = days.maxOf { it.second }
		// The top line is a whole hour (or half an hour, for a light month) above the busiest day.
		val step = if (most <= 1800L) 1800L else 3600L
		val top = max(step, ((most + step - 1) / step) * step)

		val labelW = 34f
		val chart = Box(x + PAD + labelW, body + 6f, w - PAD * 2f - labelW, y + h - PAD - 18f - (body + 6f))
		for (i in 0..2) {
			val value = top * (2 - i) / 2
			val ly = chart.y + chart.h * i / 2f
			RoundRect.fill(graphics, chart.x, ly, chart.right, ly + 1f, fade(MenuStyle.HEADING_LINE, alpha), 0f)
			val label = if (value == 0L) "0" else axis(value)
			UiText.draw(graphics, label, chart.x - 8f - UiText.width(label, 10f), ly - 5f, 10f, fade(MenuStyle.GROUP_LABEL, alpha))
		}
		// Dates under the first, middle and last days.
		for (i in listOf(0, DAYS / 2, DAYS - 1)) {
			val label = DATE.format(days[i].first)
			val lx = chart.x + chart.w * i / (DAYS - 1)
			val labelX = (lx - UiText.width(label, 10f) / 2f).coerceIn(chart.x, chart.right - UiText.width(label, 10f))
			UiText.draw(graphics, label, labelX, chart.bottom + 8f, 10f, fade(MenuStyle.GROUP_LABEL, alpha))
		}

		val values = days.map { (it.second.toFloat() / top).coerceIn(0f, 1f) }
		drawCurve(graphics, chart, values, alpha)

		// The day under the pointer: a guide, a dot on the line and what it was.
		if (mx >= chart.x - 6f && mx <= chart.right + 6f && my >= chart.y - 6f && my <= chart.bottom + 6f) {
			val index = ((mx - chart.x) / chart.w * (DAYS - 1)).roundToInt().coerceIn(0, DAYS - 1)
			val dx = chart.x + chart.w * index / (DAYS - 1)
			val dy = chart.bottom - values[index] * chart.h
			RoundRect.fill(graphics, dx, chart.y, dx + 1f, chart.bottom, fade(MenuStyle.FIELD_EDGE_HOVER, alpha), 0f)
			RoundRect.fill(graphics, dx - 4f, dy - 4f, dx + 4f, dy + 4f, fade(MenuStyle.accent(), alpha), 4f)
			RoundRect.outline(graphics, dx - 4f, dy - 4f, dx + 4f, dy + 4f, fade(0xFFFFFFFF.toInt(), alpha), 4f, 1.5f)
			val (day, seconds) = days[index]
			val text = DATE.format(day) + "  ·  " + if (seconds >= 60) duration(seconds) else "Nothing"
			val tw = UiText.width(text, 11.5f) + 18f
			val tx = (dx - tw / 2f).coerceIn(chart.x, chart.right - tw)
			val ty = max(chart.y - 4f, dy - 36f)
			RoundRect.fill(graphics, tx, ty, tx + tw, ty + 24f, fade(MenuStyle.POPUP, alpha), 6f)
			RoundRect.outline(graphics, tx, ty, tx + tw, ty + 24f, fade(MenuStyle.WINDOW_EDGE, alpha), 6f, 1f)
			UiText.draw(graphics, text, tx + 9f, ty + (24f - 11.5f) / 2f, 11.5f, fade(MenuStyle.TEXT, alpha))
		}
	}

	/**
	 * The month as a smooth line over a faint fill, eased between days so peaks round off. Laid a
	 * screen column at a time; each column's line reaches back to the last one's, so steep stretches
	 * stay joined.
	 */
	private fun drawCurve(graphics: GuiGraphicsExtractor, chart: Box, values: List<Float>, alpha: Float) {
		val pose = graphics.pose()
		val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
		val topLeft = pose.transformPosition(chart.x, chart.y, org.joml.Vector2f())
		val bottomRight = pose.transformPosition(chart.right, chart.bottom, org.joml.Vector2f())
		val left = (topLeft.x * guiScale).roundToInt()
		val right = (bottomRight.x * guiScale).roundToInt()
		val top = topLeft.y * guiScale
		val bottom = (bottomRight.y * guiScale).roundToInt()
		val height = bottom - top
		val thickness = max(2, UiText.pixelsPerUnit.roundToInt() + 1)
		val line = fade(MenuStyle.accent(), alpha)
		val area = fade(MenuStyle.accent(), alpha * 0.14f)
		pose.pushMatrix()
		pose.identity()
		pose.scale(1f / guiScale, 1f / guiScale)
		var last = -1
		for (column in left until right) {
			val t = (column - left + 0.5f) / (right - left) * (values.size - 1)
			val i = t.toInt().coerceAtMost(values.size - 2)
			val f = t - i
			val eased = (1f - cos(f * PI.toFloat())) / 2f
			val value = values[i] + (values[i + 1] - values[i]) * eased
			val y = (bottom - value * height).roundToInt()
			if (y < bottom) graphics.fill(column, y, column + 1, bottom, area)
			val from = if (last < 0) y else minOf(y, last)
			val to = if (last < 0) y else maxOf(y, last)
			graphics.fill(column, from - thickness / 2, column + 1, to + (thickness + 1) / 2, line)
			last = y
		}
		pose.popMatrix()
	}

	// ---- Time by island ----

	private fun drawIslands(graphics: GuiGraphicsExtractor, stats: PlayStats.Stats, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
		card(graphics, x, y, w, h, alpha)
		val body = cardTitle(graphics, "TIME BY ISLAND", "Where your SkyBlock time went", x, y, alpha)
		val total = stats.areas.values.sum()
		val sorted = stats.areas.entries.sortedByDescending { it.value }
		val slices = sorted.take(SLICES - 1).map { it.key to it.value }.toMutableList()
		val rest = sorted.drop(SLICES - 1).sumOf { it.value }
		if (rest > 0) slices += "Other" to rest

		val outer = 62f
		val inner = 44f
		val cx = x + PAD + outer
		val cy = body + (y + h - PAD - body) / 2f
		if (total <= 0L) {
			RoundRect.ringSegment(graphics, cx, cy, outer, inner, 0f, 1f, fade(MenuStyle.TRACK, alpha))
		} else {
			var from = 0f
			for ((index, slice) in slices.withIndex()) {
				val share = slice.second.toFloat() / total
				// A hair of a gap between slices, unless there's only one.
				val gap = if (slices.size > 1) 0.004f else 0f
				RoundRect.ringSegment(graphics, cx, cy, outer, inner, from + gap, from + share - gap, fade(sliceColor(index, slice.first), alpha))
				from += share
			}
		}
		val totalLabel = "TOTAL"
		UiText.draw(graphics, totalLabel, cx - UiText.width(totalLabel, 9.5f, UiText.Face.MEDIUM) / 2f, cy - 16f, 9.5f, fade(MenuStyle.GROUP_LABEL, alpha), UiText.Face.MEDIUM)
		val totalText = duration(total)
		UiText.draw(graphics, totalText, cx - UiText.width(totalText, 15f, UiText.Face.MEDIUM) / 2f, cy - 2f, 15f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)

		// The legend: a dot, the island, its share and its time.
		val legendX = cx + outer + 24f
		val legendRight = x + w - PAD
		if (slices.isEmpty()) {
			UiText.draw(graphics, "Nothing recorded yet.", legendX, cy - 6f, 12.5f, fade(MenuStyle.MUTED, alpha))
			return
		}
		val rowH = 20f
		var ly = cy - slices.size * rowH / 2f
		for ((index, slice) in slices.withIndex()) {
			val (name, seconds) = slice
			val time = duration(seconds)
			val percent = "${(seconds * 100 / total)}%"
			val timeW = UiText.width(time, 12f, UiText.Face.MEDIUM)
			val percentRight = legendRight - 62f
			RoundRect.fill(graphics, legendX, ly + rowH / 2f - 3.5f, legendX + 7f, ly + rowH / 2f + 3.5f, fade(sliceColor(index, name), alpha), 3.5f)
			val nameRoom = percentRight - UiText.width("100%", 10.5f) - 8f - (legendX + 14f)
			UiText.draw(graphics, UiText.fit(name, 12f, nameRoom), legendX + 14f, ly + (rowH - 12f) / 2f, 12f, fade(MenuStyle.CARD_ICON, alpha))
			UiText.draw(graphics, percent, percentRight - UiText.width(percent, 10.5f), ly + (rowH - 10.5f) / 2f + 0.5f, 10.5f, fade(MenuStyle.GROUP_LABEL, alpha))
			UiText.draw(graphics, time, legendRight - timeW, ly + (rowH - 12f) / 2f, 12f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
			ly += rowH
		}
	}

	private fun sliceColor(index: Int, name: String): Int = when {
		name == "Other" -> 0xFF64748B.toInt()
		index == 0 -> MenuStyle.accent()
		else -> PALETTE[(index - 1) % PALETTE.size]
	}

	// ---- Changelog ----

	private fun drawChangelog(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float) {
		card(graphics, x, y, w, h, alpha)
		val state = ReleaseNotes.state
		val loaded = state as? ReleaseNotes.State.Loaded
		val subtitle = when (state) {
			is ReleaseNotes.State.Loaded -> "Cryptic " + loaded!!.version.substringBefore('-') + (loaded.published?.let { ", released $it" } ?: "")
			is ReleaseNotes.State.Failed -> state.message
			else -> "Loading the release notes…"
		}
		val body = cardTitle(graphics, "CHANGELOG", subtitle, x, y, alpha)

		// "Read all" opens the Changelog page.
		val label = "Read all"
		val bw = UiText.width(label, 12f, UiText.Face.MEDIUM) + 24f
		changelogButton = Box(x + w - PAD - bw, y + PAD, bw, 28f)
		val over = pointerInside && changelogButton.contains(mx, my)
		buttonLift = approach(buttonLift, if (over) 1f else 0f, 18f, dt)
		MenuStyle.drawControlBox(graphics, changelogButton, buttonLift > 0.5f, false, alpha)
		UiText.draw(graphics, label, changelogButton.x + 12f, changelogButton.y + (28f - 12f) / 2f, 12f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)

		if (loaded == null) return
		val textW = w - PAD * 2f
		val bottom = y + h - PAD
		var ly = body + 2f
		for (block in loaded.blocks) {
			if (ly > bottom) break
			when (block) {
				is Block.Heading -> {
					if (ly > body + 2f) ly += 6f
					if (ly + 16f > bottom) break
					UiText.draw(graphics, UiText.fit(block.text, 14f, textW, UiText.Face.MEDIUM), x + PAD, ly, 14f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
					ly += 24f
				}
				is Block.Text -> {
					val text = block.spans.joinToString("") { it.text }
					val indent = if (block.depth >= 0) 14f + block.depth * 14f else 0f
					for ((index, line) in UiText.wrap(text, 12f, textW - indent).withIndex()) {
						if (ly + 14f > bottom) break
						if (index == 0 && block.depth >= 0) {
							RoundRect.fill(graphics, x + PAD + indent - 10f, ly + 5f, x + PAD + indent - 6f, ly + 9f, fade(MenuStyle.accent(), alpha), 2f)
						}
						UiText.draw(graphics, line, x + PAD + indent, ly, 12f, fade(MenuStyle.TAB_HOVER, alpha))
						ly += 17f
					}
					ly += 2f
				}
			}
		}
		// Notes running past the card fade out into it.
		if (ly + 14f > bottom) {
			val fadeTop = bottom - 36f
			for (i in 0 until 12) {
				val t = (i + 1) / 12f
				RoundRect.fill(graphics, x + 1f, fadeTop + i * 3f, x + w - 1f, fadeTop + (i + 1) * 3f, fade(MenuStyle.CARD_OFF, alpha * t * t * (3f - 2f * t)), 0f)
			}
		}
	}

	// ---- This session ----

	private fun drawSession(graphics: GuiGraphicsExtractor, stats: PlayStats.Stats, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
		card(graphics, x, y, w, h, alpha)
		val body = cardTitle(graphics, "THIS SESSION", "Since you started the game", x, y, alpha)
		val session = PlayStats.Session
		val big = if (session.seconds >= 60) duration(session.seconds) else "Just started"
		UiText.draw(graphics, big, x + PAD, body + 2f, 30f, fade(MenuStyle.accent(), alpha), UiText.Face.MEDIUM)
		UiText.draw(graphics, "on SkyBlock", x + PAD, body + 40f, 12.5f, fade(MenuStyle.MUTED, alpha))
		val favourite = stats.areas.maxByOrNull { it.value }?.key ?: "None yet"
		val rows = listOf(
			"Runs finished" to "%,d".format(Locale.US, session.runs),
			"Slayer bosses" to "%,d".format(Locale.US, session.bosses),
			"Most played island" to favourite,
		)
		var ry = body + 68f
		for ((label, value) in rows) {
			UiText.draw(graphics, label, x + PAD, ry, 12.5f, fade(MenuStyle.CARD_ICON, alpha))
			val shownValue = UiText.fit(value, 12.5f, w * 0.5f, UiText.Face.MEDIUM)
			UiText.draw(graphics, shownValue, x + w - PAD - UiText.width(shownValue, 12.5f, UiText.Face.MEDIUM), ry, 12.5f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
			ry += 26f
		}
	}

	// ---- Input ----

	fun mouseClicked(mx: Float, my: Float): Boolean {
		if (scrollbar.press(mx, my, maxScroll) { scroll = it; scrollTarget = it }) return true
		if (changelogButton.contains(mx, my)) {
			openChangelog()
			return true
		}
		return false
	}

	fun mouseDragged(my: Float): Boolean = scrollbar.drag(my, maxScroll) { scroll = it; scrollTarget = it }

	fun mouseReleased(): Boolean = scrollbar.release()

	fun mouseScrolled(amount: Float) {
		scrollTarget = (scrollTarget - amount * 70f).coerceIn(0f, maxScroll)
	}

	companion object {
		private const val DAYS = 30
		private const val SLICES = 7
		private const val PAD = 18f
		private const val GAP = 14f
		private const val RADIUS = 8f
		private const val ROW_H = 262f

		/** How far the cards rest inside the scroll area, where its fades are. */
		private const val REST = 20f

		private val DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

		private val PALETTE = intArrayOf(
			0xFFEC4899.toInt(), 0xFFF43F5E.toInt(), 0xFFFB923C.toInt(), 0xFFFBBF24.toInt(), 0xFFFDE047.toInt(),
		)

		/** "7d 3h", "14h 30m", "45m". */
		fun duration(seconds: Long): String {
			val minutes = seconds / 60
			val hours = minutes / 60
			val days = hours / 24
			return when {
				days > 0 -> "${days}d ${hours % 24}h"
				hours > 0 -> "${hours}h ${minutes % 60}m"
				else -> "${minutes}m"
			}
		}

		/** An axis label: "4h", "30m". */
		private fun axis(seconds: Long): String = if (seconds % 3600L == 0L) "${seconds / 3600}h" else "${seconds / 60}m"

		/** "19.7M", "250k", "-1.2B". */
		fun coins(amount: Double): String {
			val sign = if (amount < 0) "-" else ""
			val size = abs(amount)
			return sign + when {
				size >= 1e9 -> "%.1fB".format(Locale.US, size / 1e9)
				size >= 1e6 -> "%.1fM".format(Locale.US, size / 1e6)
				size >= 1e3 -> "%.0fk".format(Locale.US, size / 1e3)
				else -> "%.0f".format(Locale.US, size)
			}
		}
	}
}
