package imicro.cryptic.gui.menu

import imicro.cryptic.config.ConfigManager
import imicro.cryptic.feature.ClickGui
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ModuleRegistry
import imicro.cryptic.gui.menu.MenuStyle.Icons
import imicro.cryptic.gui.menu.MenuStyle.approach
import imicro.cryptic.gui.menu.MenuStyle.blend
import imicro.cryptic.gui.menu.MenuStyle.fade
import imicro.cryptic.hud.Hud
import imicro.cryptic.render.RoundRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

/**
 * Cryptic's new menu: a launcher-style window of module cards, the way Lunar
 * and Badlion lay theirs out, drawn entirely through Minecraft's own GUI
 * renderer so it runs on Vulkan as well as OpenGL.
 *
 * The tabs filter, the search narrows the list, the cards scroll, a card's
 * switch turns its module on and off and its star pins it to Favorites. Along
 * the top right are the HUD editor, the settings and a close button. A card's
 * "⋮" (or a right click) opens the module's settings in place of the grid,
 * with a way back in the top left.
 *
 * Everything is laid out in design pixels at 1080p and scaled to the window,
 * so the menu is the same size on screen whatever the GUI scale is.
 */
class MenuScreen : Screen(Component.literal("Cryptic")) {
	/** A tab is a word, or an icon in its place. */
	private class Tab(val label: String, val icon: String? = null, val filter: (Module) -> Boolean)

	private val tabs = listOf(
		Tab("Favorites", Icons.STAR) { MenuPrefs.isFavorite(it.id) },
		Tab("All") { it.category != ModuleCategory.MISC },
		Tab("General") { it.category == ModuleCategory.GENERAL },
		Tab("Dungeon") { it.category == ModuleCategory.DUNGEON },
		Tab("Boss") { it.category == ModuleCategory.FLOOR_7 },
		Tab("Render") { it.category == ModuleCategory.VISUAL },
	)

	private class CardHit(val module: Module, val card: Box, val star: Box, val toggle: Box?, val more: Box)

	private enum class HeaderButton { HUD, SETTINGS, CLOSE }

	private val tabHits = ArrayList<Pair<Int, Box>>()
	private val headerHits = ArrayList<Pair<HeaderButton, Box>>()
	private val cardHits = ArrayList<CardHit>()
	private var gridBox = Box.NONE
	private var searchBox = Box.NONE
	private var backBox = Box.NONE

	private var scroll = 0f
	private var scrollTarget = 0f
	private var maxScroll = 0f

	/** The pill under the chosen tab glides to it rather than jumping. */
	private var tabPillX = Float.NaN
	private var tabPillW = 0f
	private var tabPillAlpha = 1f

	private val search = TextField(MAX_SEARCH)
	private var searchFocused = false
	private var selectingSearch = false
	private val scrollbar = Scrollbar()

	/**
	 * The module whose settings are showing in place of the grid, if any. It
	 * outlives the screen, so the menu opens again on the module it was shut on.
	 */
	private var page: ModulePage? = lastPage?.let { id -> ModuleRegistry.byId[id]?.let(::ModulePage) }
		set(value) {
			// Leaving a module, rather than closing the menu on it, folds its rows up.
			if (field !== value) field?.onClose(keepExpanded = false)
			field = value
			lastPage = value?.module?.id
			hover.remove(BACK)
		}

	private val hover = HashMap<Any, Float>()
	private val switchPosition = HashMap<Module, Float>()
	private var opening = 0f
	private var lastFrame = 0L

	override fun isPauseScreen() = false

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		// Asked for directly rather than through vanilla's check, which goes by the
		// Menu Background Blur option; the strength is Cryptic's own (see GameRendererMixin).
		if (ClickGui.backgroundBlur.value) graphics.blurBeforeThisStratum()
		graphics.fill(0, 0, width, height, 0x88000000.toInt())
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
		opening = min(1f, opening + dt / OPEN_SECONDS)
		val reveal = MenuStyle.easeOut(opening)

		UiText.pixelsPerUnit = minecraft.window.screenHeight / DESIGN_HEIGHT
		val k = scale()
		val mx = mouseX / k
		val my = mouseY / k
		val viewW = width / k
		val viewH = height / k

