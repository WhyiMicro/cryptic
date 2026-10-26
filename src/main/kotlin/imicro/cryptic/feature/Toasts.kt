package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.component.ResolvableProfile
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Cryptic's own notifications, styled after Essential's: a dark box with the module's name as
 * title, the message underneath, and a bar that fills as time runs out. They slide in from the edge
 * and the stack closes up when one leaves. Hovering pauses the bar, a click finishes it, dragging
 * it toward its edge throws it away. Drawn through Minecraft's GUI renderer at vanilla's toast
 * layer, so it shows over any screen and on any backend.
 */
object Toasts {
	private const val TOP_LEFT = 0
	private const val TOP_RIGHT = 1
	private const val BOTTOM_LEFT = 2

	// In GUI pixels, before [scale].
	private const val WIDTH = 175
	private const val PADDING = 7
	private const val BOTTOM_PADDING = 4
	private const val TITLE_GAP = 6
	private const val LINE_SPACING = 10
	private const val TIMER_HEIGHT = 3
	private const val EDGE = 5
	private const val SPACING = 5

	/** A player's face, one line tall, and the gap after it (Essential's 8 and 5). */
	private const val HEAD = 8
	private const val HEAD_GAP = 5

	// Essential's timings, in seconds.
	private const val SLIDE_SECONDS = 0.5f
	private const val COLLAPSE_SECONDS = 0.25f
	private const val FINISH_SECONDS = 1.25f

	/** How far, as a share of its width, a drag must go to throw it away. */
	private const val THROW_SHARE = 0.25f

	/** Shown at once before the oldest is dropped. */
	private const val MAX_SHOWN = 5

	// Essential's palette.
	private const val BACKGROUND = 0xFF181818.toInt()
	private const val BORDER = 0xFF303030.toInt()
	private const val BORDER_HOVER = 0xFF757575.toInt()
	private const val TIMER = 0xFF474747.toInt()
	private const val TIMER_HOVER = 0xFF5C5C5C.toInt()
	private const val TITLE = 0xFFE5E5E5.toInt()
	private const val TITLE_ERROR = 0xFFCC2929.toInt()
	private const val BODY = 0xFFBFBFBF.toInt()

	@JvmField
	val position = DropdownModuleSetting(
		id = "position",
		label = "Position",
		options = listOf("Top left", "Top right", "Bottom left", "Bottom right"),
		defaultIndex = 3,
		description = "Which corner notifications stack in.",
	)

	@JvmField
	val seconds = SliderModuleSetting(
		id = "seconds",
		label = "Seconds",
		defaultValue = 4.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		unit = "s",
		description = "How long one stays up once it has slid in.",
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Scale",
		defaultValue = 1.0,
		min = 0.5,
		max = 2.0,
		step = 0.1,
		description = "Their size, where 1 is the size of the rest of your interface.",
	)

	/** Raises one so the look can be checked while setting it. */
	private val test = ButtonModuleSetting("test", "Show toast", action = {
		show("Toast Notifications", "Click to dismiss, or §fdrag it away§7.", color = Colors.CRYPTIC)
	})

	@JvmField
	val module = Module(
		id = "toasts",
		name = "Toast Notifications",
		description = "Cryptic's own notifications",
		category = ModuleCategory.MISC,
		enabled = true,
		settings = listOf(position, seconds, scale, test),
	)

	private enum class Phase { IN, TIMER, FINISHING, OUT, COLLAPSE }

	/** One notification. [text] is read every frame, so it can count down; [keys] makes it a question. */
	private class Toast(
		val source: String,
		val text: (secondsLeft: Int) -> String,
		val error: Boolean,
		val life: Float,
		val id: String? = null,
		val keys: Map<Int, () -> Unit> = emptyMap(),
		/** The title colour, saying what kind of notice it is. Errors stay red. */
		val color: Int? = null,
		/** A player whose face is drawn on the left. */
		val head: String? = null,
		/** A centred last line: the keys a question answers to. */
		val footer: String? = null,
	) {
		var phase = Phase.IN
		var phaseTime = 0f

		/** How much of its time has run, 0 to 1: the bar. */
		var timer = 0f
		var finishFrom = 0f

		/** How far it's pushed back toward its edge, by a drag or on the way out. */
		var slide = 0f
		var drag = 0f

		var left = 0f
		var top = 0f
		var right = 0f
		var bottom = 0f
		var height = 0f

		/** Set once answered or dismissed, so it can't be answered twice. */
		var done = false

		fun secondsLeft(): Int = ceil(life * (1f - timer)).toInt().coerceAtLeast(0)

		fun finish() {
			if (done) return
			done = true
			if (phase == Phase.IN || phase == Phase.TIMER) {
				finishFrom = timer
				phase = Phase.FINISHING
				phaseTime = 0f
			}
		}

		fun leave() {
			done = true
			if (phase != Phase.OUT && phase != Phase.COLLAPSE) {
				phase = Phase.OUT
				phaseTime = 0f
			}
		}
	}

