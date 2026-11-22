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
import imicro.cryptic.gui.menu.managers.ProfilesSection
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
 * Cryptic's menu, drawn through Minecraft's own GUI renderer so it works on Vulkan as well as
 * OpenGL: a sidebar of views, a header with the title and search, and a panel of module cards
 * under category headings. A card's gear or a right click opens its settings in the same panel.
 * Laid out in 1080p design pixels and scaled to the window.
 */
class MenuScreen : Screen(Component.literal("Cryptic")) {
	/** A sidebar entry that lists cards. */
	private enum class View(val label: String, val subtitle: String, val icon: String) {
		PROFILE("Profile", "Your personal overview", Icons.USER),
		MODULES("Modules", "Manage modules", Icons.GRID),
		FAVORITES("Favorites", "Modules you have starred", Icons.STAR),
		ENABLED("Enabled", "Modules that are on", Icons.POWER),
		PROFILES("Config", "Save, switch and share setups", Icons.LAYERS),
		SETTINGS("Settings", "Cryptic's own settings", Icons.GEAR),
	}

	/** [star] and [more] split the card's top-right corner between them, so a near miss still lands. */
	private class CardHit(val module: Module, val card: Box, val star: Box, val more: Box)

	private class Section(val category: ModuleCategory, val modules: List<Module>)

	private val sideHits = ArrayList<Pair<Any, Box>>()
	private val cardHits = ArrayList<CardHit>()
	private var gridBox = Box.NONE
	private var searchBox = Box.NONE
	private var backBox = Box.NONE

	private var scroll = 0f
	private var scrollTarget = 0f
	private var maxScroll = 0f

	/** Where each category's heading sits in the list, from the last frame, for the sidebar's jumps. */
	private val sectionTops = LinkedHashMap<ModuleCategory, Float>()

	/** A category the sidebar asked to scroll to once the list is laid out. */
	private var pendingJump: ModuleCategory? = null

	/** The category last jumped to, lit until the list is scrolled by hand (the last can't reach the top). */
	private var pinnedSection: ModuleCategory? = null

	private val search = TextField(MAX_SEARCH)
	private var searchFocused = false
	private var selectingSearch = false
	private val scrollbar = Scrollbar()

	/** The Profile view; its "Read all" opens the Changelog page. */
	private val profile = ProfilePage { open(imicro.cryptic.gui.menu.managers.ChangelogSection.module) }

	/** The Profile view is up: chosen, with no module open and no search. */
	private val showingProfile: Boolean get() = page == null && view == View.PROFILE && search.isEmpty

	/** The pages the Config and Settings views show in place of cards, kept while the menu is open. */
	private val viewPages = HashMap<View, ModulePage>()

	/** The page the chosen view shows, when it shows one and no module is open over it. */
	private fun viewPage(): ModulePage? {
		if (page != null || !search.isEmpty) return null
		return when (view) {
			View.PROFILES -> viewPages.getOrPut(view) { ModulePage(ProfilesSection.module) }
			View.SETTINGS -> viewPages.getOrPut(view) { ModulePage(SettingsTab.module, SettingsTab.keybind) }
			else -> null
		}
	}

	/** What the panel shows and input goes to: an open module, else the view's own page. */
	private fun shownPage(): ModulePage? = page ?: viewPage()

	/** A view's page with nothing of its own to search (Config) lends its search box to the modules. */
	private fun lendsSearch(current: ModulePage?): Boolean = current != null && current !== page && !current.searchable

	/** Leaving a view's page commits what was typed in it. */
	private fun leaveView() {
		viewPages[view]?.onClose(keepExpanded = false)
	}

	/** The module whose settings are showing, if any. Outlives the screen, so the menu reopens on it. */
	private var page: ModulePage? = lastPage?.let { id -> ModuleRegistry.byId[id]?.let(::ModulePage) }?.also {
		// A manager's page opens on its settings rather than its window when asked.
		if (openOnSettings) it.openSettings()
		openOnSettings = false
		pendingReveal?.let(it::reveal)
		pendingReveal = null
	}
		set(value) {
			// Leaving a module (not closing the menu) folds its rows up.
			if (field !== value) {
				field?.onClose(keepExpanded = false)
				// A page comes in from the right; going back, the cards come in from the left.
				slide = 0f
				slideFrom = if (value != null) 1f else -1f
			}
			field = value
			lastPage = value?.module?.id
			hover.remove(BACK)
		}

	private val hover = HashMap<Any, Float>()

	/** How far each sidebar entry has faded to its selected look, so a switch is seen to happen. */
	private val selectedness = HashMap<Any, Float>()

	/** How far the categories under Modules have unfolded, 0 to 1. */
	private var categoriesOpen = if (view == View.MODULES) 1f else 0f

	/**
	 * A view the sidebar switched to: the panel shrinks and fades out, the view changes, then it
	 * grows and fades back in. [panelShown] runs 1 to 0 and back.
	 */
	private var pendingView: View? = null
	private var panelShown = 1f

	/** The view the sidebar shows as chosen, ahead of the panel catching up. */
	private val chosenView: View get() = pendingView ?: view
	private var opening = 0f

	/** How far the panel's contents have slid in, 0 to 1, and from which side: 1 right, -1 left. */
	private var slide = 1f
	private var slideFrom = 1f
	private var lastFrame = 0L

	/** How far each card has faded from grey to the accent, so a toggle is seen to happen. */
	private val onness = HashMap<Module, Float>()

	/** What the pointer rests on that has a tooltip, and how long it has rested there. */
	private var tipKey: Any? = null
	private var tipText = ""
	private var tipTime = 0f
	private var tipBounds = Box.NONE

