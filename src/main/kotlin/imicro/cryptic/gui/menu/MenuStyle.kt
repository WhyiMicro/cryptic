package imicro.cryptic.gui.menu

import imicro.cryptic.feature.ClickGui
import imicro.cryptic.render.RoundRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.exp
import kotlin.math.roundToInt

class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
	val right get() = x + w
	val bottom get() = y + h
	fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h

	companion object {
		val NONE = Box(0f, 0f, 0f, 0f)
	}
}

object MenuStyle {
	// Blue-black panels with hairline edges: cards and rows step a shade lighter with an edge of
	// their own, so the accent is the only bright colour in the menu.
	const val WINDOW_BG = 0xFF1A1A1E.toInt()
	const val WINDOW_EDGE = 0xFF29292F.toInt()
	const val BUTTON_HOVER = 0xFF27272D.toInt()
	const val FIELD = 0xFF151518.toInt()
	const val FIELD_EDGE = 0xFF323239.toInt()
	const val FIELD_EDGE_HOVER = 0xFF46464E.toInt()
	const val PLACEHOLDER = 0xFF6A6A6A.toInt()
	const val TEXT = 0xFFF0F0F0.toInt()
	const val TAB_HOVER = 0xFFCDCDCD.toInt()
	const val MUTED = 0xFF8A8A8A.toInt()
	const val ICON_OFF = 0xFF5E5E5E.toInt()
	const val STAR = 0xFFF4C430.toInt()
	const val DANGER = 0xFFE5484D.toInt()
	const val CARD_OFF = 0xFF1E1E22.toInt()
	const val CARD_OFF_HOVER = 0xFF232328.toInt()
	const val CARD_EDGE = 0xFF2C2C33.toInt()
	const val CARD_EDGE_HOVER = 0xFF3C3C44.toInt()
	const val CARD_ICON = 0xFFBDBDBD.toInt()
	const val CARD_GEAR = 0xFF555555.toInt()
	// The switch: a grey track filling with the accent, and a dark knob turning white as it
	// crosses.
	const val SWITCH_OFF = 0xFF303037.toInt()
	const val KNOB_OFF = 0xFF17171A.toInt()
	const val KNOB_ON = 0xFFFFFFFF.toInt()
	const val SCROLL_TRACK = 0x14FFFFFF
	const val SCROLL_THUMB = 0x40FFFFFF
	const val SCROLL_THUMB_HOVER = 0x80FFFFFF.toInt()
	const val TRACK = 0xFF303037.toInt()
	const val ROW = 0xFF1E1E22.toInt()
	const val ROW_HOVER = 0xFF232328.toInt()
	const val ROW_NESTED = 0xFF1C1C20.toInt()
	/** The hairline between rows of a group, and the group's own edge. */
	const val ROW_LINE = 0xFF2A2A31.toInt()
	const val GROUP_EDGE = 0xFF2C2C33.toInt()
	const val POPUP = 0xFF25252B.toInt()
	const val POPUP_HOVER = 0xFF2F2F36.toInt()
	const val POPUP_EDGE = 0xFF37373F.toInt()
	const val CARD_DESC = 0xFF828282.toInt()
	const val SEMI_LEGIT = 0xFFE8BE3C.toInt()
	const val HEADING_LINE = 0xFF3A3A42.toInt()
	/** A row's controls: a step lighter than the row, with no edge until focused. */
	const val CONTROL = 0xFF29292F.toInt()
	const val CONTROL_HOVER = 0xFF303037.toInt()
	/** The sidebar: small caps over its groups, its entries, and one under the pointer. */
	const val GROUP_LABEL = 0xFF74747C.toInt()
	const val NAV_TEXT = 0xFFC4C4C4.toInt()
	/** The sidebar sits a shade darker than the header and the cards' panel. */
	const val SIDEBAR_BG = 0xFF121214.toInt()
	const val NAV_HOVER = 0xFF1C1C20.toInt()