	private val shown = ArrayList<Toast>()
	private var lastFrame = 0L

	/** The toast being pressed, and where the press began. */
	private var pressed: Toast? = null
	private var pressX = 0f
	private var dragged = false

	/**
	 * Raises a notification. [source] is the speaking module's name, [message] what it says. [life]
	 * overrides the configured time.
	 */
	fun show(
		source: String, message: String, error: Boolean = false, life: Double = seconds.value,
		color: Int? = null, head: String? = null,
	) {
		if (!module.enabled) {
			toChat(source, message, error)
			return
		}
		add(Toast(source, { message }, error, life.toFloat(), color = color, head = head))
	}

	/**
	 * Raises one that waits for a key. [keys] maps a GLFW key to its action; [id] lets its module
	 * take it down, and asking again replaces it. [footer] names the keys.
	 */
	fun ask(
		source: String, id: String, life: Double, keys: Map<Int, () -> Unit>,
		color: Int? = null, head: String? = null, footer: String? = null,
		text: (secondsLeft: Int) -> String,
	) {
		if (!module.enabled) {
			askInChat(source, id, life, keys, text(ceil(life).toInt()))
			return
		}
		shown.removeAll { it.id == id }
		add(Toast(source, text, false, life.toFloat(), id, keys, color, head, footer))
	}

	// ---- With notifications off ---------------------------------------------------------------
	//
	// Every notification still reaches you, as a chat line. A question's keys become clickable
	// answers, which run "/cryptic answer" and do what the key would have.

	private fun prefix(source: String, error: Boolean) = "§8[Cryptic] " + (if (error) "§c" else "§7") + "$source: §7"

