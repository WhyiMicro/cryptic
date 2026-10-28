package imicro.cryptic.feature

import imicro.cryptic.skyblock.HypixelServer
import imicro.cryptic.mixin.ChatComponentAccessor
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW
import java.util.Optional
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Better Chat's work on the chat window itself: filters that hide lines but keep them in history,
 * a longer history kept across servers, repeated lines merged with a count, channel tabs, search
 * and a right-click menu. Ideas from Enhanced Chat and NoFrills; settings are on [BetterChat].
 */
object ChatExtras {
	private val FORMATTING = Regex("§.")

	private val client: Minecraft get() = Minecraft.getInstance()

	private fun plain(component: Component): String = component.string.replace(FORMATTING, "").trimStart()

	@JvmStatic
	fun keepsHistory(): Boolean = BetterChat.module.enabled && BetterChat.keepHistory.value

	@JvmStatic
	fun skipsConfirmation(): Boolean = BetterChat.module.enabled && BetterChat.noConfirm.value

	/** The vanilla 100, or Better Chat's longer history. */
	@JvmStatic
	fun historyLength(): Int =
		if (BetterChat.module.enabled && BetterChat.longHistory.value) BetterChat.historyLength.value.toInt() else VANILLA_HISTORY

	private const val VANILLA_HISTORY = 100

	// ---- Filters ------------------------------------------------------------

	enum class Tab(val label: String, val short: String) {
		ALL("All", "A"), USER("User", "User"), PARTY("Party", "P"), GUILD("Guild", "G"), PM("PM", "PM"), COOP("Co-op", "Co-op");

		val shown: String get() = if (BetterChat.shortTabs.value) short else label
	}

	var tab = Tab.ALL
		private set

	private var query = ""

	/** All chat from players: optional level, icon and rank, then a name and a colon. */
	private val PLAYER_LINE = Regex("""^(?:\[\d+]\s)?(?:\S\s)?(?:\[[^\]]+]\s)?\w{2,16}(?:\s\[[^\]]+])?: """)

	private fun tabOf(text: String): Tab = when {
		text.startsWith("Party > ") || text.startsWith("P > ") -> Tab.PARTY
		text.startsWith("Guild > ") || text.startsWith("G > ") || text.startsWith("Officer > ") -> Tab.GUILD
		text.startsWith("Co-op > ") -> Tab.COOP
		text.startsWith("From ") || text.startsWith("To ") -> Tab.PM
		PLAYER_LINE.containsMatchIn(text) -> Tab.USER
		else -> Tab.ALL
	}

	private fun isGuild(text: String) = text.startsWith("Guild > ") || text.startsWith("G > ")

	/** Whether the window leaves [message] out. It stays in history, so it returns with the filter off. */
	@JvmStatic
	fun hides(message: GuiMessage): Boolean {
		if (!BetterChat.module.enabled) return false
		val text = plain(message.content())
		if (BetterChat.hideGuild.value && isGuild(text) && tab != Tab.GUILD) return true
		// Hypixel's channels; elsewhere the tab is kept but doesn't filter.
		if (tab != Tab.ALL && HypixelServer.connected && tabOf(text) != tab) return true
		if (query.isNotBlank()) {
			val lower = text.lowercase()
			if (query.lowercase().split(' ').filter { it.isNotEmpty() }.any { it !in lower }) return true
		}
		return false
	}

	/** What the filters were when the window was last rebuilt. */
	private var applied: Triple<Boolean, Tab, String>? = null

	private fun refreshIfChanged() {
		val now = Triple(BetterChat.module.enabled && BetterChat.hideGuild.value, tab, query)
		if (now == applied) return
		applied = now
		(client.gui.hud.chat as ChatComponentAccessor).`cryptic$refresh`()
	}

	fun tick() = refreshIfChanged()

	// ---- Compacting ---------------------------------------------------------

	private class Repeat(val key: String, val base: Component, val count: Int)

	/** Every shown line's repeat record, by the component the window holds. */
	private val repeats = WeakHashMap<Component, Repeat>()

	private const val COMPACT_CONSECUTIVE = 0

	/** How far back a repeat is looked for when not only the last line counts. */
	private const val COMPACT_SCAN = 30

