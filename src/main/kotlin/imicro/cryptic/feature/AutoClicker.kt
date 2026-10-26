package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.TextModuleSetting
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
 * Clicks attack (or use) for you, at a rate drawn from a range. It drives the key mapping like a
 * real press, so vanilla still decides what the click hits and applies reach, cooldowns and miss
 * timers; nothing is sent that vanilla wouldn't.
 *
 * Timing ideas from RavenB++'s AutoClicker (read, not ported): the button is held for about half
 * the gap, and the rate drifts slower for a second or two and occasionally skips a beat, since a
 * flat random rate is itself a pattern. Running off the attack and use buttons is its idea too. Its
 * rotation jitter isn't taken.
 */
object AutoClicker {
	private const val NANOS_PER_MILLI = 1_000_000.0
	private const val NANOS_PER_SECOND = 1_000_000_000.0

	/** How far a jittery hand strays from its rhythm, either way. */
	private const val JITTER_SPREAD = 0.10

	/** A butterfly's two fingers: one early, the next late. */
	private const val BUTTERFLY_SHORT = 0.65
	private const val BUTTERFLY_LONG = 1.35

	/** A press is about half the gap, minus a little. */
	private const val HOLD_JITTER_MILLIS = 10.0

	/** How long a drift or stumble lasts before rolling again. */
	private const val PHASE_MIN_MILLIS = 500.0
	private const val PHASE_MAX_MILLIS = 2000.0

	/** How often the rate drifts slower, and by how much. */
	private const val DRIFT_CHANCE = 0.15
	private const val DRIFT_MIN = 1.10
	private const val DRIFT_MAX = 1.25

	/** How often a beat is skipped, and for how long. */
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
	 * A real key mapping, so it can be a mouse button. Left click works too: the trigger is read
	 * from the device, so it doesn't see the clicker's own presses. Unused in attack/use mode.
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

	/**
	 * Items the clicker leaves alone, joined by "|": each its SkyBlock uuid (that one item), else its
	 * id, else its name. Saved with the profile; edited only through the button.
	 */
	private val blacklist = TextModuleSetting(
		id = "blacklist",
		label = "Blacklist",
		maxLength = 8192,
		visibleIf = { false },
	)

	private val blacklistHeld = ButtonModuleSetting(
		id = "blacklist_held",
		label = "Blacklist held item",
		action = ::toggleHeldItem,
	)

	private val clearBlacklist = ButtonModuleSetting(
		id = "clear_blacklist",
		label = "Clear blacklist",
		action = {
			blacklist.value = ""
			Toasts.show(SOURCE, "The blacklist is empty")
		},
	)

	private const val SOURCE = "Auto Clicker"

	private fun blacklisted(): Set<String> = blacklist.value.split('|').filter { it.isNotEmpty() }.toSet()

	/** What the blacklist knows an item by: this one item if it has a uuid, otherwise its kind. */
	private fun itemKey(stack: net.minecraft.world.item.ItemStack): String? {
		if (stack.isEmpty) return null
		val data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
		data.getString("uuid").orElse("").takeIf { it.isNotEmpty() }?.let { return "uuid:$it" }
		imicro.cryptic.skyblock.SkyblockItem.id(stack).takeIf { it.isNotEmpty() }?.let { return "id:$it" }
		return "name:" + stack.hoverName.string
	}

	/** Puts the held item on the blacklist, or takes it off. */
	private fun toggleHeldItem() {
		val stack = Minecraft.getInstance().player?.mainHandItem
		val key = stack?.let(::itemKey)
		if (stack == null || key == null) {
			Toasts.show(SOURCE, "Hold the item first", true)
			return
		}
		val list = blacklisted().toMutableSet()
		val name = stack.hoverName.string
		if (list.remove(key)) {
			Toasts.show(SOURCE, "§f$name §7is off the blacklist")
		} else {
			list += key
			Toasts.show(SOURCE, "§f$name §7is blacklisted: the clicker stops while it's held")
		}
		blacklist.value = list.joinToString("|")
	}