	private fun toChat(source: String, message: String, error: Boolean) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal(prefix(source, error) + message))
	}

	/** A question asked in chat, its answers by key name, until it runs out. */
	private class Question(val keys: Map<String, () -> Unit>, val until: Long)

	private val questions = HashMap<String, Question>()

	private fun keyName(key: Int): String =
		org.lwjgl.glfw.GLFW.glfwGetKeyName(key, 0)?.uppercase() ?: key.toString()

	private fun askInChat(source: String, id: String, life: Double, keys: Map<Int, () -> Unit>, text: String) {
		if (keys.isEmpty()) {
			toChat(source, text, false)
			return
		}
		val named = keys.entries.associate { (key, action) -> keyName(key) to action }
		questions[id] = Question(named, System.currentTimeMillis() + (life * 1000).toLong())
		val line = Component.literal(prefix(source, false) + text)
		for ((index, name) in named.keys.withIndex()) {
			// The first answer is the yes.
			val colour = if (index == 0) "§a" else "§c"
			line.append(Component.literal(" "))
			line.append(
				Component.literal("$colour§l[$name]").withStyle {
					it.withClickEvent(net.minecraft.network.chat.ClickEvent.RunCommand("cryptic answer $id $name"))
						.withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Click to answer $name")))
				},
			)
		}
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(line)
	}

	/** "/cryptic answer <id> <key>": a chat answer clicked. Each question takes one answer. */
	fun answer(id: String, key: String): String {
		val question = questions[id] ?: return "That question is gone."
		if (System.currentTimeMillis() > question.until) {
			questions.remove(id)
			return "Too late: that question ran out."
		}
		val action = question.keys[key.uppercase()] ?: return "That isn't one of its answers."
		questions.remove(id)
		action()
		return "Answered $key."
	}

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("cryptic").then(
					net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("answer").then(
						net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word()).then(
							net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("key", com.mojang.brigadier.arguments.StringArgumentType.word()).executes { context ->
								val reply = answer(
									com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
									com.mojang.brigadier.arguments.StringArgumentType.getString(context, "key"),
								)
								context.source.sendFeedback(Component.literal("§8[Cryptic] §7$reply"))
								1
							},
						),
					),
				),
			)
		}
	}

	/**
	 * Title colours, one per kind of notice, so the speaker is known before reading. Only on the
	 * title; the body stays grey with names and numbers in white.
	 */
	object Colors {
		const val PARTY = 0xFF5555FF.toInt()
		const val FRIEND = 0xFF55FF55.toInt()
		const val COOKIE = 0xFFF0A040.toInt()
		const val INVENTORY = 0xFFFFFF55.toInt()
		const val TELEPORT = 0xFFAA55FF.toInt()
		const val MINING = 0xFF55FFFF.toInt()
		const val GOOD = 0xFF55FF55.toInt()
		const val REWARD = 0xFFE0B551.toInt()
		const val BAZAAR = 0xFFFFAA00.toInt()

		/** Cryptic itself (settings and profiles), in your accent colour. */
		val CRYPTIC: Int get() = imicro.cryptic.gui.menu.MenuStyle.accent()
	}

	private fun add(toast: Toast) {
		shown += toast
		// The oldest goes first, but questions outlive announcements.
		while (shown.count { !it.done } > MAX_SHOWN) {
			val victim = shown.firstOrNull { !it.done && it.keys.isEmpty() && it !== toast } ?: shown.first { !it.done }
			victim.leave()
		}
	}

	fun dismiss(id: String, slideAway: Boolean = false) {
		// Slid straight out for a notice that turned out wrong; otherwise its timer runs out first.
		shown.forEach { if (it.id == id) if (slideAway) it.leave() else it.finish() }
	}

	/** A key going down, offered to the newest question. True when one took it. */
	fun handleKey(key: Int): Boolean {
		if (!module.enabled) return false
		val toast = shown.lastOrNull { key in it.keys && !it.done } ?: return false
		toast.finish()
		toast.keys.getValue(key).invoke()
		return true
	}

	/**
	 * A keyboard key, answered only where it can't be typing: in the world or in a chest. A Y in
	 * chat must never accept an invite.
	 */
	@JvmStatic
	fun onKeyPressed(key: Int): Boolean {
		if (shown.none { it.keys.isNotEmpty() && !it.done }) return false
		val screen = Minecraft.getInstance().gui.screen()
		if (screen != null && screen !is ContainerScreen) return false
		return handleKey(key)
	}

	/** The left button going down. A press on a notification is kept from what's under it. */
	@JvmStatic
	fun onMousePressed(): Boolean {
		val (mx, my) = pointer() ?: return false
		val toast = shown.lastOrNull { !it.done && mx >= it.left && mx < it.right && my >= it.top && my < it.bottom } ?: return false
		pressed = toast
		pressX = mx
		dragged = false
		return true
	}

	/** The left button coming up: a click finishes it; a long enough drag throws it away. */
	@JvmStatic
	fun onMouseReleased(): Boolean {
		val toast = pressed ?: return false
		pressed = null
		when {
			!dragged -> {
				Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
				toast.finish()
			}
			toast.drag >= WIDTH * THROW_SHARE -> toast.leave()
		}
		return true
	}

	/** The pointer in GUI pixels, or null while it's the crosshair. */
	private fun pointer(): Pair<Float, Float>? {
		val minecraft = Minecraft.getInstance()
		if (minecraft.mouseHandler.isMouseGrabbed) return null
		val window = minecraft.window
		return minecraft.mouseHandler.getScaledXPos(window).toFloat() to minecraft.mouseHandler.getScaledYPos(window).toFloat()
	}

	fun clear() = shown.clear()

	/** Draws the stack from vanilla's toast layer (see `GuiMixin`), above any screen. */
	@JvmStatic
	fun extract(graphics: GuiGraphicsExtractor) {
		val now = System.nanoTime()
		val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
		lastFrame = now
		if (shown.isEmpty()) return
		if (!module.enabled) {
			shown.clear()
			return
		}

		val minecraft = Minecraft.getInstance()
		val font = minecraft.font
		val factor = scale.value.toFloat()
		val screenWidth = minecraft.window.guiScaledWidth / factor
		val screenHeight = minecraft.window.guiScaledHeight / factor
		val corner = position.selectedIndex
		val fromLeft = corner == TOP_LEFT || corner == BOTTOM_LEFT
		val fromTop = corner == TOP_LEFT || corner == TOP_RIGHT
		val pointer = pointer()?.let { (x, y) -> x / factor to y / factor }

		// The pressed one follows the pointer while held.
		pressed?.let { toast ->
			pointer?.let { (x, _) ->
				val moved = (if (fromLeft) pressX / factor - x else x - pressX / factor).coerceAtLeast(0f)
				if (moved > WIDTH * 0.02f) dragged = true
				if (dragged) toast.drag = moved
			}
		}

		val pose = graphics.pose()
		pose.pushMatrix()
		pose.scale(factor, factor)

		var stack = SPACING.toFloat()
		// The oldest nearest the corner, newer ones stacked beyond, so the rest close up when it
		// leaves.
		for (toast in shown.toList()) {
			advance(toast, dt, pointer)
			// Only a message sharing its row with the face is narrowed by it.
			val titled = toast.source.isNotEmpty()
			val textWidth = WIDTH - 2 - PADDING * 2 - (if (toast.head != null && !titled) HEAD + HEAD_GAP else 0)
			val lines = font.split(Component.literal(toast.text(toast.secondsLeft())), textWidth)
			val body = if (lines.isEmpty()) 0 else (lines.size - 1) * LINE_SPACING + 9
			var content = if (titled) 9 + (if (body > 0) TITLE_GAP + body else 0) else maxOf(body, 9)
			if (toast.footer != null) content += TITLE_GAP + 9
			val full = 1 + PADDING + content + BOTTOM_PADDING + 1
			val collapse = if (toast.phase == Phase.COLLAPSE) 1f - outExpo(toast.phaseTime / COLLAPSE_SECONDS) else 1f
			toast.height = full.toFloat()
			val taken = (full + SPACING) * collapse

			val offscreen = WIDTH + EDGE.toFloat()
			val travel = when (toast.phase) {
				Phase.IN -> 1f - outExpo(toast.phaseTime / SLIDE_SECONDS)
				Phase.OUT, Phase.COLLAPSE -> toast.slide
				else -> 0f
			}
			val pushed = (travel * offscreen + toast.drag).coerceAtMost(offscreen)
			val x = if (fromLeft) EDGE - pushed else screenWidth - EDGE - WIDTH + pushed
			val y = if (fromTop) stack else screenHeight - stack - full

			if (toast.phase != Phase.COLLAPSE) {
				toast.left = x * factor
				toast.top = y * factor
				toast.right = (x + WIDTH) * factor
				toast.bottom = (y + full) * factor
				draw(graphics, toast, lines, x, y, full, pointer)
			}
			stack += taken
		}
		pose.popMatrix()
		shown.removeAll { it.phase == Phase.COLLAPSE && it.phaseTime >= COLLAPSE_SECONDS }
	}

	private fun advance(toast: Toast, dt: Float, pointer: Pair<Float, Float>?) {
		toast.phaseTime += dt
		val hovered = pointer != null && pressed == null && toast.covers(pointer)
		when (toast.phase) {
			Phase.IN -> if (toast.phaseTime >= SLIDE_SECONDS) {
				toast.phase = Phase.TIMER
				toast.phaseTime = 0f
			}
			// The bar pauses while hovered, as Essential's does.
			Phase.TIMER -> {
				if (!hovered && pressed !== toast) toast.timer += dt / toast.life
				if (toast.timer >= 1f) {
					toast.timer = 1f
					toast.leave()
				}
			}
			Phase.FINISHING -> {
				val share = (toast.phaseTime / ((1f - toast.finishFrom) * FINISH_SECONDS).coerceAtLeast(0.05f)).coerceAtMost(1f)
				toast.timer = toast.finishFrom + (1f - toast.finishFrom) * outExpo(share)
				if (share >= 1f) {
					toast.phase = Phase.OUT
					toast.phaseTime = 0f
				}
			}
			Phase.OUT -> {
				toast.slide = inExpo((toast.phaseTime / SLIDE_SECONDS).coerceAtMost(1f))
				if (toast.phaseTime >= SLIDE_SECONDS) {
					toast.phase = Phase.COLLAPSE
					toast.phaseTime = 0f
				}
			}
			Phase.COLLAPSE -> Unit
		}
		// Released short of throwing: it springs back.
		if (pressed !== toast && toast.drag > 0f && toast.phase != Phase.OUT && toast.phase != Phase.COLLAPSE) {
			toast.drag = (toast.drag - dt * WIDTH * 4f).coerceAtLeast(0f)
		}
	}

	private fun Toast.covers(pointer: Pair<Float, Float>): Boolean {
		val factor = scale.value.toFloat()
		val (x, y) = pointer
		return x * factor >= left && x * factor < right && y * factor >= top && y * factor < bottom
	}

	private fun draw(
		graphics: GuiGraphicsExtractor, toast: Toast, lines: List<net.minecraft.util.FormattedCharSequence>,
		x: Float, y: Float, height: Int, pointer: Pair<Float, Float>?,
	) {
		val hovered = (pointer != null && toast.covers(pointer)) || pressed === toast
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		val font = Minecraft.getInstance().font

		// A one-pixel border, the body, and the bar along the body's bottom.
		graphics.fill(0, 0, WIDTH, height, if (hovered) BORDER_HOVER else BORDER)
		graphics.fill(1, 1, WIDTH - 1, height - 1, BACKGROUND)
		val barWidth = ((WIDTH - 2) * toast.timer).toInt()
		if (barWidth > 0) graphics.fill(1, height - 1 - TIMER_HEIGHT, 1 + barWidth, height - 1, if (hovered) TIMER_HOVER else TIMER)

		val left = 1 + PADDING
		var textY = 1 + PADDING
		// The face goes on the first row, beside the title or the message, which shift to make
		// room.
		val headX = left
		val faceWidth = if (toast.head != null) HEAD + HEAD_GAP else 0
		toast.head?.let { name -> drawFace(graphics, name, headX, textY, HEAD) }
		if (toast.source.isNotEmpty()) {
			val titleColor = when {
				toast.error -> TITLE_ERROR
				else -> toast.color ?: TITLE
			}
			graphics.text(font, toast.source, left + faceWidth, textY, titleColor, true)
			textY += 9 + TITLE_GAP
		}
		val bodyX = if (toast.source.isEmpty()) left + faceWidth else left
		for (line in lines) {
			graphics.text(font, line, bodyX, textY, BODY, true)
			textY += LINE_SPACING
		}
		toast.footer?.let { footer ->
			// Under the face and text, centred on the whole notice.
			val bottom = height - 1 - BOTTOM_PADDING - TIMER_HEIGHT - 9
			graphics.centeredText(font, Component.literal(footer), WIDTH / 2, bottom, BODY)
		}
		pose.popMatrix()
	}

	/**
	 * A player's face with its hat layer: from the tab list when there, otherwise looked up by name
	 * like a player head (default skin until it arrives).
	 */
	internal fun drawFace(graphics: GuiGraphicsExtractor, name: String, x: Int, y: Int, size: Int) {
		// A one-pixel black shadow down and right, as Essential gives avatars.
		graphics.fill(x + 1, y + 1, x + size + 1, y + size + 1, 0xFF000000.toInt())
		val info = Minecraft.getInstance().connection?.getPlayerInfoIgnoreCase(name)
		if (info != null) {
			PlayerFaceExtractor.extractRenderState(graphics, info.skin, x, y, size)
			return
		}
		val profile = profiles.getOrPut(name.lowercase()) { ResolvableProfile.createUnresolved(name) }
		PlayerFaceExtractor.extractRenderState(graphics, profile, x, y, size)
	}

	/** Profiles being looked up by name, so each is asked for once. */
	private val profiles = HashMap<String, ResolvableProfile>()

	private fun outExpo(t: Float): Float = if (t >= 1f) 1f else 1f - 2f.pow(-10f * t.coerceAtLeast(0f))
	private fun inExpo(t: Float): Float = if (t <= 0f) 0f else 2f.pow(10f * t.coerceAtMost(1f) - 10f)
}