	override fun isPauseScreen() = false

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		// Asked for directly, since vanilla's check uses the Menu Background Blur option; the
		// strength is Cryptic's own (see GameRendererMixin).
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
		val reveal = MenuStyle.easeOut(opening)
		slide = min(1f, slide + dt / SLIDE_SECONDS)
		val slid = MenuStyle.easeOut(slide)
		val shift = (1f - slid) * SLIDE_DISTANCE * slideFrom
		val target = pendingView
		if (target != null) {
			panelShown = max(0f, panelShown - dt / SWITCH_OUT_SECONDS)
			if (panelShown <= 0f || dt <= 0f) switchTo(target)
		} else {
			panelShown = min(1f, panelShown + dt / SWITCH_IN_SECONDS)
		}
		val shown = smoothstep(panelShown)

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

		val winW = min(WINDOW_W, viewW - 60f)
		val winH = min(WINDOW_H, viewH - 60f)
		val winX = (viewW - winW) / 2f
		val winY = (viewH - winH) / 2f + (1f - reveal) * 14f
		val alpha = reveal

		val sidebar = Box(winX, winY, SIDEBAR_W, winH)
		val mainX = sidebar.right + PANEL_GAP
		val header = Box(mainX, winY, winX + winW - mainX, HEADER_H)
		val content = Box(mainX, header.bottom + PANEL_GAP, header.w, winY + winH - header.bottom - PANEL_GAP)

		frameTipKey = null
		tipBounds = Box(winX, winY, winW, winH)
		// Every shadow before any panel, so none darkens a neighbour's edge.
		for (panel in listOf(sidebar, header, content)) {
			RoundRect.shadow(graphics, panel.x, panel.y, panel.right, panel.bottom, fade(PANEL_SHADOW, alpha), PANEL_RADIUS, 28f)
		}
		drawPanel(graphics, sidebar, MenuStyle.SIDEBAR_BG, alpha)
		drawPanel(graphics, header, MenuStyle.WINDOW_BG, alpha)
		drawPanel(graphics, content, MenuStyle.WINDOW_BG, alpha)
		drawSidebar(graphics, sidebar, mx, my, dt, alpha)

		// The cards scroll from the panel's top edge to its bottom, so they fade there instead of
		// being cut at the padding.
		gridBox = Box(content.x + CONTENT_PAD, content.y + 2f, content.w - CONTENT_PAD * 2f, content.h - 4f)

		// The search box stays put, the same size everywhere; only the title beside it slides.
		searchBox = Box(header.right - HEADER_PAD - SEARCH_W, header.y + (header.h - FIELD_H) / 2f, SEARCH_W, FIELD_H)
		// The title slides from the panel's inner edge to just short of the search box, kept clear of
		// the rounded corners. Its sides fade through the padding it rests beyond.
		val titleArea = Box(header.x + 1f, header.y + PANEL_RADIUS, searchBox.x - 12f - (header.x + 1f), header.h - PANEL_RADIUS * 2f)
		val titleEdges = SlideEdges(HEADER_PAD - 3f, 11f, 0f)
		// Sliding cards and rows stay inside the panel's outline, and fade through its padding.
		val contentInside = Box(content.x + 2f, content.y + 2f, content.w - 4f, content.h - 4f)
		val contentEdges = SlideEdges(CONTENT_PAD - 4f, CONTENT_PAD - 4f, PANEL_RADIUS)

		val current = shownPage()
		if (current != null) {
			// A view's own page sits under the view's heading, with no Back.
			val opened = current === page
			current.beginFrame()
			current.trackTop = content.y + TRACK_INSET + 2f
			current.trackBottom = content.bottom - TRACK_INSET - 2f
			val titleX = header.x + HEADER_PAD - 4f
			val title = Box(titleX, searchBox.y, searchBox.x - 24f - titleX, FIELD_H)
			sliding(graphics, titleArea, shift, titleEdges) {
				if (opened) current.drawTitle(graphics, title, mx, my, dt, alpha * slid * shown) else drawHeading(graphics, header, alpha * slid * shown)
			}
			if (lendsSearch(current)) {
				MenuStyle.drawSearchBox(graphics, searchBox, search, searchFocused, searchBox.contains(mx, my), "Search for a module…", dt, alpha)
			} else {
				current.drawSearch(graphics, searchBox, mx, my, dt, alpha)
			}
			sliding(graphics, contentInside, shift, contentEdges) {
				switching(graphics, contentInside, shown) {
					val top = if (opened) {
						drawBack(graphics, content, mx, my, dt, alpha * slid * shown)
						current.drawViewSwitchAt(graphics, backBox.right + 14f, backBox, mx, my, dt, alpha * slid * shown)
						backBox.bottom + 14f
					} else {
						backBox = Box.NONE
						content.y + VIEW_PAGE_TOP
					}
					val pageBox = Box(content.x + CONTENT_PAD, top, content.w - CONTENT_PAD * 2f, content.bottom - 2f - top)
					current.draw(graphics, pageBox, mx, my, dt, alpha * slid * shown)
					current.drawOverlays(graphics, mx, my, alpha * slid * shown)
				}
			}
			current.rowTip?.let { (key, text) -> offerTip(key, text) }
		} else {
			backBox = Box.NONE
			sliding(graphics, titleArea, shift, titleEdges) {
				drawHeading(graphics, header, alpha * slid * shown)
			}
			MenuStyle.drawSearchBox(graphics, searchBox, search, searchFocused, searchBox.contains(mx, my), "Search for a module…", dt, alpha)
			sliding(graphics, contentInside, shift, contentEdges) {
				switching(graphics, contentInside, shown) {
					if (showingProfile) {
						cardHits.clear()
						profile.draw(graphics, gridBox, mx, my, dt, alpha * slid * shown)
					} else {
						drawGrid(graphics, gridBox, GRID_REST_TOP, GRID_REST_BOTTOM, mx, my, dt, alpha * slid * shown)
					}
				}
			}
		}

		if (frameTipKey != tipKey) {
			tipKey = frameTipKey
			tipTime = 0f
		} else if (tipKey != null) {
			tipTime += dt
		}
		if (tipKey != null) {
			MenuStyle.drawTooltip(graphics, tipText, mx, my, tipBounds, alpha * ((tipTime - TIP_DELAY) / 0.12f).coerceIn(0f, 1f))
		}

