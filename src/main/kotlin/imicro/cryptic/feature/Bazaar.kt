package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.mixin.ContainerScreenAccessor
import net.minecraft.client.gui.screens.Screen
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.ItemRarity
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.nio.file.Files
import java.util.Locale

/**
 * The Bazaar: your orders tracked against the market (top, matched, or undercut), shown in the
 * orders menu and on a HUD, with a notification when one is undercut or filled; and Hypixel's "Submitting..." lines kept out of chat. Orders are read from the
 * Bazaar Orders menu whenever it's open and from Hypixel's order messages in between. A baseline
 * after BtrBz.
 */
object Bazaar {
	/** BtrBz's colours; [code] is the nearest chat colour, for notifications. */
	enum class Status(val label: String, val color: Int, val code: String) {
		TOP("Top order", 0x55FF55, "§a"),
		MATCHED("Matched", 0x5599FF, "§9"),
		UNDERCUT("Undercut", 0xFF5555, "§c"),
		FILLED("Filled", 0xFFC857, "§6"),
		EXPIRED("Expired", 0xAAAAAA, "§7"),
		UNKNOWN("No market data", 0xBB77FF, "§d"),
	}

	/** One of your orders. [sell] is a sell offer; otherwise a buy order. Kept by Gson, so all defaulted. */
	data class Order(
		val name: String = "",
		val sell: Boolean = false,
		val amount: Int = 0,
		val price: Double = 0.0,
		var filled: Int = 0,
		var expired: Boolean = false,
		/** Who placed it, as Co-op Bazaar Orders says; empty when the menu doesn't. */
		val owner: String = "",
	) {
		val kind: String get() = if (sell) "SELL" else "BUY"

		/** Placed by someone else in your co-op. */
		val coopMember: Boolean
			get() = owner.isNotEmpty() && !owner.equals(Minecraft.getInstance().player?.gameProfile?.name, ignoreCase = true)
	}

	private val ordersSection = SectionModuleSetting("orders_section", "Orders")

	@JvmField
	val menuStatus = ToggleModuleSetting(
		"menu_status", "Status in the orders menu", true,
		"Tints each order in Bazaar Orders by where it stands (top, matched, undercut, filled) and says so in its tooltip.",
	)

	@JvmField
	val breakdown = ToggleModuleSetting(
		"breakdown", "Orders breakdown", true,
		"Beside Bazaar Orders, each order's worth and the totals: what your sell offers bring in once they all fill (after the sell tax), and what your buy orders cost.",
	)

	@JvmField
	val ordersHud = ToggleModuleSetting("orders_hud", "Orders HUD", true, "Your orders and where they stand, on the HUD outside the Bazaar.")

	@JvmField
	val undercutAlert = ToggleModuleSetting(
		"undercut_alert", "Notify when undercut", true,
		"A notification when someone beats one of your orders, or matches it.",
	)

	@JvmField
	val filledAlert = ToggleModuleSetting("filled_alert", "Notify when filled", true, "A notification when one of your orders fills.")

	@JvmField
	val coopAlerts = ToggleModuleSetting(
		"coop_alerts", "Co-op members' orders", true,
		"Also notifies for orders your co-op members placed: when theirs fill, and when theirs are undercut.",
	)

	@JvmField
	val sounds = ToggleModuleSetting(
		"sounds", "Sounds", true,
		"BtrBz's sounds with the notifications: a chime when an order is back on top or matched, two quick pings when it's undercut.",
	)

	@JvmField
	val reopen = ToggleModuleSetting(
		"reopen", "Reopen cancelled orders", true,
		"After you cancel an order, Bazaar Orders shows it as an item: click it to open that product's page and relist. " +
			"Hold Ctrl while cancelling to copy the amount that hadn't filled.",
	)

	@JvmField
	val presetAmounts = TextModuleSetting(
		id = "preset_amounts",
		label = "Amounts",
		defaultValue = "64, 160, 640, 1024, 2240",
		hint = "64, 640, 2240",
		description = "The amounts offered, separated by commas.",
	)

	@JvmField
	val presets = ToggleModuleSetting(
		"presets", "Amount presets", true,
		"Buttons beside a buy order's \"How many do you want?\" and an instant buy's menu that fill in Custom Amount for you: your amounts, the most " +
			"your purse allows, and when relisting a cancelled order with the reopen item, what of it hadn't filled.",
	).withMore(presetAmounts)