	/**
	 * [contents] as it should be shown: a repeat of a recent line takes that line out of the window
	 * and comes back with a count ("x3").
	 */
	@JvmStatic
	fun compact(chat: ChatComponent, contents: Component): Component {
		if (!BetterChat.module.enabled || !BetterChat.compact.value) return contents
		val key = contents.string
		if (key.isBlank() || isRule(key)) return contents
		if (BetterChat.compactSkipClickable.value && hasClick(contents)) return contents

		val messages = (chat as ChatComponentAccessor).`cryptic$allMessages`()
		val now = client.gui.hud.guiTicks
		val window = BetterChat.compactWindow.value.toInt() * 20
		val candidates = if (BetterChat.compactMode.selectedIndex == COMPACT_CONSECUTIVE) messages.take(1) else messages.take(COMPACT_SCAN)
		val index = candidates.indexOfFirst { message ->
			val repeat = repeats[message.content()]
			(repeat?.key ?: message.content().string) == key &&
				(BetterChat.compactMode.selectedIndex == COMPACT_CONSECUTIVE || now - message.addedTime() <= window)
		}
		if (index < 0) {
			repeats[contents] = Repeat(key, contents, 1)
			return contents
		}
		val old = messages.removeAt(index)
		val count = (repeats[old.content()]?.count ?: 1) + 1
		val shown = contents.copy().append(Component.literal(" §7($count)"))
		repeats[shown] = Repeat(key, contents, count)
		chat.`cryptic$refresh`()
		return shown
	}

	private fun hasClick(component: Component): Boolean {
		var found = false
		component.visit({ style, _ ->
			if (style.clickEvent != null) found = true
			Optional.empty<Unit>()
		}, Style.EMPTY)
		return found
	}

	// ---- The chat screen ----------------------------------------------------

	private var searchBox: EditBox? = null
	private var searching = false
	private var menu: Menu? = null

	private class Menu(val x: Int, val y: Int, val message: GuiMessage)