	private fun holdsBlacklisted(player: net.minecraft.world.entity.player.Player): Boolean {
		val key = itemKey(player.mainHandItem) ?: return false
		return key in blacklisted()
	}

	@JvmField
	val module = Module(
		id = "auto_clicker",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
		name = "Auto Clicker",
		description = "Clicks for you, like a human",
		category = ModuleCategory.GENERAL,
		supportsKeybind = true,
		keybind = clickKeybind,
		settings = listOf(cps, pattern, mode, rightClick, allowBreaking, blacklistHeld, clearBlacklist, blacklist),
	)

	private var toggled = false
	private var wasTriggerDown = false

	/** The button currently held down, remembered so releasing frees the right one if the target changed. */
	private var heldKey: InputConstants.Key? = null

	private var nextPressAt = 0L
	private var nextReleaseAt = 0L

	private var butterflyLong = false

	/** The current slower phase, and when it's rolled again. */
	private var driftMultiplier = 1.0
	private var driftUntil = 0L
	private var stumbleUntil = 0L

	/**
	 * Runs per frame: at useful rates a press and its release fit inside one tick (12 CPS holds for
	 * 40ms), so per-tick timing would halve the reachable rate.
	 */
	@JvmStatic
	fun frame() {
		val client = Minecraft.getInstance()
		val player = client.player

		// Switching off or leaving the world stops it.
		if (!module.enabled || player == null) {
			stop()
			toggled = false
			wasTriggerDown = false
			return
		}

		// Mining is your own hold; the clicker steps aside entirely.
		if (breakingBlock(client)) {
			stop()
			handBack(client, attackKey(client))
			return
		}

		// A blacklisted item in hand: the clicker steps aside, as for mining.
		if (holdsBlacklisted(player)) {
			stop()
			handBack(client, attackKey(client))
			handBack(client, useKey(client))
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
				// A press behind an open screen belongs to the menu.
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

		// Bows, eating and drinking need a held button; chopping it into clicks would cancel them.
		// Right click mode is the exception, since that's the point.
		val spammingUse = rightClick.value && target != null && target == useKey(client)
		val ready = usable && (spammingUse || !player.isUsingItem())

		// A screen opening pauses rather than stops, so a toggle survives typing a message.
		if (target == null || !ready) {
			stop()
			return
		}

		val now = System.nanoTime()
		if (nextPressAt == 0L) nextPressAt = now

		// Releasing the button and forgetting the next deadline are separate; doing both at once
		// once held the button down for good.
		if (heldKey != null && (heldKey != target || now >= nextReleaseAt)) letGo()

		if (heldKey == null && now >= nextPressAt) {
			val due = nextPressAt
			val delay = nextDelayNanos(now)
			press(target)
			nextReleaseAt = now + holdNanos(delay)

			// Measured from when the press was due, so lateness doesn't add to every gap.
			nextPressAt = (due + delay).coerceAtLeast(now)
		}
	}

	/**
	 * The button the bound modes repeat (attack unless asked), read off the mappings so rebinds are
	 * followed.
	 */
	private fun repeatedKey(client: Minecraft): InputConstants.Key? =
		if (rightClick.value) useKey(client) else attackKey(client)

	/**
	 * Whether you're mining, so the clicker stays out: attack must be physically down (asked of the
	 * device, so the clicker's own presses don't count) and the crosshair on a block (swinging at a
	 * mob in front of a wall is still swinging). Mining is one unbroken hold, and the clicker's
	 * releases would reset it.
	 */
	private fun breakingBlock(client: Minecraft): Boolean {
		if (!allowBreaking.value) return false
		if (!physicallyDown(client, attackKey(client))) return false
		return client.hitResult?.type == HitResult.Type.BLOCK
	}

	/** Whichever of attack and use is held, or null. Attack wins when both are (block-hitting). */
	private fun buttonUnderFinger(client: Minecraft): InputConstants.Key? {
		val attack = attackKey(client)

		// Use is repeated only when asked for, and then it wins. Otherwise right click is left
		// alone: holding it fires sword abilities, and repeating would drain your mana.
		if (rightClick.value) {
			val use = useKey(client)
			if (physicallyDown(client, use)) return use
		}

		if (physicallyDown(client, attack)) return attack
		return null
	}

	/**
	 * Hands the button back to the finger really on it. [letGo] sets the mapping up, which is right
	 * between clicks but wrong here: mining runs off the mapping, so vanilla thought the button was
	 * released and wouldn't mine until it was pressed again.
	 */
	private fun handBack(client: Minecraft, key: InputConstants.Key?) {
		if (key == null) return
		KeyMapping.set(key, physicallyDown(client, key))
	}

	private fun bindDown(client: Minecraft): Boolean {
		if (CrypticClient.autoClickerKey.isUnbound) return false
		val key = boundKey() ?: return false
		// A scancode bind can't be a mouse button, so it's never the one being driven; the mapping
		// is safe.
		if (key.type == InputConstants.Type.SCANCODE) return CrypticClient.autoClickerKey.isDown
		return physicallyDown(client, key)
	}

	/**
	 * Whether a key is physically down, asked of the device rather than the mapping, which reflects
	 * the clicker's own presses.
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
	 * Whether a press now would be read as a click. Minecraft only drains clicks with no screen or
	 * overlay, so clicks behind an inventory would pile up and fire on close.
	 */
	private fun canClick(client: Minecraft): Boolean =
		client.gui.screen() == null && client.gui.overlay() == null && client.mouseHandler.isMouseGrabbed

	private fun press(key: InputConstants.Key) {
		KeyMapping.set(key, true)
		KeyMapping.click(key)
		heldKey = key
	}

	/** Lets the button up, keeping the next press's schedule. */
	private fun letGo() {
		val key = heldKey ?: return
		KeyMapping.set(key, false)
		heldKey = null
	}

	/** The same, and forgets the schedule, when clicking is over. */
	private fun stop() {
		letGo()
		nextPressAt = 0L
		nextReleaseAt = 0L
	}

	/** What attack and use are bound to, since both are rebindable. */
	private fun attackKey(client: Minecraft): InputConstants.Key? = keyOf(client.options.keyAttack)

	private fun useKey(client: Minecraft): InputConstants.Key? = keyOf(client.options.keyUse)

	private fun boundKey(): InputConstants.Key? = keyOf(CrypticClient.autoClickerKey)

	private fun keyOf(mapping: KeyMapping): InputConstants.Key? {
		if (mapping.isUnbound) return null
		return (mapping as? KeyMappingAccessor)?.`cryptic$key`()
	}

	private fun holdNanos(delay: Long): Long =
		(delay / 2 - (Math.random() * HOLD_JITTER_MILLIS * NANOS_PER_MILLI).toLong())
			.coerceAtLeast(NANOS_PER_MILLI.toLong())

	/**
	 * Time until the next press: a rate from the range, the pattern's rhythm, then drift and
	 * stumbles so it's never stationary.
	 */
	private fun nextDelayNanos(now: Long): Long {
		// Smeared off the slider's whole numbers, so gaps don't cluster on a few values.
		val rate = (cps.random() + 0.4 * Math.random()).coerceAtLeast(1.0)
		var delay = NANOS_PER_SECOND / rate

		delay = when (pattern.selectedIndex) {
			// Jitter: one finger going fast, never quite evenly.
			0 -> delay * (1.0 + (Math.random() * 2.0 - 1.0) * JITTER_SPREAD)
			// Butterfly: two fingers alternating, so clicks come in pairs.
			1 -> {
				butterflyLong = !butterflyLong
				delay * (if (butterflyLong) BUTTERFLY_LONG else BUTTERFLY_SHORT)
			}
			// Random: the draw from the range is all of it.
			else -> delay
		}

		// Every second or two the rate slows for a while or a beat is skipped, on their own clocks
		// so they last long enough to be a phase.
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