	@JvmField
	val quietInDungeons = ToggleModuleSetting("quiet_in_dungeons", "Not in dungeons", true, "No Bazaar notifications while you're in a dungeon.")

	@JvmField
	val limit = SliderModuleSetting(
		id = "daily_limit_amount",
		label = "Limit",
		unit = "B",
		defaultValue = 15.0,
		min = 1.0,
		max = 50.0,
		step = 0.5,
		description = "Your daily limit, in billions of coins.",
	)

	@JvmField
	val tax = SliderModuleSetting(
		id = "tax",
		label = "Sell tax",
		unit = "%",
		defaultValue = 1.25,
		min = 0.0,
		max = 2.0,
		step = 0.125,
		description = "Taken off what instant sells count towards the limit, and off sell offers in the orders breakdown.",
	)

	@JvmField
	val dailyLimit = ToggleModuleSetting(
		"daily_limit", "Daily limit", true,
		"Above Bazaar menus, how much of today's trading limit you've used: instant buys, instant sells and orders you've set up, since midnight UTC.",
	).withMore(limit, tax)

	private val marketSection = SectionModuleSetting("market_section", "Market")

	/** quoi's idea. */
	@JvmField
	val escrowFix = ToggleModuleSetting(
		"escrow_fix", "Escrow fix", true,
		"When an instant buy fails and Hypixel closes the Bazaar refunding the escrow, it opens again with /bz.",
	)

	@JvmField
	val hideProgress = ToggleModuleSetting(
		"hide_progress", "Hide progress messages", true,
		"Keeps \"[Bazaar] Submitting buy order...\" and the like out of chat.",
	)

	@JvmField
	val module = Module(
		id = "bazaar",
		name = "Bazaar",
		description = "Bazaar orders and prices",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(ordersSection, menuStatus, breakdown, ordersHud, undercutAlert, filledAlert, coopAlerts, sounds, quietInDungeons, reopen, presets, marketSection, dailyLimit, escrowFix, hideProgress),
	)

	private val client: Minecraft get() = Minecraft.getInstance()
	private val FORMATTING = Regex("§.")

	private var orders: MutableList<Order> = ArrayList()
	/** By [key], so the same order read again from the menu keeps its standing. */
	private val statuses = HashMap<String, Status>()

