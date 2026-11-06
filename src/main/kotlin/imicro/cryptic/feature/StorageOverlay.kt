package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.serialization.JsonOps
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.mixin.PackTweaksMixins
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.RegistryOps
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.TreeMap
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Takes over `/storage` and every Ender Chest page and backpack: one scrolling grid of all your
 * pages, with your inventory under it. The page you're in is live (move items as in the chest);
 * the rest show as last seen, and a click opens one. Unlocked pages not yet seen read "Click to
 * load". Click the open page's title to rename it; a search box lights up matching items.
 *
 * After NoammAddons' Storage Overlay (CC0): its layout, page numbering, the Storage menu's slots
 * and the items that mark a locked or empty slot there. Instead of drawing the menu's slots itself,
 * this moves the real ones onto the grid each frame, so vanilla (and every other mod) still handles
 * hovering, clicks, drags, number keys and slot highlights. The idea of a storage overview is also
 * Enhanced Storage's (GPL-3.0); none of its code is here.
 */
object StorageOverlay {
	private val FORMATTING = Regex("§.")
	private val ENDER_CHEST = Regex("""^Ender Chest (?:✦ )?\(([1-9])/[1-9]\)$""")
	private val BACKPACK = Regex("""^.+Backpack (?:✦ )?\(Slot #(\d+)\)$""")
	private val PROFILE = Regex("""^You are playing on profile: (\w+)""")
	private const val STORAGE_TITLE = "Storage"

	private const val ENDER_CHEST_PAGES = 9
	private const val PAGES = 27

	// Layout, in GUI pixels.
	private const val CELL = 18
	private const val PAD = 8
	private const val PAGE_WIDTH = CELL * 9 + 4
	private const val TITLE = 12
	private const val PLACEHOLDER = 18
	private const val SCROLLBAR = 4
	private const val PLAYER_WIDTH = CELL * 9 + 8
	private const val PLAYER_HEIGHT = CELL * 4 + 4 + 8
	private const val SEARCH_WIDTH = 110

	// The Storage Overview's cells, full size: without its headings the menu is four rows.
	private const val OV_CELL = CELL
	private const val OV_WIDTH = OV_CELL * 9 + 8
	private const val OFF = -10_000

	private const val PANEL = 0xF018181B.toInt()
	private const val BORDER = 0xFF3C3C41.toInt()
	private const val CELL_FILL = 0xFF1E1E22.toInt()
	private const val CELL_LINE = 0xFF37373C.toInt()
	private const val ACCENT = 0xFF36865B.toInt()

	@JvmField
	val columns = SliderModuleSetting(
		id = "columns",
		label = "Columns",
		defaultValue = 3.0,
		min = 1.0,
		max = 6.0,
		step = 1.0,
		description = "Pages side by side, at most; fewer when the window is narrow.",
	)

	@JvmField
	val maxHeight = SliderModuleSetting(
		id = "max_height",
		label = "Max height",
		unit = "px",
		defaultValue = 340.0,
		min = 120.0,
		max = 700.0,
		step = 10.0,
		description = "How tall the page grid can get before it scrolls.",
	)

	@JvmField
	val search = ToggleModuleSetting(
		id = "search",
		label = "Search box",
		defaultValue = true,
		description = "Beside your inventory. Matching items light up in every page; pages without any are dimmed.",
	)

	@JvmField
	val matchColor = ColorModuleSetting(
		id = "match_color",
		label = "Match color",
		defaultRgb = 0x36865B,
		supportsAlpha = true,
		defaultAlpha = 0xA0,
		visibleIf = { search.value },
	)

	@JvmField
	val hideUnmatched = ToggleModuleSetting(
		id = "hide_unmatched",
		label = "Hide pages without matches",
		defaultValue = false,
		description = "While searching, leaves out pages with nothing that matches instead of dimming them.",
		visibleIf = { search.value },
	)

	@JvmField
	val remember = ToggleModuleSetting(
		id = "remember",
		label = "Remember between sessions",
		defaultValue = true,
		description = "Saves what each page held, and your page names, per SkyBlock profile.",
	)

	@JvmField
	val reopenLast = ToggleModuleSetting(
		id = "reopen_last",
		label = "Reopen the last page",
		defaultValue = true,
		description = "/storage opens straight into the page you were last in, with every other page still a click away.",
	)

	@JvmField
	val module = Module(
		id = "storage_overlay",
		name = "Storage Overlay",
		description = "Every storage page at once",
		category = ModuleCategory.GENERAL,
		settings = listOf(columns.withMore(maxHeight), search.withMore(matchColor, hideUnmatched), reopenLast, remember),
	)

	// ---- Pages ---------------------------------------------------------------------------------

	/** Pages 0 to 8 are the Ender Chest's, 9 to 26 the backpacks'. */
	private fun defaultName(index: Int) =
		if (index < ENDER_CHEST_PAGES) "Ender Chest ${index + 1}" else "Backpack ${index - ENDER_CHEST_PAGES + 1}"

	private fun nameOf(index: Int) = names[index] ?: defaultName(index)

	private fun command(index: Int) =
		if (index < ENDER_CHEST_PAGES) "enderchest ${index + 1}" else "backpack ${index - ENDER_CHEST_PAGES + 1}"

