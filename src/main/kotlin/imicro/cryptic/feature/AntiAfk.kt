package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.MultiSelectModuleSetting
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.HypixelServer
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Input
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Moves a little now and then once you've been idle a while, so Hypixel doesn't send you to Limbo.
 *
 * Jumping, sneaking and strafing are laid over the movement input rather than pressed on the key
 * mappings: nothing is ever left held when it stops, and toggle sneak isn't flipped. Any real key,
 * click or mouse movement hands control straight back.
 */
object AntiAfk {
	private const val SOURCE = "Anti-AFK"
	private const val NANOS_PER_SECOND = 1_000_000_000L

	private const val LOOK = "Look around"
	private const val JUMP = "Jump"
	private const val SNEAK = "Sneak"
	private const val STRAFE = "Strafe"
	private const val SWING = "Swing"

	/** Ticks spent turning away, and again turning back. */
	private const val LOOK_TICKS = 8
	private const val LOOK_PAUSE_MIN = 10
	private const val LOOK_PAUSE_MAX = 30

	private const val JUMP_TICKS = 2
	private const val SNEAK_MIN = 6
	private const val SNEAK_MAX = 16
	private const val STRAFE_MIN = 3
	private const val STRAFE_MAX = 6

	/** How far the cursor has to move to count as you, in screen pixels. */
	private const val MOUSE_SLACK = 0.5

	private val actions = MultiSelectModuleSetting(
		id = "actions",
		label = "Actions",
		options = listOf(LOOK, JUMP, SNEAK, STRAFE, SWING),
		defaultSelected = setOf(LOOK, JUMP),
		description = "What it may do. One is picked at random each time. Swing can fire a held " +
			"item's left-click ability; strafing crouches so it can't walk off a ledge.",
	)

	private val interval = RangeModuleSetting(
		id = "interval",
		label = "Interval (seconds)",
		defaultLower = 20.0,
		defaultUpper = 45.0,
		min = 5.0,
		max = 120.0,
		step = 1.0,
		description = "The gap between actions, drawn fresh from the band each time.",
	)

	private val idleDelay = SliderModuleSetting(
		id = "idle_delay",
		label = "Start after",
		defaultValue = 60.0,
		min = 5.0,
		max = 600.0,
		step = 5.0,
		unit = "s",
		description = "How long you have to be idle before it starts.",
	)

	private val lookDistance = SliderModuleSetting(
		id = "look_distance",
		label = "Look distance",
		defaultValue = 15.0,
		min = 2.0,
		max = 45.0,
		step = 1.0,
		unit = "°",
		description = "How far it turns before turning back.",
		visibleIf = { actions.isSelected(LOOK) },
	)

	private val onlyUnfocused = ToggleModuleSetting(
		id = "only_unfocused",
		label = "Only when tabbed out",
		defaultValue = false,
		description = "Stays off while the game window has focus.",
	)

	private val pauseInMenus = ToggleModuleSetting(
		id = "pause_in_menus",
		label = "Pause in menus",
		defaultValue = true,
		description = "Waits while a chest, the chat or any other screen is open.",
	)

	private val hypixelOnly = ToggleModuleSetting(
		id = "hypixel_only",
		label = "Only on Hypixel",
		defaultValue = true,
	)

	private val announce = ToggleModuleSetting(
		id = "announce",
		label = "Notify when it starts",
		defaultValue = true,
	)

	@JvmField
	val module = Module(
		id = "anti_afk",
		name = "Anti-AFK",
		description = "Moves a little while you idle",
		category = ModuleCategory.GENERAL,
		restriction = Restriction.CHEAT,
		settings = listOf(actions, interval, idleDelay, lookDistance, onlyUnfocused, pauseInMenus, hypixelOnly, announce),
	)

	private var lastInputAt = System.nanoTime()
	private var lastMouseX = Double.NaN
	private var lastMouseY = Double.NaN

	private var running = false
	private var nextActionAt = 0L

	private var jumpTicks = 0
	private var sneakTicks = 0

	/** Strafing: ticks left on this side, ticks for the way back, and which side. */
	private var strafeTicks = 0
	private var strafeBackTicks = 0
	private var strafeLeft = false

	/** Looking: the turn as a whole, the tick it's on, and the pause before turning back. */
	private var lookYaw = 0f
	private var lookPitch = 0f
	private var lookTick = -1
	private var lookPause = 0

	/** A real key, click or wheel turn. Called from the input mixins, on the client thread. */
	@JvmStatic
	fun onInput() {
		lastInputAt = System.nanoTime()
		if (running) stop()
	}

