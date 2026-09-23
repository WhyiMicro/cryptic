package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.mixin.KeyMappingAccessor
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW

/**
 * Clicks attack — or use — for you, at a rate drawn from a range rather than a
 * metronome.
 *
 * It clicks the way the mouse does. A physical press sets the mapping down and
 * queues a click onto it; Minecraft reads that queue back on the next tick to
 * decide what the click meant — an entity, a block, a swing at nothing, or
 * nothing at all because a bow is being drawn. Driving the same mapping is what
 * buys all of that: the reach test, the attack cooldown, the "hands busy" check
 * and the miss timer are vanilla's, and whatever packets follow are the ones
 * vanilla decided to send. An attack packet sent straight down the pipe would
 * skip every one of those checks.
 *
 * The timing is modelled on RavenB++'s AutoClicker (`OlziYT/RavenBS-Plus-Plus`,
 * 1.8.9 — read, not ported; none of its code is here). Two ideas of its are
 * worth having and are not obvious: the button is *held* for about half the gap
 * rather than merely counted, and the rate is not stationary. A flat random
 * draw between two bounds has the same average second after second, and that
 * steadiness is itself a pattern; a hand drifts slower for a second or two and
 * then simply misses a beat. Running straight off the attack and use buttons,
 * rather than off a bind of its own, is its idea too. What is deliberately not
 * taken from it is the rotation jitter — that moves your aim, which is a
 * different feature.
 */
object AutoClicker {
	private const val NANOS_PER_MILLI = 1_000_000.0
	private const val NANOS_PER_SECOND = 1_000_000_000.0

	/** How far a jittery hand strays from its own rhythm, either side. */
	private const val JITTER_SPREAD = 0.10

	/** A butterfly's two fingers: one lands early, the next one late. */
	private const val BUTTERFLY_SHORT = 0.65
	private const val BUTTERFLY_LONG = 1.35

	/** A press is about half the gap, less a little, as a real one is. */
	private const val HOLD_JITTER_MILLIS = 10.0

	/** How long a drift or a stumble is decided for, before it is rolled again. */
	private const val PHASE_MIN_MILLIS = 500.0
	private const val PHASE_MAX_MILLIS = 2000.0

	/** How often the rate settles into a slower phase, and by how much. */
	private const val DRIFT_CHANCE = 0.15
	private const val DRIFT_MIN = 1.10
	private const val DRIFT_MAX = 1.25

	/** How often a beat is missed outright, and by how long. */
	private const val STUMBLE_CHANCE = 0.20
	private const val STUMBLE_MIN_MILLIS = 50.0
	private const val STUMBLE_MAX_MILLIS = 150.0

	private const val MODE_HOLD = 0
	private const val MODE_TOGGLE = 1
	private const val MODE_BUTTONS = 2

	@JvmField
	val cps = RangeModuleSetting(
		id = "cps",
		label = "CPS",
		defaultLower = 8.0,
		defaultUpper = 12.0,
		min = 1.0,
		max = 20.0,
		step = 1.0,
		description = "The clicks per second to aim for. Every click draws a fresh rate from the band.",
	)

