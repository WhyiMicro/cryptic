package imicro.cryptic.gui

import imgui.ImFont
import imgui.ImFontConfig
import imgui.ImGui
import imgui.callback.ImStrConsumer
import imgui.callback.ImStrSupplier
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiKey
import imgui.gl3.ImGuiImplGl3
import imgui.internal.ImGuiContext
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/** Owns the single Dear ImGui context shared by every Cryptic screen instance. */
object ImGuiRuntime {
    private val logger = LoggerFactory.getLogger("cryptic/imgui")
    private val gl3 = ImGuiImplGl3()

    private var initialized = false
    private var broken = false
    private var context: ImGuiContext? = null
    private var fontScale = -1
    private var lastFrameNanos = 0L
    private var controlDown = false
    private var shiftDown = false
    private var altDown = false
    private var superDown = false
    private lateinit var fontBytes: ByteArray
    private lateinit var iconFontBytes: ByteArray

    // Measuring text crosses into native code, and the menu re-measures the same
    // labels on every frame. Each entry holds the size the string was measured
    // at plus the resulting width and height; labels are almost always drawn at
    // a single size, so one slot per string is enough to hit on every redraw.
    private val textSizes = HashMap<String, FloatArray>()

    lateinit var font: ImFont
        private set

    /** Width of [text] at [size], measured natively only when it is not cached. */
    fun textWidth(text: String, size: Float): Float = measure(text, size)[1]

    /** Height of [text] at [size], measured natively only when it is not cached. */
    fun textHeight(text: String, size: Float): Float = measure(text, size)[2]

    private fun measure(text: String, size: Float): FloatArray {
        val cached = textSizes[text]
        if (cached != null && cached[0] == size) return cached

        val entry = cached ?: FloatArray(3)
        entry[0] = size
        entry[1] = font.calcTextSizeAX(size, Float.MAX_VALUE, 0f, text)
        entry[2] = font.calcTextSizeAY(size, Float.MAX_VALUE, 0f, text)
        if (cached == null) {
            // Values shown while dragging a slider are transient strings, so the
            // cache is bounded rather than allowed to grow with them.
            if (textSizes.size >= MAX_CACHED_TEXT_SIZES) textSizes.clear()
            textSizes[text] = entry
        }
        return entry
    }

    /** Invoked after Minecraft has presented its off-screen target to the window. */
    fun renderIfOpen() {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.screen as? CrypticScreen ?: return
        if (broken) return

        // Dear ImGui's current context is process-global. Other mods may bundle
        // imgui-java too, so never leave Cryptic's context selected after its
        // frame or one mod can accidentally continue the other mod's frame.
        val previousContext = ImGui.getCurrentContext()
        var frameStarted = false
        try {
            if (!initialized) initialize(minecraft)
            selectCrypticContext()

            // Cryptic renders into Minecraft's existing window and never needs
            // native platform windows. Keeping this disabled also means there is
            // no UpdatePlatformWindows lifecycle for another mod to interfere with.
            ImGui.getIO().removeConfigFlags(ImGuiConfigFlags.ViewportsEnable)
            ensureFontScale(minecraft)

            updatePlatformIo(minecraft)
            gl3.newFrame()
            ImGui.newFrame()
            frameStarted = true
            screen.drawImGui()
            ImGui.render()
            frameStarted = false
            gl3.renderDrawData(ImGui.getDrawData())
        } catch (error: Throwable) {
            if (frameStarted) {
                runCatching { ImGui.endFrame() }
            }
            broken = true
            logger.error("Dear ImGui rendering failed; disabling Cryptic's GUI for this session", error)
        } finally {
            restoreContext(previousContext)
        }
    }