		graphics.pose().pushMatrix()
		graphics.pose().scale(k, k)

		val winW = min(WINDOW_W, viewW - 60f)
		val winH = min(WINDOW_H, viewH - 60f)
		val winX = (viewW - winW) / 2f
		val winY = (viewH - winH) / 2f + (1f - reveal) * 14f
		val alpha = reveal

		// The window: the body and a hairline edge, with corners rounded about as
		// much as a Windows 11 window's. The shadow has no offset, so it rings the
		// window evenly rather than dropping below it.
		RoundRect.shadow(graphics, winX, winY, winX + winW, winY + winH, fade(WINDOW_SHADOW, alpha), WINDOW_RADIUS, 24f)
		RoundRect.fill(graphics, winX, winY, winX + winW, winY + winH, fade(MenuStyle.WINDOW_BG, alpha), WINDOW_RADIUS)
		RoundRect.outline(graphics, winX, winY, winX + winW, winY + winH, fade(MenuStyle.WINDOW_EDGE, alpha), WINDOW_RADIUS, 1f)

		drawHeaderButtons(graphics, winX + winW - 20f, winY + 16f, mx, my, dt, alpha)

		val rowY = winY + 62f
		val contentBox = Box(winX + 20f, winY + 112f, winW - 40f, winH - 112f - 20f)
		// The grid scrolls through more of the window than it rests in: from just
		// under the tabs to just inside the bottom edge, so cards fade out near
		// the window's edge instead of being cut off at the padding. The left
		// side has no scrollbar to make room for, so it sits closer too.
		gridBox = Box(winX + GRID_LEFT, winY + GRID_TOP, winW - GRID_LEFT - 20f, winH - GRID_TOP - GRID_BOTTOM)

		val current = page
		if (current != null) {
			current.beginFrame()
			drawBack(graphics, winX + 16f, winY + 16f, mx, my, dt, alpha)
			current.drawTitle(graphics, Box(winX + 20f, rowY, winW - 40f, ROW_HEIGHT), mx, my, dt, alpha)
			current.draw(graphics, contentBox, mx, my, dt, alpha)
			current.drawOverlays(graphics, mx, my, alpha)
		} else {
			backBox = Box.NONE
			drawLogo(graphics, winX + 28f, winY + 26f, alpha)
			val searchW = 250f
			searchBox = Box(winX + winW - 20f - searchW, rowY, searchW, ROW_HEIGHT)
			drawTabs(graphics, winX + 20f, rowY, mx, my, dt, alpha)
			drawSearch(graphics, searchBox, mx, my, dt, alpha)
			drawGrid(graphics, gridBox, GRID_REST_TOP, GRID_REST_BOTTOM, mx, my, dt, alpha)
		}

		graphics.pose().popMatrix()