	@JvmField
	val pattern = DropdownModuleSetting(
		id = "pattern",
		label = "Pattern",
		options = listOf("Jitter", "Butterfly", "Random"),
		description = "The rhythm the clicks fall into on top of the rate.",
	)

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Hold", "Toggle", "While holding attack/use"),
		description = "Hold and Toggle run off the key bound above.",
	)

	@JvmField
	val rightClick = ToggleModuleSetting(
		id = "right_click",
		label = "Right click",
		defaultValue = false,
		description = "Repeats use rather than attack, the way holding right click does.",
	)

	@JvmField
	val allowBreaking = ToggleModuleSetting(
		id = "allow_breaking",
		label = "Allow breaking blocks",
		defaultValue = false,
		description = "Lets you mine while the clicker runs.",
	)

	/**
	 * Bound to a Minecraft key mapping, which is what lets it be a mouse button:
	 * side buttons arrive through the same mapping system keys do. Binding it to
	 * left click works too — the trigger is read from the device rather than
	 * from the mapping, so it does not see the clicker's own presses on that
	 * same button. Unused by the attack/use mode, which has no bind to read.
	 */
	private val clickKeybind = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.autoClickerKey.isUnbound) {
				"None"
			} else {
				CrypticClient.autoClickerKey.translatedKeyMessage.string.uppercase()
			}
		},
		onKeyChanged = { key ->
			CrypticClient.autoClickerKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "auto_clicker",
		name = "Auto Clicker",
		description = "Clicks for you at a human rate",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = true,
		keybind = clickKeybind,
		settings = listOf(cps, pattern, mode, rightClick, allowBreaking),
	)

	/** True while Toggle mode is switched on. The other modes never read it. */
	private var toggled = false
	private var wasTriggerDown = false

	/**
	 * The button the clicker currently has down, if any.
	 *
	 * Remembered rather than looked up again at release time, because the button
	 * being repeated can change under it — left let go while right is still
	 * held — and releasing the one it has now would leave the other stuck down.
	 */
	private var heldKey: InputConstants.Key? = null

	private var nextPressAt = 0L
	private var nextReleaseAt = 0L

	/** Which half of the butterfly's pair the next click is. */
	private var butterflyLong = false

	/** The slower phase the rate is currently in, and when it is rolled again. */
	private var driftMultiplier = 1.0
	private var driftUntil = 0L
	private var stumbleUntil = 0L

	/**
	 * Driven per frame rather than per tick.
	 *
	 * A press and the release that follows it are both inside one tick at any
	 * rate worth using — at 12 CPS the button is down for 40ms and a tick is 50
	 * — so running this on the tick could only ever manage a press one tick and
	 * a release the next, which halves the rate it can reach and turns every
	 * gap into a multiple of 50ms.
	 */
	@JvmStatic
	fun frame() {
		val client = Minecraft.getInstance()
		val player = client.player

		// Switching the module off or leaving the world is a stop, not a pause.
		if (!module.enabled || player == null) {
			stop()
			toggled = false
			wasTriggerDown = false
			return
		}

		// Mining is the player's own hold, and the clicker gets out of the way of
		// it completely — button included.
		if (breakingBlock(client)) {
			stop()
			handBack(client, attackKey(client))
			return
		}

		val usable = canClick(client)

		val target: InputConstants.Key? = when (mode.selectedIndex) {
			MODE_BUTTONS -> {
				toggled = false
				wasTriggerDown = false
				buttonUnderFinger(client)
			}
			MODE_TOGGLE -> {
				val trigger = bindDown(client)
				// A press behind an open screen is the menu's, not the clicker's.
				if (trigger && !wasTriggerDown && usable) toggled = !toggled
				wasTriggerDown = trigger
				if (toggled) repeatedKey(client) else null
			}
			else -> {
				val trigger = bindDown(client)
				wasTriggerDown = trigger
				toggled = false
				if (trigger) repeatedKey(client) else null
			}
		}

		// Drawing a bow, eating, drinking: the button is meant to be held for
		// those, and chopping the hold into clicks would cancel them outright.
		// Asking for right click is the one case where that is the whole point,
		// so the guard steps aside for it and for nothing else.
		val spammingUse = rightClick.value && target != null && target == useKey(client)
		val ready = usable && (spammingUse || !player.isUsingItem())

		// A screen going up pauses rather than stops: having to re-arm after
		// every message typed is not what a toggle is.
		if (target == null || !ready) {
			stop()
			return
		}

		val now = System.nanoTime()
		if (nextPressAt == 0L) nextPressAt = now

		// Letting the button up is not the same as forgetting when the next one
		// is due. Doing both at once is what made this hold the button down for
		// good: the press below found no deadline left to wait for and fired in
		// the same frame, so the button went down, up and down again without a
		// tick in between ever seeing it up.
		if (heldKey != null && (heldKey != target || now >= nextReleaseAt)) letGo()

		if (heldKey == null && now >= nextPressAt) {
			val due = nextPressAt
			val delay = nextDelayNanos(now)
			press(target)
			nextReleaseAt = now + holdNanos(delay)

			// Measured from when the press was due rather than from when it
			// happened, so a frame's worth of lateness is not added to every gap
			// in turn — but never so far behind that it owes a burst.
			nextPressAt = (due + delay).coerceAtLeast(now)
		}
	}

	/**
	 * The button the two bound modes repeat, which is attack unless asked.
	 *
	 * Attack and use are read off their mappings rather than assumed to be the
	 * two mouse buttons, so this follows a rebound right click as well.
	 */
	private fun repeatedKey(client: Minecraft): InputConstants.Key? =
		if (rightClick.value) useKey(client) else attackKey(client)

	/**
	 * Whether the player is mining, and the clicker should keep out of it.
	 *
	 * Two conditions together, and both are needed. The attack button has to be
	 * **physically** down — asked of the device, so the clicker's own presses on
	 * that same mapping do not answer yes and stand it down against itself — and
	 * the crosshair has to be on a block, so swinging at a mob in front of a
	 * wall is still swinging at the mob.
	 *
	 * What it buys is the block actually breaking: mining is one unbroken hold,
	 * and the clicker's releases reset the progress every time.
	 */
	private fun breakingBlock(client: Minecraft): Boolean {
		if (!allowBreaking.value) return false
		if (!physicallyDown(client, attackKey(client))) return false
		return client.hitResult?.type == HitResult.Type.BLOCK
	}

	/**
	 * Whichever of attack and use is being held, or null for neither.
	 *
	 * Attack wins when both are down, which is what block-hitting looks like:
	 * the swinging is the part worth repeating.
	 */
	private fun buttonUnderFinger(client: Minecraft): InputConstants.Key? {
		val attack = attackKey(client)

		// Use is repeated only when it has been asked for, and it wins over
		// attack when it has — the point of asking is that use is the button you
		// want repeated. Without the toggle, right click is left entirely alone:
		// holding it is how a sword's ability is fired, and repeating that empties
		// your mana in seconds.
		if (rightClick.value) {
			val use = useKey(client)
			if (physicallyDown(client, use)) return use
		}

		if (physicallyDown(client, attack)) return attack
		return null
	}

	/**
	 * Hands the button back to the finger that is really on it.
	 *
	 * [letGo] puts the mapping up, which is right between two clicks and wrong
	 * here. While somebody is holding the button themselves, a mapping that says
	 * "up" is a lie — and it is that lie that stopped blocks breaking: mining is
	 * driven off the mapping, so vanilla saw the button as released and would
	 * not start until it was physically let go and pressed again.
	 */
	private fun handBack(client: Minecraft, key: InputConstants.Key?) {
		if (key == null) return
		KeyMapping.set(key, physicallyDown(client, key))
	}

	/** Whether the module's own bind is down, for the two modes that use one. */
	private fun bindDown(client: Minecraft): Boolean {
		if (CrypticClient.autoClickerKey.isUnbound) return false
		val key = boundKey() ?: return false
		// A scancode bind has no device query, and cannot be a mouse button, so
		// it can never be the one the clicker is driving: the mapping is safe.
		if (key.type == InputConstants.Type.SCANCODE) return CrypticClient.autoClickerKey.isDown
		return physicallyDown(client, key)
	}

	/**
	 * Whether a key is physically down, asked of the device.
	 *
	 * Not asked of the key mapping, which is the whole trick to running off the
	 * attack button — or to binding this to left click. The clicker drives those
	 * mappings up and down, so a mapping is describing the clicker's own presses
	 * rather than the finger on the button.
	 */
	private fun physicallyDown(client: Minecraft, key: InputConstants.Key?): Boolean {
		if (key == null) return false
		val handle = client.window.handle()
		return when (key.type) {
			InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(handle, key.value) == GLFW.GLFW_PRESS
			InputConstants.Type.KEYSYM -> GLFW.glfwGetKey(handle, key.value) == GLFW.GLFW_PRESS
			else -> false
		}
	}

	/**
	 * Whether a press made now would be read back as a click.
	 *
	 * Minecraft only drains the click queue with no screen and no overlay up, so
	 * clicking behind an open inventory would not click — it would pile up and
	 * fire the lot the moment the inventory closed.
	 */
	private fun canClick(client: Minecraft): Boolean =
		client.gui.screen() == null && client.gui.overlay() == null && client.mouseHandler.isMouseGrabbed

	private fun press(key: InputConstants.Key) {
		KeyMapping.set(key, true)
		KeyMapping.click(key)
		heldKey = key
	}

	/** Lets the button up, leaving the next press where it was scheduled. */
	private fun letGo() {
		val key = heldKey ?: return
		KeyMapping.set(key, false)
		heldKey = null
	}

	/** The same, and forgets the schedule with it, for when clicking is over. */
	private fun stop() {
		letGo()
		nextPressAt = 0L
		nextReleaseAt = 0L
	}

	/**
	 * What attack and use are bound to, read off the mappings rather than
	 * assumed to be the two mouse buttons, because both are rebindable.
	 */
	private fun attackKey(client: Minecraft): InputConstants.Key? = keyOf(client.options.keyAttack)

	private fun useKey(client: Minecraft): InputConstants.Key? = keyOf(client.options.keyUse)

	private fun boundKey(): InputConstants.Key? = keyOf(CrypticClient.autoClickerKey)

	private fun keyOf(mapping: KeyMapping): InputConstants.Key? {
		if (mapping.isUnbound) return null
		return (mapping as? KeyMappingAccessor)?.`cryptic$key`()
	}

	/** How long the button stays down: about half the gap, as a real click is. */
	private fun holdNanos(delay: Long): Long =
		(delay / 2 - (Math.random() * HOLD_JITTER_MILLIS * NANOS_PER_MILLI).toLong())
			.coerceAtLeast(NANOS_PER_MILLI.toLong())

	/**
	 * How long until the next press.
	 *
	 * A rate drawn fresh from the range, a rhythm laid over it by the pattern,
	 * and then the two things that stop the whole thing being stationary.
	 */
	private fun nextDelayNanos(now: Long): Long {
		// Smeared off the slider's whole numbers, so the gaps do not all land on
		// the same short list of values.
		val rate = (cps.random() + 0.4 * Math.random()).coerceAtLeast(1.0)
		var delay = NANOS_PER_SECOND / rate

		delay = when (pattern.selectedIndex) {
			// Jitter: one finger going as fast as it can, never quite evenly.
			0 -> delay * (1.0 + (Math.random() * 2.0 - 1.0) * JITTER_SPREAD)
			// Butterfly: two fingers alternating, so the clicks arrive in pairs.
			1 -> {
				butterflyLong = !butterflyLong
				delay * (if (butterflyLong) BUTTERFLY_LONG else BUTTERFLY_SHORT)
			}
			// Random: the draw from the range is the whole of it.
			else -> delay
		}

		// Every second or two the rate settles slower for a while, and every
		// second or two a beat is missed outright. Rolled on their own clocks
		// rather than per click, so they last long enough to be a phase rather
		// than more noise on one gap.
		if (now > driftUntil) {
			driftMultiplier = if (Math.random() < DRIFT_CHANCE) {
				DRIFT_MIN + Math.random() * (DRIFT_MAX - DRIFT_MIN)
			} else {
				1.0
			}
			driftUntil = now + phaseNanos()
		}
		delay *= driftMultiplier

		if (now > stumbleUntil) {
			if (Math.random() < STUMBLE_CHANCE) {
				delay += (STUMBLE_MIN_MILLIS + Math.random() * (STUMBLE_MAX_MILLIS - STUMBLE_MIN_MILLIS)) *
					NANOS_PER_MILLI
			}
			stumbleUntil = now + phaseNanos()
		}

		return delay.toLong().coerceAtLeast(NANOS_PER_MILLI.toLong())
	}

	private fun phaseNanos(): Long =
		((PHASE_MIN_MILLIS + Math.random() * (PHASE_MAX_MILLIS - PHASE_MIN_MILLIS)) * NANOS_PER_MILLI).toLong()
}