	/** Which storage menu a screen is: -1 for the Storage overview, a page index, or null. */
	private fun menuOf(screen: Screen?): Int? {
		if (screen !is ContainerScreen) return null
		val title = screen.title.string.replace(FORMATTING, "").trim()
		if (title == STORAGE_TITLE) return -1
		ENDER_CHEST.find(title)?.let { return it.groupValues[1].toInt() - 1 }
		BACKPACK.find(title)?.let { match ->
			val slot = match.groupValues[1].toInt()
			if (slot in 1..PAGES - ENDER_CHEST_PAGES) return ENDER_CHEST_PAGES + slot - 1
		}
		return null
	}

	/** Every unlocked page; null contents until it's been opened. */
	private val pages = TreeMap<Int, List<ItemStack>?>()
	private val names = HashMap<Int, String>()

	/** The Storage menu as last seen, slot by slot: its page icons and its other buttons. */
	private var overview: List<ItemStack>? = null

	/** The Storage menu's labels and buttons the overview leaves out: Esc does what they do. */
	private val NOT_SHOWN = setOf("Ender Chest", "Backpacks", "Go Back", "Close")

	/** Hypixel's blank glass, and the headings and buttons the overview has no use for. */
	private fun isFiller(stack: ItemStack): Boolean {
		if (stack.isEmpty) return true
		val name = stack.hoverName.string.replace(FORMATTING, "").trim()
		return name.isEmpty() || name in NOT_SHOWN
	}

	/** The Storage menu's rows that hold anything but filler. */
	private fun overviewRows(menu: List<ItemStack>): List<Int> =
		(0 until menu.size / 9).filter { row -> (0 until 9).any { !isFiller(menu[row * 9 + it]) } }

	/** In the Storage menu, the slot that shows each page. */
	private fun overviewPage(slot: Int): Int? = when (slot) {
		in 9 until 18 -> slot - 9
		in 27 until 45 -> slot - 27 + ENDER_CHEST_PAGES
		else -> null
	}

	/** What the Storage menu shows for a locked Ender Chest page or an empty backpack slot. */
	private val NO_PAGE by lazy {
		setOf(
			net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.red().asItem(),
			net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.brown().asItem(),
			Items.DYE.gray(),
		)
	}

	private fun marksNoPage(stack: ItemStack) = stack.item in NO_PAGE

	fun active(screen: Screen?): Boolean = module.enabled && SkyblockLocation.onSkyblock && menuOf(screen) != null

	val needsSkyblock: Boolean get() = module.enabled

	// ---- Reading menus -------------------------------------------------------------------------

	private var lastScreen: Screen? = null

	fun tick(client: Minecraft) {
		if (!module.enabled) return
		val owner = owner(client) ?: return
		if (loadedFor != owner) load(client, owner)

		val screen = client.gui.screen()
		val menu = menuOf(screen)
		if (screen is ContainerScreen && menu != null) {
			if (screen !== lastScreen) {
				val fresh = lastScreen == null
				val asked = System.currentTimeMillis() <= switchingUntil
				lastScreen = screen
				switchingUntil = 0
				if (menu >= 0) {
					centreOn = menu
					if (lastPage != menu) {
						lastPage = menu
						dirty = true
					}
				} else if (fresh && !asked && pendingClick == null && reopenLast.value) {
					// A /storage typed or keyed, not one of ours: straight on to the last page.
					lastPage?.takeIf { it in pages }?.let { open(command(it)) }
				}
			}
			read(screen.menu, menu)
			if (menu < 0) clickPending(client, screen)
		} else if (screen !is HoldScreen) {
			lastScreen = null
			editing = null
			restoreWidgets()
			if (dirty) flush()
		}
	}

