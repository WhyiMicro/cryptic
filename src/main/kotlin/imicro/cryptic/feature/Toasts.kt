package imicro.cryptic.feature

import imgui.ImGui
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.ImGuiRuntime
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cryptic's own notifications: a pill in a corner saying what just happened.
 *
 * Two things separate this from the toast the menu used to draw for itself.
 * It says *who* is talking — the module's name in a badge, then what it has to
 * say — so a notification arriving while you are doing something else is
 * attributable at a glance. And it outlives the menu: a toast raised by a click
 * in the settings screen is still there after the screen is shut, because
 * closing a screen is not a reason to stop telling somebody what their click
 * did.
 *
 * Drawn through Dear ImGui rather than through Minecraft's GUI layer. That is
 * the whole reason it looks like the mock-up: ImGui anti-aliases a rounded
 * rectangle, and Minecraft's `fill` cannot — a pill built out of hard-edged
 * fills has visibly stepped corners at any size. Its foreground draw list needs
 * no window and no input, so [ImGuiRuntime] runs a frame for these even with no
 * screen open.
 */
object Toasts {
	/** Indices into [position]. */
	private const val TOP_LEFT = 0
	private const val TOP_RIGHT = 1
	private const val BOTTOM_LEFT = 2
	private const val BOTTOM_RIGHT = 3

	/** How far the pill sits from the edges of the screen, in ImGui pixels. */
	private const val MARGIN = 14f

	/** The pill's own padding, the badge's, and the gap between the two. */
	private const val PADDING = 9f

	/**
	 * The inset at the pill's right-hand end, where the message ends.
	 *
	 * Larger than [PADDING] because what is inset there is flat text against a
	 * half-circle, while what is inset on the left is the badge, which is a
	 * rounded pill of its own and nests inside the curve at a smaller gap. Nine
	 * pixels measured from the bounding box is nine pixels from a corner the
	 * pill does not have, which is how the last letter ended up sitting in the
	 * curve rather than inside it.
	 */
	private const val END_PADDING = 16f
	private const val BADGE_PADDING = 9f
	private const val GAP = 8f

	/**
	 * How much of the pill's own padding sits above and below the badge text.
	 *
	 * Half of [PADDING], so that the badge is inset from the top and bottom of
	 * the pill by exactly what it is inset from the left — the same relationship
	 * the magnifying glass has to the search field, and the thing that was
	 * visibly off before.
	 */
	private const val BADGE_VERTICAL = 4.5f

	/** The gap between stacked toasts. */
	private const val SPACING = 8f

	/** How far a toast slides in from its own edge as it appears. */
	private const val SLIDE = 16f

	private const val FADE_SECONDS = 0.16

	/** How many are shown at once before the oldest is dropped. */
	private const val MAX_SHOWN = 4

	/** The size the two pieces of text are drawn at, before [scale]. */
	private const val SOURCE_SIZE = 15f
	private const val MESSAGE_SIZE = 15f

	/**
	 * What the scale slider's 1 actually means.
	 *
	 * Everything above is written at the size the mock-up was drawn at, which is
	 * bigger than a notification wants to be over a game. Folding the difference
	 * in here rather than shrinking every number keeps the proportions readable
	 * and lets the slider say 1 for the size that is actually wanted.
	 */
	private const val BASE_SCALE = 0.6f

	@JvmField
	val position = DropdownModuleSetting(
		id = "position",
		label = "Position",
		options = listOf("Top left", "Top right", "Bottom left", "Bottom right"),
		defaultIndex = BOTTOM_RIGHT,
		description = "Which corner notifications stack in.",
	)

	@JvmField
	val seconds = SliderModuleSetting(
		id = "seconds",
		label = "Seconds",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		description = "How long one stays up.",
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Scale",
		defaultValue = 1.0,
		min = 0.5,
		max = 3.0,
		step = 0.1,
	)

	private val colorsSection = SectionModuleSetting("colors_section", "Colours")

	@JvmField
	val backgroundColor = ColorModuleSetting(
		id = "background_color",
		label = "Background",
		defaultRgb = 0x141414,
		supportsAlpha = true,
		defaultAlpha = 0xF2,
	)

	@JvmField
	val badgeColor = ColorModuleSetting(
		id = "badge_color",
		label = "Badge",
		defaultRgb = 0x3F3F3F,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
	)

