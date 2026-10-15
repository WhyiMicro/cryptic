package imicro.cryptic.gui.menu

import com.mojang.blaze3d.platform.InputConstants
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
import imicro.cryptic.gui.TextModuleSetting
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
 * One module's settings, shown in place of the card grid.
 *
 * A first pass at the layout: a list of full-width rows, each with its label
 * and description on the left and its control on the right, grouped under the
 * module's own headings. A row with settings of its own (the old "⋯" panels)
 * gets a chevron that opens them underneath it, indented. Dropdown lists and
 * the colour picker float over the list and are drawn last.
 */
class ModulePage(val module: Module) {
	/** What the module has besides its settings, if anything; its page opens on it. */
	val manager: ManagerSection? = ManagerSection.forModule(module)?.also { it.page = this }

	/** Whether the manager is showing rather than the settings. */
	var showingManager = manager != null
		private set

	private var managerScroll = 0f
	private var settingsScroll = 0f
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
		class ModuleSwitch(box: Box) : Hit(box)
		class Star(box: Box) : Hit(box)
		class View(box: Box, val manager: Boolean) : Hit(box)

		/** A whole row, under its controls: a click on the rest of it. */
		class Row(box: Box, val setting: ModuleSetting) : Hit(box)
	}

	/** Something a number or text is being typed into. */
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

	/** Where this module's page was left, the last time it closed this session. */
	private class Place(
		val showingManager: Boolean,
		val settingsScroll: Float,
		val managerScroll: Float,
		/** Rows left expanded, kept while the menu was closed on this page. */
		val expanded: Set<ModuleSetting>,
	)

	init {
		remembered[module.id]?.let { place ->
			if (manager != null) showingManager = place.showingManager
			settingsScroll = place.settingsScroll
			managerScroll = place.managerScroll
			scrollTarget = if (showingManager) managerScroll else settingsScroll
			scroll = scrollTarget
			expanded += place.expanded
		}
	}

	/** Keeps where the page is scrolled to, so it opens there again. */
	private fun remember(keepExpanded: Boolean) {
		if (showingManager) managerScroll = scrollTarget else settingsScroll = scrollTarget
		remembered[module.id] = Place(showingManager, settingsScroll, managerScroll, if (keepExpanded) expanded.toSet() else emptySet())
	}

	private var editing: Edit? = null
	private var input = TextField(0)

	/** True while the pointer, pressed in the field being typed in, is dragging out a selection. */
	private var selecting = false

	/** A click on a number's box that has not turned into a drag selects the number on release. */
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

	/** Which part of the picker is being dragged: 0 none, 1 square, 2 hue, 3 alpha. */
	private var pickerDrag = 0

	/** True while a key press belongs to this page: typing, or a bind waiting for its key. */
	val capturingKeys: Boolean get() = editing != null || awaiting != null || (showingManager && manager?.capturingKeys == true)

	// ---- Drawing ---------------------------------------------------------

	/** The row above the list: the module's name, its star, and its own switch. */
	fun drawTitle(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		// The controls on the right first, so the title knows how much room it has.
		var limit = box.right
		if (module.supportsToggle) {
			val switch = Box(box.right - 34f, box.y + (box.h - 16f) / 2f, 34f, 16f)
			val t = approach(switchPosition[module] ?: if (module.enabled) 1f else 0f, if (module.enabled) 1f else 0f, 20f, dt)
			switchPosition[module] = t
			MenuStyle.drawSwitch(graphics, switch, t, alpha)
			val label = if (module.enabled) "Enabled" else "Disabled"
			UiText.draw(graphics, label, switch.x - 12f - UiText.width(label, 13f), box.y + (box.h - 13f) / 2f, 13f, fade(MenuStyle.MUTED, alpha))
			hits += Hit.ModuleSwitch(switch)
			limit = box.right - 34f - 12f - UiText.width("Disabled", 13f) - 24f
		}
		// A module with a manager gets a switch between the two, left of its own.
		val section = manager
		if (section != null && (module.supportsKeybind || module.settings.any { it.onCard && it.isVisible() && it !is SlotMapModuleSetting })) {
			limit = drawViewSwitch(graphics, section.tabLabel, limit, box, mx, my, alpha) - 24f
		}

		// "Name | what it does", the second half quieter, then the star.
		val nameSize = 19f
		val detailSize = 14f
		var x = box.x + 4f
		UiText.draw(graphics, module.name, x, box.y + (box.h - nameSize) / 2f, nameSize, fade(MenuStyle.TEXT, alpha), UiText.Face.MEDIUM)
		x += UiText.width(module.name, nameSize, UiText.Face.MEDIUM) + 12f
		val starRoom = 22f + 10f
		if (module.description.isNotEmpty() && limit - x - starRoom > 60f) {
			val detailY = box.y + (box.h - detailSize) / 2f
			UiText.draw(graphics, "|", x, detailY, detailSize, fade(MenuStyle.FIELD_EDGE_HOVER, alpha))
			x += UiText.width("|", detailSize) + 12f
			val detail = UiText.fit(module.description, detailSize, limit - x - starRoom)
			UiText.draw(graphics, detail, x, detailY, detailSize, fade(MenuStyle.MUTED, alpha))
			x += UiText.width(detail, detailSize) + 10f
		}

		val star = Box(x, box.y + 4f, 22f, 22f)
		val favorite = MenuPrefs.isFavorite(module.id)
		val starColor = when {
			favorite -> MenuStyle.STAR
			star.contains(mx, my) -> MenuStyle.TEXT
			else -> MenuStyle.MUTED
		}
		UiText.drawIconCentered(graphics, Icons.STAR, star.x, star.y, star.w, 14f, fade(starColor, alpha), outline = !favorite)
		hits += Hit.Star(star)
	}

	/** The list of rows, clipped to [box] and scrolled. */
	fun draw(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		area = box
		frameDt = dt
		val pointerInside = box.contains(mx, my) && !overlayContains(mx, my)

		if (showingManager) {
			drawManager(graphics, box, mx, my, dt, alpha)
			return
		}

		if (!module.supportsKeybind && module.settings.none { it.onCard && it.isVisible() && it !is SlotMapModuleSetting }) {
			UiText.draw(graphics, "This module has nothing to set. Its switch is all there is.", box.x + 2f, box.y + 6f, 14f, fade(MenuStyle.MUTED, alpha))
			maxScroll = 0f
			return
		}

		graphics.enableScissor(box.x.toInt(), box.y.toInt(), box.right.toInt() + 1, box.bottom.toInt())
		var y = box.y - scroll
		var first = true

		if (module.supportsKeybind) {
			y += drawKeybindRow(graphics, box.x, y, box.w, mx, my, pointerInside, dt, alpha)
			first = false
		}

		for (setting in module.settings) {
			if (!setting.onCard || !setting.isVisible()) continue
			if (setting is SectionModuleSetting) {
				y += drawHeading(graphics, setting.label, box.x, y, first, alpha)
				first = false
				if (setting.hasVisibleMore()) y += drawChildren(graphics, setting, box, y, mx, my, pointerInside, dt, alpha, alwaysOpen = true)
				continue
			}
			first = false
			y += drawRow(graphics, setting, box.x, y, box.w, nested = false, mx, my, pointerInside, dt, alpha)
			if (setting in expanded && setting.hasVisibleMore()) {
				y += drawChildren(graphics, setting, box, y, mx, my, pointerInside, dt, alpha, alwaysOpen = false)
			}
		}
		MenuStyle.drawScrollFades(graphics, box, scroll, maxScroll, alpha)
		graphics.disableScissor()

		val contentH = y + scroll - box.y
		maxScroll = max(0f, contentH - box.h + 8f)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)
		scrollbar.draw(graphics, box.right + Scrollbar.GAP, box.y, box.h, contentH, scroll, maxScroll, mx, my, dt, alpha)
	}

	/** "Aliases | Settings": which of the two the page shows, right-aligned at [right]. Returns its left edge. */
	private fun drawViewSwitch(graphics: GuiGraphicsExtractor, label: String, right: Float, row: Box, mx: Float, my: Float, alpha: Float): Float {
		val size = 13f
		val pad = 14f
		val h = 28f
		val y = row.y + (row.h - h) / 2f
		val settingsW = UiText.width("Settings", size, UiText.Face.MEDIUM) + pad * 2f
		val managerW = UiText.width(label, size, UiText.Face.MEDIUM) + pad * 2f
		val x = right - settingsW - managerW - 6f
		RoundRect.fill(graphics, x, y, right, y + h, fade(MenuStyle.FIELD, alpha), 7f)
		RoundRect.outline(graphics, x, y, right, y + h, fade(MenuStyle.FIELD_EDGE, alpha), 7f, 1f)
		val parts = listOf(Triple(label, managerW, true), Triple("Settings", settingsW, false))
		var cx = x + 3f
		for ((text, w, isManager) in parts) {
			val part = Box(cx, y + 3f, w, h - 6f)
			val chosen = showingManager == isManager
			if (chosen) RoundRect.fill(graphics, part.x, part.y, part.right, part.bottom, fade(MenuStyle.TAB_PILL, alpha), 5f)
			val color = when {
				chosen -> MenuStyle.TEXT
				part.contains(mx, my) -> MenuStyle.TAB_HOVER
				else -> MenuStyle.MUTED
			}
			UiText.draw(graphics, text, part.x + (part.w - UiText.width(text, size, UiText.Face.MEDIUM)) / 2f, part.y + (part.h - size) / 2f, size, fade(color, alpha), UiText.Face.MEDIUM)
			hits += Hit.View(part, isManager)
			cx += w
		}
		return x
	}

	/** The manager, in the same scrolled, clipped area the settings use. */
	private fun drawManager(graphics: GuiGraphicsExtractor, box: Box, mx: Float, my: Float, dt: Float, alpha: Float) {
		val section = manager ?: return
		// Nothing under an open colour picker counts as pointed at.
		val covered = overlayContains(mx, my)
		section.ui.begin(graphics, box, if (covered) -1e6f else mx, if (covered) -1e6f else my, dt, alpha)
		graphics.enableScissor(box.x.toInt(), box.y.toInt(), box.right.toInt() + 1, box.bottom.toInt())
		val used = section.draw(box.x, box.y - scroll, box.w)
		MenuStyle.drawScrollFades(graphics, box, scroll, maxScroll, alpha)
		graphics.disableScissor()
		maxScroll = max(0f, used - box.h + 8f)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = approach(scroll, scrollTarget, 16f, dt)
		scrollbar.draw(graphics, box.right + Scrollbar.GAP, box.y, box.h, used, scroll, maxScroll, mx, my, dt, alpha)
	}

	/**
	 * Opens the colour picker on [setting], hung from [anchor]: how a manager
	 * edits a colour it keeps as a setting without a row of its own.
	 */
	fun openColorPicker(setting: ColorModuleSetting, anchor: Box) {
		openColor = setting
		colorAnchor = anchor
		hsv = java.awt.Color.RGBtoHSB((setting.rgb shr 16) and 0xFF, (setting.rgb shr 8) and 0xFF, setting.rgb and 0xFF, null)
		// The hex code is ready to be replaced, so one paste is enough.
		startEditing(Edit.Hex(setting), selectAll = true)
	}

	/**
	 * The page is going away: what was typed goes in, and the manager saves.
	 * [keepExpanded] is true when the whole menu closed on this page, which
	 * reopens on it as it was; leaving the module folds its rows back up.
	 */
	fun onClose(keepExpanded: Boolean = true) {
		commitEditing()
		manager?.onClose()
		remember(keepExpanded)
	}

	fun keyReleased(event: KeyEvent): Boolean = showingManager && manager?.keyReleased(event) == true

	/** Dropdown lists and the colour picker, over everything else. */
	fun drawOverlays(graphics: GuiGraphicsExtractor, mx: Float, my: Float, alpha: Float) {
		if (showingManager) manager?.drawOverlays()
		openDropdown?.let { drawDropdownList(graphics, it, mx, my, alpha) }
		openColor?.let { drawPicker(graphics, it, mx, my, alpha) }
		drawSuggestions(graphics, mx, my, alpha)
	}

	/** Called by the screen at the start of each frame, before anything records where it was drawn. */
	fun beginFrame() {
		hits.clear()
		suggestFor = null
	}

	private fun drawHeading(graphics: GuiGraphicsExtractor, label: String, x: Float, y: Float, first: Boolean, alpha: Float): Float {
		val gap = if (first) 2f else 18f
		UiText.draw(graphics, label.uppercase(), x + 2f, y + gap + 4f, 11f, fade(MenuStyle.MUTED, alpha), UiText.Face.MEDIUM)
		return gap + 26f
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

	/** One row: the label and description on the left, the control on the right. Returns its height. */
	private fun drawRow(
		graphics: GuiGraphicsExtractor, setting: ModuleSetting,
		x: Float, y: Float, w: Float, nested: Boolean,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float,
	): Float {
		val description = descriptionOf(setting)
		val h = if (description.isEmpty()) ROW_SHORT else ROW_TALL
		if (y + h < area.y || y > area.bottom) return h + ROW_GAP

		val row = Box(x, y, w, h)
		val hovered = pointerInside && row.contains(mx, my)
		val lift = approach(hover[setting] ?: 0f, if (hovered) 1f else 0f, 18f, dt).also { hover[setting] = it }
		val base = if (nested) MenuStyle.ROW_NESTED else MenuStyle.ROW
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(blend(base, MenuStyle.ROW_HOVER, lift), alpha), 0f)
		if (nested) RoundRect.fill(graphics, x, y, x + 2f, y + h, fade(MenuStyle.FIELD_EDGE, alpha), 0f)

		val cy = y + h / 2f
		val right = x + w - 16f
		// Registered before the controls, so a control on the row is still hit first.
		hits += Hit.Row(row, setting)
		val controlLeft = drawControl(graphics, setting, right, cy, row, mx, my, pointerInside, dt, alpha)

		// A row with settings of its own gets a chevron just left of its control.
		var labelRight = controlLeft - 16f
		if (setting !is PanelModuleSetting && setting.hasVisibleMore()) {
			val chevron = Box(controlLeft - 10f - 26f, cy - 13f, 26f, 26f)
			drawChevron(graphics, chevron, setting in expanded, pointerInside && chevron.contains(mx, my), alpha)
			hits += Hit.Expand(chevron, setting)
			labelRight = chevron.x - 10f
		}

		val labelX = x + if (nested) 34f else 18f
		val room = labelRight - labelX
		if (description.isEmpty()) {
			UiText.draw(graphics, UiText.fit(setting.label, LABEL, room), labelX, cy - LABEL / 2f, LABEL, fade(MenuStyle.TEXT, alpha))
		} else {
			UiText.draw(graphics, UiText.fit(setting.label, LABEL, room), labelX, y + 10f, LABEL, fade(MenuStyle.TEXT, alpha))
			UiText.draw(graphics, UiText.fit(description, DESCRIPTION, room), labelX, y + 30f, DESCRIPTION, fade(MenuStyle.MUTED, alpha))
		}
		return h + ROW_GAP
	}

	/** Draws the control for [setting], right-aligned at [right] and centred on [cy]. Returns its left edge. */
	private fun drawControl(
		graphics: GuiGraphicsExtractor, setting: ModuleSetting, right: Float, cy: Float, row: Box,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float,
	): Float {
		fun hovered(box: Box) = pointerInside && box.contains(mx, my)
		return when (setting) {
			is ToggleModuleSetting -> {
				val switch = Box(right - 34f, cy - 8f, 34f, 16f)
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
				val valueBox = Box(right - 62f, cy - 13f, 62f, 26f)
				drawValueBox(graphics, valueBox, Edit.Slider(setting), setting.boxValue, hovered(valueBox), alpha)
				val barW = 210f
				val barX = valueBox.x - 16f - barW
				val t = ((setting.value - setting.min) / (setting.max - setting.min)).toFloat().coerceIn(0f, 1f)
				drawTrack(graphics, barX, barW, cy, 0f, t, alpha)
				hits += Hit.Track(Box(barX - 8f, cy - 10f, barW + 16f, 20f), setting, barX, barW)
				barX - 8f
			}
			is RangeModuleSetting -> {
				val upperBox = Box(right - 52f, cy - 13f, 52f, 26f)
				val lowerBox = Box(upperBox.x - 6f - 52f, cy - 13f, 52f, 26f)
				drawValueBox(graphics, lowerBox, Edit.Lower(setting), setting.displayLower, hovered(lowerBox), alpha)
				drawValueBox(graphics, upperBox, Edit.Upper(setting), setting.displayUpper, hovered(upperBox), alpha)
				val barW = 180f
				val barX = lowerBox.x - 16f - barW
				val span = setting.max - setting.min
				val lower = ((setting.lower - setting.min) / span).toFloat().coerceIn(0f, 1f)
				val upper = ((setting.upper - setting.min) / span).toFloat().coerceIn(0f, 1f)
				drawTrack(graphics, barX, barW, cy, lower, upper, alpha, twoHandles = true)
				hits += Hit.Track(Box(barX - 8f, cy - 10f, barW + 16f, 20f), setting, barX, barW)
				barX - 8f
			}
			is DropdownModuleSetting, is MultiSelectModuleSetting -> {
				val source = setting as DropdownSource
				val box = Box(right - 220f, cy - 15f, 220f, 30f)
				drawDropdownBox(graphics, box, source, hovered(box), alpha)
				hits += Hit.Dropdown(box, source)
				if (openDropdown === source) dropdownAnchor = box
				box.x
			}
			is KeybindModuleSetting -> {
				val capturing = awaiting === setting
				drawBadge(graphics, if (capturing) "Press a key" else setting.keyName, right, cy, capturing || setting.bound, mx, my, pointerInside, alpha)
					.also { hits += Hit.Keybind(it, setting) }.x
			}
			is ButtonModuleSetting -> {
				val size = 13f
				// As wide as its label needs, and never narrower than the others.
				val w = max(150f, UiText.width(setting.label, size, UiText.Face.MEDIUM) + 32f)
				val box = Box(right - w, cy - 15f, w, 30f)
				val over = hovered(box)
				MenuStyle.drawField(graphics, box, over, false, alpha)
				UiText.draw(
					graphics, setting.label, box.x + (box.w - UiText.width(setting.label, size, UiText.Face.MEDIUM)) / 2f,
					box.y + (box.h - size) / 2f, size, fade(if (over) MenuStyle.TEXT else MenuStyle.TAB_HOVER, alpha), UiText.Face.MEDIUM,
				)
				hits += Hit.Button(box, setting)
				box.x
			}
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
				val box = Box(right - 260f, cy - 15f, 260f, 30f)
				val edit = Edit.Text(setting)
				drawTextField(graphics, box, edit, setting.value, setting.hint, hovered(box), alpha)
				// A field that completes what is typed lists the completions under itself.
				if (setting.suggestions != null && editing == edit) {
					suggestFor = setting
					suggestAnchor = box
				}
				hits += Hit.Text(box, edit)
				box.x
			}
			is PanelModuleSetting -> {
				// The whole row opens it, with the chevron at the end saying so.
				val chevron = Box(right - 26f, cy - 13f, 26f, 26f)
				drawChevron(graphics, chevron, setting in expanded, hovered(row), alpha)
				hits += Hit.Expand(row, setting)
				chevron.x
			}
			is SectionModuleSetting, is SlotMapModuleSetting -> right
		}
	}

	private fun drawKeybindRow(
		graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float,
		mx: Float, my: Float, pointerInside: Boolean, dt: Float, alpha: Float,
	): Float {
		val h = ROW_TALL
		val row = Box(x, y, w, h)
		val lift = approach(hover[module] ?: 0f, if (pointerInside && row.contains(mx, my)) 1f else 0f, 18f, dt).also { hover[module] = it }
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(blend(MenuStyle.ROW, MenuStyle.ROW_HOVER, lift), alpha), 0f)
		val cy = y + h / 2f
		val capturing = awaiting === module
		val name = module.keybind.keyName
		val badge = drawBadge(graphics, if (capturing) "Press a key" else name, x + w - 16f, cy, capturing || name != "None", mx, my, pointerInside, alpha)
		hits += Hit.Keybind(badge, module)
		UiText.draw(graphics, "Keybind", x + 18f, y + 10f, LABEL, fade(MenuStyle.TEXT, alpha))
		UiText.draw(graphics, "The key bound to this module. Escape or Backspace clears it.", x + 18f, y + 30f, DESCRIPTION, fade(MenuStyle.MUTED, alpha))
		return h + ROW_GAP
	}

	private fun drawBadge(
		graphics: GuiGraphicsExtractor, text: String, right: Float, cy: Float, lit: Boolean,
		mx: Float, my: Float, pointerInside: Boolean, alpha: Float,
	): Box {
		val size = 12f
		val w = max(64f, UiText.width(text, size, UiText.Face.MEDIUM) + 22f)
		val box = Box(right - w, cy - 13f, w, 26f)
		val over = pointerInside && box.contains(mx, my)
		val fill = if (lit) fade(MenuStyle.accent(), 0.18f) else MenuStyle.FIELD
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(fill, alpha), 6f)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(if (over) MenuStyle.FIELD_EDGE_HOVER else MenuStyle.FIELD_EDGE, alpha), 6f, 1f)
		UiText.draw(
			graphics, text, box.x + (box.w - UiText.width(text, size, UiText.Face.MEDIUM)) / 2f, box.y + (box.h - size) / 2f, size,
			fade(if (lit) MenuStyle.accent() else MenuStyle.MUTED, alpha), UiText.Face.MEDIUM,
		)
		return box
	}

	private fun drawChevron(graphics: GuiGraphicsExtractor, box: Box, open: Boolean, hovered: Boolean, alpha: Float) {
		if (hovered) RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.BUTTON_HOVER, alpha), 6f)
		val glyph = if (open) Icons.CHEVRON_DOWN else Icons.CHEVRON_RIGHT
		UiText.drawIconCentered(graphics, glyph, box.x, box.y, box.w, 11f, fade(if (open || hovered) MenuStyle.TEXT else MenuStyle.MUTED, alpha))
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
		MenuStyle.drawField(graphics, box, hovered, focused, alpha)
		val size = 12f
		if (focused) {
			input.draw(graphics, box, size, MenuStyle.TEXT, true, alpha, frameDt, padding = 6f, centered = true)
		} else {
			UiText.draw(graphics, value, box.x + (box.w - UiText.width(value, size)) / 2f, box.y + (box.h - size) / 2f, size, fade(MenuStyle.TEXT, alpha))
		}
		hits += Hit.Value(box, edit)
	}

	private fun drawTextField(graphics: GuiGraphicsExtractor, box: Box, edit: Edit, value: String, hint: String, hovered: Boolean, alpha: Float) {
		val focused = editing == edit
		MenuStyle.drawField(graphics, box, hovered, focused, alpha)
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
		MenuStyle.drawField(graphics, box, hovered, open, alpha)
		val size = 13f
		val text = UiText.fit(source.selected, size, box.w - 44f)
		UiText.draw(graphics, text, box.x + 12f, box.y + (box.h - size) / 2f, size, fade(if (open || hovered) MenuStyle.TEXT else MenuStyle.TAB_HOVER, alpha))
		UiText.drawIconCentered(graphics, Icons.CHEVRON_DOWN, box.right - 30f, box.y, 30f, 10f, fade(if (open) MenuStyle.accent() else MenuStyle.MUTED, alpha))
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

	/** The text field whose completions are showing this frame, and where it is. */
	private var suggestFor: TextModuleSetting? = null
	private var suggestAnchor = Box.NONE
	private var suggestBox = Box.NONE
	private val suggestRows = ArrayList<Pair<String, Box>>()
	private var suggestIndex = 0

	/** The completions last worked out, kept until the text changes: the sound list is long. */
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

	/** The completions for the field being typed in, in a list hung under it. */
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

	/** Puts a completion into the field, and into the setting with it. */
	private fun acceptSuggestion(option: String) {
		val edit = editing ?: return
		input.set(option)
		liveUpdate(edit)
	}

	private fun drawDropdownList(graphics: GuiGraphicsExtractor, source: DropdownSource, mx: Float, my: Float, alpha: Float) {
		dropdownRows.clear()
		val anchor = dropdownAnchor
		val rowH = 28f
		val pad = 4f
		val h = source.options.size * rowH + pad * 2f
		val below = anchor.bottom + 4f
		val y = if (below + h > area.bottom + 40f) anchor.y - 4f - h else below
		val box = Box(anchor.x, y, anchor.w, h)
		dropdownBox = box
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.POPUP, alpha), 8f)
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(MenuStyle.FIELD_EDGE, alpha), 8f, 1f)
		val size = 13f
		for ((index, option) in source.options.withIndex()) {
			val row = Box(box.x + pad, box.y + pad + index * rowH, box.w - pad * 2f, rowH)
			dropdownRows += index to row
			val over = row.contains(mx, my)
			if (over) RoundRect.fill(graphics, row.x, row.y, row.right, row.bottom, fade(MenuStyle.POPUP_HOVER, alpha), 6f)
			val checked = source.isChecked(index)
			val color = if (checked) MenuStyle.accent() else if (over) MenuStyle.TEXT else MenuStyle.TAB_HOVER
			UiText.draw(graphics, UiText.fit(option, size, row.w - 40f), row.x + 10f, row.y + (rowH - size) / 2f, size, fade(color, alpha))
			if (checked) UiText.drawIconCentered(graphics, Icons.CHECK, row.right - 28f, row.y, rowH, 10f, fade(MenuStyle.accent(), alpha))
		}
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

		// The hue, as a rainbow.
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
			// Square, like the hue bar above it.
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

	// ---- Input -----------------------------------------------------------

	private fun overlayContains(mx: Float, my: Float): Boolean =
		(openDropdown != null && dropdownBox.contains(mx, my)) || (openColor != null && pickerBox.contains(mx, my)) ||
			suggestBox.contains(mx, my)

	fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean = false): Boolean {
		// A bind waiting for its key takes any mouse button but the left one,
		// which stops it waiting instead.
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
		// A manager hears every button: the crosshair canvas erases with the right one.
		if (showingManager && button != 0 && openColor == null) return manager?.mouseClicked(mx, my, button, doubleClick) == true
		// A right click anywhere on a row opens what it holds, without aiming at the chevron.
		if (button == 1 && openDropdown == null && openColor == null && area.contains(mx, my)) {
			val row = hits.asReversed().firstOrNull { it is Hit.Row && it.box.contains(mx, my) } as? Hit.Row ?: return false
			if (!row.setting.hasVisibleMore()) return false
			commitEditing()
			if (!expanded.add(row.setting)) expanded.remove(row.setting)
			return true
		}
		if (button != 0) return false

		// The floating parts first: they sit over the rows.
		if (suggestBox.contains(mx, my)) {
			suggestRows.firstOrNull { it.second.contains(mx, my) }?.let { acceptSuggestion(it.first) }
			return true
		}
		openDropdown?.let { source ->
			for ((index, row) in dropdownRows) {
				if (row.contains(mx, my)) {
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

		// The title row is the page's own; the rest of a manager's view is the manager's.
		val titleHit = hits.lastOrNull { (it is Hit.Star || it is Hit.ModuleSwitch || it is Hit.View) && it.box.contains(mx, my) }
		if (showingManager && titleHit == null) {
			commitEditing()
			return manager?.mouseClicked(mx, my, button, doubleClick) == true || area.contains(mx, my)
		}
		if (!area.contains(mx, my) && titleHit == null) {
			commitEditing()
			return false
		}

		val hit = hits.asReversed().firstOrNull { it.box.contains(mx, my) }
		// A click anywhere but the field being typed in puts in what was typed.
		val target = (hit as? Hit.Value)?.target ?: (hit as? Hit.Text)?.target
		if (target == null || target != editing) commitEditing()

		when (hit) {
			null -> Unit
			is Hit.Toggle -> hit.setting.value = !hit.setting.value
			is Hit.ModuleSwitch -> module.enabled = !module.enabled
			is Hit.Star -> MenuPrefs.toggleFavorite(module.id)
			is Hit.View -> if (showingManager != hit.manager) {
				// Each view keeps its own place.
				if (showingManager) managerScroll = scrollTarget else settingsScroll = scrollTarget
				showingManager = hit.manager
				scrollTarget = if (showingManager) managerScroll else settingsScroll
				scroll = scrollTarget
				openDropdown = null
				manager?.ui?.blur()
			}
			is Hit.Expand -> if (!expanded.add(hit.setting)) expanded.remove(hit.setting)
			// The rest of a toggle's row flips it, so the switch need not be aimed at.
			is Hit.Row -> (hit.setting as? ToggleModuleSetting)?.let { it.value = !it.value }
			is Hit.Track -> {
				dragging = hit
				if (hit.setting is RangeModuleSetting) {
					val value = valueAt(hit, mx)
					dragLower = abs(value - hit.setting.lower) <= abs(value - hit.setting.upper)
				}
				drag(mx)
			}
			// A number is selected whole when its box is clicked, so typing replaces it.
			is Hit.Value -> focusField(hit.target, hit.box, mx, selectAll = true, doubleClick)
			is Hit.Text -> focusField(hit.target, hit.box, mx, selectAll = false, doubleClick)
			is Hit.Dropdown -> {
				openDropdown = hit.source
				dropdownAnchor = hit.box
			}
			is Hit.Keybind -> awaiting = hit.target
			is Hit.Button -> {
				val action = hit.setting.action
				Minecraft.getInstance().execute { action() }
			}
			is Hit.Swatch -> {
				openColor = hit.setting
				colorAnchor = hit.box
				hsv = java.awt.Color.RGBtoHSB((hit.setting.rgb shr 16) and 0xFF, (hit.setting.rgb shr 8) and 0xFF, hit.setting.rgb and 0xFF, null)
				// The hex code is ready to be replaced, so one paste is enough.
				startEditing(Edit.Hex(hit.setting), selectAll = true)
			}
		}
		return true
	}

	/**
	 * A press in a field: the caret goes where it landed and a drag from there
	 * selects, whether or not the field was being typed in already. A number's
	 * box that is only clicked, not dragged across, selects the whole number on
	 * release, so typing replaces it. A double click selects everything.
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
		val was = dragging != null || pickerDrag != 0 || selecting
		dragging = null
		pickerDrag = 0
		selecting = false
		return scrollbar.release() || was || managerHad
	}

	fun mouseScrolled(amount: Float) {
		if (openDropdown != null) return
		scrollTarget = (scrollTarget - amount * 70f).coerceIn(0f, maxScroll)
	}

	/** Handles a key, returning false when the screen should have it (Escape with nothing to cancel). */
	fun keyPressed(event: KeyEvent): Boolean {
		awaiting?.let { target ->
			val clear = event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE
			when (target) {
				is KeybindModuleSetting -> target.keyCode = if (clear) KeybindModuleSetting.UNBOUND else event.key()
				is Module -> target.keybind.setKey(if (clear) null else runCatching { InputConstants.getKey(event) }.getOrNull())
			}
			awaiting = null
			return true
		}
		val edit = editing
		if (edit != null) {
			when (event.key()) {
				GLFW.GLFW_KEY_ESCAPE -> {
					stopEditing()
					// Escape out of the hex code shuts the picker with it.
					if (edit is Edit.Hex) openColor = null
				}
				// With completions showing, Tab takes the lit one and the arrows move the light.
				GLFW.GLFW_KEY_TAB -> currentSuggestions().getOrNull(suggestIndex)?.let(::acceptSuggestion)
				GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
					val count = currentSuggestions().size
					if (count > 0) suggestIndex = (suggestIndex + if (event.key() == GLFW.GLFW_KEY_DOWN) 1 else count - 1) % count
				}
				GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitEditing()
				else -> if (input.keyPressed(event)) liveUpdate(edit)
			}
			return true
		}
		if (showingManager && manager?.keyPressed(event) == true) return true
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && (openDropdown != null || openColor != null)) {
			openDropdown = null
			openColor = null
			return true
		}
		return false
	}

	fun charTyped(codePoint: Int): Boolean {
		val edit = editing ?: return showingManager && manager?.charTyped(codePoint) == true
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

	/** Text goes in as it is typed; a hex code as soon as it is whole. */
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
		// The hex field follows the drag. It is usually focused, ready for a
		// paste, and it is committed when the picker closes — so a code left
		// behind there put the colour straight back to where it started.
		val hex = Edit.Hex(setting)
		if (editing == hex) input.set(rawValue(hex), selectAll = true)
	}

	private fun snap(value: Double, min: Double, max: Double, step: Double): Double {
		val clamped = value.coerceIn(min, max)
		if (step <= 0.0) return clamped
		return (min + round((clamped - min) / step) * step).coerceIn(min, max)
	}

	private companion object {
		/** Each module's page as it was left, for the length of the game. */
		val remembered = HashMap<String, Place>()

		const val ROW_TALL = 56f
		const val ROW_SHORT = 44f
		const val ROW_GAP = 2f
		const val LABEL = 14f
		const val DESCRIPTION = 12f
	}
}
