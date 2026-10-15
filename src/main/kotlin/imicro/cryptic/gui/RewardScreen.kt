package imicro.cryptic.gui

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
 * The daily reward picker: Hypixel's three cards, turned over and claimed in
 * game, in the look of Cryptic's menu.
 *
 * Laid out in design pixels at 1080p and scaled to the window, as the menu is,
 * so it is the same size whatever the GUI scale.
 */
class RewardScreen private constructor() : Screen(Component.literal("Daily Reward")) {
	private sealed class State {
		data object Loading : State()
		class Ready(val offer: RewardOffer) : State()
		class Failed(val reason: String, val link: String?) : State()
	}

	private var state: State = State.Loading

	/** When the cards arrived, for the ad's countdown. */
	private var shownAt = 0L

	/** How far each card has turned over, 0 face down to 1 face up, and where it is going. */
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

	fun show(offer: RewardOffer) {
		state = State.Ready(offer)
		shownAt = System.currentTimeMillis()
		// "Already turned over": face up from the start, with no turning and no
		// sound for each card.
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

	/** Set when you close the picker yourself, which is the only close let through while it opens. */
	var closedByUser = false
		private set

	override fun onClose() {
		closedByUser = true
		super.onClose()
	}

	/** "Close menu on reward claim", once "Claimed" has had a moment on screen. */
	override fun tick() {
		super.tick()
		if (closeAt != 0L && System.currentTimeMillis() >= closeAt) {
			closeAt = 0L
			onClose()
		}
	}

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		graphics.fill(0, 0, width, height, 0x99000000.toInt())
	}

	/** Design pixels per GUI unit: 1080p design, whatever the GUI scale. */
	private fun scale(): Float {
		val window = minecraft.window
		return (window.screenHeight / DESIGN_HEIGHT) / window.guiScale.toFloat()
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		val now = System.nanoTime()
		val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
		lastFrame = now
		opening = min(1f, opening + dt / 0.18f)
		val alpha = MenuStyle.easeOut(opening)

		UiText.pixelsPerUnit = minecraft.window.screenHeight / DESIGN_HEIGHT
		val k = scale()
		val mx = mouseX / k
		val my = mouseY / k
		val viewW = width / k
		val viewH = height / k

		graphics.pose().pushMatrix()
		graphics.pose().scale(k, k)

		val winW = min(WIN_W, viewW - 40f)
		val winH = min(WIN_H, viewH - 40f)
		val winX = (viewW - winW) / 2f
		val winY = (viewH - winH) / 2f + (1f - alpha) * 14f

		RoundRect.shadow(graphics, winX, winY, winX + winW, winY + winH, fade(0xB4000000.toInt(), alpha), 8f, 24f)
		RoundRect.fill(graphics, winX, winY, winX + winW, winY + winH, fade(MenuStyle.WINDOW_BG, alpha), 8f)
		RoundRect.outline(graphics, winX, winY, winX + winW, winY + winH, fade(MenuStyle.WINDOW_EDGE, alpha), 8f, 1f)

		drawHeader(graphics, winX, winY, winW, mx, my, dt, alpha)

		when (val current = state) {
			State.Loading -> drawLoading(graphics, winX, winY, winW, winH, alpha)
			is State.Failed -> drawFailed(graphics, current, winX, winY, winW, winH, mx, my, alpha)
			is State.Ready -> drawOffer(graphics, current.offer, winX, winY, winW, mx, my, dt, alpha)
		}

		graphics.pose().popMatrix()
	}

	private fun drawHeader(graphics: GuiGraphicsExtractor, winX: Float, winY: Float, winW: Float, mx: Float, my: Float, dt: Float, alpha: Float) {
		UiText.drawIcon(graphics, GIFT, winX + 28f, winY + 26f, 22f, fade(MenuStyle.accent(), alpha))
		UiText.draw(graphics, "Daily Reward", winX + 60f, winY + 24f, 22f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)

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
		UiText.draw(graphics, UiText.fit(subtitle.first, 13f, winW - 120f), winX + 28f, winY + 56f, 13f, fade(subtitle.second, alpha))

		closeBox = Box(winX + winW - 52f, winY + 20f, 30f, 30f)
		val hovered = closeBox.contains(mx, my)
		closeHover = approach(closeHover, if (hovered) 1f else 0f, dt * 10f)
		val color = MenuStyle.blend(MenuStyle.MUTED, MenuStyle.DANGER, closeHover)
		UiText.drawIconCentered(graphics, MenuStyle.Icons.XMARK, closeBox.x, closeBox.y, closeBox.w, 18f, fade(color, alpha))
	}

