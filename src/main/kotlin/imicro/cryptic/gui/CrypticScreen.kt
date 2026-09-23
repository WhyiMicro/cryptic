package imicro.cryptic.gui

import com.mojang.blaze3d.platform.InputConstants
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiColorEditFlags
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import imicro.cryptic.config.ConfigManager
import imicro.cryptic.config.ProfileEntry
import imicro.cryptic.feature.ClickGui
import imicro.cryptic.feature.Tooltips
import imicro.cryptic.hud.Hud
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.roundToInt
import java.nio.file.Path
import java.time.Duration

/**
 * Cryptic's settings menu, rendered entirely through Dear ImGui.
 *
 * The screen itself only asks Minecraft for its blurred background and owns
 * keyboard capture. [ImGuiRuntime] renders the controls at the end of the game
 * frame, after Minecraft has finished drawing that background.
 */
class CrypticScreen : Screen(Component.literal("Cryptic")), ImGuiScreen {
    private var selectedCategory = SESSION_SELECTED_CATEGORY
    private val expandedModules = SESSION_EXPANDED_MODULES
    private var awaitingKeybind: Module? = null

    /** Descriptions trimmed to the card, remade only when the card resizes. */
    private val fitted = HashMap<String, String>()
    private var fittedSize = 0f
    private var fittedWidth = 0f
    private var openDropdown: DropdownSource? = null
    private var draggingLowerRangeHandle = true

    /** The same, for a range that belongs to a setting rather than a demo card. */
    private var draggingLowerSettingHandle = true

    private var animatedTabX = Float.NaN
    private var tabAnimationStartX = Float.NaN
    private var tabContentProgress = 1f
    private var tabContentDirection = 0f
    /** What the cursor is resting on this frame, drawn once the rest is done. */
    private var tooltipText: String? = null

    private var openingProgress = 0f
    private var guiAlpha = 1f
    private var contentAlpha = 1f
    private var searchOpen = false
    private var searchProgress = 0f
    private val searchBuffer = ImString(128)
    private var searchFocusRequested = false
    private val expansion = mutableMapOf<Module, Float>()

    /**
     * How far the module list is scrolled, and how far it is allowed to go.
     *
     * The offset outlives the screen, the same as the open cards and the chosen
     * tab do: closing the menu to try a setting and opening it again should put
     * you back where you were looking, not at the top of a list you have
     * already scrolled past once.
     */
    private var scrollOffset: Float
        get() = SESSION_SCROLL_OFFSET
        set(value) { SESSION_SCROLL_OFFSET = value }

    /**
     * Kept alongside the offset, because the offset is clamped against it on
     * the frame before it has been recomputed. A fresh screen starting from
     * zero here would clamp the restored offset straight back to the top.
     */
    private var maxScroll: Float
        get() = SESSION_MAX_SCROLL
        set(value) { SESSION_MAX_SCROLL = value }

    private val togglePosition = mutableMapOf<Module, Float>()
    private val settingTogglePosition = mutableMapOf<ToggleModuleSetting, Float>()
    private val dropdownPosition = mutableMapOf<DropdownSource, Float>()
    private var renamingProfile: ProfileEntry? = null
    private val renameBuffer = ImString(256)
    private var renameFocusRequested = false
    private var renameFieldBounds: FloatArray? = null
    private var editingNumericId: String? = null
    private val numericBuffer = ImString(32)
    private var numericFocusRequested = false
    private val colorHexBuffers = mutableMapOf<ColorModuleSetting, ImString>()

    /** The swatch whose hex field is waiting to be focused, for one frame. */
    private var colorHexFocusRequested: ColorModuleSetting? = null
    private val textBuffers = mutableMapOf<TextModuleSetting, ImString>()
    private var editingTextId: String? = null

    // Cached view state. Rebuilding these per frame is what the menu used to do.
    private var cachedModules: List<Module> = emptyList()
    private var cachedModuleCategory: ModuleCategory? = null
    private var cachedModuleQuery: String? = null
    private val profileDescriptions = mutableMapOf<Path, String>()
    private var profileDescriptionsAt = 0L

