package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.ItemPrices
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import java.util.Locale

/**
 * What each dungeon chest is worth, and which runs at Croesus still have one
 * worth opening.
 *
 * Put together from five mods that each do part of it: the run colours from
 * Odin's Croesus (BSD 3-Clause, Copyright (c) 2025 odtheking) and
 * Skyblocker's Croesus Helper (LGPL-3.0), the chest values from Odin's,
 * Skyblocker's Croesus Profit and NoammAddons' Chest Profit (CC0), and the two
 * profit lists drawn the way SkyHanni's Instance Chest Profit draws them
 * (LGPL-2.1), down to its colours and its number format. Prices are Odin's
 * seven-day averages, through [ItemPrices].
 *
 * Three screens are read. Croesus' list of runs; one run's six chests, each
 * with what is in it and what it costs in its description; and a chest
 * itself, which is the same screen at Croesus and in the boss room after a
 * run. Whether a run is still worth a Dungeon Chest Key cannot be read off the
 * list — only off the run's own screen — so a run's chests are remembered once
 * it has been looked at, by its page and slot in the list, as SkyHanni tells
 * runs apart.
 */
object CroesusHelper {
	// ---- Settings --------------------------------------------------------

	@JvmField
	val highlightRuns = ToggleModuleSetting(
		id = "highlight_runs",
		label = "Highlight runs",
		defaultValue = true,
		description = "Colours each run at Croesus: not opened, worth a Dungeon Chest Key, or done.",
	)

	@JvmField
	val highlightChests = ToggleModuleSetting(
		id = "highlight_chests",
		label = "Highlight chests",
		defaultValue = true,
		description = "In a run, colours the chest to open first, and one worth a key after it.",
	)

	@JvmField
	val minKeyProfit = SliderModuleSetting(
		id = "min_key_profit",
		label = "Key profit (thousands)",
		defaultValue = 200.0,
		min = 100.0,
		max = 500.0,
		step = 25.0,
		description = "How much a chest has to make after its coins and the price of a Dungeon Chest Key to be worth the key.",
	)

	@JvmField
	val includeEssence = ToggleModuleSetting(
		id = "include_essence",
		label = "Count essence",
		defaultValue = true,
		description = "Adds what the essence in a chest sells for.",
	)

	@JvmField
	val profitInTitle = ToggleModuleSetting(
		id = "profit_in_title",
		label = "Profit in the chest",
		defaultValue = true,
		description = "Writes a chest's profit beside its title when you open it, at Croesus or in the boss room.",
	)

	@JvmField
	val profitHud = ToggleModuleSetting(
		id = "profit_hud",
		label = "Profit overlays",
		defaultValue = true,
		description = "SkyHanni's two lists: every chest of a run at Croesus, and what one chest holds when you open it. " +
			"Hover a chest in the first for what is in it.",
	)

	private val colorSection = SectionModuleSetting("color_section", "Colors")

	@JvmField
	val unopenedColor = ColorModuleSetting(
		id = "unopened_color",
		label = "Not opened",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x90,
	)

	@JvmField
	val keyColor = ColorModuleSetting(
		id = "key_color",
		label = "Worth a key",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0x90,
	)

	@JvmField
	val doneColor = ColorModuleSetting(
		id = "done_color",
		label = "Done",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x90,
	)