	@JvmField
	val sourceColor = ColorModuleSetting(
		id = "source_color",
		label = "Module name",
		defaultRgb = 0xE4E4E4,
	)

	@JvmField
	val messageColor = ColorModuleSetting(
		id = "message_color",
		label = "Message",
		defaultRgb = 0xA4A4A4,
	)

	@JvmField
	val errorColor = ColorModuleSetting(
		id = "error_color",
		label = "Message (problem)",
		defaultRgb = 0xDF4A4F,
		description = "The message's colour when the notification is reporting something that went wrong.",
	)

	/**
	 * Raises one of each, so the shape and the colours can be seen while they are
	 * being set rather than the next time something happens to say something.
	 */
	private val test = ButtonModuleSetting("test", "Show a test notification", action = {
		show("Toast Notifications", "Toggled blah blah")
	})

	@JvmField
	val module = Module(
		id = "toasts",
		name = "Toast Notifications",
		description = "Cryptic's own notifications",
		category = ModuleCategory.MISC,
		enabled = true,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			position, seconds, scale, test,
			colorsSection, backgroundColor, badgeColor, sourceColor, messageColor, errorColor,
		),
	)

	/**
	 * One notification, and when it went up.
	 *
	 * [text] is asked every frame rather than fixed, so a notification that is
	 * waiting on something can say how long it has left. [keys] is what makes
	 * one a question: a key to an answer, tried while it is on screen.
	 *
	 * The four numbers at the bottom are where it was last drawn, in ImGui's
	 * pixels, which is how a click finds the one it landed on.
	 */
	private class Toast(
		val source: String,
		val text: (secondsLeft: Int) -> String,
		val error: Boolean,
		val startedAt: Long,
		var life: Double,
		val id: String? = null,
		val keys: Map<Int, () -> Unit> = emptyMap(),
	) {
		var left = 0f
		var top = 0f
		var right = 0f
		var bottom = 0f

		/**
		 * Set once it has been answered or waved away. It is still on screen for
		 * the moment it takes to fade, and a second press of the key in that
		 * moment must not answer it a second time.
		 */
		var done = false
			private set

		fun secondsLeft(now: Long): Int =
			(life - (now - startedAt) / 1_000_000_000.0).coerceAtLeast(0.0).let { kotlin.math.ceil(it).toInt() }

		/** Ends it now, fading out the way one that ran out of time does. */
		fun dismiss(now: Long) {
			done = true
			val elapsed = (now - startedAt) / 1_000_000_000.0
			life = minOf(life, elapsed + FADE_SECONDS)
		}
	}

	private val shown = ArrayDeque<Toast>()

	/**
	 * Raises a notification.
	 *
	 * [source] is who is speaking — a module's name — and [message] is what it
	 * has to say. Nothing here needs a screen to be open, which is the point.
	 *
	 * [life] overrides the configured duration, for the rare notification that
	 * is worth more of somebody's attention than the rest — a reminder they are
	 * about to lose something has to survive being walked away from.
	 */
	fun show(source: String, message: String, error: Boolean = false, life: Double = seconds.value) {
		if (!module.enabled) return
		add(Toast(source, { message }, error, System.nanoTime(), life))
	}

	/**
	 * Raises a notification that asks something, and waits for a key.
	 *
	 * [keys] maps a GLFW key to what pressing it does; whichever is pressed
	 * first answers the question and takes the notification down. [id] names it,
	 * so the module that raised it can take it down itself when the question
	 * stops being worth asking — and so asking the same thing twice replaces the
	 * first rather than stacking under it.
	 */
	fun ask(
		source: String,
		id: String,
		life: Double,
		keys: Map<Int, () -> Unit>,
		text: (secondsLeft: Int) -> String,
	) {
		if (!module.enabled) return
		shown.removeAll { it.id == id }
		add(Toast(source, text, false, System.nanoTime(), life, id, keys))
	}

	private fun add(toast: Toast) {
		shown.addLast(toast)
		// The oldest goes first, but a question outlives an announcement: four
		// things being said in a row should not take a party invite down with
		// them.
		while (shown.size > MAX_SHOWN) {
			val victim = shown.firstOrNull { it.keys.isEmpty() && it !== toast } ?: shown.first()
			shown.remove(victim)
		}
	}

	/** Takes down the notification raised under [id], if it is still up. */
	fun dismiss(id: String) {
		val now = System.nanoTime()
		shown.forEach { if (it.id == id) it.dismiss(now) }
	}

	/**
	 * A key going down, offered to whichever question is on screen.
	 *
	 * The newest first, so that two invites answer in the order a person would
	 * read them. True when a question took the key, which keeps it from also
	 * doing whatever it is bound to.
	 */
	fun handleKey(key: Int): Boolean {
		if (!module.enabled) return false
		val now = System.nanoTime()
		val toast = shown.lastOrNull { key in it.keys && !it.done } ?: return false
		toast.dismiss(now)
		toast.keys.getValue(key).invoke()
		return true
	}

	/**
	 * A click, in window pixels. A notification under it goes away.
	 *
	 * Only while the cursor is free — with a screen open — because there is no
	 * cursor to click with otherwise. True when one was hit, so the click does
	 * not also land on whatever the notification was covering.
	 */
	fun handleClick(x: Double, y: Double): Boolean {
		if (!module.enabled) return false
		val now = System.nanoTime()
		val toast = shown.lastOrNull {
			x >= it.left && x <= it.right && y >= it.top && y <= it.bottom && !it.done
		} ?: return false
		toast.dismiss(now)
		return true
	}

	/**
	 * A key going down anywhere in the game, from the keyboard itself.
	 *
	 * Only answered where a letter cannot be something being typed: out in the
	 * world, or in a chest, which has no text box. A Y in the middle of a chat
	 * message must never accept a party invite.
	 */
	@JvmStatic
	fun onKeyPressed(key: Int): Boolean {
		if (shown.none { it.keys.isNotEmpty() && !it.done }) return false
		val screen = Minecraft.getInstance().gui.screen()
		if (screen != null && screen !is ContainerScreen) return false
		return handleKey(key)
	}

	/** The left button going down, wherever the cursor is. See [handleClick]. */
	@JvmStatic
	fun onMousePressed(): Boolean {
		if (shown.isEmpty()) return false
		val mouse = Minecraft.getInstance().mouseHandler
		// A grabbed cursor is the crosshair, which is not pointing at anything
		// on the screen.
		if (mouse.isMouseGrabbed) return false
		return handleClick(mouse.xpos(), mouse.ypos())
	}

	/**
	 * Whether [ImGuiRuntime] should run a frame for these with no screen open.
	 *
	 * Asked every frame, so it is a size check and nothing else — the expiring
	 * happens in the draw, which only runs when this has said yes.
	 */
	fun wantsDrawing(): Boolean = module.enabled && shown.isNotEmpty()

	/** Drops everything, for a session that is ending or a menu that broke. */
	fun clear() = shown.clear()

	/**
	 * Draws the stack onto ImGui's foreground list.
	 *
	 * The foreground list is above every ImGui window, so a toast raised by the
	 * settings screen is drawn over it rather than behind it.
	 */
	fun drawImGui() {
		if (!module.enabled || shown.isEmpty()) return

		val now = System.nanoTime()
		shown.removeAll { (now - it.startedAt) / 1_000_000_000.0 >= it.life }
		if (shown.isEmpty()) return

		val window = Minecraft.getInstance().window
		val screenWidth = window.screenWidth.toFloat()
		val screenHeight = window.screenHeight.toFloat()

		// ImGui works in real screen pixels, where Minecraft's GUI works in
		// scaled ones, so the player's GUI scale is folded in here — a toast
		// should be the same size as the rest of their interface, not the same
		// number of physical pixels.
		val guiScale = (window.screenWidth / window.guiScaledWidth.coerceAtLeast(1).toFloat())
			.coerceAtLeast(1f)
		val factor = scale.value.toFloat() * BASE_SCALE * guiScale

		val top = position.selectedIndex == TOP_LEFT || position.selectedIndex == TOP_RIGHT
		val left = position.selectedIndex == TOP_LEFT || position.selectedIndex == BOTTOM_LEFT

		// Newest nearest the edge it came from, so the one that just arrived is
		// where the eye already is.
		val order = if (top) shown.toList() else shown.toList().asReversed()

		var offset = 0f
		for (toast in order) {
			val height = draw(toast, now, screenWidth, screenHeight, factor, top, left, offset)
			offset += height + SPACING * factor
		}
	}

	/** Draws one pill and answers how tall it was, so the next can stack on it. */
	private fun draw(
		toast: Toast,
		now: Long,
		screenWidth: Float,
		screenHeight: Float,
		factor: Float,
		top: Boolean,
		left: Boolean,
		offset: Float,
	): Float {
		val elapsed = (now - toast.startedAt) / 1_000_000_000.0

		val sourceSize = SOURCE_SIZE * factor
		val messageSize = MESSAGE_SIZE * factor
		val padding = PADDING * factor
		val badgePadding = BADGE_PADDING * factor
		val gap = GAP * factor

		val sourceWidth = ImGuiRuntime.textWidth(toast.source, sourceSize)
		val message = toast.text(toast.secondsLeft(now))
		val messageWidth = ImGuiRuntime.textWidth(message, messageSize)
		val textHeight = ImGuiRuntime.textHeight(toast.source, sourceSize)

		val badgeWidth = sourceWidth + badgePadding * 2
		val badgeHeight = textHeight + BADGE_VERTICAL * factor * 2
		// The badge is inset from the top and bottom by the same padding it is
		// inset from the left, which is what makes it sit square in the pill.
		val height = badgeHeight + padding * 2

		// Plus however far the end cap has curved back in by the time it reaches
		// the top and bottom of the text, which is the part a flat inset misses.
		val radius = height / 2f
		val halfText = textHeight / 2f
		val capInset = radius - sqrt((radius * radius - halfText * halfText).coerceAtLeast(0f))
		val endPadding = END_PADDING * factor + capInset

		val width = padding + badgeWidth + gap + messageWidth + endPadding

		// In and out at the ends of its life, and a nudge in from its own edge so
		// it arrives rather than appearing.
		val appearing = (elapsed / FADE_SECONDS).coerceIn(0.0, 1.0)
		val leaving = ((toast.life - elapsed) / FADE_SECONDS).coerceIn(0.0, 1.0)
		val opacity = minOf(appearing, leaving).toFloat()
		val slide = SLIDE * factor * (1f - appearing.toFloat())

		val margin = MARGIN * factor
		val x = if (left) margin - slide else screenWidth - margin - width + slide
		val y = if (top) margin + offset else screenHeight - margin - height - offset

		// Where it is, for the click that wants it gone.
		toast.left = x
		toast.top = y
		toast.right = x + width
		toast.bottom = y + height

		val list = ImGui.getForegroundDrawList()

		// The pill: rounded to its own half-height, which is what makes it a
		// pill rather than a rectangle with soft corners.
		list.addRectFilled(x, y, x + width, y + height, abgr(backgroundColor.argb, opacity), height / 2f)

		val badgeX = x + padding
		val badgeY = y + (height - badgeHeight) / 2f
		list.addRectFilled(
			badgeX,
			badgeY,
			badgeX + badgeWidth,
			badgeY + badgeHeight,
			abgr(badgeColor.argb, opacity),
			badgeHeight / 2f,
		)

		val textY = y + (height - textHeight) / 2f
		list.addText(
			ImGuiRuntime.font,
			sourceSize.roundToInt(),
			badgeX + badgePadding,
			textY,
			abgr(sourceColor.argb, opacity),
			toast.source,
		)
		list.addText(
			ImGuiRuntime.font,
			messageSize.roundToInt(),
			badgeX + badgeWidth + gap,
			textY,
			abgr(if (toast.error) errorColor.argb else messageColor.argb, opacity),
			message,
		)

		return height
	}

	/**
	 * A colour in the order ImGui's draw lists want it, faded by [opacity].
	 *
	 * Dear ImGui packs a colour as ABGR where everything else here is ARGB, so
	 * the two ends swap on the way in.
	 */
	private fun abgr(argb: Int, opacity: Float): Int {
		val alpha = (((argb ushr 24) and 0xFF) * opacity).toInt().coerceIn(0, 255)
		val red = (argb ushr 16) and 0xFF
		val green = (argb ushr 8) and 0xFF
		val blue = argb and 0xFF
		return (alpha shl 24) or (blue shl 16) or (green shl 8) or red
	}
}
