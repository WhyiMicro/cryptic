package imicro.cryptic.gui.menu

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.feature.ClickGui
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownSource
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.MultiSelectModuleSetting
import imicro.cryptic.gui.PanelModuleSetting
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TabModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.SlotMapModuleSetting
import imicro.cryptic.gui.menu.MenuStyle.Icons
import imicro.cryptic.gui.menu.MenuStyle.approach
import imicro.cryptic.gui.menu.MenuStyle.blend
import imicro.cryptic.gui.menu.MenuStyle.fade
import imicro.cryptic.render.RoundRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * One module's settings, shown in place of the card grid: full-width rows with label and
 * description on the left and the control on the right, under the module's headings. A row with
 * settings of its own gets a chevron that opens them indented underneath. Dropdowns and the colour
 * picker float over the list.
 */
class ModulePage(val module: Module, private val keybindPlacement: KeybindPlacement = KeybindPlacement.TOP) {
	/** Where a page shows its module's own bind: under [section], or first when that is null. */
	class KeybindPlacement(val section: ModuleSetting?, val label: String, val description: String) {
		companion object {
			val TOP = KeybindPlacement(null, "Keybind", "The key bound to this module. Escape or Backspace clears it.")
		}
	}

	/** The module's extras besides settings, if any; the page opens on it. */
	val manager: ManagerSection? = ManagerSection.forModule(module)?.also { it.page = this }

	var showingManager = manager != null
		private set

	private var managerScroll = 0f
	private var settingsScroll = 0f

	/** The module's tabs, if it splits its settings into them, and the one shown. */
	private val tabs = module.settings.filterIsInstance<TabModuleSetting>()
	private var tabIndex = 0
	private sealed class Hit(val box: Box) {
		class Toggle(box: Box, val setting: ToggleModuleSetting) : Hit(box)
		class Expand(box: Box, val setting: ModuleSetting) : Hit(box)
		class Track(box: Box, val setting: ModuleSetting, val barX: Float, val barW: Float) : Hit(box)
		class Value(box: Box, val target: Edit) : Hit(box)
		class Dropdown(box: Box, val source: DropdownSource) : Hit(box)
		class Keybind(box: Box, val target: Any) : Hit(box)
		class Button(box: Box, val setting: ButtonModuleSetting) : Hit(box)
		class Swatch(box: Box, val setting: ColorModuleSetting) : Hit(box)
		class Text(box: Box, val target: Edit) : Hit(box)
		class Search(box: Box) : Hit(box)
		class Star(box: Box) : Hit(box)
		class View(box: Box, val manager: Boolean, val tab: Int) : Hit(box)
		class Reset(box: Box, val setting: ModuleSetting) : Hit(box)

		/** A whole row behind its controls: a click on the rest of it. */
		class Row(box: Box, val setting: ModuleSetting) : Hit(box)
	}

	sealed class Edit {
		data class Slider(val setting: SliderModuleSetting) : Edit()
		data class Lower(val setting: RangeModuleSetting) : Edit()
		data class Upper(val setting: RangeModuleSetting) : Edit()
		data class Text(val setting: TextModuleSetting) : Edit()
		data class Hex(val setting: ColorModuleSetting) : Edit()
	}

	private val hits = ArrayList<Hit>()
	private val expanded = HashSet<ModuleSetting>()
	private val switchPosition = HashMap<Any, Float>()
	private val hover = HashMap<Any, Float>()

	private var area = Box.NONE
	private var scroll = 0f
	private var scrollTarget = 0f
	private var maxScroll = 0f

	/** Where this module's page was left, this session. */
	private class Place(
		val showingManager: Boolean,
		val tabIndex: Int,
		val settingsScroll: Float,
		val managerScroll: Float,
		val expanded: Set<ModuleSetting>,
	)

	init {
		remembered[module.id]?.let { place ->
			if (manager != null) showingManager = place.showingManager
			tabIndex = place.tabIndex.coerceIn(0, maxOf(0, tabs.size - 1))
			settingsScroll = place.settingsScroll
			managerScroll = place.managerScroll
			scrollTarget = if (showingManager) managerScroll else settingsScroll
			scroll = scrollTarget
			expanded += place.expanded
		}
	}

	/** Switches a manager's page to its settings, as the HUD editor's right-click asks. */
	fun openSettings() {
		if (!showingManager) return
		showingManager = false
		scrollTarget = settingsScroll
		scroll = scrollTarget
	}

	/** Shows [setting]: its tab, with its own "⋯" panel and any it sits in open. */
	fun reveal(setting: ModuleSetting) {
		openSettings()
		var top = setting
		while (true) {
			val parent = top.parent ?: break
			expanded += parent
			top = parent
		}
		if (setting.more.isNotEmpty()) expanded += setting
		var tab = -1
		for (each in module.settings) {
			if (each is TabModuleSetting) tab = tabs.indexOf(each)
			if (each === top) break
		}
		if (tab >= 0 && tab != tabIndex) {
			tabIndex = tab
			settingsScroll = 0f
			scrollTarget = 0f
			scroll = 0f
		}
	}

	private fun remember(keepExpanded: Boolean) {
		if (showingManager) managerScroll = scrollTarget else if (!searching) settingsScroll = scrollTarget
		remembered[module.id] = Place(showingManager, tabIndex, settingsScroll, managerScroll, if (keepExpanded) expanded.toSet() else emptySet())
	}

	private var editing: Edit? = null
	private var input = TextField(0)

	/** True while a press in the active field is dragging out a selection. */
	private var selecting = false

	/** A click on a number's box that didn't become a drag selects the number on release. */
	private var pendingSelectAll = false
	private var frameDt = 0f
	private val scrollbar = Scrollbar()

	/** A bind waiting for its key: a [KeybindModuleSetting], or the module itself for its own bind. */
	private var awaiting: Any? = null

	private var dragging: Hit.Track? = null
	private var dragLower = true

	private var openDropdown: DropdownSource? = null
	private var dropdownAnchor = Box.NONE
	private var dropdownBox = Box.NONE
	private val dropdownRows = ArrayList<Pair<Int, Box>>()

	private var openColor: ColorModuleSetting? = null
	private var colorAnchor = Box.NONE
	private var pickerBox = Box.NONE
	private var pickerSquare = Box.NONE
	private var pickerHue = Box.NONE
	private var pickerAlpha = Box.NONE
	private var pickerHex = Box.NONE
	private var hsv = floatArrayOf(0f, 0f, 0f)

	/** Which part of the picker is dragged: 0 none, 1 square, 2 hue, 3 alpha. */
	private var pickerDrag = 0

	/** The search in the title row; while it holds text, the list shows only what matches. */
	private val query = TextField(48)
	private var queryFocused = false
	private var selectingQuery = false
	val searchable = module.supportsKeybind || module.settings.any {
		it.hasOwnRow && it !is SectionModuleSetting && it !is TabModuleSetting && it !is SlotMapModuleSetting
	}
	private val searching: Boolean get() = query.text.isNotBlank()

	/** Set by a key that set a bind, so the character it also types isn't taken as a search. */
	private var swallowChar = false

	/** A bind (or a manager's own capture) waiting for its key, which takes the mouse's side buttons too. */
	val awaitingBind: Boolean get() = awaiting != null || (showingManager && manager?.capturingKeys == true)

	/** The view switch's parts: a manager and its settings, or the module's tabs. Null when there's one view. */
	private fun viewParts(): List<Pair<Hit.View, String>>? {
		val section = manager
		return when {
			// A manager beside a module's own tabs: one part for it, then each tab.
			section != null && tabs.size > 1 -> listOf(Hit.View(Box.NONE, true, 0) to section.tabLabel) +
				tabs.withIndex().filter { it.value.isVisible() }.map { Hit.View(Box.NONE, false, it.index) to it.value.label }
			section != null && (module.supportsKeybind || module.settings.any { it.onCard && it.isVisible() && it !is SlotMapModuleSetting }) ->
				listOf(Hit.View(Box.NONE, true, 0) to section.tabLabel, Hit.View(Box.NONE, false, 0) to "Settings")
			tabs.size > 1 -> tabs.withIndex().filter { it.value.isVisible() }.map { Hit.View(Box.NONE, false, it.index) to it.value.label }
			else -> null
		}
	}

	/** The view switch, left-aligned at [x] and centred on [row], if the module has more than one view. */
	fun drawViewSwitchAt(graphics: GuiGraphicsExtractor, x: Float, row: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val parts = viewParts() ?: return
		drawViewSwitch(graphics, parts, x + viewSwitchWidth(parts), row, mx, my, dt, alpha)
	}

