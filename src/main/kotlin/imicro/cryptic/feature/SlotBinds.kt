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
import imicro.cryptic.gui.TextModuleSetting
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
 * Ties two inventory slots together so one shift-click swaps them, for items swapped all day (an
 * AOTE for a Hyperion, a pickaxe for mining). Ported from Odin. With one end on the hotbar it's a
 * single hotbar-swap action; two non-hotbar slots take three cursor clicks, which "Allow outside of
 * hotbar" enables.
 */
object SlotBinds {
	/**
	 * Bindable slots: the player's own. Below five is the crafting grid; 5 to 8 are armour; the
	 * hotbar is the last nine. Odin's range.
	 */
	private val BINDABLE = 5 until 45
	private val HOTBAR = 36..44

	/** The four armour slots, helmet down to boots. */
	private val ARMOR = 5..8

	private const val LINES_HOVER = 0
	private const val LINES_HOVER_SHIFT = 1

	/** The profile that ships with Cryptic and can't be changed: no binds at all. */
	const val NO_BINDS = "No binds"

	/**
	 * The other profile that ships with Cryptic: always the binds you have right now, whichever
	 * profile they came from, so the binds you had when the game closed are there next time.
	 */
	const val LAST_SESSION = "Remember through sessions"

	/** What [LAST_SESSION] was called, and still the name its binds are saved under. */
	private const val SESSION_KEY = "Last session"

	private val BUILT_IN = setOf(NO_BINDS, LAST_SESSION, SESSION_KEY)