	private fun read(chest: ChestMenu, menu: Int) {
		val end = chest.rowCount * 9
		// Until the server fills it, a fresh menu is empty; its first row never is.
		if ((0 until 9).all { chest.slots[it].item.isEmpty }) return
		if (menu < 0) {
			val seen = (0 until end).map { chest.slots[it].item.copy() }
			val old = overview
			if (old == null || old.size != seen.size || old.indices.any { !ItemStack.matches(old[it], seen[it]) }) {
				overview = seen
				dirty = true
			}
			for (slot in 0 until end) {
				val page = overviewPage(slot) ?: continue
				val stack = chest.slots[slot].item
				if (stack.isEmpty) continue
				if (marksNoPage(stack)) {
					if (page in pages) {
						pages.remove(page)
						dirty = true
					}
				} else if (page !in pages) {
					pages[page] = null
					dirty = true
				}
			}
			return
		}
		if (end <= 9) return
		val items = (9 until end).map { chest.slots[it].item.copy() }
		val old = pages[menu]
		if (old == null || old.size != items.size || old.indices.any { !ItemStack.matches(old[it], items[it]) }) {
			pages[menu] = items
			dirty = true
		}
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val match = PROFILE.find(message.string.replace(FORMATTING, "").trim()) ?: return
		if (match.groupValues[1] != profile) {
			if (dirty) flush()
			profile = match.groupValues[1]
			loadedFor = null
		}
	}

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		// The next server says which profile it is; until then nothing is shown under the old one.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			Minecraft.getInstance().execute {
				profile = null
				loadedFor = null
				pages.clear()
				names.clear()
				overview = null
				dirty = false
				editing = null
			}
		}
	}

	// ---- Saving --------------------------------------------------------------------------------

	private var saved: JsonObject? = null
	private var loadedFor: String? = null
	private var profile: String? = null
	private var dirty = false

	private val writer = Executors.newSingleThreadExecutor { Thread(it, "Cryptic storage save").apply { isDaemon = true } }
	private val gson = GsonBuilder().disableHtmlEscaping().create()
	private val file: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("storage.json")

	private fun owner(client: Minecraft): String? {
		val player = client.player ?: return null
		return player.uuid.toString() + ":" + (profile ?: "default")
	}

	/** A page's key in the file; the older "ec:1" and "bp:1" are read too. */
	private fun indexOf(key: String): Int? {
		key.toIntOrNull()?.let { return it.takeIf { index -> index in 0 until PAGES } }
		val number = key.substringAfter(':').toIntOrNull() ?: return null
		return when (key.substringBefore(':')) {
			"ec" -> number - 1
			"bp" -> ENDER_CHEST_PAGES + number - 1
			else -> null
		}?.takeIf { it in 0 until PAGES }
	}

	private fun load(client: Minecraft, owner: String) {
		pages.clear()
		names.clear()
		overview = null
		loadedFor = owner
		if (!remember.value) return
		val level = client.level ?: return
		val all = saved ?: runCatching {
			if (Files.exists(file)) JsonParser.parseString(Files.readString(file)).asJsonObject else JsonObject()
		}.getOrElse { JsonObject() }.also { saved = it }
		val mine = all.getAsJsonObject(owner) ?: return
		val ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess())
		// Older saves held the pages at the top level.
		val stored = mine.getAsJsonObject("pages") ?: mine
		for ((key, value) in stored.entrySet()) {
			val index = indexOf(key) ?: continue
			pages[index] = (value as? JsonArray)?.map { element ->
				ItemStack.OPTIONAL_CODEC.parse(ops, element).result().orElse(ItemStack.EMPTY)
			}
		}
		(mine.get("overview") as? JsonArray)?.let { array ->
			overview = array.map { ItemStack.OPTIONAL_CODEC.parse(ops, it).result().orElse(ItemStack.EMPTY) }
		}
		lastPage = runCatching { mine.get("last")?.asInt }.getOrNull()?.takeIf { it in 0 until PAGES }
		mine.getAsJsonObject("names")?.entrySet()?.forEach { (key, value) ->
			val index = indexOf(key) ?: return@forEach
			runCatching { value.asString }.getOrNull()?.takeIf { it.isNotBlank() }?.let { names[index] = it }
		}
	}

	private fun flush() {
		dirty = false
		if (!remember.value) return
		val owner = loadedFor ?: return
		val level = Minecraft.getInstance().level ?: return
		val ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess())
		val stored = JsonObject()
		for ((index, items) in pages) {
			if (items == null) {
				stored.add(index.toString(), JsonNull.INSTANCE)
				continue
			}
			val array = JsonArray()
			for (stack in items) array.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().orElse(JsonObject()))
			stored.add(index.toString(), array)
		}
		val named = JsonObject()
		for ((index, name) in names) named.addProperty(index.toString(), name)
		val mine = JsonObject()
		mine.add("pages", stored)
		mine.add("names", named)
		lastPage?.let { mine.addProperty("last", it) }
		overview?.let { menu ->
			val array = JsonArray()
			for (stack in menu) array.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().orElse(JsonObject()))
			mine.add("overview", array)
		}
		val all = saved ?: JsonObject().also { saved = it }
		all.add(owner, mine)
		val text = gson.toJson(all)
		writer.execute {
			runCatching {
				Files.createDirectories(file.parent)
				val temp = file.resolveSibling("storage.json.tmp")
				Files.writeString(temp, text)
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			}
		}
	}

	// ---- Layout --------------------------------------------------------------------------------

	private class Box(val index: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
		fun contains(mx: Double, my: Double) = mx >= x && mx < x + width && my >= y && my < y + height
	}

	/** One frame's layout, also used for that frame's clicks. */
	private class Layout(
		val x: Int, val y: Int, val width: Int, val height: Int,
		val viewTop: Int, val viewBottom: Int,
		val boxes: List<Box>, val contentHeight: Int,
		val playerX: Int, val playerY: Int,
		val search: IntArray?,
		val overviewX: Int, val overviewY: Int,
		/** The Storage menu's rows shown, top to bottom, and the items in it. */
		val overviewRows: List<Int>, val overviewItems: List<ItemStack>?,
	) {
		val overviewHeight get() = if (overviewRows.isEmpty()) 0 else TITLE + overviewRows.size * OV_CELL + 6

		fun inOverview(mx: Double, my: Double) =
			overviewRows.isNotEmpty() && mx >= overviewX && mx < overviewX + OV_WIDTH && my >= overviewY && my < overviewY + overviewHeight

		/** The Storage menu slot under a point, if any. */
		fun overviewSlotAt(mx: Double, my: Double): Int? {
			if (!inOverview(mx, my)) return null
			val col = ((mx - overviewX - 4) / OV_CELL).toInt()
			val row = ((my - overviewY - TITLE) / OV_CELL).toInt()
			if (col !in 0 until 9 || row !in overviewRows.indices || my < overviewY + TITLE) return null
			return overviewRows[row] * 9 + col
		}

		fun inView(mx: Double, my: Double) = mx >= x && mx < x + width && my >= viewTop && my < viewBottom
	}

	private var layout: Layout? = null

	/** Kept across menus, so hopping between pages keeps your place. */
	private var scroll = 0
	private var centreOn: Int? = null

	private fun rowsOf(index: Int, live: ChestMenu?, liveIndex: Int): Int {
		if (live != null && index == liveIndex) return live.rowCount - 1
		return pages[index]?.let { (it.size + 8) / 9 } ?: 0
	}

	private fun heightOf(rows: Int) = if (rows == 0) PLACEHOLDER else TITLE + rows * CELL + 4

	private fun shownPages(live: ChestMenu?, liveIndex: Int): List<Int> {
		val all = pages.keys.toMutableList()
		if (live != null && liveIndex >= 0 && liveIndex !in all) all += liveIndex
		all.sort()
		val words = words()
		if (!hideUnmatched.value || words.isEmpty()) return all
		return all.filter { index -> pageMatches(index, live, liveIndex, words) }
	}

	private fun pageMatches(index: Int, live: ChestMenu?, liveIndex: Int, words: List<String>): Boolean {
		if (live != null && index == liveIndex) return (9 until live.rowCount * 9).any { matches(live.slots[it].item, words) }
		return pages[index]?.any { matches(it, words) } == true
	}

	private fun measure(screenWidth: Int, screenHeight: Int, live: ChestMenu?, liveIndex: Int): Layout {
		val menuItems = if (live != null && liveIndex < 0) (0 until live.rowCount * 9).map { live.slots[it].item } else overview
		val menuRows = menuItems?.let(::overviewRows).orEmpty()
		val bottom = maxOf(PLAYER_HEIGHT, if (menuRows.isEmpty()) 0 else TITLE + menuRows.size * OV_CELL + 6)
		// With SkyBlock Item List, room is left each side for its list and favourites.
		val room = if (itemListLoaded) ITEM_LIST_ROOM * 2 else 0
		val fit = ((screenWidth - room - PAD * 3 - SCROLLBAR) / (PAGE_WIDTH + PAD)).coerceAtLeast(1)
		val cols = columns.value.toInt().coerceIn(1, fit)
		val inner = PAGE_WIDTH * cols + PAD * (cols - 1)
		val width = inner + PAD * 3 + SCROLLBAR
		val height = minOf(screenHeight - bottom - minOf(40, screenHeight / 10), maxHeight.value.toInt()).coerceAtLeast(60)
		val x = (screenWidth - width) / 2
		val y = ((screenHeight - height - bottom - 4) / 2).coerceAtLeast(2)
		val viewTop = y + PAD
		val viewBottom = y + height - PAD

		// Row by row; each row as tall as its tallest page.
		val boxes = ArrayList<Box>()
		var rowTop = 0
		var rowHeight = 0
		var column = 0
		for (index in shownPages(live, liveIndex)) {
			if (column == cols) {
				column = 0
				rowTop += rowHeight + PAD
				rowHeight = 0
			}
			val boxHeight = heightOf(rowsOf(index, live, liveIndex))
			boxes += Box(index, x + PAD + column * (PAGE_WIDTH + PAD), rowTop, PAGE_WIDTH, boxHeight)
			rowHeight = maxOf(rowHeight, boxHeight)
			column++
		}
		val contentHeight = rowTop + rowHeight
		val view = viewBottom - viewTop
		val maxScroll = (contentHeight - view).coerceAtLeast(0)

		centreOn?.let { target ->
			boxes.firstOrNull { it.index == target }?.let { box ->
				// Only when it isn't already in full view, so clicking a visible page doesn't jump.
				if (box.y < scroll || box.y + box.height > scroll + view) scroll = box.y + box.height / 2 - view / 2
			}
			centreOn = null
		}
		scroll = scroll.coerceIn(0, maxScroll)

		val placed = boxes.map { Box(it.index, it.x, it.y + viewTop - scroll, it.width, it.height) }
		var playerX = (screenWidth - PLAYER_WIDTH) / 2
		val playerY = y + height + 4
		// Left of your inventory, like the Storage menu it stands for; on a narrow window the two
		// are centred together instead.
		var overviewX = playerX - 6 - OV_WIDTH
		if (menuRows.isNotEmpty() && overviewX < 2) {
			overviewX = ((screenWidth - OV_WIDTH - 6 - PLAYER_WIDTH) / 2).coerceAtLeast(2)
			playerX = overviewX + OV_WIDTH + 6
		}
		val searchBox = if (search.value) {
			val sx = playerX + PLAYER_WIDTH + 6
			// Beside your inventory, or over the pages' top right corner when there's no room.
			if (sx + SEARCH_WIDTH <= screenWidth - 2) {
				intArrayOf(sx, playerY + 4, sx + SEARCH_WIDTH, playerY + 18)
			} else {
				val top = (y - 16).coerceAtLeast(1)
				intArrayOf(x + width - SEARCH_WIDTH, top, x + width, top + 14)
			}
		} else {
			null
		}
		return Layout(
			x, y, width, height, viewTop, viewBottom, placed, contentHeight, playerX, playerY, searchBox,
			overviewX, playerY, menuRows, menuItems,
		)
	}

	/** Where player inventory slot [containerSlot] goes: three rows, then the hotbar. */
	private fun playerCell(at: Layout, containerSlot: Int): Pair<Int, Int> {
		val left = at.playerX + 4
		val top = at.playerY + 4
		return if (containerSlot < 9) {
			left + containerSlot * CELL to top + 3 * CELL + 4
		} else {
			left + (containerSlot % 9) * CELL to top + (containerSlot / 9 - 1) * CELL
		}
	}

	private fun pageCell(box: Box, index: Int) = box.x + 2 + (index % 9) * CELL to box.y + TITLE + (index / 9) * CELL

	private fun setSlot(slot: Slot, x: Int, y: Int) {
		val accessor = slot as PackTweaksMixins.SlotAccessor
		if (slot.x != x) accessor.`cryptic$setX`(x)
		if (slot.y != y) accessor.`cryptic$setY`(y)
	}

	/** Puts the menu's real slots onto the grid, and hides the rest. */
	private fun placeSlots(chest: ChestMenu, liveIndex: Int, at: Layout) {
		val chestEnd = chest.rowCount * 9
		val liveBox = at.boxes.firstOrNull { it.index == liveIndex }
		for (slot in chest.slots) {
			if (slot.container is Inventory) {
				val (x, y) = playerCell(at, slot.containerSlot)
				setSlot(slot, x + 1, y + 1)
				continue
			}
			val index = slot.index
			// The Storage menu's own slots are drawn small in the overview panel, which clicks them itself.
			if (liveIndex < 0 || liveBox == null || index < 9 || index >= chestEnd) {
				setSlot(slot, OFF, OFF)
				continue
			}
			val (x, y) = pageCell(liveBox, index - 9)
			// Only whole cells inside the view: vanilla draws slots without clipping.
			if (y < at.viewTop || y + CELL > at.viewBottom) setSlot(slot, OFF, OFF) else setSlot(slot, x + 1, y + 1)
		}
	}

	// ---- Drawing -------------------------------------------------------------------------------

	private val lowerNames = WeakHashMap<ItemStack, String>()

	private fun nameKey(stack: ItemStack): String =
		lowerNames.getOrPut(stack) { stack.hoverName.string.replace(FORMATTING, "").lowercase(Locale.ROOT) }

	private fun words(): List<String> = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }

	private fun matches(stack: ItemStack, words: List<String>): Boolean {
		if (stack.isEmpty || words.isEmpty()) return false
		val name = nameKey(stack)
		return words.all { it in name }
	}

	private fun grid(graphics: GuiGraphicsExtractor, x: Int, y: Int, rows: Int, cell: Int = CELL) {
		val w = 9 * cell
		val h = rows * cell
		graphics.fill(x, y, x + w, y + h, CELL_FILL)
		for (col in 0..9) graphics.fill(x + col * cell, y, x + col * cell + 1, y + h + 1, CELL_LINE)
		for (row in 0..rows) graphics.fill(x, y + row * cell, x + w + 1, y + row * cell + 1, CELL_LINE)
	}

	/** The whole overlay, in place of the chest's background; from the `ContainerScreen` hook. */
	@JvmStatic
	fun renderBackground(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val chest = (screen as? ContainerScreen)?.menu ?: return
		val liveIndex = menuOf(screen) ?: return
		val accessor = screen as ContainerScreenAccessor
		// Slots are placed in screen coordinates; clicks off the chest are handled by [clickedOutside].
		accessor.`cryptic$setLeftPos`(0)
		accessor.`cryptic$setTopPos`(0)
		// Skyblocker's buttons (quick navigation, container value) are placed around the chest, which
		// isn't there any more, so they'd float over the pages. Other mods' widgets stay: SkyBlock
		// Item List lays itself out beside the overlay.
		for (child in screen.children()) {
			if (child !is AbstractWidget || !child.javaClass.name.startsWith(SKYBLOCKER)) continue
			if (child !in hiddenWidgets) hiddenWidgets[child] = child.visible
			child.visible = false
		}
		val at = measure(screen.width, screen.height, chest, liveIndex)
		layout = at
		placeSlots(chest, liveIndex, at)
		draw(graphics, at, chest, liveIndex, mouseX, mouseY, chest.carried.isEmpty)
	}

	private fun draw(graphics: GuiGraphicsExtractor, at: Layout, live: ChestMenu?, liveIndex: Int, mouseX: Int, mouseY: Int, emptyHand: Boolean) {
		val font = Minecraft.getInstance().font
		val words = words()
		val mx = mouseX.toDouble()
		val my = mouseY.toDouble()
		graphics.fill(at.x, at.y, at.x + at.width, at.y + at.height, PANEL)
		graphics.outline(at.x, at.y, at.width, at.height, BORDER)

		var hovered: ItemStack? = null
		graphics.enableScissor(at.x + 1, at.viewTop - 1, at.x + at.width - 1, at.viewBottom)
		for (box in at.boxes) {
			if (box.y > at.viewBottom || box.y + box.height < at.viewTop) continue
			val isLive = live != null && box.index == liveIndex
			val items = if (isLive) null else pages[box.index]
			val rows = rowsOf(box.index, live, liveIndex)
			val pageHovered = !isLive && emptyHand && box.contains(mx, my) && at.inView(mx, my)

			if (rows == 0) {
				graphics.fill(box.x, box.y, box.x + box.width, box.y + box.height, if (pageHovered) 0xFF2A2A30.toInt() else CELL_FILL)
				graphics.outline(box.x, box.y, box.width, box.height, BORDER)
				graphics.text(font, "§7${nameOf(box.index)} §8- Click to load", box.x + 4, box.y + 5, -1, false)
				continue
			}

			if (isLive) {
				graphics.outline(box.x - 1, box.y - 1, box.width + 2, box.height + 2, ACCENT)
			} else if (pageHovered) {
				graphics.outline(box.x - 1, box.y - 1, box.width + 2, box.height + 2, 0xFF6A6A72.toInt())
			}
			if (editing == box.index) {
				graphics.fill(box.x + 2, box.y + TITLE - 2, box.x + box.width - 2, box.y + TITLE - 1, ACCENT)
				graphics.text(font, field + caret(), box.x + 4, box.y + 2, -1, false)
			} else {
				val title = DungeonScore.colored(nameOf(box.index), if (isLive) "§a" else "§f")
				graphics.text(font, title, box.x + 4, box.y + 2, -1, false)
			}
			grid(graphics, box.x + 2, box.y + TITLE, rows)

			var anyMatch = false
			val count = if (isLive) rows * 9 else items?.size ?: 0
			for (i in 0 until count) {
				val (cx, cy) = pageCell(box, i)
				if (cy + CELL < at.viewTop || cy > at.viewBottom) continue
				val stack = if (live != null && isLive) live.slots[9 + i].item else items?.getOrNull(i) ?: continue
				if (matches(stack, words)) {
					anyMatch = true
					graphics.fill(cx + 1, cy + 1, cx + CELL, cy + CELL, matchColor.argb)
				}
				// The live page's items are the menu's own slots, drawn by the game.
				if (isLive || stack.isEmpty) continue
				graphics.item(stack, cx + 1, cy + 1)
				graphics.itemDecorations(font, stack, cx + 1, cy + 1)
				if (mx >= cx + 1 && mx < cx + CELL && my >= cy + 1 && my < cy + CELL && at.inView(mx, my)) {
					graphics.fill(cx + 1, cy + 1, cx + CELL, cy + CELL, 0x30FFFFFF)
					if (emptyHand) hovered = stack
				}
			}
			if (words.isNotEmpty() && !anyMatch) graphics.fill(box.x, box.y, box.x + box.width, box.y + box.height, 0xA0000000.toInt())
		}
		graphics.disableScissor()

		if (at.boxes.isEmpty()) {
			val hint = if (pages.isEmpty()) "Open /storage once to list your pages." else "Nothing matches."
			graphics.text(font, "§7$hint", at.x + PAD, at.viewTop + 2, -1, false)
		}

		// Scrollbar.
		val view = at.viewBottom - at.viewTop
		if (at.contentHeight > view) {
			val trackX = at.x + at.width - PAD - SCROLLBAR
			graphics.fill(trackX, at.viewTop, trackX + SCROLLBAR, at.viewBottom, 0x601E1E23)
			val thumb = (view * view / at.contentHeight).coerceAtLeast(12)
			val maxScroll = (at.contentHeight - view).coerceAtLeast(1)
			val thumbTop = at.viewTop + (view - thumb) * scroll / maxScroll
			graphics.fill(trackX, thumbTop, trackX + SCROLLBAR, thumbTop + thumb, 0xFF78787F.toInt())
		}

		// Your inventory.
		graphics.fill(at.playerX, at.playerY, at.playerX + PLAYER_WIDTH, at.playerY + PLAYER_HEIGHT, PANEL)
		graphics.outline(at.playerX, at.playerY, PLAYER_WIDTH, PLAYER_HEIGHT, BORDER)
		grid(graphics, at.playerX + 4, at.playerY + 4, 3)
		grid(graphics, at.playerX + 4, at.playerY + 4 + 3 * CELL + 4, 1)
		Minecraft.getInstance().player?.inventory?.let { inventory ->
			for (slot in 0 until 36) {
				if (!matches(inventory.getItem(slot), words)) continue
				val (cx, cy) = playerCell(at, slot)
				graphics.fill(cx + 1, cy + 1, cx + CELL, cy + CELL, matchColor.argb)
			}
		}

		// The Storage menu, as a panel: its page icons and its other buttons.
		val menuItems = at.overviewItems
		if (at.overviewRows.isNotEmpty() && menuItems != null) {
			val ox = at.overviewX
			val oy = at.overviewY
			val itemScale = minOf(1f, (OV_CELL - 2) / 16f)
			graphics.fill(ox, oy, ox + OV_WIDTH, oy + at.overviewHeight, PANEL)
			graphics.outline(ox, oy, OV_WIDTH, at.overviewHeight, BORDER)
			graphics.text(font, "Storage Overview", ox + 4, oy + 2, 0xFFFFFFFF.toInt(), false)
			grid(graphics, ox + 4, oy + TITLE, at.overviewRows.size, OV_CELL)
			for ((row, menuRow) in at.overviewRows.withIndex()) {
				for (col in 0 until 9) {
					val slot = menuRow * 9 + col
					val stack = menuItems.getOrNull(slot) ?: continue
					if (isFiller(stack)) continue
					val cx = ox + 4 + col * OV_CELL
					val cy = oy + TITLE + row * OV_CELL
					if (overviewPage(slot) == liveIndex && liveIndex >= 0) graphics.outline(cx, cy, OV_CELL + 1, OV_CELL + 1, ACCENT)
					if (matches(stack, words)) graphics.fill(cx + 1, cy + 1, cx + OV_CELL, cy + OV_CELL, matchColor.argb)
					val pose = graphics.pose()
					pose.pushMatrix()
					pose.translate(cx + 1f, cy + 1f)
					pose.scale(itemScale, itemScale)
					graphics.item(stack, 0, 0)
					graphics.itemDecorations(font, stack, 0, 0)
					pose.popMatrix()
					if (mx >= cx + 1 && mx < cx + OV_CELL && my >= cy + 1 && my < cy + OV_CELL) {
						graphics.fill(cx + 1, cy + 1, cx + OV_CELL, cy + OV_CELL, 0x30FFFFFF)
						if (emptyHand) hovered = stack
					}
				}
			}
		}

		at.search?.let { box ->
			graphics.fill(box[0], box[1], box[2], box[3], PANEL)
			graphics.outline(box[0], box[1], box[2] - box[0], box[3] - box[1], if (editing == SEARCH) 0xFFFFFFFF.toInt() else BORDER)
			val shown = when {
				editing == SEARCH -> field + caret()
				query.isNotEmpty() -> query
				else -> "§8Search..."
			}
			graphics.text(font, font.plainSubstrByWidth(shown, box[2] - box[0] - 6), box[0] + 3, box[1] + 3, -1, false)
		}

		hovered?.let { graphics.setTooltipForNextFrame(font, it, mouseX, mouseY) }
	}

	private fun caret() = if (System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""

	/** Room each side of the overlay for SkyBlock Item List's list and favourites. */
	private const val ITEM_LIST_ROOM = 140

	private const val SKYBLOCKER = "de.hysky.skyblocker."

	private val itemListLoaded: Boolean by lazy { FabricLoader.getInstance().isModLoaded("skyblock-item-list") }

	/**
	 * The overlay's left and right edges, for SkyBlock Item List to lay itself out around, or null
	 * when the screen isn't one the overlay takes over; from `ItemListCompatMixin`.
	 */
	@JvmStatic
	fun itemListBounds(screen: Screen?): IntArray? {
		if (!active(screen) || screen !is ContainerScreen) return null
		val at = measure(screen.width, screen.height, screen.menu, menuOf(screen) ?: return null)
		var left = at.x
		var right = at.x + at.width
		if (at.overviewRows.isNotEmpty()) left = minOf(left, at.overviewX)
		left = minOf(left, at.playerX)
		right = maxOf(right, at.playerX + PLAYER_WIDTH)
		at.search?.let { right = maxOf(right, it[2]) }
		return intArrayOf(left, right)
	}

	/**
	 * Whether a click counts as off the menu, which drops a held item. Only outside both panels;
	 * from `AbstractContainerScreenMixin`, or null to leave it to the game.
	 */
	@JvmStatic
	fun clickedOutside(screen: AbstractContainerScreen<*>, x: Double, y: Double): Boolean? {
		if (!active(screen)) return null
		val at = layout ?: return null
		val inPanel = x >= at.x && x < at.x + at.width && y >= at.y && y < at.y + at.height
		val inPlayer = x >= at.playerX && x < at.playerX + PLAYER_WIDTH && y >= at.playerY && y < at.playerY + PLAYER_HEIGHT
		return !inPanel && !inPlayer && !at.inOverview(x, y)
	}

	/** Whether to skip the menu's title and "Inventory" labels; from `AbstractContainerScreenMixin`. */
	@JvmStatic
	fun hidesLabels(screen: AbstractContainerScreen<*>): Boolean = active(screen)

	// ---- Between pages -------------------------------------------------------------------------

	/** Until when a page we asked for is on its way. */
	private var switchingUntil = 0L

	/**
	 * Hypixel closes one menu before it opens the next. While a page we asked for is coming, that
	 * gap shows the overlay as last seen instead of the world, so nothing flickers and the mouse
	 * isn't grabbed and recentred.
	 */
	@JvmStatic
	fun holdInstead(screen: Screen?): Screen? {
		if (screen != null && menuOf(screen) == null) restoreWidgets()
		if (screen != null || System.currentTimeMillis() > switchingUntil || !module.enabled) return screen
		return if (menuOf(Minecraft.getInstance().gui.screen()) != null) HoldScreen() else screen
	}

	private class HoldScreen : Screen(Component.literal(STORAGE_TITLE)) {
		override fun isPauseScreen() = false

		override fun tick() {
			// The page never came.
			if (System.currentTimeMillis() > switchingUntil + 2_000) onClose()
		}

		override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
			draw(graphics, measure(width, height, null, -1), null, -1, mouseX, mouseY, false)
		}
	}

	// ---- Input ---------------------------------------------------------------------------------

	private const val SEARCH = -2

	/** What's being typed in: [SEARCH], a page index being renamed, or nothing. */
	private var editing: Int? = null
	private var field = ""
	private var query = ""

	private fun stopEditing(save: Boolean) {
		val target = editing ?: return
		if (target != SEARCH && save) {
			val name = field.trim()
			if (name.isEmpty() || name == defaultName(target)) names.remove(target) else names[target] = name
			dirty = true
		}
		editing = null
	}

	private fun startEditing(target: Int) {
		stopEditing(true)
		editing = target
		field = if (target == SEARCH) query else nameOf(target).replace('§', '&')
	}

	/** A click on the overlay. True when it was ours (a page, a title, the search box). */
	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, x: Double, y: Double, button: Int): Boolean {
		if (!active(screen)) return false
		val at = layout ?: return false
		val box = at.search
		if (box != null && x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) {
			if (button == 1) {
				query = ""
				field = ""
			}
			if (editing != SEARCH) startEditing(SEARCH)
			return true
		}
		stopEditing(true)
		val liveIndex = menuOf(screen)

		// In /storage the overview panel is the menu itself: a click there is a click on that slot,
		// any button, held item and all (backpacks go in and out this way).
		if (liveIndex == -1 && at.inOverview(x, y)) {
			val slot = at.overviewSlotAt(x, y) ?: return true
			val player = Minecraft.getInstance().player ?: return true
			if (slot >= screen.menu.slots.size || isFiller(screen.menu.slots[slot].item) && screen.menu.carried.isEmpty) return true
			val shift = InputConstants.isKeyDown(Minecraft.getInstance().window, GLFW.GLFW_KEY_LEFT_SHIFT) ||
				InputConstants.isKeyDown(Minecraft.getInstance().window, GLFW.GLFW_KEY_RIGHT_SHIFT)
			val input = if (shift) net.minecraft.world.inventory.ContainerInput.QUICK_MOVE else net.minecraft.world.inventory.ContainerInput.PICKUP
			Minecraft.getInstance().gameMode?.handleContainerInput(screen.menu.containerId, slot, button, input, player)
			return true
		}

		if (!screen.menu.carried.isEmpty || button != 0) return false

		// The overview panel, from a page: page icons open that page; Hypixel's other buttons only
		// work in the real menu, so they open /storage.
		if (liveIndex != -1) {
			val slot = at.overviewSlotAt(x, y)
			if (slot != null) {
				val stack = at.overviewItems?.getOrNull(slot) ?: return true
				if (isFiller(stack)) return true
				val page = overviewPage(slot)?.takeIf { !marksNoPage(stack) }
				if (page == null) {
					// Toolkits and the like: open /storage and press it there.
					pendingClick = slot
					open("storage")
				} else if (page != liveIndex) {
					open(command(page))
				}
				return true
			}
		}

		if (!at.inView(x, y)) return false
		val hit = at.boxes.firstOrNull { it.contains(x, y) } ?: return false
		if (hit.index == liveIndex) {
			// Its title renames it; its slots are the game's.
			if (y < hit.y + TITLE) {
				startEditing(hit.index)
				return true
			}
			return false
		}
		open(command(hit.index))
		return true
	}

	/** The page last opened, which /storage can reopen. */
	private var lastPage: Int? = null

	/** A Storage menu button pressed from a page, to press once /storage is up. */
	private var pendingClick: Int? = null

	private fun clickPending(client: Minecraft, screen: ContainerScreen) {
		val slot = pendingClick ?: return
		val chest = screen.menu
		// Once the server has filled the menu.
		if ((0 until 9).all { chest.slots[it].item.isEmpty }) return
		pendingClick = null
		val player = client.player ?: return
		if (slot < chest.rowCount * 9 && !chest.slots[slot].item.isEmpty) {
			client.gameMode?.handleContainerInput(chest.containerId, slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
		}
	}

	/** Other mods' widgets hidden over the overlay, with how each was, to put back after. */
	private val hiddenWidgets = HashMap<AbstractWidget, Boolean>()

	/** Some mods keep one widget for every screen (SkyBlock Item List does), so it must come back. */
	private fun restoreWidgets() {
		if (hiddenWidgets.isEmpty()) return
		for ((widget, visible) in hiddenWidgets) widget.visible = visible
		hiddenWidgets.clear()
	}

	/** Runs a storage command, holding the overlay up until its menu opens. */
	private fun open(command: String) {
		switchingUntil = System.currentTimeMillis() + 1_500
		Minecraft.getInstance().connection?.sendCommand(command)
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
	}

	@JvmStatic
	fun handleScroll(screen: AbstractContainerScreen<*>, x: Double, y: Double, amount: Double): Boolean {
		if (!active(screen)) return false
		val at = layout ?: return false
		if (!at.inView(x, y)) return false
		scroll = (scroll - (amount * 30).toInt()).coerceAtLeast(0)
		return true
	}

	/** Keys while a box has focus; it keeps every key from the menu, so E and 1 to 9 type. */
	@JvmStatic
	fun handleKey(screen: AbstractContainerScreen<*>, key: Int): Boolean {
		val target = editing ?: return false
		if (!active(screen)) return false
		when (key) {
			GLFW.GLFW_KEY_ESCAPE -> stopEditing(false)
			GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> stopEditing(true)
			GLFW.GLFW_KEY_BACKSPACE -> {
				field = field.dropLast(1)
				if (target == SEARCH) query = field
			}
			GLFW.GLFW_KEY_V -> if (controlDown()) typeText(Minecraft.getInstance().keyboardHandler.clipboard)
		}
		return true
	}

	private fun controlDown(): Boolean {
		val window = Minecraft.getInstance().window
		return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
	}

	private fun typeText(text: String) {
		val target = editing ?: return
		val clean = text.replace('\n', ' ').filter { it >= ' ' && it != '§' }
		field = (field + clean).take(if (target == SEARCH) 40 else 48)
		if (target == SEARCH) query = field
	}

	/** A typed character, from `KeyboardHandlerMixin`. True when a box took it. */
	@JvmStatic
	fun charTyped(codePoint: Int): Boolean {
		if (editing == null || !active(Minecraft.getInstance().gui.screen())) return false
		if (!Character.isISOControl(codePoint)) typeText(String(Character.toChars(codePoint)))
		return true
	}
}