	/** The title row: the module's name, its description and its star, across [box]. */
	fun drawTitle(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val limit = box.right
		val nameSize = 21f
		val nameW = UiText.width(module.name, nameSize, UiText.Face.MEDIUM)
		val starRoom = 22f + 10f
		val row = box

		val detailSize = 14f
		var x = row.x + 4f
		UiText.draw(graphics, module.name, x, row.y + (row.h - nameSize) / 2f, nameSize, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		x += nameW + 12f
		if (module.description.isNotEmpty() && limit - x - starRoom > 60f) {
			val detailY = row.y + (row.h - detailSize) / 2f
			UiText.draw(graphics, "|", x, detailY, detailSize, fade(MenuStyle.FIELD_EDGE_HOVER, alpha))
			x += UiText.width("|", detailSize) + 12f
			val detail = UiText.fit(module.description, detailSize, limit - x - starRoom)
			UiText.draw(graphics, detail, x, detailY, detailSize, fade(MenuStyle.MUTED, alpha))
			x += UiText.width(detail, detailSize) + 10f
		}

		val star = Box(x, row.y + (row.h - 22f) / 2f, 22f, 22f)
		val favorite = MenuPrefs.isFavorite(module.id)
		val starColor = when {
			favorite -> MenuStyle.STAR
			star.contains(mx, my) -> MenuStyle.TEXT
			else -> MenuStyle.MUTED
		}
		UiText.drawIconCentered(graphics, Icons.STAR, star.x, star.y, star.w, 14f, fade(starColor, alpha), outline = !favorite)
		hits += Hit.Star(star)
	}

	/** The settings search, in the menu's search box. A module with nothing to search shows it inert. */
	fun drawSearch(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		MenuStyle.drawSearchBox(graphics, box, query, queryFocused, searchable && box.contains(mx, my), "Search for a setting…", dt, alpha)
		if (searchable) hits += Hit.Search(box)
	}

	fun draw(graphics: GuiGraphicsExtractor, content: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		var box = content
		// One heads-up above the settings, where it stays while they scroll, for the module and any
		// settings past it; the settings themselves only carry a small sign.
		pageNotice?.let { (title, text, level) ->
			val h = MenuStyle.drawCallout(graphics, Icons.WARNING, title, text, MenuStyle.restrictionColor(level), content.x, content.y, content.w, alpha)
			val below = content.y + h + 14f
			box = Box(content.x, below, content.w, content.bottom - below)
		}
		area = box
		frameDt = dt
		val pointerInside = box.contains(mx, my) && !overlayContains(mx, my)

		if (showingManager) {
			drawManager(graphics, box, mx, my, dt, alpha)
			return
		}

		if (searching) {
			drawResults(graphics, box, mx, my, pointerInside, dt, alpha)
			return
		}

		if (!module.supportsKeybind && module.settings.none { it.onCard && it.isVisible() && it !is SlotMapModuleSetting }) {
			MenuStyle.drawEmptyState(graphics, box, Icons.SLIDERS, "Nothing to set", "This module has no settings. Click its card to turn it on or off.", alpha)
			maxScroll = 0f
			return
		}

		val shown = MenuStyle.clipList(graphics, box)
		var y = box.y + LIST_TOP - scroll
		var first = true

		if (module.supportsKeybind && keybindPlacement.section == null) {
			y += drawKeybindRow(graphics, box.x, y, box.w, mx, my, pointerInside, dt, alpha)
			first = false
		}

		val shownTab = tabs.getOrNull(tabIndex)
		var inTab = shownTab == null
		for (setting in module.settings) {
			if (setting is TabModuleSetting) {
				inTab = setting === shownTab
				continue
			}
			if (!inTab || !setting.onCard || !setting.isVisible()) continue
			if (setting is SectionModuleSetting) {
				y += drawHeading(graphics, setting.label, box.x, y, box.w, first, alpha)
				first = false
				if (module.supportsKeybind && keybindPlacement.section === setting) y += drawKeybindRow(graphics, box.x, y, box.w, mx, my, pointerInside, dt, alpha)
				if (setting.hasVisibleMore()) y += drawChildren(graphics, setting, box, y, mx, my, pointerInside, dt, alpha, alwaysOpen = true)
				continue
			}
			first = false
			y += drawRow(graphics, setting, box.x, y, box.w, nested = false, mx, my, pointerInside, dt, alpha)
			y += drawExpanding(graphics, setting, box, y, mx, my, pointerInside, dt, alpha)
		}
		closeGroup(graphics, y, alpha)
		MenuStyle.drawScrollFades(graphics, shown, scroll, maxScroll, alpha)
		graphics.disableScissor()

		val contentH = y + scroll - box.y
		maxScroll = max(0f, contentH - box.h + 8f)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)
		drawScrollbar(graphics, box, contentH, mx, my, dt, alpha)
	}