	private fun key(order: Order) = "${order.sell}|${order.name}|${order.price}|${order.amount}"
	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("bazaar-orders.json")

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
		loadDaily()
		BazaarMarket.onUpdate = ::updateStatuses
		Hud.register(OrdersElement())
		ItemTooltipCallback.EVENT.register { stack, _, _, lines -> addTooltip(stack, lines) }
	}

	val needsSkyblock: Boolean get() = module.enabled

	private fun inOrdersMenu(screen: Any?): Boolean {
		val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: return false
		return title == "Your Bazaar Orders" || title == "Co-op Bazaar Orders"
	}

	private fun inBazaar(screen: Any?): Boolean {
		val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: return false
		return title.startsWith("Bazaar") || inOrdersMenu(screen) || title.contains("➜")
	}

	private var stamp = 0
	private var stableTicks = 0

	fun tick(client: Minecraft) {
		if (!module.enabled || !SkyblockLocation.onSkyblock) return
		val screen = client.gui.screen()
		// Fresh while trading or with orders out; otherwise only now and then, for tooltips.
		playDueSounds()
		forgetReopenOnLeave(screen)
		val trading = inBazaar(screen)
		val busy = trading || orders.any { !it.expired && it.filled < it.amount }
		BazaarMarket.refresh(if (trading) 5_000L else if (busy) 20_000L else 120_000L)
		if (inOrdersMenu(screen)) readMenu(screen as AbstractContainerScreen<*>)
		(screen as? AbstractContainerScreen<*>)?.let(::readProductPage)
	}

	/** "- 9,798,500.1 coins each | 41x in 9 orders" or "- 1 coin each | 1x from 1 offer". */
	private val bookLine = Regex("""^- ([\d,.]+) coins? each \| ([\d,]+)x (?:in|from) ([\d,]+) (?:orders?|offers?)""")
	private var pageStamp = 0

	/**
	 * A product's page shows the top of its book, live, on its Create Buy Order and Create Sell
	 * Offer buttons. Read whenever it changes, so your orders' standing doesn't wait for the API.
	 */
	private fun readProductPage(screen: AbstractContainerScreen<*>) {
		val slots = screen.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }
		val buy = slots.firstOrNull { it.item.hoverName.string.replace(FORMATTING, "").trim() == "Create Buy Order" } ?: return
		val sell = slots.firstOrNull { it.item.hoverName.string.replace(FORMATTING, "").trim() == "Create Sell Offer" }
		val stamp = slots.sumOf { (it.item.hoverName.string.hashCode() * 31 + (it.item.get(DataComponents.LORE)?.lines()?.hashCode() ?: 0)) * (it.index + 1) }
		if (stamp == pageStamp) return
		pageStamp = stamp
		val name = screen.title.string.replace(FORMATTING, "").substringAfterLast("➜").trim()
		lastProduct = name
		val id = BazaarMarket.idForName(name) ?: return
		fun levels(stack: ItemStack?) = stack?.get(DataComponents.LORE)?.lines()?.mapNotNull { line ->
			bookLine.find(line.string.replace(FORMATTING, "").trim())?.let { match ->
				BazaarMarket.Level(
					match.groupValues[2].replace(",", "").toLong(),
					match.groupValues[1].replace(",", "").toDouble(),
					match.groupValues[3].replace(",", "").toInt(),
				)
			}
		}?.takeIf { it.isNotEmpty() }
		BazaarMarket.update(id, sellOffers = levels(sell?.item), buyOrders = levels(buy.item))
		updateStatuses()
	}

	// ---- Reading orders --------------------------------------------------------------------

	/** "BUY Enchanted Diamond" or "SELL Heat Core". */
	private val orderTitle = Regex("""^(BUY|SELL) (.+)$""")
	private val amountLine = Regex("""^(?:Order|Offer) amount: ([\d,]+)x""")
	private val filledLine = Regex("""^Filled: ([\d,.]+[kKmM]?)/""")
	/** "By: [MVP+] Name", on a co-op's orders. */
	private val ownerLine = Regex("""^By: (?:\[[^]]+] )?(\S+)""")
	private val priceLine = Regex("""^Price per unit: ([\d,.]+) coins""")

	private fun readMenu(screen: AbstractContainerScreen<*>) {
		val slots = screen.menu.slots.filter { it.container !is Inventory }
		val now = slots.sumOf { (it.item.hoverName.string.hashCode() * 31 + it.item.count) * (it.index + 1) }
		if (now != stamp) {
			stamp = now
			stableTicks = 0
			return
		}
		if (++stableTicks != 3) return
		val read = slots.mapNotNull { parseOrder(it.item) }
		// The menu is the whole truth about your orders; chat only fills in between visits.
		orders = read.toMutableList()
		updateStatuses()
		save()
	}

	private fun parseOrder(stack: ItemStack): Order? {
		if (stack.isEmpty) return null
		val title = orderTitle.find(stack.hoverName.string.replace(FORMATTING, "").trim()) ?: return null
		val lines = stack.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "").trim() } ?: return null
		val amount = lines.firstNotNullOfOrNull { amountLine.find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() } ?: return null
		val price = lines.firstNotNullOfOrNull { priceLine.find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() } ?: return null
		val filled = when {
			lines.any { it.startsWith("Filled:") && it.endsWith("100%!") } -> amount
			else -> lines.firstNotNullOfOrNull { filledLine.find(it)?.groupValues?.get(1) }?.let(::parseCompact) ?: 0
		}
		val owner = lines.firstNotNullOfOrNull { ownerLine.find(it)?.groupValues?.get(1) } ?: ""
		return Order(title.groupValues[2], title.groupValues[1] == "SELL", amount, price, filled, lines.any { it == "Expired!" }, owner)
	}

	/** "1,234", "12.5k", "3M". */
	private fun parseCompact(text: String): Int {
		val number = text.dropLastWhile { it.isLetter() }.replace(",", "").toDoubleOrNull() ?: return 0
		val scale = when (text.lastOrNull()?.lowercaseChar()) {
			'k' -> 1_000.0
			'm' -> 1_000_000.0
			else -> 1.0
		}
		return (number * scale).toInt()
	}

	// ---- Chat ------------------------------------------------------------------------------

	private val setUp = Regex("""^\[Bazaar] (Buy Order|Sell Offer) Setup! ([\d,]+)x (.+) for ([\d,.]+) coins\.""")
	private val instant = Regex("""^\[Bazaar] (Bought|Sold) [\d,]+x .+ for ([\d,.]+) coins!""")
	/** Yours or a co-op member's: "Your co-op Sell Offer for 1x Plant Matter was filled!". */
	private val filledMessage = Regex("""^\[Bazaar] Your (co-op )?(Buy Order|Sell Offer) for ([\d,]+)x (.+) was filled!""")
	private val progress = setOf(
		"[Bazaar] Cancelling order...", "[Bazaar] Putting goods in escrow...", "[Bazaar] Submitting buy order...",
		"[Bazaar] Claiming order...", "[Bazaar] Submitting sell offer...", "[Bazaar] Executing instant sell...",
		"[Bazaar] Executing instant buy...", "[Bazaar] Claiming orders...",
	)

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val line = message.string.replace(FORMATTING, "").trim()
		if (escrowFix.value && ESCROW.matches(line) && SkyblockLocation.onSkyblock) {
			val now = System.currentTimeMillis()
			// One reopen for a burst of refunds.
			if (now - escrowReopenedAt > ESCROW_COOLDOWN_MILLIS) {
				escrowReopenedAt = now
				client.execute { client.connection?.sendCommand("bz") }
			}
			return
		}
		if (!line.startsWith("[Bazaar]")) return
		instant.find(line)?.let { match ->
			val total = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return
			client.execute { spend(if (match.groupValues[1] == "Sold") total * (1 - tax.value / 100) else total) }
			return
		}
		setUp.find(line)?.let { match ->
			val amount = match.groupValues[2].replace(",", "").toIntOrNull() ?: return
			val total = match.groupValues[4].replace(",", "").toDoubleOrNull() ?: return
			client.execute {
				spend(total)
				orders += Order(match.groupValues[3], match.groupValues[1] == "Sell Offer", amount, total / amount)
				updateStatuses()
				save()
			}
			return
		}
		filledMessage.find(line)?.let { match ->
			val coop = match.groupValues[1].isNotEmpty()
			val sell = match.groupValues[2] == "Sell Offer"
			val amount = match.groupValues[3].replace(",", "").toIntOrNull() ?: return
			val name = match.groupValues[4]
			// Hypixel writes the name in its rarity's colour; kept for this item's other notifications.
			Regex("""x((?:§.)*) ?""" + Regex.escape(name)).find(message.string)?.groupValues?.get(1)
				?.let { codes -> Regex("§[0-9a-f]").findAll(codes).lastOrNull()?.value }
				?.let { nameCodes[name] = it }
			client.execute {
				orders.firstOrNull { it.sell == sell && it.name == name && it.amount == amount && it.filled < it.amount }?.filled = amount
				updateStatuses()
				save()
				if (filledAlert.value && (!coop || coopAlerts.value)) {
					val whose = if (coop) "Your co-op's" else "Your"
					notify("§7$whose ${if (sell) "sell offer" else "buy order"} for §f${amount}x ${named(name)} §7was ${Status.FILLED.code}filled§7.")
				}
			}
		}
	}

	/** Hypixel's progress lines, kept out of chat. */
	@JvmStatic
	fun hidesChat(message: Component, overlay: Boolean): Boolean {
		if (overlay || !module.enabled || !hideProgress.value) return false
		return message.string.replace(FORMATTING, "").trim() in progress
	}

	// ---- Where orders stand ----------------------------------------------------------------

	private fun statusOf(order: Order): Status {
		if (order.filled >= order.amount) return Status.FILLED
		if (order.expired) return Status.EXPIRED
		val id = BazaarMarket.idForName(order.name) ?: return Status.UNKNOWN
		val product = BazaarMarket.product(id) ?: return Status.UNKNOWN
		val book = if (order.sell) product.sellOffers else product.buyOrders
		val best = book.firstOrNull() ?: return Status.TOP
		// Prices in the book are to a tenth of a coin.
		val diff = order.price - best.price
		val ahead = if (order.sell) diff < -0.05 else diff > 0.05
		val level = kotlin.math.abs(diff) <= 0.05
		return when {
			ahead -> Status.TOP
			level && best.orders <= 1 -> Status.TOP
			level -> Status.MATCHED
			else -> Status.UNDERCUT
		}
	}

	private fun updateStatuses() {
		val before = HashMap(statuses)
		statuses.clear()
		for (order in orders) {
			val now = statusOf(order)
			statuses[key(order)] = now
			val was = before[key(order)]
			val beaten = now == Status.UNDERCUT && (was == Status.TOP || was == Status.MATCHED)
			val matched = now == Status.MATCHED && was == Status.TOP
			val backOnTop = now == Status.TOP && (was == Status.MATCHED || was == Status.UNDERCUT)
			if (undercutAlert.value && (beaten || matched || backOnTop) && (coopAlerts.value || !order.coopMember)) {
				val best = BazaarMarket.idForName(order.name)?.let(BazaarMarket::product)?.let { if (order.sell) it.sellOffers else it.buyOrders }?.firstOrNull()
				val whose = if (order.coopMember) "§f${order.owner}§7's" else "Your"
				val what = "§7$whose ${if (order.sell) "sell offer" else "buy order"} for ${named(order.name)} §7"
				if (backOnTop) {
					notify(what + "is ${now.code}back on top§7.")
				} else {
					notify(
						what + "was ${now.code}${if (beaten) "undercut" else "matched"}" +
							(best?.let { " §8(§e${coins(it.price)}§8)" } ?: "") + "§7.",
					)
				}
				// BtrBz's sounds: a chime for good news, two pings for being beaten.
				if (beaten) playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 2)
				else playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), 1)
			}
		}
	}

	/** Sounds still to come, by the tick they're due. */
	private val pendingSounds = ArrayList<Pair<Long, net.minecraft.sounds.SoundEvent>>()
	private var ticks = 0L

	private fun playSound(sound: net.minecraft.sounds.SoundEvent, times: Int) {
		if (!sounds.value || (quietInDungeons.value && DungeonLocation.inDungeon)) return
		// Repeats three ticks apart, as BtrBz spaces them.
		for (i in 0 until times) pendingSounds += (ticks + i * 3L) to sound
	}

	private fun playDueSounds() {
		ticks++
		val due = pendingSounds.filter { it.first <= ticks }
		if (due.isEmpty()) return
		pendingSounds.removeAll(due)
		due.forEach { SoundChoices.play(it.second, 0.5f, 1f) }
	}

	// ---- Reopening a cancelled order -------------------------------------------------------

	/** An order, as clicked in Bazaar Orders. [left] is how many hadn't filled. */
	private class Picked(val name: String, val icon: ItemStack, val left: Int, val sell: Boolean)

	/** The product whose Bazaar page was open last, for the amount presets. */
	var lastProduct: String? = null
		private set

	private var picked: Picked? = null
	private var cancelled: Picked? = null

	/** Whether Bazaar Orders has shown the reopen item since the cancel; leaving it then clears it. */
	private var reopenShown = false

	private fun isReopenSlot(screen: AbstractContainerScreen<*>, slot: Slot?): Boolean {
		if (slot == null || !reopen.value || !module.enabled || cancelled == null || !inOrdersMenu(screen)) return false
		if (slot.container is Inventory) return false
		return slot.containerSlot == slot.container.containerSize - 6
	}

	private fun reopenStack(): ItemStack? {
		val order = cancelled ?: return null
		val stack = order.icon.copy()
		stack.count = 1
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("§aReopen: §f${order.name}").withStyle { it.withItalic(false) })
		stack.set(
			DataComponents.LORE,
			net.minecraft.world.item.component.ItemLore(listOf(
				Component.empty(),
				Component.literal("§7Click to open this product's Bazaar page.").withStyle { it.withItalic(false) },
			)),
		)
		return stack
	}

	/** The reopen item, drawn in place of the slot's own. True when it was. */
	@JvmStatic
	fun drawReopen(graphics: GuiGraphicsExtractor, slot: Slot): Boolean {
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
		if (!isReopenSlot(screen, slot)) return false
		val stack = reopenStack() ?: return false
		reopenShown = true
		graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x3055FF55)
		graphics.item(stack, slot.x, slot.y)
		return true
	}

	/** The reopen item's tooltip over the slot. True when it replaced the slot's own. */
	@JvmStatic
	fun drawReopenTooltip(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		val slot = (screen as ContainerScreenAccessor).`cryptic$hoveredSlot`() ?: return false
		if (!isReopenSlot(screen, slot)) return false
		val stack = reopenStack() ?: return false
		graphics.setComponentTooltipForNextFrame(client.font, listOf(stack.hoverName) + (stack.get(DataComponents.LORE)?.lines() ?: emptyList()), mouseX, mouseY)
		return true
	}

	/** A click of yours in a Bazaar menu. True takes it. */
	@JvmStatic
	fun onSlotClicked(screen: AbstractContainerScreen<*>, slot: Slot?): Boolean {
		if (!module.enabled || !reopen.value || slot == null) return false
		if (isReopenSlot(screen, slot)) {
			val order = cancelled ?: return true
			val name = order.name
			cancelled = null
			BazaarPresets.relisting(name, order.sell, order.left)
			// The click never reaches Hypixel, so its click sound is played here.
			client.soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f))
			client.player?.connection?.sendCommand("bz $name")
			return true
		}
		if (inOrdersMenu(screen) && slot.container !is Inventory) {
			parseOrder(slot.item)?.let { order -> picked = Picked(order.name, slot.item.copy(), order.amount - order.filled, order.sell) }
			return false
		}
		val name = slot.item.hoverName.string.replace(FORMATTING, "").trim()
		if (screen.title.string == "Order options" && name == "Cancel Order") {
			val order = picked ?: return false
			cancelled = order
			reopenShown = false
			if (client.hasControlDown() && order.left > 0) {
				client.keyboardHandler.clipboard = order.left.toString()
				notify("§7Copied §f${order.left} §7to the clipboard.")
			}
		}
		return false
	}

	/** Once Bazaar Orders has shown the reopen item and been left, it's gone. */
	private fun forgetReopenOnLeave(screen: Any?) {
		if (reopenShown && !inOrdersMenu(screen)) {
			cancelled = null
			reopenShown = false
		}
	}

	/** Colours read off Hypixel's own messages, by item name. */
	private val nameCodes = HashMap<String, String>()

	/** [name] in its rarity's colour: as Hypixel last wrote it, or from the items list. */
	private fun named(name: String): String = (nameCodes[name] ?: ItemRarity.codeForName(name) ?: "§f") + name

	/** A notification (a chat line with notifications off). */
	private fun notify(message: String) {
		// Logged either way, so a notification that seemed missing can be traced.
		val quiet = quietInDungeons.value && DungeonLocation.inDungeon
		Cryptic.LOGGER.info("Bazaar notification{}: {}", if (quiet) " (held back in a dungeon)" else "", message.replace(FORMATTING, ""))
		if (quiet) return
		Toasts.show("Bazaar", message, color = Toasts.Colors.BAZAAR)
	}

	// ---- Daily limit -------------------------------------------------------------------------

	private class Daily(var day: Long = 0, var used: Double = 0.0)

	private var daily = Daily()
	private val dailyFile get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("bazaar-daily.json")

	private fun today(): Long = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()

	private fun used(): Double {
		if (daily.day != today()) {
			daily = Daily(today(), 0.0)
			saveDaily()
		}
		return daily.used
	}

	private fun spend(coins: Double) {
		if (coins <= 0 || !coins.isFinite()) return
		used()
		daily.used += coins
		saveDaily()
	}

	private fun saveDaily() {
		runCatching {
			Files.createDirectories(dailyFile.parent)
			Files.writeString(dailyFile, gson.toJson(daily))
		}.onFailure { Cryptic.LOGGER.warn("Could not save the Bazaar daily limit", it) }
	}

	private fun loadDaily() {
		runCatching {
			if (Files.exists(dailyFile)) daily = gson.fromJson(Files.readString(dailyFile), Daily::class.java) ?: Daily()
		}.onFailure { Cryptic.LOGGER.warn("Could not read the Bazaar daily limit", it) }
	}

	/** "380.8M", "15B", "0": short, without zeros that say nothing. */
	fun short(value: Double): String {
		fun trim(number: Double, unit: String) =
			String.format(Locale.US, "%.2f", number).trimEnd('0').trimEnd('.') + unit
		return when {
			value >= 1e9 -> trim(value / 1e9, "B")
			value >= 1e6 -> trim(value / 1e6, "M")
			value >= 1e3 -> trim(value / 1e3, "k")
			else -> String.format(Locale.US, "%.0f", value)
		}
	}

	/**
	 * Over a Bazaar menu: the day's usage and a bar, centred above the window and high enough to
	 * clear the buttons other mods put along its top.
	 */
	@JvmStatic
	fun renderOverlay(screen: Screen, graphics: GuiGraphicsExtractor) {
		if (!module.enabled || !inBazaar(screen)) return
		val accessor = screen as? ContainerScreenAccessor ?: return
		if (breakdown.value && inOrdersMenu(screen)) drawBreakdown(screen as AbstractContainerScreen<*>, accessor, graphics)
		if (!dailyLimit.value) return
		val font = client.font
		val used = used()
		val cap = limit.value * 1e9
		val text = "§6Daily limit: §f${short(used)} §7/ ${short(cap)}"
		val centre = accessor.`cryptic$leftPos`() + accessor.`cryptic$imageWidth`() / 2
		val top = (accessor.`cryptic$topPos`() - 34 - font.lineHeight).coerceAtLeast(2)
		graphics.text(font, text, centre - font.width(text) / 2, top, 0xFFFFFFFF.toInt())
	}

	fun statusFor(order: Order): Status = statuses[key(order)] ?: statusOf(order)

	// ---- The orders breakdown ------------------------------------------------------------------

	private const val BREAKDOWN_GAP = 8
	private const val COLUMN_GAP = 12
	private const val MIN_SCALE = 0.5f
	private const val RULE_COLOR = 0x40FFFFFF

	/** A line of the breakdown: words, and a value on the right; null words make a rule. */
	private class Line(val left: String?, val right: String? = null)

	/**
	 * Right of the orders menu, the orders it shows now: each one's worth and the totals. A sell
	 * offer counts whole, filled or not, since its coins are yours either way once it fills.
	 */
	private fun drawBreakdown(screen: AbstractContainerScreen<*>, accessor: ContainerScreenAccessor, graphics: GuiGraphicsExtractor) {
		val shown = screen.menu.slots
			.filter { it.container !is Inventory }
			.mapNotNull { slot -> parsed.getOrPut(slot.item) { parseOrder(slot.item) ?: NOT_AN_ORDER }.takeIf { it !== NOT_AN_ORDER } }
		if (shown.isEmpty()) return
		val keep = 1 - tax.value / 100
		val (sells, buys) = shown.partition { it.sell }
		val lines = ArrayList<Line>()
		// A square in the order's status colour, as its slot is tinted.
		fun row(order: Order, worth: Double) =
			Line("${statusFor(order).code}■ §7${order.amount}x ${named(order.name)}", "§6${short(worth)}")
		if (sells.isNotEmpty()) {
			lines += Line("§7Sell offers")
			sells.forEach { lines += row(it, it.amount * it.price * keep) }
			lines += Line("§fAll sold", "§6${short(sells.sumOf { it.amount * it.price * keep })}")
		}
		if (buys.isNotEmpty()) {
			if (lines.isNotEmpty()) lines += Line(null)
			lines += Line("§7Buy orders")
			buys.forEach { lines += row(it, it.amount * it.price) }
			lines += Line("§fCost", "§6${short(buys.sumOf { it.amount * it.price })}")
		}

		val font = client.font
		val height = font.lineHeight + 2
		val valued = lines.filter { it.right != null }
		val width = maxOf(
			lines.filter { it.right == null }.maxOfOrNull { font.width(it.left ?: "") } ?: 0,
			(valued.maxOfOrNull { font.width(it.left!!) } ?: 0) + COLUMN_GAP + (valued.maxOfOrNull { font.width(it.right!!) } ?: 0),
		)
		val right = accessor.`cryptic$leftPos`() + accessor.`cryptic$imageWidth`() + BREAKDOWN_GAP
		val room = (graphics.guiWidth() - right - 2).toFloat()
		val scale = if (width <= room) 1f else (room / width.coerceAtLeast(1)).coerceAtLeast(MIN_SCALE)
		val y = accessor.`cryptic$topPos`() + accessor.`cryptic$imageHeight`() / 2f - (lines.size * height - 2) * scale / 2f
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(right.toFloat(), maxOf(2f, y))
		pose.scale(scale, scale)
		lines.forEachIndexed { i, line ->
			val rowY = i * height
			if (line.left == null) {
				graphics.fill(0, rowY + (height - 2) / 2, width, rowY + (height - 2) / 2 + 1, RULE_COLOR)
				return@forEachIndexed
			}
			graphics.text(font, line.left, 0, rowY, -1)
			line.right?.let { graphics.text(font, it, width - font.width(it), rowY, -1) }
		}
		pose.popMatrix()
	}

	// ---- The menu ----------------------------------------------------------------------------

	/** Each order slot's status in the open orders menu, by stack, for the slot tint and tooltip. */
	private val menuStatuses = java.util.WeakHashMap<ItemStack, Status>()

	/** Orders read off menu stacks, so slots drawn every frame aren't parsed every frame. */
	private val parsed = java.util.WeakHashMap<ItemStack, Order>()
	private val NOT_AN_ORDER = Order()

	@JvmStatic
	fun drawSlot(graphics: GuiGraphicsExtractor, slot: Slot) {
		if (!module.enabled || !menuStatus.value || slot.container is Inventory) return
		if (!inOrdersMenu(client.gui.screen())) return
		val order = parsed.getOrPut(slot.item) { parseOrder(slot.item) ?: NOT_AN_ORDER }.takeIf { it !== NOT_AN_ORDER } ?: return
		val status = statusFor(order)
		menuStatuses[slot.item] = status
		if (status == Status.UNKNOWN) return
		graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, (0x60 shl 24) or status.color)
	}

	private fun addTooltip(stack: ItemStack, lines: MutableList<Component>) {
		if (!module.enabled || lines.isEmpty()) return
		if (menuStatus.value && inOrdersMenu(client.gui.screen())) {
			menuStatuses[stack]?.let { status ->
				lines.add(1, Component.literal("§7Status: ").append(Component.literal(status.label).withColor(status.color)))
			}
		}
	}

	private val ESCROW = Regex("""^Escrow refunded [\d,]+ coins for Bazaar .+!$""")
	private const val ESCROW_COOLDOWN_MILLIS = 750L
	private var escrowReopenedAt = 0L

	fun coins(value: Double): String =
		if (value >= 1000) String.format(Locale.US, "%,.0f", value) else String.format(Locale.US, "%,.1f", value)

	// ---- The HUD ------------------------------------------------------------------------------

	private class OrdersElement : HudElement("bazaar_orders", "Bazaar Orders", 0.02, 0.05) {
		private val font get() = Minecraft.getInstance().font

		private fun lines(list: List<Order>): List<Pair<String, Status>> =
			list.map { "§7${it.kind} §f${it.amount}x ${it.name} §8@ §e${coins(it.price)}" to statusFor(it) }

		private fun sample() = listOf(
			Order("Enchanted Diamond", false, 64, 156.2),
			Order("Heat Core", true, 8, 1_300_000.0),
		)

		override val width: Int
			get() = maxOf(font.width("Bazaar Orders"), lines(orders.ifEmpty { sample() }).maxOf { (text, status) -> font.width(text) + font.width(" " + status.label) })
		override val height: Int get() = font.lineHeight * (1 + maxOf(1, orders.size))

		override fun isVisible(): Boolean =
			module.enabled && ordersHud.value && orders.isNotEmpty() && SkyblockLocation.onSkyblock && !inBazaar(Minecraft.getInstance().gui.screen())

		override fun showInEditor(): Boolean = module.enabled && ordersHud.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, lines(orders))

		override fun renderExample(context: GuiGraphicsExtractor) =
			draw(context, sample().map { "§7${it.kind} §f${it.amount}x ${it.name} §8@ §e${coins(it.price)}" to Status.TOP })

		private fun draw(context: GuiGraphicsExtractor, rows: List<Pair<String, Status>>) {
			context.text(font, "§6§lBazaar Orders", 0, 0, 0xFFFFFFFF.toInt())
			var y = font.lineHeight
			for ((text, status) in rows) {
				context.text(font, text, 0, y, 0xFFFFFFFF.toInt())
				context.text(font, " ${status.label}", font.width(text), y, (0xFF shl 24) or status.color)
				y += font.lineHeight
			}
		}
	}

	// ---- Saving -------------------------------------------------------------------------------

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val type = object : TypeToken<MutableList<Order>>() {}.type
			orders = gson.fromJson<MutableList<Order>>(Files.readString(file), type) ?: ArrayList()
		}.onFailure { Cryptic.LOGGER.warn("Could not read Bazaar orders", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(orders))
		}.onFailure { Cryptic.LOGGER.warn("Could not save Bazaar orders", it) }
	}

	fun describe(): List<String> = listOf(
		"Bazaar: ${BazaarMarket.products.size} products, updated ${(System.currentTimeMillis() - BazaarMarket.updatedAt) / 1000}s ago",
	) + orders.map { "${it.kind} ${it.amount}x ${it.name} @ ${it.price}: ${statusFor(it).label} (id ${BazaarMarket.idForName(it.name) ?: "unknown"})" }
}