		// Switches flipped here are saved like any other change.
		ConfigManager.autosave()
	}

	/** Cryptic's crescent, filling the 17-pixel square the old mark sat in. */
	private fun drawLogo(graphics: GuiGraphicsExtractor, x: Float, y: Float, alpha: Float) {
		val size = 26f
		val centre = 17f / 2f
		MenuLogo.draw(graphics, x + centre - size / 2f, y + centre - size / 2f, size, fade(MenuStyle.TEXT, alpha))
	}

	/** "← Back", where the logo sits while a module's settings are showing. */
	private fun drawBack(graphics: GuiGraphicsExtractor, x: Float, y: Float, mx: Float, my: Float, dt: Float, alpha: Float) {
		val size = 13f
		val w = 12f + 13f + 8f + UiText.width("Back", size, UiText.Face.MEDIUM) + 12f
		val box = Box(x, y, w, 30f)
		backBox = box
		val lift = approach(hover[BACK] ?: 0f, if (box.contains(mx, my)) 1f else 0f, 18f, dt).also { hover[BACK] = it }
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(fade(MenuStyle.BUTTON_HOVER, lift), alpha), 6f)
		val color = fade(blend(MenuStyle.MUTED, MenuStyle.TEXT, lift), alpha)
		UiText.drawIconCentered(graphics, Icons.ARROW_LEFT, box.x + 10f, box.y + (box.h - 15f) / 2f, 15f, 13f, color)
		UiText.draw(graphics, "Back", box.x + 12f + 13f + 8f, box.y + (box.h - size) / 2f, size, color, UiText.Face.MEDIUM)
	}

	/** Edit HUD, Settings and Close, right to left from [right], as icon buttons. */
	private fun drawHeaderButtons(graphics: GuiGraphicsExtractor, right: Float, y: Float, mx: Float, my: Float, dt: Float, alpha: Float) {
		headerHits.clear()
		val size = 30f
		// The cross's own right edge lines up with [right], the end of the search
		// bar, rather than its hit box: the box is wider than the glyph.
		var x = right - UiText.width(Icons.XMARK, CLOSE_ICON, UiText.Face.ICON) / 2f - size / 2f
		for (button in listOf(HeaderButton.CLOSE, HeaderButton.SETTINGS, HeaderButton.HUD)) {
			val box = Box(x, y, size, size)
			headerHits += button to box
			val active = button == HeaderButton.SETTINGS && showingSettings && page == null
			val lift = approach(hover[button] ?: 0f, if (box.contains(mx, my)) 1f else 0f, 18f, dt).also { hover[button] = it }
			val glyph = when (button) {
				HeaderButton.HUD -> Icons.EDIT
				HeaderButton.SETTINGS -> Icons.GEAR
				HeaderButton.CLOSE -> Icons.XMARK
			}
			// No box under the pointer: the icon itself answers, Close in red.
			val hovered = if (button == HeaderButton.CLOSE) MenuStyle.DANGER else MenuStyle.TEXT
			val color = if (active) MenuStyle.TEXT else blend(MenuStyle.MUTED, hovered, lift)
			UiText.drawIconCentered(graphics, glyph, box.x, box.y, size, if (button == HeaderButton.CLOSE) CLOSE_ICON else 14f, fade(color, alpha))
			x -= size + 6f
		}
	}

	private fun drawTabs(graphics: GuiGraphicsExtractor, x: Float, y: Float, mx: Float, my: Float, dt: Float, alpha: Float) {
		tabHits.clear()
		var cursor = x
		val padding = 13f
		var chosenX = cursor
		var chosenW = 0f
		val boxes = ArrayList<Box>(tabs.size)
		for ((index, tab) in tabs.withIndex()) {
			val content = if (tab.icon != null) 15f else UiText.width(tab.label, TAB_TEXT)
			val w = content + padding * 2f
			val box = Box(cursor, y, w, ROW_HEIGHT)
			boxes += box
			tabHits += index to box
			if (index == selectedTab) {
				chosenX = cursor
				chosenW = w
			}
			cursor += w + 4f
		}

		// The pill steps aside while the settings or a search are showing,
		// since neither is one of the tabs.
		val pillShown = !showingSettings && search.isEmpty
		if (tabPillX.isNaN()) {
			tabPillX = chosenX
			tabPillW = chosenW
		}
		tabPillX = approach(tabPillX, chosenX, 20f, dt)
		tabPillW = approach(tabPillW, chosenW, 20f, dt)
		tabPillAlpha = approach(tabPillAlpha, if (pillShown) 1f else 0f, 20f, dt)
		RoundRect.fill(graphics, tabPillX, y, tabPillX + tabPillW, y + ROW_HEIGHT, fade(MenuStyle.TAB_PILL, alpha * tabPillAlpha), 6f)

		for ((index, tab) in tabs.withIndex()) {
			val box = boxes[index]
			val chosen = index == selectedTab && pillShown
			val color = when {
				chosen -> MenuStyle.TEXT
				box.contains(mx, my) -> MenuStyle.TAB_HOVER
				else -> MenuStyle.MUTED
			}
			if (tab.icon != null) {
				// Filled while it is the tab you are on, an outline otherwise.
				UiText.drawIconCentered(
					graphics, tab.icon, box.x + (box.w - box.h) / 2f, box.y, box.h, 14f,
					fade(if (chosen) MenuStyle.STAR else color, alpha), outline = !chosen,
				)
			} else {
				UiText.draw(
					graphics, tab.label, box.x + padding, box.y + (ROW_HEIGHT - TAB_TEXT) / 2f, TAB_TEXT,
					fade(color, alpha), if (chosen) UiText.Face.MEDIUM else UiText.Face.REGULAR,
				)
			}
		}
	}

	private fun drawSearch(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		MenuStyle.drawField(graphics, box, box.contains(mx, my), searchFocused, alpha)
		UiText.drawIconCentered(
			graphics, Icons.SEARCH, box.right - box.h, box.y, box.h, 13f,
			fade(if (searchFocused) MenuStyle.TEXT else MenuStyle.MUTED, alpha),
		)
		// The field stops short of the magnifier.
		val field = Box(box.x, box.y, box.w - box.h + 8f, box.h)
		search.draw(graphics, field, SEARCH_TEXT, MenuStyle.TEXT, searchFocused, alpha, dt, placeholder = "Search modules")
	}

	/**
	 * The cards, scrolled and clipped to [box]. They rest [insetTop] below its
	 * top and, scrolled to the end, [insetBottom] above its bottom; in between
	 * they pass through that space, which is where the fades are.
	 */
	private fun drawGrid(graphics: GuiGraphicsExtractor, box: Box, insetTop: Float, insetBottom: Float, mx: Float, my: Float, dt: Float, alpha: Float) {
		cardHits.clear()
		// Each list keeps its own place: a tab left and come back to, or the
		// menu shut and opened again, is where it was.
		val key = listKey()
		if (key != shownList) {
			shownList?.let { listScrolls[it] = scrollTarget }
			scrollTarget = listScrolls[key] ?: 0f
			scroll = scrollTarget
			shownList = key
		}
		val modules = visibleModules()
		val x = box.x
		val y = box.y + insetTop
		val w = box.w
		val h = box.h - insetTop - insetBottom

		if (modules.isEmpty()) {
			val message = when {
				!search.isEmpty -> "No modules match \"${search.text}\"."
				!showingSettings && selectedTab == FAVORITES_TAB -> "Star a module to pin it here."
				else -> "No modules here."
			}
			UiText.draw(graphics, message, x + 8f, y + 10f, 14f, fade(MenuStyle.MUTED, alpha))
			maxScroll = 0f
			return
		}

		val columns = max(2, ((w + CARD_GAP) / (CARD_MIN_W + CARD_GAP)).toInt())
		val cardW = (w - CARD_GAP * (columns - 1)) / columns
		val rows = (modules.size + columns - 1) / columns
		val contentH = rows * CARD_H + (rows - 1) * CARD_GAP
		maxScroll = max(0f, contentH - h)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)

		graphics.enableScissor(box.x.toInt(), box.y.toInt(), box.right.toInt() + 1, box.bottom.toInt())
		val pointerInGrid = box.contains(mx, my)
		for ((index, module) in modules.withIndex()) {
			val cx = x + (index % columns) * (cardW + CARD_GAP)
			val cy = y + (index / columns) * (CARD_H + CARD_GAP) - scroll
			if (cy + CARD_H < box.y || cy > box.bottom) continue
			drawCard(graphics, module, cx, cy, cardW, mx, my, pointerInGrid, dt, alpha)
		}
		MenuStyle.drawScrollFades(graphics, box, scroll, maxScroll, alpha, topRamp = insetTop, bottomRamp = insetBottom)
		graphics.disableScissor()

		// From the first row's resting place down to just inside the window's edge.
		scrollbar.draw(graphics, x + w + Scrollbar.GAP, y, box.bottom - SCROLLBAR_BOTTOM - y, contentH, scroll, maxScroll, mx, my, dt, alpha)
	}

	private fun drawCard(
		graphics: GuiGraphicsExtractor,
		module: Module,
		x: Float, y: Float, w: Float,
		mx: Float, my: Float,
		pointerInGrid: Boolean,
		dt: Float, alpha: Float,
	) {
		val h = CARD_H
		val card = Box(x, y, w, h)
		val hovered = pointerInGrid && card.contains(mx, my)
		val lift = approach(hover[module] ?: 0f, if (hovered) 1f else 0f, 18f, dt).also { hover[module] = it }
		val on = module.supportsToggle && module.enabled
		val muted = if (on) MenuStyle.onMuted() else MenuStyle.MUTED

		val fill = if (on) blend(MenuStyle.cardOn(), MenuStyle.cardOnHover(), lift) else blend(MenuStyle.CARD_OFF, MenuStyle.CARD_OFF_HOVER, lift)
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(fill, alpha), 0f)

		// Icon, top left.
		UiText.drawIcon(graphics, iconFor(module), x + 18f, y + 18f, 17f, fade(if (on) MenuStyle.TEXT else MenuStyle.ICON_OFF, alpha))

		// Along the top right: the star, the switch, then the "⋮".
		val more = Box(x + w - 14f - 14f, y + 14f, 14f, 22f)
		if (pointerInGrid && more.contains(mx, my)) {
			RoundRect.fill(graphics, more.x - 2f, more.y, more.right + 2f, more.bottom, fade(0x22FFFFFF, alpha), 5f)
		}
		UiText.drawIconCentered(
			graphics, Icons.ELLIPSIS_VERTICAL, more.x, more.y + (more.h - more.w) / 2f, more.w, 13f,
			fade(muted, alpha),
		)

		var toggle: Box? = null
		var starRight = more.x - 6f
		if (module.supportsToggle) {
			toggle = Box(more.x - 8f - 32f, y + 18f, 32f, 15f)
			val t = approach(switchPosition[module] ?: if (on) 1f else 0f, if (on) 1f else 0f, 20f, dt)
			switchPosition[module] = t
			MenuStyle.drawSwitch(graphics, toggle, t, alpha)
			starRight = toggle.x - 6f
		}

		val star = Box(starRight - 22f, y + 14f, 22f, 22f)
		val favorite = MenuPrefs.isFavorite(module.id)
		// The empty star only shows on a card under the pointer, so a grid of
		// cards is not a grid of stars; a filled one always shows.
		val starColor = when {
			favorite -> MenuStyle.STAR
			pointerInGrid && star.contains(mx, my) -> MenuStyle.TEXT
			else -> fade(muted, lift)
		}
		UiText.drawIconCentered(graphics, Icons.STAR, star.x, star.y, star.w, 14f, fade(starColor, alpha), outline = !favorite)

		// Name and description, bottom left.
		val textW = w - 36f
		UiText.draw(
			graphics, UiText.fit(module.name, NAME_TEXT, textW, UiText.Face.MEDIUM),
			x + 18f, y + h - 52f, NAME_TEXT, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM,
		)
		UiText.draw(graphics, UiText.fit(module.description, DESC_TEXT, textW), x + 18f, y + h - 30f, DESC_TEXT, fade(muted, alpha))

		cardHits += CardHit(module, card, star, toggle, more)
	}

	// ---- Input -----------------------------------------------------------

	private fun designX(x: Double) = (x / scale()).toFloat()
	private fun designY(y: Double) = (y / scale()).toFloat()

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val mx = designX(event.x())
		val my = designY(event.y())
		val current = page
		val button = event.button()

		// A bind on the page waiting for its key takes any button first.
		if (current != null && current.capturingKeys && button != 0) {
			current.mouseClicked(mx, my, button)
			return true
		}

		if (button == 0) {
			for ((header, box) in headerHits) {
				if (!box.contains(mx, my)) continue
				when (header) {
					HeaderButton.HUD -> Hud.openEditor(this)
					HeaderButton.SETTINGS -> {
						page = null
						showingSettings = !showingSettings
						search.clear()
					}
					HeaderButton.CLOSE -> onClose()
				}
				return true
			}
		}

		if (current != null) {
			// Back, or the mouse's own back button, as in a browser.
			if ((button == 0 && backBox.contains(mx, my)) || button == GLFW.GLFW_MOUSE_BUTTON_4) {
				page = null
				return true
			}
			current.mouseClicked(mx, my, button, doubleClick)
			return true
		}

		if (button == 0) {
			val wasFocused = searchFocused
			searchFocused = searchBox.contains(mx, my)
			if (searchFocused) {
				// A double click takes the whole query, the way a browser's address bar does.
				if (doubleClick) search.selectAll() else search.pointAt(mx, extend = wasFocused && event.hasShiftDown())
				selectingSearch = true
				return true
			}
			if (scrollbar.press(mx, my, maxScroll, ::setScroll)) return true

			for ((index, box) in tabHits) {
				if (!box.contains(mx, my)) continue
				if (selectedTab != index || showingSettings || !search.isEmpty) {
					selectedTab = index
					showingSettings = false
					search.clear()
				}
				return true
			}
		}

		if (!gridBox.contains(mx, my)) return super.mouseClicked(event, doubleClick)
		for (hit in cardHits) {
			if (!hit.card.contains(mx, my)) continue
			when {
				button == 1 -> if (ClickGui.rightClickEdits.value) open(hit.module)
				button != 0 -> return super.mouseClicked(event, doubleClick)
				hit.star.contains(mx, my) -> MenuPrefs.toggleFavorite(hit.module.id)
				hit.more.contains(mx, my) -> open(hit.module)
				hit.toggle?.contains(mx, my) == true -> hit.module.enabled = !hit.module.enabled
				hit.module.cardAction != null -> hit.module.cardAction.invoke()
				// Clicking the rest of the card toggles it too, unless that is turned off.
				hit.module.supportsToggle && ClickGui.leftClickToggles.value -> hit.module.enabled = !hit.module.enabled
			}
			return true
		}
		return super.mouseClicked(event, doubleClick)
	}

	private fun open(module: Module) {
		page = ModulePage(module)
		searchFocused = false
	}

	private fun setScroll(value: Float) {
		scroll = value
		scrollTarget = value
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		val mx = designX(event.x())
		val my = designY(event.y())
		page?.let { if (it.mouseDragged(mx, my)) return true }
		if (scrollbar.drag(my, maxScroll, ::setScroll)) return true
		if (selectingSearch) {
			search.pointAt(mx, extend = true)
			return true
		}
		return super.mouseDragged(event, dragX, dragY)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		selectingSearch = false
		if (page?.mouseReleased() == true) return true
		if (scrollbar.release()) return true
		return super.mouseReleased(event)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		val current = page
		if (current != null) {
			current.mouseScrolled(verticalAmount.toFloat())
			return true
		}
		scrollTarget = (scrollTarget - verticalAmount.toFloat() * SCROLL_STEP).coerceIn(0f, maxScroll)
		return true
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val current = page
		if (current != null) {
			if (current.keyPressed(event)) return true
			// Escape with nothing on the page to cancel shuts the menu; the menu
			// opens on this module again next time, and Back is the way out of it.
			return super.keyPressed(event)
		}

		val control = (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0
		// Ctrl+F, or Ctrl+A over the cards, takes you to the search with what is in it selected.
		if (control && (event.key() == GLFW.GLFW_KEY_F || (event.key() == GLFW.GLFW_KEY_A && !searchFocused))) {
			searchFocused = true
			search.selectAll()
			return true
		}
		if (searchFocused) {
			when (event.key()) {
				// The first Escape leaves the field; the next one closes the menu.
				GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> searchFocused = false
				else -> {
					search.keyPressed(event)
				}
			}
			return true
		}
		return super.keyPressed(event)
	}

	override fun charTyped(event: CharacterEvent): Boolean {
		val codePoint = event.codepoint()
		page?.let { return it.charTyped(codePoint) }
		if (codePoint < ' '.code) return false
		// Typing anywhere in the menu goes to the search, as in a launcher.
		searchFocused = true
		search.charTyped(codePoint)
		return true
	}

	override fun onClose() {
		ConfigManager.autosave()
		super.onClose()
	}

	/** Which list the grid is showing, for remembering each one's scroll. */
	private fun listKey(): String = when {
		!search.isEmpty -> "search:" + search.text.trim().lowercase()
		showingSettings -> "settings"
		else -> "tab:$selectedTab"
	}

	/** The list whose place [scroll] is, once the grid has been drawn. */
	private var shownList: String? = null

	override fun removed() {
		shownList?.let { listScrolls[it] = scrollTarget }
		page?.onClose()
		if (ClickGui.rememberSearch.value) ClickGui.rememberedSearch = search.text
		ConfigManager.autosave()
		super.removed()
	}

	override fun init() {
		super.init()
		if (ClickGui.rememberSearch.value && search.isEmpty && ClickGui.rememberedSearch.isNotEmpty()) search.set(ClickGui.rememberedSearch)
	}

	override fun keyReleased(event: KeyEvent): Boolean {
		if (page?.keyReleased(event) == true) return true
		return super.keyReleased(event)
	}

	private fun visibleModules(): List<Module> {
		val query = search.text.trim()
		val modules = when {
			query.isNotEmpty() -> ModuleRegistry.modules.filter {
				it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
			}
			showingSettings -> ModuleRegistry.modules.filter { it.category == ModuleCategory.MISC }
			else -> ModuleRegistry.modules.filter(tabs[selectedTab].filter)
		}
		return when (ClickGui.sorting.selectedIndex) {
			ClickGui.SORT_Z_TO_A -> modules.sortedByDescending(Module::sortKey)
			// The longest name first, measured as drawn rather than by counting letters.
			ClickGui.SORT_WIDTH -> modules.sortedWith(compareByDescending<Module> { UiText.width(it.name, NAME_TEXT, UiText.Face.MEDIUM) }.thenBy(Module::sortKey))
			else -> modules.sortedBy(Module::sortKey)
		}
	}

	private fun iconFor(module: Module): String = ModuleIcons.of(module) ?: when (module.category) {
		ModuleCategory.GENERAL -> Icons.GEAR
		ModuleCategory.DUNGEON -> Icons.DUNGEON
		ModuleCategory.FLOOR_7 -> Icons.SKULL
		ModuleCategory.VISUAL -> Icons.EYE
		ModuleCategory.MISC -> Icons.SLIDERS
	}

	companion object {
		/** Kept for the session, so the menu opens where it was left. */
		private var selectedTab = 1

		/** The module page the menu was shut on, by id, so it opens there again. */
		private var lastPage: String? = null

		/** Each list's scroll, by [listKey], for the length of the game. */
		private val listScrolls = HashMap<String, Float>()

		private var requested = false

		/**
		 * Asks for the menu to open at the next tick, on [module]'s page when one is
		 * given. Deferred because a command runs while the chat screen is still
		 * closing, and a screen opened then is closed with it.
		 */
		fun request(module: Module? = null) {
			if (module != null) lastPage = module.id
			requested = true
		}

		fun openIfRequested(client: net.minecraft.client.Minecraft) {
			if (!requested) return
			requested = false
			client.gui.setScreen(MenuScreen())
		}

		/**
		 * The blur strength to hand the renderer this frame: Click GUI's, while this
		 * menu is open with the blur on, and nought (leave vanilla's alone) otherwise.
		 */
		@JvmStatic
		fun blurRadius(): Int {
			if (net.minecraft.client.Minecraft.getInstance().gui.screen() !is MenuScreen) return 0
			return if (ClickGui.backgroundBlur.value) ClickGui.blurStrength.value.toInt() else 0
		}
		private var showingSettings = false
		private const val FAVORITES_TAB = 0
		private const val BACK = "back"

		private const val DESIGN_HEIGHT = 1080f
		private const val OPEN_SECONDS = 0.22f
		private const val MAX_SEARCH = 48

		private const val WINDOW_W = 940f
		private const val WINDOW_H = 620f
		private const val WINDOW_RADIUS = 8f
		private const val WINDOW_SHADOW = 0xB4000000.toInt()
		private const val ROW_HEIGHT = 30f
		private const val CLOSE_ICON = 15f

		/** The grid's scroll area against the window: its left edge, its top (just under the tab row) and its bottom margin. */
		private const val GRID_LEFT = 12f
		private const val GRID_TOP = 96f
		private const val GRID_BOTTOM = 3f

		/** Where the cards rest inside that area: the first row this far below its top, the last row this far above its bottom. */
		private const val GRID_REST_TOP = 8f
		private const val GRID_REST_BOTTOM = 12f

		/** The scrollbar stops this far above the scroll area's bottom, about as far in as it sits from the right edge. */
		private const val SCROLLBAR_BOTTOM = 5f

		private const val CARD_MIN_W = 200f
		private const val CARD_H = 136f
		private const val CARD_GAP = 9f
		private const val SCROLL_STEP = 70f

		private const val TAB_TEXT = 14f
		private const val SEARCH_TEXT = 13f
		private const val NAME_TEXT = 15f
		private const val DESC_TEXT = 12f
	}
}
