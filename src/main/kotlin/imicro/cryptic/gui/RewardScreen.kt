package imicro.cryptic.gui

import imicro.cryptic.feature.ClickGui
import imicro.cryptic.feature.DailyRewards
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.MenuStyle.fade
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.render.RoundRect
import imicro.cryptic.rewards.RewardCard
import imicro.cryptic.rewards.RewardOffer
import imicro.cryptic.rewards.RewardRarity
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Util
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.net.URI
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The daily reward picker: Hypixel's three cards, turned over and claimed in game, in the menu's
 * style. Laid out in the menu's design pixels and scaled to the window.
 */
class RewardScreen private constructor() : Screen(Component.literal("Daily Reward")) {
	private sealed class State {
		data object Loading : State()
		class Ready(val offer: RewardOffer) : State()
		class Failed(val reason: String, val link: String?) : State()
	}

	private var state: State = State.Loading

	private var shownAt = 0L

	/** How far each card has turned, 0 face down to 1 face up. */
	private val flip = FloatArray(3)
	private val flipping = BooleanArray(3)
	private val flipDelay = FloatArray(3)
	private val revealedSound = BooleanArray(3)
	private val lift = FloatArray(3)
	private val buttonHover = FloatArray(3)

	private var claimed = -1
	private var claiming = -1
	private var claimError: String? = null

	/** When the picker closes itself after a claim, or 0. */
	private var closeAt = 0L

	private var opening = 0f
	private var lastFrame = 0L

	private val cardBoxes = Array(3) { Box.NONE }
	private val buttonBoxes = Array(3) { Box.NONE }
	private var closeBox = Box.NONE
	private var browserBox = Box.NONE
	private var closeHover = 0f
	private var browserHover = 0f

	fun show(offer: RewardOffer) {
		state = State.Ready(offer)
		shownAt = System.currentTimeMillis()
		// "Already turned over": face up from the start, with no animation or sounds.
		if (DailyRewards.reveal.selectedIndex == DailyRewards.REVEAL_ON_OPEN) {
			for (i in 0 until 3) {
				flipping[i] = true
				flip[i] = 1f
				revealedSound[i] = true
			}
		}
	}

	fun fail(reason: String, link: String?) {
		state = State.Failed(reason, link)
	}

	override fun isPauseScreen() = false

	/** Set when you close the picker yourself, the only close allowed while it opens. */
	var closedByUser = false
		private set

	override fun onClose() {
		closedByUser = true
		super.onClose()
	}