	@JvmField
	val profile = DropdownModuleSetting(
		id = "profile",
		label = "Profile",
		options = listOf(NO_BINDS),
		defaultIndex = 0,
		description = "Picking one loads its binds. Remember through sessions is always the binds you had last, kept when the game closes; No binds has none. Save under a name below to add your own.",
		liveOptions = { listOf(NO_BINDS, LAST_SESSION) + binds.names().filter { it !in BUILT_IN } },
		renamed = mapOf(SESSION_KEY to LAST_SESSION),
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
	val allowOutsideHotbar = ToggleModuleSetting(
		id = "allow_outside_hotbar",
		label = "Allow outside of hotbar",
		// Below it, a loaded profile's binds with neither end on the hotbar are left alone.
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		defaultValue = true,
		description = "Lets two slots be bound with neither on the hotbar, such as a worn helmet and a spare one. " +
			"That swap takes three clicks rather than one.",
	)

	@JvmField
	val binds = SlotMapModuleSetting(id = "binds", label = "Binds")

	@JvmField
	val profileName = TextModuleSetting(
		id = "profile_name",
		label = "Save as",
		maxLength = 32,
		hint = "Profile name",
		description = "Saves the binds you have now under this name. An existing name is overwritten.",
		button = ButtonModuleSetting("save", "Save", action = { saveToProfile() }),
	)

	private val delete = ButtonModuleSetting("delete", "Delete profile", action = { deleteProfile() }, visibleIf = { profile.selected !in BUILT_IN })

	/**
	 * The card's own bind button on the real key mapping, so it can be set from the module as well
	 * as the controls screen.
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
		description = "Shift-click to swap slots",
		category = ModuleCategory.GENERAL,
		supportsKeybind = true,
		keybind = bindKey,
		// The binds load first, so the saved profile name is in the dropdown when it's read.
		settings = listOf(binds, profile, profileName, delete, dungeonsOnly, allowOutsideHotbar, lineDisplay, lineColor, lineWidth),
	)

	/**
	 * The binds being edited. Changes stay here until saved under a name, so a bind you were trying
	 * out doesn't get saved by accident. Switching profile loads its binds over these, which also
	 * discards unwanted edits.
	 */
	private val working = LinkedHashMap<Int, Int>()

	/** Which profile [working] was loaded from, to notice a switch. */
	private var loadedFrom: String? = null

	private val current: MutableMap<Int, Int>
		get() {
			followProfile()
			return working
		}

	/** Loads the picked profile's binds once it changes. */
	private fun followProfile() {
		val chosen = profile.selected
		if (loadedFrom == chosen) return
		// The name box follows, so saving again updates the profile you picked.
		if (loadedFrom != null) profileName.value = if (chosen in BUILT_IN) "" else chosen
		loadedFrom = chosen
		working.clear()
		if (chosen != NO_BINDS) working.putAll(binds.binds(if (chosen == LAST_SESSION) SESSION_KEY else chosen))
	}

	/**
	 * Saves the binds being edited under the typed name and picks that profile;
	 * [imicro.cryptic.config.ConfigManager] writes it out with everything else.
	 */
	private fun saveToProfile() {
		val name = profileName.value.trim()
		val edited = LinkedHashMap(current)
		when {
			name.isEmpty() -> return say("Type a name for the profile first", error = true)
			BUILT_IN.any { name.equals(it, ignoreCase = true) } -> return say("§f$name §7is built in, pick another name", error = true)
			name.any { it == ';' || it == '=' } -> return say("A name can't contain ; or =", error = true)
			edited.isEmpty() -> return say("There are no binds to save", error = true)
		}
		val existed = name in binds.names()
		val target = binds.binds(name)
		target.clear()
		target.putAll(edited)
		profile.select(name)
		loadedFrom = name
		profileName.value = name
		val count = edited.size / 2
		val binds = if (count == 1) "1 bind" else "$count binds"
		say(if (existed) "Updated §f$name §7($binds)" else "Saved §f$name §7($binds)")
	}

	/** Keeps [LAST_SESSION]'s binds equal to the ones in use; the config saves them with everything else. */
	private fun rememberSession() {
		val session = binds.binds(SESSION_KEY)
		if (session == working) return
		session.clear()
		session.putAll(working)
	}

	private fun deleteProfile() {
		val name = profile.selected
		if (name in BUILT_IN) return
		binds.remove(name)
		profile.select(NO_BINDS)
		say("Deleted §f$name")
	}

	/**
	 * The first half of a bind being made. Cleared when the screen closes, since a half-bind only
	 * means something in that inventory.
	 */
	private var pending: Int? = null

	fun forget() {
		pending = null
	}

	/** Drops a half-made bind once its inventory has closed. */
	fun tick(client: Minecraft) {
		// Every tick, so the name box changes as soon as another profile is picked.
		if (module.enabled) {
			followProfile()
			rememberSession()
		}
		if (pending != null && client.gui.screen() !is InventoryScreen) pending = null
	}

	/**
	 * A key in the inventory: over a slot it starts a bind, over a second it finishes it, and over
	 * a bound slot it removes that bind (both ends). True when the key was ours.
	 */
	@JvmStatic
	fun handleKeyPress(screen: Screen, event: KeyEvent): Boolean {
		if (!module.enabled || screen !is InventoryScreen || !SkyblockLocation.onSkyblock) return false
		if (!CrypticClient.slotBindKey.matches(event)) return false

		val slot = hoveredSlot(screen) ?: return true
		val started = pending

		if (started == null) {
			// Binds are stored both ways, so both directions are removed.
			val other = current.remove(slot)
			if (other != null) {
				if (current[other] == slot) current.remove(other)
				say("Unbound slot §f$slot §7from §f$other")
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
		if (started !in HOTBAR && slot !in HOTBAR && !allowOutsideHotbar.value) {
			say("One of the two slots has to be on the hotbar", error = true)
			return true
		}

		current[started] = slot
		current[slot] = started
		say("Bound slot §f$started §7to §f$slot")
		return true
	}

	/**
	 * A shift-click on a bound slot, turned into the swap. True when handled, so the screen doesn't
	 * also move the item.
	 */
	@JvmStatic
	fun handleShiftClick(screen: AbstractContainerScreen<*>, button: Int): Boolean {
		if (!module.enabled || screen !is InventoryScreen || !swapsHere()) return false
		if (button != 0 || !Minecraft.getInstance().hasShiftDown()) return false

		val clicked = hoveredSlot(screen) ?: return false
		val bound = current[clicked] ?: return false

		val client = Minecraft.getInstance()
		val player = client.player ?: return false
		// The menu the player holds, which the game checks clicks against, not the screen's.
		val container = player.containerMenu.containerId

		// With an end on the hotbar, one "put this slot in that hotbar number" action does it all,
		// as Odin and NoammAddons send.
		val hotbar = if (clicked in HOTBAR) clicked else if (bound in HOTBAR) bound else null
		if (hotbar != null) {
			val slot = if (hotbar == clicked) bound else clicked
			client.gameMode?.handleContainerInput(container, slot, hotbar - HOTBAR.first, ContainerInput.SWAP, player)
			return true
		}

		if (!allowOutsideHotbar.value) return false
		// Something already in hand would land in the first slot clicked.
		if (!player.containerMenu.carried.isEmpty) return false

		// Without a hotbar end, the swap goes through the cursor: pick one up, drop it on the
		// other, put the returned item back. With armour involved, start from the non-armour slot:
		// starting on a worn piece empties the armour slot, and Hypixel ignores the third click,
		// leaving the piece in your hand.
		val first = if (clicked in ARMOR && bound !in ARMOR) bound else clicked
		val second = if (first == clicked) bound else clicked
		pickup(client, player, container, first)
		pickup(client, player, container, second)
		pickup(client, player, container, first)
		return true
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
	 * Only on SkyBlock. Making binds needs only SkyBlock; the dungeons-only toggle holds back just
	 * the swap, so a set can be arranged in the hub.
	 */
	private fun swapsHere(): Boolean =
		SkyblockLocation.onSkyblock && (!dungeonsOnly.value || DungeonLocation.inDungeon)

	/**
	 * Draws the line from a bound slot to its partner: for the bind being made (following the
	 * cursor), and for the bind under the cursor.
	 */
	@JvmStatic
	fun render(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (!module.enabled || screen !is InventoryScreen || !SkyblockLocation.onSkyblock) return

		val started = pending
		val hovered = hoveredSlot(screen)
		val bound = hovered?.let { slot -> current[slot]?.takeIf { usable(slot, it) } }

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

	private fun usable(a: Int, b: Int): Boolean = a in HOTBAR || b in HOTBAR || allowOutsideHotbar.value

	private fun centreOf(screen: AbstractContainerScreen<*>, slot: Int): Pair<Int, Int>? {
		val accessor = screen as? ContainerScreenAccessor ?: return null
		val target = screen.menu.slots.getOrNull(slot) ?: return null
		return (accessor.`cryptic$leftPos`() + target.x + 8) to (accessor.`cryptic$topPos`() + target.y + 8)
	}

	private fun hoveredSlot(screen: AbstractContainerScreen<*>): Int? =
		(screen as? ContainerScreenAccessor)?.`cryptic$hoveredSlot`()?.index?.takeIf { it in BINDABLE }

	/** A straight line between slots, drawn as one rotated quad with [GuiShapes], Odin's way. */
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

	/** Shown as a notification, since chat would scroll away. */
	private fun say(message: String, error: Boolean = false) {
		Toasts.show("Slot Binds", message, error, color = Toasts.Colors.INVENTORY)
	}
}