	@JvmField
	val module = Module(
		id = "croesus_helper",
		name = "Croesus Helper",
		description = "Which dungeon chests are worth opening, and what they make",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			highlightRuns, highlightChests, minKeyProfit, includeEssence, profitInTitle, profitHud,
			colorSection, unopenedColor, keyColor, doneColor,
		),
	)

	// ---- Screens ---------------------------------------------------------

	/** "Croesus", or "(2/3) Croesus" past the first page. */
	private val CROESUS_TITLE = Regex("""^(?:\((\d+)/\d+\) )?Croesus$""")

	/** One run's chests: "Catacombs - Floor VII", "Master Catacombs - Floor VII". */
	private val RUN_TITLE = Regex("""^(?:Master )?Catacombs - .*$""")

	/** A chest itself, which the run's screen calls "Bedrock" and the chest's own screen may call "Bedrock Chest". */
	private val CHEST_TITLE = Regex("""^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$""")

	private val CHEST_NAME = Regex("""^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)$""")

	private val RUN_NAMES = setOf("The Catacombs", "Master Mode The Catacombs")

	private val OPENED_CHEST = Regex("""^Opened Chest: (\w+)""")

	private const val UNOPENED = "No chests opened yet!"
	private const val NO_MORE = "No more chests to open!"
	private const val ALREADY_OPENED = "Already opened!"

	private val COINS = Regex("""^([\d,]+) Coins$""")
	private val ESSENCE = Regex("""^(\w+) Essence x(\d+)$""")
	private val SHARD = Regex("""^(.+) Shard(?: x(\d+))?$""")
	private val BOOK = Regex("""^Enchanted Book \((.+) ([IVXL]+|\d+)\)$""")
	private val FORMATTING = Regex("§.")

	private const val KEY_ID = "DUNGEON_CHEST_KEY"
	private const val KEY_LABEL = "§9Dungeon Chest Key"

	/**
	 * Drops whose names are not their ids. Every other name is turned into its
	 * id the way the ids are written — "Fifth Master Star" is
	 * FIFTH_MASTER_STAR — which is right for nearly everything else. From
	 * Skyblocker's table and Odin's replacements.
	 */
	private val NAME_IDS = mapOf(
		"Necromancer's Brooch" to "NECROMANCER_BROOCH",
		"Bonzo's Staff" to "BONZO_STAFF",
		"Bonzo's Mask" to "BONZO_MASK",
		"Adaptive Blade" to "STONE_BLADE",
		"Scarf's Studies" to "SCARF_STUDIES",
		"Spirit Shortbow" to "ITEM_SPIRIT_BOW",
		"Spirit Boots" to "THORNS_BOOTS",
		"Spirit Stone" to "SPIRIT_DECOY",
		"Warped Stone" to "AOTE_STONE",
		"Giant's Sword" to "GIANTS_SWORD",
		"Sadan's Brooch" to "SADAN_BROOCH",
		"Necron Dye" to "DYE_NECRON",
		"Livid Dye" to "DYE_LIVID",
		"Necron's Handle" to "NECRON_HANDLE",
		"Shadow Warp" to "SHADOW_WARP_SCROLL",
		"Wither Shield" to "WITHER_SHIELD_SCROLL",
		"Implosion" to "IMPLOSION_SCROLL",
		"Wither Cloak Sword" to "WITHER_CLOAK",
		"Dungeon Disc" to "DUNGEON_DISC_1",
		"Clown Disc" to "DUNGEON_DISC_2",
		"Watcher Disc" to "DUNGEON_DISC_3",
		"Old Disc" to "DUNGEON_DISC_4",
		"Necron Disc" to "DUNGEON_DISC_5",
	)

	/** The colour Hypixel names each chest in, which is SkyHanni's too. */
	private val CHEST_COLORS = mapOf(
		"Wood" to "§f",
		"Gold" to "§6",
		"Diamond" to "§b",
		"Emerald" to "§2",
		"Obsidian" to "§5",
		"Bedrock" to "§8",
	)

	// ---- What has been read ----------------------------------------------

	/** One line of a chest: an item and what it sells for, or a cost and what it takes. */
	data class Entry(val label: Component, val value: Double)

	/** One chest: what is in it, and what opening it costs. */
	data class Chest(
		val type: String,
		val slot: Int,
		val items: List<Entry>,
		val costs: List<Entry>,
		val opened: Boolean,
	) {
		val revenue: Double get() = items.sumOf { it.value }
		val cost: Double get() = costs.sumOf { it.value }
		val profit: Double get() = revenue + cost

		/** The profit if this chest were opened as a run's second, with a key. */
		val profitWithKey: Double
			get() = if (hasKey) profit else profit - keyPrice()

		/** Whether a Dungeon Chest Key is already among its costs. */
		val hasKey: Boolean get() = costs.any { clean(it.label.string) == "Dungeon Chest Key" }

		/** The chest's name in its colour. */
		val name: String get() = (CHEST_COLORS[type] ?: "§f") + type
	}

	/** A run's chests, kept by its place in the list, with what it looked like then. */
	private data class Run(val signature: String, val keyProfits: Map<String, Double>)

	private val runs = HashMap<String, Run>()

	/** The run just clicked in the list, waiting for its screen to open. */
	private var pendingRun: Pair<String, String>? = null

	/** The chests of the run screen open now, worked out once per tick. */
	private var runChests: List<Chest> = emptyList()

	/** The chest screen open now, and what its screen is called. */
	private var openChest: Chest? = null
	private var openChestTitle = ""

	/** Every chest's profit looked at in this dungeon, by its screen's name. */
	private val dungeonChests = LinkedHashMap<String, Double>()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(OverviewElement)
		Hud.register(ChestElement)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			dungeonChests.clear()
			costMemory.keys.removeIf { it.startsWith("dungeon|") }
		}
	}

	private fun keyPrice(): Double = ItemPrices.price(KEY_ID) ?: 0.0

	private fun minProfit(): Double = minKeyProfit.value * 1000.0

	// ---- Ticking ---------------------------------------------------------

	fun tick(client: Minecraft) {
		runChests = emptyList()
		openChest = null
		if (!module.enabled) return
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
		val title = clean(screen.title.string)
		val croesus = CROESUS_TITLE.matches(title)
		val run = RUN_TITLE.matches(title)
		val chest = CHEST_TITLE.matchEntire(title)
		if (!croesus && !run && chest == null) return
		ItemPrices.ensureFetched()

		when {
			run -> readRun(screen)
			chest != null -> readChest(screen, chest.groupValues[1], title)
		}
	}

	private fun containerSlots(screen: AbstractContainerScreen<*>): List<Slot> =
		screen.menu.slots.filter { it.container !is Inventory }

	/**
	 * The chests of the run screen open now, by slot, kept for as long as that
	 * screen is open.
	 *
	 * A chest clicked is taken onto the cursor for a moment before the server
	 * opens it, and its slot reads empty in between: worked out afresh from
	 * what is in the slots, the highlight jumped to the second-best chest for
	 * that moment. An empty slot keeps the chest it last held.
	 */
	private val runSlots = HashMap<Int, Chest>()
	private var runSlotsScreen: Any? = null

	/**
	 * What each chest cost, remembered from when it showed its price. Once a
	 * chest is opened its description no longer says what it cost, and a
	 * revisit counted it as free: an 8m chest that cost 1m showed 8m.
	 * Kept by the run being looked at, or by the dungeon for the chests in the
	 * boss room, and only for this session.
	 */
	private val costMemory = HashMap<String, List<Entry>>()

	private fun costKey(type: String): String =
		(if (DungeonLocation.inDungeon) "dungeon" else pendingRun?.first ?: "croesus") + "|" + type

	/** The chest's costs, or what it was remembered to cost when it no longer says. */
	private fun withRememberedCost(chest: Chest): Chest {
		val key = costKey(chest.type)
		if (chest.costs.isNotEmpty()) {
			costMemory[key] = chest.costs
			return chest
		}
		return costMemory[key]?.let { chest.copy(costs = it) } ?: chest
	}

	private fun readRun(screen: AbstractContainerScreen<*>) {
		if (runSlotsScreen !== screen) {
			runSlots.clear()
			runSlotsScreen = screen
		}
		for (slot in containerSlots(screen)) {
			val stack = slot.item
			if (stack.isEmpty) continue
			val type = clean(stack.hoverName.string)
			if (stack.item != Items.PLAYER_HEAD || !CHEST_NAME.matches(type)) {
				runSlots.remove(slot.index)
				continue
			}
			runSlots[slot.index] = chestFromLore(type, slot.index, stack)
		}
		val chests = runSlots.values.sortedBy { it.slot }
		// Past the first chest, every other one takes a key as well, whether
		// or not its description has caught up and says so.
		val anyOpened = chests.any { it.opened }
		runChests = chests.map { chest ->
			withRememberedCost(
				if (!anyOpened || chest.opened || chest.hasKey) chest
				else chest.copy(costs = chest.costs + Entry(Component.literal(KEY_LABEL), -keyPrice())),
			)
		}

		// Only with prices: a run judged on a table that has not arrived yet
		// would be remembered as worth nothing.
		if (!ItemPrices.loaded) return
		pendingRun?.let { (key, signature) ->
			if (runChests.isNotEmpty()) runs[key] = Run(signature, runChests.associate { it.type to it.profitWithKey })
		}
	}

	private fun chestFromLore(type: String, slot: Int, stack: ItemStack): Chest {
		val items = ArrayList<Entry>()
		val costs = ArrayList<Entry>()
		var inContents = false
		var opened = false
		for (component in loreComponents(stack)) {
			val line = clean(component.string)
			when {
				line == "Contents" -> { inContents = true; continue }
				line.isBlank() -> { inContents = false; continue }
				line == ALREADY_OPENED -> opened = true
			}
			if (inContents) {
				nameValue(line)?.let { items += Entry(component, it) }
				continue
			}
			COINS.matchEntire(line)?.let { costs += Entry(component, -it.groupValues[1].replace(",", "").toDouble()) }
			if (line == "Dungeon Chest Key") costs += Entry(component, -keyPrice())
		}
		return Chest(type, slot, items, costs, opened)
	}

	private fun readChest(screen: AbstractContainerScreen<*>, type: String, title: String) {
		val items = LinkedHashMap<String, Entry>()
		val costs = ArrayList<Entry>()
		var costRead = false
		for (slot in containerSlots(screen)) {
			if (slot.index > 40) continue
			val stack = slot.item
			if (stack.isEmpty || isPane(stack)) continue
			if (stack.item == Items.CHEST) {
				// The button that opens the chest, which is where the cost is written.
				val lore = loreComponents(stack)
				if (lore.none { clean(it.string) == "Cost" }) continue
				costRead = true
				lore.forEach { component ->
					val line = clean(component.string)
					COINS.matchEntire(line)?.let { costs += Entry(component, -it.groupValues[1].replace(",", "").toDouble()) }
					if (line == "Dungeon Chest Key") costs += Entry(component, -keyPrice())
				}
				continue
			}
			// The same item twice is one line, as SkyHanni adds them up.
			val entry = stackValue(stack) ?: continue
			val key = entry.label.string
			items[key] = items[key]?.let { it.copy(value = it.value + entry.value) } ?: entry
		}
		if (!costRead && items.isEmpty()) return
		val chest = withRememberedCost(Chest(type, -1, items.values.toList(), costs, false))
		openChest = chest
		openChestTitle = title
		if (DungeonLocation.inDungeon && ItemPrices.loaded) dungeonChests[title] = chest.profit
	}

	private fun isPane(stack: ItemStack): Boolean = BuiltInRegistries.ITEM.getKey(stack.item).path.endsWith("stained_glass_pane")

	// ---- Values ----------------------------------------------------------

	/** An item in an open chest, which has its id. Null for anything without a price. */
	private fun stackValue(stack: ItemStack): Entry? {
		val name = clean(stack.hoverName.string)
		ESSENCE.matchEntire(name)?.let { match ->
			if (!includeEssence.value) return null
			val each = ItemPrices.price("ESSENCE_" + match.groupValues[1].uppercase(Locale.ROOT)) ?: return null
			return Entry(stack.hoverName, each * match.groupValues[2].toInt())
		}
		val id = SkyblockItem.id(stack).removePrefix("STARRED_")
		if (id == "ENCHANTED_BOOK") {
			// A book is called "Enchanted Book"; its first line says which.
			val label = loreComponents(stack).firstOrNull() ?: stack.hoverName
			val enchants = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompoundOrEmpty("enchantments")
			val enchant = enchants.keySet().firstOrNull() ?: return null
			val price = ItemPrices.price("ENCHANTED_BOOK-${enchant.uppercase(Locale.ROOT)}-${enchants.getIntOr(enchant, 0)}") ?: return null
			return Entry(label, price)
		}
		val price = (if (id.isEmpty()) null else ItemPrices.price(id)) ?: nameValue(name) ?: return null
		return Entry(stack.hoverName, price * stack.count.coerceAtLeast(1))
	}

	/** An item written by name, as a run's screen lists them. */
	private fun nameValue(line: String): Double? {
		BOOK.matchEntire(line)?.let { match ->
			val enchant = match.groupValues[1].uppercase(Locale.ROOT).replace(" ", "_")
			val level = romanToInt(match.groupValues[2])
			return ItemPrices.price("ENCHANTED_BOOK-$enchant-$level")
				?: ItemPrices.price("ENCHANTED_BOOK-ULTIMATE_$enchant-$level")
		}
		ESSENCE.matchEntire(line)?.let { match ->
			if (!includeEssence.value) return null
			val each = ItemPrices.price("ESSENCE_" + match.groupValues[1].uppercase(Locale.ROOT)) ?: return null
			return each * match.groupValues[2].toInt()
		}
		SHARD.matchEntire(line)?.let { match ->
			val id = "SHARD_" + match.groupValues[1].uppercase(Locale.ROOT).replace("'S", "").replace(" ", "_")
			val count = match.groupValues[2].toIntOrNull() ?: 1
			ItemPrices.price(id)?.let { return it * count }
		}
		// A pet comes as "[Lvl 1] Spirit", and is not worth reading for.
		if (line.startsWith("[Lvl")) return null
		val name = line.replace("✪", "").trim().removePrefix("Shiny ")
		val id = NAME_IDS[name] ?: name.uppercase(Locale.ROOT).replace("'", "").replace(" -", "").replace(" ", "_")
		return ItemPrices.price(id)
	}

	private fun romanToInt(text: String): Int {
		text.toIntOrNull()?.let { return it }
		val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50)
		var total = 0
		for (i in text.indices) {
			val value = values[text[i]] ?: return 0
			val next = text.getOrNull(i + 1)?.let { values[it] } ?: 0
			total += if (value < next) -value else value
		}
		return total
	}

	private fun loreComponents(stack: ItemStack): List<Component> = stack.get(DataComponents.LORE)?.lines() ?: emptyList()

	private fun lore(stack: ItemStack): List<String> = loreComponents(stack).map { clean(it.string) }

	private fun clean(text: String): String = text.replace(FORMATTING, "").trim()

	// ---- Highlights ------------------------------------------------------

	/** A click in the run list: remembers which run is about to open. */
	@JvmStatic
	fun onSlotClicked(slotId: Int) {
		if (!module.enabled || slotId < 0) return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
		val page = CROESUS_TITLE.matchEntire(clean(screen.title.string))?.groupValues?.get(1) ?: return
		val slot = screen.menu.slots.getOrNull(slotId) ?: return
		val stack = slot.item
		if (clean(stack.hoverName.string) !in RUN_NAMES) return
		pendingRun = runKey(page, slot) to signature(stack)
	}

	private fun runKey(page: String, slot: Slot): String = "${page.ifEmpty { "1" }}:${slot.index}"

	/** What a run's entry says that does not change as its chests are opened. */
	private fun signature(stack: ItemStack): String =
		clean(stack.hoverName.string) + "|" + lore(stack).firstOrNull { it.startsWith("Floor") }.orEmpty()

	/** Draws a colour behind a slot's item, before the game draws the item. */
	@JvmStatic
	fun drawSlotHighlight(context: GuiGraphicsExtractor, slot: Slot) {
		if (!module.enabled || slot.container is Inventory) return
		val stack = slot.item
		if (stack.isEmpty || stack.item != Items.PLAYER_HEAD) return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
		val title = clean(screen.title.string)

		val color = CROESUS_TITLE.matchEntire(title)?.let { match ->
			if (!highlightRuns.value) return
			runColor(match.groupValues[1], slot, stack)
		} ?: if (RUN_TITLE.matches(title) && highlightChests.value && ItemPrices.loaded) chestColor(slot) else null

		if (color != null) context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color)
	}

	private fun runColor(page: String, slot: Slot, stack: ItemStack): Int? {
		if (clean(stack.hoverName.string) !in RUN_NAMES) return null
		val lines = lore(stack)
		if (lines.any { it == NO_MORE }) return doneColor.argb
		if (lines.any { it == UNOPENED }) return unopenedColor.argb
		val opened = lines.firstNotNullOfOrNull { OPENED_CHEST.find(it)?.groupValues?.get(1) } ?: return null
		val run = runs[runKey(page, slot)]?.takeIf { it.signature == signature(stack) }
			// Not looked at yet: worth opening to find out.
			?: return keyColor.argb
		val worth = run.keyProfits.any { (type, profit) -> type != opened && profit >= minProfit() }
		return if (worth) keyColor.argb else doneColor.argb
	}

	private fun chestColor(slot: Slot): Int? {
		val chest = runChests.firstOrNull { it.slot == slot.index } ?: return null
		if (chest.opened) return doneColor.argb
		val unopened = runChests.filter { !it.opened }
		if (runChests.none { it.opened }) {
			// The first chest is the most profitable one; a second is worth its key on top.
			val ranked = unopened.sortedByDescending { it.profit }
			if (ranked.firstOrNull() === chest && chest.profit > 0) return unopenedColor.argb
			if (ranked.getOrNull(1) === chest && chest.profitWithKey >= minProfit()) return keyColor.argb
			return null
		}
		val best = unopened.maxByOrNull { it.profitWithKey }
		return if (best === chest && chest.profitWithKey >= minProfit()) keyColor.argb else null
	}

	// ---- Numbers, SkyHanni's way -----------------------------------------

	/**
	 * SkyHanni's short number: one decimal kept, and cut rather than rounded,
	 * only while it still says something — under 10k, under 100M — so 1.3M,
	 * 263k and 1M rather than 1.30M, 263.4k and 1.00M.
	 */
	private fun compact(input: Double): String {
		val value = input.toLong()
		if (value < 0) return "-" + compact(-input)
		if (value < 1000) return value.toString()
		val (divideBy, suffix) = when {
			value >= 1_000_000_000_000L -> 1_000_000_000_000L to "T"
			value >= 1_000_000_000L -> 1_000_000_000L to "B"
			value >= 1_000_000L -> 1_000_000L to "M"
			else -> 1_000L to "k"
		}
		val truncated = value / (divideBy / 10)
		val truncatedAt = when (suffix) {
			"M" -> 1000
			"B" -> 1_000_000
			else -> 100
		}
		val hasDecimal = truncated < truncatedAt && truncated % 10 != 0L
		return if (hasDecimal) "${truncated / 10.0}$suffix" else "${truncated / 10}$suffix"
	}

	/** Gold for a gain, red for a loss: SkyHanni's coin colour. */
	private fun coin(value: Double): String = (if (value < 0) "§c" else "§6") + compact(value)

	private fun text(legacy: String): Component = Component.literal(legacy)

	private fun join(label: Component, legacy: String): Component = Component.empty().append(label).append(text(legacy))

	// ---- The overlays ----------------------------------------------------

	/** "Croesus Profit Overlay": a run's chests, each with its profit. */
	private fun overviewLines(chests: List<Chest>): List<Component> =
		listOf(text("§6§lCroesus Profit Overlay")) + chests.map { text("${it.name}§r: ${coin(it.profit)}") }

	/** What hovering a chest in the overview shows. */
	private fun overviewTip(chest: Chest): List<Component> = buildList {
		add(text("${chest.name}:"))
		chest.items.forEach { add(Component.literal(" ").append(it.label).append(text("§r: ${coin(it.value)}"))) }
		add(text("Cost: ${coin(chest.cost)}"))
		add(text("Profit: ${coin(chest.profit)} §f(Pre Cost Profit ${coin(chest.revenue)}§f)"))
	}

	/** "Obsidian Profit": one chest, its revenue, its cost and what is left. */
	private fun chestLines(title: String, chest: Chest): List<Component> = buildList {
		add(text("§d§l$title Profit"))
		add(text(""))
		add(text("§a§lTotal Revenue §a${coin(chest.revenue)}"))
		chest.items.forEach { add(join(it.label, " §a${coin(it.value)}")) }
		if (chest.costs.isNotEmpty()) {
			add(text(" "))
			add(text("§c§lTotal Cost §c${coin(chest.cost)}"))
			chest.costs.forEach { add(join(it.label, " §c${coin(it.value)}")) }
		}
		val color = if (chest.profit < 0) "§c" else "§a"
		add(text(""))
		add(text("$color§lProfit $color${coin(chest.profit)}"))
		// In a dungeon, every chest looked at so far, as SkyHanni lists them.
		if (DungeonLocation.inDungeon && dungeonChests.isNotEmpty()) {
			add(text(""))
			add(text("§d§lAll Chest Profits"))
			dungeonChests.entries.sortedByDescending { it.value }.forEach { add(text("${it.key} ${coin(it.value)}")) }
		}
	}

	/** The profit by a chest's title, and SkyHanni's lists beside the window. */
	@JvmStatic
	fun renderOverlay(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (!module.enabled || screen !is AbstractContainerScreen<*>) return
		val chest = openChest
		val accessor = screen as? ContainerScreenAccessor
		if (profitInTitle.value && chest != null && accessor != null) {
			val font = Minecraft.getInstance().font
			val text = "Profit: " + coin(chest.profit)
			val x = accessor.`cryptic$leftPos`() + accessor.`cryptic$imageWidth`() - 8 - font.width(text)
			context.text(font, text, x, accessor.`cryptic$topPos`() + 6, -1, false)
		}
		if (!profitHud.value || !ItemPrices.loaded) return

		if (chest != null) {
			drawBeside(context, accessor, ChestElement, chestLines(openChestTitle, chest))
			return
		}
		val shown = runChests.filter { !it.opened }
		if (shown.isEmpty()) return
		val placed = drawBeside(context, accessor, OverviewElement, overviewLines(shown))
		// The row under the mouse, past the heading, shows that chest's contents.
		val row = placed.rowAt(mouseX, mouseY) ?: return
		val hovered = shown.getOrNull(row - 1) ?: return
		context.setComponentTooltipForNextFrame(Minecraft.getInstance().font, overviewTip(hovered), mouseX, mouseY)
	}

	/** Where a list ended up on screen, to tell which of its rows the mouse is on. */
	private class Placed(val x: Float, val y: Float, val scale: Float, val width: Int, val rows: Int) {
		fun rowAt(mouseX: Int, mouseY: Int): Int? {
			if (mouseX < x || mouseX > x + width * scale) return null
			val row = ((mouseY - y) / (ROW_HEIGHT * scale)).toInt()
			return if (mouseY >= y && row in 0 until rows) row else null
		}
	}

	/**
	 * Draws a list where it was placed, or beside the window when that would
	 * put it under the window — shrunk if even beside it there is not the room.
	 */
	private fun drawBeside(context: GuiGraphicsExtractor, accessor: ContainerScreenAccessor?, element: ListElement, lines: List<Component>): Placed {
		element.lines = lines
		val width = element.width
		var x = (element.x * context.guiWidth()).toFloat()
		val y = (element.y * context.guiHeight()).toFloat()
		var scale = element.scale.toFloat()
		val left = accessor?.`cryptic$leftPos`()?.toFloat()
		val right = left?.plus(accessor.`cryptic$imageWidth`())
		if (left != null && right != null && x < right && x + width * scale > left) {
			scale = minOf(scale, (left - 8f) / width.coerceAtLeast(1)).coerceAtLeast(0.4f)
			x = maxOf(2f, left - width * scale - 6f)
		}
		val pose = context.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		pose.scale(scale, scale)
		element.render(context)
		pose.popMatrix()
		return Placed(x, y, scale, width, lines.size)
	}

	/** SkyHanni's rows: the font's height and one pixel between them. */
	private val ROW_HEIGHT get() = Minecraft.getInstance().font.lineHeight + 1

	/**
	 * A list drawn over a chest screen. Only placed through the HUD editor:
	 * outside a chest screen there is nothing for it to show, as in SkyHanni.
	 */
	private abstract class ListElement(id: String, name: String) : HudElement(id, name, 0.02, 0.25) {
		private val font get() = Minecraft.getInstance().font

		/** What was last drawn, which is also what gives the element its size. */
		var lines: List<Component> = emptyList()

		abstract fun sample(): List<Component>

		private fun shown(): List<Component> = lines.ifEmpty { sample() }

		override val width: Int get() = shown().maxOf { font.width(it) }
		override val height: Int get() = shown().size * ROW_HEIGHT

		override fun isVisible(): Boolean = false

		override fun showInEditor(): Boolean = module.enabled && profitHud.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, lines)

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, sample())

		private fun draw(context: GuiGraphicsExtractor, lines: List<Component>) {
			lines.forEachIndexed { i, line -> context.text(font, line, 0, i * ROW_HEIGHT, -1) }
		}
	}

	private object OverviewElement : ListElement("croesus_overview", "Croesus Profit Overlay") {
		override fun sample(): List<Component> = overviewLines(SAMPLE_CHESTS)
	}

	private object ChestElement : ListElement("croesus_profit", "Chest Profit") {
		override fun sample(): List<Component> = chestLines("Obsidian", SAMPLE_CHESTS.last())
	}

	private val SAMPLE_CHESTS: List<Chest> by lazy {
		listOf(
			Chest("Gold", 0, emptyList(), listOf(Entry(text("§6227,000 Coins"), -227_000.0)), false),
			Chest("Diamond", 1, emptyList(), listOf(Entry(text("§6329,000 Coins"), -329_000.0)), false),
			Chest(
				"Obsidian", 2,
				listOf(
					Entry(text("§d§lLast Stand I"), 55_000.0),
					Entry(text("§9Overload I"), 867_000.0),
					Entry(text("§dWither Essence §8x71"), 175_000.0),
				),
				listOf(Entry(text(KEY_LABEL), -263_000.0)),
				false,
			),
		)
	}
}