	private val MENU_ITEMS = listOf("Copy text", "Copy message", "Copy with & codes", "Delete")
	private const val MENU_WIDTH = 112
	private const val MENU_ROW = 11

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ScreenEvents.AFTER_INIT.register { _, screen, width, height ->
			if (screen is ChatScreen && BetterChat.module.enabled) setUp(screen, width, height)
		}
	}

	private fun setUp(screen: ChatScreen, width: Int, height: Int) {
		menu = null
		val widgets = Screens.getWidgets(screen)
		var x = 2
		val y = height - 27
		tabBoxes = emptyList()
		if (BetterChat.tabs.value && HypixelServer.connected) {
			tabBoxes = Tab.entries.map { each ->
				val w = client.font.width(each.shown) + 8
				TabBox(each, x, y, w).also { x += w + 1 }
			}
		}
		if (BetterChat.search.value) {
			val box = EditBox(client.font, x + 4, y + 2, minOf(140, width - x - 8), 10, Component.literal("Search"))
			box.setBordered(false)
			box.setHint(Component.literal("§8Search (Ctrl+F)"))
			box.value = query
			box.setResponder { typed ->
				query = typed
				refreshIfChanged()
			}
			box.visible = searching || BetterChat.searchAlways.value || query.isNotEmpty()
			widgets += box
			searchBox = box
		} else {
			searchBox = null
		}

		ScreenKeyboardEvents.allowKeyPress(screen).register { _, event ->
			if (!BetterChat.search.value || event.key() != GLFW.GLFW_KEY_F || (event.modifiers() and GLFW.GLFW_MOD_CONTROL) == 0) return@register true
			val box = searchBox ?: return@register true
			searching = !searching || !box.isFocused
			box.visible = true
			screen.setFocused(if (searching) box else null)
			false
		}
		ScreenMouseEvents.allowMouseClick(screen).register { _, event -> mouseClicked(screen, event.x(), event.y(), event.button()) }
		openedAt = System.nanoTime()
		ScreenEvents.beforeExtract(screen).register { _, graphics, mouseX, mouseY, _ ->
			screenSlide = inputSlide()
			graphics.pose().pushMatrix()
			graphics.pose().translate(0f, screenSlide)
			drawBar(graphics, mouseX, mouseY)
		}
		ScreenEvents.afterExtract(screen).register { _, graphics, mouseX, mouseY, _ ->
			graphics.pose().popMatrix()
			screenSlide = 0f
			drawSeparators(graphics, screen.height, mouseX, mouseY)
			drawMenu(graphics, mouseX, mouseY)
		}
		ScreenEvents.remove(screen).register {
			searching = false
			menu = null
		}
	}

	private class TabBox(val tab: Tab, val x: Int, val y: Int, val w: Int) {
		fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + TAB_HEIGHT
	}

	private const val TAB_HEIGHT = 12

	private var tabBoxes: List<TabBox> = emptyList()

	/** The chat's own background, so the bar reads as part of it. */
	private fun chatBackground(): Int = net.minecraft.util.ARGB.black(client.options.textBackgroundOpacity().get().toFloat())

	/** The tabs and the search box's background, above the chat box. */
	private fun drawBar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val background = chatBackground()
		for (box in tabBoxes) {
			val selected = box.tab == tab
			val hovered = box.contains(mouseX.toDouble(), mouseY.toDouble())
			graphics.fill(box.x, box.y, box.x + box.w, box.y + TAB_HEIGHT, background)
			if (selected || hovered) graphics.fill(box.x, box.y, box.x + box.w, box.y + TAB_HEIGHT, if (selected) 0x30FFFFFF else 0x18FFFFFF)
			if (selected) graphics.fill(box.x, box.y + TAB_HEIGHT - 1, box.x + box.w, box.y + TAB_HEIGHT, 0xFFFFFFFF.toInt())
			graphics.text(client.font, box.tab.shown, box.x + 4, box.y + 2, if (selected || hovered) 0xFFFFFFFF.toInt() else 0xFFA0A0A0.toInt())
		}
		searchBox?.takeIf { it.visible }?.let { box ->
			graphics.fill(box.x - 4, box.y - 2, box.x + box.width + 2, box.y + box.height, background)
		}
	}

	// ---- Animation ----------------------------------------------------------

	private var openedAt = 0L

	/** The rise applied to the whole chat screen this frame, which the messages take back off. */
	private var screenSlide = 0f

	/** How far the chat box and bar still have to rise after the chat opened. */
	private fun inputSlide(): Float {
		if (!BetterChat.animate.value) return 0f
		val t = ((System.nanoTime() - openedAt) / 1_000_000f / BetterChat.animationTime.value.toFloat()).coerceIn(0f, 1f)
		return INPUT_SLIDE * (1f - easeOut(t))
	}

	private const val INPUT_SLIDE = 14f

	private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

	/** The height still to rise for lines that just arrived, in chat lines. */
	private var slideLines = 0f
	private var slideFrom = 0f
	private var slideStartedAt = 0L

	/** The last line shown, by its text without a repeat count, to tell a repeat in a row. */
	private var lastShownKey: String? = null

	/** A line of only dashes, which servers put around their messages; never a repeat. */
	private val RULE = Regex("""^[\s\-\u2500\u25AC]+$""")

	private fun isRule(text: String): Boolean = RULE.matches(text)

	/**
	 * A new line in the window; called as the chat adds it. The same line again straight after
	 * (an ability spammed into "There are blocks in the way!") doesn't slide in.
	 */
	@JvmStatic
	fun onLineShown(message: GuiMessage) {
		val key = repeats[message.content()]?.key ?: message.content().string
		val repeat = key == lastShownKey && !isRule(key)
		lastShownKey = key
		if (!BetterChat.module.enabled || !BetterChat.animate.value || repeat) return
		val now = System.nanoTime()
		slideFrom = currentSlide(now) + 1
		slideStartedAt = now
	}

	private fun currentSlide(now: Long): Float {
		if (slideFrom <= 0f) return 0f
		val t = ((now - slideStartedAt) / 1_000_000f / BetterChat.animationTime.value.toFloat()).coerceIn(0f, 1f)
		return slideFrom * (1f - easeOut(t))
	}

	/** How far down to draw the chat this frame, in its own pixels, so new lines rise into place. */
	@JvmStatic
	fun chatSlide(): Float {
		if (!BetterChat.module.enabled || !BetterChat.animate.value) return 0f
		// Opening the chat lifts only the box and tabs; the messages stay where they were.
		val opening = -screenSlide
		val chat = client.gui.hud.chat as ChatComponentAccessor
		if (chat.`cryptic$scroll`() != 0) return opening
		val entryHeight = (9 * (client.options.chatLineSpacing().get() + 1.0)).toFloat()
		return opening + currentSlide(System.nanoTime()) * entryHeight
	}

	// ---- Separators ---------------------------------------------------------

	/** Lines above and below the message under the mouse, or the one whose menu is open. */
	private fun drawSeparators(graphics: GuiGraphicsExtractor, screenHeight: Int, mouseX: Int, mouseY: Int) {
		if (!BetterChat.contextMenu.value) return
		val message = menu?.message ?: messageAt(screenHeight, mouseX.toDouble(), mouseY.toDouble()) ?: return
		val chat = client.gui.hud.chat
		val access = chat as ChatComponentAccessor
		val scale = access.`cryptic$scale`()
		val chatBottom = floor((screenHeight - 40) / scale)
		val entryHeight = (9 * (client.options.chatLineSpacing().get() + 1.0)).toInt().coerceAtLeast(1)
		val lines = access.`cryptic$trimmedMessages`()
		val scroll = access.`cryptic$scroll`()
		val rows = (0 until chat.linesPerPage).filter { lines.getOrNull(it + scroll)?.parent() === message }
		if (rows.isEmpty()) return
		val top = ((chatBottom - (rows.max() + 1) * entryHeight) * scale).toInt()
		val bottom = ((chatBottom - rows.min() * entryHeight) * scale).toInt()
		val right = ((ceil(access.`cryptic$width`() / scale) + 8) * scale).toInt()
		graphics.fill(0, top - 1, right, top, SEPARATOR)
		graphics.fill(0, bottom, right, bottom + 1, SEPARATOR)
	}

	private const val SEPARATOR = 0xB0FFFFFF.toInt()

	/** False when the click was the menu's. */
	private fun mouseClicked(screen: ChatScreen, mouseX: Double, mouseY: Double, button: Int): Boolean {
		tabBoxes.firstOrNull { it.contains(mouseX, mouseY) }?.let { box ->
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				tab = box.tab
				refreshIfChanged()
			}
			return false
		}
		menu?.let { open ->
			menu = null
			val row = ((mouseY - open.y) / MENU_ROW).toInt()
			if (mouseX >= open.x && mouseX < open.x + MENU_WIDTH && mouseY >= open.y && row in MENU_ITEMS.indices) {
				act(row, open.message)
				return false
			}
		}
		if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT || !BetterChat.contextMenu.value) return true
		val message = messageAt(screen.height, mouseX, mouseY) ?: return true
		if (BetterChat.rightClickCopy.value) {
			act(0, message)
		} else {
			menu = Menu(mouseX.toInt(), mouseY.toInt() - MENU_ITEMS.size * MENU_ROW, message)
		}
		return false
	}

	private fun act(item: Int, message: GuiMessage) {
		val text = message.content().string
		when (item) {
			0 -> copy(text.replace(FORMATTING, ""))
			// Without the "Name: " in front.
			1 -> copy(text.replace(FORMATTING, "").substringAfter(": ", text.replace(FORMATTING, "")))
			2 -> copy(legacy(message.content()))
			3 -> {
				val chat = client.gui.hud.chat as ChatComponentAccessor
				chat.`cryptic$allMessages`().remove(message)
				chat.`cryptic$refresh`()
			}
		}
	}

	private fun copy(text: String) {
		client.keyboardHandler.clipboard = text
		Toasts.show("Better Chat", "Copied to the clipboard", life = 2.0, color = Toasts.Colors.CRYPTIC)
	}

	/** The line with & colour codes, rebuilt from its pieces' styles. */
	private fun legacy(component: Component): String {
		val out = StringBuilder()
		component.visit({ style, piece ->
			style.color?.let { color ->
				val code = net.minecraft.ChatFormatting.entries.firstOrNull { net.minecraft.network.chat.TextColor.fromLegacyFormat(it) == color }
				if (code != null) out.append('&').append(code.toString()[1]) else out.append("&#").append(String.format("%06X", color.value))
			}
			if (style.isBold) out.append("&l")
			if (style.isItalic) out.append("&o")
			if (style.isUnderlined) out.append("&n")
			if (style.isStrikethrough) out.append("&m")
			if (style.isObfuscated) out.append("&k")
			out.append(piece.replace('§', '&'))
			Optional.empty<Unit>()
		}, Style.EMPTY)
		return out.toString()
	}

	/** The chat message under the mouse in the open chat screen, from the window's own layout. */
	private fun messageAt(screenHeight: Int, mouseX: Double, mouseY: Double): GuiMessage? {
		val chat = client.gui.hud.chat
		val access = chat as ChatComponentAccessor
		val scale = access.`cryptic$scale`()
		val chatBottom = floor((screenHeight - 40) / scale)
		val entryHeight = (9 * (client.options.chatLineSpacing().get() + 1.0)).toInt().coerceAtLeast(1)
		val x = mouseX / scale - 4
		val y = mouseY / scale
		if (x < -4 || x > ceil(access.`cryptic$width`() / scale) + 4 || y > chatBottom) return null
		val row = floor((chatBottom - y) / entryHeight).toInt()
		if (row < 0 || row >= chat.linesPerPage) return null
		return access.`cryptic$trimmedMessages`().getOrNull(row + access.`cryptic$scroll`())?.parent()
	}

	private fun drawMenu(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val open = menu ?: return
		val height = MENU_ITEMS.size * MENU_ROW
		graphics.fill(open.x - 1, open.y - 1, open.x + MENU_WIDTH + 1, open.y + height + 1, 0xFF3A3A3A.toInt())
		graphics.fill(open.x, open.y, open.x + MENU_WIDTH, open.y + height, 0xF0141414.toInt())
		MENU_ITEMS.forEachIndexed { index, label ->
			val top = open.y + index * MENU_ROW
			val hovered = mouseX >= open.x && mouseX < open.x + MENU_WIDTH && mouseY >= top && mouseY < top + MENU_ROW
			if (hovered) graphics.fill(open.x, top, open.x + MENU_WIDTH, top + MENU_ROW, 0x40FFFFFF)
			graphics.text(client.font, label, open.x + 4, top + 2, if (index == 3) 0xFFFF6B6B.toInt() else 0xFFFFFFFF.toInt())
		}
	}
}
