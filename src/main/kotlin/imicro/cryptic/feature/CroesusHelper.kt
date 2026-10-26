package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
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
 * What each dungeon and Kuudra chest is worth, and which Croesus runs still have one worth opening.
 * Inspired by the Croesus and chest profit features of Odin, Skyblocker, NoammAddons and SkyHanni;
 * the Kuudra Key's price is Odin's (Vesuvius). Prices are Odin's seven-day averages via
 * [ItemPrices].
 *
 * Three screens are read: Croesus's run list, a run's chests (contents and cost in each
 * description: six for a dungeon, Free and Paid for Kuudra), and a chest itself (the same at
 * Croesus, in the boss room and at Kuudra). Whether a run is worth a key only shows on its own
 * screen, so a run's chests are remembered once looked at, by page and slot.
 */
object CroesusHelper {
	@JvmField
	val highlightRuns = ToggleModuleSetting(
		id = "highlight_runs",
		label = "Highlight runs",
		defaultValue = true,
		description = "Colours each run at Croesus, dungeon and Kuudra alike: not opened, worth a key, or done.",
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
		label = "Key profit",
		unit = "k",
		defaultValue = 200.0,
		min = 100.0,
		max = 500.0,
		step = 25.0,
		description = "How much a chest has to make after its cost (coins and a Dungeon Chest Key, or its Kuudra Key) to be worth opening.",
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
		description = "Writes a chest's profit beside its title when you open it, at Croesus, in the boss room or at Kuudra.",
	)

	@JvmField
	val profitHud = ToggleModuleSetting(
		id = "profit_hud",
		label = "Profit overlays",
		defaultValue = true,
		description = "A list beside the chest screen: a run's chests at Croesus, best first, or what one chest holds when you open it. " +
			"Hover a chest in the run's list for what is in it.",
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
		description = "Which chests are worth it",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(
			highlightRuns, highlightChests, minKeyProfit, includeEssence, profitInTitle, profitHud,
			colorSection, unopenedColor, keyColor, doneColor,
		),
	)

	/** "Croesus", or "(2/3) Croesus" past the first page; Vesuvius on the Crimson Isle keeps the Kuudra chests the same way. */
	private val CROESUS_TITLE = Regex("""^(?:\((\d+)/\d+\) )?(?:Croesus|Vesuvius)$""")

	/** Vesuvius lists only Kuudra runs, so its slots don't line up with the runs kept for Croesus. */
	private fun isVesuvius(title: String) = title.endsWith("Vesuvius")

	private val RUN_TITLE = Regex("""^(?:(?:Master )?Catacombs|Kuudra) - .*$""")

	/** A chest: "Bedrock" on the run's screen, possibly "Bedrock Chest" on its own. */
	private val CHEST_TITLE = Regex("""^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$""")

	/** A Kuudra chest's screen; Hypixel sometimes doubles the word ("Paid Chest Chest"). */
	private val KUUDRA_CHEST_TITLE = Regex("""^(Free|Paid) Chest(?: Chest)?$""")

	private val CHEST_NAME = Regex("""^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)$""")

	/** Kuudra's two chests on its run screen, by type. */
	private val KUUDRA_CHEST_NAME = Regex("""^(Free|Paid) Chest$""")

	private val RUN_NAMES = setOf("The Catacombs", "Master Mode The Catacombs", KUUDRA_RUN)

	private const val KUUDRA_RUN = "Kuudra's Hollow"

	/** How [CroesusRuns] files every Kuudra run; Croesus's list doesn't say the tier. */
	private const val KUUDRA_FLOOR = "K"

	/** Hypixel's line when Kuudra dies, which puts the run on Croesus's list. */
	private const val KUUDRA_DOWN = "KUUDRA DOWN!"

	private var kuudraDownAt = 0L

	/** Buttons in a chest that are not loot. */
	private val NOT_LOOT = setOf("Reroll Shard", "Reroll Chest")

	private val OPENED_CHEST = Regex("""^Opened Chest: (\w+)""")

	private const val UNOPENED = "No chests opened yet!"
	private const val NO_MORE = "No more chests to open!"
	private const val ALREADY_OPENED = "Already opened!"

	private val COINS = Regex("""^([\d,]+) Coins$""")
	private val ESSENCE = Regex("""^(\w+) Essence x(\d+)$""")
	private val SHARD = Regex("""^(.+) Shard(?: x(\d+))?$""")
	private val AMOUNT = Regex("""^(.+) x(\d+)$""")
	private val BOOK = Regex("""^Enchanted Book \((.+) ([IVXL]+|\d+)\)$""")
	private val FORMATTING = Regex("§.")