	fun tick(client: Minecraft) {
		val player = client.player
		pollMouse(client)

		if (player == null || !module.enabled) {
			stop()
			lastInputAt = System.nanoTime()
			return
		}

		val now = System.nanoTime()
		val idle = now - lastInputAt >= (idleDelay.value * NANOS_PER_SECOND).toLong()
		val allowed = (!hypixelOnly.value || HypixelServer.connected) &&
			(!onlyUnfocused.value || !client.isWindowActive) &&
			(!pauseInMenus.value || client.gui.screen() == null)

		if (!idle || !allowed || actions.ticked.isEmpty()) {
			stop()
			return
		}

		if (!running) {
			running = true
			// The idle wait already counts as the first gap.
			nextActionAt = now
			if (announce.value) Toasts.show(SOURCE, "You're idle, so it's keeping you moving. Move to take over")
		}

		if (busy()) {
			step(player)
			return
		}

		if (now >= nextActionAt) {
			start(player)
			nextActionAt = now + (interval.random() * NANOS_PER_SECOND).toLong()
		}
	}

	/**
	 * Lays the current action over what the keyboard asked for. Called at the end of
	 * KeyboardInput.tick, so it only lasts as long as the action does.
	 */
	@JvmStatic
	fun input(keys: Input): Input {
		if (!running) return keys
		val strafing = strafeTicks > 0 || strafeBackTicks > 0
		if (jumpTicks <= 0 && sneakTicks <= 0 && !strafing) return keys
		val left = strafing && (if (strafeTicks > 0) strafeLeft else !strafeLeft)
		val right = strafing && !left
		return Input(
			keys.forward(),
			keys.backward(),
			keys.left() || left,
			keys.right() || right,
			keys.jump() || jumpTicks > 0,
			keys.shift() || sneakTicks > 0 || strafing,
			keys.sprint(),
		)
	}

	/** The cursor counts as input, so turning the camera hands control back too. */
	private fun pollMouse(client: Minecraft) {
		val x = client.mouseHandler.xpos()
		val y = client.mouseHandler.ypos()
		val moved = !lastMouseX.isNaN() && (abs(x - lastMouseX) > MOUSE_SLACK || abs(y - lastMouseY) > MOUSE_SLACK)
		lastMouseX = x
		lastMouseY = y
		if (moved) onInput()
	}

	private fun busy(): Boolean =
		jumpTicks > 0 || sneakTicks > 0 || strafeTicks > 0 || strafeBackTicks > 0 || lookTick >= 0

	private fun start(player: net.minecraft.client.player.LocalPlayer) {
		val choices = actions.options.filter { actions.isSelected(it) }
		when (choices.random()) {
			LOOK -> {
				val distance = lookDistance.value.toFloat() * (0.5f + 0.5f * Math.random().toFloat())
				lookYaw = if (Math.random() < 0.5) distance else -distance
				lookPitch = distance / 3f * (Math.random().toFloat() * 2f - 1f)
				lookTick = 0
				lookPause = LOOK_PAUSE_MIN + (Math.random() * (LOOK_PAUSE_MAX - LOOK_PAUSE_MIN)).toInt()
			}
			JUMP -> jumpTicks = JUMP_TICKS
			SNEAK -> sneakTicks = SNEAK_MIN + (Math.random() * (SNEAK_MAX - SNEAK_MIN)).toInt()
			STRAFE -> {
				strafeTicks = STRAFE_MIN + (Math.random() * (STRAFE_MAX - STRAFE_MIN)).toInt()
				strafeBackTicks = strafeTicks
				strafeLeft = Math.random() < 0.5
			}
			SWING -> player.swing(InteractionHand.MAIN_HAND)
		}
	}

	/** One tick of whatever is under way. Input reads the counters on the next tick. */
	private fun step(player: net.minecraft.client.player.LocalPlayer) {
		if (jumpTicks > 0) jumpTicks--
		if (sneakTicks > 0) sneakTicks--
		if (strafeTicks > 0) {
			strafeTicks--
		} else if (strafeBackTicks > 0) {
			strafeBackTicks--
		}
		if (lookTick >= 0) stepLook(player)
	}

	/** Eases out, waits, then eases back by the same amount, so it ends where it started. */
	private fun stepLook(player: net.minecraft.client.player.LocalPlayer) {
		val tick = lookTick
		val share = when {
			tick < LOOK_TICKS -> ease(tick + 1) - ease(tick)
			tick < LOOK_TICKS + lookPause -> 0f
			tick < LOOK_TICKS * 2 + lookPause -> {
				val back = tick - LOOK_TICKS - lookPause
				-(ease(back + 1) - ease(back))
			}
			else -> {
				lookTick = -1
				return
			}
		}
		if (share != 0f) {
			player.setYRot(player.yRot + lookYaw * share)
			player.setXRot((player.xRot + lookPitch * share).coerceIn(-90f, 90f))
		}
		lookTick++
	}

	/** How much of the turn is done after [tick] ticks, slow at both ends. */
	private fun ease(tick: Int): Float = ((1.0 - cos(PI * tick / LOOK_TICKS)) / 2.0).toFloat()

	/** Ends whatever is under way where it stands; the input override goes with it. */
	private fun stop() {
		running = false
		jumpTicks = 0
		sneakTicks = 0
		strafeTicks = 0
		strafeBackTicks = 0
		lookTick = -1
	}
}
