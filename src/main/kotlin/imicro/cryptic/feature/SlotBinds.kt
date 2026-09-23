package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.SlotMapModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.render.GuiShapes
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.inventory.ContainerInput

/**
 * Ties two inventory slots together, so one shift-click swaps them.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). SkyBlock asks
 * you to swap the same few items in and out of the same hotbar slot all day —
 * an aspect of the end for a wither impact, a pickaxe for a mining trip — and
 * doing it by dragging is slow and easy to get wrong under pressure. A bind
 * makes it one click on either end.
 *
 * Nothing here fabricates a click the player did not make: a bind turns one
 * click into the swap it obviously meant, in a menu they opened themselves.
 */
object SlotBinds {
	/**
	 * The slots a bind may touch, which are the player's own.
	 *
	 * Odin's range, and the reason for it is that the inventory screen numbers
	 * armour and the crafting grid below five: binding those would swap
	 * equipment rather than items. The hotbar is the last nine.
	 */
	private val BINDABLE = 5 until 45
	private val HOTBAR = 36..44

	/** Indices into [lineDisplay]. */
	private const val LINES_HOVER = 0
	private const val LINES_HOVER_SHIFT = 1

	@JvmField
	val profile = DropdownModuleSetting(
		id = "profile",
		label = "Profile",
		options = listOf("Profile 1", "Profile 2", "Profile 3", "Profile 4", "Profile 5", "Profile 6"),
		defaultIndex = 0,
		description = "Six independent sets of binds.",
	)

	@JvmField
	val lineDisplay = DropdownModuleSetting(
		id = "line_display",
		label = "Show lines",
		options = listOf("On hover", "On hover + shift", "Never"),
		defaultIndex = 0,
		description = "When to draw the line joining a slot to the one it is bound to.",
	)

	@JvmField
	val lineColor = ColorModuleSetting(
		id = "line_color",
		label = "Line",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Line width",
		defaultValue = 2.0,
		min = 1.0,
		max = 6.0,
		step = 0.5,
		description = "Rounded at both ends, so two lines meeting at a slot read as one thing.",
	)

	@JvmField
	val dungeonsOnly = ToggleModuleSetting(
		id = "dungeons_only",
		label = "Only in dungeons",
		defaultValue = false,
		description = "Shift-clicking only swaps inside a dungeon.",
	)

	@JvmField
	val binds = SlotMapModuleSetting(id = "binds", label = "Binds")

	@JvmField
	val save = ButtonModuleSetting("save", "Save to profile", action = { saveToProfile() })

