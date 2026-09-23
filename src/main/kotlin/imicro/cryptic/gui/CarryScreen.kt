package imicro.cryptic.gui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import imicro.cryptic.carry.Carry
import imicro.cryptic.carry.CarryStore
import imicro.cryptic.feature.CarryManager
import imicro.cryptic.feature.ClickGui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The Carry Manager's own window.
 *
 * Separate from the settings screen on purpose: what is in here is not
 * settings. It is a job list that changes while you play, and it wants opening,
 * editing and shutting without walking through a module tree.
 *
 * Drawn the way the settings screen is drawn — a pill of tabs over cards, in
 * the same palette, at the same scale — rather than with Dear ImGui's stock
 * widgets. Two windows in one mod that look like two different programs is
 * worse than the work of matching them.
 */
class CarryScreen: Screen(Component.literal("Carry Manager")), ImGuiScreen {
	private val nameBuffer = ImString(24)
	private val amountBuffer = ImString(4).apply { set("1") }
	private var tier = 4

	/** The tier dropdown's state, and how far through opening it is. */
	private var tierOpen = false
	private var tierProgress = 0f

	private var selectedTab = 0
	private var animatedTabX = Float.NaN
	private var tabStartX = 0f
	private var tabProgress = 1f

	private var scrollOffset = 0f
	private var maxScroll = 0f

	private var editingName = false
	private var editingAmount = false

	/** Where the tier control sits, so the popup can be drawn over the cards. */
	private var tierX = 0f
	private var tierY = 0f
	private var tierWidth = 0f
	private var tierHeight = 0f