	override fun tick() {
		super.tick()
		if (closeAt != 0L && System.currentTimeMillis() >= closeAt) {
			closeAt = 0L
			onClose()
		}
	}

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		// As the menu: blurred when Click GUI asks for it (see MenuScreen.blurRadius).
		if (ClickGui.backgroundBlur.value) graphics.blurBeforeThisStratum()
		graphics.fill(0, 0, width, height, 0x88000000.toInt())
	}

	private fun scale(): Float {
		val window = minecraft.window
		return (window.screenHeight / DESIGN_HEIGHT) / window.guiScale.toFloat()
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		val now = System.nanoTime()
		val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
		lastFrame = now
		opening = min(1f, opening + dt / OPEN_SECONDS)
		val alpha = MenuStyle.easeOut(opening)

		// A minimised window has no size to lay out in.
		if (minecraft.window.screenHeight <= 0 || width <= 0 || height <= 0) return
		UiText.pixelsPerUnit = minecraft.window.screenHeight / DESIGN_HEIGHT
		val k = scale()
		val mx = mouseX / k
		val my = mouseY / k
		val viewW = width / k
		val viewH = height / k

		graphics.pose().pushMatrix()
		graphics.pose().scale(k, k)

		val winW = min(WIN_W, viewW - 40f)
		val winH = HEADER_H + PANEL_GAP + CONTENT_H
		val winX = (viewW - winW) / 2f
		val winY = (viewH - winH) / 2f + (1f - alpha) * 14f
		val header = Box(winX, winY, winW, HEADER_H)
		val content = Box(winX, header.bottom + PANEL_GAP, winW, CONTENT_H)

		// Both shadows before either panel, so neither darkens the other's edge.
		for (panel in listOf(header, content)) {
			RoundRect.shadow(graphics, panel.x, panel.y, panel.right, panel.bottom, fade(PANEL_SHADOW, alpha), PANEL_RADIUS, 28f)
		}
		for (panel in listOf(header, content)) {
			RoundRect.fill(graphics, panel.x, panel.y, panel.right, panel.bottom, fade(MenuStyle.WINDOW_BG, alpha), PANEL_RADIUS)
			RoundRect.outline(graphics, panel.x, panel.y, panel.right, panel.bottom, fade(MenuStyle.WINDOW_EDGE, alpha), PANEL_RADIUS, 1f)
		}

		drawHeader(graphics, header, mx, my, dt, alpha)

		when (val current = state) {
			State.Loading -> drawLoading(graphics, content, alpha)
			is State.Failed -> drawFailed(graphics, current, content, mx, my, dt, alpha)
			is State.Ready -> drawOffer(graphics, current.offer, content, mx, my, dt, alpha)
		}

		graphics.pose().popMatrix()
	}

	/** The menu's heading: title, a hairline, then the subtitle; the close button at the right. */
	private fun drawHeader(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val x = box.x + HEADER_PAD
		val titleSize = 22f
		UiText.draw(graphics, "Daily Reward", x, box.y + (box.h - titleSize) / 2f, titleSize, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		val barX = x + UiText.width("Daily Reward", titleSize, UiText.Face.MEDIUM) + 14f
		RoundRect.fill(graphics, barX, box.y + box.h / 2f - 9f, barX + 1f, box.y + box.h / 2f + 9f, fade(MenuStyle.FIELD_EDGE_HOVER, alpha), 0f)

		val subtitle: Pair<String, Int> = when (val current = state) {
			State.Loading -> "Reading today's cards from Hypixel" to MenuStyle.MUTED
			is State.Failed -> "Something went wrong" to MenuStyle.DANGER
			is State.Ready -> when {
				claimError != null -> "Claim failed: ${claimError}" to MenuStyle.DANGER
				claimed >= 0 -> "Claimed. The next reward is in ${resetText()}" to MenuStyle.MUTED
				adRemaining(current.offer) > 0 -> "Hypixel's ad is running. Cards can be claimed in ${adRemaining(current.offer)}s" to MenuStyle.STAR
				current.offer.sample -> "A preview: claiming does nothing" to MenuStyle.MUTED
				else -> "Turn the cards over and claim one" to MenuStyle.MUTED
			}
		}

		closeBox = Box(box.right - HEADER_PAD - CLOSE_SIZE, box.y + (box.h - CLOSE_SIZE) / 2f, CLOSE_SIZE, CLOSE_SIZE)
		val room = closeBox.x - 16f - (barX + 14f)
		UiText.draw(graphics, UiText.fit(subtitle.first, 14f, room), barX + 14f, box.y + (box.h - 14f) / 2f, 14f, fade(subtitle.second, alpha))

		closeHover = approach(closeHover, if (closeBox.contains(mx, my)) 1f else 0f, dt * 10f)
		if (closeHover > 0f) {
			RoundRect.fill(graphics, closeBox.x, closeBox.y, closeBox.right, closeBox.bottom, fade(MenuStyle.BUTTON_HOVER, alpha * closeHover), 6f)
		}
		UiText.drawIconCentered(graphics, MenuStyle.Icons.XMARK, closeBox.x, closeBox.y, closeBox.w, 13f,
			fade(MenuStyle.blend(MenuStyle.TAB_HOVER, MenuStyle.TEXT, closeHover), alpha))

		// Hypixel's ad, as a hairline progress along the header's foot.
		val offer = (state as? State.Ready)?.offer
		if (offer != null && offer.adSeconds > 0 && claimed < 0) {
			val done = (1f - adRemaining(offer) / offer.adSeconds.toFloat()).coerceIn(0f, 1f)
			val bar = Box(box.x + PANEL_RADIUS, box.bottom - 4f, box.w - PANEL_RADIUS * 2f, 2f)
			RoundRect.fill(graphics, bar.x, bar.y, bar.right, bar.bottom, fade(MenuStyle.TRACK, alpha), 1f)
			RoundRect.fill(graphics, bar.x, bar.y, bar.x + bar.w * done, bar.bottom, fade(MenuStyle.STAR, alpha), 1f)
		}
	}

	private fun drawLoading(graphics: GuiGraphicsExtractor, content: Box, alpha: Float) {
		val cx = content.x + content.w / 2f
		val cy = content.y + content.h / 2f - 12f
		val time = System.currentTimeMillis() / 1000.0
		for (i in 0 until 3) {
			val pulse = ((sin(time * 5.0 - i * 0.8) + 1.0) / 2.0).toFloat()
			val r = 4f + pulse * 2f
			val x = cx - 22f + i * 22f
			RoundRect.fill(graphics, x - r, cy - r, x + r, cy + r, fade(MenuStyle.blend(MenuStyle.TRACK, MenuStyle.accent(), pulse), alpha), r)
		}
		centered(graphics, "Fetching your reward…", cx, cy + 22f, 13f, fade(MenuStyle.MUTED, alpha))
	}

	private fun drawFailed(graphics: GuiGraphicsExtractor, failed: State.Failed, content: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val cx = content.x + content.w / 2f
		val cy = content.y + content.h / 2f - 60f
		UiText.drawIconCentered(graphics, MenuStyle.Icons.WARNING, cx - 20f, cy - 52f, 40f, 26f, fade(MenuStyle.DANGER, alpha))
		centered(graphics, "Couldn't read today's reward", cx, cy, 18f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		wrap(failed.reason, 13f, content.w - 200f, 3).forEachIndexed { line, text ->
			centered(graphics, text, cx, cy + 30f + line * 18f, 13f, fade(MenuStyle.CARD_DESC, alpha))
		}
		browserBox = if (failed.link != null) Box(cx - 100f, cy + 100f, 200f, BUTTON_H) else Box.NONE
		if (failed.link != null) {
			browserHover = approach(browserHover, if (browserBox.contains(mx, my)) 1f else 0f, dt * 10f)
			primaryButton(graphics, browserBox, "Open in your browser", browserHover, alpha)
		}
	}

	private fun drawOffer(graphics: GuiGraphicsExtractor, offer: RewardOffer, content: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val count = offer.cards.size.coerceAtMost(3)
		val total = count * CARD_W + (count - 1) * CARD_GAP
		val left = content.x + (content.w - total) / 2f
		val top = content.y + CONTENT_PAD
		val best = bestRevealed(offer)

		for (i in 0 until count) {
			if (flipping[i] && flipDelay[i] > 0f) {
				flipDelay[i] -= dt
			} else if (flipping[i]) {
				flip[i] = min(1f, flip[i] + dt / FLIP_SECONDS)
			}
			if (flip[i] >= 0.5f && !revealedSound[i]) {
				revealedSound[i] = true
				playReveal(offer.cards[i].rarity)
			}

			val box = Box(left + i * (CARD_W + CARD_GAP), top, CARD_W, CARD_H)
			cardBoxes[i] = box
			val hovered = box.contains(mx, my) && claimed < 0
			lift[i] = approach(lift[i], if (hovered) 1f else 0f, dt * 10f)
			drawCard(graphics, offer, i, box, mx, my, dt, alpha, i == best)
		}

		drawStreak(graphics, offer, Box(left, top + CARD_H + 18f, total, STREAK_H), alpha)
	}

	private fun drawCard(
		graphics: GuiGraphicsExtractor, offer: RewardOffer, index: Int, box: Box,
		mx: Float, my: Float, dt: Float, alpha: Float, best: Boolean,
	) {
		val card = offer.cards[index]
		val hover = lift[index]
		val y = box.y - MenuStyle.easeOut(hover) * 4f
		val x0 = box.x
		val x1 = box.right
		val y1 = y + box.h
		val cx = box.x + box.w / 2f
		val dim = if (claimed >= 0 && claimed != index) 0.45f else 1f
		val a = alpha * dim

		// Turning over: the card narrows to its edge and opens on the other face.
		val turn = flip[index]
		val squash = max(0.02f, abs(cos(turn * PI).toFloat()))
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(cx, 0f)
		pose.scale(squash, 1f)
		pose.translate(-cx, 0f)

		val faceUp = turn >= 0.5f
		if (!faceUp) {
			RoundRect.fill(graphics, x0, y, x1, y1, fade(MenuStyle.blend(MenuStyle.CARD_OFF, MenuStyle.cardTintHover(), hover), a), CARD_RADIUS)
			RoundRect.outline(graphics, x0, y, x1, y1, fade(MenuStyle.blend(MenuStyle.CARD_EDGE, MenuStyle.cardOnEdge(), hover), a), CARD_RADIUS, 1f)
			val mid = y + box.h / 2f
			UiText.drawIconCentered(graphics, GIFT, cx - 30f, mid - 76f, 60f, 40f, fade(MenuStyle.blend(MenuStyle.CARD_ICON, MenuStyle.accent(), hover), a))
			centered(graphics, "Card ${index + 1}", cx, mid + 2f, 16f, fade(MenuStyle.TEXT, a), UiText.Face.MEDIUM)
			centered(graphics, "Click to turn over", cx, mid + 26f, 12f, fade(MenuStyle.CARD_DESC, a))
			keyBadge(graphics, "${index + 1}", x1 - 14f, y + 14f, a)
			buttonBoxes[index] = Box.NONE
		} else {
			val rarity = card.rarity.color
			val highlight = best && DailyRewards.highlightBest.value
			if (highlight) {
				RoundRect.shadow(graphics, x0, y, x1, y1, fade((rarity and 0xFFFFFF) or 0x66000000, a), CARD_RADIUS, 22f)
			}
			RoundRect.fill(graphics, x0, y, x1, y1, fade(MenuStyle.CARD_OFF, a), CARD_RADIUS)
			RoundRect.gradient(graphics, x0, y, x1, y + 140f, fade((rarity and 0xFFFFFF) or 0x1E000000, a), 0x00000000, CARD_RADIUS)
			val edge = if (highlight) rarity else MenuStyle.blend(MenuStyle.CARD_EDGE, rarity, 0.45f)
			RoundRect.outline(graphics, x0, y, x1, y1, fade(edge, a), CARD_RADIUS, 1f)

			rarityBadge(graphics, card.rarity, cx, y + 16f, a)
			val button = Box(x0 + 16f, y1 - 16f - BUTTON_H, box.w - 32f, BUTTON_H)

			// The icon and text, centred between the badge and the button.
			val name = wrap(card.name, 15f, box.w - 28f, 2, UiText.Face.MEDIUM)
			val description = wrap(card.description, 12f, box.w - 32f, 3)
			val amountH = if (card.amount > 1) 30f else 0f
			val blockH = 48f + 16f + amountH + name.size * 19f + 4f + description.size * 16f
			val areaTop = y + 46f
			var textY = areaTop + ((button.y - 8f - areaTop) - blockH) / 2f
			drawIcon(graphics, card, cx, textY, a)
			textY += 48f + 16f
			if (card.amount > 1) {
				centered(graphics, "%,d".format(card.amount), cx, textY, 22f, fade(MenuStyle.TEXT, a), UiText.Face.MEDIUM)
				textY += amountH
			}
			for (line in name) {
				centered(graphics, line, cx, textY, 15f, fade(rarity, a), UiText.Face.MEDIUM)
				textY += 19f
			}
			textY += 4f
			for (line in description) {
				centered(graphics, line, cx, textY, 12f, fade(MenuStyle.CARD_DESC, a))
				textY += 16f
			}

			drawClaimButton(graphics, offer, index, button, mx, my, dt, a)
		}
		pose.popMatrix()
	}

	/** A key's name in a small box, as the menu's keybind badges; [right] is its right edge. */
	private fun keyBadge(graphics: GuiGraphicsExtractor, text: String, right: Float, y: Float, a: Float) {
		val size = 11f
		val w = max(24f, UiText.width(text, size, UiText.Face.MEDIUM) + 14f)
		val h = 22f
		RoundRect.fill(graphics, right - w, y, right, y + h, fade(MenuStyle.FIELD, a), 5f)
		RoundRect.outline(graphics, right - w, y, right, y + h, fade(MenuStyle.FIELD_EDGE, a), 5f, 1f)
		centered(graphics, text, right - w / 2f, y + (h - size) / 2f, size, fade(MenuStyle.MUTED, a), UiText.Face.MEDIUM)
	}

	/** The rarity as a small tinted pill, centred on [cx]. */
	private fun rarityBadge(graphics: GuiGraphicsExtractor, rarity: RewardRarity, cx: Float, y: Float, a: Float) {
		val label = rarity.label.uppercase()
		val size = 11f
		val tracking = 1f
		val w = UiText.trackedWidth(label, size, UiText.Face.MEDIUM, tracking) + 20f
		val h = 22f
		val color = rarity.color
		RoundRect.fill(graphics, cx - w / 2f, y, cx + w / 2f, y + h, fade((color and 0xFFFFFF) or 0x24000000, a), h / 2f)
		RoundRect.outline(graphics, cx - w / 2f, y, cx + w / 2f, y + h, fade((color and 0xFFFFFF) or 0x55000000, a), h / 2f, 1f)
		UiText.drawTracked(graphics, label, cx - w / 2f + 10f, y + (h - size) / 2f, size, fade(color, a), UiText.Face.MEDIUM, tracking)
	}

	private fun drawClaimButton(
		graphics: GuiGraphicsExtractor, offer: RewardOffer, index: Int, box: Box,
		mx: Float, my: Float, dt: Float, a: Float,
	) {
		val ad = adRemaining(offer)
		val usable = claimed < 0 && claiming < 0 && ad <= 0
		buttonBoxes[index] = if (usable) box else Box.NONE
		when {
			claimed == index -> {
				RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade((MenuStyle.accent() and 0xFFFFFF) or 0x2E000000, a), 6f)
				RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.cardOnEdge(), a), 6f, 1f)
				val label = "Claimed"
				val width = UiText.width(label, 13f, UiText.Face.MEDIUM) + 20f
				val start = box.x + (box.w - width) / 2f
				val textY = box.y + (box.h - 13f) / 2f
				UiText.drawIcon(graphics, MenuStyle.Icons.CHECK, start, textY, 13f, fade(MenuStyle.onMuted(), a))
				UiText.draw(graphics, label, start + 20f, textY, 13f, fade(MenuStyle.onMuted(), a), UiText.Face.MEDIUM)
			}
			claimed >= 0 -> Unit
			claiming == index -> disabledButton(graphics, box, "Claiming…", a)
			ad > 0 -> disabledButton(graphics, box, "Claim in ${ad}s", a)
			else -> {
				buttonHover[index] = approach(buttonHover[index], if (box.contains(mx, my)) 1f else 0f, dt * 10f)
				primaryButton(graphics, box, "Claim", buttonHover[index], a)
			}
		}
	}

	/** The menu's primary button: the accent, a shade lighter while hovered. */
	private fun primaryButton(graphics: GuiGraphicsExtractor, box: Box, label: String, hover: Float, a: Float) {
		val fill = MenuStyle.blend(MenuStyle.accent(), MenuStyle.scale(MenuStyle.accent(), 1.15f), hover)
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(fill, a), 6f)
		centered(graphics, label, box.x + box.w / 2f, box.y + (box.h - 13f) / 2f, 13f, fade(0xFFFFFFFF.toInt(), a), UiText.Face.MEDIUM)
	}

	private fun disabledButton(graphics: GuiGraphicsExtractor, box: Box, label: String, a: Float) {
		MenuStyle.drawField(graphics, box, false, false, a)
		centered(graphics, label, box.x + box.w / 2f, box.y + (box.h - 13f) / 2f, 13f, fade(MenuStyle.MUTED, a), UiText.Face.MEDIUM)
	}

	private fun drawIcon(graphics: GuiGraphicsExtractor, card: RewardCard, cx: Float, y: Float, a: Float) {
		if (a < 0.5f) return
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(cx - 24f, y)
		pose.scale(3f, 3f)
		graphics.item(iconFor(card), 0, 0)
		pose.popMatrix()
	}

	/** The streak, in a group panel like the menu's settings: its name and counts, then the steps to the next box. */
	private fun drawStreak(graphics: GuiGraphicsExtractor, offer: RewardOffer, box: Box, alpha: Float) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.ROW, alpha), GROUP_RADIUS)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.GROUP_EDGE, alpha), GROUP_RADIUS, 1f)

		val x = box.x + ROW_PAD
		val inner = box.w - ROW_PAD * 2f
		val y = box.y + 16f
		UiText.drawIcon(graphics, FIRE, x, y, 14f, fade(MenuStyle.STAR, alpha))
		UiText.draw(graphics, "Daily streak", x + 22f, y, 14f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		val streak = if (claimed >= 0 && !offer.sample) offer.streak + 1 else offer.streak
		val parts = listOf("Current " to MenuStyle.MUTED, "$streak" to MenuStyle.TEXT, "   Best " to MenuStyle.MUTED, "${max(streak, offer.bestStreak)}" to MenuStyle.TEXT)
		var penX = x + inner - parts.sumOf { UiText.width(it.first, 13f).toDouble() }.toFloat()
		for ((text, color) in parts) {
			UiText.draw(graphics, text, penX, y + 1f, 13f, fade(color, alpha))
			penX += UiText.width(text, 13f)
		}

		val steps = 9
		val gap = 6f
		val pip = (inner - gap * (steps - 1)) / steps
		val filled = (if (claimed >= 0 && !offer.sample) offer.streakStep + 1 else offer.streakStep).coerceIn(0, steps)
		val pipY = box.bottom - 22f
		for (i in 0 until steps) {
			val px = x + i * (pip + gap)
			val color = if (i < filled) MenuStyle.accent() else MenuStyle.TRACK
			RoundRect.fill(graphics, px, pipY, px + pip, pipY + 6f, fade(color, alpha), 3f)
		}
	}

	private fun centered(graphics: GuiGraphicsExtractor, text: String, cx: Float, y: Float, size: Float, color: Int, face: UiText.Face = UiText.Face.REGULAR) {
		UiText.draw(graphics, text, cx - UiText.width(text, size, face) / 2f, y, size, color, face)
	}

	/** [text] in at most [lines] lines no wider than [width], the last cut short if needed. */
	private fun wrap(text: String, size: Float, width: Float, lines: Int, face: UiText.Face = UiText.Face.REGULAR): List<String> {
		val out = mutableListOf<String>()
		var line = ""
		val words = text.split(' ').filter { it.isNotEmpty() }
		for ((index, word) in words.withIndex()) {
			val next = if (line.isEmpty()) word else "$line $word"
			if (UiText.width(next, size, face) <= width || line.isEmpty()) {
				line = next
				continue
			}
			out += line
			line = word
			if (out.size == lines - 1) {
				line = words.drop(index).joinToString(" ")
				break
			}
		}
		if (line.isNotEmpty()) out += UiText.fit(line, size, width, face)
		return out.take(lines)
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val k = scale()
		val mx = (event.x() / k).toFloat()
		val my = (event.y() / k).toFloat()
		if (event.button() != 0) return super.mouseClicked(event, doubleClick)

		if (closeBox.contains(mx, my)) {
			onClose()
			return true
		}
		when (val current = state) {
			is State.Failed -> if (browserBox.contains(mx, my) && current.link != null) {
				Util.getPlatform().openUri(URI.create(current.link))
				return true
			}
			is State.Ready -> {
				for (i in current.offer.cards.indices.take(3)) {
					if (buttonBoxes[i].contains(mx, my)) {
						claim(current.offer, i)
						return true
					}
					if (cardBoxes[i].contains(mx, my)) {
						turnOver(i)
						return true
					}
				}
			}
			State.Loading -> Unit
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val index = event.key() - GLFW.GLFW_KEY_1
		val current = state
		if (current is State.Ready && index in 0 until current.offer.cards.size.coerceAtMost(3)) {
			// The number key turns a card over, then claims it.
			if (flip[index] >= 1f && buttonBoxes[index] != Box.NONE) claim(current.offer, index) else turnOver(index)
			return true
		}
		return super.keyPressed(event)
	}

	private fun turnOver(index: Int) {
		if (flipping[index] || claimed >= 0) return
		if (DailyRewards.reveal.selectedIndex != DailyRewards.REVEAL_ONE) {
			var order = 0
			for (i in listOf(index) + (0 until 3).filter { it != index }) {
				if (flipping[i]) continue
				flipping[i] = true
				flipDelay[i] = order++ * 0.14f
			}
		} else {
			flipping[index] = true
		}
	}

	private fun claim(offer: RewardOffer, index: Int) {
		if (claimed >= 0 || claiming >= 0 || adRemaining(offer) > 0) return
		claiming = index
		claimError = null
		DailyRewards.claim(offer, index) { error ->
			claiming = -1
			if (error == null) {
				claimed = index
				if (DailyRewards.sounds.value) minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2f))
				if (DailyRewards.closeOnClaim.value) closeAt = System.currentTimeMillis() + CLOSE_DELAY_MS
			} else {
				claimError = error
			}
		}
	}

	private fun playReveal(rarity: RewardRarity) {
		if (!DailyRewards.sounds.value) return
		val sound = when (rarity) {
			RewardRarity.LEGENDARY -> SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f)
			RewardRarity.EPIC -> SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f)
			RewardRarity.RARE -> SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.3f)
			RewardRarity.COMMON -> SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1f)
		}
		minecraft.soundManager.play(sound)
	}

	private fun adRemaining(offer: RewardOffer): Int {
		if (offer.adSeconds <= 0) return 0
		val passed = ((System.currentTimeMillis() - shownAt) / 1000).toInt()
		return (offer.adSeconds - passed).coerceAtLeast(0)
	}

	private fun bestRevealed(offer: RewardOffer): Int {
		var best = -1
		for (i in offer.cards.indices.take(3)) {
			if (flip[i] < 0.5f) continue
			if (best < 0 || offer.cards[i].rarity.ordinal > offer.cards[best].rarity.ordinal) best = i
		}
		// Only worth a glow when it stands out.
		return if (best >= 0 && offer.cards.take(3).count { it.rarity == offer.cards[best].rarity } == 1) best else -1
	}

	private fun resetText(): String {
		val until = DailyRewards.untilReset()
		return "${until.toHours()}h ${until.toMinutesPart()}m"
	}

	private fun approach(value: Float, target: Float, step: Float): Float =
		if (value < target) min(target, value + step) else max(target, value - step)

	companion object {
		// The menu's design height and panel style, so the two look alike.
		private const val DESIGN_HEIGHT = 1350f
		private const val OPEN_SECONDS = 0.22f
		private const val PANEL_GAP = 12f
		private const val PANEL_RADIUS = 10f
		private const val PANEL_SHADOW = 0x38000000
		private const val HEADER_H = 58f
		private const val HEADER_PAD = 16f
		private const val CLOSE_SIZE = 30f
		private const val CONTENT_PAD = 24f
		private const val GROUP_RADIUS = 8f
		private const val ROW_PAD = 20f
		private const val BUTTON_H = 34f

		private const val CARD_W = 240f
		private const val CARD_H = 330f
		private const val CARD_GAP = 16f
		private const val CARD_RADIUS = 8f
		private const val STREAK_H = 76f
		private const val WIN_W = 3 * CARD_W + 2 * CARD_GAP + 2 * CONTENT_PAD
		private const val CONTENT_H = CONTENT_PAD + CARD_H + 18f + STREAK_H + CONTENT_PAD
		private const val FLIP_SECONDS = 0.38f
		private const val CLOSE_DELAY_MS = 800L

		private val GIFT = Char(0xF06B).toString()
		private val FIRE = Char(0xF06D).toString()

		fun loading() = RewardScreen()

		fun ready(offer: RewardOffer) = RewardScreen().also { it.show(offer) }

		private fun iconFor(card: RewardCard): ItemStack = ItemStack(
			when (card.type) {
				"coins" -> Items.GOLD_INGOT
				"tokens" -> Items.EMERALD
				"experience" -> Items.EXPERIENCE_BOTTLE
				"dust" -> Items.GLOWSTONE_DUST
				"mystery_box" -> Items.ENDER_CHEST
				"souls" -> Items.SOUL_LANTERN
				"housing_package" -> Items.PLAYER_HEAD
				"add_vanity" -> Items.LEATHER_CHESTPLATE
				"adsense_token" -> Items.SUNFLOWER
				else -> Items.CHEST
			},
		)
	}
}