	/**
	 * The module card's own bind button, pointed at the real key mapping.
	 *
	 * The mapping is Minecraft's, so its options file remembers it and the
	 * controls screen can rebind it — but a key that is the whole of how a
	 * module is used should be settable from the module, not only from a screen
	 * three menus away.
	 */
	private val bindKey = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.slotBindKey.isUnbound) {
				"None"
			} else {
				CrypticClient.slotBindKey.translatedKeyMessage.string.uppercase()
			}
		},
		onKeyChanged = { key ->
			CrypticClient.slotBindKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "slot_binds",
		name = "Slot Binds",
		description = "Swap two slots with shift-click",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = true,
		keybind = bindKey,
		settings = listOf(profile, save, dungeonsOnly, lineDisplay, lineColor, lineWidth, binds),
	)

	/**
	 * The binds being edited, which are not the binds that have been saved.
	 *
	 * Everything made in the inventory lands here and nowhere else until **Save
	 * to profile** is pressed. That is the difference between a bind you were
	 * trying out and a bind you meant: without it, one press of the key over the
	 * wrong slot is written into the profile before you have seen what it did,
	 * and the only way back is to make the opposite change by hand.
	 *
	 * Switching profile loads that profile's binds in over the top, which is
	 * also how an unwanted set of edits is thrown away.
	 */
	private val working = LinkedHashMap<Int, Int>()

	/** Which profile [working] was last loaded from, so a switch is noticed. */
	private var loadedFrom: String? = null

	private val current: MutableMap<Int, Int>
		get() {
			val chosen = profile.selected
			if (loadedFrom != chosen) {
				loadedFrom = chosen
				working.clear()
				working.putAll(binds.binds(chosen))
			}
			return working
		}

	/** True while the working set differs from what the profile holds. */
	val unsaved: Boolean
		get() = loadedFrom != null && working != binds.binds(profile.selected)

	/**
	 * Writes the binds being edited into the profile the dropdown is on.
	 *
	 * The one place anything is committed. [imicro.cryptic.config.ConfigManager]
	 * notices the change and persists it with everything else.
	 */
	private fun saveToProfile() {
		val chosen = profile.selected
		val target = binds.binds(chosen)
		target.clear()
		target.putAll(current)
		say("Saved ${target.size / 2} binds to $chosen")
	}

	/**
	 * The first half of a bind being made, waiting for its other end.
	 *
	 * Cleared whenever a screen closes, because a half-made bind is only
	 * meaningful while the inventory it was started in is still open.
	 */
	private var pending: Int? = null

	fun forget() {
		pending = null
	}

	/**
	 * Drops a half-made bind once the inventory it was started in has gone.
	 *
	 * A bind is two slots picked in one screen; carrying the first of them into
	 * the next screen the player opens would tie together two things they never
	 * pointed at together.
	 */
	fun tick(client: Minecraft) {
		if (pending != null && client.gui.screen() !is InventoryScreen) pending = null
	}

	/**
	 * A key pressed in the inventory: makes, or removes, a bind.
	 *
	 * Press it over a slot to start a bind and again over another to finish it;
	 * press it over a slot that already has one to take that bind away, both ends
	 * of it at once.
	 *
	 * Returns true when the key was ours and the screen should not see it.
	 */
	@JvmStatic
	fun handleKeyPress(screen: Screen, event: KeyEvent): Boolean {
		if (!module.enabled || screen !is InventoryScreen || !SkyblockLocation.onSkyblock) return false
		if (!CrypticClient.slotBindKey.matches(event)) return false

		val slot = hoveredSlot(screen) ?: return true
		val started = pending

		if (started == null) {
			// A bind is written both ways round, so removing it has to take both
			// — otherwise one end goes on pointing at a slot that no longer
			// points back, and unbinding reads as only half working.
			val other = current.remove(slot)
			if (other != null) {
				if (current[other] == slot) current.remove(other)
				say("Unbound $slot from $other")
			} else {
				pending = slot
			}
			return true
		}

		pending = null
		if (started == slot) {
			say("A slot cannot be bound to itself", error = true)
			return true
		}

		current[started] = slot
		current[slot] = started
		say("Bound $started to $slot")
		return true
	}

	/**
	 * A shift-click on a bound slot, turned into the swap it meant.
	 *
	 * Returns true when it was handled, so the screen does not also do its own
	 * shift-click — which would move the item to the other end of the inventory
	 * instead.
	 */
	@JvmStatic
	fun handleShiftClick(screen: AbstractContainerScreen<*>, button: Int): Boolean {
		if (!module.enabled || screen !is InventoryScreen || !swapsHere()) return false
		if (button != 0 || !Minecraft.getInstance().hasShiftDown()) return false

		val clicked = hoveredSlot(screen) ?: return false
		val bound = current[clicked] ?: return false

		val client = Minecraft.getInstance()
		val player = client.player ?: return false
		val container = screen.menu.containerId

		// The game has one action for "put this slot in that hotbar number", so
		// a bind with an end in the hotbar is a single click. Odin only allows
		// those; there is no need to. Two slots anywhere else swap the way a
		// person would do it by hand — pick one up, drop it on the other, put
		// what came back where the first one was — which is three actions and
		// works between any two slots at all.
		when {
			clicked in HOTBAR ->
				swapWithHotbar(client, player, container, bound, clicked)
			bound in HOTBAR ->
				swapWithHotbar(client, player, container, clicked, bound)
			else -> {
				pickup(client, player, container, clicked)
				pickup(client, player, container, bound)
				pickup(client, player, container, clicked)
			}
		}
		return true
	}

	/**
	 * Whether a shift-click should swap here.
	 *
	 * Only on SkyBlock, because another game's inventory is laid out for another
	 * game and a bind made for this one means nothing there. Making a bind is
	 * gated on SkyBlock alone; the dungeons-only toggle holds back just the swap,
	 * so a set can still be arranged in the hub before it is needed.
	 */
	private fun swapsHere(): Boolean =
		SkyblockLocation.onSkyblock && (!dungeonsOnly.value || DungeonLocation.inDungeon)

	private fun swapWithHotbar(
		client: Minecraft,
		player: net.minecraft.world.entity.player.Player,
		container: Int,
		slot: Int,
		hotbar: Int,
	) {
		client.gameMode?.handleContainerInput(
			container,
			slot,
			hotbar - HOTBAR.first,
			ContainerInput.SWAP,
			player,
		)
	}

	private fun pickup(
		client: Minecraft,
		player: net.minecraft.world.entity.player.Player,
		container: Int,
		slot: Int,
	) {
		client.gameMode?.handleContainerInput(container, slot, 0, ContainerInput.PICKUP, player)
	}

	/**
	 * Draws the line joining a bound slot to its partner.
	 *
	 * Two things get one: the bind being made, which follows the cursor so there
	 * is something to aim with, and the bind under the cursor, which is how you
	 * check what a slot is tied to without remembering.
	 */
	@JvmStatic
	fun render(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (!module.enabled || screen !is InventoryScreen || !SkyblockLocation.onSkyblock) return

		val started = pending
		val hovered = hoveredSlot(screen)
		val bound = hovered?.let { current[it] }

		val showBound = when (lineDisplay.selectedIndex) {
			LINES_HOVER -> bound != null
			LINES_HOVER_SHIFT -> bound != null && Minecraft.getInstance().hasShiftDown()
			else -> false
		}
		if (started == null && !showBound) return

		context.nextStratum()

		val from = started ?: hovered ?: return
		val start = centreOf(screen, from) ?: return
		val end = if (started != null) mouseX to mouseY else centreOf(screen, bound ?: return) ?: return

		line(context, start.first, start.second, end.first, end.second)
	}

	/** The middle of a slot on screen, which is where a line ends. */
	private fun centreOf(screen: AbstractContainerScreen<*>, slot: Int): Pair<Int, Int>? {
		val accessor = screen as? ContainerScreenAccessor ?: return null
		val target = screen.menu.slots.getOrNull(slot) ?: return null
		return (accessor.`cryptic$leftPos`() + target.x + 8) to (accessor.`cryptic$topPos`() + target.y + 8)
	}

	private fun hoveredSlot(screen: AbstractContainerScreen<*>): Int? =
		(screen as? ContainerScreenAccessor)?.`cryptic$hoveredSlot`()?.index?.takeIf { it in BINDABLE }

	/**
	 * A straight line between two slots, drawn Odin's way.
	 *
	 * The drawing itself is [GuiShapes]: one quad, turned to face along the
	 * line. Two earlier attempts are worth not repeating — a square stepped
	 * along the line is a staircase, and rasterising it a column at a time to
	 * smooth the edges in alpha is right in principle and was wrong here, badly
	 * enough that nothing but the two ends ever drew.
	 */
	private fun line(context: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int) {
		GuiShapes.line(
			context,
			x1.toFloat(),
			y1.toFloat(),
			x2.toFloat(),
			y2.toFloat(),
			lineWidth.value.toFloat(),
			lineColor.argb,
		)
	}

	/** Said as a notification rather than in chat, where it would scroll away. */
	private fun say(message: String, error: Boolean = false) {
		Toasts.show("Slot Binds", message, error)
	}
}