		graphics.pose().popMatrix()

		ConfigManager.autosave()
	}

	private var frameTipKey: Any? = null

	/**
	 * Draws [block] moved [shift] across, clipped to [panel] so nothing slides out past its edge.
	 * Clicks land where things will rest, so a click mid-slide still hits.
	 */
	private inline fun sliding(graphics: GuiGraphicsExtractor, panel: Box, shift: Float, edges: SlideEdges, block: () -> Unit) {
		if (shift == 0f) {
			block()
			return
		}
		MenuStyle.clip(graphics, panel)
		graphics.pose().pushMatrix()
		graphics.pose().translate(shift, 0f)
		block()
		graphics.pose().popMatrix()
		// Gradients in the panel's colour over the clip's sides, as the list has at its top and
		// bottom, so what slides comes out of them rather than from a hard line. They lie in the
		// padding, beyond where anything rests, so nothing is left under them when the slide ends.
		val band = Box(panel.x, panel.y + edges.inset, panel.w, panel.h - edges.inset * 2f)
		MenuStyle.drawSideFade(graphics, band, true, edges.left, MenuStyle.WINDOW_BG, 1f)
		MenuStyle.drawSideFade(graphics, band, false, edges.right, MenuStyle.WINDOW_BG, 1f)
		graphics.disableScissor()
	}

	/** Draws [block] scaled about [panel]'s centre by how far it is [shown], for a view switch. */
	private inline fun switching(graphics: GuiGraphicsExtractor, panel: Box, shown: Float, block: () -> Unit) {
		if (shown >= 1f) {
			block()
			return
		}
		val s = SWITCH_SCALE + (1f - SWITCH_SCALE) * shown
		val cx = panel.x + panel.w / 2f
		val cy = panel.y + panel.h / 2f
		graphics.pose().pushMatrix()
		graphics.pose().translate(cx, cy)
		graphics.pose().scale(s, s)
		graphics.pose().translate(-cx, -cy)
		block()
		graphics.pose().popMatrix()
	}

	/** Finishes a sidebar switch once the panel has faded out. */
	private fun switchTo(target: View) {
		pendingView = null
		page = null
		// The fade stands in for the slide a closing module would start.
		slide = 1f
		if (view != target) leaveView()
		view = target
		search.clear()
	}

	/** How far a slide's side gradients reach in from the left and right, and how far they stay off the top and bottom (the panel's corners). */
	private class SlideEdges(val left: Float, val right: Float, val inset: Float)

	/** Asks for [text] as a tooltip while the pointer stays on [key]. */
	private fun offerTip(key: Any, text: String) {
		frameTipKey = key
		if (key != tipKey) tipText = text
	}

	/** Cards toggled while listed, kept so turning one off in Enabled doesn't pull it from under the pointer. */
	private val keptInList = HashSet<Module>()

	private fun toggle(module: Module) {
		keptInList += module
		module.enabled = !module.enabled
	}

	private fun drawPanel(graphics: GuiGraphicsExtractor, box: Box, fill: Int, alpha: Float) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(fill, alpha), PANEL_RADIUS)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.WINDOW_EDGE, alpha), PANEL_RADIUS, 1f)
	}

	// ---- Sidebar ----

	private fun drawSidebar(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		sideHits.clear()
		// The mark and the name are centred as one, by what they draw: the logo image has wide
		// empty margins, and letters have side bearings.
		val logo = 28f
		val nameSize = 21f
		// Inter is spaced for small text; at this size it wants pulling in a little.
		val nameTracking = -0.5f
		val gap = 9f
		val (logoLeft, logoRight) = MenuLogo.inkSpan
		val (nameLeft, nameRight) = UiText.trackedInk("Cryptic", nameSize, UiText.Face.BOLD, nameTracking)
		val markW = (logoRight - logoLeft) * logo
		val start = box.x + (box.w - (markW + gap + nameRight - nameLeft)) / 2f
		MenuLogo.draw(graphics, start - logoLeft * logo, box.y + 30f, logo, fade(MenuStyle.TEXT, alpha))
		UiText.drawTracked(graphics, "Cryptic", start + markW + gap - nameLeft, box.y + 30f + (logo - nameSize) / 2f, nameSize, fade(MenuStyle.TEXT, alpha), UiText.Face.BOLD, nameTracking)

		val x = box.x + 14f
		val w = box.w - 28f
		var y = box.y + 92f
		y = drawGroupLabel(graphics, "MAIN", x, y, alpha)
		y = drawNavItem(graphics, View.PROFILE, x, y, w, mx, my, dt, alpha)
		y = drawNavItem(graphics, View.MODULES, x, y, w, mx, my, dt, alpha)
		val unfolding = if (chosenView == View.MODULES) 1f else -1f
		categoriesOpen = if (dt <= 0f) max(0f, unfolding) else (categoriesOpen + unfolding * dt / FOLD_SECONDS).coerceIn(0f, 1f)
		if (categoriesOpen > 0f) y = drawCategories(graphics, x, y, w, mx, my, dt, alpha)
		y = drawNavItem(graphics, View.FAVORITES, x, y, w, mx, my, dt, alpha)
		y = drawNavItem(graphics, View.ENABLED, x, y, w, mx, my, dt, alpha)

		y += 14f
		y = drawGroupLabel(graphics, "CONFIGURATION", x, y, alpha)
		y = drawNavItem(graphics, View.PROFILES, x, y, w, mx, my, dt, alpha)
		drawNavItem(graphics, View.SETTINGS, x, y, w, mx, my, dt, alpha)

		// Options sit at the foot of the sidebar.
		val footY = box.bottom - 16f - NAV_H - GROUP_LABEL_H
		val after = drawGroupLabel(graphics, "OPTIONS", x, footY, alpha)
		drawNavButton(graphics, HUD, Icons.EDIT, "Edit HUD", false, x, after, w, mx, my, dt, alpha)
	}

	/** A small grey caps label over a group of entries. Returns where the entries start. */
	private fun drawGroupLabel(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, alpha: Float): Float {
		UiText.drawTracked(graphics, text, x + 6f, y, 12f, fade(MenuStyle.GROUP_LABEL, alpha), UiText.Face.BOLD, 0.6f)
		return y + GROUP_LABEL_H
	}

	private fun drawNavItem(graphics: GuiGraphicsExtractor, item: View, x: Float, y: Float, w: Float, mx: Float, my: Float, dt: Float, alpha: Float): Float =
		drawNavButton(graphics, item, item.icon, item.label, chosenView == item, x, y, w, mx, my, dt, alpha)

	private fun drawNavButton(
		graphics: GuiGraphicsExtractor, key: Any, icon: String, label: String, selected: Boolean,
		x: Float, y: Float, w: Float, mx: Float, my: Float, dt: Float, alpha: Float,
	): Float {
		val box = Box(x, y, w, NAV_H)
		val over = box.contains(mx, my)
		val lift = approach(hover[key] ?: 0f, if (over) 1f else 0f, 18f, dt).also { hover[key] = it }
		val on = approach(selectedness[key] ?: 0f, if (selected) 1f else 0f, SELECT_SPEED, dt).also { selectedness[key] = it }
		val fill = blend(fade(MenuStyle.NAV_HOVER, lift), MenuStyle.navSelected(), on)
		if ((fill ushr 24) != 0) RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(fill, alpha), 7f)
		val color = blend(blend(MenuStyle.NAV_TEXT, MenuStyle.TEXT, lift), MenuStyle.accent(), on)
		UiText.drawIconCentered(graphics, icon, box.x + 8f, box.y + (box.h - 22f) / 2f, 22f, 14f, fade(color, alpha))
		UiText.draw(graphics, label, box.x + 40f, box.y + (box.h - 14f) / 2f, 14f, fade(color, alpha))
		sideHits += key to box
		return box.bottom + 4f
	}

	/** The categories under Modules: each jumps to its heading, and the one in view is lit. */
	private fun drawCategories(graphics: GuiGraphicsExtractor, x: Float, top: Float, w: Float, mx: Float, my: Float, dt: Float, alpha: Float): Float {
		val active = page?.module?.category ?: pinnedSection ?: if (search.isEmpty) inView() else null
		// Folding, the list shrinks from the bottom and fades; the entries below follow its edge.
		val eased = smoothstep(categoriesOpen)
		val shown = (MODULE_CATEGORIES.size * SUB_H + 6f) * eased
		val folding = categoriesOpen < 1f
		if (folding) MenuStyle.clip(graphics, Box(x, top, w, shown))
		val listAlpha = alpha * eased
		var y = top
		for (category in MODULE_CATEGORIES) {
			val box = Box(x, y, w, SUB_H)
			val over = box.contains(mx, my) && !folding
			val lift = approach(hover[category] ?: 0f, if (over) 1f else 0f, 18f, dt).also { hover[category] = it }
			val lit = approach(selectedness[category] ?: 0f, if (category == active) 1f else 0f, SELECT_SPEED, dt).also { selectedness[category] = it }
			val color = blend(blend(MenuStyle.MUTED, MenuStyle.TEXT, lift), MenuStyle.accent(), lit)
			if (lit > 0.01f) RoundRect.fill(graphics, box.x + 27f, box.y + box.h / 2f - 2f, box.x + 31f, box.y + box.h / 2f + 2f, fade(MenuStyle.accent(), listAlpha * lit), 2f)
			UiText.draw(graphics, category.title, box.x + 40f, box.y + (box.h - 13f) / 2f, 13f, fade(color, listAlpha))
			if (!folding) sideHits += category to box
			y += SUB_H
		}
		if (folding) graphics.disableScissor()
		return top + shown
	}

	private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

	/** The last category whose heading has scrolled to the top of the list. */
	private fun inView(): ModuleCategory? = sectionTops.entries.lastOrNull { it.value <= scroll + 24f }?.key
		?: sectionTops.keys.firstOrNull()

	private fun jumpTo(category: ModuleCategory) {
		pendingView = null
		page = null
		if (view != View.MODULES) leaveView()
		if (view != View.MODULES || !search.isEmpty) {
			view = View.MODULES
			search.clear()
		}
		searchFocused = false
		pendingJump = category
		pinnedSection = category
	}

	// ---- Header ----

	private fun drawHeading(graphics: GuiGraphicsExtractor, box: Box, alpha: Float) {
		val searching = !search.isEmpty
		val title = if (searching) "Search" else view.label
		val subtitle = if (searching) "Modules and settings matching \"${search.text.trim()}\"" else view.subtitle
		val x = box.x + HEADER_PAD
		val titleSize = 22f
		UiText.draw(graphics, title, x, box.y + (box.h - titleSize) / 2f, titleSize, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		val barX = x + UiText.width(title, titleSize, UiText.Face.MEDIUM) + 14f
		RoundRect.fill(graphics, barX, box.y + box.h / 2f - 9f, barX + 1f, box.y + box.h / 2f + 9f, fade(MenuStyle.FIELD_EDGE_HOVER, alpha), 0f)
		val room = searchBox.x - 24f - (barX + 14f)
		UiText.draw(graphics, UiText.fit(subtitle, 14f, room), barX + 14f, box.y + (box.h - 14f) / 2f, 14f, fade(MenuStyle.MUTED, alpha))
	}

	/** "← Back" at the top left of the settings panel. */
	private fun drawBack(graphics: GuiGraphicsExtractor, content: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val size = 13f
		val w = 10f + 14f + 8f + UiText.width("Back", size, UiText.Face.MEDIUM) + 12f
		val box = Box(content.x + CONTENT_PAD - 4f, content.y + 14f, w, 30f)
		backBox = box
		val over = box.contains(mx, my)
		val lift = approach(hover[BACK] ?: 0f, if (over) 1f else 0f, 18f, dt).also { hover[BACK] = it }
		// Under the pointer it lightens and its words turn white, as the sidebar's entries do; the
		// panel is lighter than the sidebar, so the step up is a little bigger.
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(blend(MenuStyle.WINDOW_BG, 0xFFFFFFFF.toInt(), 0.07f), alpha * lift), 7f)
		val color = fade(blend(MenuStyle.MUTED, MenuStyle.TEXT, lift), alpha)
		UiText.drawIconCentered(graphics, Icons.ARROW_LEFT, box.x + 8f, box.y + (box.h - 18f) / 2f, 18f, 13f, color)
		UiText.draw(graphics, "Back", box.x + 10f + 14f + 8f, box.y + (box.h - size) / 2f, size, color, UiText.Face.MEDIUM)
	}

	// ---- Cards ----

	private fun sections(): List<Section> {
		val byCategory = visibleModules().groupBy { it.category }
		return ModuleCategory.entries.mapNotNull { category -> byCategory[category]?.let { Section(category, it) } }
	}

	/**
	 * The cards under their category headings, scrolled and clipped to [box], resting [insetTop]
	 * and [insetBottom] inside it, where the fades are.
	 */
	private fun drawGrid(graphics: GuiGraphicsExtractor, box: Box, insetTop: Float, insetBottom: Float, mx: Float, my: Float, dt: Float, alpha: Float) {
		cardHits.clear()
		// Each list keeps its own scroll position.
		val key = listKey()
		if (key != shownList) {
			shownList?.let { listScrolls[it] = scrollTarget }
			scrollTarget = listScrolls[key] ?: 0f
			scroll = scrollTarget
			shownList = key
			keptInList.clear()
			if (pendingJump == null) pinnedSection = null
		}
		val sections = sections()
		val x = box.x
		val y = box.y + insetTop
		val w = box.w
		val h = box.h - insetTop - insetBottom

		sectionTops.clear()
		if (sections.isEmpty()) {
			when {
				!search.isEmpty -> MenuStyle.drawEmptyState(
					graphics, box, Icons.SEARCH, "No modules match \"${search.text.trim()}\"",
					"Try another keyword, or clear the search to see every module.", alpha,
				)
				view == View.FAVORITES -> MenuStyle.drawEmptyState(graphics, box, Icons.STAR, "No favorites yet", "Star a module to pin it here.", alpha, outline = true)
				view == View.ENABLED -> MenuStyle.drawEmptyState(graphics, box, Icons.POWER, "Nothing is on", "Click a module's card to turn it on.", alpha)
				else -> MenuStyle.drawEmptyState(graphics, box, Icons.GRID, "No modules here", "Client Restrictions may be hiding them.", alpha)
			}
			maxScroll = 0f
			return
		}

		val columns = max(2, ((w + CARD_GAP) / (CARD_MIN_W + CARD_GAP)).toInt())
		val cardW = (w - CARD_GAP * (columns - 1)) / columns
		var contentH = 0f
		for ((index, section) in sections.withIndex()) {
			if (index > 0) contentH += SECTION_GAP
			sectionTops[section.category] = contentH
			val rows = (section.modules.size + columns - 1) / columns
			contentH += HEADING_H + rows * CARD_H + (rows - 1) * CARD_GAP
		}
		maxScroll = max(0f, contentH - h)
		pendingJump?.let { category ->
			pendingJump = null
			scrollTarget = sectionTops[category] ?: scrollTarget
		}
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)

		val shown = MenuStyle.clipList(graphics, box)
		val pointerInGrid = box.contains(mx, my)
		for (section in sections) {
			val top = y + (sectionTops[section.category] ?: 0f) - scroll
			drawSectionHeading(graphics, section.category.title, x, top, w, alpha)
			val cardsTop = top + HEADING_H
			for ((index, module) in section.modules.withIndex()) {
				val cx = x + (index % columns) * (cardW + CARD_GAP)
				val cy = cardsTop + (index / columns) * (CARD_H + CARD_GAP)
				if (cy + CARD_H < box.y || cy > box.bottom) continue
				drawCard(graphics, module, cx, cy, cardW, mx, my, pointerInGrid, dt, alpha)
			}
		}
		MenuStyle.drawScrollFades(graphics, shown, scroll, maxScroll, alpha, topRamp = insetTop, bottomRamp = insetBottom)
		graphics.disableScissor()

		// The bar runs the panel's height, not just the cards'.
		val trackTop = box.y + TRACK_INSET
		scrollbar.draw(graphics, x + w + Scrollbar.GAP, trackTop, box.bottom - TRACK_INSET - trackTop, contentH, scroll, maxScroll, mx, my, dt, alpha)
	}

	/** A category's name with a hairline running from it to the right edge. */
	private fun drawSectionHeading(graphics: GuiGraphicsExtractor, title: String, x: Float, y: Float, w: Float, alpha: Float) {
		val size = 19f
		UiText.draw(graphics, title, x + 2f, y, size, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		val lineX = x + 2f + UiText.width(title, size, UiText.Face.MEDIUM) + 14f
		val lineY = y + size / 2f
		RoundRect.fill(graphics, lineX, lineY, x + w, lineY + 1f, fade(MenuStyle.HEADING_LINE, alpha), 0f)
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
		val target = if (on) 1f else 0f
		val t = approach(onness[module] ?: target, target, 14f, dt).also { onness[module] = it }

		// On, a card takes the accent's edge and a faint wash of it.
		val offFill = blend(MenuStyle.CARD_OFF, MenuStyle.CARD_OFF_HOVER, lift)
		val onFill = blend(MenuStyle.cardTint(), MenuStyle.cardTintHover(), lift)
		// With coloured icons, hovering outlines the card in its module's colour.
		val coloured = ClickGui.colouredIcons.value
		val ownColour = ModuleIcons.colourOf(module)
		val offHoverEdge = if (coloured) blend(MenuStyle.CARD_EDGE, ownColour, 0.6f) else MenuStyle.CARD_EDGE_HOVER
		val offEdge = blend(MenuStyle.CARD_EDGE, offHoverEdge, lift)
		val onEdge = blend(MenuStyle.cardOnEdge(), if (coloured) ownColour else MenuStyle.accent(), lift)
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(blend(offFill, onFill, t), alpha), CARD_RADIUS)
		RoundRect.outline(graphics, x, y, x + w, y + h, fade(blend(offEdge, onEdge, t), alpha), CARD_RADIUS, 1f)

		// A coloured icon is a little muted while its module is off, so turning it on still shows.
		val iconColor = if (coloured) {
			blend(blend(ownColour, MenuStyle.CARD_ICON, 0.35f), ownColour, t)
		} else {
			blend(MenuStyle.CARD_ICON, MenuStyle.accent(), t)
		}
		UiText.drawIconCentered(graphics, iconFor(module), x + 12f, y + 14f, 20f, 14f, fade(iconColor, alpha))

		// Along the top right: the star, then the gear. The card itself is the switch.
		val more = Box(x + w - 10f - 22f, y + 13f, 22f, 22f)
		val star = Box(more.x - 22f, y + 13f, 22f, 22f)
		// Each takes its half of the corner, so a click just off either still hits it.
		val split = (star.right + more.x) / 2f
		val starZone = Box(star.x - CORNER_SLACK, y, split - star.x + CORNER_SLACK, CORNER_H)
		val moreZone = Box(split, y, x + w - split, CORNER_H)
		val overMore = pointerInGrid && moreZone.contains(mx, my)
		val overStar = pointerInGrid && starZone.contains(mx, my)

		if (overMore) offerTip(module to "more", "Settings")
		UiText.drawIconCentered(graphics, Icons.GEAR, more.x, more.y, more.w, 12f, fade(if (overMore) MenuStyle.TEXT else MenuStyle.CARD_GEAR, alpha))

		val favorite = MenuPrefs.isFavorite(module.id)
		// The empty star only shows under the pointer; a filled one always shows.
		val starColor = when {
			favorite -> MenuStyle.STAR
			overStar -> MenuStyle.TEXT
			else -> fade(MenuStyle.CARD_GEAR, lift)
		}
		UiText.drawIconCentered(graphics, Icons.STAR, star.x, star.y, star.w, 12f, fade(starColor, alpha), outline = !favorite)
		if (overStar) offerTip(module to "star", if (favorite) "Unfavorite" else "Favorite")

		val nameX = x + 40f
		// The name runs up to the gear while the star is hidden.
		val nameRight = if (favorite || hovered) star.x else more.x
		val name = UiText.fit(module.name, NAME_TEXT, nameRight - 4f - nameX, UiText.Face.MEDIUM)
		UiText.draw(graphics, name, nameX, y + 16.5f, NAME_TEXT, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)

		val lines = descriptionLines(module.description, w - 32f)
		lines.forEachIndexed { i, line ->
			UiText.draw(graphics, line, x + 16f, y + 46f + i * DESC_LINE, DESC_TEXT, fade(MenuStyle.CARD_DESC, alpha))
		}
		// A cut description shows whole on a rest over the card.
		val cut = lines.lastOrNull()?.endsWith("…") == true && !module.description.endsWith("…") || name != module.name
		if (hovered && !overMore && !overStar && cut) offerTip(module, module.description)

		cardHits += CardHit(module, card, starZone, moreZone)
	}

	/** Up to two lines of [text] across [width], the second cut short if it runs over. */
	private fun descriptionLines(text: String, width: Float): List<String> {
		if (text.isEmpty()) return emptyList()
		val key = "$width|$text"
		return wrapped.getOrPut("${UiText.pixelsPerUnit}|$key") {
			val lines = UiText.wrap(text, DESC_TEXT, width)
			if (lines.size <= 2) lines else listOf(lines[0], UiText.fit(lines.drop(1).joinToString(" "), DESC_TEXT, width))
		}
	}

	/** Wrapped descriptions by width and text; a resize only adds keys. Cleared with the screen. */
	private val wrapped = HashMap<String, List<String>>()

	private fun designX(x: Double) = (x / scale()).toFloat()
	private fun designY(y: Double) = (y / scale()).toFloat()

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val mx = designX(event.x())
		val my = designY(event.y())
		val current = shownPage()
		val button = event.button()

		// A bind waiting for its key takes any button first.
		if (current != null && current.awaitingBind && button != 0) {
			current.mouseClicked(mx, my, button)
			return true
		}

		if (button == 0) {
			for ((key, box) in sideHits) {
				if (!box.contains(mx, my)) continue
				when (key) {
					HUD -> Hud.openEditor(this)
					is ModuleCategory -> jumpTo(key)
					is View -> {
						searchFocused = false
						if (view != key) {
							pendingView = key
						} else if (pendingView != null) {
							// Back to the view still showing, before the switch finished.
							pendingView = null
						} else if (page != null || !search.isEmpty) {
							page = null
							search.clear()
						} else {
							// The view already showing goes back to its top.
							scrollTarget = 0f
							pinnedSection = null
						}
					}
				}
				return true
			}
		}

		if (current != null) {
			if (current === page && ((button == 0 && backBox.contains(mx, my)) || button == GLFW.GLFW_MOUSE_BUTTON_4)) {
				forward = current.module
				page = null
				return true
			}
			if (lendsSearch(current) && button == 0) {
				val wasFocused = searchFocused
				searchFocused = searchBox.contains(mx, my)
				if (searchFocused) {
					if (doubleClick) search.selectAll() else search.pointAt(mx, extend = wasFocused && event.hasShiftDown())
					selectingSearch = true
					return true
				}
			}
			current.mouseClicked(mx, my, button, doubleClick)
			return true
		}

		// The mouse's forward button reopens the module just left, as a browser would.
		if (button == GLFW.GLFW_MOUSE_BUTTON_5) {
			forward?.let(::open)
			return true
		}

		if (button == 0) {
			val wasFocused = searchFocused
			searchFocused = searchBox.contains(mx, my)
			if (searchFocused) {
				// A double click selects the whole query.
				if (doubleClick) search.selectAll() else search.pointAt(mx, extend = wasFocused && event.hasShiftDown())
				selectingSearch = true
				return true
			}
			if (showingProfile) return profile.mouseClicked(mx, my) || super.mouseClicked(event, doubleClick)
			if (scrollbar.press(mx, my, maxScroll, ::setScroll)) {
				pinnedSection = null
				return true
			}
		}
		if (showingProfile) return super.mouseClicked(event, doubleClick)

		if (!gridBox.contains(mx, my)) return super.mouseClicked(event, doubleClick)
		for (hit in cardHits) {
			if (!hit.card.contains(mx, my)) continue
			// Settings found by the search open on the Settings tab, still searched for.
			if (hit.module === SettingsTab.module && (button == 0 || button == 1)) {
				showSettings(search.text.trim())
				return true
			}
			when {
				button == 1 -> if (ClickGui.rightClickEdits.value) open(hit.module)
				button != 0 -> return super.mouseClicked(event, doubleClick)
				hit.star.contains(mx, my) -> MenuPrefs.toggleFavorite(hit.module.id)
				hit.more.contains(mx, my) -> open(hit.module)
				hit.module.cardAction != null -> hit.module.cardAction.invoke()
				hit.module.supportsToggle -> toggle(hit.module)
			}
			return true
		}
		return super.mouseClicked(event, doubleClick)
	}

	/** The module Back last left, for the mouse's forward button. */
	private var forward: Module? = null

	private fun open(module: Module) {
		page = ModulePage(module)
		searchFocused = false
		forward = null
	}

	private fun showSettings(query: String) {
		pendingView = null
		page = null
		if (view != View.SETTINGS) leaveView()
		view = View.SETTINGS
		search.clear()
		searchFocused = false
		viewPage()?.search(query)
	}

	private fun setScroll(value: Float) {
		scroll = value
		scrollTarget = value
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		val mx = designX(event.x())
		val my = designY(event.y())
		shownPage()?.let { if (it.mouseDragged(mx, my)) return true }
		if (showingProfile && profile.mouseDragged(my)) return true
		if (scrollbar.drag(my, maxScroll, ::setScroll)) return true
		if (selectingSearch) {
			search.pointAt(mx, extend = true)
			return true
		}
		return super.mouseDragged(event, dragX, dragY)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		selectingSearch = false
		if (shownPage()?.mouseReleased() == true) return true
		if (profile.mouseReleased()) return true
		if (scrollbar.release()) return true
		return super.mouseReleased(event)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		val current = shownPage()
		if (current != null) {
			current.mouseScrolled(verticalAmount.toFloat())
			return true
		}
		if (showingProfile) {
			profile.mouseScrolled(verticalAmount.toFloat())
			return true
		}
		pinnedSection = null
		scrollTarget = (scrollTarget - verticalAmount.toFloat() * SCROLL_STEP).coerceIn(0f, maxScroll)
		return true
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val current = shownPage()
		val lends = lendsSearch(current)
		if (current != null && !(lends && searchFocused)) {
			if (current.keyPressed(event)) return true
			// Escape with nothing to cancel closes the menu, which reopens on this module.
			if (!lends) return super.keyPressed(event)
		}

		val control = (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0
		if (control && (event.key() == GLFW.GLFW_KEY_F || (event.key() == GLFW.GLFW_KEY_A && !searchFocused))) {
			searchFocused = true
			search.selectAll()
			return true
		}
		if (searchFocused) {
			when (event.key()) {
				// The first Escape leaves the field; the next closes the menu.
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
		val current = shownPage()
		if (current != null) {
			val lends = lendsSearch(current)
			if (!(lends && searchFocused)) {
				if (current.charTyped(codePoint)) return true
				if (!lends) return false
			}
		}
		if (codePoint < ' '.code) return false
		// Typing anywhere goes to the search.
		searchFocused = true
		search.charTyped(codePoint)
		return true
	}

	override fun onClose() {
		ConfigManager.autosave()
		super.onClose()
	}

	private fun listKey(): String = when {
		!search.isEmpty -> "search:" + search.text.trim().lowercase()
		else -> "view:${view.name}"
	}

	private var shownList: String? = null

	override fun removed() {
		// Closed mid-switch, the menu reopens on the view that was chosen.
		pendingView?.let(::switchTo)
		shownList?.let { listScrolls[it] = scrollTarget }
		page?.onClose()
		viewPages.values.forEach { it.onClose() }
		if (ClickGui.rememberSearch.value) ClickGui.rememberedSearch = search.text
		ConfigManager.autosave()
		super.removed()
	}

	override fun init() {
		super.init()
		pendingSearch?.let { query ->
			pendingSearch = null
			page = null
			search.set(query)
			return
		}
		if (ClickGui.rememberSearch.value && search.isEmpty && ClickGui.rememberedSearch.isNotEmpty()) search.set(ClickGui.rememberedSearch)
	}

	override fun keyReleased(event: KeyEvent): Boolean {
		if (shownPage()?.keyReleased(event) == true) return true
		return super.keyReleased(event)
	}

	private fun visibleModules(): List<Module> {
		val query = search.text.trim()
		// Client Restrictions hides what's above the chosen level. The client's own modules live on
		// the Settings tab, which the search lists as one card.
		val all = ModuleRegistry.modules.filter { it.allowed && it.category != ModuleCategory.MISC }
		val modules = when {
			query.isNotEmpty() -> (all + SettingsTab.module).filter { module ->
				module.name.contains(query, ignoreCase = true) || module.description.contains(query, ignoreCase = true) ||
					module.settings.any { it.hasOwnRow && it.label.contains(query, ignoreCase = true) }
			}
			else -> all.filter { inView(it) || it in keptInList }
		}
		return when (ClickGui.sorting.selectedIndex) {
			ClickGui.SORT_Z_TO_A -> modules.sortedByDescending(Module::sortKey)
			// Longest name first, measured as drawn.
			ClickGui.SORT_WIDTH -> modules.sortedWith(compareByDescending<Module> { UiText.width(it.name, NAME_TEXT, UiText.Face.MEDIUM) }.thenBy(Module::sortKey))
			else -> modules.sortedBy(Module::sortKey)
		}
	}

	private fun inView(module: Module): Boolean = when (view) {
		View.MODULES -> true
		View.FAVORITES -> MenuPrefs.isFavorite(module.id)
		View.ENABLED -> module.supportsToggle && module.enabled
		View.PROFILE, View.PROFILES, View.SETTINGS -> false
	}

	private fun iconFor(module: Module): String = ModuleIcons.of(module) ?: when (module.category) {
		ModuleCategory.GENERAL -> Icons.GEAR
		ModuleCategory.DUNGEON -> Icons.DUNGEON
		ModuleCategory.FLOOR_7 -> Icons.SKULL
		ModuleCategory.VISUAL -> Icons.EYE
		ModuleCategory.MISC -> Icons.SLIDERS
	}

	companion object {
		private var view = View.MODULES

		private var lastPage: String? = null

		private val listScrolls = HashMap<String, Float>()

		private var requested = false

		/**
		 * Opens the menu next tick, on [module]'s page if given. Deferred because a command runs
		 * while chat is still closing, which would close the new screen too.
		 */
		fun request(module: Module? = null, settings: Boolean = false, reveal: imicro.cryptic.gui.ModuleSetting? = null) {
			if (module != null) lastPage = module.id
			openOnSettings = settings
			pendingReveal = reveal
			requested = true
		}

		private var openOnSettings = false

		/** A setting the page opens on: its tab, with its "⋯" panel open. */
		private var pendingReveal: imicro.cryptic.gui.ModuleSetting? = null

		/** A search to open on, from "/cryptic <words>"; it replaces whatever page was open. */
		private var pendingSearch: String? = null

		fun requestSearch(query: String) {
			pendingSearch = query.trim()
			requested = true
		}

		/** Opens [module]'s page in the menu that is up, or the menu on it. */
		fun show(module: Module) {
			val screen = net.minecraft.client.Minecraft.getInstance().gui.screen() as? MenuScreen ?: return request(module)
			screen.open(module)
		}

		fun openIfRequested(client: net.minecraft.client.Minecraft) {
			if (!requested) return
			requested = false
			client.gui.setScreen(MenuScreen())
		}

		/**
		 * The blur strength for this frame: Click GUI's while this menu (or the daily reward picker,
		 * drawn in its style) is open with blur on, otherwise zero (vanilla's own).
		 */
		@JvmStatic
		fun blurRadius(): Int {
			val screen = net.minecraft.client.Minecraft.getInstance().gui.screen()
			if (screen !is MenuScreen && screen !is imicro.cryptic.gui.RewardScreen) return 0
			return if (ClickGui.backgroundBlur.value) ClickGui.blurStrength.value.toInt() else 0
		}

		private val MODULE_CATEGORIES = ModuleCategory.entries.filter { it != ModuleCategory.MISC }

		private const val TIP_DELAY = 0.45f

		/** The top-right corner the star and gear share: its height, and how far it reaches left of the star. */
		private const val CORNER_H = 44f
		private const val CORNER_SLACK = 8f
		private const val BACK = "back"
		private const val HUD = "hud"

		/** The screen height the layout is drawn for: 1350 makes the menu 80% of its size at 1080. */
		private const val DESIGN_HEIGHT = 1350f
		private const val OPEN_SECONDS = 0.22f
		private const val SLIDE_SECONDS = 0.28f
		private const val SLIDE_DISTANCE = 48f
		private const val MAX_SEARCH = 48

		private const val WINDOW_W = 1420f
		private const val WINDOW_H = 760f
		private const val PANEL_GAP = 12f
		private const val PANEL_RADIUS = 10f
		private const val PANEL_SHADOW = 0x38000000

		private const val SIDEBAR_W = 210f
		private const val NAV_H = 38f
		private const val SUB_H = 30f
		private const val FOLD_SECONDS = 0.22f
		private const val SELECT_SPEED = 16f
		private const val SWITCH_OUT_SECONDS = 0.12f
		private const val SWITCH_IN_SECONDS = 0.18f
		private const val SWITCH_SCALE = 0.96f
		private const val GROUP_LABEL_H = 27f

		private const val HEADER_H = 58f
		private const val HEADER_PAD = 16f
		private const val FIELD_H = 34f
		private const val SEARCH_W = 290f

		private const val CONTENT_PAD = 24f

		/** How far a view's own page starts below the panel's top, level with the cards' first heading. */
		private const val VIEW_PAGE_TOP = 22f

		/** Where the cards rest inside the scroll area: first heading this far below its top, last row this far above its bottom. */
		private const val GRID_REST_TOP = 20f
		private const val GRID_REST_BOTTOM = 20f

		/** How far the scrollbar stops short of the panel's top and bottom (the grid's box sits 2 inside it). */
		private const val TRACK_INSET = 6f

		private const val CARD_MIN_W = 240f
		private const val CARD_H = 96f
		private const val CARD_GAP = 14f
		private const val CARD_RADIUS = 8f
		private const val HEADING_H = 36f
		private const val SECTION_GAP = 26f
		private const val SCROLL_STEP = 70f

		private const val NAME_TEXT = 15f
		private const val DESC_TEXT = 12f
		private const val DESC_LINE = 16f
	}
}