    private fun initialize(minecraft: Minecraft) {
        context = ImGui.createContext()
        val io = ImGui.getIO()
        io.iniFilename = null
        io.logFilename = null
        io.removeConfigFlags(ImGuiConfigFlags.ViewportsEnable)

        fontBytes = checkNotNull(
            ImGuiRuntime::class.java.getResourceAsStream("/assets/cryptic/font/inter.ttf"),
        ) { "Missing bundled Inter font" }.use { it.readBytes() }
        iconFontBytes = checkNotNull(
            ImGuiRuntime::class.java.getResourceAsStream("/assets/cryptic/font/fa-solid-900.ttf"),
        ) { "Missing bundled Font Awesome Free Solid font" }.use { it.readBytes() }

        fontScale = minecraft.window.guiScale
        buildFontAtlas()

        val style = ImGui.getStyle()
        style.antiAliasedFill = true
        style.antiAliasedLines = true

        // Deliberately do not initialize ImGuiImplGlfw. Minecraft owns the
        // process-wide GLFW window and its callbacks, and another mod may own a
        // second Dear ImGui context. Cryptic supplies display/input/clipboard
        // state directly from Minecraft instead, so the two backends cannot
        // overwrite each other's callbacks or make GLFW calls from the wrong
        // event thread (notably when Ixeris is installed).
        io.backendPlatformName = "cryptic_minecraft"
        io.setGetClipboardTextFn(object : ImStrSupplier() {
            override fun get(): String = Minecraft.getInstance().keyboardHandler.clipboard
        })
        io.setSetClipboardTextFn(object : ImStrConsumer() {
            override fun accept(value: String) {
                Minecraft.getInstance().keyboardHandler.clipboard = value
            }
        })
        check(gl3.init("#version 150")) { "Could not initialize ImGui's OpenGL backend" }
        lastFrameNanos = System.nanoTime()
        initialized = true
        logger.info("Initialized embedded Dear ImGui runtime")
    }

    fun mousePosition(guiX: Double, guiY: Double) = withCrypticContext {
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val x = guiX * window.screenWidth / window.guiScaledWidth.coerceAtLeast(1)
        val y = guiY * window.screenHeight / window.guiScaledHeight.coerceAtLeast(1)
        ImGui.getIO().addMousePosEvent(x.toFloat(), y.toFloat())
    }

    fun mouseButton(button: Int, pressed: Boolean) = withCrypticContext {
        if (button in 0..4) {
            ImGui.getIO().addMouseButtonEvent(button, pressed)
        }
    }

    fun scroll(horizontal: Double, vertical: Double) = withCrypticContext {
        ImGui.getIO().addMouseWheelEvent(horizontal.toFloat(), vertical.toFloat())
    }

    fun key(key: Int, scanCode: Int, modifiers: Int, pressed: Boolean) = withCrypticContext {
        updateModifiers(key, modifiers, pressed)
        val io = ImGui.getIO()
        io.addKeyEvent(ImGuiKey.ImGuiMod_Ctrl, controlDown)
        io.addKeyEvent(ImGuiKey.ImGuiMod_Shift, shiftDown)
        io.addKeyEvent(ImGuiKey.ImGuiMod_Alt, altDown)
        io.addKeyEvent(ImGuiKey.ImGuiMod_Super, superDown)

        val imguiKey = glfwKeyToImGuiKey(key)
        if (imguiKey != ImGuiKey.None) {
            io.addKeyEvent(imguiKey, pressed)
            io.setKeyEventNativeData(imguiKey, key, scanCode)
        }
    }

    fun character(codepoint: Int) = withCrypticContext {
        ImGui.getIO().addInputCharacter(codepoint)
    }

    fun releaseInput() = withCrypticContext {
        controlDown = false
        shiftDown = false
        altDown = false
        superDown = false

        // Dear ImGui only advances a frame while Cryptic's screen is open, so
        // anything still queued when it closes is delivered to the *next*
        // session instead. The key that closed the menu is always in there,
        // pressed, with its release never forwarded because the screen was gone
        // by then; left queued it arrives held down, and a held Escape retriggers
        // on key-repeat and drops focus from whatever field is being typed into.
        // Clearing the state alone does not help: the queue outlives it.
        val io = ImGui.getIO()
        io.clearEventsQueue()
        io.clearInputKeys()
        io.clearInputMouse()
        io.addFocusEvent(false)
    }

    private inline fun withCrypticContext(action: () -> Unit) {
        if (!initialized || broken) return
        val ownContext = context ?: return
        val previousContext = ImGui.getCurrentContext()
        try {
            if (previousContext.ptr != ownContext.ptr) {
                ImGui.setCurrentContext(ownContext)
            }
            action()
        } catch (error: Throwable) {
            logger.error("Could not forward input to Cryptic's Dear ImGui context", error)
        } finally {
            restoreContext(previousContext)
        }
    }