	private const val KEY_ID = "DUNGEON_CHEST_KEY"
	private const val KEY_LABEL = "§9Dungeon Chest Key"

	/**
	 * Drops whose names aren't their ids; other names convert directly ("Fifth Master Star" is
	 * FIFTH_MASTER_STAR). Hypixel's ids, as Skyblocker and Odin list them.
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
		"Aurora Staff" to "RUNIC_STAFF",
		"Hellstorm Wand" to "HELLSTORM_STAFF",
		"Dusty Travel Scroll to the Kuudra Skull" to "NETHER_FORTRESS_BOSS_TRAVEL_SCROLL",
	)

	/** Kuudra's books whose enchant is named differently from its id. */
	private val BOOK_IDS = mapOf(
		"Strong Vitality" to "STRONG_MANA",
		"Vivacious Vitality" to "FEROCIOUS_MANA",
		"Hardened Vitality" to "HARDENED_MANA",
		"Vampiric Vitality" to "MANA_VAMPIRE",
	)

	/**
	 * A Kuudra Key's cost: coins, then Enchanted Red Sand or Mycelium (whichever is cheaper) and
	 * two Corrupted Nether Stars. Odin's figures; the coins are before faction discounts.
	 */
	private class KuudraKey(val coins: Double, val material: Int)

	private val KUUDRA_KEYS = mapOf(
		"Kuudra Key" to KuudraKey(155_200.0, 2),
		"Hot Kuudra Key" to KuudraKey(310_400.0, 4),
		"Burning Kuudra Key" to KuudraKey(582_000.0, 16),
		"Fiery Kuudra Key" to KuudraKey(1_164_000.0, 40),
		"Infernal Kuudra Key" to KuudraKey(2_328_000.0, 80),
	)

	private fun kuudraKeyPrice(name: String): Double {
		val key = KUUDRA_KEYS[name] ?: return 0.0
		val material = listOfNotNull(ItemPrices.price("ENCHANTED_RED_SAND"), ItemPrices.price("ENCHANTED_MYCELIUM")).minOrNull() ?: 0.0
		return key.coins + material * key.material + (ItemPrices.price("CORRUPTED_NETHER_STAR") ?: 0.0) * 2
	}

	/** A cost line in a chest's description, as a negative value, or null when it isn't one. */
	private fun costOf(line: String): Double? {
		COINS.matchEntire(line)?.let { return -it.groupValues[1].replace(",", "").toDouble() }
		if (line == "Dungeon Chest Key") return -keyPrice()
		if (line in KUUDRA_KEYS) return -kuudraKeyPrice(line)
		return null
	}

	private val CHEST_COLORS = mapOf(
		"Wood" to "§f",
		"Gold" to "§6",
		"Diamond" to "§b",
		"Emerald" to "§2",
		"Obsidian" to "§5",
		"Bedrock" to "§8",
		"Free" to "§a",
		"Paid" to "§6",
	)

	data class Entry(val label: Component, val value: Double)

	data class Chest(
		val type: String,
		val slot: Int,
		val items: List<Entry>,
		val costs: List<Entry>,
		val opened: Boolean,
		/** Kuudra's chests carry their own key in their cost, and never need a dungeon key. */
		val kuudra: Boolean = false,
	) {
		val revenue: Double get() = items.sumOf { it.value }
		val cost: Double get() = costs.sumOf { it.value }
		val profit: Double get() = revenue + cost

		/** The profit if opened as a run's second chest, with a key. */
		val profitWithKey: Double
			get() = if (hasKey) profit else profit - keyPrice()

		val hasKey: Boolean get() = kuudra || costs.any { clean(it.label.string) == "Dungeon Chest Key" }

		val name: String get() = (CHEST_COLORS[type] ?: "§f") + type
	}

	/** The run just clicked, until its screen opens: its place in [CroesusRuns] and its floor. */
	private var pendingRun: Pair<Int, String>? = null

	private var endRecorded = false

	/** The open run screen's chests, worked out once per tick. */
	private var runChests: List<Chest> = emptyList()

	/** The open chest screen, and its title. */
	private var openChest: Chest? = null
	private var openChestTitle = ""