	/** The Font Awesome glyphs the menu uses. [STAR] comes in solid and outline. */
	object Icons {
		const val LAYERS = ""
		const val GRID = ""
		const val USER = ""
		const val CLOCK = ""
		const val COINS = ""
		const val MAP = ""
		const val WARNING = ""
		const val RESET = ""
		const val STAR = ""
		const val SEARCH = ""
		const val XMARK = ""
		const val EDIT = ""
		const val GEAR = ""
		const val DUNGEON = ""
		const val SKULL = ""
		const val EYE = ""
		const val SLIDERS = ""
		const val ARROW_LEFT = ""
		const val CHEVRON_DOWN = ""
		const val CHEVRON_RIGHT = ""
		const val CHECK = ""
		const val POWER = ""
	}

	/** Click GUI's accent colour, used for everything "on" or "chosen". */
	fun accent(): Int = 0xFF000000.toInt() or ClickGui.accentColor.rgb

	fun cardOn(): Int = accent()

	fun cardOnHover(): Int = blend(accent(), 0xFFFFFFFF.toInt(), 0.12f)

	fun onMuted(): Int = blend(accent(), 0xFFFFFFFF.toInt(), 0.82f)

	/** A menu card that's on: a faint wash of the accent, and an edge of it. */
	fun cardTint(): Int = blend(CARD_OFF, accent(), 0.08f)

	fun cardTintHover(): Int = blend(CARD_OFF, accent(), 0.13f)

	fun cardOnEdge(): Int = blend(CARD_EDGE, accent(), 0.7f)

	/** The chosen sidebar entry: the panel tinted towards the accent. */
	fun navSelected(): Int = blend(SIDEBAR_BG, accent(), 0.13f)

	/** A chosen row: the row tinted towards the accent. */
	fun rowSelected(): Int = blend(ROW, accent(), 0.3f)

	fun selection(): Int = (accent() and 0x00FFFFFF) or 0x66000000

	fun scale(color: Int, factor: Float): Int {
		val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
		val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
		val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
		return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
	}

	fun approach(current: Float, target: Float, speed: Float, dt: Float): Float =
		if (dt <= 0f) target else current + (target - current) * (1f - exp(-speed * dt))

	fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

