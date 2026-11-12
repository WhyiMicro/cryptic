package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.MultiSelectModuleSetting
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.ItemRarity
import imicro.cryptic.skyblock.ParticleTrail
import imicro.cryptic.skyblock.SackContents
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Base64
import java.util.Locale
import java.util.Optional

/**
 * The Garden: what your visitors want and what you're short of, the pest a vacuum points at, lines
 * to the pests in sight, when pests can spawn next, and keeping your spot while you hunt them.
 */
object Farming {
	private val visitorsSection = SectionModuleSetting("visitors_section", "Visitors")

	private const val WHERE_GARDEN = "Anywhere in the Garden"
	private const val WHERE_VISITORS = "Near the visitors"
	private const val WHERE_BAZAAR = "Near the Hub's Bazaar"
	private const val WHERE_BAZAAR_MENUS = "In Bazaar menus"
	private const val WHERE_VISITOR_MENU = "In a visitor's menu"

	@JvmField
	val listWhere = MultiSelectModuleSetting(
		"visitor_list_where", "Show",
		listOf(WHERE_GARDEN, WHERE_VISITORS, WHERE_BAZAAR, WHERE_BAZAAR_MENUS, WHERE_VISITOR_MENU),
		defaultSelected = setOf(WHERE_VISITORS, WHERE_BAZAAR, WHERE_BAZAAR_MENUS, WHERE_VISITOR_MENU),
		description = "Where the list shows; anywhere else it stays hidden. Bazaar menus count wherever you open them.",
	)

	private const val MENUS_BOTH = "In and out of menus"
	private const val MENUS_ONLY = "Only in menus"
	private const val MENUS_NEVER = "Only outside menus"

	@JvmField
	val listMenus = DropdownModuleSetting(
		"visitor_list_menus", "Menus", listOf(MENUS_BOTH, MENUS_ONLY, MENUS_NEVER), 0,
		"Whether it shows with a menu or your inventory open, outside them, or both. In a menu, hover an item for details and click it to open it on the Bazaar.",
	)

	private const val PRICE_INSTANT = "Instant buy"
	private const val PRICE_ORDER = "Buy order"
	private const val PRICE_OFF = "Off"

	@JvmField
	val listPrice = DropdownModuleSetting(
		"visitor_list_price", "Prices", listOf(PRICE_INSTANT, PRICE_ORDER, PRICE_OFF), 0,
		"What each item costs on the Bazaar for the amount wanted, and the total.",
	)

	@JvmField
	val listNewVisitors = ToggleModuleSetting(
		"visitor_list_new", "New visitors", true,
		"Below the items, the visitors waiting in the Garden whose menu you haven't opened, so what they want isn't known yet. Read from the tab list's Visitors widget.",
	)

	@JvmField
	val listVisitors = ToggleModuleSetting(
		"visitor_list_names", "Visitors", true,
		"Below those, the visitors whose menu you've opened, whose wants make up the list.",
	)

	@JvmField
	val visitorList = ToggleModuleSetting(
		"visitor_list", "Shopping list", true,
		"A HUD element with everything your visitors want, what it costs, and what your sacks hold of it. A visitor is read when you open their " +
			"menu, and leaves the list once you give to them or refuse them, or once the Garden's tab list no longer names them (served by your co-op, say).",
	).withMore(listWhere, listMenus, listPrice, listNewVisitors, listVisitors)

	@JvmField
	val visitorPresets = ToggleModuleSetting(
		"visitor_presets", "Bazaar amounts", true,
		"On a visitor item's buy order or instant buy, two amount presets: what you're short counting your sacks, and counting only your " +
			"inventory, to keep what's in your sacks.",
	)

	private val toolsSection = SectionModuleSetting("tools_section", "Tools")

	/** A crop's mousemat angles, editable; the defaults are the ones SkyHanni suggests. */
	private class Crop(val name: String, val icon: net.minecraft.world.item.Item, val yaw: Float, val pitch: Float) {
		val setting = TextModuleSetting(
			"mousemat_" + name.lowercase(Locale.ROOT).replace(' ', '_'), name, "${number(yaw)} ${number(pitch)}",
			maxLength = 24, hint = "yaw pitch", description = "$name's yaw and pitch, as \"90 0\".",
		)

		/** The yaw and pitch as set, or the defaults when it doesn't read as two numbers. */
		fun angles(): Pair<Float, Float> {
			val numbers = NUMBER.findAll(setting.value).mapNotNull { it.value.toFloatOrNull() }.toList()
			return if (numbers.size == 2) numbers[0] to numbers[1] else yaw to pitch
		}
	}

	private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

	private fun number(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else value.toString()

	private val CROPS = listOf(
		Crop("Wheat", net.minecraft.world.item.Items.WHEAT, 90f, 0f),
		Crop("Carrot", net.minecraft.world.item.Items.CARROT, -90f, 2.8f),
		Crop("Potato", net.minecraft.world.item.Items.POTATO, -90f, 2.8f),
		Crop("Nether Wart", net.minecraft.world.item.Items.NETHER_WART, 90f, 0f),
		Crop("Pumpkin", net.minecraft.world.item.Items.CARVED_PUMPKIN, 90f, -58.5f),
		Crop("Melon Slice", net.minecraft.world.item.Items.MELON_SLICE, 90f, -58.5f),
		Crop("Cocoa Beans", net.minecraft.world.item.Items.COCOA_BEANS, 180f, -45f),
		Crop("Sugar Cane", net.minecraft.world.item.Items.SUGAR_CANE, -135f, 0f),
		Crop("Cactus", net.minecraft.world.item.Items.CACTUS, -90f, 0f),
		Crop("Mushroom", net.minecraft.world.item.Items.RED_MUSHROOM, 116.5f, 0f),
		Crop("Sunflower", net.minecraft.world.item.Items.SUNFLOWER, 45f, 0f),
		Crop("Moonflower", net.minecraft.world.item.Items.BLUE_ORCHID, 45f, 0f),
		Crop("Wild Rose", net.minecraft.world.item.Items.ROSE_BUSH, 45f, 0f),
	)

	@JvmField
	val mousematPresets = ToggleModuleSetting(
		"mousemat_presets", "Mousemat presets", true,
		"Beside a mousemat's sign, each crop's yaw and pitch. Click one to write it into the sign, then press Done. Change a crop's angles under ⋯.",
	).withMore(*CROPS.map { it.setting }.toTypedArray())

	private val pestsSection = SectionModuleSetting("pests_section", "Pests")

	@JvmField
	val vacuumTracer = ToggleModuleSetting("vacuum_tracer", "Line to it", true, "A line from the crosshair to where the vacuum points.")

	@JvmField
	val vacuumSolver = ToggleModuleSetting(
		"vacuum_solver", "Vacuum solver", true,
		"Left-clicking a vacuum sends a trail of particles toward the nearest pest. The trail is followed to its end, " +
			"and that spot is marked with a beam and its distance.",
	).withMore(vacuumTracer)

	@JvmField
	val pestTracers = ToggleModuleSetting(
		"pest_tracers", "Pest tracers", false,
		"Lines to every pest close enough to be seen, and a box around each.",
		restriction = Restriction.SEMI_LEGIT,
	)

	@JvmField
	val pestCooldownHud = ToggleModuleSetting(
		"pest_cooldown_hud", "Cooldown HUD", false,
		"A HUD element counting down to when pests can spawn again. Place it in the HUD editor.",
	)

	@JvmField
	val pestCooldown = ToggleModuleSetting(
		"pest_cooldown", "Pest cooldown", false,
		"A toast when pests can spawn again. Read from the tab list, so Hypixel's Pests widget has to be on (/widget).",
	).withMore(pestCooldownHud)

	@JvmField
	val autoSetspawn = ToggleModuleSetting(
		"auto_setspawn", "Auto setspawn", false,
		"A second after pests spawn, sets your spawn where you stand; when the last one dies, warps you back to it. Only on a numbered plot, never the barn's or the greenhouse. Needs the Pests widget too.",
		restriction = Restriction.SEMI_LEGIT,
	)

	@JvmField
	val module = Module(
		id = "farming",
		name = "Farming",
		description = "Visitors, vacuum and pests",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(visitorsSection, visitorList, visitorPresets, toolsSection, mousematPresets, pestsSection, vacuumSolver, pestTracers, pestCooldown, autoSetspawn),
	)

	private val client: Minecraft get() = Minecraft.getInstance()
	private val FORMATTING = Regex("§.")

	val inGarden: Boolean get() = SkyblockLocation.onSkyblock && SkyblockLocation.area == "Garden"

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		Hud.register(PestCooldownElement())
		Hud.register(listElement)
	}