    /** Input stays locked until every opening-animation frame has rendered. */
    private val openingComplete: Boolean
        get() = openingProgress >= 1f

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractBlurredBackground(graphics)
        graphics.fill(0, 0, width, height, 0x99000000.toInt())
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!openingComplete && event.key() != InputConstants.KEY_ESCAPE) return true
        ImGuiRuntime.key(event.key(), event.scancode(), event.modifiers(), true)

        if (editingNumericId != null && event.key() == InputConstants.KEY_ESCAPE) {
            editingNumericId = null
            numericBuffer.clear()
            return true
        }

        if (renamingProfile != null && event.key() == InputConstants.KEY_ESCAPE) {
            cancelProfileRename()
            return true
        }

        val module = awaitingKeybind
        if (module != null) {
            val key = if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_BACKSPACE) {
                null
            } else {
                runCatching {
                    InputConstants.getKey(event)
                }.getOrNull()
            }
            module.keybind.setKey(key)
            awaitingKeybind = null
            return true
        }
        if (searchOpen && event.key() == InputConstants.KEY_ESCAPE) {
            closeSearch()
            return true
        }
        // The shortcut every other search field in every other program uses.
        if (!searchOpen && event.key() == GLFW.GLFW_KEY_F && (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0) {
            openSearch()
            return true
        }
        return super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (!openingComplete) return true
        ImGuiRuntime.key(event.key(), event.scancode(), event.modifiers(), false)
        return super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!openingComplete) return true
        ImGuiRuntime.character(event.codepoint())
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        ImGuiRuntime.mousePosition(mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        ImGuiRuntime.mousePosition(event.x(), event.y())
        if (!openingComplete) return true

        // A bind waiting for a key takes a mouse button too, any of them, left
        // included: holding left click is how an auto clicker is normally run,
        // and a thumb button is the natural home for a zoom.
        //
        // Binding left does not cost the menu its own button. The badge arms on
        // the release of the click that hit it, so the press captured here is
        // always a later one; and in the menu a bind is only a name on a badge,
        // never something the menu itself reads.
        val module = awaitingKeybind
        if (module != null) {
            module.keybind.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()))
            awaitingKeybind = null
            return true
        }

        ImGuiRuntime.mouseButton(event.button(), true)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        ImGuiRuntime.mousePosition(event.x(), event.y())
        // Presses are withheld until the menu has finished opening, so the
        // matching releases are withheld too. Feeding Dear ImGui a release it
        // never saw pressed leaves its mouse state inconsistent.
        if (!openingComplete) return true
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
        if (!openingComplete) return true
        ImGuiRuntime.scroll(horizontalAmount, verticalAmount)
        return true
    }

    override fun isPauseScreen() = false

    override fun removed() {
        ImGuiRuntime.releaseInput()
        ConfigManager.flush()
        super.removed()
    }

    override fun drawImGui() {
        val io = ImGui.getIO()
        val displayWidth = io.displaySizeX
        val displayHeight = io.displaySizeY
        if (displayWidth <= 0f || displayHeight <= 0f) return

        // Match the reference's 1080p sizing and ignore ultrawide aspect ratio.
        // At 1080p the UI uses a 1.5x design scale; other resolutions scale by height.
        val scale = (displayHeight / REFERENCE_HEIGHT * REFERENCE_1080_SCALE).coerceAtLeast(0.75f)
        val dt = io.deltaTime.coerceIn(0f, 0.05f)
        openingProgress = (openingProgress + dt / OPENING_ANIMATION_SECONDS).coerceAtMost(1f)
        val openingAnimation = easeOutCubic(openingProgress)
        guiAlpha = openingAnimation
        val openingOffsetY = -dp(OPENING_ANIMATION_DISTANCE, scale) * (1f - openingAnimation)
        val interactive = openingComplete

        ImGui.setNextWindowPos(0f, 0f)
        ImGui.setNextWindowSize(displayWidth, displayHeight)
        val flags = ImGuiWindowFlags.NoDecoration or
            ImGuiWindowFlags.NoMove or
            ImGuiWindowFlags.NoSavedSettings or
            ImGuiWindowFlags.NoBackground or
            ImGuiWindowFlags.NoBringToFrontOnFocus or
            ImGuiWindowFlags.NoNavFocus

        tooltipText = null
        ImGui.begin("##cryptic_root", flags)
        val draw = ImGui.getWindowDrawList()
        drawNavigation(draw, displayWidth, scale, dt, openingOffsetY, interactive)
        drawModules(draw, displayWidth, displayHeight, scale, dt, openingOffsetY, interactive)
        // Last, so it lies over every card and popup drawn before it.
        if (interactive) drawTooltip(draw, displayWidth, displayHeight, scale)
        ImGui.end()
        ConfigManager.autosave()
        guiAlpha = 1f
    }

    private fun drawNavigation(
        draw: ImDrawList,
        displayWidth: Float,
        scale: Float,
        dt: Float,
        offsetY: Float,
        interactive: Boolean,
    ) {
        val panelWidth = dp(PANEL_WIDTH, scale)
        val panelX = (displayWidth - panelWidth) / 2f
        val panelY = dp(28f, scale) + offsetY
        val panelHeight = dp(30f, scale)
        val searchWidth = dp(SEARCH_NAV_WIDTH, scale)
        val hudWidth = dp(HUD_NAV_WIDTH, scale)
        val hudX = panelX + panelWidth - hudWidth
        val tabsX = panelX + searchWidth
        val tabWidth = (panelWidth - searchWidth - hudWidth) / ModuleCategory.entries.size

        draw.addRectFilled(panelX, panelY, panelX + panelWidth, panelY + panelHeight, guiColor(SURFACE_DARK), panelHeight / 2f)

        val searchClicked = interactive && hit("##module_search_toggle", panelX, panelY, searchWidth, panelHeight)
        val searchHovered = interactive && ImGui.isItemHovered()
        if (searchClicked) {
            if (searchOpen) closeSearch() else openSearch()
        }

        val searchTarget = if (searchOpen) 1f else 0f
        searchProgress = animate(searchProgress, searchTarget, SEARCH_ANIMATION_SPEED, dt)
        if (abs(searchProgress - searchTarget) < 0.001f) searchProgress = searchTarget
        val searchAnimation = easeOutCubic(searchProgress)

        // Handle the click before updating either animation so the tab highlight
        // and the incoming module cards begin moving on the exact same frame.
        ModuleCategory.entries.forEachIndexed { index, category ->
            val x = tabsX + index * tabWidth
            if (
                interactive && searchProgress <= 0.001f &&
                hit(CATEGORY_IDS[index], x, panelY, tabWidth, panelHeight) &&
                category != selectedCategory
            ) {
                tabContentDirection = if (category.ordinal > selectedCategory.ordinal) 1f else -1f
                tabContentProgress = 0f
                tabAnimationStartX = animatedTabX
                selectedCategory = category
                SESSION_SELECTED_CATEGORY = category
                expandedModules.clear()
                expansion.clear()
                scrollOffset = 0f
                dropdownPosition.clear()
                openDropdown = null
                editingNumericId = null
                numericBuffer.clear()
            }
        }

        val targetTabX = tabsX + selectedCategory.ordinal * tabWidth + dp(4f, scale)
        if (animatedTabX.isNaN()) {
            animatedTabX = targetTabX
            tabAnimationStartX = targetTabX
        }
        tabContentProgress = (tabContentProgress + dt / TAB_ANIMATION_SECONDS).coerceAtMost(1f)
        val tabAnimationProgress = easeOutCubic(tabContentProgress)
        animatedTabX = tabAnimationStartX + (targetTabX - tabAnimationStartX) * tabAnimationProgress
        val indicatorY = panelY + dp(4f, scale)
        val indicatorHeight = panelHeight - dp(8f, scale)
        val collapsedIndicatorWidth = tabWidth - dp(8f, scale)
        val expandedIndicatorX = panelX + dp(4f, scale)
        // The search field stops short of the HUD button, which stays reachable
        // whether or not the search is open.
        val expandedIndicatorWidth = panelWidth - dp(8f, scale) - hudWidth
        val indicatorX = animatedTabX + (expandedIndicatorX - animatedTabX) * searchAnimation
        val indicatorWidth = collapsedIndicatorWidth +
            (expandedIndicatorWidth - collapsedIndicatorWidth) * searchAnimation
        draw.addRectFilled(
            indicatorX,
            indicatorY,
            indicatorX + indicatorWidth,
            indicatorY + indicatorHeight,
            guiColor(SURFACE_ACTIVE),
            indicatorHeight / 2f,
        )

        val searchIconColor = when {
            searchOpen -> TEXT
            searchHovered -> TEXT
            else -> MUTED_TEXT
        }
        drawCenteredText(
            draw,
            FontAwesomeIcons.SEARCH,
            panelX,
            panelY,
            searchWidth,
            panelHeight,
            fadeColor(searchIconColor, maxOf(0.55f, searchAnimation)),
            dp(9.5f, scale),
        )

        val tabOpacity = 1f - searchAnimation
        ModuleCategory.entries.forEachIndexed { index, category ->
            val x = tabsX + index * tabWidth
            val textColor = if (category == ModuleCategory.DEVELOPER) ACCENT else NAV_TEXT
            drawCenteredText(
                draw,
                category.title,
                x,
                panelY,
                tabWidth,
                panelHeight,
                fadeColor(textColor, tabOpacity),
                dp(11f, scale),
            )
        }

        // Placing HUD elements is a different job from configuring modules, so
        // it gets its own door out of the menu rather than a card to find.
        val hudClicked = interactive && hit("##hud_editor_button", hudX, panelY, hudWidth, panelHeight)
        val hudHovered = interactive && ImGui.isItemHovered()
        drawCenteredText(
            draw,
            FontAwesomeIcons.MOVE,
            hudX,
            panelY,
            hudWidth,
            panelHeight,
            if (hudHovered) TEXT else MUTED_TEXT,
            dp(9.5f, scale),
        )
        if (hudClicked) {
            // Swapping the screen out from under Dear ImGui mid-frame is asking
            // for trouble, so the editor opens on the next tick instead. This
            // screen is handed over as the way back, so escape returns to the
            // menu with its tab and scroll intact rather than to the game.
            Minecraft.getInstance().execute { Hud.openEditor(this) }
        }

        if (searchOpen && searchProgress > 0.05f) {
            drawSearchField(panelX, panelY, panelWidth, panelHeight, searchWidth, hudWidth, scale, searchAnimation)
        }
    }

    private fun drawSearchField(
        panelX: Float,
        panelY: Float,
        panelWidth: Float,
        panelHeight: Float,
        searchWidth: Float,
        hudWidth: Float,
        scale: Float,
        opacity: Float,
    ) {
        val fieldX = panelX + searchWidth
        val fieldY = panelY + dp(4f, scale)
        val fieldWidth = panelWidth - searchWidth - hudWidth - dp(10f, scale)

        ImGui.setCursorScreenPos(fieldX, fieldY)
        ImGui.setNextItemWidth(fieldWidth)
        ImGui.pushStyleColor(ImGuiCol.Text, guiColor(TEXT, opacity))
        ImGui.pushStyleColor(ImGuiCol.TextDisabled, guiColor(MUTED_TEXT, opacity))
        ImGui.pushStyleColor(ImGuiCol.FrameBg, 0)
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0)
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, 0)
        ImGui.pushStyleColor(ImGuiCol.Border, 0)
        ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, guiColor(ACCENT_DARK, opacity))
        ImGui.pushStyleColor(ImGuiCol.InputTextCursor, guiColor(ACCENT, opacity))
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, panelHeight / 2f)
        pushFieldFont(dp(11f, scale), panelHeight - dp(8f, scale), dp(4f, scale))

        if (searchFocusRequested && searchProgress > 0.8f) {
            ImGui.setKeyboardFocusHere()
            searchFocusRequested = false
        }
        ImGui.inputTextWithHint("##module_search", "Search modules...", searchBuffer)

        popFieldFont()
        ImGui.popStyleVar(2)
        ImGui.popStyleColor(8)
    }

    private fun openSearch() {
        searchOpen = true
        searchBuffer.clear()
        searchFocusRequested = true
        awaitingKeybind = null
        openDropdown = null
        editingNumericId = null
        numericBuffer.clear()
    }

    private fun closeSearch() {
        searchOpen = false
        scrollOffset = 0f
        searchFocusRequested = false
        openDropdown = null
        editingNumericId = null
        numericBuffer.clear()
    }

    private fun drawModules(
        draw: ImDrawList,
        displayWidth: Float,
        displayHeight: Float,
        scale: Float,
        dt: Float,
        offsetY: Float,
        interactive: Boolean,
    ) {
        val panelWidth = dp(PANEL_WIDTH, scale)
        val panelX = (displayWidth - panelWidth) / 2f
        val columnGap = dp(COLUMN_GAP, scale)
        val cardGap = dp(CARD_GAP, scale)
        val cardWidth = (panelWidth - columnGap) / 2f
        val contentTop = dp(CONTENT_Y, scale)
        val contentBottom = displayHeight - dp(CONTENT_BOTTOM_MARGIN, scale)

        // A tall card can reach past the bottom of the screen, so the list
        // scrolls. The limit is the end of the longest column, which stops the
        // list being scrolled off into nothing.
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        val columnStart = contentTop + offsetY - scrollOffset
        val columnY = floatArrayOf(columnStart, columnStart)
        val entrance = easeOutCubic(tabContentProgress)
        val entranceOffset = dp(TAB_TRANSITION_DISTANCE, scale) * tabContentDirection * (1f - entrance)
        contentAlpha = entrance

        // Search results only belong to the actively open search field. When
        // it closes, restore the selected category immediately while the
        // navbar highlight is still animating back into its tab.
        val searchMode = searchOpen
        val contentInteractive = interactive && (!searchMode || (searchOpen && searchProgress >= 0.999f))

        if (!searchMode && selectedCategory == ModuleCategory.DEVELOPER) {
            drawProfiles(
                draw,
                panelX + entranceOffset,
                dp(CONTENT_Y, scale) + offsetY,
                panelWidth,
                scale,
                contentInteractive && tabContentProgress > 0.9f,
            )
            contentAlpha = 1f
            return
        }

        var dropdownOverlay: DropdownOverlay? = null

        val visibleModules = visibleModules(searchMode)

        // Cards are clipped to the list, so a scrolled one slides under the
        // navbar instead of drawing over it.
        draw.pushClipRect(panelX - columnGap, contentTop + offsetY, panelX + panelWidth + columnGap, contentBottom, true)

        if (searchMode && visibleModules.isEmpty()) {
            drawText(
                draw,
                "No modules found",
                panelX + dp(14f, scale),
                columnY[0] + dp(8f, scale),
                MUTED_TEXT,
                dp(10f, scale),
            )
        }

        visibleModules.forEachIndexed { index, module ->
            val column = index % 2
            val progress = animate(
                expansion.getOrPut(module) { if (module in expandedModules) 1f else 0f },
                if (module in expandedModules) 1f else 0f,
                18f,
                dt,
            )
            expansion[module] = progress
            val expandedHeight = expandedModuleHeight(module)
            val cardHeight = dp(COLLAPSED_HEIGHT + (expandedHeight - COLLAPSED_HEIGHT) * ease(progress), scale)
            val x = panelX + column * (cardWidth + columnGap) + entranceOffset
            val y = columnY[column]
            drawModule(draw, module, x, y, cardWidth, cardHeight, scale, dt, progress, contentInteractive)?.let {
                dropdownOverlay = it
            }
            columnY[column] += cardHeight + cardGap
        }
        draw.popClipRect()

        // Measured after laying out, because a card's height depends on whether
        // it is open and on how far its opening animation has got.
        val contentHeight = maxOf(columnY[0], columnY[1]) - columnStart - cardGap
        maxScroll = maxOf(0f, contentHeight - (contentBottom - contentTop))
        if (interactive) applyScrollInput(panelX, panelWidth, contentTop + offsetY, contentBottom, scale)

        dropdownOverlay?.let { drawDropdownOverlay(draw, it, interactive) }
        contentAlpha = 1f
    }

    /**
     * Turns the wheel into a scroll, while the pointer is over the list.
     *
     * Nothing happens when everything already fits, so a short page cannot be
     * nudged loose, and the offset is held inside the list's own length.
     */
    private fun applyScrollInput(
        panelX: Float,
        panelWidth: Float,
        top: Float,
        bottom: Float,
        scale: Float,
    ) {
        if (maxScroll <= 0f) {
            scrollOffset = 0f
            return
        }

        val wheel = ImGui.getIO().mouseWheel
        if (wheel == 0f) return
        if (!ImGui.isMouseHoveringRect(panelX, top, panelX + panelWidth, bottom)) return

        scrollOffset = (scrollOffset - wheel * dp(SCROLL_STEP, scale)).coerceIn(0f, maxScroll)
    }

    /**
     * The card list only changes when the tab or the search query changes, so it
     * is filtered and sorted once instead of on every frame the menu is open.
     */
    private fun visibleModules(searchMode: Boolean): List<Module> {
        val query = if (searchMode) searchBuffer.get().trim() else null
        val category = if (searchMode) null else selectedCategory
        if (category == cachedModuleCategory && query == cachedModuleQuery) return cachedModules

        cachedModuleCategory = category
        cachedModuleQuery = query
        cachedModules = MODULES
            .filter { module ->
                when {
                    query == null -> module.category == category
                    query.isEmpty() -> true
                    else -> module.name.contains(query, ignoreCase = true) ||
                        module.description.contains(query, ignoreCase = true)
                }
            }
            .sortedBy(Module::sortKey)
        return cachedModules
    }

    private fun drawProfiles(
        draw: ImDrawList,
        panelX: Float,
        startY: Float,
        panelWidth: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        var allowInteractions = interactive
        val renameBounds = renameFieldBounds
        if (
            interactive &&
            renamingProfile != null &&
            renameBounds != null &&
            ImGui.isMouseClicked(ImGuiMouseButton.Left) &&
            !ImGui.isMouseHoveringRect(renameBounds[0], renameBounds[1], renameBounds[2], renameBounds[3])
        ) {
            finishProfileRename()
            // The click that commits a rename should not also trigger the
            // control underneath it.
            allowInteractions = false
        }

        val cardGap = dp(CARD_GAP, scale)
        val cardHeight = dp(PROFILE_CARD_HEIGHT, scale)
        var cardY = startY
        val profiles = ConfigManager.profiles()

        profiles.forEach { profile ->
            drawProfileCard(draw, profile, panelX, cardY, panelWidth, cardHeight, scale, allowInteractions)
            cardY += cardHeight + cardGap
        }

        drawNewProfileCard(draw, panelX, cardY, panelWidth, cardHeight, scale, allowInteractions)
    }

    private fun drawProfileCard(
        draw: ImDrawList,
        profile: ProfileEntry,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        draw.addRectFilled(x, y, x + width, y + height, contentColor(SURFACE), dp(12f, scale))

        val actionsWidth = dp(100f, scale)
        val isRenaming = renamingProfile?.path == profile.path
        val titleX = x + dp(12f, scale)
        val titleY = y + dp(10f, scale)
        val titleSize = dp(11f, scale)
        val titleHitWidth = textWidth(profile.name, titleSize) + dp(6f, scale)
        val titleHovered = interactive && !profile.builtIn && !isRenaming && ImGui.isMouseHoveringRect(
            titleX - dp(2f, scale),
            titleY - dp(2f, scale),
            titleX + titleHitWidth,
            titleY + dp(17f, scale),
        )
        if (titleHovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            beginProfileRename(profile)
        }

        val cardClicked = !isRenaming && interactive && hit(
            profile.widgetIds.card,
            x,
            y,
            width - actionsWidth,
            height,
        ) && !titleHovered
        if (cardClicked && !profile.active && !isRenaming) {
            ConfigManager.loadProfile(profile).fold(
                onSuccess = { showToast("Loaded ${profile.name}") },
                onFailure = { showToast(it.message ?: "Could not load profile", true) },
            )
        }

        if (isRenaming) {
            // Sized and styled like the text setting on a module card, so
            // starting a rename changes the words in the card and nothing else
            // about it.
            val fieldX = titleX - dp(4f, scale)
            val fieldY = titleY - dp(4f, scale)
            val availableWidth = width - actionsWidth - dp(24f, scale)
            val fieldWidth = minOf(dp(140f, scale), availableWidth).coerceAtLeast(dp(90f, scale))
            val fieldHeight = dp(TEXT_FIELD_HEIGHT, scale)
            val border = dp(1f, scale)
            val radius = dp(3f, scale)
            renameFieldBounds = floatArrayOf(fieldX, fieldY, fieldX + fieldWidth, fieldY + fieldHeight)

            ImGui.setCursorScreenPos(fieldX, fieldY)
            ImGui.setNextItemWidth(fieldWidth)
            ImGui.pushStyleColor(ImGuiCol.Text, contentColor(TEXT))
            ImGui.pushStyleColor(ImGuiCol.FrameBg, contentColor(RENAME_FIELD))
            ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, contentColor(RENAME_FIELD))
            ImGui.pushStyleColor(ImGuiCol.FrameBgActive, contentColor(RENAME_FIELD))
            ImGui.pushStyleColor(ImGuiCol.Border, contentColor(ACCENT))
            ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, contentColor(ACCENT_DARK))
            ImGui.pushStyleColor(ImGuiCol.InputTextCursor, contentColor(ACCENT))
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, radius)
            ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, border)
            pushFieldFont(dp(TEXT_FIELD_FONT, scale), fieldHeight, dp(6f, scale))
            if (renameFocusRequested) {
                ImGui.setKeyboardFocusHere()
                renameFocusRequested = false
            }
            val submitted = ImGui.inputText(
                profile.widgetIds.rename,
                renameBuffer,
                ImGuiInputTextFlags.EnterReturnsTrue,
            )
            popFieldFont()
            ImGui.popStyleVar(2)
            ImGui.popStyleColor(7)
            if (submitted) finishProfileRename()
        } else {
            drawText(draw, profile.name, titleX, titleY, if (titleHovered) ACCENT else TEXT, titleSize)
        }

        if (profile.active && !isRenaming) {
            val badgeText = "ACTIVE"
            val badgeX = titleX + textWidth(profile.name, titleSize) + dp(5f, scale)
            val badgeWidth = textWidth(badgeText, dp(7f, scale)) + dp(10f, scale)
            val badgeHeight = dp(13f, scale)
            draw.addRectFilled(
                badgeX,
                y + dp(9f, scale),
                badgeX + badgeWidth,
                y + dp(9f, scale) + badgeHeight,
                contentColor(ACCENT_DARK),
                dp(4f, scale),
            )
            drawCenteredText(
                draw,
                badgeText,
                badgeX,
                y + dp(9f, scale),
                badgeWidth,
                badgeHeight,
                ACCENT,
                dp(7f, scale),
            )
        }

        drawText(
            draw,
            profileDescription(profile),
            titleX,
            y + dp(29f, scale),
            MUTED_TEXT,
            dp(9.5f, scale),
        )

        val actionSize = dp(19f, scale)
        val actionGap = dp(3f, scale)
        val actionY = y + (height - actionSize) / 2f
        var actionX = x + width - dp(10f, scale) - actionSize * 4f - actionGap * 3f

        if (drawProfileIconButton(draw, FontAwesomeIcons.UPLOAD, profile.widgetIds.export, actionX, actionY, actionSize, scale, interactive)) {
            ConfigManager.exportProfile(profile).fold(
                onSuccess = {
                    ImGui.setClipboardText(it)
                    showToast("Copied ${profile.name} to clipboard")
                },
                onFailure = { showToast(it.message ?: "Could not export profile", true) },
            )
        }
        actionX += actionSize + actionGap

        val canImport = interactive && profile.active && !profile.builtIn
        if (drawProfileIconButton(draw, FontAwesomeIcons.DOWNLOAD, profile.widgetIds.import, actionX, actionY, actionSize, scale, canImport)) {
            ConfigManager.importIntoProfile(profile, ImGui.getClipboardText()).fold(
                onSuccess = { name -> showToast("Imported $name") },
                onFailure = { showToast(it.message ?: "Could not import profile", true) },
            )
        }
        actionX += actionSize + actionGap

        if (drawProfileIconButton(draw, FontAwesomeIcons.FOLDER, profile.widgetIds.folder, actionX, actionY, actionSize, scale, interactive)) {
            ConfigManager.openProfileDirectory().onFailure {
                showToast(it.message ?: "Could not open profile folder", true)
            }
        }
        actionX += actionSize + actionGap

        if (!profile.builtIn && drawProfileIconButton(
                draw,
                FontAwesomeIcons.TRASH,
                profile.widgetIds.delete,
                actionX,
                actionY,
                actionSize,
                scale,
                interactive,
                destructive = true,
            )
        ) {
            ConfigManager.deleteProfile(profile).fold(
                onSuccess = { showToast("Deleted ${profile.name}") },
                onFailure = { showToast(it.message ?: "Could not delete profile", true) },
            )
        }
    }

    private fun drawNewProfileCard(
        draw: ImDrawList,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        val clicked = interactive && hit("##new_profile", x, y, width, height)
        val hovered = interactive && ImGui.isItemHovered()
        draw.addRectFilled(x, y, x + width, y + height, contentColor(SURFACE), dp(12f, scale))
        drawCenteredText(
            draw,
            "${FontAwesomeIcons.PLUS}  New profile",
            x,
            y,
            width,
            height,
            if (hovered) TEXT else MUTED_TEXT,
            dp(9f, scale),
        )
        if (clicked) {
            ConfigManager.createProfile().fold(
                onSuccess = { showToast("Created $it") },
                onFailure = { showToast(it.message ?: "Could not create profile", true) },
            )
        }
    }

    private fun drawProfileIconButton(
        draw: ImDrawList,
        icon: String,
        id: String,
        x: Float,
        y: Float,
        size: Float,
        scale: Float,
        enabled: Boolean,
        destructive: Boolean = false,
    ): Boolean {
        val clicked = enabled && hit(id, x, y, size, size)
        val hovered = enabled && ImGui.isItemHovered()
        val color = when {
            !enabled -> DISABLED_TEXT
            destructive && hovered -> DELETE_HOVER
            destructive -> DELETE_TEXT
            hovered -> TEXT
            else -> MUTED_TEXT
        }
        drawCenteredText(draw, icon, x, y, size, size, color, dp(9.5f, scale))
        return clicked
    }

    /**
     * The relative timestamp only changes by the minute, so it is rebuilt on a
     * timer rather than once per profile card per frame.
     */
    private fun profileDescription(profile: ProfileEntry): String {
        val now = System.currentTimeMillis()
        if (now - profileDescriptionsAt >= PROFILE_DESCRIPTION_REFRESH_MS) {
            profileDescriptions.clear()
            profileDescriptionsAt = now
        }
        return profileDescriptions.getOrPut(profile.path) { describeProfile(profile, now) }
    }

    private fun describeProfile(profile: ProfileEntry, now: Long): String {
        if (profile.builtIn) return "Contains the default configuration."
        val elapsed = Duration.ofMillis((now - profile.modifiedAt).coerceAtLeast(0L))
        return when {
            elapsed.toMinutes() < 1 -> "Modified just now."
            elapsed.toHours() < 1 -> "Modified ${elapsed.toMinutes()} minutes ago."
            elapsed.toDays() < 1 -> "Modified ${elapsed.toHours()} hours ago."
            elapsed.toDays() == 1L -> "Modified yesterday."
            else -> "Modified ${elapsed.toDays()} days ago."
        }
    }

    private fun beginProfileRename(profile: ProfileEntry) {
        renamingProfile = profile
        renameBuffer.set(profile.name)
        renameFocusRequested = true
        renameFieldBounds = null
    }

    private fun finishProfileRename() {
        val profile = renamingProfile ?: return
        val requestedName = renameBuffer.get()
        cancelProfileRename()
        ConfigManager.renameProfile(profile, requestedName).fold(
            onSuccess = { showToast("Renamed profile to $it") },
            onFailure = { showToast(it.message ?: "Could not rename profile", true) },
        )
    }

    private fun cancelProfileRename() {
        renamingProfile = null
        renameBuffer.clear()
        renameFocusRequested = false
        renameFieldBounds = null
    }

    /**
     * Raises a notification about something the menu just did.
     *
     * Handed to [imicro.cryptic.feature.Toasts], which draws on Dear ImGui's
     * foreground list rather than inside this screen — so a profile loaded on
     * the way out is still confirmed after the menu has gone, which is exactly
     * when you would be looking for the confirmation.
     */
    private fun showToast(message: String, error: Boolean = false) {
        imicro.cryptic.feature.Toasts.show("Profiles", message, error)
    }


    private fun drawModule(
        draw: ImDrawList,
        module: Module,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        scale: Float,
        dt: Float,
        expandedProgress: Float,
        interactive: Boolean,
    ): DropdownOverlay? {
        draw.addRectFilled(x, y, x + width, y + height, contentColor(SURFACE), dp(12f, scale))
        draw.pushClipRect(x, y, x + width, y + height, true)

        val titleSize = dp(11f, scale)
        val smallSize = dp(8f, scale)
        val titleX = x + dp(14f, scale)
        val titleY = y + dp(8f, scale)
        drawText(draw, module.name, titleX, titleY, TEXT, titleSize)

        if (module.supportsKeybind) {
            val badgeText = if (awaitingKeybind == module) "..." else module.keybind.keyName.uppercase()
            val hasKeybind = module.keybind.keyName != "None"
            val titleWidth = textWidth(module.name, titleSize)
            val badgeX = titleX + titleWidth + dp(KEYBIND_GAP, scale)
            val badgeWidth = maxOf(dp(KEYBIND_MIN_WIDTH, scale), textWidth(badgeText, smallSize) + dp(12f, scale))
            val badgeY = y + dp(8f, scale)
            val badgeHeight = dp(14f, scale)
            draw.addRectFilled(
                badgeX,
                badgeY,
                badgeX + badgeWidth,
                badgeY + badgeHeight,
                contentColor(if (awaitingKeybind == module || hasKeybind) ACCENT_DARK else SURFACE_RAISED),
                dp(4f, scale),
            )
            if (interactive && hit(module.widgetIds.keybind, badgeX, badgeY, badgeWidth, badgeHeight)) {
                awaitingKeybind = module
            }
            drawCenteredText(
                draw,
                badgeText,
                badgeX,
                badgeY,
                badgeWidth,
                badgeHeight,
                if (awaitingKeybind == module || hasKeybind) ACCENT else MUTED_TEXT,
                smallSize,
            )
        }

        if (module.supportsToggle) {
            drawToggle(draw, module, x + width - dp(45f, scale), y + dp(9f, scale), scale, dt, interactive)
        }

        val descriptionX = if (module.hasSettings) x + dp(37f, scale) else titleX
        if (module.hasSettings) {
            val expandX = x + dp(14f, scale)
            val expandY = y + dp(25f, scale)
            val expandSize = dp(15f, scale)
            draw.addRectFilled(expandX, expandY, expandX + expandSize, expandY + expandSize, contentColor(SURFACE_RAISED), dp(4f, scale))
            if (interactive && hit(module.widgetIds.expand, expandX, expandY, expandSize, expandSize)) {
                if (!expandedModules.add(module)) expandedModules.remove(module)
                if (module !in expandedModules && module.owns(openDropdown)) openDropdown = null
            }
            drawCenteredText(
                draw,
                if (module in expandedModules) "-" else "+",
                expandX,
                expandY,
                expandSize,
                expandSize,
                TEXT,
                dp(9f, scale),
            )
        }
        // Clipped to the card. A description is one line and cards are a fixed
        // width, so a long one used to run out past the right edge and carry on
        // over whatever was beside it.
        val descriptionSize = dp(9.5f, scale)
        val descriptionRoom = (x + width - dp(14f, scale)) - descriptionX
        drawText(
            draw,
            fitToWidth(module.id, module.description, descriptionSize, descriptionRoom),
            descriptionX,
            y + dp(28f, scale),
            MUTED_TEXT,
            descriptionSize,
        )

        var settingsOverlay: DropdownOverlay? = null
        var dropdownHovered = false
        if (module.hasSettings && expandedProgress > 0.02f) {
            if (module.settings.isNotEmpty()) {
                settingsOverlay = drawCustomSettings(
                    draw,
                    module,
                    x,
                    y,
                    width,
                    scale,
                    dt,
                    interactive && expandedProgress > 0.9f,
                    expandedProgress > 0.9f,
                )
            } else if (module.hasDemoSettings) {
                drawText(draw, "Main", x + dp(14f, scale), y + dp(53f, scale), MUTED_TEXT, dp(9f, scale))
                val settingsInteractive = interactive && expandedProgress > 0.9f
                drawSlider(draw, module, x, y, width, scale, settingsInteractive)
                drawRangeSlider(draw, module, x, y, width, scale, settingsInteractive)
                dropdownHovered = drawDropdownButton(draw, module, x, y, width, scale, settingsInteractive)
            }
        }
        draw.popClipRect()

        if (!module.hasDemoSettings) return settingsOverlay

        return dropdownOverlayFor(
            source = module.dropdown,
            x = x + dp(DROPDOWN_X, scale),
            y = y + dp(114f, scale),
            width = width - dp(DROPDOWN_X + 14f, scale),
            scale = scale,
            dt = dt,
            expanded = expandedProgress > 0.9f,
            buttonHovered = dropdownHovered,
        )
    }

    private fun drawToggle(
        draw: ImDrawList,
        module: Module,
        x: Float,
        y: Float,
        scale: Float,
        dt: Float,
        interactive: Boolean,
    ) {
        val width = dp(32f, scale)
        val height = dp(15f, scale)
        val progress = animate(
            togglePosition.getOrPut(module) { if (module.enabled) 1f else 0f },
            if (module.enabled) 1f else 0f,
            22f,
            dt,
        )
        togglePosition[module] = progress
        draw.addRectFilled(x, y, x + width, y + height, contentColor(if (module.enabled) ACCENT else TOGGLE_OFF), height / 2f)
        val radius = dp(5.5f, scale)
        val knobX = x + dp(2.5f, scale) + radius + (width - dp(5f, scale) - radius * 2f) * progress
        draw.addCircleFilled(knobX, y + height / 2f, radius, contentColor(KNOB), 32)
        if (interactive && hit(module.widgetIds.toggle, x, y, width, height)) {
            module.enabled = !module.enabled
        }
    }

    private fun expandedModuleHeight(module: Module): Float {
        val settings = module.settings
        if (settings.isEmpty()) return EXPANDED_HEIGHT

        var rows = 0f
        var firstVisible = true
        for (index in settings.indices) {
            val setting = settings[index]
            if (!setting.isVisible()) continue
            val isFirst = firstVisible
            firstVisible = false
            rows += when (setting) {
                is SliderModuleSetting -> CUSTOM_SLIDER_ROW_HEIGHT
                is RangeModuleSetting -> CUSTOM_SLIDER_ROW_HEIGHT
                is ToggleModuleSetting -> CUSTOM_TOGGLE_ROW_HEIGHT
                is ButtonModuleSetting -> CUSTOM_BUTTON_ROW_HEIGHT
                is ColorModuleSetting -> CUSTOM_COLOR_ROW_HEIGHT
                is DropdownModuleSetting -> CUSTOM_DROPDOWN_ROW_HEIGHT
                is TextModuleSetting -> CUSTOM_TEXT_ROW_HEIGHT
                // Made by pointing at slots in the inventory, so it has no row.
                is SlotMapModuleSetting -> 0f
                // The first heading takes the place of the implicit one, so it
                // is the later ones that add height.
                is SectionModuleSetting -> if (isFirst) 0f else CUSTOM_SECTION_GAP + CUSTOM_SECTION_HEIGHT
            }
        }
        return CUSTOM_SETTINGS_Y + CUSTOM_SECTION_HEIGHT + rows + CUSTOM_BOTTOM_PADDING
    }

    private fun drawCustomSettings(
        draw: ImDrawList,
        module: Module,
        cardX: Float,
        cardY: Float,
        cardWidth: Float,
        scale: Float,
        dt: Float,
        interactive: Boolean,
        expanded: Boolean,
    ): DropdownOverlay? {
        val settingsY = cardY + dp(CUSTOM_SETTINGS_Y, scale)
        val firstSetting = module.settings.firstOrNull { it.isVisible() }
        // A module that names its own first group gets that name instead of the
        // implicit one, rather than both.
        val leadingSection = firstSetting as? SectionModuleSetting
        drawSectionHeading(draw, leadingSection?.label ?: "Main", cardX, settingsY, cardWidth, scale)
        var rowY = cardY + dp(CUSTOM_SETTINGS_Y + CUSTOM_SECTION_HEIGHT, scale)

        // Only one dropdown can be open at a time, so a card never has more than
        // one popup to hand back.
        var overlay: DropdownOverlay? = null

        // Indexed so that redrawing a card does not allocate an iterator or a
        // filtered copy of its settings on every frame.
        val settings = module.settings
        for (index in settings.indices) {
            val setting = settings[index]
            if (!setting.isVisible()) continue

            val rowTop = rowY
            when (setting) {
                is SliderModuleSetting -> {
                    drawCustomSlider(draw, setting, cardX, rowY, cardWidth, scale, interactive)
                    rowY += dp(CUSTOM_SLIDER_ROW_HEIGHT, scale)
                }
                is RangeModuleSetting -> {
                    drawCustomRange(draw, setting, cardX, rowY, cardWidth, scale, interactive)
                    rowY += dp(CUSTOM_SLIDER_ROW_HEIGHT, scale)
                }
                is ToggleModuleSetting -> {
                    drawText(draw, setting.label, cardX + dp(14f, scale), rowY + dp(3f, scale), TEXT, dp(9.5f, scale))
                    drawSettingToggle(
                        draw,
                        setting,
                        cardX + cardWidth - dp(45f, scale),
                        rowY + dp(1f, scale),
                        scale,
                        dt,
                        interactive,
                    )
                    rowY += dp(CUSTOM_TOGGLE_ROW_HEIGHT, scale)
                }
                is ButtonModuleSetting -> {
                    val buttonX = cardX + dp(SLIDER_X, scale)
                    val buttonY = rowY + dp(1f, scale)
                    val buttonWidth = cardWidth - dp(SLIDER_X + 14f, scale)
                    val buttonHeight = dp(20f, scale)
                    outlinedPill(draw, buttonX, buttonY, buttonWidth, buttonHeight, scale)
                    val clicked = interactive && hit(setting.widgetIds.control, buttonX, buttonY, buttonWidth, buttonHeight)
                    val hovered = interactive && ImGui.isItemHovered()
                    drawCenteredText(
                        draw,
                        setting.label,
                        buttonX,
                        buttonY,
                        buttonWidth,
                        buttonHeight,
                        if (hovered) TEXT else MUTED_TEXT,
                        dp(9.5f, scale),
                    )
                    if (clicked) setting.action()
                    rowY += dp(CUSTOM_BUTTON_ROW_HEIGHT, scale)
                }
                is ColorModuleSetting -> {
                    drawColorSetting(draw, setting, cardX, rowY, cardWidth, scale, interactive)
                    rowY += dp(CUSTOM_COLOR_ROW_HEIGHT, scale)
                }
                is DropdownModuleSetting -> {
                    val boxX = cardX + dp(SLIDER_X, scale)
                    val boxY = rowY + dp(1f, scale)
                    val boxWidth = cardWidth - dp(SLIDER_X + 14f, scale)
                    drawText(draw, setting.label, cardX + dp(14f, scale), rowY + dp(5f, scale), TEXT, dp(9.5f, scale))
                    val hovered = drawDropdownControl(draw, setting, boxX, boxY, boxWidth, scale, interactive)
                    dropdownOverlayFor(setting, boxX, boxY, boxWidth, scale, dt, expanded, hovered)?.let {
                        overlay = it
                    }
                    rowY += dp(CUSTOM_DROPDOWN_ROW_HEIGHT, scale)
                }
                is TextModuleSetting -> {
                    drawTextSetting(draw, setting, cardX, rowY, cardWidth, scale, interactive)
                    rowY += dp(CUSTOM_TEXT_ROW_HEIGHT, scale)
                }
                // Nothing to draw: the binds are made in the inventory.
                is SlotMapModuleSetting -> Unit
                is SectionModuleSetting -> {
                    // The leading heading was already drawn above the rows.
                    if (setting !== leadingSection) {
                        rowY += dp(CUSTOM_SECTION_GAP, scale)
                        drawSectionHeading(draw, setting.label, cardX, rowY, cardWidth, scale)
                        rowY += dp(CUSTOM_SECTION_HEIGHT, scale)
                    }
                }
            }

            // Whatever the row turned out to be, the cursor resting anywhere
            // on it asks for the sentence the setting carries.
            noteTooltip(setting, cardX, rowTop, cardWidth, rowY - rowTop)
        }
        return overlay
    }

    /**
     * A group's name with a rule under it, so a long card reads as a few short
     * lists rather than one run of controls.
     */
    private fun drawSectionHeading(
        draw: ImDrawList,
        label: String,
        cardX: Float,
        rowY: Float,
        cardWidth: Float,
        scale: Float,
    ) {
        val textX = cardX + dp(14f, scale)
        val fontSize = dp(9f, scale)
        drawText(draw, label, textX, rowY, MUTED_TEXT, fontSize)

        val lineY = rowY + fontSize + dp(4f, scale)
        val thickness = dp(1f, scale)
        draw.addRectFilled(
            textX,
            lineY,
            cardX + cardWidth - dp(14f, scale),
            lineY + thickness,
            contentColor(DIVIDER),
        )
    }

    /**
     * A full-width text field. The label sits on its own line above it, because
     * free text needs far more room than a value a slider or swatch shows.
     */
    private fun drawTextSetting(
        draw: ImDrawList,
        setting: TextModuleSetting,
        cardX: Float,
        rowY: Float,
        cardWidth: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        drawText(draw, setting.label, cardX + dp(14f, scale), rowY + dp(1f, scale), TEXT, dp(9.5f, scale))

        val fieldX = cardX + dp(14f, scale)
        val fieldY = rowY + dp(TEXT_LABEL_HEIGHT, scale)
        val fieldWidth = cardWidth - dp(28f, scale)
        val fieldHeight = dp(TEXT_FIELD_HEIGHT, scale)
        val fontSize = dp(TEXT_FIELD_FONT, scale)
        val textX = fieldX + dp(6f, scale)
        val textY = fieldY + (fieldHeight - fontSize) / 2f
        val key = setting.widgetIds.editorKey

        val buffer = textBuffers.getOrPut(setting) { ImString(setting.maxLength + 1) }
        // The field is only refilled while it is not being edited, so typing is
        // never overwritten by the value it is in the middle of changing.
        if (editingTextId != key && buffer.get() != setting.value) buffer.set(setting.value)

        if (!interactive) {
            // The card is still animating open, so the field is drawn rather
            // than submitted. Both halves use one font size and one box, so the
            // row does not visibly resize when the animation hands over.
            draw.addRectFilled(fieldX, fieldY, fieldX + fieldWidth, fieldY + fieldHeight, contentColor(RENAME_FIELD), dp(3f, scale))
            drawText(draw, setting.value, textX, textY, MUTED_TEXT, fontSize)
            return
        }

        ImGui.setCursorScreenPos(fieldX, fieldY)
        ImGui.setNextItemWidth(fieldWidth)
        ImGui.pushStyleColor(ImGuiCol.Text, contentColor(TEXT))
        ImGui.pushStyleColor(ImGuiCol.FrameBg, contentColor(RENAME_FIELD))
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, contentColor(RENAME_FIELD))
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, contentColor(RENAME_FIELD))
        ImGui.pushStyleColor(ImGuiCol.Border, contentColor(if (editingTextId == key) ACCENT else DISABLED_BORDER))
        ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, contentColor(ACCENT_DARK))
        ImGui.pushStyleColor(ImGuiCol.InputTextCursor, contentColor(ACCENT))
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, dp(3f, scale))
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, dp(1f, scale))
        pushFieldFont(fontSize, fieldHeight, dp(6f, scale))
        ImGui.inputTextWithHint(setting.widgetIds.editorInput, setting.hint, buffer)
        // Tracked from isItemActive rather than the activate/deactivate pair, so
        // losing focus any way at all still releases the field.
        val active = ImGui.isItemActive()
        popFieldFont()
        ImGui.popStyleVar(2)
        ImGui.popStyleColor(7)

        if (active) editingTextId = key else if (editingTextId == key) editingTextId = null
        if (buffer.get() != setting.value) setting.value = buffer.get()
    }

    private fun drawColorSetting(
        draw: ImDrawList,
        setting: ColorModuleSetting,
        cardX: Float,
        rowY: Float,
        cardWidth: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        drawText(draw, setting.label, cardX + dp(14f, scale), rowY + dp(3f, scale), TEXT, dp(9.5f, scale))

        val swatchWidth = dp(47f, scale)
        val swatchHeight = dp(18f, scale)
        val swatchX = cardX + cardWidth - dp(14f, scale) - swatchWidth
        val swatchY = rowY
        val popupId = setting.widgetIds.picker
        val hovered = interactive && ImGui.isMouseHoveringRect(
            swatchX,
            swatchY,
            swatchX + swatchWidth,
            swatchY + swatchHeight,
        )

        draw.addRectFilled(
            swatchX,
            swatchY,
            swatchX + swatchWidth,
            swatchY + swatchHeight,
            contentColor(if (hovered) TEXT else TRACK),
            swatchHeight / 2f,
        )
        draw.addRectFilled(
            swatchX + dp(1.5f, scale),
            swatchY + dp(1.5f, scale),
            swatchX + swatchWidth - dp(1.5f, scale),
            swatchY + swatchHeight - dp(1.5f, scale),
            contentColor(setting.abgr),
            (swatchHeight - dp(3f, scale)) / 2f,
        )

        if (interactive && hit(setting.widgetIds.control, swatchX, swatchY, swatchWidth, swatchHeight)) {
            colorHexBuffers.getOrPut(setting) { ImString(9) }.set(setting.hexDigits)
            // Focused and selected on open, so a hex on the clipboard can go
            // straight in with one paste.
            colorHexFocusRequested = setting
            ImGui.openPopup(popupId)
        }

        ImGui.pushStyleColor(ImGuiCol.Text, contentColor(TEXT))
        ImGui.pushStyleColor(ImGuiCol.PopupBg, contentColor(SURFACE))
        ImGui.pushStyleColor(ImGuiCol.Border, contentColor(TRACK))
        ImGui.pushStyleColor(ImGuiCol.FrameBg, contentColor(SURFACE_RAISED))
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, contentColor(SURFACE_ACTIVE))
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, contentColor(SURFACE_ACTIVE))
        ImGui.pushStyleColor(ImGuiCol.Button, contentColor(SURFACE_RAISED))
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, contentColor(SURFACE_ACTIVE))
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, contentColor(ACCENT_DARK))
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, dp(9f, scale))
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, dp(6f, scale))
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, dp(10f, scale), dp(9f, scale))

        if (ImGui.beginPopup(popupId)) {
            ImGui.text(setting.label)
            val rgb = setting.rgb
            val picker = if (setting.supportsAlpha) {
                floatArrayOf(
                    ((rgb ushr 16) and 0xFF) / 255f,
                    ((rgb ushr 8) and 0xFF) / 255f,
                    (rgb and 0xFF) / 255f,
                    setting.alpha / 255f,
                )
            } else {
                floatArrayOf(
                    ((rgb ushr 16) and 0xFF) / 255f,
                    ((rgb ushr 8) and 0xFF) / 255f,
                    (rgb and 0xFF) / 255f,
                )
            }
            ImGui.setNextItemWidth(dp(150f, scale))
            var flags = ImGuiColorEditFlags.NoInputs or
                ImGuiColorEditFlags.NoSidePreview or
                ImGuiColorEditFlags.NoSmallPreview or
                ImGuiColorEditFlags.PickerHueBar
            flags = if (setting.supportsAlpha) flags or ImGuiColorEditFlags.AlphaBar else flags or ImGuiColorEditFlags.NoAlpha
            val pickerChanged = if (setting.supportsAlpha) {
                ImGui.colorPicker4("##color_picker", picker, flags)
            } else {
                ImGui.colorPicker3("##color_picker", picker, flags)
            }
            if (pickerChanged) {
                val red = (picker[0] * 255f).roundToInt().coerceIn(0, 255)
                val green = (picker[1] * 255f).roundToInt().coerceIn(0, 255)
                val blue = (picker[2] * 255f).roundToInt().coerceIn(0, 255)
                setting.rgb = (red shl 16) or (green shl 8) or blue
                if (setting.supportsAlpha) {
                    setting.alpha = (picker[3] * 255f).roundToInt().coerceIn(0, 255)
                }
                colorHexBuffers.getOrPut(setting) { ImString(9) }.set(setting.hexDigits)
            }

            val hexBuffer = colorHexBuffers.getOrPut(setting) { ImString(9).also { it.set(setting.hexDigits) } }
            ImGui.setNextItemWidth(dp(150f, scale))
            pushFieldFont(dp(10f, scale), dp(20f, scale), dp(6f, scale))
            if (colorHexFocusRequested === setting) {
                colorHexFocusRequested = null
                ImGui.setKeyboardFocusHere()
            }
            val hexChanged = ImGui.inputTextWithHint(
                "##color_hex",
                if (setting.supportsAlpha) "AARRGGBB" else "RRGGBB",
                hexBuffer,
                ImGuiInputTextFlags.CharsHexadecimal or
                    ImGuiInputTextFlags.CharsUppercase or
                    ImGuiInputTextFlags.AutoSelectAll,
            )
            popFieldFont()
            if (hexChanged) setting.setHex(hexBuffer.get())
            ImGui.endPopup()
        }

        ImGui.popStyleVar(3)
        ImGui.popStyleColor(9)
    }

    private fun drawCustomSlider(
        draw: ImDrawList,
        setting: SliderModuleSetting,
        cardX: Float,
        rowY: Float,
        cardWidth: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        val barX = cardX + dp(SLIDER_X, scale)
        val barY = rowY + dp(9f, scale)
        val barWidth = cardWidth - dp(SLIDER_X + 14f, scale)
        val trackHeight = dp(4f, scale)
        val progress = ((setting.value - setting.min) / (setting.max - setting.min)).toFloat().coerceIn(0f, 1f)

        drawEditableSliderValue(
            draw = draw,
            ids = setting.widgetIds,
            labelText = setting.labelWithColon,
            value = setting.displayValue,
            x = cardX + dp(14f, scale),
            y = rowY + dp(3f, scale),
            scale = scale,
            interactive = interactive,
        ) { entered ->
            setting.value = snapSliderValue(entered, setting.min, setting.max, setting.step)
        }
        draw.addRectFilled(barX, barY, barX + barWidth, barY + trackHeight, contentColor(TRACK), trackHeight / 2f)
        draw.addRectFilled(barX, barY, barX + barWidth * progress, barY + trackHeight, contentColor(ACCENT), trackHeight / 2f)
        drawSliderHandle(draw, barX + barWidth * progress, barY + trackHeight / 2f, scale)

        hit(setting.widgetIds.control, barX, barY - dp(5f, scale), barWidth, dp(14f, scale))
        if (interactive && ImGui.isItemActive()) {
            val ratio = ((ImGui.getMousePosX() - barX) / barWidth).coerceIn(0f, 1f)
            val raw = setting.min + (setting.max - setting.min) * ratio
            setting.value = snapSliderValue(raw, setting.min, setting.max, setting.step)
        }
    }

    /**
     * A slider with two handles, laid out on the same row a single slider gets
     * so the two read as the same kind of control.
     *
     * Which handle a drag grabs is decided once, when the drag starts, from
     * whichever end the press was nearer: deciding it per frame would let the
     * handles swap under the cursor halfway through a drag.
     */
    private fun drawCustomRange(
        draw: ImDrawList,
        setting: RangeModuleSetting,
        cardX: Float,
        rowY: Float,
        cardWidth: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        val barX = cardX + dp(SLIDER_X, scale)
        val barY = rowY + dp(9f, scale)
        val barWidth = cardWidth - dp(SLIDER_X + 14f, scale)
        val trackHeight = dp(4f, scale)
        val span = setting.max - setting.min
        val lower = ((setting.lower - setting.min) / span).toFloat().coerceIn(0f, 1f)
        val upper = ((setting.upper - setting.min) / span).toFloat().coerceIn(0f, 1f)

        drawEditableRangeTokens(draw, setting, cardX + dp(14f, scale), rowY + dp(3f, scale), scale, interactive)

        draw.addRectFilled(barX, barY, barX + barWidth, barY + trackHeight, contentColor(TRACK), trackHeight / 2f)
        draw.addRectFilled(
            barX + barWidth * lower,
            barY,
            barX + barWidth * upper,
            barY + trackHeight,
            contentColor(ACCENT),
            trackHeight / 2f,
        )
        drawSliderHandle(draw, barX + barWidth * lower, barY + trackHeight / 2f, scale)
        drawSliderHandle(draw, barX + barWidth * upper, barY + trackHeight / 2f, scale)

        hit(setting.widgetIds.control, barX, barY - dp(5f, scale), barWidth, dp(14f, scale))
        if (interactive && ImGui.isItemActivated()) {
            val value = settingRangeValueAt(setting, ImGui.getMousePosX(), barX, barWidth)
            draggingLowerSettingHandle = abs(value - setting.lower) <= abs(value - setting.upper)
        }
        if (interactive && ImGui.isItemActive()) {
            val raw = settingRangeValueAt(setting, ImGui.getMousePosX(), barX, barWidth)
            if (draggingLowerSettingHandle) {
                setting.lower = snapSliderValue(raw, setting.min, setting.upper, setting.step)
            } else {
                setting.upper = snapSliderValue(raw, setting.lower, setting.max, setting.step)
            }
        }
    }

    private fun settingRangeValueAt(
        setting: RangeModuleSetting,
        mouseX: Float,
        barX: Float,
        barWidth: Float,
    ): Double {
        val ratio = ((mouseX - barX) / barWidth).coerceIn(0f, 1f)
        return setting.min + (setting.max - setting.min) * ratio
    }

    private fun drawEditableRangeTokens(
        draw: ImDrawList,
        setting: RangeModuleSetting,
        x: Float,
        y: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        val fontSize = dp(9.5f, scale)
        val labelText = setting.labelWithColon
        val lowerText = setting.displayLower
        val upperText = setting.displayUpper
        val lowerX = x + textWidth(labelText, fontSize) + dp(4f, scale)
        val lowerWidth = maxOf(textWidth(lowerText, fontSize), dp(13f, scale))
        val separatorX = lowerX + lowerWidth + dp(3f, scale)
        val upperX = separatorX + textWidth("-", fontSize) + dp(3f, scale)

        drawText(draw, labelText, x, y, TEXT, fontSize)
        drawEditableNumericToken(draw, setting.lowerIds, lowerText, lowerX, y, scale, interactive) { entered ->
            setting.lower = snapSliderValue(entered, setting.min, setting.upper, setting.step)
        }
        drawText(draw, "-", separatorX, y, TEXT, fontSize)
        drawEditableNumericToken(draw, setting.upperIds, upperText, upperX, y, scale, interactive) { entered ->
            setting.upper = snapSliderValue(entered, setting.lower, setting.max, setting.step)
        }
    }

    private fun drawSettingToggle(
        draw: ImDrawList,
        setting: ToggleModuleSetting,
        x: Float,
        y: Float,
        scale: Float,
        dt: Float,
        interactive: Boolean,
    ) {
        val width = dp(32f, scale)
        val height = dp(15f, scale)
        val progress = animate(
            settingTogglePosition.getOrPut(setting) { if (setting.value) 1f else 0f },
            if (setting.value) 1f else 0f,
            22f,
            dt,
        )
        settingTogglePosition[setting] = progress
        draw.addRectFilled(x, y, x + width, y + height, contentColor(if (setting.value) ACCENT else TOGGLE_OFF), height / 2f)
        val radius = dp(5.5f, scale)
        val knobX = x + dp(2.5f, scale) + radius + (width - dp(5f, scale) - radius * 2f) * progress
        draw.addCircleFilled(knobX, y + height / 2f, radius, contentColor(KNOB), 32)
        if (interactive && hit(setting.widgetIds.control, x, y, width, height)) {
            setting.value = !setting.value
        }
    }

    /**
     * Slider values stay visually lightweight, but clicking the number turns
     * it into a real ImGui text field with selection, arrows and clipboard
     * shortcuts. Every module slider uses this same editor.
     */
    private fun drawEditableSliderValue(
        draw: ImDrawList,
        ids: WidgetIds,
        labelText: String,
        value: String,
        x: Float,
        y: Float,
        scale: Float,
        interactive: Boolean,
        onCommit: (Double) -> Unit,
    ) {
        val fontSize = dp(9.5f, scale)
        drawText(draw, labelText, x, y, TEXT, fontSize)
        drawEditableNumericToken(
            draw,
            ids,
            value,
            x + textWidth(labelText, fontSize) + dp(4f, scale),
            y,
            scale,
            interactive,
            onCommit,
        )
    }

    private fun drawEditableRangeValues(
        draw: ImDrawList,
        module: Module,
        x: Float,
        y: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        val fontSize = dp(9.5f, scale)
        val labelText = module.range.labelWithColon
        val lowerText = module.range.displayLower
        val upperText = module.range.displayUpper
        val lowerX = x + textWidth(labelText, fontSize) + dp(4f, scale)
        val lowerWidth = maxOf(textWidth(lowerText, fontSize), dp(13f, scale))
        val separatorX = lowerX + lowerWidth + dp(3f, scale)
        val upperX = separatorX + textWidth("-", fontSize) + dp(3f, scale)

        drawText(draw, labelText, x, y, TEXT, fontSize)
        drawEditableNumericToken(
            draw,
            module.widgetIds.demoRangeLower,
            lowerText,
            lowerX,
            y,
            scale,
            interactive,
        ) { entered ->
            module.range.lower = snapSliderValue(entered, module.range.min, module.range.upper, 1.0)
        }
        drawText(draw, "-", separatorX, y, TEXT, fontSize)
        drawEditableNumericToken(
            draw,
            module.widgetIds.demoRangeUpper,
            upperText,
            upperX,
            y,
            scale,
            interactive,
        ) { entered ->
            module.range.upper = snapSliderValue(entered, module.range.lower, module.range.max, 1.0)
        }
    }

    private fun drawEditableNumericToken(
        draw: ImDrawList,
        ids: WidgetIds,
        value: String,
        x: Float,
        y: Float,
        scale: Float,
        interactive: Boolean,
        onCommit: (Double) -> Unit,
    ) {
        val fontSize = dp(9.5f, scale)
        val fieldWidth = maxOf(textWidth(value, fontSize) + dp(9f, scale), dp(25f, scale))
        val fieldHeight = dp(16f, scale)

        if (editingNumericId != ids.editorKey) {
            val clicked = interactive && hit(ids.editorHit, x - dp(2f, scale), y - dp(2f, scale), fieldWidth, fieldHeight)
            val hovered = interactive && ImGui.isItemHovered()
            drawText(draw, value, x, y, if (hovered) ACCENT else TEXT, fontSize)
            if (clicked) {
                editingNumericId = ids.editorKey
                numericBuffer.set(value)
                numericFocusRequested = true
            }
            return
        }

        ImGui.setCursorScreenPos(x - dp(3f, scale), y - dp(3f, scale))
        ImGui.setNextItemWidth(fieldWidth)
        ImGui.pushStyleColor(ImGuiCol.Text, contentColor(TEXT))
        ImGui.pushStyleColor(ImGuiCol.FrameBg, contentColor(RENAME_FIELD))
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, contentColor(RENAME_FIELD))
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, contentColor(RENAME_FIELD))
        ImGui.pushStyleColor(ImGuiCol.Border, contentColor(ACCENT))
        ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, contentColor(ACCENT_DARK))
        ImGui.pushStyleColor(ImGuiCol.InputTextCursor, contentColor(ACCENT))
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, dp(3f, scale))
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, dp(1f, scale))
        pushFieldFont(fontSize, fieldHeight, dp(3f, scale))
        if (numericFocusRequested) {
            ImGui.setKeyboardFocusHere()
            numericFocusRequested = false
        }
        val submitted = ImGui.inputText(
            ids.editorInput,
            numericBuffer,
            ImGuiInputTextFlags.CharsDecimal or
                ImGuiInputTextFlags.AutoSelectAll or
                ImGuiInputTextFlags.EnterReturnsTrue,
        )
        val deactivated = ImGui.isItemDeactivated()
        popFieldFont()
        ImGui.popStyleVar(2)
        ImGui.popStyleColor(7)

        if (submitted || deactivated) {
            numericBuffer.get().trim().replace(',', '.').toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?.let(onCommit)
            editingNumericId = null
            numericBuffer.clear()
        }
    }

    private fun snapSliderValue(value: Double, min: Double, max: Double, step: Double): Double {
        val clamped = value.coerceIn(min, max)
        if (step <= 0.0) return clamped
        return (min + round((clamped - min) / step) * step).coerceIn(min, max)
    }

    private fun drawSlider(
        draw: ImDrawList,
        module: Module,
        cardX: Float,
        cardY: Float,
        cardWidth: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        val barX = cardX + dp(SLIDER_X, scale)
        val barY = cardY + dp(75f, scale)
        val barWidth = cardWidth - dp(SLIDER_X + 14f, scale)
        val trackHeight = dp(4f, scale)
        val progress = ((module.slider.value - module.slider.min) / (module.slider.max - module.slider.min)).toFloat()

        drawEditableSliderValue(
            draw = draw,
            ids = module.widgetIds.demoSliderEditor,
            labelText = module.slider.labelWithColon,
            value = module.slider.displayValue,
            x = cardX + dp(14f, scale),
            y = cardY + dp(69f, scale),
            scale = scale,
            interactive = interactive,
        ) { entered ->
            module.slider.value = snapSliderValue(entered, module.slider.min, module.slider.max, 1.0)
        }
        draw.addRectFilled(barX, barY, barX + barWidth, barY + trackHeight, contentColor(TRACK), trackHeight / 2f)
        draw.addRectFilled(barX, barY, barX + barWidth * progress, barY + trackHeight, contentColor(ACCENT), trackHeight / 2f)
        drawSliderHandle(draw, barX + barWidth * progress, barY + trackHeight / 2f, scale)

        hit(module.widgetIds.demoSlider, barX, barY - dp(5f, scale), barWidth, dp(14f, scale))
        if (interactive && ImGui.isItemActive()) {
            val ratio = ((ImGui.getMousePosX() - barX) / barWidth).coerceIn(0f, 1f)
            module.slider.value = snapSliderValue(
                module.slider.min + (module.slider.max - module.slider.min) * ratio,
                module.slider.min,
                module.slider.max,
                1.0,
            )
        }
    }

    private fun drawRangeSlider(
        draw: ImDrawList,
        module: Module,
        cardX: Float,
        cardY: Float,
        cardWidth: Float,
        scale: Float,
        interactive: Boolean,
    ) {
        val barX = cardX + dp(SLIDER_X, scale)
        val barY = cardY + dp(99f, scale)
        val barWidth = cardWidth - dp(SLIDER_X + 14f, scale)
        val trackHeight = dp(4f, scale)
        val lower = ((module.range.lower - module.range.min) / (module.range.max - module.range.min)).toFloat()
        val upper = ((module.range.upper - module.range.min) / (module.range.max - module.range.min)).toFloat()

        drawEditableRangeValues(
            draw,
            module,
            cardX + dp(14f, scale),
            cardY + dp(93f, scale),
            scale,
            interactive,
        )
        draw.addRectFilled(barX, barY, barX + barWidth, barY + trackHeight, contentColor(TRACK), trackHeight / 2f)
        draw.addRectFilled(barX + barWidth * lower, barY, barX + barWidth * upper, barY + trackHeight, contentColor(ACCENT), trackHeight / 2f)
        drawSliderHandle(draw, barX + barWidth * lower, barY + trackHeight / 2f, scale)
        drawSliderHandle(draw, barX + barWidth * upper, barY + trackHeight / 2f, scale)

        hit(module.widgetIds.demoRange, barX, barY - dp(5f, scale), barWidth, dp(14f, scale))
        if (interactive && ImGui.isItemActivated()) {
            val value = rangeValueAt(module, ImGui.getMousePosX(), barX, barWidth)
            draggingLowerRangeHandle = abs(value - module.range.lower) <= abs(value - module.range.upper)
        }
        if (interactive && ImGui.isItemActive()) {
            val value = rangeValueAt(module, ImGui.getMousePosX(), barX, barWidth)
            if (draggingLowerRangeHandle) module.range.lower = minOf(value, module.range.upper)
            else module.range.upper = maxOf(value, module.range.lower)
        }
    }

    private fun drawDropdownButton(
        draw: ImDrawList,
        module: Module,
        cardX: Float,
        cardY: Float,
        cardWidth: Float,
        scale: Float,
        interactive: Boolean,
    ): Boolean {
        val boxX = cardX + dp(DROPDOWN_X, scale)
        val boxY = cardY + dp(114f, scale)
        val boxWidth = cardWidth - dp(DROPDOWN_X + 14f, scale)
        drawText(draw, module.dropdown.label, cardX + dp(14f, scale), cardY + dp(118f, scale), TEXT, dp(10f, scale))
        return drawDropdownControl(draw, module.dropdown, boxX, boxY, boxWidth, scale, interactive)
    }

    /**
     * Draws the closed dropdown control and reports whether it is hovered, which
     * the popup needs so its own button half keeps the same highlight.
     */
    private fun drawDropdownControl(
        draw: ImDrawList,
        source: DropdownSource,
        x: Float,
        y: Float,
        width: Float,
        scale: Float,
        interactive: Boolean,
    ): Boolean {
        val height = dp(20f, scale)
        outlinedPill(draw, x, y, width, height, scale)
        val clicked = interactive && hit(source.buttonId, x, y, width, height)
        val hovered = interactive && ImGui.isItemHovered()
        if (clicked) {
            openDropdown = if (openDropdown === source) null else source
        }
        val textColor = when {
            openDropdown === source -> ACCENT
            hovered -> TEXT
            else -> MUTED_TEXT
        }
        drawCenteredText(draw, source.selected, x, y, width, height, textColor, dp(10f, scale))
        return hovered
    }

    /**
     * Advances one dropdown's open/close animation and, while it is visible,
     * hands back the popup for the caller to draw over the finished cards.
     */
    private fun dropdownOverlayFor(
        source: DropdownSource,
        x: Float,
        y: Float,
        width: Float,
        scale: Float,
        dt: Float,
        expanded: Boolean,
        buttonHovered: Boolean,
    ): DropdownOverlay? {
        val target = if (expanded && openDropdown === source) 1f else 0f
        val progress = animate(
            dropdownPosition.getOrPut(source) { target },
            target,
            DROPDOWN_ANIMATION_SPEED,
            dt,
        )
        dropdownPosition[source] = progress
        if (!expanded || progress <= 0.01f) return null
        return DropdownOverlay(source, x, y, width, scale, progress, buttonHovered)
    }

    private fun drawDropdownOverlay(draw: ImDrawList, overlay: DropdownOverlay, guiInteractive: Boolean) {
        val source = overlay.source
        val scale = overlay.scale
        val popupX = overlay.x
        val popupY = overlay.y
        val popupWidth = overlay.width
        val buttonHeight = dp(20f, scale)
        val rowHeight = dp(18f, scale)
        val options = source.options
        val selectedIndex = source.selectedIndex
        val optionsHeight = rowHeight * (options.size - 1).coerceAtLeast(0)
        val reveal = easeOutCubic(overlay.progress)
        val popupHeight = buttonHeight + optionsHeight * reveal

        // A dropdown near the bottom of the window would open past it, and a
        // card is not scrolled by a popup floating over it, so the options go
        // above the control instead. The control itself does not move.
        val flipped = popupY + popupHeight > ImGui.getIO().displaySizeY - dp(8f, scale)
        val popupTop = if (flipped) popupY + buttonHeight - popupHeight else popupY
        val buttonTop = if (flipped) popupTop + popupHeight - buttonHeight else popupTop
        val rowsTop = if (flipped) popupTop else popupTop + buttonHeight

        draw.pushClipRect(popupX, popupTop, popupX + popupWidth, popupTop + popupHeight, true)
        draw.addRectFilled(popupX, popupTop, popupX + popupWidth, popupTop + popupHeight, contentColor(TRACK), dp(8f, scale))
        val border = dp(2f, scale)
        draw.addRectFilled(
            popupX + border,
            popupTop + border,
            popupX + popupWidth - border,
            popupTop + popupHeight - border,
            contentColor(SURFACE),
            dp(6f, scale),
        )

        val buttonColor = when {
            openDropdown === source -> ACCENT
            overlay.buttonHovered -> TEXT
            else -> MUTED_TEXT
        }
        drawCenteredText(draw, source.selected, popupX, buttonTop, popupWidth, buttonHeight, buttonColor, dp(10f, scale))
        draw.addRectFilled(
            popupX + border,
            if (flipped) buttonTop else buttonTop + buttonHeight - dp(1f, scale),
            popupX + popupWidth - border,
            if (flipped) buttonTop + dp(1f, scale) else buttonTop + buttonHeight,
            contentColor(TRACK),
        )

        // Walked by index: the popup is redrawn every frame, so pairing each
        // option with its position must not allocate a list to do it.
        var rowIndex = 0
        for (index in options.indices) {
            if (index == selectedIndex) continue
            val option = options[index]
            val rowY = rowsTop + rowHeight * rowIndex
            // The region is claimed for the whole opening animation, so a row
            // the popup has already covered cannot be clicked through it, but
            // the option only takes effect once the popup is really open.
            val claiming = guiInteractive && openDropdown === source
            val pressed = claiming && hit(
                source.optionIds[index],
                popupX + border,
                rowY,
                popupWidth - border * 2f,
                rowHeight,
            )
            val hovered = claiming && ImGui.isItemHovered()
            if (pressed && overlay.progress > 0.9f) {
                source.selectedIndex = index
                openDropdown = null
            }
            if (rowIndex > 0) {
                draw.addRectFilled(
                    popupX + border,
                    rowY,
                    popupX + popupWidth - border,
                    rowY + dp(1f, scale),
                    contentColor(TRACK, overlay.progress),
                )
            }
            val optionColor = when {
                index == source.selectedIndex -> ACCENT
                hovered -> TEXT
                else -> MUTED_TEXT
            }
            drawCenteredText(
                draw,
                option,
                popupX,
                rowY,
                popupWidth,
                rowHeight,
                fadeColor(optionColor, overlay.progress),
                dp(10f, scale),
            )
            rowIndex++
        }
        draw.popClipRect()
    }

    private fun drawSliderHandle(draw: ImDrawList, x: Float, y: Float, scale: Float) {
        draw.addCircleFilled(x, y, dp(6f, scale), contentColor(ACCENT), 32)
    }

    /**
     * Prepares a Dear ImGui text field to occupy exactly the box drawn for it.
     *
     * ImGui derives a field's height from its font, and the atlas font is built
     * for Minecraft's GUI scale rather than this menu's design scale, so the two
     * disagree by a different amount on every setup. Left alone the field's
     * clickable rectangle drifts away from the box on screen, and a click landing
     * in the gap goes to the window behind instead of focusing the field, which
     * looks exactly like a field that focuses and then immediately drops it.
     */
    private fun pushFieldFont(fontSize: Float, height: Float, horizontalPadding: Float) {
        ImGui.pushFont(ImGuiRuntime.font, fontSize)
        val verticalPadding = ((height - fontSize) / 2f).coerceAtLeast(0f)
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, horizontalPadding, verticalPadding)
    }

    private fun popFieldFont() {
        ImGui.popStyleVar()
        ImGui.popFont()
    }

    private fun outlinedPill(draw: ImDrawList, x: Float, y: Float, width: Float, height: Float, scale: Float) {
        draw.addRectFilled(x, y, x + width, y + height, contentColor(TRACK), height / 2f)
        val border = dp(2f, scale)
        draw.addRectFilled(x + border, y + border, x + width - border, y + height - border, contentColor(SURFACE), (height - border * 2f) / 2f)
    }

    /**
     * Registers one clickable region.
     *
     * Overlap is allowed on every control, because a dropdown popup is drawn
     * after the cards but sits on top of the rows below its own. Without this
     * the row underneath claims the click, since Dear ImGui otherwise awards a
     * shared position to whichever control was submitted first.
     */
    private fun hit(id: String, x: Float, y: Float, width: Float, height: Float): Boolean {
        ImGui.setCursorScreenPos(x, y)
        ImGui.setNextItemAllowOverlap()
        return ImGui.invisibleButton(id, width.coerceAtLeast(1f), height.coerceAtLeast(1f), ImGuiMouseButton.Left)
    }

    /**
     * A line trimmed to fit, with an ellipsis where it was cut.
     *
     * Measuring a string costs a walk through it, and this runs for every card
     * every frame, so the answer is kept until the thing that could change it
     * does: the card's width, or the text size the menu scale gives it.
     */
    private fun fitToWidth(key: String, text: String, size: Float, maxWidth: Float): String {
        if (size != fittedSize || maxWidth != fittedWidth) {
            fitted.clear()
            fittedSize = size
            fittedWidth = maxWidth
        }
        return fitted.getOrPut(key) { ellipsize(text, size, maxWidth) }
    }

    private fun ellipsize(text: String, size: Float, maxWidth: Float): String {
        if (maxWidth <= 0f || textWidth(text, size) <= maxWidth) return text

        var end = text.length
        while (end > 0 && textWidth(text.take(end) + ELLIPSIS, size) > maxWidth) end--
        return if (end <= 0) ELLIPSIS else text.take(end).trimEnd() + ELLIPSIS
    }

    /**
     * The sentence a setting carries, if any.
     *
     * Declared per setting type rather than on the base class, which is where
     * the descriptions were written; this is the one place that has to know
     * about all of them.
     */
    private fun tipFor(setting: ModuleSetting): String = when (setting) {
        is ToggleModuleSetting -> setting.description
        is SliderModuleSetting -> setting.description
        is RangeModuleSetting -> setting.description
        is ColorModuleSetting -> setting.description
        is TextModuleSetting -> setting.description
        is DropdownModuleSetting -> setting.description
        else -> ""
    }

    /** Remembers the row under the cursor, to be explained at the end of the frame. */
    private fun noteTooltip(setting: ModuleSetting, x: Float, y: Float, width: Float, height: Float) {
        if (!Tooltips.showing || height <= 0f) return
        val tip = tipFor(setting)
        if (tip.isEmpty()) return

        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return
        tooltipText = tip
    }

    /**
     * Draws the explanation beside the cursor.
     *
     * Kept inside the screen rather than handed to Dear ImGui's own tooltip,
     * which would arrive in its default styling in the middle of a menu that
     * is drawn by hand. It is nudged back on screen at the edges, because the
     * settings column reaches the right-hand side.
     */
    private fun drawTooltip(draw: ImDrawList, displayWidth: Float, displayHeight: Float, scale: Float) {
        val text = tooltipText ?: return
        val size = dp(9f, scale)
        val padding = dp(7f, scale)
        val lineHeight = size + dp(2f, scale)
        val lines = wrapText(text, dp(190f, scale), size)

        val width = (lines.maxOfOrNull { textWidth(it, size) } ?: 0f) + padding * 2f
        val height = lines.size * lineHeight + padding * 2f - dp(2f, scale)

        val x = (ImGui.getMousePosX() + dp(12f, scale)).coerceAtMost(displayWidth - width - dp(6f, scale))
        val y = (ImGui.getMousePosY() + dp(14f, scale)).coerceAtMost(displayHeight - height - dp(6f, scale))

        draw.addRectFilled(x, y, x + width, y + height, contentColor(RENAME_FIELD), dp(5f, scale))
        draw.addRect(x, y, x + width, y + height, contentColor(DISABLED_BORDER), dp(5f, scale), 0, dp(1f, scale))
        lines.forEachIndexed { index, line ->
            drawText(draw, line, x + padding, y + padding + index * lineHeight, TEXT, size)
        }
    }

    /** Breaks a sentence into lines no wider than [maxWidth], on word boundaries. */
    private fun wrapText(text: String, maxWidth: Float, size: Float): List<String> {
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var line = StringBuilder()

        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (textWidth(candidate, size) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                lines += line.toString()
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private fun drawText(draw: ImDrawList, text: String, x: Float, y: Float, color: Int, size: Float) {
        draw.addText(ImGuiRuntime.font, size.roundToInt().coerceAtLeast(1), x, y, contentColor(color), text)
    }

    private fun drawCenteredText(draw: ImDrawList, text: String, x: Float, y: Float, width: Float, height: Float, color: Int, size: Float) {
        val textWidth = ImGuiRuntime.textWidth(text, size)
        val textHeight = ImGuiRuntime.textHeight(text, size)
        drawText(draw, text, x + (width - textWidth) / 2f, y + (height - textHeight) / 2f, color, size)
    }

    private fun textWidth(text: String, size: Float): Float = ImGuiRuntime.textWidth(text, size)

    private fun rangeValueAt(module: Module, mouseX: Float, barX: Float, barWidth: Float): Double {
        val ratio = ((mouseX - barX) / barWidth).coerceIn(0f, 1f)
        return module.range.min + (module.range.max - module.range.min) * ratio
    }

    private fun animate(current: Float, target: Float, speed: Float, dt: Float): Float {
        if (dt <= 0f) return target
        return current + (target - current) * (1f - exp(-speed * dt))
    }

    private fun ease(value: Float): Float = value * value * (3f - 2f * value)
    private fun easeOutCubic(value: Float): Float = 1f - (1f - value) * (1f - value) * (1f - value)

    private fun guiColor(color: Int, opacity: Float = 1f): Int = fadeColor(color, guiAlpha * opacity)

    private fun contentColor(color: Int, opacity: Float = 1f): Int =
        fadeColor(color, guiAlpha * contentAlpha * opacity)

    private fun fadeColor(color: Int, opacity: Float): Int {
        if (opacity >= 0.999f) return color
        val alpha = (((color ushr 24) and 0xFF) * opacity).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun dp(value: Float, scale: Float) = value * scale

    /**
     * A dropdown popup, deferred until every card has been drawn.
     *
     * Cards clip their own contents, so an open popup is drawn afterwards in
     * screen space; it carries its own geometry because the control it belongs
     * to may be a module's own dropdown or any one setting's row.
     */
    private data class DropdownOverlay(
        val source: DropdownSource,
        val x: Float,
        val y: Float,
        val width: Float,
        val scale: Float,
        val progress: Float,
        val buttonHovered: Boolean,
    )

    private companion object {
        /** Three dots rather than the single glyph, which the bundled font may lack. */
        private const val ELLIPSIS = "..."

        // Kept in memory across CrypticScreen instances, but deliberately not
        // written to config: restarting the client returns every card to closed.
        val SESSION_EXPANDED_MODULES = mutableSetOf<Module>()
        var SESSION_SCROLL_OFFSET = 0f
        var SESSION_MAX_SCROLL = 0f
        var SESSION_SELECTED_CATEGORY = ModuleCategory.GENERAL

        val MODULES = ModuleRegistry.modules

        /** Dear ImGui tab identifiers, built once rather than once per frame. */
        val CATEGORY_IDS: List<String> = ModuleCategory.entries.map { "##category_${it.name}" }

        const val REFERENCE_HEIGHT = 1080f
        const val REFERENCE_1080_SCALE = 1.5f
        const val PANEL_WIDTH = 590f
        const val CONTENT_Y = 70f
        const val CONTENT_BOTTOM_MARGIN = 12f
        const val SCROLL_STEP = 34f
        const val COLUMN_GAP = 8f
        const val CARD_GAP = 8f
        const val COLLAPSED_HEIGHT = 49f
        const val EXPANDED_HEIGHT = 142f
        const val CUSTOM_SETTINGS_Y = 53f
        const val CUSTOM_SECTION_HEIGHT = 18f
        const val CUSTOM_SECTION_GAP = 6f
        const val CUSTOM_SLIDER_ROW_HEIGHT = 24f
        const val CUSTOM_TOGGLE_ROW_HEIGHT = 21f
        const val CUSTOM_BUTTON_ROW_HEIGHT = 25f
        const val CUSTOM_COLOR_ROW_HEIGHT = 23f
        const val CUSTOM_DROPDOWN_ROW_HEIGHT = 25f
        const val TEXT_LABEL_HEIGHT = 14f
        const val TEXT_FIELD_HEIGHT = 20f
        const val TEXT_FIELD_FONT = 10f
        const val CUSTOM_TEXT_ROW_HEIGHT = TEXT_LABEL_HEIGHT + TEXT_FIELD_HEIGHT + 5f
        const val CUSTOM_BOTTOM_PADDING = 10f
        const val PROFILE_CARD_HEIGHT = 50f
        const val SLIDER_X = 120f
        const val DROPDOWN_X = SLIDER_X
        const val KEYBIND_GAP = 5f
        const val KEYBIND_MIN_WIDTH = 24f
        const val TAB_ANIMATION_SECONDS = 0.2f
        const val TAB_TRANSITION_DISTANCE = 42f
        const val DROPDOWN_ANIMATION_SPEED = 22f
        const val OPENING_ANIMATION_SECONDS = 0.32f
        const val OPENING_ANIMATION_DISTANCE = 22f
        const val SEARCH_NAV_WIDTH = 30f
        const val HUD_NAV_WIDTH = 30f
        const val SEARCH_ANIMATION_SPEED = 18f
        const val PROFILE_DESCRIPTION_REFRESH_MS = 1_000L

        // ImGui colors are packed as ABGR rather than Minecraft's ARGB.
        val ACCENT: Int get() = ClickGui.accentAbgr()
        val ACCENT_DARK: Int get() = ClickGui.accentDarkAbgr()
        const val SURFACE_DARK = 0xFF141414.toInt()
        const val SURFACE = 0xFF141414.toInt()
        const val SURFACE_ACTIVE = 0xFF464646.toInt()
        const val SURFACE_RAISED = 0xFF3F3F3F.toInt()
        const val RENAME_FIELD = 0xFF1D1D1D.toInt()
        const val DISABLED_BORDER = 0xFF2B2B2B.toInt()
        const val DIVIDER = 0xFF2B2B2B.toInt()
        const val DISABLED_TEXT = 0xFF666666.toInt()
        const val TRACK = 0xFF4B4B4B.toInt()
        const val TOGGLE_OFF = 0xFF474747.toInt()
        const val KNOB = 0xFF161616.toInt()
        const val TEXT = 0xFFE4E4E4.toInt()
        const val NAV_TEXT = 0xFFD8D8D8.toInt()
        const val MUTED_TEXT = 0xFFA4A4A4.toInt()
        // #DF4A4F and its brighter hover state, packed as ABGR.
        const val DELETE_TEXT = 0xFF4F4ADF.toInt()
        const val DELETE_HOVER = 0xFF5F5AF3.toInt()
    }
}