	/** Set by a row's buttons, applied after the list has been walked. */
	private var toRemove: Carry? = null
	private var toComplete: Carry? = null

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		extractBlurredBackground(graphics)
		graphics.fill(0, 0, width, height, 0x99000000.toInt())
	}

	override fun isPauseScreen() = false

	override fun keyPressed(event: KeyEvent): Boolean {
		ImGuiRuntime.key(event.key(), event.scancode(), event.modifiers(), true)
		return super.keyPressed(event)
	}

	override fun keyReleased(event: KeyEvent): Boolean {
		ImGuiRuntime.key(event.key(), event.scancode(), event.modifiers(), false)
		return super.keyReleased(event)
	}

	override fun charTyped(event: CharacterEvent): Boolean {
		ImGuiRuntime.character(event.codepoint())
		return true
	}

	override fun mouseMoved(mouseX: Double, mouseY: Double) {
		ImGuiRuntime.mousePosition(mouseX, mouseY)
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		ImGuiRuntime.mouseButton(event.button(), true)
		return true
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		ImGuiRuntime.mouseButton(event.button(), false)
		return true
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		ImGuiRuntime.mousePosition(event.x(), event.y())
		return true
	}

	override fun mouseScrolled(
		mouseX: Double,
		mouseY: Double,
		horizontalAmount: Double,
		verticalAmount: Double,
	): Boolean {
		ImGuiRuntime.mousePosition(mouseX, mouseY)
		scrollOffset = (scrollOffset - verticalAmount.toFloat() * SCROLL_STEP).coerceIn(0f, maxScroll)
		return true
	}

	override fun removed() {
		ImGuiRuntime.releaseInput()
		CarryStore.save()
		super.removed()
	}

	override fun drawImGui() {
		val io = ImGui.getIO()
		val displayWidth = io.displaySizeX
		val displayHeight = io.displaySizeY
		if (displayWidth <= 0f || displayHeight <= 0f) return

		val scale = (displayHeight / REFERENCE_HEIGHT * REFERENCE_SCALE).coerceAtLeast(0.75f)
		val dt = io.deltaTime.coerceIn(0f, 0.05f)

		ImGui.setNextWindowPos(0f, 0f)
		ImGui.setNextWindowSize(displayWidth, displayHeight)
		val flags = ImGuiWindowFlags.NoDecoration or
			ImGuiWindowFlags.NoMove or
			ImGuiWindowFlags.NoSavedSettings or
			ImGuiWindowFlags.NoBackground or
			ImGuiWindowFlags.NoBringToFrontOnFocus or
			ImGuiWindowFlags.NoNavFocus

		ImGui.begin("##cryptic_carry_root", flags)
		val draw = ImGui.getWindowDrawList()

		val panelWidth = dp(PANEL_WIDTH, scale)
		val panelX = (displayWidth - panelWidth) / 2f

		drawNavigation(draw, panelX, panelWidth, scale, dt)

		val contentY = dp(CONTENT_Y, scale)
		val contentBottom = displayHeight - dp(CONTENT_BOTTOM_MARGIN, scale)
		draw.pushClipRect(panelX, contentY, panelX + panelWidth, contentBottom, true)
		val used = when (selectedTab) {
			0 -> drawOverview(draw, panelX, contentY - scrollOffset, panelWidth, scale)
			else -> drawVoidgloom(draw, panelX, contentY - scrollOffset, panelWidth, scale)
		}
		draw.popClipRect()

		// Outside the clip, and after everything else, so the list is neither cut
		// off by the card it belongs to nor painted over by the rows below it.
		if (selectedTab == 1) drawTierOptions(draw, scale, dt)

		maxScroll = (used - (contentBottom - contentY)).coerceAtLeast(0f)
		scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

		ImGui.end()

		toRemove?.let { CarryManager.remove(it) }
		toComplete?.let { CarryManager.complete(it) }
		toRemove = null
		toComplete = null
	}

	/** The tab pill, which is the settings screen's navigation at half the width. */
	private fun drawNavigation(draw: ImDrawList, panelX: Float, panelWidth: Float, scale: Float, dt: Float) {
		val y = dp(28f, scale)
		val height = dp(30f, scale)
		val tabWidth = panelWidth / TABS.size

		draw.addRectFilled(panelX, y, panelX + panelWidth, y + height, SURFACE_DARK, height / 2f)

		TABS.forEachIndexed { index, _ ->
			val x = panelX + index * tabWidth
			if (hit(TAB_IDS[index], x, y, tabWidth, height) && index != selectedTab) {
				tabStartX = animatedTabX
				tabProgress = 0f
				selectedTab = index
				scrollOffset = 0f
			}
		}

		val targetX = panelX + selectedTab * tabWidth + dp(4f, scale)
		if (animatedTabX.isNaN()) {
			animatedTabX = targetX
			tabStartX = targetX
		}
		tabProgress = (tabProgress + dt / TAB_ANIMATION_SECONDS).coerceAtMost(1f)
		animatedTabX = tabStartX + (targetX - tabStartX) * easeOutCubic(tabProgress)

		draw.addRectFilled(
			animatedTabX,
			y + dp(4f, scale),
			animatedTabX + tabWidth - dp(8f, scale),
			y + height - dp(4f, scale),
			SURFACE_ACTIVE,
			(height - dp(8f, scale)) / 2f,
		)

		TABS.forEachIndexed { index, title ->
			centered(
				draw,
				title,
				panelX + index * tabWidth,
				y,
				tabWidth,
				height,
				if (index == selectedTab) TEXT else NAV_TEXT,
				dp(11f, scale),
			)
		}
	}

	/**
	 * The overview, which is four numbers and a progress bar.
	 *
	 * Deliberately short. Everything here is read at a glance between bosses,
	 * and a wall of statistics is not read at all — so the current job gets a
	 * card of its own, and the rest is one row of headline figures.
	 */
	private fun drawOverview(draw: ImDrawList, x: Float, top: Float, width: Float, scale: Float): Float {
		var y = top
		val totals = CarryStore.totals
		val active = CarryManager.activeCarry

		val currentHeight = dp(88f, scale)
		card(draw, x, y, width, currentHeight, scale)
		label(draw, "Current carry", x + dp(16f, scale), y + dp(12f, scale), MUTED_TEXT, dp(9f, scale))

		if (active == null) {
			label(draw, "Nothing on the list", x + dp(16f, scale), y + dp(30f, scale), TEXT, dp(13f, scale))
			label(
				draw,
				"Add somebody on the Voidgloom Seraph tab, or with /cryptic carry add.",
				x + dp(16f, scale),
				y + dp(52f, scale),
				MUTED_TEXT,
				dp(9.5f, scale),
			)
		} else {
			label(draw, active.name, x + dp(16f, scale), y + dp(28f, scale), TEXT, dp(15f, scale))
			badge(draw, "T${active.tier}", x + dp(16f, scale) + textWidth(active.name, dp(15f, scale)) + dp(8f, scale), y + dp(29f, scale), scale)

			val progressText = "${active.done}/${active.ordered}"
			label(
				draw,
				progressText,
				x + width - dp(16f, scale) - textWidth(progressText, dp(15f, scale)),
				y + dp(28f, scale),
				TEXT,
				dp(15f, scale),
			)

			progress(draw, x + dp(16f, scale), y + dp(54f, scale), width - dp(32f, scale), scale, active.done, active.ordered)

			val onBoss = CarryManager.currentBossMillis(active)
			val status = if (onBoss > 0) "Boss up for ${duration(onBoss)}" else "Waiting for a boss"
			label(draw, status, x + dp(16f, scale), y + dp(68f, scale), MUTED_TEXT, dp(9.5f, scale))
		}
		y += currentHeight + dp(CARD_GAP, scale)

		val statsHeight = dp(64f, scale)
		card(draw, x, y, width, statsHeight, scale)
		val columnWidth = width / 5f
		stat(draw, x, y, columnWidth, scale, totals.carriesCompleted.toString(), "Carries done")
		stat(draw, x + columnWidth, y, columnWidth, scale, totals.bossesKilled.toString(), "Bosses killed")
		stat(draw, x + columnWidth * 2, y, columnWidth, scale, duration(CarryStore.averageKillMillis()), "Average kill")
		stat(draw, x + columnWidth * 3, y, columnWidth, scale, duration(totals.lastKillMillis), "Last kill")
		stat(draw, x + columnWidth * 4, y, columnWidth, scale, duration(totals.fastestKillMillis), "Fastest kill")
		y += statsHeight + dp(CARD_GAP, scale)

		// The two places a carry happens, which is most of the walking between
		// bosses and the only reason to leave this window while working.
		val warpHeight = dp(58f, scale)
		card(draw, x, y, width, warpHeight, scale)
		label(draw, "Warp", x + dp(16f, scale), y + dp(12f, scale), MUTED_TEXT, dp(9f, scale))
		val warpWidth = (width - dp(40f, scale)) / 2f
		warpButton(draw, x + dp(16f, scale), y + dp(28f, scale), warpWidth, scale, "##carry_warp_island", "Island", "warp island")
		warpButton(draw, x + dp(24f, scale) + warpWidth, y + dp(28f, scale), warpWidth, scale, "##carry_warp_void", "Voidgloom", "warp void")
		y += warpHeight + dp(CARD_GAP, scale)

		val history = CarryStore.history
		if (history.isEmpty()) return y - top

		val rows = history.asReversed().take(4)
		val rowHeight = dp(22f, scale)
		val historyHeight = dp(30f, scale) + rows.size * rowHeight
		card(draw, x, y, width, historyHeight, scale)
		label(draw, "Finished", x + dp(16f, scale), y + dp(12f, scale), MUTED_TEXT, dp(9f, scale))
		rows.forEachIndexed { index, entry ->
			val rowY = y + dp(28f, scale) + index * rowHeight
			val fontSize = dp(10f, scale)

			label(draw, entry.name, x + dp(16f, scale), rowY + dp(3f, scale), TEXT, fontSize)
			var cursor = x + dp(16f, scale) + textWidth(entry.name, fontSize) + dp(7f, scale)

			badge(draw, "T${entry.tier}", cursor, rowY, scale)
			cursor += textWidth("T${entry.tier}", dp(9f, scale)) + dp(12f, scale) + dp(7f, scale)

			// The boss is named rather than assumed: Voidgloom is the only kind
			// today, and this row has to stay readable when it is not.
			label(draw, entry.bossType, cursor, rowY + dp(3f, scale), MUTED_TEXT, dp(9.5f, scale))

			val amount = "Amount: ${entry.count}/${entry.orderedOrCount}"
			val took = duration(entry.durationMillis)
			val tookWidth = textWidth(took, fontSize)
			label(draw, took, x + width - dp(16f, scale) - tookWidth, rowY + dp(3f, scale), MUTED_TEXT, fontSize)
			label(
				draw,
				amount,
				x + width - dp(26f, scale) - tookWidth - textWidth(amount, fontSize),
				rowY + dp(3f, scale),
				TEXT,
				fontSize,
			)
		}
		y += historyHeight + dp(CARD_GAP, scale)

		return y - top
	}

	/** One warp button, which sends its command and shuts the window. */
	private fun warpButton(
		draw: ImDrawList,
		x: Float,
		y: Float,
		width: Float,
		scale: Float,
		id: String,
		title: String,
		command: String,
	) {
		val height = dp(20f, scale)
		val hovered = ImGui.isMouseHoveringRect(x, y, x + width, y + height)
		draw.addRectFilled(x, y, x + width, y + height, if (hovered) ACCENT else SURFACE_ACTIVE, height / 2f)
		centered(draw, title, x, y, width, height, if (hovered) SURFACE else TEXT, dp(10f, scale))
		if (hit(id, x, y, width, height)) warp(command)
	}

	private fun drawVoidgloom(draw: ImDrawList, x: Float, top: Float, width: Float, scale: Float): Float {
		var y = top

		val suggestions = lobbyMatches()
		val addHeight = dp(if (suggestions.isEmpty()) 74f else 100f, scale)
		card(draw, x, y, width, addHeight, scale)
		label(draw, "Add a carry", x + dp(16f, scale), y + dp(12f, scale), MUTED_TEXT, dp(9f, scale))

		val fieldY = y + dp(28f, scale)
		val fieldHeight = dp(22f, scale)
		val fieldWidth = width * 0.38f
		nameField(draw, x + dp(16f, scale), fieldY, fieldWidth, fieldHeight, scale)

		tierX = x + dp(16f, scale) + fieldWidth + dp(8f, scale)
		tierY = fieldY
		tierWidth = dp(66f, scale)
		tierHeight = fieldHeight
		drawTierButton(draw, scale)

		amountField(draw, tierX + tierWidth + dp(8f, scale), fieldY, dp(52f, scale), fieldHeight, scale)

		val addWidth = dp(46f, scale)
		val addX = x + width - dp(16f, scale) - addWidth
		val addHovered = ImGui.isMouseHoveringRect(addX, fieldY, addX + addWidth, fieldY + fieldHeight)
		draw.addRectFilled(addX, fieldY, addX + addWidth, fieldY + fieldHeight, if (addHovered) ACCENT else SURFACE_ACTIVE, fieldHeight / 2f)
		centered(draw, "Add", addX, fieldY, addWidth, fieldHeight, if (addHovered) SURFACE else TEXT, dp(10f, scale))
		if (hit("##carry_add", addX, fieldY, addWidth, fieldHeight)) submit()

		if (suggestions.isNotEmpty()) {
			val chipY = fieldY + fieldHeight + dp(8f, scale)
			var chipX = x + dp(16f, scale)
			label(draw, "In this lobby:", chipX, chipY + dp(4f, scale), MUTED_TEXT, dp(9f, scale))
			chipX += textWidth("In this lobby:", dp(9f, scale)) + dp(8f, scale)
			for (name in suggestions) {
				val chipWidth = textWidth(name, dp(9.5f, scale)) + dp(14f, scale)
				if (chipX + chipWidth > x + width - dp(16f, scale)) break
				val hovered = ImGui.isMouseHoveringRect(chipX, chipY, chipX + chipWidth, chipY + dp(18f, scale))
				draw.addRectFilled(chipX, chipY, chipX + chipWidth, chipY + dp(18f, scale), if (hovered) SURFACE_ACTIVE else TRACK, dp(9f, scale))
				centered(draw, name, chipX, chipY, chipWidth, dp(18f, scale), TEXT, dp(9.5f, scale))
				if (hit("##carry_suggest_$name", chipX, chipY, chipWidth, dp(18f, scale))) nameBuffer.set(name)
				chipX += chipWidth + dp(5f, scale)
			}
		}
		y += addHeight + dp(CARD_GAP, scale)

		val carries = CarryManager.carries
		if (carries.isEmpty()) {
			val emptyHeight = dp(46f, scale)
			card(draw, x, y, width, emptyHeight, scale)
			centered(draw, "No carries yet", x, y, width, emptyHeight, MUTED_TEXT, dp(11f, scale))
			return y + emptyHeight - top
		}

		for ((index, carry) in carries.withIndex()) {
			val rowHeight = dp(52f, scale)
			card(draw, x, y, width, rowHeight, scale)

			label(draw, carry.name, x + dp(16f, scale), y + dp(11f, scale), TEXT, dp(12f, scale))
			badge(draw, "T${carry.tier}", x + dp(16f, scale) + textWidth(carry.name, dp(12f, scale)) + dp(8f, scale), y + dp(11f, scale), scale)

			val counter = "${carry.done}/${carry.ordered}"
			var buttonX = x + width - dp(16f, scale)
			val buttonSize = dp(18f, scale)

			buttonX -= buttonSize
			iconButton(draw, buttonX, y + dp(10f, scale), buttonSize, scale, "##carry_remove_$index", FontAwesomeIcons.XMARK, DELETE_TEXT) {
				toRemove = carry
			}
			buttonX -= buttonSize + dp(4f, scale)
			iconButton(draw, buttonX, y + dp(10f, scale), buttonSize, scale, "##carry_done_$index", FontAwesomeIcons.CHECK, TEXT) {
				toComplete = carry
			}
			buttonX -= buttonSize + dp(10f, scale)
			iconButton(draw, buttonX, y + dp(10f, scale), buttonSize, scale, "##carry_plus_$index", "+", TEXT) {
				carry.done++
				CarryStore.save()
			}
			buttonX -= buttonSize + dp(4f, scale)
			iconButton(draw, buttonX, y + dp(10f, scale), buttonSize, scale, "##carry_minus_$index", "-", TEXT) {
				if (carry.done > 0) carry.done--
				CarryStore.save()
			}

			buttonX -= dp(10f, scale)
			label(draw, counter, buttonX - textWidth(counter, dp(11f, scale)), y + dp(12f, scale), MUTED_TEXT, dp(11f, scale))

			progress(draw, x + dp(16f, scale), y + dp(34f, scale), width - dp(32f, scale), scale, carry.done, carry.ordered)
			y += rowHeight + dp(CARD_GAP, scale)
		}

		return y - top
	}

	/**
	 * The tier dropdown, shut.
	 *
	 * The options are drawn afterwards, over the finished cards, because a card
	 * clips its own contents and a popup that is clipped to the card it opened
	 * from is a popup nobody can use.
	 */
	private fun drawTierButton(draw: ImDrawList, scale: Float) {
		draw.addRectFilled(tierX, tierY, tierX + tierWidth, tierY + tierHeight, TRACK, tierHeight / 2f)
		val border = dp(2f, scale)
		draw.addRectFilled(
			tierX + border,
			tierY + border,
			tierX + tierWidth - border,
			tierY + tierHeight - border,
			SURFACE,
			(tierHeight - border * 2f) / 2f,
		)

		if (hit("##carry_tier", tierX, tierY, tierWidth, tierHeight)) tierOpen = !tierOpen
		val hovered = ImGui.isItemHovered()
		val color = if (tierOpen) ACCENT else if (hovered) TEXT else MUTED_TEXT

		val label = "Tier $tier"
		val fontSize = dp(10f, scale)
		val caretWidth = textWidth(FontAwesomeIcons.CARET_DOWN, dp(8f, scale))
		val labelWidth = textWidth(label, fontSize)
		val start = tierX + (tierWidth - labelWidth - caretWidth - dp(5f, scale)) / 2f
		label(draw, label, start, tierY + (tierHeight - ImGuiRuntime.textHeight(label, fontSize)) / 2f, color, fontSize)
		label(
			draw,
			FontAwesomeIcons.CARET_DOWN,
			start + labelWidth + dp(5f, scale),
			tierY + (tierHeight - ImGuiRuntime.textHeight(FontAwesomeIcons.CARET_DOWN, dp(8f, scale))) / 2f,
			color,
			dp(8f, scale),
		)
	}

	/** The open tier list, drawn last so nothing is painted over it. */
	private fun drawTierOptions(draw: ImDrawList, scale: Float, dt: Float) {
		val target = if (tierOpen) 1f else 0f
		tierProgress += (target - tierProgress) * (1f - exp(-DROPDOWN_SPEED * dt))
		if (kotlin.math.abs(tierProgress - target) < 0.01f) tierProgress = target
		if (tierProgress <= 0.001f) return

		val rowHeight = dp(18f, scale)
		val reveal = easeOutCubic(tierProgress)
		val listHeight = rowHeight * 3 * reveal
		val top = tierY + tierHeight + dp(2f, scale)

		draw.pushClipRect(tierX, top, tierX + tierWidth, top + listHeight, true)
		draw.addRectFilled(tierX, top, tierX + tierWidth, top + listHeight, TRACK, dp(8f, scale))
		val border = dp(2f, scale)
		draw.addRectFilled(
			tierX + border,
			top + border,
			tierX + tierWidth - border,
			top + listHeight - border,
			SURFACE,
			dp(6f, scale),
		)

		var row = 0
		for (value in 1..4) {
			if (value == tier) continue
			val rowY = top + rowHeight * row
			val pressed = hit("##carry_tier_$value", tierX + border, rowY, tierWidth - border * 2f, rowHeight)
			val hovered = ImGui.isItemHovered()
			if (pressed && tierProgress > 0.9f) {
				tier = value
				tierOpen = false
			}
			centered(draw, "Tier $value", tierX, rowY, tierWidth, rowHeight, if (hovered) TEXT else MUTED_TEXT, dp(10f, scale))
			row++
		}
		draw.popClipRect()
	}

	/**
	 * The amount field.
	 *
	 * Typed rather than stepped, because ordering twenty carries should not be
	 * twenty clicks. Digits only, and anything over the cap is pulled back to it
	 * as it is typed rather than refused — a field that silently eats a keypress
	 * reads as broken.
	 */
	private fun amountField(draw: ImDrawList, x: Float, y: Float, width: Float, height: Float, scale: Float) {
		val fontSize = dp(10f, scale)
		draw.addRectFilled(x, y, x + width, y + height, RENAME_FIELD, height / 2f)
		draw.addRect(x, y, x + width, y + height, if (editingAmount) ACCENT else DISABLED_BORDER, height / 2f, 0, dp(1f, scale))

		ImGui.setCursorScreenPos(x + dp(4f, scale), y)
		ImGui.setNextItemWidth(width - dp(8f, scale))
		ImGui.pushStyleColor(ImGuiCol.Text, TEXT)
		ImGui.pushStyleColor(ImGuiCol.FrameBg, 0)
		ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0)
		ImGui.pushStyleColor(ImGuiCol.FrameBgActive, 0)
		ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, ACCENT_DARK)
		ImGui.pushStyleColor(ImGuiCol.InputTextCursor, ACCENT)
		ImGui.pushFont(ImGuiRuntime.font, fontSize)
		ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, dp(6f, scale), ((height - fontSize) / 2f).coerceAtLeast(0f))
		ImGui.inputTextWithHint("##carry_amount", "1", amountBuffer, ImGuiInputTextFlags.CharsDecimal)
		editingAmount = ImGui.isItemActive()
		ImGui.popStyleVar()
		ImGui.popFont()
		ImGui.popStyleColor(6)

		val typed = amountBuffer.get()
		val parsed = typed.filter { it.isDigit() }.toIntOrNull()
		if (parsed != null && parsed > MAX_ORDER) amountBuffer.set(MAX_ORDER.toString())
	}

	/** What the amount field says, floored at one so Add always means something. */
	private fun amount(): Int = amountBuffer.get().toIntOrNull()?.coerceIn(1, MAX_ORDER) ?: 1

	/**
	 * The username field.
	 *
	 * A real Dear ImGui field under a box drawn here, which is how the settings
	 * screen does its text rows: the drawing has to match everything around it,
	 * and re-implementing a caret, a selection and a clipboard would not.
	 */
	private fun nameField(draw: ImDrawList, x: Float, y: Float, width: Float, height: Float, scale: Float) {
		val fontSize = dp(10f, scale)
		draw.addRectFilled(x, y, x + width, y + height, RENAME_FIELD, height / 2f)
		draw.addRect(x, y, x + width, y + height, if (editingName) ACCENT else DISABLED_BORDER, height / 2f, 0, dp(1f, scale))

		ImGui.setCursorScreenPos(x + dp(4f, scale), y)
		ImGui.setNextItemWidth(width - dp(8f, scale))
		ImGui.pushStyleColor(ImGuiCol.Text, TEXT)
		ImGui.pushStyleColor(ImGuiCol.FrameBg, 0)
		ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0)
		ImGui.pushStyleColor(ImGuiCol.FrameBgActive, 0)
		ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, ACCENT_DARK)
		ImGui.pushStyleColor(ImGuiCol.InputTextCursor, ACCENT)
		ImGui.pushFont(ImGuiRuntime.font, fontSize)
		ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, dp(6f, scale), ((height - fontSize) / 2f).coerceAtLeast(0f))
		ImGui.inputTextWithHint("##carry_name", "Username", nameBuffer)
		editingName = ImGui.isItemActive()
		ImGui.popStyleVar()
		ImGui.popFont()
		ImGui.popStyleColor(6)
	}

	/** Names in the lobby that start with what has been typed. */
	private fun lobbyMatches(): List<String> {
		val typed = nameBuffer.get().trim()
		if (typed.isEmpty()) return emptyList()
		val connection = Minecraft.getInstance().connection ?: return emptyList()
		return connection.onlinePlayers
			.map { it.profile.name }
			.filter { it.startsWith(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
			.sorted()
			.take(5)
	}

	private fun submit() {
		if (CarryManager.add(nameBuffer.get(), tier, amount())) {
			nameBuffer.clear()
			amountBuffer.set("1")
		}
	}

	private fun card(draw: ImDrawList, x: Float, y: Float, width: Float, height: Float, scale: Float) {
		draw.addRectFilled(x, y, x + width, y + height, SURFACE, dp(10f, scale))
	}

	/** One headline figure with its name underneath. */
	private fun stat(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float, value: String, name: String) {
		centered(draw, value, x, y + dp(16f, scale), width, dp(18f, scale), TEXT, dp(16f, scale))
		centered(draw, name, x, y + dp(38f, scale), width, dp(12f, scale), MUTED_TEXT, dp(9f, scale))
	}

	private fun progress(draw: ImDrawList, x: Float, y: Float, width: Float, scale: Float, done: Int, total: Int) {
		val height = dp(6f, scale)
		draw.addRectFilled(x, y, x + width, y + height, TRACK, height / 2f)
		val ratio = (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
		if (ratio > 0f) {
			draw.addRectFilled(x, y, x + (width * ratio).coerceAtLeast(height), y + height, ACCENT, height / 2f)
		}
	}

	private fun badge(draw: ImDrawList, text: String, x: Float, y: Float, scale: Float) {
		val fontSize = dp(9f, scale)
		val height = dp(14f, scale)
		val width = textWidth(text, fontSize) + dp(12f, scale)
		// The same corner radius the settings screen gives a keybind badge, which
		// is the other small label in the mod and the one this should match.
		draw.addRectFilled(x, y, x + width, y + height, SURFACE_RAISED, dp(4f, scale))
		centered(draw, text, x, y, width, height, MUTED_TEXT, fontSize)
	}

	private fun iconButton(
		draw: ImDrawList,
		x: Float,
		y: Float,
		size: Float,
		scale: Float,
		id: String,
		glyph: String,
		color: Int,
		action: () -> Unit,
	) {
		val hovered = ImGui.isMouseHoveringRect(x, y, x + size, y + size)
		draw.addRectFilled(x, y, x + size, y + size, if (hovered) SURFACE_ACTIVE else TRACK, size / 2f)
		centered(draw, glyph, x, y, size, size, color, dp(9.5f, scale))
		if (hit(id, x, y, size, size)) action()
	}

	private fun label(draw: ImDrawList, text: String, x: Float, y: Float, color: Int, size: Float) {
		draw.addText(ImGuiRuntime.font, size.roundToInt().coerceAtLeast(1), x, y, color, text)
	}

	private fun centered(
		draw: ImDrawList,
		text: String,
		x: Float,
		y: Float,
		width: Float,
		height: Float,
		color: Int,
		size: Float,
	) {
		val w = textWidth(text, size)
		val h = ImGuiRuntime.textHeight(text, size)
		label(draw, text, x + (width - w) / 2f, y + (height - h) / 2f, color, size)
	}

	private fun textWidth(text: String, size: Float): Float = ImGuiRuntime.textWidth(text, size)

	private fun hit(id: String, x: Float, y: Float, width: Float, height: Float): Boolean {
		ImGui.setCursorScreenPos(x, y)
		ImGui.setNextItemAllowOverlap()
		return ImGui.invisibleButton(id, width.coerceAtLeast(1f), height.coerceAtLeast(1f), ImGuiMouseButton.Left)
	}

	private fun easeOutCubic(value: Float): Float = 1f - (1f - value) * (1f - value) * (1f - value)

	private fun dp(value: Float, scale: Float) = value * scale

	/** Milliseconds as something readable, which past a minute is not seconds. */
	private fun duration(millis: Long): String {
		if (millis <= 0) return "-"
		val total = millis / 1000.0
		if (total < 60) return String.format(Locale.ROOT, "%.1fs", total)
		val minutes = (total / 60).toInt()
		return String.format(Locale.ROOT, "%dm %02ds", minutes, (total - minutes * 60).toInt())
	}

	companion object {
		private const val REFERENCE_HEIGHT = 1080f
		private const val REFERENCE_SCALE = 1.5f
		private const val PANEL_WIDTH = 420f
		private const val CONTENT_Y = 70f
		private const val CONTENT_BOTTOM_MARGIN = 12f
		private const val CARD_GAP = 8f
		private const val SCROLL_STEP = 34f
		private const val TAB_ANIMATION_SECONDS = 0.2f
		private const val DROPDOWN_SPEED = 22f

		/** Nobody orders more than this, and a field with no cap is a typo waiting. */
		private const val MAX_ORDER = 100

		private val TABS = listOf("Overview", "Voidgloom Seraph")
		private val TAB_IDS = List(TABS.size) { "##carry_tab_$it" }

		// Dear ImGui packs colors as ABGR, which is the settings screen's palette
		// read the same way round.
		private const val SURFACE_DARK = 0xFF141414.toInt()
		// Twenty, not twenty-five: the settings screen's cards are #141414, and a
		// second window one shade lighter reads as a different program.
		private const val SURFACE = 0xFF141414.toInt()
		private const val SURFACE_RAISED = 0xFF3F3F3F.toInt()
		private const val SURFACE_ACTIVE = 0xFF464646.toInt()
		private const val RENAME_FIELD = 0xFF1D1D1D.toInt()
		private const val DISABLED_BORDER = 0xFF2B2B2B.toInt()
		private const val TRACK = 0xFF2B2B2B.toInt()
		private const val TEXT = 0xFFE4E4E4.toInt()
		private const val NAV_TEXT = 0xFFD8D8D8.toInt()
		private const val MUTED_TEXT = 0xFFA4A4A4.toInt()
		private const val DELETE_TEXT = 0xFF4F4ADF.toInt()

		private val ACCENT: Int get() = ClickGui.accentAbgr()
		private val ACCENT_DARK: Int get() = ClickGui.accentDarkAbgr()

		/**
		 * Set when the window should open.
		 *
		 * The button that opens it is drawn inside the settings screen's own
		 * ImGui frame, and swapping Minecraft's screen from inside that frame
		 * tears down the screen that is mid-draw. The request is picked up by the
		 * client tick instead, one frame later.
		 */
		private var requested = false

		/**
		 * A warp the window asked for, waiting for a tick to send it.
		 *
		 * Deferred for the same reason opening is: shutting the screen from
		 * inside its own ImGui frame tears down the thing being drawn.
		 */
		private var pendingWarp: String? = null

		fun request() {
			requested = true
		}

		fun warp(command: String) {
			pendingWarp = command
		}

		/** Opens the window, or sends a warp, from the end of a client tick. */
		fun openIfRequested(client: Minecraft) {
			pendingWarp?.let { command ->
				pendingWarp = null
				client.connection?.sendCommand(command)
				// Warping is leaving, so the window goes with it.
				if (client.gui.screen() is CarryScreen) client.gui.setScreen(null)
			}

			if (!requested) return
			requested = false
			ImGuiRuntime.open(client) { CarryScreen() }
		}
	}
}