	private fun drawLoading(graphics: GuiGraphicsExtractor, winX: Float, winY: Float, winW: Float, winH: Float, alpha: Float) {
		val cx = winX + winW / 2f
		val cy = winY + winH / 2f
		val time = System.currentTimeMillis() / 1000.0
		for (i in 0 until 3) {
			val pulse = ((sin(time * 5.0 - i * 0.8) + 1.0) / 2.0).toFloat()
			val r = 5f + pulse * 2f
			val x = cx - 24f + i * 24f
			RoundRect.fill(graphics, x - r, cy - r, x + r, cy + r, fade(MenuStyle.blend(MenuStyle.TRACK, MenuStyle.accent(), pulse), alpha), r)
		}
		centered(graphics, "Fetching your reward…", cx, cy + 24f, 14f, fade(MenuStyle.MUTED, alpha))
	}

	private fun drawFailed(
		graphics: GuiGraphicsExtractor, failed: State.Failed,
		winX: Float, winY: Float, winW: Float, winH: Float, mx: Float, my: Float, alpha: Float,
	) {
		val cx = winX + winW / 2f
		val cy = winY + winH / 2f - 30f
		centered(graphics, "Couldn't read today's reward", cx, cy, 18f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		wrap(failed.reason, 13f, winW - 160f, 3).forEachIndexed { line, text ->
			centered(graphics, text, cx, cy + 32f + line * 18f, 13f, fade(MenuStyle.MUTED, alpha))
		}
		browserBox = if (failed.link != null) Box(cx - 100f, cy + 100f, 200f, 36f) else Box.NONE
		if (failed.link != null) {
			val hovered = browserBox.contains(mx, my)
			RoundRect.fill(graphics, browserBox.x, browserBox.y, browserBox.x + browserBox.w, browserBox.y + browserBox.h,
				fade(if (hovered) MenuStyle.cardOnHover() else MenuStyle.cardOn(), alpha), 8f)
			centered(graphics, "Open in your browser", cx, browserBox.y + 10f, 14f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		}
	}

	private fun drawOffer(
		graphics: GuiGraphicsExtractor, offer: RewardOffer,
		winX: Float, winY: Float, winW: Float, mx: Float, my: Float, dt: Float, alpha: Float,
	) {
		// The ad's progress, under the header, while it runs.
		val adTotal = offer.adSeconds
		if (adTotal > 0 && claimed < 0) {
			val left = adRemaining(offer)
			val done = 1f - left / adTotal.toFloat()
			val barX = winX + 28f
			val barW = winW - 56f
			RoundRect.fill(graphics, barX, winY + 80f, barX + barW, winY + 84f, fade(MenuStyle.TRACK, alpha), 2f)
			RoundRect.fill(graphics, barX, winY + 80f, barX + barW * done.coerceIn(0f, 1f), winY + 84f, fade(MenuStyle.STAR, alpha), 2f)
		}

		val count = offer.cards.size.coerceAtMost(3)
		val total = count * CARD_W + (count - 1) * CARD_GAP
		val left = winX + (winW - total) / 2f
		val top = winY + 100f
		val best = bestRevealed(offer)

		for (i in 0 until count) {
			// A card waiting its turn in "all at once" counts its delay down first.
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

		drawStreak(graphics, offer, left, top + CARD_H + 30f, total, alpha)
	}

	private fun drawCard(
		graphics: GuiGraphicsExtractor, offer: RewardOffer, index: Int, box: Box,
		mx: Float, my: Float, dt: Float, alpha: Float, best: Boolean,
	) {
		val card = offer.cards[index]
		val y = box.y - MenuStyle.easeOut(lift[index]) * 6f
		val x0 = box.x
		val x1 = box.x + box.w
		val y1 = y + box.h
		val cx = box.x + box.w / 2f
		val dim = if (claimed >= 0 && claimed != index) 0.45f else 1f
		val a = alpha * dim

		// Turning over: the card narrows to its edge and opens out on the other face.
		val turn = flip[index]
		val squash = max(0.02f, abs(cos(turn * PI).toFloat()))
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(cx, 0f)
		pose.scale(squash, 1f)
		pose.translate(-cx, 0f)

		val faceUp = turn >= 0.5f
		if (faceUp && best && DailyRewards.highlightBest.value) {
			RoundRect.shadow(graphics, x0, y, x1, y1, fade((card.rarity.color and 0xFFFFFF) or 0x99000000.toInt(), a), CARD_RADIUS, 26f)
		} else {
			RoundRect.shadow(graphics, x0, y, x1, y1, fade(0x66000000, a), CARD_RADIUS, 14f)
		}

		if (!faceUp) {
			val hover = lift[index]
			RoundRect.fill(graphics, x0, y, x1, y1, fade(MenuStyle.blend(MenuStyle.CARD_OFF, MenuStyle.CARD_OFF_HOVER, hover), a), CARD_RADIUS)
			RoundRect.gradient(graphics, x0, y, x1, y1, fade((MenuStyle.accent() and 0xFFFFFF) or 0x30000000, a), 0x00000000, CARD_RADIUS)
			RoundRect.outline(graphics, x0, y, x1, y1, fade((MenuStyle.accent() and 0xFFFFFF) or ((0x55 + (hover * 0x60).toInt()) shl 24), a), CARD_RADIUS, 1.5f)
			val iconWidth = UiText.width(GIFT, 52f, UiText.Face.ICON)
			UiText.drawIcon(graphics, GIFT, cx - iconWidth / 2f, y + box.h / 2f - 74f, 52f, fade(MenuStyle.accent(), a))
			centered(graphics, "Card ${index + 1}", cx, y + box.h / 2f + 6f, 16f, fade(MenuStyle.TEXT, a), UiText.Face.MEDIUM)
			centered(graphics, "Click to turn over", cx, y + box.h / 2f + 30f, 12f, fade(MenuStyle.MUTED, a))
			keyHint(graphics, index, x1, y, a)
			buttonBoxes[index] = Box.NONE
		} else {
			val rarity = card.rarity.color
			RoundRect.fill(graphics, x0, y, x1, y1, fade(0xFF18181B.toInt(), a), CARD_RADIUS)
			RoundRect.gradient(graphics, x0, y, x1, y + 150f, fade((rarity and 0xFFFFFF) or 0x48000000, a), 0x00000000, CARD_RADIUS)
			RoundRect.outline(graphics, x0, y, x1, y1, fade((rarity and 0xFFFFFF) or 0xAA000000.toInt(), a), CARD_RADIUS, 1.5f)

			centered(graphics, card.rarity.label.uppercase(), cx, y + 16f, 12f, fade(rarity, a), UiText.Face.MEDIUM)
			drawIcon(graphics, card, cx, y + 42f, a)

			var textY = y + 104f
			if (card.amount > 1) {
				centered(graphics, "%,d".format(card.amount), cx, textY, 22f, fade(MenuStyle.TEXT, a), UiText.Face.MEDIUM)
				textY += 30f
			}
			wrap(card.name, 15f, box.w - 28f, 2, UiText.Face.MEDIUM).forEach { line ->
				centered(graphics, line, cx, textY, 15f, fade(rarity, a), UiText.Face.MEDIUM)
				textY += 19f
			}
			textY += 4f
			wrap(card.description, 12f, box.w - 32f, 3).forEach { line ->
				centered(graphics, line, cx, textY, 12f, fade(MenuStyle.MUTED, a))
				textY += 16f
			}

			drawClaimButton(graphics, offer, index, Box(x0 + 18f, y1 - 52f, box.w - 36f, 34f), mx, my, dt, a)
		}
		pose.popMatrix()
	}

	/** The card's number, top right, for the 1, 2 and 3 keys. */
	private fun keyHint(graphics: GuiGraphicsExtractor, index: Int, x1: Float, y: Float, a: Float) {
		RoundRect.fill(graphics, x1 - 34f, y + 12f, x1 - 12f, y + 34f, fade(MenuStyle.TAB_PILL, a), 6f)
		centered(graphics, "${index + 1}", x1 - 23f, y + 15f, 12f, fade(MenuStyle.MUTED, a), UiText.Face.MEDIUM)
	}

	private fun drawClaimButton(
		graphics: GuiGraphicsExtractor, offer: RewardOffer, index: Int, box: Box,
		mx: Float, my: Float, dt: Float, a: Float,
	) {
		val ad = adRemaining(offer)
		val label: String
		val fill: Int
		val text: Int
		val usable = claimed < 0 && claiming < 0 && ad <= 0
		when {
			claimed == index -> { label = "Claimed"; fill = 0xFF2E6B34.toInt(); text = MenuStyle.TEXT }
			claimed >= 0 -> { buttonBoxes[index] = Box.NONE; return }
			claiming == index -> { label = "Claiming…"; fill = MenuStyle.TRACK; text = MenuStyle.MUTED }
			ad > 0 -> { label = "Claim in ${ad}s"; fill = MenuStyle.TRACK; text = MenuStyle.MUTED }
			else -> {
				label = "Claim"
				buttonHover[index] = approach(buttonHover[index], if (box.contains(mx, my)) 1f else 0f, dt * 10f)
				fill = MenuStyle.blend(MenuStyle.cardOn(), MenuStyle.accent(), buttonHover[index])
				text = MenuStyle.TEXT
			}
		}
		buttonBoxes[index] = if (usable) box else Box.NONE
		RoundRect.fill(graphics, box.x, box.y, box.x + box.w, box.y + box.h, fade(fill, a), 8f)
		if (claimed == index) {
			val width = UiText.width(label, 14f, UiText.Face.MEDIUM) + 22f
			val start = box.x + (box.w - width) / 2f
			UiText.drawIcon(graphics, MenuStyle.Icons.CHECK, start, box.y + 9f, 14f, fade(text, a))
			UiText.draw(graphics, label, start + 22f, box.y + 9f, 14f, fade(text, a), UiText.Face.MEDIUM)
		} else {
			centered(graphics, label, box.x + box.w / 2f, box.y + 9f, 14f, fade(text, a), UiText.Face.MEDIUM)
		}
	}

	/** The reward's item, three times its size, centred on [cx]. */
	private fun drawIcon(graphics: GuiGraphicsExtractor, card: RewardCard, cx: Float, y: Float, a: Float) {
		if (a < 0.5f) return
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(cx - 24f, y)
		pose.scale(3f, 3f)
		graphics.item(iconFor(card), 0, 0)
		pose.popMatrix()
	}

	private fun drawStreak(graphics: GuiGraphicsExtractor, offer: RewardOffer, x: Float, y: Float, width: Float, alpha: Float) {
		UiText.drawIcon(graphics, FIRE, x, y, 15f, fade(MenuStyle.STAR, alpha))
		UiText.draw(graphics, "Daily streak", x + 22f, y, 15f, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		val streak = if (claimed >= 0 && !offer.sample) offer.streak + 1 else offer.streak
		val right = "Current §f$streak  §7Best §f${max(streak, offer.bestStreak)}"
		val plain = right.replace(Regex("§."), "")
		val rightX = x + width - UiText.width(plain, 13f)
		// Two tones by hand: the counts in white, their labels muted.
		var penX = rightX
		for ((text, color) in listOf("Current " to MenuStyle.MUTED, "$streak" to MenuStyle.TEXT, "   Best " to MenuStyle.MUTED, "${max(streak, offer.bestStreak)}" to MenuStyle.TEXT)) {
			UiText.draw(graphics, text, penX, y + 1f, 13f, fade(color, alpha))
			penX += UiText.width(text, 13f)
		}

		val steps = 9
		val gap = 8f
		val pip = (width - gap * (steps - 1)) / steps
		val filled = (if (claimed >= 0 && !offer.sample) offer.streakStep + 1 else offer.streakStep).coerceIn(0, steps)
		for (i in 0 until steps) {
			val px = x + i * (pip + gap)
			val color = if (i < filled) MenuStyle.accent() else MenuStyle.TRACK
			RoundRect.fill(graphics, px, y + 28f, px + pip, y + 36f, fade(color, alpha), 4f)
		}
	}

	private fun centered(graphics: GuiGraphicsExtractor, text: String, cx: Float, y: Float, size: Float, color: Int, face: UiText.Face = UiText.Face.REGULAR) {
		UiText.draw(graphics, text, cx - UiText.width(text, size, face) / 2f, y, size, color, face)
	}

	/** [text] broken into at most [lines] lines no wider than [width], the last cut short if need be. */
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

	// --- Input --------------------------------------------------------------

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
			// The number turns a card over, and once it is up, claims it.
			if (flip[index] >= 1f && buttonBoxes[index] != Box.NONE) claim(current.offer, index) else turnOver(index)
			return true
		}
		return super.keyPressed(event)
	}

	private fun turnOver(index: Int) {
		if (flipping[index] || claimed >= 0) return
		if (DailyRewards.reveal.selectedIndex != DailyRewards.REVEAL_ONE) {
			// The one clicked first, then the others one after another.
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
				// Long enough to see "Claimed" before it goes.
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

	// --- Helpers --------------------------------------------------------------

	private fun adRemaining(offer: RewardOffer): Int {
		if (offer.adSeconds <= 0) return 0
		val passed = ((System.currentTimeMillis() - shownAt) / 1000).toInt()
		return (offer.adSeconds - passed).coerceAtLeast(0)
	}

	/** The rarest card turned over, for the glow. */
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
		private const val DESIGN_HEIGHT = 1080f
		private const val WIN_W = 780f
		private const val WIN_H = 520f
		private const val CARD_W = 220f
		private const val CARD_H = 300f
		private const val CARD_GAP = 22f
		private const val CARD_RADIUS = 10f
		private const val FLIP_SECONDS = 0.38f
		private const val CLOSE_DELAY_MS = 800L

		/** Font Awesome's gift and fire. */
		private val GIFT = Char(0xF06B).toString()
		private val FIRE = Char(0xF06D).toString()

		fun loading() = RewardScreen()

		fun ready(offer: RewardOffer) = RewardScreen().also { it.show(offer) }

		/** What each kind of reward looks like, from Minecraft's own items. */
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