	private var level: ClientLevel? = null
	private var ticks = 0

	fun tick(client: Minecraft) {
		if (!module.enabled) return
		if (client.level !== level) {
			level = client.level
			clearGuess()
			pests = emptyList()
		}
		val screen = client.gui.screen()
		SackContents.readMenu(screen)
		SackContents.tick()
		if (inGarden) (screen as? AbstractContainerScreen<*>)?.let(::readVisitor)
		val held = client.player?.mainHandItem
		if (inGarden && held != null && SkyblockItem.id(held) in VACUUMS) vacuumHeldAt = System.currentTimeMillis()
		if (++ticks % 5 == 0) {
			if (visitors.isNotEmpty()) SackContents.ensureFromApi()
			if (inGarden) readTabVisitors(client)
			tickVisitorList(client)
			pests = if (pestTracers.value && inGarden) findPests() else emptyList()
		}
		tickGuess()
		tickPests()
	}

	// ---- Visitors -----------------------------------------------------------------------------

	/** A visitor, the colour of their rarity, and what they want by item name. */
	private class Visitor(val name: String, val wants: Map<String, Int>, val colour: String)

	private val visitors = LinkedHashMap<String, Visitor>()

	/** The visitors the Garden's tab list last named, with their colour code. Kept off the island. */
	private var waiting: List<Pair<String, String>> = emptyList()

	private var nearVisitors = false
	private var nearBazaar = false

	/** "Enchanted Hay Bale x64", or just a name for one. */
	private val requirement = Regex("""^(.+?)(?: x([\d,]+))?$""")