	/** Every setting matching the search, from every tab and "⋯" panel, under its own heading. */
	private fun drawResults(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float) {
		val words = query.text.trim()
		val shown = MenuStyle.clipList(graphics, box)
		var y = box.y + LIST_TOP - scroll
		var first = true
		if (module.supportsKeybind && ("keybind".contains(words, ignoreCase = true) || keybindPlacement.label.contains(words, ignoreCase = true))) {
			y += drawKeybindRow(graphics, box.x, y, box.w, mx, my, pointerInside, dt, alpha)
			first = false
		}
		var section: SectionModuleSetting? = null
		var headed = false
		for (setting in module.settings) {
			when (setting) {
				is SectionModuleSetting -> {
					section = setting.takeIf { it.isVisible() }
					headed = false
					continue
				}
				is TabModuleSetting -> {
					section = null
					continue
				}
				else -> if (!matches(setting, words, section)) continue
			}
			if (section != null && !headed) {
				y += drawHeading(graphics, section.label, box.x, y, box.w, first, alpha)
				headed = true
			}
			first = false
			y += drawRow(graphics, setting, box.x, y, box.w, nested = false, mx, my, pointerInside, dt, alpha)
			y += drawExpanding(graphics, setting, box, y, mx, my, pointerInside, dt, alpha)
		}
		if (first) MenuStyle.drawEmptyState(graphics, box, Icons.SEARCH, "No settings match \"$words\"", "Try another keyword, or clear the search to see every setting.", alpha)
		closeGroup(graphics, y, alpha)
		MenuStyle.drawScrollFades(graphics, shown, scroll, maxScroll, alpha)
		graphics.disableScissor()

		val contentH = y + scroll - box.y
		maxScroll = max(0f, contentH - box.h + 8f)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)
		drawScrollbar(graphics, box, contentH, mx, my, dt, alpha)
	}

	/**
	 * A row the search shows: its label or description holds [words], or its heading does. Only
	 * rows of the list itself; what sits behind a "⋯" opens from its row, as outside a search.
	 */
	private fun matches(setting: ModuleSetting, words: String, section: SectionModuleSetting?): Boolean {
		if (!setting.hasOwnRow || setting is SlotMapModuleSetting) return false
		val parent = setting.parent
		if (parent != null && parent !is SectionModuleSetting) return false
		if (!setting.isVisible() || setting.parent?.isVisible() == false) return false
		return setting.label.contains(words, ignoreCase = true) ||
			descriptionOf(setting).contains(words, ignoreCase = true) ||
			section?.label?.contains(words, ignoreCase = true) == true
	}

	/** Shows the settings matching [text], as typing it into the search would. */
	fun search(text: String) {
		if (!searchable) return
		editQuery {
			query.set(text)
			true
		}
	}

	private fun jump(to: Float) {
		scrollTarget = to
		scroll = to
	}

	/** Applies an edit to the search, moving between the results and the list they came from. */
	private fun editQuery(change: () -> Boolean) {
		val before = searching
		if (!change()) return
		when {
			!before && searching -> {
				// Searching from a manager's window searches its settings.
				if (showingManager) {
					managerScroll = scrollTarget
					showingManager = false
					manager?.ui?.blur()
				} else {
					settingsScroll = scrollTarget
				}
				jump(0f)
			}
			before && !searching -> jump(settingsScroll)
			searching -> jump(0f)
		}
		openDropdown = null
	}

	/**
	 * "Aliases | Settings", or a module's tabs, right-aligned at [right]. Each part is what it
	 * switches to, with its label. Returns its left edge.
	 */
	// Many tabs sit closer, to leave the title room.
	private fun viewSwitchPad(parts: List<Pair<Hit.View, String>>) = if (parts.size > 4) 13f else 15f

	private fun viewSwitchWidth(parts: List<Pair<Hit.View, String>>): Float {
		val pad = viewSwitchPad(parts)
		return parts.sumOf { (UiText.width(it.second, PILL_TEXT, UiText.Face.MEDIUM) + pad * 2f).toDouble() }.toFloat() + PILL_GAP * (parts.size - 1)
	}

	/** How far each view-switch pill has lit for the pointer, and towards being the chosen one, by position. */
	private val pillHover = HashMap<Int, Float>()
	private val pillChosen = HashMap<Int, Float>()

	private fun drawViewSwitch(graphics: GuiGraphicsExtractor, parts: List<Pair<Hit.View, String>>, right: Float, row: Box, mx: Float, my: Float, dt: Float, alpha: Float): Float {
		val size = PILL_TEXT
		val pad = viewSwitchPad(parts)
		val h = 31f
		val radius = 8f
		val y = row.y + (row.h - h) / 2f
		val widths = parts.map { UiText.width(it.second, size, UiText.Face.MEDIUM) + pad * 2f }
		val x = right - viewSwitchWidth(parts)
		var cx = x
		for ((index, entry) in parts.withIndex()) {
			val (target, text) = entry
			val part = Box(cx, y, widths[index], h)
			val chosen = !searching && showingManager == target.manager && (target.manager || target.tab == tabIndex)
			val lift = approach(pillHover[index] ?: 0f, if (part.contains(mx, my)) 1f else 0f, 18f, dt).also { pillHover[index] = it }
			val on = approach(pillChosen[index] ?: 0f, if (chosen) 1f else 0f, 16f, dt).also { pillChosen[index] = it }
			// Flat pills with no edge; the chosen one takes a faint wash of the accent and accent text.
			val restFill = blend(PILL_FILL, PILL_FILL_HOVER, lift)
			val fill = blend(restFill, blend(PILL_FILL, MenuStyle.accent(), 0.12f + 0.04f * lift), on)
			RoundRect.fill(graphics, part.x, part.y, part.right, part.bottom, fade(fill, alpha), radius)
			val color = blend(blend(PILL_TEXT_COLOR, MenuStyle.TEXT, lift), MenuStyle.accent(), on)
			UiText.draw(graphics, text, part.x + (part.w - UiText.width(text, size, UiText.Face.MEDIUM)) / 2f, part.y + (part.h - size) / 2f, size, fade(color, alpha), UiText.Face.MEDIUM)
			hits += Hit.View(part, target.manager, target.tab)
			cx += part.w + PILL_GAP
		}
		return x
	}

	private fun drawManager(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val section = manager ?: return
		// Nothing under an open colour picker counts as hovered.
		val covered = overlayContains(mx, my)
		section.ui.begin(graphics, box, if (covered) -1e6f else mx, if (covered) -1e6f else my, dt, alpha)
		val shown = MenuStyle.clipList(graphics, box)
		val used = section.draw(box.x, box.y + LIST_TOP - scroll, box.w) + LIST_TOP
		MenuStyle.drawScrollFades(graphics, shown, scroll, maxScroll, alpha)
		graphics.disableScissor()
		maxScroll = max(0f, used - box.h + 8f)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)
		drawScrollbar(graphics, box, used, mx, my, dt, alpha)
	}

	/**
	 * Opens the colour picker on [setting], hung from [anchor], for a manager editing a colour that
	 * has no row of its own.
	 */
	fun openColorPicker(setting: ColorModuleSetting, anchor: Box) {
		openColor = setting
		colorAnchor = anchor
		hsv = java.awt.Color.RGBtoHSB((setting.rgb shr 16) and 0xFF, (setting.rgb shr 8) and 0xFF, setting.rgb and 0xFF, null)
		// The hex code is selected, so one paste replaces it.
		startEditing(Edit.Hex(setting), selectAll = true)
	}

	/**
	 * The page is closing: commit typed text and save the manager. [keepExpanded] when the whole
	 * menu closed here (it reopens as it was); leaving the module folds its rows.
	 */
	fun onClose(keepExpanded: Boolean = true) {
		commitEditing()
		manager?.onClose()
		remember(keepExpanded)
	}

	fun keyReleased(event: KeyEvent): Boolean {
		swallowChar = false
		return showingManager && manager?.keyReleased(event) == true
	}

	fun drawOverlays(graphics: GuiGraphicsExtractor, mx: Float, my: Float, alpha: Float) {
		if (showingManager) manager?.drawOverlays()
		openDropdown?.let { drawDropdownList(graphics, it, mx, my, alpha) }
		openColor?.let { drawPicker(graphics, it, mx, my, alpha) }
		drawSuggestions(graphics, mx, my, alpha)
	}

	/** The span the scrollbar runs, top to bottom, when the menu gives one; else the list's own. */
	var trackTop = Float.NaN
	var trackBottom = Float.NaN

	private fun drawScrollbar(graphics: GuiGraphicsExtractor, box: Box, contentH: Float, mx: Float, my: Float, dt: Float, alpha: Float) {
		val top = if (trackTop.isNaN()) box.y else trackTop
		val bottom = if (trackBottom.isNaN()) box.bottom else trackBottom
		scrollbar.draw(graphics, box.right + Scrollbar.GAP, top, bottom - top, contentH, scroll, maxScroll, mx, my, dt, alpha)
	}

	fun beginFrame() {
		hits.clear()
		suggestFor = null
		rowTip = null
		groupTop = Float.NaN
	}

	/** The row under the pointer whose words were cut short, and them in full, for the menu's tooltip. */
	var rowTip: Pair<Any, String>? = null
		private set

	/** A heading closes the group above it: its name, then a hairline to the right edge. */
	private fun drawHeading(graphics: GuiGraphicsExtractor, label: String, x: Float, y: Float, w: Float, first: Boolean, alpha: Float): Float {
		closeGroup(graphics, y, alpha)
		val gap = if (first) 0f else 26f
		val size = 17f
		val top = y + gap
		UiText.draw(graphics, label, x + 2f, top, size, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		val lineX = x + 2f + UiText.width(label, size, UiText.Face.MEDIUM) + 14f
		RoundRect.fill(graphics, lineX, top + size / 2f, x + w, top + size / 2f + 1f, fade(MenuStyle.HEADING_LINE, alpha), 0f)
		return gap + size + 14f
	}

	/** The top of the group of rows being drawn, NaN between groups, and its sides. */
	private var groupTop = Float.NaN
	private var groupX = 0f
	private var groupW = 0f

	/** Puts a row starting at [y] in the open group, opening one if none is. True for a group's first row. */
	private fun joinGroup(x: Float, y: Float, w: Float): Boolean {
		if (!groupTop.isNaN()) return false
		groupTop = y
		groupX = x
		groupW = w
		return true
	}

	/**
	 * Ends the open group at [bottom]: rounds its corners by laying the panel's colour over what the
	 * square rows leave outside them, then draws its edge.
	 */
	private fun closeGroup(graphics: GuiGraphicsExtractor, bottom: Float, alpha: Float) {
		val top = groupTop
		groupTop = Float.NaN
		if (top.isNaN() || bottom <= top) return
		val r = GROUP_RADIUS
		// The band reaches r(√2 - 1) in from the corner at least, enough to cover it.
		val k = r * 0.5f
		RoundRect.outline(graphics, groupX - k, top - k, groupX + groupW + k, bottom + k, fade(MenuStyle.WINDOW_BG, alpha), r + k, k)
		RoundRect.outline(graphics, groupX, top, groupX + groupW, bottom, fade(MenuStyle.GROUP_EDGE, alpha), r, 1f)
	}

	private fun drawChildren(
		graphics: GuiGraphicsExtractor, parent: ModuleSetting, box: Box, top: Float,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float, alwaysOpen: Boolean,
	): Float {
		var y = top
		for (child in parent.more) {
			if (!child.hasOwnRow || !child.isVisible()) continue
			y += drawRow(graphics, child, box.x, y, box.w, nested = !alwaysOpen, mx, my, pointerInside, dt, alpha)
		}
		return y - top
	}

	/** How far each setting's sub-settings are open, 0 to 1, easing towards whether they're expanded. */
	private val openness = HashMap<ModuleSetting, Float>()

	private fun openness(setting: ModuleSetting): Float = openness[setting] ?: if (setting in expanded) 1f else 0f

	/**
	 * [setting]'s sub-settings under its row, opening and closing like an accordion: drawn whole,
	 * shown through a clip that grows and shrinks. Returns the height they take this frame.
	 */
	private fun drawExpanding(
		graphics: GuiGraphicsExtractor, setting: ModuleSetting, box: Box, top: Float,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float,
	): Float {
		val target = if (setting in expanded) 1f else 0f
		var t = approach(openness(setting), target, 16f, dt)
		if (abs(t - target) < 0.004f) t = target
		openness[setting] = t
		if (t <= 0f || !setting.hasVisibleMore()) return 0f
		if (t >= 1f) return drawChildren(graphics, setting, box, top, mx, my, pointerInside, dt, alpha, alwaysOpen = false)
		val full = setting.more.filter { it.hasOwnRow && it.isVisible() }.sumOf { rowHeight(it, box.w, nested = true).toDouble() }.toFloat()
		val shown = full * t
		MenuStyle.clip(graphics, Box(box.x, top, box.w, shown))
		drawChildren(graphics, setting, box, top, mx, my, pointerInside, dt, alpha * t, alwaysOpen = false)
		graphics.disableScissor()
		return shown
	}

	/** A row's height, gap included, as [drawRow] lays it out. */
	private fun rowHeight(setting: ModuleSetting, w: Float, nested: Boolean): Float {
		val lines = descriptionLines(setting, w, nested)
		return when {
			descriptionOf(setting).isEmpty() -> ROW_SHORT
			lines.size > 1 -> ROW_TALL + DESCRIPTION_LINE
			else -> ROW_TALL
		} + ROW_GAP
	}

	private fun descriptionOf(setting: ModuleSetting): String = when (setting) {
		is ToggleModuleSetting -> setting.description
		is SliderModuleSetting -> setting.description
		is RangeModuleSetting -> setting.description
		is ColorModuleSetting -> setting.description
		is TextModuleSetting -> setting.description
		is KeybindModuleSetting -> setting.description
		is DropdownModuleSetting -> setting.description
		is MultiSelectModuleSetting -> setting.description
		is PanelModuleSetting -> setting.description
		else -> ""
	}

	/**
	 * What a notice says of [subject] at [restriction], or null when it's legit. Plain about what it
	 * is, without warning people off it.
	 */
	private fun restrictionText(restriction: Restriction, subject: String, plural: Boolean = false): String? {
		val verb = if (plural) "are" else "is"
		return when (restriction) {
			Restriction.LEGIT -> null
			Restriction.SEMI_LEGIT -> "$subject $verb considered semi-legit. Use at your own risk."
			Restriction.CHEAT -> "$subject $verb considered a cheat. Use at your own risk."
		}
	}

	/**
	 * The heads-up above the page: its title, its words and its level. It covers the module's own
	 * restriction and any settings marked past it, or is null when everything is legit.
	 */
	private val pageNotice: Triple<String, String, Restriction>? by lazy {
		val own = module.restriction.takeIf { it != Restriction.LEGIT }
		val marked = module.settings.mapNotNull { settingNotice(it)?.second }.toSortedSet()
		if (own == null && marked.isEmpty()) return@lazy null
		val top = (marked + listOfNotNull(own)).maxBy { it.ordinal }
		fun kind(level: Restriction) = if (level == Restriction.CHEAT) "cheats" else "semi-legit"
		fun sign(level: Restriction) = if (level == Restriction.CHEAT) "red" else "yellow"
		val text = when {
			own == null && marked.size > 1 -> "Some settings are considered semi-legit, marked with a yellow sign, or cheats, marked with a red one. Use them at your own risk."
			own == null -> "Some settings are considered ${kind(top)}. They're marked with a ${sign(top)} sign. Use them at your own risk."
			marked.isEmpty() -> restrictionText(own, "This module")!!
			else -> "This module is considered ${if (own == Restriction.CHEAT) "a cheat" else "semi-legit"}, and the settings marked with a ${sign(top)} sign are considered ${kind(top)}. Use at your own risk."
		}
		val title = if (own == top) MenuStyle.restrictionTitle(top) else MenuStyle.restrictionTitle(top) + " settings"
		Triple(title, text, top)
	}

	/**
	 * The warning for [setting], and its level: for a toggle past what the module itself is,
	 * or a dropdown with such options. Null for the rest.
	 */
	private fun settingNotice(setting: ModuleSetting): Pair<String, Restriction>? {
		val floor = module.restriction.ordinal
		return when (setting) {
			is ToggleModuleSetting -> setting.restriction.takeIf { it.ordinal > floor }?.let { level ->
				restrictionText(level, "This setting")?.let { it to level }
			}
			is DropdownModuleSetting -> {
				val options = setting.restrictedOptions.filterValues { it.ordinal > floor }
				val level = options.values.maxByOrNull { it.ordinal } ?: return null
				val names = options.keys.joinToString(" and ") { "“$it”" }
				restrictionText(level, names, plural = options.size > 1)?.let { it to level }
			}
			else -> null
		}
	}

	/** One row, label left and control right. Returns its height. */
	private fun drawRow(
		graphics: GuiGraphicsExtractor, setting: ModuleSetting,
		x: Float, y: Float, w: Float, nested: Boolean,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float,
	): Float {
		val description = descriptionOf(setting)
		val lines = descriptionLines(setting, w, nested)
		val h = rowHeight(setting, w, nested) - ROW_GAP
		val notice = settingNotice(setting)
		val firstInGroup = joinGroup(x, y, w)
		if (y + h < area.y || y > area.bottom) return h + ROW_GAP

		val row = Box(x, y, w, h)
		val hovered = pointerInside && row.contains(mx, my)
		val lift = approach(hover[setting] ?: 0f, if (hovered) 1f else 0f, 18f, dt).also { hover[setting] = it }
		val base = if (nested) MenuStyle.ROW_NESTED else MenuStyle.ROW
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(blend(base, MenuStyle.ROW_HOVER, lift), alpha), 0f)
		val lineFrom = x + if (nested) NESTED_PAD else ROW_PAD
		if (!firstInGroup) RoundRect.fill(graphics, lineFrom, y, x + w - ROW_PAD, y + 1f, fade(MenuStyle.ROW_LINE, alpha), 0f)
		val cy = y + h / 2f
		val right = x + w - ROW_PAD
		// Registered before the controls, so a control is hit first.
		hits += Hit.Row(row, setting)
		val controlLeft = drawControl(graphics, setting, right, cy, row, mx, my, pointerInside, dt, alpha)

		var labelRight = controlLeft - 16f
		if (setting !is PanelModuleSetting && setting.hasVisibleMore()) {
			val chevron = Box(controlLeft - 10f - 26f, cy - 13f, 26f, 26f)
			drawChevron(graphics, chevron, openness(setting), pointerInside && chevron.contains(mx, my), alpha)
			hits += Hit.Expand(chevron, setting)
			labelRight = chevron.x - 10f
		}

		val labelX = x + if (nested) NESTED_PAD else ROW_PAD
		val showReset = ClickGui.resetButtons.value && setting.changed
		val room = labelRight - labelX - (if (showReset) RESET_ROOM else 0f) - (if (notice != null) SIGN_ROOM else 0f)
		val label = UiText.fit(setting.label, LABEL, room, UiText.Face.MEDIUM)
		val labelY = if (description.isEmpty()) cy - LABEL / 2f else y + LABEL_TOP
		UiText.draw(graphics, label, labelX, labelY, LABEL, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		lines.forEachIndexed { index, line ->
			UiText.draw(graphics, line, labelX, y + DESCRIPTION_TOP + index * DESCRIPTION_LINE, DESCRIPTION, fade(MenuStyle.MUTED, alpha))
		}
		// Cut short to fit: a rest on the label or description shows it whole.
		val cut = label != setting.label || lines.lastOrNull()?.let { it.endsWith("…") && !description.endsWith("…") } == true
		if (cut && pointerInside && mx >= labelX && mx < labelRight && my >= y && my < y + h) {
			rowTip = setting to if (description.isEmpty()) setting.label else "${setting.label}: $description"
		}
		var afterLabel = labelX + UiText.width(label, LABEL, UiText.Face.MEDIUM)
		// A setting past the module's own level carries a small sign in its colour; resting on it says why.
		notice?.let { (text, level) ->
			val sign = Box(afterLabel + 4f, labelY + LABEL / 2f - 10f, 20f, 20f)
			UiText.drawIconCentered(graphics, Icons.WARNING, sign.x, sign.y, sign.w, 11f, fade(MenuStyle.restrictionColor(level), alpha))
			if (pointerInside && sign.contains(mx, my)) rowTip = (setting to "sign") to text
			afterLabel = sign.right - 4f
		}
		if (showReset) {
			val button = Box(afterLabel + 6f, labelY + LABEL / 2f - 10f, 20f, 20f)
			val over = pointerInside && button.contains(mx, my)
			if (over) RoundRect.fill(graphics, button.x, button.y, button.right, button.bottom, fade(MenuStyle.BUTTON_HOVER, alpha), 5f)
			UiText.drawIconCentered(graphics, Icons.RESET, button.x, button.y, button.w, 10f, fade(if (over) MenuStyle.TEXT else MenuStyle.MUTED, alpha))
			hits += Hit.Reset(button, setting)
		}
		return h + ROW_GAP
	}

	/** How much of a row's right side a setting's control takes, its chevron included. */
	private fun controlWidth(setting: ModuleSetting): Float {
		val control = when (setting) {
			is ToggleModuleSetting -> 40f + if (setting.inlineColor != null) 36f else 0f
			is SliderModuleSetting -> 60f + 16f + 210f + 8f
			is RangeModuleSetting -> 56f + 16f + 170f + 16f + 56f
			is DropdownModuleSetting, is MultiSelectModuleSetting -> 220f
			is KeybindModuleSetting -> 120f
			is ButtonModuleSetting -> 150f
			is ColorModuleSetting -> 26f + 10f + UiText.width(setting.hex, 12f)
			is TextModuleSetting -> 220f + if (setting.button != null) 88f else 0f
			is PanelModuleSetting -> setting.note?.let { UiText.width(it, 13f) } ?: 26f
			is SectionModuleSetting, is TabModuleSetting, is SlotMapModuleSetting -> 0f
		}
		val chevron = if (setting !is PanelModuleSetting && setting.hasVisibleMore()) 36f else 0f
		return control + chevron
	}

	/**
	 * A setting's description as drawn: one line when it fits, else wrapped to two, the second cut
	 * short if even two aren't enough.
	 */
	private fun descriptionLines(setting: ModuleSetting, rowWidth: Float, nested: Boolean): List<String> {
		val description = descriptionOf(setting)
		if (description.isEmpty()) return emptyList()
		val room = rowWidth - ROW_PAD - controlWidth(setting) - 16f - (if (nested) NESTED_PAD else ROW_PAD)
		if (UiText.width(description, DESCRIPTION) <= room) return listOf(description)
		val wrapped = UiText.wrap(description, DESCRIPTION, room)
		if (wrapped.size <= 2) return wrapped
		return listOf(wrapped[0], UiText.fit(wrapped.drop(1).joinToString(" "), DESCRIPTION, room))
	}

	/** Draws [setting]'s control right-aligned at [right], centred on [cy]. Returns its left edge. */
	private fun drawControl(
		graphics: GuiGraphicsExtractor, setting: ModuleSetting, right: Float, cy: Float, row: Box,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float,
	): Float {
		fun hovered(box: Box) = pointerInside && box.contains(mx, my)
		return when (setting) {
			is ToggleModuleSetting -> {
				val switch = Box(right - 40f, cy - 11f, 40f, 22f)
				val t = approach(switchPosition[setting] ?: if (setting.value) 1f else 0f, if (setting.value) 1f else 0f, 20f, dt)
				switchPosition[setting] = t
				MenuStyle.drawSwitch(graphics, switch, t, alpha)
				hits += Hit.Toggle(switch, setting)
				var left = switch.x
				setting.inlineColor?.let { color ->
					val swatch = Box(switch.x - 12f - 24f, cy - 12f, 24f, 24f)
					drawSwatch(graphics, color, swatch, hovered(swatch), alpha)
					hits += Hit.Swatch(swatch, color)
					left = swatch.x
				}
				left
			}
			is SliderModuleSetting -> {
				val valueBox = Box(right - 60f, cy - 15f, 60f, 30f)
				drawValueBox(graphics, valueBox, Edit.Slider(setting), setting.boxValue, hovered(valueBox), alpha)
				val barW = 210f
				val barX = valueBox.x - 16f - barW
				val t = ((setting.value - setting.min) / (setting.max - setting.min)).toFloat().coerceIn(0f, 1f)
				drawTrack(graphics, barX, barW, cy, 0f, t, alpha)
				hits += Hit.Track(Box(barX - 8f, cy - 10f, barW + 16f, 20f), setting, barX, barW)
				barX - 8f
			}
			is RangeModuleSetting -> {
				// Low, track, high: each number beside its own end.
				val barW = 170f
				val upperBox = Box(right - 56f, cy - 15f, 56f, 30f)
				val barX = upperBox.x - 16f - barW
				val lowerBox = Box(barX - 16f - 56f, cy - 15f, 56f, 30f)
				drawValueBox(graphics, lowerBox, Edit.Lower(setting), setting.displayLower, hovered(lowerBox), alpha)
				drawValueBox(graphics, upperBox, Edit.Upper(setting), setting.displayUpper, hovered(upperBox), alpha)
				val span = setting.max - setting.min
				val lower = ((setting.lower - setting.min) / span).toFloat().coerceIn(0f, 1f)
				val upper = ((setting.upper - setting.min) / span).toFloat().coerceIn(0f, 1f)
				drawTrack(graphics, barX, barW, cy, lower, upper, alpha, twoHandles = true)
				hits += Hit.Track(Box(barX - 8f, cy - 10f, barW + 16f, 20f), setting, barX, barW)
				lowerBox.x
			}
			is DropdownModuleSetting, is MultiSelectModuleSetting -> {
				val source = setting as DropdownSource
				val box = Box(right - 220f, cy - 16f, 220f, 32f)
				drawDropdownBox(graphics, box, source, hovered(box), alpha)
				hits += Hit.Dropdown(box, source)
				if (openDropdown === source) dropdownAnchor = box
				box.x
			}
			is KeybindModuleSetting -> {
				val capturing = awaiting === setting
				drawBadge(graphics, if (capturing) "Press a key" else setting.keyName, right, cy, capturing || setting.bound, capturing, mx, my, pointerInside, alpha)
					.also { hits += Hit.Keybind(it, setting) }.x
			}
			is ButtonModuleSetting -> drawButton(graphics, setting, right, cy, 150f, mx, my, pointerInside, alpha)
			is ColorModuleSetting -> {
				val swatch = Box(right - 26f, cy - 13f, 26f, 26f)
				drawSwatch(graphics, setting, swatch, hovered(swatch), alpha)
				hits += Hit.Swatch(swatch, setting)
				val hex = setting.hex
				val hexX = swatch.x - 10f - UiText.width(hex, 12f)
				UiText.draw(graphics, hex, hexX, cy - 6f, 12f, fade(MenuStyle.MUTED, alpha))
				hexX
			}
			is TextModuleSetting -> {
				val fieldRight = setting.button?.let { drawButton(graphics, it, right, cy, 80f, mx, my, pointerInside, alpha) - 8f } ?: right
				val box = Box(fieldRight - 220f, cy - 16f, 220f, 32f)
				val edit = Edit.Text(setting)
				drawTextField(graphics, box, edit, setting.value, setting.hint, hovered(box), alpha)
				if (setting.suggestions != null && editing == edit) {
					suggestFor = setting
					suggestAnchor = box
				}
				hits += Hit.Text(box, edit)
				box.x
			}
			is PanelModuleSetting -> {
				// A note in place of a control: just the words, muted.
				setting.note?.let { note ->
					val noteX = right - UiText.width(note, 13f)
					UiText.draw(graphics, note, noteX, cy - 6.5f, 13f, fade(MenuStyle.MUTED, alpha))
					return noteX
				}
				val chevron = Box(right - 26f, cy - 13f, 26f, 26f)
				drawChevron(graphics, chevron, openness(setting), hovered(row), alpha)
				hits += Hit.Expand(row, setting)
				chevron.x
			}
			is SectionModuleSetting, is TabModuleSetting, is SlotMapModuleSetting -> right
		}
	}

	private fun drawKeybindRow(
		graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float,
	): Float {
		val h = ROW_TALL
		joinGroup(x, y, w)
		val row = Box(x, y, w, h)
		val lift = approach(hover[module] ?: 0f, if (pointerInside && row.contains(mx, my)) 1f else 0f, 18f, dt).also { hover[module] = it }
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(blend(MenuStyle.ROW, MenuStyle.ROW_HOVER, lift), alpha), 0f)
		val cy = y + h / 2f
		val capturing = awaiting === module
		val name = module.keybind.keyName
		val badge = drawBadge(graphics, if (capturing) "Press a key" else name, x + w - ROW_PAD, cy, capturing || name != "None", capturing, mx, my, pointerInside, alpha)
		hits += Hit.Keybind(badge, module)
		UiText.draw(graphics, keybindPlacement.label, x + ROW_PAD, y + LABEL_TOP, LABEL, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		UiText.draw(graphics, keybindPlacement.description, x + ROW_PAD, y + DESCRIPTION_TOP, DESCRIPTION, fade(MenuStyle.MUTED, alpha))
		return h + ROW_GAP
	}

	private fun drawBadge(
		graphics: GuiGraphicsExtractor, text: String, right: Float, cy: Float, lit: Boolean, capturing: Boolean,
		mx: Float, my: Float, pointerInside: Boolean, alpha: Float,
	): Box {
		val size = 12f
		// Key names read in capitals, and an empty bind says so.
		val shown = (if (text == "None") "Not bound" else text).uppercase()
		val w = max(120f, UiText.width(shown, size, UiText.Face.MEDIUM) + 28f)
		val box = Box(right - w, cy - 16f, w, 32f)
		val over = pointerInside && box.contains(mx, my)
		// The accent's edge marks one waiting for a key.
		MenuStyle.drawControlBox(graphics, box, over, capturing, alpha)
		UiText.draw(
			graphics, shown, box.x + (box.w - UiText.width(shown, size, UiText.Face.MEDIUM)) / 2f, box.y + (box.h - size) / 2f, size,
			fade(if (lit) MenuStyle.TEXT else MenuStyle.MUTED, alpha), UiText.Face.MEDIUM,
		)
		return box
	}

	/** The chevron that opens sub-settings: pointing right when shut, turning down as they open ([open], 0 to 1). */
	private fun drawChevron(graphics: GuiGraphicsExtractor, box: Box, open: Float, hovered: Boolean, alpha: Float) {
		if (hovered) RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.CONTROL, alpha), 6f)
		val cx = box.x + box.w / 2f
		val cy = box.y + box.h / 2f
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(cx, cy)
		pose.rotate(open * (Math.PI / 2.0).toFloat())
		pose.translate(-cx, -cy)
		val color = blend(MenuStyle.MUTED, MenuStyle.TEXT, if (hovered) 1f else open)
		UiText.drawIconCentered(graphics, Icons.CHEVRON_RIGHT, box.x, box.y, box.w, 11f, fade(color, alpha))
		pose.popMatrix()
	}

	private fun drawTrack(graphics: GuiGraphicsExtractor, barX: Float, barW: Float, cy: Float, from: Float, to: Float, alpha: Float, twoHandles: Boolean = false) {
		val h = 4f
		RoundRect.fill(graphics, barX, cy - h / 2f, barX + barW, cy + h / 2f, fade(MenuStyle.TRACK, alpha), h / 2f)
		RoundRect.fill(graphics, barX + barW * from, cy - h / 2f, barX + barW * to, cy + h / 2f, fade(MenuStyle.accent(), alpha), h / 2f)
		val r = 7f
		if (twoHandles) {
			val lx = barX + barW * from
			RoundRect.fill(graphics, lx - r, cy - r, lx + r, cy + r, fade(MenuStyle.TEXT, alpha), r)
		}
		val hx = barX + barW * to
		RoundRect.fill(graphics, hx - r, cy - r, hx + r, cy + r, fade(MenuStyle.TEXT, alpha), r)
	}

	private fun drawValueBox(graphics: GuiGraphicsExtractor, box: Box, edit: Edit, value: String, hovered: Boolean, alpha: Float) {
		val focused = editing == edit
		MenuStyle.drawControlBox(graphics, box, hovered, focused, alpha)
		val size = 12f
		if (focused) {
			input.draw(graphics, box, size, MenuStyle.TEXT, true, alpha, frameDt, padding = 6f, centered = true)
		} else {
			UiText.draw(graphics, value, box.x + (box.w - UiText.width(value, size)) / 2f, box.y + (box.h - size) / 2f, size, fade(MenuStyle.TEXT, alpha))
		}
		hits += Hit.Value(box, edit)
	}

	/** A button right-aligned at [right], at least [minWidth] wide. Returns its left edge. */
	private fun drawButton(
		graphics: GuiGraphicsExtractor, setting: ButtonModuleSetting, right: Float, cy: Float, minWidth: Float,
		mx: Float, my: Float, pointerInside: Boolean, alpha: Float,
	): Float {
		val size = 13f
		val w = max(minWidth, UiText.width(setting.label, size, UiText.Face.MEDIUM) + 32f)
		val box = Box(right - w, cy - 15f, w, 30f)
		val over = pointerInside && box.contains(mx, my)
		MenuStyle.drawControlBox(graphics, box, over, false, alpha)
		UiText.draw(
			graphics, setting.label, box.x + (box.w - UiText.width(setting.label, size, UiText.Face.MEDIUM)) / 2f,
			box.y + (box.h - size) / 2f, size, fade(if (over) MenuStyle.TEXT else MenuStyle.TAB_HOVER, alpha), UiText.Face.MEDIUM,
		)
		hits += Hit.Button(box, setting)
		return box.x
	}

	private fun drawTextField(graphics: GuiGraphicsExtractor, box: Box, edit: Edit, value: String, hint: String, hovered: Boolean, alpha: Float) {
		val focused = editing == edit
		MenuStyle.drawControlBox(graphics, box, hovered, focused, alpha)
		val size = 13f
		if (focused) {
			input.draw(graphics, box, size, MenuStyle.TEXT, true, alpha, frameDt)
			return
		}
		val room = box.w - 24f
		val ty = box.y + (box.h - size) / 2f
		if (value.isEmpty()) {
			UiText.draw(graphics, hint, box.x + 12f, ty, size, fade(MenuStyle.PLACEHOLDER, alpha))
		} else {
			UiText.draw(graphics, UiText.fit(value, size, room), box.x + 12f, ty, size, fade(MenuStyle.TEXT, alpha))
		}
	}

	private fun drawDropdownBox(graphics: GuiGraphicsExtractor, box: Box, source: DropdownSource, hovered: Boolean, alpha: Float) {
		val open = openDropdown === source
		MenuStyle.drawControlBox(graphics, box, hovered, open, alpha)
		val size = 13f
		var textX = box.x + 12f
		// A multi-select leads with how many of its options are ticked.
		if (source is MultiSelectModuleSetting) {
			val count = "${source.ticked.size} / ${source.options.size}"
			val countSize = 11f
			val pill = Box(box.x + 7f, box.y + (box.h - 20f) / 2f, UiText.width(count, countSize, UiText.Face.MEDIUM) + 14f, 20f)
			RoundRect.fill(graphics, pill.x, pill.y, pill.right, pill.bottom, fade(blend(MenuStyle.CONTROL, MenuStyle.accent(), 0.25f), alpha), 4f)
			UiText.draw(graphics, count, pill.x + 7f, pill.y + (pill.h - countSize) / 2f, countSize, fade(blend(MenuStyle.accent(), 0xFFFFFFFF.toInt(), 0.45f), alpha), UiText.Face.MEDIUM)
			textX = pill.right + 9f
		}
		val text = UiText.fit(source.selected, size, box.right - 30f - textX)
		UiText.draw(graphics, text, textX, box.y + (box.h - size) / 2f, size, fade(MenuStyle.TEXT, alpha))
		UiText.drawIconCentered(graphics, Icons.CHEVRON_DOWN, box.right - 28f, box.y, 28f, 8f, fade(if (open) MenuStyle.accent() else MenuStyle.MUTED, alpha))
	}

	private fun drawSwatch(graphics: GuiGraphicsExtractor, setting: ColorModuleSetting, box: Box, hovered: Boolean, alpha: Float) {
		val color = (if (setting.supportsAlpha) setting.alpha shl 24 else 0xFF000000.toInt()) or setting.rgb
		if (setting.supportsAlpha && setting.alpha < 0xFF) drawChecker(graphics, box, 6f, alpha)
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(color, alpha), 6f)
		val edge = if (hovered || openColor === setting) 0x80FFFFFF.toInt() else 0x30FFFFFF
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(edge, alpha), 6f, 1f)
	}

	private fun drawChecker(graphics: GuiGraphicsExtractor, box: Box, radius: Float, alpha: Float) =
		RoundRect.checker(graphics, box.x, box.y, box.right, box.bottom, radius, 5f, alpha)

	private var suggestFor: TextModuleSetting? = null
	private var suggestAnchor = Box.NONE
	private var suggestBox = Box.NONE
	private val suggestRows = ArrayList<Pair<String, Box>>()
	private var suggestIndex = 0

	/** The last completions, kept until the text changes (the sound list is long). */
	private var suggestedFor = ""
	private var suggested: List<String> = emptyList()

	private fun currentSuggestions(): List<String> {
		val setting = suggestFor ?: return emptyList()
		val provider = setting.suggestions ?: return emptyList()
		val text = input.text
		if (text != suggestedFor) {
			suggestedFor = text
			suggested = provider(text).filter { !it.equals(text, ignoreCase = true) }
			suggestIndex = 0
		}
		return suggested
	}

	private fun drawSuggestions(graphics: GuiGraphicsExtractor, mx: Float, my: Float, alpha: Float) {
		suggestRows.clear()
		suggestBox = Box.NONE
		val options = currentSuggestions()
		if (options.isEmpty()) return
		val anchor = suggestAnchor
		val rowH = 26f
		val pad = 4f
		val h = options.size * rowH + pad * 2f
		val below = anchor.bottom + 4f
		val y = if (below + h > area.bottom + 40f) anchor.y - 4f - h else below
		val box = Box(anchor.x, y, anchor.w, h)
		suggestBox = box
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.POPUP, alpha), 8f)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.FIELD_EDGE, alpha), 8f, 1f)
		val size = 12f
		for ((index, option) in options.withIndex()) {
			val row = Box(box.x + pad, box.y + pad + index * rowH, box.w - pad * 2f, rowH)
			suggestRows += option to row
			val lit = row.contains(mx, my) || index == suggestIndex
			if (lit) RoundRect.fill(graphics, row.x, row.y, row.right, row.bottom, fade(MenuStyle.POPUP_HOVER, alpha), 6f)
			UiText.draw(graphics, UiText.fit(option, size, row.w - 16f), row.x + 8f, row.y + (rowH - size) / 2f, size, fade(if (lit) MenuStyle.TEXT else MenuStyle.TAB_HOVER, alpha))
		}
	}

	private fun acceptSuggestion(option: String) {
		val edit = editing ?: return
		input.set(option)
		liveUpdate(edit)
	}

	/**
	 * The open dropdown's list, under its box (or over it, near the bottom). It eases in, shows at
	 * most [DROPDOWN_ROWS] options and scrolls past that. A multi-select ticks boxes; a plain one
	 * tints and checks the chosen option. Options past the module's own level carry its sign.
	 */
	private fun drawDropdownList(graphics: GuiGraphicsExtractor, source: DropdownSource, mx: Float, my: Float, alpha: Float) {
		dropdownRows.clear()
		val anchor = dropdownAnchor
		val rowH = 30f
		val pad = 5f
		val shown = source.shownOptions
		val listH = minOf(shown.size, DROPDOWN_ROWS) * rowH
		val h = listH + pad * 2f
		val below = anchor.bottom + 6f
		val above = anchor.y - 6f - h
		val upward = below + h > area.bottom + 40f && above > area.y - 60f
		dropdownOpening = minOf(1f, dropdownOpening + frameDt / DROPDOWN_SECONDS)
		val eased = MenuStyle.easeOut(dropdownOpening)
		val shownAlpha = alpha * eased
		val slide = (1f - eased) * if (upward) 6f else -6f
		val box = Box(anchor.x, (if (upward) above else below) + slide, anchor.w, h)
		dropdownBox = box
		RoundRect.shadow(graphics, box.x, box.y, box.right, box.bottom, fade(0x70000000, shownAlpha), 8f, 16f)
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.POPUP, shownAlpha), 8f)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.POPUP_EDGE, shownAlpha), 8f, 1f)

		val view = Box(box.x + pad, box.y + pad, box.w - pad * 2f, listH)
		dropdownView = view
		dropdownMax = maxOf(0f, shown.size * rowH - listH)
		dropdownScroll = dropdownScroll.coerceIn(0f, dropdownMax)
		val floor = module.restriction.ordinal
		val restricted = (source as? DropdownModuleSetting)?.restrictedOptions.orEmpty()
		val size = 13f
		MenuStyle.clip(graphics, view)
		for ((place, index) in shown.withIndex()) {
			val row = Box(view.x, view.y + place * rowH - dropdownScroll, view.w, rowH)
			if (row.bottom < view.y || row.y > view.bottom) continue
			dropdownRows += index to row
			val option = source.options[index]
			val over = view.contains(mx, my) && row.contains(mx, my)
			val checked = source.isChecked(index)
			val chosenRow = checked && !source.multiple
			when {
				chosenRow -> RoundRect.fill(graphics, row.x, row.y, row.right, row.bottom, fade(blend(MenuStyle.POPUP, MenuStyle.accent(), if (over) 0.2f else 0.14f), shownAlpha), 6f)
				over -> RoundRect.fill(graphics, row.x, row.y, row.right, row.bottom, fade(MenuStyle.POPUP_HOVER, shownAlpha), 6f)
			}
			var textX = row.x + 10f
			if (source.multiple) {
				val tick = Box(row.x + 9f, row.y + (rowH - 16f) / 2f, 16f, 16f)
				// The box fades between empty and ticked rather than snapping.
				val key = source to index
				val target = if (checked) 1f else 0f
				val ticked = approach(tickness[key] ?: target, target, 20f, frameDt).also { tickness[key] = it }
				if (ticked < 0.99f) {
					RoundRect.outline(graphics, tick.x, tick.y, tick.right, tick.bottom, fade(if (over) MenuStyle.MUTED else MenuStyle.FIELD_EDGE_HOVER, shownAlpha * (1f - ticked)), 4f, 1.2f)
				}
				if (ticked > 0.01f) {
					RoundRect.fill(graphics, tick.x, tick.y, tick.right, tick.bottom, fade(MenuStyle.accent(), shownAlpha * ticked), 4f)
					UiText.drawIconCentered(graphics, Icons.CHECK, tick.x, tick.y, tick.w, 9f, fade(0xFFFFFFFF.toInt(), shownAlpha * ticked))
				}
				textX = tick.right + 10f
			}
			val level = restricted[option]?.takeIf { it.ordinal > floor }
			val room = row.right - textX - (if (chosenRow) 30f else 10f) - (if (level != null) 20f else 0f)
			val text = UiText.fit(option, size, room)
			val color = when {
				checked || over -> MenuStyle.TEXT
				else -> MenuStyle.TAB_HOVER
			}
			UiText.draw(graphics, text, textX, row.y + (rowH - size) / 2f, size, fade(color, shownAlpha))
			if (level != null) {
				UiText.drawIconCentered(graphics, Icons.WARNING, textX + UiText.width(text, size) + 2f, row.y + (rowH - 20f) / 2f, 20f, 10f, fade(MenuStyle.restrictionColor(level), shownAlpha))
			}
			if (chosenRow) UiText.drawIconCentered(graphics, Icons.CHECK, row.right - 28f, row.y, rowH, 10f, fade(MenuStyle.accent(), shownAlpha))
		}
		graphics.disableScissor()

		// A thin bar inside the right edge when there are more options than fit.
		if (dropdownMax > 0f) {
			val trackH = view.h - 8f
			val thumbH = maxOf(24f, trackH * listH / (shown.size * rowH))
			val thumbY = view.y + 4f + (trackH - thumbH) * (dropdownScroll / dropdownMax)
			val barX = box.right - 5f
			RoundRect.fill(graphics, barX, thumbY, barX + 3f, thumbY + thumbH, fade(MenuStyle.SCROLL_THUMB, shownAlpha), 1.5f)
		}
	}

	/** How ticked each multi-select option's box looks, 0 to 1, so a tick fades in and out. */
	private val tickness = HashMap<Pair<DropdownSource, Int>, Float>()

	/** How far the open dropdown has eased in, and its list's scroll. */
	private var dropdownOpening = 0f
	private var dropdownScroll = 0f
	private var dropdownMax = 0f
	private var dropdownView = Box.NONE

	/** Opens [source]'s list under [anchor], scrolled so its chosen option shows. */
	private fun openDropdownList(source: DropdownSource, anchor: Box) {
		openDropdown = source
		dropdownAnchor = anchor
		dropdownOpening = 0f
		val place = source.shownOptions.indexOfFirst { source.isChecked(it) && !source.multiple }
		dropdownScroll = if (place < 0) 0f else maxOf(0f, (place - DROPDOWN_ROWS / 2) * 30f)
	}

	private fun drawPicker(graphics: GuiGraphicsExtractor, setting: ColorModuleSetting, mx: Float, my: Float, alpha: Float) {
		val pad = 12f
		val w = 220f
		val inner = w - pad * 2f
		val squareH = 130f
		val barH = 12f
		val gap = 10f
		val hexH = 28f
		val h = pad + squareH + gap + barH + (if (setting.supportsAlpha) gap + barH else 0f) + gap + hexH + pad
		val anchor = colorAnchor
		val x = (anchor.right - w).coerceAtLeast(area.x)
		val below = anchor.bottom + 6f
		val y = if (below + h > area.bottom + 40f) anchor.y - 6f - h else below
		val box = Box(x, y, w, h)
		pickerBox = box
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.POPUP, alpha), 10f)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.FIELD_EDGE, alpha), 10f, 1f)

		var cy = y + pad
		// Saturation across, brightness down.
		val square = Box(x + pad, cy, inner, squareH)
		pickerSquare = square
		val hue = java.awt.Color.HSBtoRGB(hsv[0], 1f, 1f) or 0xFF000000.toInt()
		val white = 0xFFFFFFFF.toInt()
		RoundRect.fillCorners(graphics, square.x, square.y, square.right, square.bottom, fade(white, alpha), fade(hue, alpha), fade(hue, alpha), fade(white, alpha), 6f)
		RoundRect.fillCorners(graphics, square.x, square.y, square.right, square.bottom, 0x00000000, 0x00000000, fade(0xFF000000.toInt(), alpha), fade(0xFF000000.toInt(), alpha), 6f)
		val ringX = square.x + hsv[1] * square.w
		val ringY = square.y + (1f - hsv[2]) * square.h
		RoundRect.outline(graphics, ringX - 6f, ringY - 6f, ringX + 6f, ringY + 6f, fade(white, alpha), 6f, 2f)
		cy += squareH + gap

		val hueBar = Box(x + pad, cy, inner, barH)
		pickerHue = hueBar
		val stops = intArrayOf(0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000)
		val segment = inner / (stops.size - 1)
		for (index in 0 until stops.size - 1) {
			val from = fade(stops[index] or 0xFF000000.toInt(), alpha)
			val to = fade(stops[index + 1] or 0xFF000000.toInt(), alpha)
			val sx = hueBar.x + segment * index
			RoundRect.fillCorners(graphics, sx, hueBar.y, sx + segment + 0.5f, hueBar.bottom, from, to, to, from, 0f)
		}
		drawBarHandle(graphics, hueBar.x + hsv[0] * hueBar.w, hueBar, hue, alpha)
		cy += barH

		if (setting.supportsAlpha) {
			cy += gap
			val alphaBar = Box(x + pad, cy, inner, barH)
			pickerAlpha = alphaBar
			drawChecker(graphics, alphaBar, 0f, alpha)
			val solid = setting.rgb or 0xFF000000.toInt()
			RoundRect.fillCorners(graphics, alphaBar.x, alphaBar.y, alphaBar.right, alphaBar.bottom, fade(setting.rgb, alpha), fade(solid, alpha), fade(solid, alpha), fade(setting.rgb, alpha), 0f)
			drawBarHandle(graphics, alphaBar.x + setting.alpha / 255f * alphaBar.w, alphaBar, (setting.alpha shl 24) or setting.rgb, alpha)
			cy += barH
		} else {
			pickerAlpha = Box.NONE
		}
		cy += gap

		val hexBox = Box(x + pad, cy, inner, hexH)
		pickerHex = hexBox
		val edit = Edit.Hex(setting)
		drawTextField(graphics, hexBox, edit, setting.hex, "", hexBox.contains(mx, my), alpha)
	}

	private fun drawBarHandle(graphics: GuiGraphicsExtractor, hx: Float, bar: Box, color: Int, alpha: Float) {
		val half = 5f
		RoundRect.fill(graphics, hx - half, bar.y - 3f, hx + half, bar.bottom + 3f, fade(0xFFFFFFFF.toInt(), alpha), half)
		RoundRect.fill(graphics, hx - half + 2f, bar.y - 1f, hx + half - 2f, bar.bottom + 1f, fade(color or 0xFF000000.toInt(), alpha), half - 2f)
	}

	private fun overlayContains(mx: Float, my: Float): Boolean =
		(openDropdown != null && dropdownBox.contains(mx, my)) || (openColor != null && pickerBox.contains(mx, my)) ||
			suggestBox.contains(mx, my)

	fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean = false): Boolean {
		// A bind waiting for its key takes any mouse button but left, which cancels.
		awaiting?.let { target ->
			if (button != 0) {
				when (target) {
					is KeybindModuleSetting -> target.keyCode = KeybindModuleSetting.mouse(button)
					is Module -> target.keybind.setKey(InputConstants.Type.MOUSE.getOrCreate(button))
				}
			}
			awaiting = null
			return true
		}
		// Any press but one on the search leaves it; that one focuses it again below.
		if (button == 0) queryFocused = false
		// Managers hear every button (the crosshair canvas erases with right).
		if (showingManager && button != 0 && openColor == null) return manager?.mouseClicked(mx, my, button, doubleClick) == true
		// A right click anywhere on a row opens what it holds.
		if (button == 1 && openDropdown == null && openColor == null && area.contains(mx, my)) {
			val row = hits.asReversed().firstOrNull { it is Hit.Row && it.box.contains(mx, my) } as? Hit.Row ?: return false
			if (!row.setting.hasVisibleMore()) return false
			commitEditing()
			if (!expanded.add(row.setting)) expanded.remove(row.setting)
			return true
		}
		if (button != 0) return false

		// Floating parts first; they sit over the rows.
		if (suggestBox.contains(mx, my)) {
			suggestRows.firstOrNull { it.second.contains(mx, my) }?.let { acceptSuggestion(it.first) }
			return true
		}
		openDropdown?.let { source ->
			for ((index, row) in dropdownRows) {
				if (row.contains(mx, my) && dropdownView.contains(mx, my)) {
					source.pick(index)
					if (!source.multiple) openDropdown = null
					return true
				}
			}
			if (dropdownBox.contains(mx, my)) return true
			openDropdown = null
			if (dropdownAnchor.contains(mx, my)) return true
		}
		openColor?.let { color ->
			if (pickerBox.contains(mx, my)) {
				when {
					pickerSquare.contains(mx, my) -> pickerDrag = 1
					pickerHue.contains(mx, my) -> pickerDrag = 2
					pickerAlpha.contains(mx, my) -> pickerDrag = 3
					pickerHex.contains(mx, my) -> focusField(Edit.Hex(color), pickerHex, mx, selectAll = true, doubleClick)
				}
				applyPicker(mx, my)
				return true
			}
			commitEditing()
			openColor = null
			if (colorAnchor.contains(mx, my)) return true
		}

		if (scrollbar.press(mx, my, maxScroll) { scroll = it; scrollTarget = it }) return true

		val titleHit = hits.lastOrNull { (it is Hit.Star || it is Hit.Search || it is Hit.View) && it.box.contains(mx, my) }
		if (showingManager && titleHit == null) {
			commitEditing()
			return manager?.mouseClicked(mx, my, button, doubleClick) == true || area.contains(mx, my)
		}
		if (!area.contains(mx, my) && titleHit == null) {
			commitEditing()
			return false
		}

		val hit = hits.asReversed().firstOrNull { it.box.contains(mx, my) }
		// Clicking anywhere but the active field commits what was typed.
		val target = (hit as? Hit.Value)?.target ?: (hit as? Hit.Text)?.target
		if (target == null || target != editing) commitEditing()

		when (hit) {
			null -> Unit
			is Hit.Toggle -> hit.setting.value = !hit.setting.value
			is Hit.Search -> {
				queryFocused = true
				if (doubleClick) query.selectAll() else query.pointAt(mx)
				selectingQuery = true
			}
			is Hit.Star -> MenuPrefs.toggleFavorite(module.id)
			// Picking a tab while searching ends the search on that tab.
			is Hit.View -> if (searching) {
				val sameList = hit.manager == showingManager && (hit.manager || hit.tab == tabIndex)
				query.clear()
				showingManager = hit.manager
				if (!hit.manager) tabIndex = hit.tab
				jump(if (showingManager) managerScroll else if (sameList) settingsScroll else 0f)
				manager?.ui?.blur()
			} else if (showingManager != hit.manager) {
				if (showingManager) managerScroll = scrollTarget else settingsScroll = scrollTarget
				showingManager = hit.manager
				if (!hit.manager) tabIndex = hit.tab
				scrollTarget = if (showingManager) managerScroll else settingsScroll
				scroll = scrollTarget
				openDropdown = null
				manager?.ui?.blur()
			} else if (!hit.manager && hit.tab != tabIndex) {
				// Each tab is its own list, read from the top.
				tabIndex = hit.tab
				scrollTarget = 0f
				scroll = 0f
				openDropdown = null
				openColor = null
			}
			is Hit.Expand -> if (!expanded.add(hit.setting)) expanded.remove(hit.setting)
			is Hit.Reset -> hit.setting.resetToDefault()
			// The rest of a toggle's row flips it.
			is Hit.Row -> (hit.setting as? ToggleModuleSetting)?.let { it.value = !it.value }
			is Hit.Track -> {
				dragging = hit
				if (hit.setting is RangeModuleSetting) {
					val value = valueAt(hit, mx)
					dragLower = abs(value - hit.setting.lower) <= abs(value - hit.setting.upper)
				}
				drag(mx)
			}
			// Clicking a number's box selects it, so typing replaces it.
			is Hit.Value -> focusField(hit.target, hit.box, mx, selectAll = true, doubleClick)
			is Hit.Text -> focusField(hit.target, hit.box, mx, selectAll = false, doubleClick)
			is Hit.Dropdown -> openDropdownList(hit.source, hit.box)
			is Hit.Keybind -> awaiting = hit.target
			is Hit.Button -> {
				val action = hit.setting.action
				Minecraft.getInstance().execute { action() }
			}
			is Hit.Swatch -> {
				openColor = hit.setting
				colorAnchor = hit.box
				hsv = java.awt.Color.RGBtoHSB((hit.setting.rgb shr 16) and 0xFF, (hit.setting.rgb shr 8) and 0xFF, hit.setting.rgb and 0xFF, null)
				// The hex code is selected, so one paste replaces it.
				startEditing(Edit.Hex(hit.setting), selectAll = true)
			}
		}
		return true
	}

	/**
	 * A press in a field puts the caret there; dragging selects. Clicking a number's box without
	 * dragging selects it all on release; a double click selects everything.
	 */
	private fun focusField(target: Edit, box: Box, mx: Float, selectAll: Boolean, doubleClick: Boolean) {
		val fresh = editing != target
		if (fresh) {
			startEditing(target, selectAll = false)
			if (target is Edit.Text || target is Edit.Hex) input.place(box, 13f) else input.place(box, 12f, padding = 6f, centered = true)
		}
		if (doubleClick) {
			input.selectAll()
			return
		}
		input.pointAt(mx)
		selecting = true
		pendingSelectAll = fresh && selectAll
	}

	fun mouseDragged(mx: Float, my: Float): Boolean {
		if (selectingQuery) {
			query.pointAt(mx, extend = true)
			return true
		}
		if (pickerDrag != 0) {
			applyPicker(mx, my)
			return true
		}
		if (showingManager && manager?.mouseDragged(mx, my) == true) return true
		if (dragging != null) {
			drag(mx)
			return true
		}
		if (selecting) {
			pendingSelectAll = false
			input.pointAt(mx, extend = true)
			return true
		}
		return scrollbar.drag(my, maxScroll) { scroll = it; scrollTarget = it }
	}

	fun mouseReleased(): Boolean {
		val managerHad = showingManager && manager?.mouseReleased() == true
		if (pendingSelectAll) input.selectAll()
		pendingSelectAll = false
		val was = dragging != null || pickerDrag != 0 || selecting || selectingQuery
		dragging = null
		pickerDrag = 0
		selecting = false
		selectingQuery = false
		return scrollbar.release() || was || managerHad
	}

	fun mouseScrolled(amount: Float) {
		// An open list takes the wheel.
		if (openDropdown != null) {
			dropdownScroll = (dropdownScroll - amount * 30f).coerceIn(0f, dropdownMax)
			return
		}
		scrollTarget = (scrollTarget - amount * 70f).coerceIn(0f, maxScroll)
	}

	fun keyPressed(event: KeyEvent): Boolean {
		awaiting?.let { target ->
			val clear = event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE
			when (target) {
				is KeybindModuleSetting -> target.keyCode = if (clear) KeybindModuleSetting.UNBOUND else event.key()
				is Module -> target.keybind.setKey(if (clear) null else runCatching { InputConstants.getKey(event) }.getOrNull())
			}
			awaiting = null
			swallowChar = true
			return true
		}
		val control = (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0
		if (searchable && control && event.key() == GLFW.GLFW_KEY_F) {
			commitEditing()
			queryFocused = true
			query.selectAll()
			return true
		}
		if (queryFocused) {
			when (event.key()) {
				GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> queryFocused = false
				else -> editQuery { query.keyPressed(event) }
			}
			return true
		}
		val edit = editing
		if (edit != null) {
			when (event.key()) {
				GLFW.GLFW_KEY_ESCAPE -> {
					stopEditing()
					// Escaping the hex code closes the picker too.
					if (edit is Edit.Hex) openColor = null
				}
				// With completions showing, Tab takes the highlighted one and the arrows move it.
				GLFW.GLFW_KEY_TAB -> currentSuggestions().getOrNull(suggestIndex)?.let(::acceptSuggestion)
				GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
					val count = currentSuggestions().size
					if (count > 0) suggestIndex = (suggestIndex + if (event.key() == GLFW.GLFW_KEY_DOWN) 1 else count - 1) % count
				}
				GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					val button = (editing as? Edit.Text)?.setting?.button
					commitEditing()
					button?.let { Minecraft.getInstance().execute { it.action() } }
				}
				else -> if (input.keyPressed(event)) liveUpdate(edit)
			}
			return true
		}
		if (showingManager && manager?.keyPressed(event) == true) return true
		// An open dropdown steps through its options with the arrows, so each can be tried in turn.
		openDropdown?.let { source ->
			val shown = source.shownOptions
			val count = shown.size
			if (!source.multiple && count > 0) {
				when (event.key()) {
					GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
						val step = if (event.key() == GLFW.GLFW_KEY_DOWN) 1 else count - 1
						val place = shown.indexOf(source.selectedIndex).coerceAtLeast(0)
						source.pick(shown[(place + step) % count])
						return true
					}
					GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
						openDropdown = null
						return true
					}
				}
			}
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && (openDropdown != null || openColor != null)) {
			openDropdown = null
			openColor = null
			return true
		}
		// Escape clears a search before it closes the menu.
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && searching) {
			editQuery { query.clear(); true }
			return true
		}
		return false
	}

	fun charTyped(codePoint: Int): Boolean {
		if (swallowChar) {
			swallowChar = false
			return true
		}
		if (queryFocused) {
			editQuery { query.charTyped(codePoint) }
			return true
		}
		val edit = editing
		if (edit == null) {
			if (showingManager) return manager?.charTyped(codePoint) == true
			// Typing with nothing focused searches, as on the module grid.
			if (!searchable || codePoint <= ' '.code) return false
			queryFocused = true
			editQuery { query.charTyped(codePoint) }
			return true
		}
		if (input.charTyped(codePoint)) liveUpdate(edit)
		return true
	}

	private fun startEditing(target: Edit, selectAll: Boolean) {
		commitEditing()
		editing = target
		input = when (target) {
			is Edit.Text -> TextField(target.setting.maxLength)
			is Edit.Hex -> TextField(if (target.setting.supportsAlpha) 8 else 6) { Character.digit(it, 16) >= 0 }
			else -> TextField(16) { Character.isDigit(it) || it == '.'.code || it == ','.code || it == '-'.code }
		}
		input.set(rawValue(target), selectAll)
	}

	private fun stopEditing() {
		editing = null
		selecting = false
	}

	/** Text applies as typed; a hex code once complete. */
	private fun liveUpdate(edit: Edit) {
		when (edit) {
			is Edit.Text -> edit.setting.value = input.text
			is Edit.Hex -> if (edit.setting.setHex(input.text)) {
				hsv = java.awt.Color.RGBtoHSB((edit.setting.rgb shr 16) and 0xFF, (edit.setting.rgb shr 8) and 0xFF, edit.setting.rgb and 0xFF, null)
			}
			else -> Unit
		}
	}

	private fun commitEditing() {
		val edit = editing ?: return
		val number = input.text.trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)
		when (edit) {
			is Edit.Slider -> number?.let { edit.setting.value = snap(it, edit.setting.min, edit.setting.max, edit.setting.step) }
			is Edit.Lower -> number?.let { edit.setting.lower = snap(it, edit.setting.min, edit.setting.upper, edit.setting.step) }
			is Edit.Upper -> number?.let { edit.setting.upper = snap(it, edit.setting.lower, edit.setting.max, edit.setting.step) }
			is Edit.Text, is Edit.Hex -> liveUpdate(edit)
		}
		stopEditing()
	}

	private fun rawValue(edit: Edit): String = when (edit) {
		is Edit.Slider -> edit.setting.displayValue
		is Edit.Lower -> edit.setting.displayLower
		is Edit.Upper -> edit.setting.displayUpper
		is Edit.Text -> edit.setting.value
		is Edit.Hex -> edit.setting.hexDigits
	}

	private fun valueAt(hit: Hit.Track, mx: Float): Double {
		val ratio = ((mx - hit.barX) / hit.barW).coerceIn(0f, 1f).toDouble()
		return when (val setting = hit.setting) {
			is SliderModuleSetting -> setting.min + (setting.max - setting.min) * ratio
			is RangeModuleSetting -> setting.min + (setting.max - setting.min) * ratio
			else -> 0.0
		}
	}

	private fun drag(mx: Float) {
		val hit = dragging ?: return
		val raw = valueAt(hit, mx)
		when (val setting = hit.setting) {
			is SliderModuleSetting -> setting.value = snap(raw, setting.min, setting.max, setting.step)
			is RangeModuleSetting -> if (dragLower) {
				setting.lower = snap(raw, setting.min, setting.upper, setting.step)
			} else {
				setting.upper = snap(raw, setting.lower, setting.max, setting.step)
			}
			else -> Unit
		}
	}

	private fun applyPicker(mx: Float, my: Float) {
		val setting = openColor ?: return
		when (pickerDrag) {
			1 -> {
				hsv[1] = ((mx - pickerSquare.x) / pickerSquare.w).coerceIn(0f, 1f)
				hsv[2] = 1f - ((my - pickerSquare.y) / pickerSquare.h).coerceIn(0f, 1f)
			}
			2 -> hsv[0] = ((mx - pickerHue.x) / pickerHue.w).coerceIn(0f, 0.9999f)
			3 -> setting.alpha = (((mx - pickerAlpha.x) / pickerAlpha.w).coerceIn(0f, 1f) * 255f).toInt()
			else -> return
		}
		if (pickerDrag != 3) setting.rgb = java.awt.Color.HSBtoRGB(hsv[0], hsv[1], hsv[2]) and 0xFFFFFF
		// The hex field follows the drag, since it's committed when the picker closes and a stale
		// code would undo the drag.
		val hex = Edit.Hex(setting)
		if (editing == hex) input.set(rawValue(hex), selectAll = true)
	}

	private fun snap(value: Double, min: Double, max: Double, step: Double): Double {
		val clamped = value.coerceIn(min, max)
		if (step <= 0.0) return clamped
		return (min + round((clamped - min) / step) * step).coerceIn(min, max)
	}

	private companion object {
		val remembered = HashMap<String, Place>()

		const val ROW_TALL = 64f
		const val ROW_SHORT = 54f
		const val PILL_GAP = 7f
		const val PILL_TEXT = 15f
		const val PILL_FILL = 0xFF222226.toInt()
		const val PILL_FILL_HOVER = 0xFF2D2D32.toInt()
		const val PILL_TEXT_COLOR = 0xFFA9ABB8.toInt()

		/** A row's inner margin either side, and a nested row's indent. */
		const val ROW_PAD = 20f
		const val NESTED_PAD = 36f

		/** Where a label and its description start below a row's top. */
		const val LABEL_TOP = 15f
		const val DESCRIPTION_TOP = 36f
		const val ROW_GAP = 0f

		/** An open dropdown shows this many options before it scrolls, and eases in over this long. */
		const val DROPDOWN_ROWS = 8
		const val DROPDOWN_SECONDS = 0.12f
		const val GROUP_RADIUS = 8f

		/** Room above a list, so the clip (snapped to whole pixels) can't take a group's top edge. */
		const val LIST_TOP = 2f

		/** The warning sign after a restricted setting's label. */
		const val SIGN_ROOM = 20f
		const val LABEL = 14f
		const val DESCRIPTION = 12f

		/** The reset button and its gap, kept free after a label. */
		const val RESET_ROOM = 26f

		/** The step to a description's second line. */
		const val DESCRIPTION_LINE = 15f
	}
}