	/** Every chest's profit seen in this dungeon, by screen name. */
	private val dungeonChests = LinkedHashMap<String, Double>()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			dungeonChests.clear()
			costMemory.keys.removeIf { it.startsWith("dungeon|") }
		}
	}

	private fun keyPrice(): Double = ItemPrices.price(KEY_ID) ?: 0.0

	private fun minProfit(): Double = minKeyProfit.value * 1000.0

	fun tick(client: Minecraft) {
		// Recorded whether or not the module is on: the list must follow Croesus's, and a missed
		// run shifts every later one.
		if (DungeonRun.ended && DungeonLocation.inDungeon) {
			if (!endRecorded && DungeonLocation.floor > 0) {
				CroesusRuns.onRunFinished((if (DungeonLocation.masterMode) "M" else "F") + DungeonLocation.floor)
			}
			endRecorded = true
		} else if (!DungeonRun.ended) {
			endRecorded = false
		}

		runChests = emptyList()
		openChest = null
		if (!module.enabled) return
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
		val title = clean(screen.title.string)
		val croesus = CROESUS_TITLE.matches(title)
		val run = RUN_TITLE.matches(title)
		val chest = CHEST_TITLE.matchEntire(title) ?: KUUDRA_CHEST_TITLE.matchEntire(title)
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
	 * The open run screen's chests by slot. A clicked chest briefly reads empty while on the
	 * cursor, so an empty slot keeps its last chest (otherwise the highlight jumped to the second
	 * best).
	 */
	private val runSlots = HashMap<Int, Chest>()
	private var runSlotsScreen: Any? = null

	/**
	 * What each chest cost, remembered from when it showed its price; an opened chest no longer
	 * shows its cost. Kept per run (or per dungeon for boss-room chests), for the session.
	 */
	private val costMemory = HashMap<String, List<Entry>>()

	private fun costKey(type: String): String =
		(if (DungeonLocation.inDungeon) "dungeon" else pendingRun?.first?.toString() ?: "croesus") + "|" + type

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
			val name = clean(stack.hoverName.string)
			val kuudra = KUUDRA_CHEST_NAME.matchEntire(name)
			if (stack.item != Items.PLAYER_HEAD || (kuudra == null && !CHEST_NAME.matches(name))) {
				runSlots.remove(slot.index)
				continue
			}
			runSlots[slot.index] = chestFromLore(kuudra?.groupValues?.get(1) ?: name, slot.index, stack, kuudra != null)
		}
		val chests = runSlots.values.sortedBy { it.slot }
		// After the first chest, every other one needs a key, whether or not its description says
		// so yet.
		val anyOpened = chests.any { it.opened }
		runChests = chests.map { chest ->
			withRememberedCost(
				if (!anyOpened || chest.opened || chest.hasKey) chest
				else chest.copy(costs = chest.costs + Entry(Component.literal(KEY_LABEL), -keyPrice())),
			)
		}

		// Only with prices, or the run would be remembered as worthless.
		if (!ItemPrices.loaded) return
		pendingRun?.let { (index, floor) ->
			if (runChests.isNotEmpty()) CroesusRuns.learn(index, floor, runChests.associate { it.type to it.profitWithKey })
		}
	}

	private fun chestFromLore(type: String, slot: Int, stack: ItemStack, kuudra: Boolean = false): Chest {
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
			costOf(line)?.let { costs += Entry(component, it) }
		}
		return Chest(type, slot, items, costs, opened, kuudra)
	}

	private fun readChest(screen: AbstractContainerScreen<*>, type: String, title: String) {
		val kuudra = type == "Free" || type == "Paid"
		val items = LinkedHashMap<String, Entry>()
		val costs = ArrayList<Entry>()
		var costRead = false
		for (slot in containerSlots(screen)) {
			if (slot.index > 40) continue
			val stack = slot.item
			if (stack.isEmpty || isPane(stack)) continue
			if (stack.item == Items.CHEST) {
				// The button that opens the chest carries the cost.
				val lore = loreComponents(stack)
				if (lore.none { clean(it.string) == "Cost" }) continue
				costRead = true
				lore.forEach { component -> costOf(clean(component.string))?.let { costs += Entry(component, it) } }
				continue
			}
			if (clean(stack.hoverName.string) in NOT_LOOT) continue
			// The same item twice is one line.
			val entry = stackValue(stack) ?: continue
			val key = entry.label.string
			items[key] = items[key]?.let { it.copy(value = it.value + entry.value) } ?: entry
		}
		if (!costRead && items.isEmpty()) return
		val chest = withRememberedCost(Chest(type, -1, items.values.toList(), costs, false, kuudra))
		openChest = chest
		openChestTitle = title
		if (DungeonLocation.inDungeon && ItemPrices.loaded) dungeonChests[title] = chest.profit
	}

	private fun isPane(stack: ItemStack): Boolean = BuiltInRegistries.ITEM.getKey(stack.item).path.endsWith("stained_glass_pane")

	/** An item in an open chest, by its id. Null without a price. */
	private fun stackValue(stack: ItemStack): Entry? {
		val name = clean(stack.hoverName.string)
		ESSENCE.matchEntire(name)?.let { match ->
			if (!includeEssence.value) return null
			val each = ItemPrices.price("ESSENCE_" + match.groupValues[1].uppercase(Locale.ROOT)) ?: return null
			return Entry(stack.hoverName, each * match.groupValues[2].toInt())
		}
		val id = SkyblockItem.id(stack).removePrefix("STARRED_")
		if (id == "ENCHANTED_BOOK") {
			// A book is called "Enchanted Book"; its first lore line says which.
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
			val named = match.groupValues[1]
			val enchant = BOOK_IDS[named] ?: named.uppercase(Locale.ROOT).replace(" ", "_")
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
		// A pet ("[Lvl 1] Spirit") isn't worth pricing.
		if (line.startsWith("[Lvl")) return null
		// A stack written with its count ("Kuudra Teeth x4").
		AMOUNT.matchEntire(line)?.let { match ->
			val each = nameValue(match.groupValues[1]) ?: return null
			return each * match.groupValues[2].toInt()
		}
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

	/**
	 * Kuudra dying puts a run at the front of Croesus's list, like a finished dungeon, so it's
	 * recorded to keep the list in step.
	 */
	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || clean(content.string) != KUUDRA_DOWN) return
		val now = System.currentTimeMillis()
		// The line can arrive twice; one run is one entry.
		if (now - kuudraDownAt < 60_000L) return
		kuudraDownAt = now
		Minecraft.getInstance().execute { CroesusRuns.onRunFinished(KUUDRA_FLOOR) }
	}

	/** A click in the run list: remembers which run is opening. */
	@JvmStatic
	fun onSlotClicked(slotId: Int) {
		if (!module.enabled || slotId < 0) return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
		val page = CROESUS_TITLE.matchEntire(clean(screen.title.string))?.groupValues?.get(1) ?: return
		if (isVesuvius(clean(screen.title.string))) return
		val slot = screen.menu.slots.getOrNull(slotId) ?: return
		val stack = slot.item
		if (clean(stack.hoverName.string) !in RUN_NAMES) return
		val index = CroesusRuns.indexOf(page.toIntOrNull() ?: 1, slot.index) ?: return
		pendingRun = index to floorOf(stack)
	}

	/** A run's floor as [CroesusRuns] keeps it ("M7", "F5"). */
	private fun floorOf(stack: ItemStack): String {
		if (clean(stack.hoverName.string) == KUUDRA_RUN) return KUUDRA_FLOOR
		val master = clean(stack.hoverName.string).startsWith("Master")
		val number = lore(stack).firstNotNullOfOrNull { FLOOR_LINE.find(it) }?.groupValues?.get(1)?.let(::romanToInt) ?: 0
		return (if (master) "M" else "F") + number
	}

	private val FLOOR_LINE = Regex("""^Floor ([IVX]+)""")

	/** Draws a colour behind a slot's item. */
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
		// Kuudra's tooltip never says "No chests opened yet!", and only one of its chests can be
		// opened, so it's either untouched or done.
		if (clean(stack.hoverName.string) == KUUDRA_RUN) {
			return if (lines.any { OPENED_CHEST.containsMatchIn(it) }) doneColor.argb else unopenedColor.argb
		}
		if (lines.any { it == UNOPENED }) return unopenedColor.argb
		val opened = lines.firstNotNullOfOrNull { OPENED_CHEST.find(it)?.groupValues?.get(1) } ?: return null
		val profits = CroesusRuns.indexOf(page.toIntOrNull() ?: 1, slot.index)?.let { CroesusRuns.at(it, floorOf(stack)) }?.keyProfits
			// Not looked at yet: worth opening to find out.
			?: return keyColor.argb
		val worth = profits.any { (type, profit) -> type != opened && profit >= minProfit() }
		return if (worth) keyColor.argb else doneColor.argb
	}

	private fun chestColor(slot: Slot): Int? {
		val chest = runChests.firstOrNull { it.slot == slot.index } ?: return null
		if (chest.opened) return doneColor.argb
		val unopened = runChests.filter { !it.opened }
		if (chest.kuudra) {
			// One chest per Kuudra run: the better one, until either is opened.
			if (runChests.any { it.opened }) return null
			return if (unopened.maxByOrNull { it.profit } === chest && chest.profit > 0) unopenedColor.argb else null
		}
		if (runChests.none { it.opened }) {
			// The first chest is the most profitable; a second is worth its key on top.
			val ranked = unopened.sortedByDescending { it.profit }
			if (ranked.firstOrNull() === chest && chest.profit > 0) return unopenedColor.argb
			if (ranked.getOrNull(1) === chest && chest.profitWithKey >= minProfit()) return keyColor.argb
			return null
		}
		val best = unopened.maxByOrNull { it.profitWithKey }
		return if (best === chest && chest.profitWithKey >= minProfit()) keyColor.argb else null
	}

	/**
	 * A short number of at most three figures, trailing zeros dropped (1.3M, 263k, 12.3k, 9.87k,
	 * 1M). Truncated, so a chest never looks worth more than it is.
	 */
	private fun short(input: Double): String {
		if (input < 0) return "-" + short(-input)
		val value = input.toLong()
		if (value < 1000) return value.toString()
		val (unit, suffix) = UNITS.first { value >= it.first }
		val whole = value / unit
		val decimals = when {
			whole >= 100 -> 0
			whole >= 10 -> 1
			else -> 2
		}
		var scaled = value * POWERS[decimals] / unit
		var places = decimals
		while (places > 0 && scaled % 10 == 0L) {
			scaled /= 10
			places--
		}
		val text = if (places == 0) scaled.toString() else scaled.toString().padStart(places + 1, '0').let {
			it.dropLast(places) + "." + it.takeLast(places)
		}
		return text + suffix
	}

	private val UNITS = listOf(1_000_000_000_000L to "T", 1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "k")
	private val POWERS = longArrayOf(1, 10, 100)

	private fun signed(value: Double): String = if (value < 0) "§c${short(value)}" else "§a+${short(value)}"

	private fun text(legacy: String): Component = Component.literal(legacy)

	/** One list row: words left, a value aligned right. A [rule] row is a thin divider. */
	private class Row(val left: Component, val right: Component? = null, val rule: Boolean = false) {
		constructor(left: String, right: String? = null) : this(text(left), right?.let(::text))

		companion object {
			val RULE = Row(Component.empty(), rule = true)
		}
	}

	/** A run's unopened chests, best first, the one worth opening starred. */
	private fun overviewRows(chests: List<Chest>): List<Row> = buildList {
		add(Row("§6§lChests"))
		val ranked = chests.sortedByDescending { it.profit }
		ranked.forEachIndexed { index, chest ->
			val star = if (index == 0 && chest.profit > 0) " §e★" else ""
			add(Row("${chest.name}$star", signed(chest.profit)))
		}
		val key = keyPrice()
		if (key > 0) {
			add(Row.RULE)
			add(Row("§7Chest Key", "§7${short(key)}"))
		}
	}

	private fun overviewOrder(chests: List<Chest>): List<Chest> = chests.sortedByDescending { it.profit }

	private fun overviewTip(chest: Chest): List<Component> = buildList {
		add(text(chest.name))
		chest.items.forEach { add(Component.empty().append(it.label).append(text(" §7${short(it.value)}"))) }
		if (chest.costs.isNotEmpty()) add(text("§7Costs ${signed(chest.cost)}"))
		add(text("§7Profit ${signed(chest.profit)}"))
	}

	/** One chest: each item and its value, the costs, and the profit. */
	private fun chestRows(title: String, chest: Chest): List<Row> = buildList {
		add(Row("§d§l$title"))
		chest.items.sortedByDescending { it.value }.forEach { add(Row(it.label, text(signed(it.value)))) }
		chest.costs.forEach { add(Row(it.label, text(signed(it.value)))) }
		add(Row.RULE)
		add(Row("§lProfit", "§l" + signed(chest.profit)))
		// In a dungeon, every chest seen so far, to compare.
		if (DungeonLocation.inDungeon && dungeonChests.size > 1) {
			add(Row.RULE)
			add(Row("§7This run"))
			dungeonChests.entries.sortedByDescending { it.value }.forEach { (name, profit) ->
				val mark = if (name == title) "§f› " else "§7"
				add(Row(mark + name.removeSuffix(" Chest"), signed(profit)))
			}
		}
	}

	/** The profit beside a chest's title, and the profit list beside the window. */
	@JvmStatic
	fun renderOverlay(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (!module.enabled || screen !is AbstractContainerScreen<*>) return
		val chest = openChest
		val accessor = screen as? ContainerScreenAccessor
		if (profitInTitle.value && chest != null && accessor != null) {
			val font = Minecraft.getInstance().font
			val text = signed(chest.profit)
			val x = accessor.`cryptic$leftPos`() + accessor.`cryptic$imageWidth`() - 8 - font.width(text)
			context.text(font, text, x, accessor.`cryptic$topPos`() + 6, -1, false)
		}
		if (!profitHud.value || !ItemPrices.loaded) return

		if (chest != null) {
			drawBeside(context, accessor, chestRows(openChestTitle, chest))
			return
		}
		val shown = runChests.filter { !it.opened }
		if (shown.isEmpty()) return
		val placed = drawBeside(context, accessor, overviewRows(shown))
		// The hovered row (after the heading) shows that chest's contents.
		val row = placed.rowAt(mouseX, mouseY) ?: return
		val hovered = overviewOrder(shown).getOrNull(row - 1) ?: return
		context.setComponentTooltipForNextFrame(Minecraft.getInstance().font, overviewTip(hovered), mouseX, mouseY)
	}

	private class Placed(val x: Float, val y: Float, val scale: Float, val width: Int, val rows: Int) {
		fun rowAt(mouseX: Int, mouseY: Int): Int? {
			if (mouseX < x || mouseX > x + width * scale) return null
			val inside = (mouseY - y) / scale
			val row = (inside / ROW_HEIGHT).toInt()
			return if (inside >= 0 && row in 0 until rows) row else null
		}
	}

	private val ROW_HEIGHT get() = Minecraft.getInstance().font.lineHeight + 2

	private const val COLUMN_GAP = 12
	private const val WINDOW_GAP = 8

	/** The smallest the list shrinks to when there's little room beside the window. */
	private const val MIN_SCALE = 0.5f

	private const val RULE_COLOR = 0x40FFFFFF

	/**
	 * Draws the list left of the window, centred on the window's middle (a run's window and a
	 * chest's differ in height). Shrunk only when the gap is too narrow.
	 */
	private fun drawBeside(context: GuiGraphicsExtractor, accessor: ContainerScreenAccessor?, rows: List<Row>): Placed {
		val font = Minecraft.getInstance().font
		val width = contentWidth(font, rows)
		val height = rows.size * ROW_HEIGHT - 2
		val left = accessor?.`cryptic$leftPos`() ?: (context.guiWidth() / 2 - 88)
		val top = accessor?.`cryptic$topPos`() ?: (context.guiHeight() / 2 - 83)
		val windowHeight = accessor?.`cryptic$imageHeight`() ?: 166

		val room = (left - WINDOW_GAP - 2).toFloat()
		val scale = if (width <= room) 1f else (room / width.coerceAtLeast(1)).coerceAtLeast(MIN_SCALE)
		val x = maxOf(2f, left - WINDOW_GAP - width * scale)
		val y = top + windowHeight / 2f - height * scale / 2f

		val pose = context.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		pose.scale(scale, scale)
		rows.forEachIndexed { i, row ->
			val rowY = i * ROW_HEIGHT
			if (row.rule) {
				val middle = rowY + (ROW_HEIGHT - 2) / 2
				context.fill(0, middle, width, middle + 1, RULE_COLOR)
				return@forEachIndexed
			}
			context.text(font, row.left, 0, rowY, -1)
			row.right?.let { context.text(font, it, width - font.width(it), rowY, -1) }
		}
		pose.popMatrix()
		return Placed(x, y, scale, width, rows.size)
	}

	/** Wide enough for the longest valued words, a gap and the longest value, or for an unvalued row. */
	private fun contentWidth(font: net.minecraft.client.gui.Font, rows: List<Row>): Int {
		val valued = rows.filter { it.right != null }
		val words = valued.maxOfOrNull { font.width(it.left) } ?: 0
		val values = valued.maxOfOrNull { font.width(it.right!!) } ?: 0
		val lone = rows.filter { it.right == null }.maxOfOrNull { font.width(it.left) } ?: 0
		return maxOf(lone, if (values > 0) words + COLUMN_GAP + values else words)
	}
}