    private fun selectCrypticContext() {
        val ownContext = checkNotNull(context) { "Cryptic's Dear ImGui context was not created" }
        if (ImGui.getCurrentContext().ptr != ownContext.ptr) {
            ImGui.setCurrentContext(ownContext)
        }
    }

    private fun restoreContext(previousContext: ImGuiContext) {
        val ownContext = context ?: return
        if (previousContext.isValidPtr && previousContext.ptr != ownContext.ptr) {
            ImGui.setCurrentContext(previousContext)
        }
    }

    private fun ensureFontScale(minecraft: Minecraft) {
        val desiredScale = minecraft.window.guiScale
        if (desiredScale == fontScale) return

        val io = ImGui.getIO()
        io.fonts.clear()
        fontScale = desiredScale
        buildFontAtlas()
        gl3.destroyFontsTexture()
    }

    private fun updatePlatformIo(minecraft: Minecraft) {
        val io = ImGui.getIO()
        val window = minecraft.window
        val screenWidth = window.screenWidth.coerceAtLeast(1)
        val screenHeight = window.screenHeight.coerceAtLeast(1)
        io.setDisplaySize(screenWidth.toFloat(), screenHeight.toFloat())
        io.setDisplayFramebufferScale(
            window.width.toFloat() / screenWidth,
            window.height.toFloat() / screenHeight,
        )

        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(1f / 1000f, 0.1f)
        }
        lastFrameNanos = now
        io.setDeltaTime(delta)
        io.addFocusEvent(window.isFocused)
    }

    private fun updateModifiers(key: Int, modifiers: Int, pressed: Boolean) {
        when (key) {
            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> controlDown = pressed
            GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> shiftDown = pressed
            GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> altDown = pressed
            GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> superDown = pressed
            else -> {
                // GLFW's modifier mask reflects the state at the event and also
                // covers layouts where a modifier key is reported unusually.
                controlDown = modifiers and GLFW.GLFW_MOD_CONTROL != 0
                shiftDown = modifiers and GLFW.GLFW_MOD_SHIFT != 0
                altDown = modifiers and GLFW.GLFW_MOD_ALT != 0
                superDown = modifiers and GLFW.GLFW_MOD_SUPER != 0
            }
        }
    }

    private fun glfwKeyToImGuiKey(key: Int): Int = when (key) {
        in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> ImGuiKey._0 + (key - GLFW.GLFW_KEY_0)
        in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> ImGuiKey.A + (key - GLFW.GLFW_KEY_A)
        in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F24 -> ImGuiKey.F1 + (key - GLFW.GLFW_KEY_F1)
        in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_9 -> ImGuiKey.Keypad0 + (key - GLFW.GLFW_KEY_KP_0)
        GLFW.GLFW_KEY_TAB -> ImGuiKey.Tab
        GLFW.GLFW_KEY_LEFT -> ImGuiKey.LeftArrow
        GLFW.GLFW_KEY_RIGHT -> ImGuiKey.RightArrow
        GLFW.GLFW_KEY_UP -> ImGuiKey.UpArrow
        GLFW.GLFW_KEY_DOWN -> ImGuiKey.DownArrow
        GLFW.GLFW_KEY_PAGE_UP -> ImGuiKey.PageUp
        GLFW.GLFW_KEY_PAGE_DOWN -> ImGuiKey.PageDown
        GLFW.GLFW_KEY_HOME -> ImGuiKey.Home
        GLFW.GLFW_KEY_END -> ImGuiKey.End
        GLFW.GLFW_KEY_INSERT -> ImGuiKey.Insert
        GLFW.GLFW_KEY_DELETE -> ImGuiKey.Delete
        GLFW.GLFW_KEY_BACKSPACE -> ImGuiKey.Backspace
        GLFW.GLFW_KEY_SPACE -> ImGuiKey.Space
        GLFW.GLFW_KEY_ENTER -> ImGuiKey.Enter
        GLFW.GLFW_KEY_ESCAPE -> ImGuiKey.Escape
        GLFW.GLFW_KEY_LEFT_CONTROL -> ImGuiKey.LeftCtrl
        GLFW.GLFW_KEY_LEFT_SHIFT -> ImGuiKey.LeftShift
        GLFW.GLFW_KEY_LEFT_ALT -> ImGuiKey.LeftAlt
        GLFW.GLFW_KEY_LEFT_SUPER -> ImGuiKey.LeftSuper
        GLFW.GLFW_KEY_RIGHT_CONTROL -> ImGuiKey.RightCtrl
        GLFW.GLFW_KEY_RIGHT_SHIFT -> ImGuiKey.RightShift
        GLFW.GLFW_KEY_RIGHT_ALT -> ImGuiKey.RightAlt
        GLFW.GLFW_KEY_RIGHT_SUPER -> ImGuiKey.RightSuper
        GLFW.GLFW_KEY_MENU -> ImGuiKey.Menu
        GLFW.GLFW_KEY_APOSTROPHE -> ImGuiKey.Apostrophe
        GLFW.GLFW_KEY_COMMA -> ImGuiKey.Comma
        GLFW.GLFW_KEY_MINUS -> ImGuiKey.Minus
        GLFW.GLFW_KEY_PERIOD -> ImGuiKey.Period
        GLFW.GLFW_KEY_SLASH -> ImGuiKey.Slash
        GLFW.GLFW_KEY_SEMICOLON -> ImGuiKey.Semicolon
        GLFW.GLFW_KEY_EQUAL -> ImGuiKey.Equal
        GLFW.GLFW_KEY_LEFT_BRACKET -> ImGuiKey.LeftBracket
        GLFW.GLFW_KEY_BACKSLASH -> ImGuiKey.Backslash
        GLFW.GLFW_KEY_RIGHT_BRACKET -> ImGuiKey.RightBracket
        GLFW.GLFW_KEY_GRAVE_ACCENT -> ImGuiKey.GraveAccent
        GLFW.GLFW_KEY_CAPS_LOCK -> ImGuiKey.CapsLock
        GLFW.GLFW_KEY_SCROLL_LOCK -> ImGuiKey.ScrollLock
        GLFW.GLFW_KEY_NUM_LOCK -> ImGuiKey.NumLock
        GLFW.GLFW_KEY_PRINT_SCREEN -> ImGuiKey.PrintScreen
        GLFW.GLFW_KEY_PAUSE -> ImGuiKey.Pause
        GLFW.GLFW_KEY_KP_DECIMAL -> ImGuiKey.KeypadDecimal
        GLFW.GLFW_KEY_KP_DIVIDE -> ImGuiKey.KeypadDivide
        GLFW.GLFW_KEY_KP_MULTIPLY -> ImGuiKey.KeypadMultiply
        GLFW.GLFW_KEY_KP_SUBTRACT -> ImGuiKey.KeypadSubtract
        GLFW.GLFW_KEY_KP_ADD -> ImGuiKey.KeypadAdd
        GLFW.GLFW_KEY_KP_ENTER -> ImGuiKey.KeypadEnter
        GLFW.GLFW_KEY_KP_EQUAL -> ImGuiKey.KeypadEqual
        else -> ImGuiKey.None
    }

    private fun buildFontAtlas() {
        // Metrics belong to the atlas that produced them.
        textSizes.clear()

        val io = ImGui.getIO()
        font = io.fonts.addFontFromMemoryTTF(fontBytes, 11f * fontScale)

        // Merge Font Awesome into Inter so icon constants can be used in any
        // ordinary ImGui label without changing fonts during rendering.
        val iconConfig = ImFontConfig().apply {
            mergeMode = true
            pixelSnapH = true
        }
        io.fonts.addFontFromMemoryTTF(
            iconFontBytes,
            10f * fontScale,
            iconConfig,
            FONT_AWESOME_GLYPH_RANGES,
        )
        iconConfig.destroy()

        io.setFontDefault(font)
        check(io.fonts.build()) { "Could not build Cryptic's Inter and Font Awesome atlas" }
    }

    private const val MAX_CACHED_TEXT_SIZES = 512

    private val FONT_AWESOME_GLYPH_RANGES = shortArrayOf(
        0xE005.toShort(),
        0xF8FF.toShort(),
        0,
    )
}