	/**
	 * A visitor's menu: their head (with "Times Visited") in the middle of the top, and the accept
	 * button below it listing "Items Required:" down to a blank line.
	 */
	private fun readVisitor(screen: AbstractContainerScreen<*>) {
		val head = screen.menu.slots.getOrNull(13)?.item ?: return
		val headLore = head.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "").trim() } ?: return
		if (headLore.none { it.contains("Times Visited") }) return
		val name = head.hoverName.string.replace(FORMATTING, "").trim()
		if (name in visitors) return
		val accept = screen.menu.slots.getOrNull(29)?.item?.get(DataComponents.LORE)?.lines()
			?.map { it.string.replace(FORMATTING, "").trim() } ?: return
		val wants = LinkedHashMap<String, Int>()
		accept.dropWhile { !it.contains("Items Required") }.drop(1).takeWhile { it.isNotEmpty() }.forEach { line ->
			val match = requirement.find(line) ?: return@forEach
			val amount = match.groupValues[2].replace(",", "").toIntOrNull() ?: 1
			wants.merge(match.groupValues[1].trim(), amount, Int::plus)
		}
		if (wants.isNotEmpty()) visitors[name] = Visitor(name, wants, colourCode(head.hoverName))
	}

	/** "Visitors: (2)" in the Garden's tab list, with one visitor's name on each row below it. */
	private val visitorsHeading = Regex("""(?:^|[^A-Za-z])Visitors:?\s*\(?(\d+)\)?""")

	/**
	 * Reads who's waiting from the Garden's tab list, and drops read visitors it no longer names, so
	 * ones served by a co-op member (or gone while you were away) leave the list. Pruning only acts on
	 * a whole list.
	 */
	private fun readTabVisitors(client: Minecraft) {
		val connection = client.connection ?: return
		val rows = connection.listedOnlinePlayers
			.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
			.map { it.tabListDisplayName }
		val plain = rows.map { row -> row?.string?.replace(FORMATTING, "")?.trim()?.trimStart { !it.isLetterOrDigit() }.orEmpty() }
		val at = plain.indexOfFirst { visitorsHeading.containsMatchIn(it) && !it.startsWith("New", ignoreCase = true) }
		if (at < 0) return
		val count = visitorsHeading.find(plain[at])?.groupValues?.get(1)?.toIntOrNull() ?: return
		// Names run down to the next widget's heading or a blank row.
		val names = plain.drop(at + 1).take(count).takeWhile { it.isNotEmpty() && ':' !in it }
		waiting = names.mapIndexed { i, name -> name to (rows[at + 1 + i]?.let(::colourCode) ?: "§f") }
		if (names.size == count) visitors.keys.removeAll { visitor -> names.none { namesMatch(it, visitor) } }
	}

	private fun namesMatch(tab: String, visitor: String) = tab.equals(visitor, ignoreCase = true) || tab.contains(visitor, ignoreCase = true)

	/** The colour a tab row's name is written in: the last code before its first letter, or its style's. */
	private fun colourCode(row: Component): String {
		val raw = row.string
		var code: String? = null
		var i = 0
		while (i < raw.length) {
			val c = raw[i]
			if (c == '§' && i + 1 < raw.length) {
				ChatFormatting.getByCode(raw[i + 1])?.takeIf { it <= ChatFormatting.WHITE }?.let { code = it.toString() }
				i += 2
				continue
			}
			if (c.isLetterOrDigit()) break
			i++
		}
		code?.let { return it }
		var styled = "§f"
		row.visit({ style: Style, text: String ->
			if (text.isBlank()) return@visit Optional.empty<Unit>()
			style.color?.let { colour -> ChatFormatting.entries.firstOrNull { TextColor.fromLegacyFormat(it) == colour }?.let { styled = it.toString() } }
			Optional.of(Unit)
		}, Style.EMPTY)
		return styled
	}

	/** A click in a visitor's menu: giving or refusing sends them away. */
	@JvmStatic
	fun onSlotClicked(screen: AbstractContainerScreen<*>, slotId: Int) {
		if (!module.enabled || visitors.isEmpty()) return
		val stack = screen.menu.slots.getOrNull(slotId)?.item ?: return
		val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "").trim() } ?: return
		if (lore.none { it == "Click to give!" || it == "Click to refuse!" }) return
		val name = screen.menu.slots.getOrNull(13)?.item?.hoverName?.string?.replace(FORMATTING, "")?.trim() ?: return
		visitors.remove(name)
	}

	/** One item every visitor wants together, and what you hold of it. */
	private class Wanted(val name: String, val amount: Int, val by: List<String>) {
		val id = BazaarMarket.idForName(name)
		val inventory = inventoryCount(name)
		val sacks = SackContents.count(name)
		val short: Int get() = (amount - inventory - sacks).coerceAtLeast(0)
		val shortWithoutSacks: Int get() = (amount - inventory).coerceAtLeast(0)

		/** One of it on the Bazaar, as the Prices setting asks, or null. */
		fun unitPrice(): Double? {
			val product = id?.let(BazaarMarket::product) ?: return null
			return when (listPrice.selected) {
				PRICE_INSTANT -> product.instantBuy
				PRICE_ORDER -> product.instantSell
				else -> null
			}?.takeIf { it > 0 }
		}
	}

	private fun wanted(): List<Wanted> {
		val amounts = LinkedHashMap<String, Int>()
		val by = HashMap<String, MutableList<String>>()
		for (visitor in visitors.values) for ((item, amount) in visitor.wants) {
			amounts.merge(item, amount, Int::plus)
			by.getOrPut(item) { ArrayList() } += visitor.name
		}
		return amounts.map { (item, amount) -> Wanted(item, amount, by[item].orEmpty()) }
	}

	private fun inventoryCount(name: String): Int {
		val inventory = client.player?.inventory ?: return 0
		return (0 until inventory.containerSize).sumOf { slot ->
			val stack = inventory.getItem(slot)
			if (!stack.isEmpty && stack.hoverName.string.replace(FORMATTING, "").trim().equals(name, ignoreCase = true)) stack.count else 0
		}
	}

	/**
	 * Amounts for the Bazaar's presets, when [product] is something a visitor wants: what's still
	 * short with your sacks counted, and without them. One button when they agree (nothing in sacks).
	 */
	fun presetsFor(product: String?): List<Pair<String, Int>> {
		if (!module.enabled || !visitorPresets.value || product == null) return emptyList()
		val items = wanted()
		// A name cut short by a menu title still names one item, if only one starts that way.
		val item = items.firstOrNull { it.name.equals(product, ignoreCase = true) }
			?: items.singleOrNull { product.length >= 10 && it.name.startsWith(product, ignoreCase = true) } ?: return emptyList()
		if (item.short == item.shortWithoutSacks) return if (item.short > 0) listOf("§aShort" to item.short) else emptyList()
		return buildList {
			if (item.short > 0) add("§aWith sacks" to item.short)
			add("§eWithout sacks" to item.shortWithoutSacks)
		}
	}

	private fun isBazaarMenu(screen: AbstractContainerScreen<*>): Boolean {
		val title = screen.title.string.replace(FORMATTING, "")
		return title.startsWith("Bazaar") || title.contains("➜") || title == "How many do you want?" || title == "How much do you want to pay?"
	}

	private fun isVisitorMenu(screen: AbstractContainerScreen<*>): Boolean =
		screen.menu.slots.getOrNull(13)?.item?.get(DataComponents.LORE)?.lines()?.any { it.string.contains("Times Visited") } == true

	// ---- The shopping list --------------------------------------------------------------------

	private const val NEAR = 16.0
	private const val PRICE_MAX_AGE_MILLIS = 120_000L

	/** Worked out a few times a second; drawing only reads it. */
	private var listLines: List<ListLine> = emptyList()
	private var listWidth = 0

	/**
	 * One row of the list. [item] makes it hoverable in menus; item rows leave room for an icon, and
	 * [trailing] icons follow the text (what a new visitor usually wants).
	 */
	private class ListLine(
		val prefix: String, val text: String, val item: Wanted? = null, val icon: ItemStack? = null, val hasIcon: Boolean = false,
		val trailing: List<ItemStack> = emptyList(),
	) {
		val prefixWidth = Minecraft.getInstance().font.width(prefix)
		val textWidth = Minecraft.getInstance().font.width(text)
		val width = prefixWidth + (if (hasIcon) ICON + 2 else 0) + textWidth + if (trailing.isEmpty()) 0 else 2 + trailing.size * (ICON + 1)
	}

	private const val ROW = 10
	private const val ICON = 10

	private fun tickVisitorList(client: Minecraft) {
		val shopping = if (visitors.isEmpty()) emptyList() else wanted()
		if (!visitorList.value) {
			nearVisitors = false
			nearBazaar = false
			listLines = emptyList()
			return
		}
		nearVisitors = inGarden && nearNamed(client, waiting.map { it.first } + visitors.keys)
		nearBazaar = SkyblockLocation.onSkyblock && SkyblockLocation.area == "Hub" &&
			(SkyblockLocation.zone()?.contains("Bazaar", ignoreCase = true) == true || nearNamed(client, listOf("Bazaar")))
		if (shopping.isNotEmpty() && listPrice.selected != PRICE_OFF) BazaarMarket.refresh(PRICE_MAX_AGE_MILLIS)
		learnIcons(client, shopping)
		listLines = buildLines(shopping)
		listWidth = listLines.maxOfOrNull { it.width } ?: 0
	}

	private fun buildLines(shopping: List<Wanted>): List<ListLine> {
		val lines = ArrayList<ListLine>()
		if (shopping.isNotEmpty()) {
			val prices = shopping.map { item -> item.unitPrice()?.let { it * item.amount } }
			val total = prices.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
			lines += ListLine("", "§fVisitor Shopping List:" + (total?.let { " §7(§6${compact(it)}§7)" } ?: ""))
			shopping.forEachIndexed { i, item ->
				val text = StringBuilder()
				text.append(ItemRarity.codeForName(item.name) ?: "§f").append(item.name).append(" §ex").append(item.amount)
				prices[i]?.let { text.append(" §7(§6").append(compact(it)).append("§7)") }
				if (item.sacks > 0) text.append(" §7(§a").append(String.format(Locale.US, "%,d", item.sacks)).append(" §7in sacks)")
				if (item.short == 0) text.append(" §a✔")
				lines += ListLine(" §7- ", text.toString(), item, iconFor(item.name), hasIcon = true)
			}
		}
		// Who's waiting alone is only worth showing in the Garden.
		if (!inGarden && shopping.isEmpty()) return lines
		if (listNewVisitors.value) {
			val unread = waiting.filter { (name, _) -> visitors.keys.none { namesMatch(name, it) } }
			section(lines, "New Visitor", unread, wants = true)
		}
		if (listVisitors.value) section(lines, "Visitor", visitors.values.map { it.name to it.colour }, wants = false)
		return lines
	}

	/** "6 New Visitors:" and a row per name, after a blank line; with [wants], what each usually asks for. */
	private fun section(lines: MutableList<ListLine>, heading: String, names: List<Pair<String, String>>, wants: Boolean) {
		if (names.isEmpty()) return
		if (lines.isNotEmpty()) lines += ListLine("", "")
		lines += ListLine("", "§e${names.size} §f$heading${if (names.size == 1) "" else "s"}:")
		for ((name, colour) in names) {
			val usual = if (wants) usuallyWants(name) else null
			lines += when {
				usual == null -> ListLine(" §7- ", colour + name)
				usual.isEmpty() -> ListLine(" §7- ", "$colour$name §f(Any)")
				else -> ListLine(" §7- ", colour + name, trailing = usual.mapNotNull(::iconFor))
			}
		}
	}

	/**
	 * What each visitor asks for, by lower-case name; empty for one who takes any crop. Facts from
	 * SkyHanni-REPO's Garden data (MIT).
	 */
	private val VISITOR_WANTS: Map<String, List<String>> by lazy {
		runCatching {
			Farming::class.java.getResourceAsStream("/assets/cryptic/garden/visitor_wants.json")!!.reader(Charsets.UTF_8).use { reader ->
				com.google.gson.JsonParser.parseReader(reader).asJsonObject.entrySet()
					.associate { (name, items) -> name.lowercase(Locale.ROOT) to items.asJsonArray.map { it.asString } }
			}
		}.getOrElse { emptyMap() }
	}

	private fun usuallyWants(name: String): List<String>? = VISITOR_WANTS[name.lowercase(Locale.ROOT)]

	/** 948k, 1.2M. */
	private fun compact(value: Double): String = when {
		value >= 1e9 -> String.format(Locale.US, "%.1fB", value / 1e9)
		value >= 1e6 -> String.format(Locale.US, "%.1fM", value / 1e6)
		value >= 1e3 -> String.format(Locale.US, "%.0fk", value / 1e3)
		else -> String.format(Locale.US, "%.0f", value)
	}.replace(".0", "")

	/** Whether an entity named one of [names] (an NPC's nametag) is within [NEAR] blocks. */
	private fun nearNamed(client: Minecraft, names: Collection<String>): Boolean {
		if (names.isEmpty()) return false
		val player = client.player ?: return false
		val level = client.level ?: return false
		for (entity in level.entitiesForRendering()) {
			if (entity === player || entity.distanceToSqr(player) > NEAR * NEAR) continue
			val name = entity.customName?.string?.replace(FORMATTING, "")?.trim() ?: continue
			if (names.any { it.equals(name, ignoreCase = true) }) return true
		}
		return false
	}

	/** Icons as seen in your inventory or a menu, by lower-case name; better than the items list's guess. */
	private val seenIcons = HashMap<String, ItemStack>()
	private val apiIcons = HashMap<String, ItemStack?>()

	private fun learnIcons(client: Minecraft, shopping: List<Wanted>) {
		val missing = shopping.map { it.name.lowercase(Locale.ROOT) }.filter { it !in seenIcons }
		if (missing.isEmpty()) return
		val menu = client.player?.containerMenu ?: return
		for (slot in menu.slots) {
			val stack = slot.item
			if (stack.isEmpty) continue
			val name = stack.hoverName.string.replace(FORMATTING, "").trim().lowercase(Locale.ROOT)
			if (name in missing) seenIcons[name] = stack.copyWithCount(1)
		}
	}

	/** An item's icon by its name: as seen in a menu, else from the items list. Null until it's known. */
	fun iconFor(name: String): ItemStack? {
		seenIcons[name.lowercase(Locale.ROOT)]?.let { return it }
		val id = BazaarMarket.idForName(name) ?: return null
		if (id in apiIcons) return apiIcons[id]
		val look = BazaarMarket.look(id) ?: return null
		return apiIcon(look, name).also { apiIcons[id] = it }
	}

	/**
	 * Pre-1.13 materials the items list still uses, by "MATERIAL:durability" or "MATERIAL", to their
	 * item now. Others keep their name.
	 */
	private val LEGACY_MATERIALS = mapOf(
		"CARROT_ITEM" to "carrot", "POTATO_ITEM" to "potato", "MELON" to "melon_slice", "NETHER_STALK" to "nether_wart",
		"SEEDS" to "wheat_seeds", "INK_SACK:0" to "ink_sac", "INK_SACK:1" to "red_dye", "INK_SACK:2" to "green_dye",
		"INK_SACK:3" to "cocoa_beans", "INK_SACK:4" to "lapis_lazuli", "INK_SACK:15" to "bone_meal",
		"HUGE_MUSHROOM_1" to "brown_mushroom_block", "HUGE_MUSHROOM_2" to "red_mushroom_block", "DOUBLE_PLANT:0" to "sunflower",
		"DOUBLE_PLANT:4" to "rose_bush", "RED_ROSE:0" to "poppy", "RED_ROSE:1" to "blue_orchid", "YELLOW_FLOWER" to "dandelion",
		"PORK" to "porkchop", "GRILLED_PORK" to "cooked_porkchop", "RAW_FISH:0" to "cod", "RAW_FISH:1" to "salmon",
		"RAW_FISH:2" to "tropical_fish", "RAW_FISH:3" to "pufferfish", "RAW_CHICKEN" to "chicken", "RAW_BEEF" to "beef",
		"WATER_LILY" to "lily_pad", "GOLD_RECORD" to "music_disc_13", "SULPHUR" to "gunpowder", "ENDER_STONE" to "end_stone",
		"LOG:0" to "oak_log", "WOOD:0" to "oak_planks", "MUSHROOM_SOUP" to "mushroom_stew", "SKULL_ITEM" to "player_head",
		"WEB" to "cobweb", "SNOW_BALL" to "snowball", "NETHER_BRICK_ITEM" to "nether_brick",
	)

	/**
	 * An icon from the items list: the model Hypixel's resource pack draws a newer item with, a head's
	 * skin, or the material.
	 */
	private fun apiIcon(look: BazaarMarket.Look, name: String): ItemStack? {
		val glint = look.glowing || name.startsWith("Enchanted ")
		look.itemModel?.let(Identifier::tryParse)?.let { model ->
			return ItemStack(Items.PAPER).apply {
				set(DataComponents.ITEM_MODEL, model)
				if (glint) set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
			}
		}
		look.skin?.let(SkyblockItem::textureHash)?.let { return SkyblockItem.head(it) }
		val material = LEGACY_MATERIALS["${look.material}:${look.durability}"] ?: LEGACY_MATERIALS[look.material] ?: look.material.lowercase(Locale.ROOT)
		val item = Identifier.tryParse(material)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.takeIf { it != Items.AIR } ?: return null
		return ItemStack(item).apply { if (glint) set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
	}

	/** Whether the list shows: with [menu] open (null outside menus), by the Show and Menus settings. */
	private fun shows(menu: AbstractContainerScreen<*>?): Boolean {
		if (!module.enabled || !visitorList.value || listLines.isEmpty()) return false
		when (listMenus.selected) {
			MENUS_ONLY -> if (menu == null) return false
			MENUS_NEVER -> if (menu != null) return false
		}
		return listWhere.isSelected(WHERE_GARDEN) && inGarden ||
			listWhere.isSelected(WHERE_VISITORS) && nearVisitors ||
			listWhere.isSelected(WHERE_BAZAAR) && nearBazaar ||
			menu != null && listWhere.isSelected(WHERE_BAZAAR_MENUS) && isBazaarMenu(menu) ||
			menu != null && listWhere.isSelected(WHERE_VISITOR_MENU) && inGarden && isVisitorMenu(menu)
	}

	private fun drawLines(context: GuiGraphicsExtractor, lines: List<ListLine>, width: Int, hovered: Int) {
		val font = client.font
		lines.forEachIndexed { i, line ->
			val y = i * ROW
			if (i == hovered) context.fill(-2, y, width + 2, y + ROW, 0x40FFFFFF)
			if (line.prefix.isNotEmpty()) context.text(font, line.prefix, 0, y + 1, -1)
			var x = line.prefixWidth
			if (line.hasIcon) {
				line.icon?.let { icon ->
					val pose = context.pose()
					pose.pushMatrix()
					pose.translate(x.toFloat(), y.toFloat())
					pose.scale(ICON / 16f, ICON / 16f)
					context.item(icon, 0, 0)
					pose.popMatrix()
				}
				x += ICON + 2
			}
			context.text(font, line.text, x, y + 1, -1)
			if (line.trailing.isNotEmpty()) {
				x += line.textWidth + 2
				val pose = context.pose()
				for (icon in line.trailing) {
					pose.pushMatrix()
					pose.translate(x.toFloat(), y.toFloat())
					pose.scale(ICON / 16f, ICON / 16f)
					context.item(icon, 0, 0)
					pose.popMatrix()
					x += ICON + 1
				}
			}
		}
	}

	private val sampleLines: List<ListLine> by lazy {
		fun glinting(item: net.minecraft.world.item.Item) = ItemStack(item).apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
		listOf(
			ListLine("", "§fVisitor Shopping List: §7(§6948k§7)"),
			ListLine(" §7- ", "§9Polished Pumpkin §ex1 §7(§6256k§7)", icon = glinting(Items.CARVED_PUMPKIN), hasIcon = true),
			ListLine(" §7- ", "§9Enchanted Baked Potato §ex5 §7(§6384k§7) §7(§a2 §7in sacks)", icon = glinting(Items.BAKED_POTATO), hasIcon = true),
			ListLine(" §7- ", "§9Enchanted Hay Bale §ex2 §7(§6307k§7)", icon = glinting(Items.HAY_BLOCK), hasIcon = true),
			ListLine("", ""),
			ListLine("", "§e2 §fNew Visitors:"),
			ListLine(" §7- ", "§aFarm Merchant §f(Any)"),
			ListLine(" §7- ", "§9Fann", trailing = listOf(ItemStack(Items.NETHER_WART), ItemStack(Items.RED_MUSHROOM_BLOCK))),
			ListLine("", ""),
			ListLine("", "§e1 §fVisitor:"),
			ListLine(" §7- ", "§9Chantelle"),
		)
	}

	private class ShoppingListElement : HudElement("visitor_shopping_list", "Visitor Shopping List", 0.02, 0.3) {
		private val lines: List<ListLine> get() = listLines.ifEmpty { sampleLines }

		override val width: Int get() = maxOf(lines.maxOf { it.width }, 60)
		override val height: Int get() = lines.size * ROW

		override fun showInEditor(): Boolean = module.enabled && visitorList.value

		// In a menu it's drawn over the menu by renderList instead, where it can be hovered and clicked.
		override fun isVisible(): Boolean = client.gui.screen() !is AbstractContainerScreen<*> && shows(null)

		override fun render(context: GuiGraphicsExtractor) {
			keepOnScreen(context)
			drawLines(context, listLines, listWidth, -1)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = drawLines(context, lines, width, -1)
	}

	private val listElement = ShoppingListElement()

	/** Where each item row was drawn in a menu, for clicks and hovering. */
	private class Row(val item: Wanted, val index: Int, val x: Float, val y: Float, val width: Float, val height: Float) {
		fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var rows: List<Row> = emptyList()

	/** The list over a menu, at its HUD place, kept on screen. */
	@JvmStatic
	fun renderList(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val chest = screen as? AbstractContainerScreen<*>
		if (chest == null || !shows(chest)) {
			rows = emptyList()
			return
		}
		val element = listElement
		val scale = element.scale.toFloat()
		val width = listWidth
		val height = listLines.size * ROW
		val left = Math.round(element.left(graphics.guiWidth()).coerceAtMost(graphics.guiWidth() - width * element.scale).coerceAtLeast(0.0)).toFloat()
		val top = Math.round(element.top(graphics.guiHeight()).coerceAtMost(graphics.guiHeight() - height * element.scale).coerceAtLeast(0.0)).toFloat()
		val placed = listLines.mapIndexedNotNull { i, line ->
			line.item?.let { Row(it, i, left, top + i * ROW * scale, width * scale, ROW * scale) }
		}
		rows = placed
		val hovered = placed.firstOrNull { it.contains(mouseX.toDouble(), mouseY.toDouble()) }

		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(left, top)
		pose.scale(scale, scale)
		drawLines(graphics, listLines, width, hovered?.index ?: -1)
		pose.popMatrix()

		hovered?.item?.let { item ->
			graphics.setComponentTooltipForNextFrame(client.font, listOf(
				Component.literal("${ItemRarity.codeForName(item.name) ?: "§f"}${item.name}"),
				Component.literal("§7Wanted by §f${item.by.joinToString("§7, §f")}"),
				Component.literal("§7Needed: §f${item.amount}"),
				Component.literal("§7Inventory: §f${item.inventory}  §7Sacks: §f${item.sacks}"),
				Component.literal("§7To buy: §e${item.short} §8(§7${item.shortWithoutSacks} keeping your sacks§8)"),
				Component.empty(),
				Component.literal("§eClick to open it on the Bazaar"),
			), mouseX, mouseY)
		}
	}

	/** A click on an item in the list opens it on the Bazaar. True takes it. */
	@JvmStatic
	fun handleMouseClick(mouseX: Double, mouseY: Double): Boolean {
		val row = rows.firstOrNull { it.contains(mouseX, mouseY) } ?: return false
		client.player?.connection?.sendCommand("bz ${row.item.name}")
		return true
	}

	// ---- Debug --------------------------------------------------------------------------------

	/** For /cryptic debug visitors: everything the list and the Bazaar presets go by. */
	fun describeVisitors(): List<String> {
		val player = client.player
		val out = ArrayList<String>()
		out += "Farming ${module.enabled}, shopping list ${visitorList.value}, Bazaar amounts ${visitorPresets.value}"
		out += "List shows: ${listWhere.selected}; ${listMenus.selected}; prices ${listPrice.selected}; HUD at %.2f, %.2f".format(Locale.US, listElement.x, listElement.y)
		out += "SkyBlock ${SkyblockLocation.onSkyblock}, area ${SkyblockLocation.area}, zone ${SkyblockLocation.zone()}, in the Garden $inGarden"
		out += "Near the visitors $nearVisitors, near the Bazaar $nearBazaar, showing outside menus ${shows(null)}, ${listLines.size} lines"
		out += "Tab list: ${waiting.size} waiting (${waiting.joinToString { it.first }})"
		client.connection?.let { connection ->
			val rows = connection.listedOnlinePlayers.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
				.map { it.tabListDisplayName?.string.orEmpty() }
			val at = rows.indexOfFirst { it.replace(FORMATTING, "").contains("Visitor", ignoreCase = true) }
			out += if (at < 0) "Tab rows: none mention visitors (${rows.size} rows)"
			else "Tab rows from $at: " + rows.drop(at).take(7).joinToString(" | ") { "\"${it.replace("§", "&")}\"" }
		}
		out += "Read visitors (${visitors.size}):"
		for (visitor in visitors.values) out += "  ${visitor.name}: ${visitor.wants.entries.joinToString { "${it.key} x${it.value}" }}"
		for (item in wanted()) {
			val product = item.id?.let(BazaarMarket::product)
			val icon = when {
				item.name.lowercase(Locale.ROOT) in seenIcons -> "seen"
				item.id != null && apiIcons[item.id] != null -> "items list"
				else -> "none (${item.id?.let(BazaarMarket::look)?.material ?: "no look"})"
			}
			out += "Item ${item.name} x${item.amount}: id ${item.id ?: "UNKNOWN"}, inventory ${item.inventory}, sacks ${item.sacks}, " +
				"short ${item.short} (${item.shortWithoutSacks} keeping sacks), buy ${product?.instantBuy?.let(::compact)}, order ${product?.instantSell?.let(::compact)}, " +
				"icon $icon, presets ${presetsFor(item.name).joinToString { "${it.first.replace(FORMATTING, "")} ${it.second}" }.ifEmpty { "none" }}"
		}
		if (player != null) {
			val named = client.level?.entitiesForRendering()?.filter { it !== player && it.customName != null && it.distanceToSqr(player) <= NEAR * NEAR }
				?.sortedBy { it.distanceToSqr(player) }?.take(12)
				?.joinToString { "${it.customName!!.string.replace(FORMATTING, "").trim()} (${"%.0f".format(Locale.US, it.distanceTo(player))}m)" }
			out += "Named entities within ${NEAR.toInt()} blocks: ${named?.ifEmpty { "none" }}"
		}
		out += BazaarPresets.describe()
		out += SackContents.describe()
		return out
	}

	// ---- Mousemat -----------------------------------------------------------------------------

	/** A mousemat asks for yaw on its first line and pitch on its last, saying so in between. */
	private fun mousematSign(screen: Any?): net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen? {
		if (!module.enabled || !mousematPresets.value) return null
		val sign = screen as? net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen ?: return null
		val lines = (sign as imicro.cryptic.mixin.SignEditScreenAccessor).`cryptic$messages`()
		return sign.takeIf { lines.getOrNull(1)?.contains("Set Yaw Above") == true && lines.getOrNull(2)?.contains("Set Pitch Below") == true }
	}

	private class AngleRow(val crop: Crop, val x: Float, val y: Float, val width: Float, val height: Float) {
		fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var angleRows: List<AngleRow> = emptyList()

	private fun angleText(crop: Crop): String {
		val (yaw, pitch) = crop.angles()
		return "§a${crop.name} §7- §e${number(yaw)}§7/§e${number(pitch)}"
	}

	/** The crops, right of the sign; shrunk when the screen is too small for them. */
	@JvmStatic
	fun renderMousemat(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (mousematSign(screen) == null) {
			angleRows = emptyList()
			return
		}
		val font = client.font
		val rowHeight = 18
		val texts = CROPS.map(::angleText)
		val width = 20 + texts.maxOf { font.width(it) }
		val height = rowHeight * CROPS.size
		val left = screen.width / 2f + 70
		val top = 40f
		val scale = minOf(1f, (screen.height - top - 4) / height, (screen.width - left - 4) / width).coerceAtLeast(0.4f)
		val rows = CROPS.mapIndexed { i, crop -> AngleRow(crop, left, top + i * rowHeight * scale, width * scale, rowHeight * scale) }
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(left, top)
		pose.scale(scale, scale)
		rows.forEachIndexed { i, row ->
			val y = i * rowHeight
			if (row.contains(mouseX.toDouble(), mouseY.toDouble())) graphics.fill(-2, y, width + 2, y + rowHeight, 0x40FFFFFF)
			graphics.item(net.minecraft.world.item.ItemStack(row.crop.icon), 0, y + 1)
			graphics.text(font, texts[i], 20, y + 5, -1)
		}
		pose.popMatrix()
		angleRows = rows
	}

	/** A click on a crop writes its yaw and pitch into the sign. True takes the click. */
	@JvmStatic
	fun onMousePressed(): Boolean {
		val sign = mousematSign(client.gui.screen()) ?: return false
		val mouseX = client.mouseHandler.getScaledXPos(client.window)
		val mouseY = client.mouseHandler.getScaledYPos(client.window)
		val row = angleRows.firstOrNull { it.contains(mouseX, mouseY) } ?: return false
		val accessor = sign as imicro.cryptic.mixin.SignEditScreenAccessor
		val lines = accessor.`cryptic$messages`()
		val (yaw, pitch) = row.crop.angles()
		lines[0] = number(yaw)
		lines[3] = number(pitch)
		// The cursor was on a line that may now be shorter.
		accessor.`cryptic$signField`()?.setCursorToEnd()
		client.soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f))
		return true
	}

	// ---- Vacuum solver --------------------------------------------------------------------------

	private val VACUUMS = setOf("SKYMART_VACUUM", "SKYMART_TURBO_VACUUM", "SKYMART_HYPER_VACUUM", "INFINI_VACUUM", "INFINI_VACUUM_HOOVERIUS")

	/** When a vacuum was last in your hand; its trail is only read just after. */
	private var vacuumHeldAt = 0L

	/** A trail being read, one per particle kind Hypixel may draw it with. */
	private val trails = arrayOf(ParticleTrail(), ParticleTrail())

	/** Where the last trail leads, and when it was found. */
	private var guess: Vec3? = null
	private var guessAt = 0L

	private const val HELD_MILLIS = 1_500L
	private const val GUESS_MILLIS = 60_000L
	private const val ARRIVED = 6.0
	private const val MAX_GUESS_DISTANCE = 300.0

	/**
	 * Which trail a particle belongs to: Hypixel's vacuum trail has been drawn with angry villager
	 * particles and with enchanting ones; both are followed. -1 for neither.
	 */
	private fun trailKind(packet: ClientboundLevelParticlesPacket): Int {
		val still = packet.xDist == 0f && packet.yDist == 0f && packet.zDist == 0f
		if (!still) return -1
		return when {
			packet.particle.type == ParticleTypes.ANGRY_VILLAGER && packet.count == 1 && packet.maxSpeed == 0f -> 0
			packet.particle.type == ParticleTypes.ENCHANT && packet.count == 10 && packet.maxSpeed == -2f -> 1
			else -> -1
		}
	}

	/** A particle; on the client thread only (the network thread's call is skipped). */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (!client.isSameThread || !module.enabled || !vacuumSolver.value || !inGarden) return
		val now = System.currentTimeMillis()
		if (now - vacuumHeldAt > HELD_MILLIS) return
		val kind = trailKind(packet)
		if (kind < 0) return
		val player = client.player ?: return
		// The trail aims at the pest's middle, half a block above where it stands.
		val end = trails[kind].offer(Vec3(packet.x, packet.y, packet.z), player.eyePosition, now)?.add(0.0, -0.5, 0.0) ?: return
		if (end.distanceTo(player.position()) > MAX_GUESS_DISTANCE) return
		guess = end
		guessAt = now
	}

	private fun tickGuess() {
		val at = guess ?: return
		val player = client.player ?: return
		val flat = Vec3(at.x - player.x, 0.0, at.z - player.z).length()
		if (flat < ARRIVED || System.currentTimeMillis() - guessAt > GUESS_MILLIS) clearGuess()
	}

	private fun clearGuess() {
		guess = null
		trails.forEach { it.clear() }
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val text = message.string.replace(FORMATTING, "").trim()
		if (text == "There are not any Pests on your Garden right now! Keep farming!") clearGuess()
		// A pest spawning mid-hunt is the same hunt: your spot is already saved.
		if (autoSetspawn.value && inGarden && huntingSince == 0L && onCropPlot() && PEST_SPAWNED.containsMatchIn(text)) {
			val now = System.currentTimeMillis()
			huntingSince = now
			setspawnAt = now + SETSPAWN_DELAY_MILLIS
			pestsSeen = false
		}
	}

	// ---- Pests --------------------------------------------------------------------------------

	/**
	 * The pests' head textures, by the hash in each skin's address. Facts about Hypixel's pests,
	 * as Skyblocker lists them.
	 */
	private val PEST_TEXTURES = setOf(
		"70a1e836bf1968b2eaa4837227a19204f17295d870ee9e754bd6b6d60ddbed3c", // beetle
		"a24c69f96ce556221e195c8ef2bfad71ebf7f95f5ae914a484a8d0ec21672674", // cricket
		"254aff4c0b2dce3a672349cc0ee9e6f3a9deebe4b3556e84611eca250a7821bf", // dragonfly
		"6403ba4027a333d8d2fd32ab59d1cfdbaa7d908d80d2381db2a69cbe65450ad8", // earthworm
		"fd40aa50905235b628e7379eb31fa45cd41b503f09721db3c437fcee39207dfc", // earthworm's tail
		"f379e09252817314bd0b694f7d53b48af2c7fa8499109802a41bb294d2f93e3e", // field mouse
		"4ce79e90adf34718f313ec24d6c6135b69b3788c618498446ccc83ca640c0b14", // firefly
		"3e52782d7f2aaee8af5ba2928fec7885e94879334c2296bc9e7e2dbc5418e58f", // firefly, lit
		"9d90e777826a52461368e26d1b2e19bfa1ba582d602483e545f4124d0f731842", // fly
		"4b24a482a32db1ea78fb98060b0c2fa4a373cbd18a68edddeb7419455a59cda9", // locust
		"bee4fcc5aac27bdbb0be210df08b05bca5aebc89bf2821f608a55fd2cf0434be", // lunar moth
		"be6baf6431a9daa2ca604d5a3c26e9a761d5952f0817174a4fe0b764616e21ff", // mite
		"52a9fe05bc663efcd12e56a3ccc5ec035bf577b78708548b6f4ffcf1d30eccfe", // mosquito
		"65485c4b34e5b5470be94de100e61f7816f81bc5a11dfdf0eccf890172da5d0a", // moth
		"1e04bb6367caa4e88f5fd0ee80f0745d137a6060223dbbc42a16471fdf64bb83", // praying mantis
		"a8abb471db0ab78703011979dc8b40798a941f3a4dec3ec61cbeec2af8cffe8", // rat
		"7a79d0fd677b54530961117ef84adc206e2cc5045c1344d61d776bf8ac2fe1ba", // slug
	)

	private val TEXTURE_HASH = Regex("""texture/([0-9a-f]+)""")

	/** Whether a head texture is a pest's, by the base64 Hypixel sends; remembered per texture. */
	private val pestTexture = HashMap<String, Boolean>()

	private var pests: List<Vec3> = emptyList()

	private fun findPests(): List<Vec3> {
		val level = client.level ?: return emptyList()
		return level.entitiesForRendering().mapNotNull { entity ->
			val stand = entity as? ArmorStand ?: return@mapNotNull null
			val texture = SkyblockItem.skullTexture(stand.getItemBySlot(EquipmentSlot.HEAD)) ?: return@mapNotNull null
			val pest = pestTexture.getOrPut(texture) {
				runCatching { String(Base64.getDecoder().decode(texture)) }.getOrNull()
					?.let { TEXTURE_HASH.find(it)?.groupValues?.get(1) } in PEST_TEXTURES
			}
			if (pest) stand.eyePosition else null
		}
	}

	// ---- Pest cooldown ------------------------------------------------------------------------

	private enum class PestTimer { UNKNOWN, RUNNING, READY, MAX }

	/** The tab list's Pests widget, as Hypixel writes it. */
	private val COOLDOWN_ROW = Regex("""^Cooldown: (?:(READY)|(MAX PESTS)|(?:(\d+)m)? ?(?:(\d+)s)?)""")
	private val ALIVE_ROW = Regex("""^Alive: (\d+)""")

	/** "GROSS! A Pest has appeared in Plot - 9!", or "2 Pests have spawned in"; not the offline summary. */
	private val PEST_SPAWNED = Regex("""\bPests? ha(?:s|ve) (?:appeared|spawned) in Plot""")

	private const val WIDGET_TICKS = 10

	/** The widget's seconds tick unevenly, so the count only follows it when it's this far out. */
	private const val COOLDOWN_DRIFT_MILLIS = 2_000L

	/** Only a cooldown longer than this is a new one, rather than the last's final seconds. */
	private const val NEW_COOLDOWN_SECONDS = 5L
	private const val SETSPAWN_DELAY_MILLIS = 1_000L

	/** Given up after this, for when the widget is off and the last pest can't be told. */
	private const val HUNT_MILLIS = 15 * 60 * 1000L

	private var pestTimer = PestTimer.UNKNOWN
	private var cooldownEnds = 0L
	private var cooldownToasted = true
	private var pestsAlive: Int? = null

	private var setspawnAt = 0L
	private var huntingSince = 0L
	private var pestsSeen = false

	private fun readPestWidget(now: Long) {
		var cooldown: MatchResult? = null
		var alive: Int? = null
		for (info in client.connection?.onlinePlayers.orEmpty()) {
			val row = info.tabListDisplayName?.string?.replace(FORMATTING, "")?.trim() ?: continue
			COOLDOWN_ROW.find(row)?.let { cooldown = it }
			ALIVE_ROW.find(row)?.let { alive = it.groupValues[1].toInt() }
		}
		pestsAlive = alive
		val row = cooldown
		if (row == null) {
			pestTimer = PestTimer.UNKNOWN
			return
		}
		val (ready, max, minutes, seconds) = row.destructured
		when {
			ready.isNotEmpty() -> pestTimer = PestTimer.READY
			max.isNotEmpty() -> {
				pestTimer = PestTimer.MAX
				cooldownToasted = true
			}
			minutes.isNotEmpty() || seconds.isNotEmpty() -> {
				val left = minutes.ifEmpty { "0" }.toLong() * 60 + seconds.ifEmpty { "0" }.toLong()
				val ends = now + left * 1000
				if (pestTimer != PestTimer.RUNNING || kotlin.math.abs(ends - cooldownEnds) > COOLDOWN_DRIFT_MILLIS) cooldownEnds = ends
				pestTimer = PestTimer.RUNNING
				if (left > NEW_COOLDOWN_SECONDS) cooldownToasted = false
			}
		}
	}

	private fun tickPests() {
		if (!pestCooldown.value && !autoSetspawn.value) return
		val now = System.currentTimeMillis()
		if (inGarden) {
			if (ticks % WIDGET_TICKS == 0) readPestWidget(now)
		} else {
			setspawnAt = 0L
			huntingSince = 0L
		}

		val over = pestTimer == PestTimer.READY || (pestTimer == PestTimer.RUNNING && now >= cooldownEnds)
		if (over && !cooldownToasted) {
			cooldownToasted = true
			if (pestCooldown.value) Toasts.show("Pests", "Pest cooldown is over")
		}

		if (!autoSetspawn.value) {
			setspawnAt = 0L
			huntingSince = 0L
			return
		}
		if (setspawnAt != 0L && now >= setspawnAt) {
			setspawnAt = 0L
			// Walked off the plot in that second: nothing to come back to.
			if (onCropPlot()) command("setspawn") else huntingSince = 0L
		}
		if (huntingSince == 0L) return
		val alive = pestsAlive
		if (alive != null && alive > 0) pestsSeen = true
		when {
			now - huntingSince > HUNT_MILLIS -> huntingSince = 0L
			pestsSeen && alive == 0 -> {
				huntingSince = 0L
				command("warp garden")
			}
		}
	}

	/**
	 * On a numbered plot ("Plot - 4" in the sidebar), where you farm. The barn's plot reads "The
	 * Garden" and the greenhouse has its own name, so neither counts.
	 */
	private fun onCropPlot(): Boolean = SkyblockLocation.zone()?.let { it.startsWith("Plot") && !it.contains("Greenhouse", ignoreCase = true) } == true

	private fun command(line: String) {
		if (DebugOverrides.previewPartyCommands) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Would send: §f/$line"))
			return
		}
		client.connection?.sendCommand(line)
	}

	private fun cooldownText(): String {
		val left = (cooldownEnds - System.currentTimeMillis()) / 1000
		return when {
			pestTimer == PestTimer.UNKNOWN -> "§cNo Pests widget"
			pestTimer == PestTimer.MAX -> "§cMax pests"
			pestTimer == PestTimer.READY || left <= 0 -> "§aReady"
			left >= 60 -> "§e${left / 60}m ${left % 60}s"
			else -> "§e${left}s"
		}
	}

	private const val COOLDOWN_LABEL = "§6Pest cooldown: "

	private class PestCooldownElement : HudElement("pest_cooldown", "Pest Cooldown", 0.02, 0.42) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(COOLDOWN_LABEL + "§e2m 59s")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = showInEditor() && inGarden

		override fun showInEditor(): Boolean = module.enabled && pestCooldown.value && pestCooldownHud.value

		override fun render(context: GuiGraphicsExtractor) {
			context.text(font, COOLDOWN_LABEL + cooldownText(), 0, 0, -1)
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			context.text(font, COOLDOWN_LABEL + "§e2m 59s", 0, 0, -1)
		}
	}

	// ---- Drawing ------------------------------------------------------------------------------

	private const val PEST_COLOR = 0xFFB62F00.toInt()
	private const val GUESS_COLOR = 0xFFFF5555.toInt()
	private const val BEAM_HEIGHT = 40.0

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !inGarden) return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		for (pest in pests) {
			WorldRender.drawBox(
				poseStack, collector, pest.x - 0.35, pest.y - 0.35, pest.z - 0.35, pest.x + 0.35, pest.y + 0.35, pest.z + 0.35,
				outlineArgb = PEST_COLOR, fillArgb = 0x40000000 or (PEST_COLOR and 0xFFFFFF), outline = true, fill = true, phase = true, lineWidth = 2f,
			)
			WorldRender.drawTracer(poseStack, collector, pest.x, pest.y, pest.z, PEST_COLOR, 2f, phase = true)
		}
		val at = guess ?: return
		val player = client.player ?: return
		WorldRender.drawBox(
			poseStack, collector, at.x - 0.15, at.y, at.z - 0.15, at.x + 0.15, at.y + BEAM_HEIGHT, at.z + 0.15,
			outlineArgb = 0, fillArgb = 0x60000000 or (GUESS_COLOR and 0xFFFFFF), outline = false, fill = true, phase = true, lineWidth = 1f,
		)
		WorldRender.drawBox(
			poseStack, collector, at.x - 0.5, at.y - 0.5, at.z - 0.5, at.x + 0.5, at.y + 0.5, at.z + 0.5,
			outlineArgb = GUESS_COLOR, fillArgb = 0x30000000 or (GUESS_COLOR and 0xFFFFFF), outline = true, fill = true, phase = true, lineWidth = 2f,
		)
		if (vacuumTracer.value) WorldRender.drawTracer(poseStack, collector, at.x, at.y, at.z, GUESS_COLOR, 2f, phase = true)
		val distance = player.eyePosition.distanceTo(at)
		WorldRender.drawText(
			poseStack = poseStack,
			collector = collector,
			orientation = client.gameRenderer.mainCamera().rotation(),
			text = Component.literal("§cPest §e${String.format(Locale.US, "%.0f", distance)}m"),
			x = at.x, y = at.y + 1.2, z = at.z,
			scale = (distance / 12.0).coerceIn(1.0, 6.0).toFloat(),
			seeThrough = true,
			centered = true,
		)
	}

	fun describe(): List<String> = listOf(
		"Farming: in the Garden ${inGarden}, ${visitors.size} visitors (${visitors.keys.joinToString()})",
		"Vacuum: guess ${guess?.let { "%.1f %.1f %.1f".format(it.x, it.y, it.z) } ?: "none"}, trails ${trails.joinToString { it.size.toString() }}",
		"Pests in sight: ${pests.size}",
		SackContents.describe(),
	)
}
