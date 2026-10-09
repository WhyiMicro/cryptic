package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.ContainerInput
import org.lwjgl.glfw.GLFW

/**
 * Keys for SkyBlock's loadout menu.
 *
 * Odin's Loadout Keybinds (BSD 3-Clause, Copyright (c) 2025 odtheking): in the
 * "(1/2) Loadout" menu a key presses the slot of the loadout it stands for, and
 * two more turn the page. The key that opens the menu is Odin's Command
 * Keybinds' Loadouts. Close GUI on swap is Cryptic's: the menu has done its job
 * once a loadout is put on, so it can go.
 *
 * The loadout keys are 1 to 0 to begin with, which in a chest would otherwise
 * swap the hovered item into the hotbar: inside the loadout menu they are
 * taken here instead. The page keys are the mouse's side buttons.
 */
object LoadoutManager {
	/** "(1/2) Loadout". */
	private val LOADOUT_TITLE = Regex("""\((\d+)/(\d+)\) Loadout""")
	private val FORMATTING = Regex("§.")

	/** The twelve loadouts, three to a row, in the order Odin numbers them. */
	private val LOADOUT_SLOTS = intArrayOf(
		14, 15, 16,
		23, 24, 25,
		32, 33, 34,
		41, 42, 43,
	)

	/** The page arrows. */
	private const val NEXT_PAGE_SLOT = 44
	private const val PREVIOUS_PAGE_SLOT = 17

	@JvmField
	val openKey = KeybindModuleSetting(
		id = "open_key",
		label = "Loadouts",
		description = "Opens the loadout menu (/loadout), anywhere in SkyBlock.",
	)

	@JvmField
	val closeOnSwap = ToggleModuleSetting(
		id = "close_on_swap",
		label = "Close GUI on swap",
		defaultValue = true,
		description = "Closes the loadout menu once a loadout is put on, by key or by click.",
	)

	private val pageSection = SectionModuleSetting("page_section", "Pages")

	@JvmField
	val nextPage = KeybindModuleSetting(
		id = "next_page",
		label = "Next page",
		description = "Turns the loadout menu to its next page.",
		defaultKey = KeybindModuleSetting.mouse(GLFW.GLFW_MOUSE_BUTTON_5),
	)

	@JvmField
	val previousPage = KeybindModuleSetting(
		id = "previous_page",
		label = "Previous page",
		description = "Turns the loadout menu to its previous page.",
		defaultKey = KeybindModuleSetting.mouse(GLFW.GLFW_MOUSE_BUTTON_4),
	)

	private val loadoutSection = SectionModuleSetting("loadout_section", "Loadout keys", startsCollapsed = true)

	private val DEFAULT_KEYS = intArrayOf(
		InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4,
		InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8,
		InputConstants.KEY_9, InputConstants.KEY_0, InputConstants.KEY_MINUS, InputConstants.KEY_EQUALS,
	)

	/** One key per loadout, Loadout 1 to Loadout 12. */
	val loadoutKeys: List<KeybindModuleSetting> = DEFAULT_KEYS.mapIndexed { index, key ->
		KeybindModuleSetting(
			id = "loadout_${index + 1}",
			label = "Loadout ${index + 1}",
			description = "Puts on loadout ${index + 1} of the page that is open.",
			defaultKey = key,
		)
	}

	@JvmField
	val module = Module(
		id = "loadout_manager",
		name = "Loadout Manager",
		description = "Keys for the loadout menu",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(openKey, closeOnSwap, pageSection, nextPage, previousPage, loadoutSection) + loadoutKeys,
	)

	/** Whether the open key was down last tick, so a held key opens the menu once. */
	private var openKeyDown = false

	/** Set by a swap: the menu is closed on the next tick, once the click has gone out. */
	private var closePending = false

	/** True while SkyBlock has to be told apart from the rest of Hypixel for this. */
	val needsSkyblock: Boolean get() = module.enabled

	fun tick(client: Minecraft) {
		if (closePending) {
			closePending = false
			if (loadoutPage(client.gui.screen()) != null) client.player?.closeContainer()
		}
		if (!module.enabled) {
			openKeyDown = false
			return
		}
		val down = client.gui.screen() == null && openKey.isDown(client.window)
		if (down && !openKeyDown && SkyblockLocation.onSkyblock) client.connection?.sendCommand("loadout")
		openKeyDown = down
	}

	/** The page the loadout menu is on and how many it has, or null when it is not open. */
	private fun loadoutPage(screen: Any?): Pair<Int, Int>? {
		if (screen !is AbstractContainerScreen<*>) return null
		val match = LOADOUT_TITLE.find(screen.title.string.replace(FORMATTING, "")) ?: return null
		val current = match.groupValues[1].toIntOrNull() ?: return null
		val total = match.groupValues[2].toIntOrNull() ?: return null
		return current to total
	}

	/** A key pressed in a screen. True when it was one of ours, which the screen then never sees. */
	@JvmStatic
	fun handleKeyPress(screen: AbstractContainerScreen<*>, key: Int): Boolean {
		if (!module.enabled) return false
		val page = loadoutPage(screen) ?: return false
		return act(screen, page) { it.matches(key) }
	}

	/** A mouse button pressed in a screen, for binds on the mouse — the side buttons by default. */
	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, button: Int): Boolean {
		if (!module.enabled) return false
		val page = loadoutPage(screen) ?: return false
		return act(screen, page) { it.matchesMouse(button) }
	}

	private fun act(screen: AbstractContainerScreen<*>, page: Pair<Int, Int>, pressed: (KeybindModuleSetting) -> Boolean): Boolean {
		val (current, total) = page
		val slot = when {
			pressed(nextPage) -> if (current < total) NEXT_PAGE_SLOT else return true
			pressed(previousPage) -> if (current > 1) PREVIOUS_PAGE_SLOT else return true
			else -> {
				val index = loadoutKeys.indexOfFirst(pressed)
				if (index < 0) return false
				LOADOUT_SLOTS[index].also { if (closeOnSwap.value && holdsLoadout(screen, it)) closePending = true }
			}
		}
		val client = Minecraft.getInstance()
		val player = client.player ?: return true
		client.gameMode?.handleContainerInput(screen.menu.containerId, slot, 0, ContainerInput.PICKUP, player)
		return true
	}

	/**
	 * A slot clicked in a screen by hand: a loadout clicked in the loadout menu
	 * is a swap too, as far as closing the menu goes.
	 */
	@JvmStatic
	fun onSlotClicked(slotId: Int, button: Int, input: ContainerInput) {
		if (!module.enabled || !closeOnSwap.value) return
		if (button != 0 || input != ContainerInput.PICKUP || slotId !in LOADOUT_SLOTS) return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
		if (loadoutPage(screen) == null || !holdsLoadout(screen, slotId)) return
		closePending = true
	}

	/** Whether a loadout slot has a loadout in it, rather than the glass that fills an empty one. */
	private fun holdsLoadout(screen: AbstractContainerScreen<*>, slot: Int): Boolean {
		val stack = screen.menu.slots.getOrNull(slot)?.item ?: return false
		if (stack.isEmpty) return false
		return !BuiltInRegistries.ITEM.getKey(stack.item).path.endsWith("stained_glass_pane")
	}
}