	fun fade(color: Int, amount: Float): Int {
		if (amount >= 0.999f) return color
		val a = ((color ushr 24) * amount.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
		return (color and 0x00FFFFFF) or (a shl 24)
	}

	fun blend(from: Int, to: Int, t: Float): Int {
		if (t <= 0f) return from
		if (t >= 1f) return to
		var out = 0
		for (shift in intArrayOf(0, 8, 16, 24)) {
			val a = (from ushr shift) and 0xFF
			val b = (to ushr shift) and 0xFF
			out = out or (((a + (b - a) * t).toInt() and 0xFF) shl shift)
		}
		return out
	}

	/**
	 * A switch whose track and knob blend as it slides, so one flicked back halfway turns from
	 * where it is.
	 */
	fun drawSwitch(graphics: GuiGraphicsExtractor, box: Box, t: Float, alpha: Float) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(blend(SWITCH_OFF, accent(), t), alpha), box.h / 2f)
		val radius = box.h * 0.37f
		val inset = box.h / 6f
		val cx = box.x + inset + radius + (box.w - inset * 2f - radius * 2f) * t
		val cy = box.y + box.h / 2f
		RoundRect.fill(graphics, cx - radius, cy - radius, cx + radius, cy + radius, fade(blend(KNOB_OFF, KNOB_ON, t), alpha), radius)
	}

	/**
	 * Soft edges on a scrolling list: the window colour fading in over the top and bottom of [box].
	 * Each fade shows only while there's more list beyond it, and grows in over the first [topRamp]
	 * / [bottomRamp] of scrolling. Drawn after the list, inside its clip.
	 */
	fun drawScrollFades(
		graphics: GuiGraphicsExtractor, box: Box, scroll: Float, maxScroll: Float, alpha: Float,
		topRamp: Float = SCROLL_FADE, bottomRamp: Float = SCROLL_FADE,
	) {
		val top = (scroll / topRamp.coerceAtLeast(1f)).coerceIn(0f, 1f)
		val bottom = ((maxScroll - scroll) / bottomRamp.coerceAtLeast(1f)).coerceIn(0f, 1f)
		if (top > 0.01f) drawFade(graphics, box.x - 1f, box.right + 1f, box.y, 1f, alpha * top)
		if (bottom > 0.01f) drawFade(graphics, box.x - 1f, box.right + 1f, box.bottom, -1f, alpha * bottom)
	}

	/**
	 * One edge's fade: a few solid pixels, then an eased fall-off (a straight ramp shows a line).
	 * Filled a screen row at a time so there are no seams.
	 */
	private fun drawFade(graphics: GuiGraphicsExtractor, x0: Float, x1: Float, edge: Float, direction: Float, strength: Float) {
		val ppu = UiText.pixelsPerUnit
		val pose = graphics.pose()
		// Rows are laid in screen pixels through the same transform the clip uses, so the first
		// covered row is the clip's first row.
		val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
		val from = pose.transformPosition(x0, edge, org.joml.Vector2f())
		val to = pose.transformPosition(x1, edge, org.joml.Vector2f())
		pose.pushMatrix()
		pose.identity()
		pose.scale(1f / guiScale, 1f / guiScale)
		val left = (from.x * guiScale).roundToInt()
		val right = (to.x * guiScale).roundToInt()
		val start = (from.y * guiScale).roundToInt()
		val solid = (FADE_SOLID * ppu).roundToInt().coerceAtLeast(1)
		val length = (FADE_LENGTH * ppu).roundToInt().coerceAtLeast(1)
		val step = if (direction > 0) 1 else -1
		// Starts outside the edge: the clip rounds outward to whole GUI units, and a sliver of list
		// left there shows as a line.
		val overlap = Minecraft.getInstance().window.guiScale + ppu.roundToInt() + 1
		for (row in -overlap until solid + length) {
			val t = ((row - solid + 0.5f) / length).coerceIn(0f, 1f)
			val cover = if (row < solid) 1f else 1f - t * t * (3f - 2f * t)
			val y = if (step > 0) start + row else start - row - 1
			graphics.fill(left, y, right, y + 1, fade(WINDOW_BG, strength * cover))
		}
		pose.popMatrix()
	}

	/**
	 * A soft side edge on something sliding across [box]: [color] at its left (or right) edge,
	 * easing out over [length]. Laid a screen column at a time, through the clip's own transform.
	 */
	fun drawSideFade(graphics: GuiGraphicsExtractor, box: Box, fromLeft: Boolean, length: Float, color: Int, strength: Float) {
		if (strength <= 0.01f) return
		val pose = graphics.pose()
		val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
		val topLeft = pose.transformPosition(box.x, box.y, org.joml.Vector2f())
		val bottomRight = pose.transformPosition(box.right, box.bottom, org.joml.Vector2f())
		pose.pushMatrix()
		pose.identity()
		pose.scale(1f / guiScale, 1f / guiScale)
		val top = (topLeft.y * guiScale).roundToInt()
		val bottom = (bottomRight.y * guiScale).roundToInt()
		val edge = ((if (fromLeft) topLeft.x else bottomRight.x) * guiScale).roundToInt()
		val columns = (length * UiText.pixelsPerUnit).roundToInt().coerceAtLeast(1)
		for (column in 0 until columns) {
			val t = (column + 0.5f) / columns
			val cover = 1f - t * t * (3f - 2f * t)
			val x = if (fromLeft) edge + column else edge - column - 1
			graphics.fill(x, top, x + 1, bottom, fade(color, strength * cover))
		}
		pose.popMatrix()
	}

	private const val FADE_SOLID = 3f
	private const val FADE_LENGTH = 30f
	private const val SCROLL_FADE = FADE_SOLID + FADE_LENGTH

	/**
	 * A tooltip under and right of the pointer, wrapped to [maxWidth] and kept inside [bounds].
	 * Drawn last, over everything.
	 */
	fun drawTooltip(graphics: GuiGraphicsExtractor, text: String, mx: Float, my: Float, bounds: Box, alpha: Float, maxWidth: Float = 320f) {
		if (alpha <= 0.01f) return
		val size = 12f
		val line = 16f
		val lines = UiText.wrap(text, size, maxWidth)
		val w = (lines.maxOfOrNull { UiText.width(it, size) } ?: 0f) + 20f
		val h = lines.size * line + 12f
		val x = (mx + 12f).coerceAtMost(bounds.right - w - 4f).coerceAtLeast(bounds.x + 4f)
		val y = if (my + 18f + h > bounds.bottom - 4f) my - 8f - h else my + 18f
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(POPUP, alpha), 6f)
		RoundRect.outline(graphics, x, y, x + w, y + h, fade(WINDOW_EDGE, alpha), 6f, 1f)
		lines.forEachIndexed { i, text -> UiText.draw(graphics, text, x + 10f, y + 6f + i * line + (line - size) / 2f, size, fade(TAB_HOVER, alpha)) }
	}

	/**
	 * What an empty list shows, centred in [box]: a large grey icon, [title] under it, and a
	 * line of advice under that.
	 */
	fun drawEmptyState(graphics: GuiGraphicsExtractor, box: Box, icon: String, title: String, advice: String, alpha: Float, outline: Boolean = false) {
		val cx = box.x + box.w / 2f
		val top = box.y + (box.h - EMPTY_HEIGHT) / 2f
		UiText.drawIconCentered(graphics, icon, cx - 20f, top, 40f, 28f, fade(EMPTY_ICON, alpha), outline)
		val shownTitle = UiText.fit(title, 15f, box.w - 32f, UiText.Face.MEDIUM)
		UiText.draw(graphics, shownTitle, cx - UiText.width(shownTitle, 15f, UiText.Face.MEDIUM) / 2f, top + 52f, 15f, fade(TEXT, alpha), UiText.Face.MEDIUM)
		val lines = UiText.wrap(advice, 13f, box.w - 48f)
		lines.forEachIndexed { i, line ->
			UiText.draw(graphics, line, cx - UiText.width(line, 13f) / 2f, top + 84f + i * 18f, 13f, fade(MUTED, alpha))
		}
	}

	private const val EMPTY_HEIGHT = 100f
	private const val EMPTY_ICON = 0xFF5C5C66.toInt()

	/** A control's box on a row: a lighter fill, edged in the accent while it has focus or is open. */
	fun drawControlBox(graphics: GuiGraphicsExtractor, box: Box, hovered: Boolean, focused: Boolean, alpha: Float, radius: Float = 6f) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(if (hovered) CONTROL_HOVER else CONTROL, alpha), radius)
		if (focused) RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(accent(), alpha), radius, 1f)
	}

	/** The menu's search box: a field with the magnifier at its left, typed into through [field]. */
	fun drawSearchBox(graphics: GuiGraphicsExtractor, box: Box, field: TextField, focused: Boolean, hovered: Boolean, placeholder: String, dt: Float, alpha: Float) {
		drawField(graphics, box, hovered, focused, alpha, radius = 7f)
		UiText.drawIconCentered(graphics, Icons.SEARCH, box.x + 6f, box.y + (box.h - 24f) / 2f, 24f, 12f, fade(if (focused) TEXT else PLACEHOLDER, alpha))
		field.draw(graphics, Box(box.x + 26f, box.y, box.w - 26f, box.h), 13f, TEXT, focused, alpha, dt, padding = 8f, placeholder = placeholder)
	}

	/**
	 * Clips to [box] rounded outward to whole screen pixels. Vanilla's scissor rounds the width
	 * down in GUI units, which on a large GUI scale cuts several pixels off the right and bottom.
	 */
	fun clip(graphics: GuiGraphicsExtractor, box: Box) {
		val pose = graphics.pose()
		val topLeft = pose.transformPosition(box.x, box.y, org.joml.Vector2f())
		val bottomRight = pose.transformPosition(box.right, box.bottom, org.joml.Vector2f())
		pose.pushMatrix()
		pose.identity()
		// The slack keeps an edge already on a whole unit from rounding a unit out.
		graphics.enableScissor(
			kotlin.math.floor(topLeft.x + 1e-3f).toInt(), kotlin.math.floor(topLeft.y + 1e-3f).toInt(),
			kotlin.math.ceil(bottomRight.x - 1e-3f).toInt(), kotlin.math.ceil(bottomRight.y - 1e-3f).toInt(),
		)
		pose.popMatrix()
	}

	/**
	 * Clips a scrolling list to [box] with its top and bottom moved in to whole GUI units, the
	 * finest a clip can end on, so nothing spills over the panel's edge. Returns the box clipped
	 * to, which is where its fades go.
	 */
	fun clipList(graphics: GuiGraphicsExtractor, box: Box): Box {
		val pose = graphics.pose()
		val origin = pose.transformPosition(0f, 0f, org.joml.Vector2f())
		val unit = pose.transformPosition(0f, 1f, org.joml.Vector2f()).y - origin.y
		if (unit <= 0f) {
			clip(graphics, box)
			return box
		}
		val top = (kotlin.math.ceil(box.y * unit + origin.y - 1e-3f) - origin.y) / unit
		val bottom = (kotlin.math.floor(box.bottom * unit + origin.y + 1e-3f) - origin.y) / unit
		val snapped = Box(box.x, top, box.w, (bottom - top).coerceAtLeast(0f))
		clip(graphics, snapped)
		return snapped
	}

	fun restrictionColor(restriction: imicro.cryptic.gui.Restriction): Int =
		if (restriction == imicro.cryptic.gui.Restriction.CHEAT) DANGER else SEMI_LEGIT

	fun restrictionTitle(restriction: imicro.cryptic.gui.Restriction): String =
		if (restriction == imicro.cryptic.gui.Restriction.CHEAT) "Cheat" else "Semi-legit"

	/**
	 * A heads-up box: a faint wash of [color] edged in it, an icon and [title] in the colour, and
	 * [text] under the title. Returns its height.
	 */
	fun drawCallout(graphics: GuiGraphicsExtractor, icon: String, title: String, text: String, color: Int, x: Float, y: Float, w: Float, alpha: Float): Float {
		val lines = UiText.wrap(text, CALLOUT_TEXT, w - CALLOUT_TEXT_X - CALLOUT_PAD)
		val h = CALLOUT_PAD_Y * 2f + CALLOUT_TITLE + CALLOUT_GAP + lines.size * CALLOUT_LINE
		RoundRect.fill(graphics, x, y, x + w, y + h, fade(blend(WINDOW_BG, color, 0.09f), alpha), 8f)
		RoundRect.outline(graphics, x, y, x + w, y + h, fade(blend(WINDOW_BG, color, 0.32f), alpha), 8f, 1f)
		val titleY = y + CALLOUT_PAD_Y
		UiText.drawIconCentered(graphics, icon, x + CALLOUT_PAD - 4f, titleY + (CALLOUT_TITLE - 22f) / 2f, 22f, 13f, fade(color, alpha))
		UiText.draw(graphics, title, x + CALLOUT_TEXT_X, titleY, CALLOUT_TITLE, fade(color, alpha), UiText.Face.MEDIUM)
		lines.forEachIndexed { i, line ->
			val lineY = titleY + CALLOUT_TITLE + CALLOUT_GAP + i * CALLOUT_LINE + (CALLOUT_LINE - CALLOUT_TEXT) / 2f
			UiText.draw(graphics, line, x + CALLOUT_TEXT_X, lineY, CALLOUT_TEXT, fade(CALLOUT_BODY, alpha))
		}
		return h
	}

	private const val CALLOUT_TITLE = 13f
	private const val CALLOUT_TEXT = 12.5f
	private const val CALLOUT_LINE = 17f
	private const val CALLOUT_GAP = 5f
	private const val CALLOUT_PAD = 16f
	private const val CALLOUT_PAD_Y = 11f
	private const val CALLOUT_TEXT_X = 42f
	private const val CALLOUT_BODY = 0xFFCFCFCF.toInt()


	/**
	 * A field's box: a shade off the row, with an edge that lightens on hover and turns teal while
	 * typing.
	 */
	fun drawField(graphics: GuiGraphicsExtractor, box: Box, hovered: Boolean, focused: Boolean, alpha: Float, radius: Float = 6f) {
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fade(FIELD, alpha), radius)
		val edge = when {
			focused -> accent()
			hovered -> FIELD_EDGE_HOVER
			else -> FIELD_EDGE
		}
		RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, fade(edge, alpha), radius, 1f)
	}
}
