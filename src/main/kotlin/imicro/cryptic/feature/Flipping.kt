package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.mixin.SignEditScreenAccessor
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.ItemRarity
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.nio.file.Files
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bazaar flipping. A finder ranks products by what a buy order and then a sell offer would make
 * with your purse, from Hypixel's public Bazaar data; a guide walks you through each flip one step
 * at a time (the command to type, the slot to click, the amount for the sign) and keeps going as
 * orders fill: claim, sell, relist. Every click is yours. FlipperX Bazaar Assist's idea; the
 * picking and the code are Cryptic's own.
 */
object Flipping {
	private val finderSection = SectionModuleSetting("finder_section", "Finder")

	@JvmField
	val finder = ToggleModuleSetting(
		"finder", "Flip finder", true,
		"Left of Bazaar menus, the products a buy order and then a sell offer would make the most on right now, for what your purse covers. " +
			"Click one to open it and have the guide take it from there.",
	)

	@JvmField
	val slots = SliderModuleSetting(
		id = "flips_at_once",
		label = "Flips at once",
		defaultValue = 4.0,
		min = 1.0,
		max = 14.0,
		step = 1.0,
		description = "How many flips run side by side. Your budget is split between them.",
	)

	@JvmField
	val budget = SliderModuleSetting(
		id = "budget",
		label = "Budget",
		unit = "%",
		defaultValue = 80.0,
		min = 5.0,
		max = 100.0,
		step = 5.0,
		description = "How much of your purse flips may use.",
	)

	@JvmField
	val fillWithin = SliderModuleSetting(
		id = "fill_within",
		label = "Fill within",
		unit = "min",
		defaultValue = 30.0,
		min = 5.0,
		max = 240.0,
		step = 5.0,
		description = "How long a flip may take to fill, going by the week's trading. Longer allows bigger orders of slower products.",
	)

	@JvmField
	val maxMargin = SliderModuleSetting(
		id = "max_margin",
		label = "Largest margin",
		unit = "%",
		defaultValue = 35.0,
		min = 5.0,
		max = 300.0,
		step = 5.0,
		description = "Products whose sell price sits further above their buy price than this are left out: a gap that wide is usually a price about to move.",
	)

	private val guideSection = SectionModuleSetting("guide_section", "Guide")

	@JvmField
	val toggleKey = KeybindModuleSetting("toggle_key", "Start/stop key", "Starts the guide, or stops it.")

	@JvmField
	val commandKey = KeybindModuleSetting(
		"command_key", "Send command key",
		"Sends the command the guide shows, such as /bz Enchanted Diamond, so you don't type it. Works in menus too.",
	)

	private const val SIGN_SHOW = 0
	private const val SIGN_CLOSE = 2

	@JvmField
	val signs = DropdownModuleSetting(
		id = "signs",
		label = "Amount sign",
		options = listOf("Show the amount", "Type it in", "Type and close"),
		defaultIndex = SIGN_CLOSE,
		description = "What happens when Custom Amount's sign opens: the amount is only shown for you to type, typed in for you to confirm, " +
			"or typed in and the sign closed, as the Bazaar's amount presets do.",
	)

	@JvmField
	val relistAfter = SliderModuleSetting(
		id = "relist_after",
		label = "Relist after",
		unit = "min",
		defaultValue = 3.0,
		min = 0.0,
		max = 30.0,
		step = 1.0,
		description = "How long a flip's order stays undercut before the guide has you cancel and relist it. 0 leaves undercut orders alone.",
	)

	@JvmField
	val guide = ToggleModuleSetting(
		"guide", "Guide", true,
		"Walks you through each flip: the command to type, the slot to click (outlined), the amount for the sign. As orders fill it has you " +
			"claim them, sell what you bought and start the next. Start it with its key, or from the finder.",
	).withMore(toggleKey, commandKey, signs, relistAfter)

	private val forget = ButtonModuleSetting("forget_flips", "Forget flips", action = { forgetFlips() })

	private val summarySection = SectionModuleSetting("summary_section", "Summary")

	@JvmField
	val summary = ToggleModuleSetting(
		"summary", "Week summary", true,
		"Opens the panel left of Bazaar menus with what your flips made this week: the total, a chart, the item that made the most, and your goal.",
	)

	@JvmField
	val goal = TextModuleSetting(
		id = "goal",
		label = "Goal",
		maxLength = 32,
		hint = "500m",
		description = "Coins to flip your way to, such as 500m or 1.5b Hyperion. Shown as a bar in the summary and on the Flips HUD. Empty for none.",
	)

	private val resetGoal = ButtonModuleSetting("reset_goal", "Restart goal", action = { BazaarFlips.resetGoal() })

	private val hudSection = SectionModuleSetting("hud_section", "HUD")

	private val resetFlips = ButtonModuleSetting("reset_flips", "Reset session", action = { BazaarFlips.reset() })

	@JvmField
	val flipHud = ToggleModuleSetting(
		"flip_hud", "Flips HUD", true,
		"A panel with the guide's next step, or with the guide off what your orders want next (claiming, relisting, nothing), and what your " +
			"flips made this session and per hour, with your goal under it. Profit counts what you sell of things you bought on the Bazaar, against what you paid.",
	).withMore(resetFlips)

	@JvmField
	val module = Module(
		id = "flipping",
		name = "Flipping",
		description = "Bazaar flips, step by step",
		category = ModuleCategory.GENERAL,
		settings = listOf(finderSection, finder, slots, budget, fillWithin, maxMargin, guideSection, guide, forget, summarySection, summary, goal, resetGoal, hudSection, flipHud),
	)

	private val client: Minecraft get() = Minecraft.getInstance()
	private val FORMATTING = Regex("§.")

	fun initialize() = load()

	// ---- Finding flips -------------------------------------------------------------------------

	/**
	 * A flip as it stands now: [units] bought at [buyAt] and sold at [sellAt], [margin] each after
	 * the sell tax. [hourly] is how many trade both ways in an hour.
	 */
	class Candidate(val id: String, var name: String, val units: Int, val buyAt: Double, val sellAt: Double, val margin: Double, val hourly: Double) {
		val profit: Double get() = units * margin
	}

	private const val ORDER_LIMIT = 71_680
	private val UNCARRIED = listOf("ENCHANTMENT_", "ESSENCE_", "SHARD_")
	private const val STACK = 64
	private const val ROWS = 8

	/** How much of a product's trading one order on top is taken to catch; others keep outbidding it. */
	private const val FLOW_SHARE = 0.25

	private var candidates: List<Candidate> = emptyList()
	private var emptyReason = "Loading prices..."
	private var rankedAt = 0L

	/** Products passed over, by id, until when. */
	private val skipped = HashMap<String, Long>()
	private const val SKIP_MILLIS = 10 * 60_000L

	private val slotCount: Int get() = slots.value.roundToInt()

	private fun own(): List<Bazaar.Order> = Bazaar.openOrders.filter { !it.coopMember }

	private val ids = HashMap<String, String>()

	private fun idOf(name: String): String? = ids[name] ?: BazaarMarket.idForName(name)?.also { ids[name] = it }

	/** A name without the symbol gemstones lead with, as it's typed and as chat writes it. */
	private fun bare(name: String) = name.trimStart { !it.isLetterOrDigit() }.trim()

	/** A product's name, when typing it finds that product again. */
	private fun nameFor(id: String): String? = BazaarMarket.nameOf(id)?.let(::bare)?.takeIf { BazaarMarket.idForName(it) == id }

	/** What one more flip may spend: the budget's share of your purse, split over the free slots. */
	private fun budgetEach(): Double {
		val purse = SkyblockLocation.purse() ?: return 0.0
		return purse * budget.value / 100 / (slotCount - flips.size).coerceAtLeast(1)
	}

	/** How many units your free inventory slots hold: a filled buy order has to be claimed into them. */
	private fun room(): Int {
		val inventory = client.player?.inventory ?: return 0
		return (0 until 36).count { inventory.getItem(it).isEmpty } * STACK
	}

	private fun carried(id: String): Int {
		val inventory = client.player?.inventory ?: return 0
		var count = 0
		for (i in 0 until 36) {
			val stack = inventory.getItem(i)
			if (!stack.isEmpty && SkyblockItem.id(stack) == id) count += stack.count
		}
		return count
	}

	private fun bought(id: String): Long = BazaarFlips.heldUnits { idOf(it) == id }

	/**
	 * A buy order a tenth over the best one, then a sell offer a tenth under the cheapest. Sized to
	 * what [each] coins buy, [room] holds, and a share of what trades within the fill time.
	 */
	private fun evaluate(product: BazaarMarket.Product, each: Double, room: Int, floor: Double): Candidate? {
		// Books, essences and shards aren't carried or named like other products.
		if (UNCARRIED.any { product.id.startsWith(it) }) return null
		val buyAt = (product.buyOrders.firstOrNull()?.price ?: return null) + 0.1
		val sellAt = (product.sellOffers.firstOrNull()?.price ?: return null) - 0.1
		val margin = sellAt * (1 - Bazaar.tax.value / 100) - buyAt
		if (margin <= 0 || margin / buyAt * 100 > maxMargin.value) return null
		val hourly = minOf(product.boughtWeekly, product.soldWeekly) / 168.0
		val units = minOf(each / buyAt, hourly * fillWithin.value / 60 * FLOW_SHARE, room.toDouble(), ORDER_LIMIT.toDouble()).toInt()
		if (units < 1 || units * margin < floor) return null
		return Candidate(product.id, "", units, buyAt, sellAt, margin, hourly)
	}

	private fun rank() {
		val now = System.currentTimeMillis()
		if (now - rankedAt < 1_000L) return
		rankedAt = now
		skipped.values.removeIf { it < now }
		val each = budgetEach()
		val room = room()
		val found = ArrayList<Candidate>()
		if (each > 0 && room > 0) {
			val taken = flips.keys + own().mapNotNull { idOf(it.name) }
			val floor = maxOf(1_000.0, each * 0.001)
			for (candidate in BazaarMarket.products.values.mapNotNull { evaluate(it, each, room, floor) }.sortedByDescending { it.profit }) {
				if (found.size >= ROWS) break
				if (candidate.id in taken || candidate.id in skipped) continue
				candidate.name = nameFor(candidate.id) ?: continue
				found += candidate
			}
			retarget(each, room)
		}
		candidates = found
		emptyReason = when {
			SkyblockLocation.purse() == null -> "Purse isn't on the sidebar"
			room == 0 -> "Inventory is full"
			BazaarMarket.products.isEmpty() -> "Loading prices..."
			else -> "Nothing worth flipping"
		}
	}

	/** The flip being set up, at today's prices; passed over once its spread has closed. */
	private fun retarget(each: Double, room: Int) {
		val old = target ?: return
		if (shownMenu in FLOW) return
		val fresh = BazaarMarket.product(old.id)?.let { evaluate(it, each, room, 0.0) }
		if (fresh == null) {
			skipped[old.id] = System.currentTimeMillis() + SKIP_MILLIS
			target = null
			notify("§f${old.name}§7's spread closed; picking another.")
			return
		}
		fresh.name = old.name
		target = fresh
	}

	// ---- Flips under way -----------------------------------------------------------------------

	/** A flip the guide started, by product id. Kept by Gson, so all defaulted. */
	private class Flip(var name: String = "", var units: Int = 0, var buyAt: Double = 0.0, var startedAt: Long = 0) {
		/** Since when it has had no order and nothing carried. */
		@Transient
		var idleAt = 0L
	}

	private val flips = LinkedHashMap<String, Flip>()
	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("bazaar-flipping.json")

	/** The flip whose buy order is being set up. */
	private var target: Candidate? = null

	/** What Custom Amount takes at most, as its lore says. */
	private var amountCap = ORDER_LIMIT

	var running = false
		private set

	private const val SETTLE_MILLIS = 15_000L
	private const val IDLE_MILLIS = 5 * 60_000L

	private fun forgetFlips() {
		flips.clear()
		target = null
		save()
		notify("§7Forgot the flips under way.")
	}

	/** A flip is over once it has no order, nothing carried and nothing bought left to sell. */
	private fun prune(own: List<Bazaar.Order>) {
		val now = System.currentTimeMillis()
		val before = flips.size
		flips.entries.removeIf { (id, flip) ->
			if (own.any { idOf(it.name) == id } || carried(id) > 0) {
				flip.idleAt = 0L
				return@removeIf false
			}
			if (flip.idleAt == 0L) flip.idleAt = now
			// Bought items that went elsewhere (a chest, an NPC) would hold their slot for ever.
			(now - flip.startedAt > SETTLE_MILLIS && bought(id) == 0L) || now - flip.idleAt > IDLE_MILLIS
		}
		if (flips.size != before) save()
	}

	/** The target's buy order is out: the flip is under way. */
	private fun settle(own: List<Bazaar.Order>) {
		val placed = target ?: return
		if (own.none { !it.sell && idOf(it.name) == placed.id }) return
		flips[placed.id] = Flip(placed.name, placed.units, placed.buyAt, System.currentTimeMillis())
		target = null
		save()
	}

	/** Orders of flips that have stood undercut, by order, since when. */
	private val undercutSince = HashMap<String, Long>()

	private fun orderKey(order: Bazaar.Order) = "${order.sell}|${order.name}|${order.price}|${order.amount}"

	private fun same(a: Bazaar.Order, b: Bazaar.Order) = orderKey(a) == orderKey(b)

	private fun noteUndercut(own: List<Bazaar.Order>) {
		val now = System.currentTimeMillis()
		val undercut = own.filter { Bazaar.statusFor(it) == Bazaar.Status.UNDERCUT }.map(::orderKey).toSet()
		undercutSince.keys.retainAll(undercut)
		undercut.forEach { undercutSince.putIfAbsent(it, now) }
	}

	/** A flip's order to cancel and place again: expired, or undercut for long enough. */
	private fun relistable(own: List<Bazaar.Order>): Bazaar.Order? {
		val wait = (relistAfter.value * 60_000).toLong()
		val now = System.currentTimeMillis()
		return own.firstOrNull { order ->
			if (order.filled >= order.amount || idOf(order.name) !in flips) return@firstOrNull false
			when (Bazaar.statusFor(order)) {
				Bazaar.Status.EXPIRED -> true
				Bazaar.Status.UNDERCUT -> wait > 0 && now - (undercutSince[orderKey(order)] ?: now) >= wait
				else -> false
			}
		}
	}

	/** A flip whose bought items are on you, waiting for their sell offer. */
	private fun selling(): Map.Entry<String, Flip>? = flips.entries.firstOrNull { (id, _) -> carried(id) > 0 && bought(id) > 0 }

	// ---- The next step -------------------------------------------------------------------------

	/**
	 * What to do next. [command] is typed (or sent with the key), [slot] is the menu slot to click,
	 * [sign] is what the open sign takes. [urgent] steps are yours to make; the rest is waiting.
	 */
	class Step(
		val title: String,
		val detail: String? = null,
		val urgent: Boolean = true,
		val icon: ItemStack? = null,
		val command: String? = null,
		val slot: Int = -1,
		val sign: String? = null,
	)

	private enum class Menu { NONE, MAIN, PRODUCT, AMOUNT, BUY_PRICE, BUY_CONFIRM, SELL_PRICE, SELL_CONFIRM, ORDERS, OPTIONS, SIGN, OTHER }

	/** Menus in the middle of placing or cancelling an order, where the plan isn't changed. */
	private val FLOW = setOf(Menu.AMOUNT, Menu.SIGN, Menu.BUY_PRICE, Menu.BUY_CONFIRM, Menu.SELL_PRICE, Menu.SELL_CONFIRM, Menu.OPTIONS)

	private const val CUSTOM_AMOUNT = "Custom Amount"
	private val buyUpTo = Regex("""Buy up to ([\d,]+)x""")

	private var step: Step? = null
	private var shownScreen: Screen? = null
	private var shownMenu = Menu.NONE

	/** The order last clicked in the orders menu, which Order options is about. */
	private var picked: Bazaar.Order? = null

	private var amountMenuAt = 0L
	private var seenSign: Screen? = null
	private var signIsAmount = false

	/** The guide's step for the HUD; null while it isn't running. */
	fun hudStep(): Step? = if (running && module.enabled) step else null

	private fun plain(stack: ItemStack) = bare(stack.hoverName.string.replace(FORMATTING, ""))

	private fun AbstractContainerScreen<*>.named(name: String): Slot? =
		menu.slots.firstOrNull { it.container !is Inventory && !it.item.isEmpty && plain(it.item) == name }

	private fun AbstractContainerScreen<*>.containing(part: String): Slot? =
		menu.slots.firstOrNull { it.container !is Inventory && !it.item.isEmpty && plain(it.item).contains(part) }

	private fun AbstractContainerScreen<*>.carrying(id: String): Slot? =
		menu.slots.firstOrNull { it.container is Inventory && SkyblockItem.id(it.item) == id }

	private fun menuOf(screen: Screen?): Menu {
		if (screen == null || screen is ChatScreen) return Menu.NONE
		if (screen is AbstractSignEditScreen) return Menu.SIGN
		val chest = screen as? AbstractContainerScreen<*> ?: return Menu.OTHER
		val title = chest.title.string.replace(FORMATTING, "").trim()
		return when {
			title.startsWith("How many do you want") -> Menu.AMOUNT
			title.startsWith("How much do you want to pay") -> Menu.BUY_PRICE
			title.startsWith("Confirm Buy Order") -> Menu.BUY_CONFIRM
			title.startsWith("At what price are you selling") -> Menu.SELL_PRICE
			title.startsWith("Confirm Sell Offer") -> Menu.SELL_CONFIRM
			title == "Your Bazaar Orders" || title == "Co-op Bazaar Orders" -> Menu.ORDERS
			title == "Order options" -> Menu.OPTIONS
			chest.named("Create Buy Order") != null -> Menu.PRODUCT
			title.startsWith("Bazaar") || title.contains("➜") -> Menu.MAIN
			else -> Menu.OTHER
		}
	}

	private fun count(amount: Int) = String.format(Locale.US, "%,d", amount)

	private fun icon(name: String): ItemStack? = BazaarFlips.iconOf(name) ?: Farming.iconFor(name)

	private fun amountFor(flip: Candidate) = minOf(flip.units, amountCap)

	/** A command, with how to send it. */
	private fun typed(command: String, why: String, icon: ItemStack?): Step {
		val how = when {
			commandKey.bound -> "$why  §8[${commandKey.keyName}]"
			client.gui.screen() is AbstractContainerScreen<*> -> "$why (close this menu)"
			else -> why
		}
		return Step("/$command", how, icon = icon, command = command)
	}

	/** On the way to the orders menu. */
	private fun toOrders(menu: Menu, chest: AbstractContainerScreen<*>?, why: String, icon: ItemStack?): Step {
		if (chest != null && (menu == Menu.MAIN || menu == Menu.PRODUCT)) {
			chest.named("Manage Orders")?.let { return Step("Click Manage Orders", why, icon = icon, slot = it.index) }
		}
		return typed("bz", why, icon)
	}

	private fun compute(menu: Menu, chest: AbstractContainerScreen<*>?, own: List<Bazaar.Order>): Step {
		val buying = target
		if (chest != null) when (menu) {
			Menu.AMOUNT -> if (buying != null) {
				val custom = chest.named(CUSTOM_AMOUNT)
				custom?.item?.get(DataComponents.LORE)?.lines()
					?.firstNotNullOfOrNull { buyUpTo.find(it.string.replace(FORMATTING, ""))?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
					?.let { amountCap = it }
				return Step("Click Custom Amount", "${count(amountFor(buying))}x ${buying.name}", icon = icon(buying.name), slot = custom?.index ?: -1)
			}
			Menu.BUY_PRICE -> return Step("Click Best Order +0.1", "Puts your order on top", slot = chest.containing("+0.1")?.index ?: -1)
			Menu.BUY_CONFIRM -> return Step("Confirm the buy order", buying?.let { "About +${Bazaar.short(it.profit)} once it's sold" }, slot = chest.containing("Buy Order")?.index ?: -1)
			Menu.SELL_PRICE -> return Step("Click Best Offer -0.1", "Puts your offer on top", slot = chest.containing("-0.1")?.index ?: -1)
			Menu.SELL_CONFIRM -> return Step("Confirm the sell offer", slot = chest.containing("Sell Offer")?.index ?: -1)
			Menu.OPTIONS -> {
				val cancel = picked?.takeIf { clicked -> relistable(own)?.let { same(it, clicked) } == true }
					?: return Step("Order options", urgent = false)
				return Step("Click Cancel Order", "${cancel.name}: relist it after", icon = icon(cancel.name), slot = chest.named("Cancel Order")?.index ?: -1)
			}
			else -> {}
		}
		if (menu == Menu.SIGN) {
			return if (signIsAmount && buying != null) {
				val amount = amountFor(buying).toString()
				Step("Amount: $amount", if (signs.selectedIndex == SIGN_SHOW) "Type it on the first line" else "Typed in: press Done", sign = amount)
			} else Step("Sign", urgent = false)
		}

		// Claiming comes first: coins and items sitting in a filled order do nothing.
		if (chest != null && menu == Menu.ORDERS) {
			chest.menu.slots.firstOrNull { slot ->
				slot.container !is Inventory && Bazaar.orderOf(slot.item)?.let { !it.coopMember && it.amount > 0 && it.filled >= it.amount } == true
			}?.let { slot ->
				val order = Bazaar.orderOf(slot.item)!!
				return Step("Claim ${count(order.amount)}x ${order.name}", if (order.sell) "Your sell offer filled" else "Your buy order filled", icon = slot.item, slot = slot.index)
			}
		} else {
			val filled = own.filter { Bazaar.statusFor(it) == Bazaar.Status.FILLED }
			filled.firstOrNull()?.let { first ->
				val why = if (filled.size == 1) "Claim ${count(first.amount)}x ${first.name}" else "Claim ${filled.size} filled orders"
				return toOrders(menu, chest, why, icon(first.name))
			}
		}

		selling()?.let { (id, flip) ->
			val units = count(carried(id))
			if (chest != null && menu == Menu.PRODUCT && Bazaar.openProduct == id) {
				return Step("Click Create Sell Offer", "${units}x ${flip.name}", icon = icon(flip.name), slot = chest.named("Create Sell Offer")?.index ?: -1)
			}
			// In a Bazaar menu, clicking an item of yours opens its page.
			if (chest != null && menu != Menu.OTHER) chest.carrying(id)?.let { slot ->
				return Step("Click your ${flip.name}", "Opens its page, to sell ${units}x", icon = slot.item, slot = slot.index)
			}
			return typed("bz ${flip.name}", "Sell ${units}x", icon(flip.name))
		}

		relistable(own)?.let { order ->
			val why = if (Bazaar.statusFor(order) == Bazaar.Status.EXPIRED) "expired" else "undercut"
			if (chest == null || menu != Menu.ORDERS) return toOrders(menu, chest, "Relist ${order.name}, $why", icon(order.name))
			chest.menu.slots.firstOrNull { slot -> slot.container !is Inventory && Bazaar.orderOf(slot.item)?.let { same(it, order) } == true }?.let { slot ->
				return Step("Click ${order.name}", "It's $why: cancel it, then relist", icon = slot.item, slot = slot.index)
			}
		}

		if (buying != null) {
			val plan = "${count(buying.units)}x for +${Bazaar.short(buying.profit)}"
			if (chest != null && menu == Menu.PRODUCT && Bazaar.openProduct == buying.id) {
				return Step("Click Create Buy Order", plan, icon = icon(buying.name), slot = chest.named("Create Buy Order")?.index ?: -1)
			}
			if (chest != null && menu == Menu.MAIN) chest.named(buying.name)?.let { slot ->
				return Step("Click ${buying.name}", plan, icon = slot.item, slot = slot.index)
			}
			return typed("bz ${buying.name}", "Buy $plan", icon(buying.name))
		}

		val waiting = own.count { !it.expired && it.filled < it.amount }
		return when {
			waiting == 1 -> Step("Waiting for 1 order", urgent = false)
			waiting > 1 -> Step("Waiting for $waiting orders", urgent = false)
			else -> Step(emptyReason, urgent = false)
		}
	}

	// ---- Running -------------------------------------------------------------------------------

	private var highlightSlot = -1
	private var highlightMenu: AbstractContainerMenu? = null

	private var toggleWasDown = false
	private var commandWasDown = false
	private var commandSentAt = 0L

	private fun notify(message: String) = Toasts.show("Flipping", message, color = Toasts.Colors.BAZAAR)

	private fun clickSound() = client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))

	private fun toggle() {
		if (!guide.value) return
		running = !running
		if (!running) target = null
		rankedAt = 0L
		notify(if (running) "§7Guide §aon§7." else "§7Guide §coff§7.")
	}

	private fun send(command: String) {
		val now = System.currentTimeMillis()
		// One press, one command.
		if (now - commandSentAt < 1_000L) return
		commandSentAt = now
		client.connection?.sendCommand(command)
	}

	/** Starts [candidate]: its page is opened, and the guide takes it from there. */
	private fun start(candidate: Candidate) {
		clickSound()
		if (guide.value) {
			target = candidate
			amountCap = ORDER_LIMIT
			running = true
		}
		send("bz ${candidate.name}")
	}

	private fun skip() {
		val passed = target ?: return
		clickSound()
		skipped[passed.id] = System.currentTimeMillis() + SKIP_MILLIS
		target = null
		rankedAt = 0L
	}

	/** The keys with no screen open; in menus they come through [handleKey]. */
	private fun pollKeys(screen: Screen?) {
		val toggleDown = toggleKey.isDown(client.window)
		val commandDown = commandKey.isDown(client.window)
		if (screen == null) {
			if (toggleDown && !toggleWasDown) toggle()
			if (commandDown && !commandWasDown) step?.command?.takeIf { running }?.let(::send)
		}
		toggleWasDown = toggleDown
		commandWasDown = commandDown
	}

	/** A key press in a chest. True takes it. */
	@JvmStatic
	fun handleKey(screen: AbstractContainerScreen<*>, key: Int): Boolean {
		if (!module.enabled || !guide.value || !SkyblockLocation.onSkyblock) return false
		if (toggleKey.matches(key)) {
			toggle()
			return true
		}
		if (commandKey.matches(key) && running) {
			val command = step?.command ?: return false
			send(command)
			return true
		}
		return false
	}

	/** A click of yours in a chest: the order clicked in the orders menu is the one its options are about. */
	@JvmStatic
	fun onSlotClicked(slot: Slot?) {
		if (!running || slot == null || shownMenu != Menu.ORDERS || slot.container is Inventory) return
		picked = Bazaar.orderOf(slot.item)
	}

	private fun idle() {
		step = null
		highlightSlot = -1
		highlightMenu = null
	}

	fun tick(client: Minecraft) {
		val screen = client.gui.screen()
		shownScreen = screen
		if (!module.enabled) running = false
		// Off SkyBlock the guide waits; a warp between islands doesn't stop it.
		if (!module.enabled || !SkyblockLocation.onSkyblock) {
			shownMenu = Menu.NONE
			idle()
			return
		}
		pollKeys(screen)
		if (!guide.value) running = false
		val menu = menuOf(screen)
		shownMenu = menu
		val trading = menu != Menu.NONE && menu != Menu.OTHER && menu != Menu.SIGN
		if (!running && !(finder.value && trading)) {
			idle()
			return
		}
		BazaarMarket.refresh(if (trading) 5_000L else 15_000L)
		val own = own()
		prune(own)
		rank()
		if (!running) {
			idle()
			return
		}

		val now = System.currentTimeMillis()
		if (menu == Menu.AMOUNT) amountMenuAt = now
		noteUndercut(own)
		settle(own)
		if (target == null && flips.size < slotCount && menu !in FLOW) {
			target = candidates.firstOrNull()
			amountCap = ORDER_LIMIT
		}

		if (menu == Menu.SIGN) {
			if (screen !== seenSign) {
				seenSign = screen
				// Custom Amount's sign opens straight from the amount menu.
				signIsAmount = target != null && now - amountMenuAt < 3_000L
				if (signIsAmount) writeSign(screen as AbstractSignEditScreen)
			}
		} else {
			seenSign = null
		}

		val chest = screen as? AbstractContainerScreen<*>
		val next = compute(menu, chest, own)
		step = next
		highlightSlot = if (chest != null) next.slot else -1
		highlightMenu = if (highlightSlot >= 0) chest?.menu else null
	}

	private fun writeSign(sign: AbstractSignEditScreen) {
		val amount = amountFor(target ?: return)
		if (signs.selectedIndex == SIGN_SHOW) return
		val accessor = sign as SignEditScreenAccessor
		accessor.`cryptic$messages`()[0] = amount.toString()
		accessor.`cryptic$signField`()?.setCursorToEnd()
		// Closing the sign sends it.
		if (signs.selectedIndex == SIGN_CLOSE) client.gui.setScreen(null)
	}

	// ---- Drawing -------------------------------------------------------------------------------

	private const val PANEL_WIDTH = 156
	private const val PAD = 6
	private const val LINE = 11
	private const val GAP = 6
	private const val MIN_SCALE = 0.6f
	private const val AMBER = 0xFFE8A33D.toInt()
	private const val TEXT = 0xFFE8E8E8.toInt()
	private const val DIM = 0xFF8A8A8A.toInt()
	private const val RULE = 0xFF2A2A2A.toInt()
	private const val HOVER = 0x20FFFFFF
	private const val BUTTON_HEIGHT = 15
	private const val BUTTON_FILL = 0xFF1B1B1B.toInt()
	private const val BUTTON_EDGE = 0xFF3A3A3A.toInt()
	private const val BUTTON_EDGE_HOVER = 0xFF8A8A8A.toInt()
	private const val HIGHLIGHT = 0xE8A33D

	/** Flips listed under the week's summary, which takes most of the panel's height. */
	private const val ROWS_UNDER_SUMMARY = 6

	/** Somewhere to click on the panel, in its own coordinates. */
	private class Hit(val x: Int, val y: Int, val width: Int, val height: Int, val tip: (() -> List<Component>)? = null, val action: () -> Unit) {
		fun has(mouseX: Float, mouseY: Float) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var hits: List<Hit> = emptyList()
	private var panelScreen: Screen? = null
	private var panelX = 0f
	private var panelY = 0f
	private var panelScale = 1f
	private var panelHeight = 0

	/** The slot the step wants clicked, outlined over its item. */
	@JvmStatic
	fun drawSlot(graphics: GuiGraphicsExtractor, slot: Slot) {
		if (slot.index != highlightSlot) return
		val menu = highlightMenu ?: return
		if ((client.gui.screen() as? AbstractContainerScreen<*>)?.menu !== menu) return
		val pulse = 0.6 + 0.4 * sin(System.currentTimeMillis() / 180.0)
		val edge = ((pulse * 255).toInt() shl 24) or HIGHLIGHT
		val x = slot.x
		val y = slot.y
		graphics.fill(x - 1, y - 1, x + 17, y, edge)
		graphics.fill(x - 1, y + 16, x + 17, y + 17, edge)
		graphics.fill(x - 1, y, x, y + 16, edge)
		graphics.fill(x + 16, y, x + 17, y + 16, edge)
		graphics.fill(x, y, x + 16, y + 16, (0x30 shl 24) or HIGHLIGHT)
	}

	private fun fit(text: String, width: Int): String {
		val font = client.font
		if (font.width(text) <= width) return text
		return font.plainSubstrByWidth(text, width - font.width("...")) + "..."
	}

	private fun line(text: String): Component = Component.literal(text)

	private fun tooltip(flip: Candidate): List<Component> = listOf(
		line((ItemRarity.codeForName(flip.name) ?: "§f") + flip.name),
		line("§7Buy order: §f${count(flip.units)}x §7at §e${Bazaar.coins(flip.buyAt)}"),
		line("§7Sell offer at §e${Bazaar.coins(flip.sellAt)}"),
		line("§7Margin: §f${Bazaar.coins(flip.margin)} §7each after tax §8(${String.format(Locale.US, "%.1f", flip.margin / flip.buyAt * 100)}%)"),
		line("§7Trades both ways: §f${Bazaar.short(flip.hourly)}§7 an hour"),
		line("§7Profit: §a+${Bazaar.short(flip.profit)}"),
		Component.empty(),
		line(if (guide.value) "§eClick to flip it!" else "§eClick to open it!"),
	)

	/**
	 * The panel, top to bottom: the week's summary, the guide's step, the flips on offer, and the
	 * guide's buttons. Draws when given [graphics], in the panel's own coordinates; returns its
	 * height either way, so it can be measured before it's placed.
	 */
	private fun panel(graphics: GuiGraphicsExtractor?, mouseX: Float, mouseY: Float, placed: MutableList<Hit>?): Int {
		val font = client.font
		val right = PANEL_WIDTH - PAD
		val inner = PANEL_WIDTH - PAD * 2
		var rowY = PAD

		fun rule() {
			rowY += 3
			graphics?.fill(PAD, rowY, right, rowY + 1, RULE)
			rowY += 6
		}

		fun button(label: String, edge: Int, primary: Boolean, action: () -> Unit): Int {
			val width = font.width(label) + 16
			val hit = Hit(edge - width, rowY, width, BUTTON_HEIGHT, action = action)
			val hovered = hit.has(mouseX, mouseY)
			graphics?.fill(hit.x, rowY, edge, rowY + BUTTON_HEIGHT, if (hovered) BUTTON_EDGE_HOVER else BUTTON_EDGE)
			graphics?.fill(hit.x + 1, rowY + 1, edge - 1, rowY + BUTTON_HEIGHT - 1, BUTTON_FILL)
			graphics?.text(font, label, hit.x + 8, rowY + 4, if (primary || hovered) TEXT else DIM, false)
			placed?.add(hit)
			return hit.x - 4
		}

		var rows = ROWS
		if (summary.value) {
			val used = BazaarFlips.summary(graphics, PAD, rowY, inner)
			if (used > LINE * 4) rows = ROWS_UNDER_SUMMARY
			rowY += used
		} else {
			graphics?.text(font, "Flips", PAD, rowY, DIM, false)
			rowY += LINE
		}

		hudStep()?.let { shown ->
			rule()
			graphics?.text(font, fit(shown.title, inner), PAD, rowY, if (shown.urgent) AMBER else TEXT, false)
			rowY += LINE
			shown.detail?.let {
				graphics?.text(font, fit(it, inner), PAD, rowY, DIM, false)
				rowY += LINE
			}
		}

		if (finder.value) {
			rule()
			val list = candidates.take(rows)
			if (list.isEmpty()) {
				graphics?.text(font, emptyReason, PAD, rowY, DIM, false)
				rowY += LINE
			}
			for (flip in list) {
				val hit = Hit(2, rowY - 1, PANEL_WIDTH - 4, LINE, tip = { tooltip(flip) }) { start(flip) }
				if (hit.has(mouseX, mouseY)) graphics?.fill(hit.x, hit.y, hit.x + hit.width, hit.y + LINE, HOVER)
				val profit = "+" + Bazaar.short(flip.profit)
				graphics?.text(font, fit(flip.name, inner - font.width(profit) - 6), PAD, rowY, TEXT, false)
				graphics?.text(font, profit, right - font.width(profit), rowY, AMBER, false)
				placed?.add(hit)
				rowY += LINE
			}
			rowY += 2
			val each = budgetEach()
			val footer = "${flips.size}/$slotCount flips" + if (each > 0) ", ${Bazaar.short(each)} for the next" else ""
			graphics?.text(font, fit(footer, inner), PAD, rowY, DIM, false)
			rowY += LINE
		}

		if (guide.value) {
			rule()
			val edge = button(if (running) "Stop" else "Start", right, true, ::toggle)
			if (running && target != null) button("Skip", edge, false, ::skip)
			rowY += BUTTON_HEIGHT
		}
		return rowY + PAD
	}

	/** Over a screen: the panel left of a Bazaar menu, or the amount over Hypixel's sign. */
	@JvmStatic
	fun renderOverlay(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		hits = emptyList()
		panelScreen = null
		// Drawn from what the last tick read, so nothing is searched per frame.
		if (!module.enabled || screen !== shownScreen) return
		if (screen is AbstractSignEditScreen) {
			drawSign(graphics)
			return
		}
		val menu = shownMenu
		if (menu == Menu.NONE || menu == Menu.OTHER || menu == Menu.SIGN) return
		if (!running && !finder.value) return
		val accessor = screen as? ContainerScreenAccessor ?: return

		val height = panel(null, -1f, -1f, null)
		val left = accessor.`cryptic$leftPos`()
		// Shrunk to fit beside the menu, and to fit the screen's height.
		val scale = minOf((left - GAP - 2).toFloat() / PANEL_WIDTH, (graphics.guiHeight() - 4f) / height).coerceIn(MIN_SCALE, 1f)
		val x = (left - GAP - PANEL_WIDTH * scale).coerceAtLeast(2f)
		val y = minOf(accessor.`cryptic$topPos`().toFloat(), graphics.guiHeight() - 2 - height * scale).coerceAtLeast(2f)
		val localX = (mouseX - x) / scale
		val localY = (mouseY - y) / scale
		val placed = ArrayList<Hit>()

		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		pose.scale(scale, scale)
		BazaarHistory.panelBox(graphics, 0, 0, PANEL_WIDTH, height)
		panel(graphics, localX, localY, placed)
		pose.popMatrix()

		hits = placed
		panelScreen = screen
		panelX = x
		panelY = y
		panelScale = scale
		panelHeight = height
		placed.firstOrNull { it.has(localX, localY) }?.tip?.let { graphics.setComponentTooltipForNextFrame(client.font, it(), mouseX, mouseY) }
	}

	/** A click on the panel. True takes it. */
	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double): Boolean {
		if (screen !== panelScreen) return false
		val localX = ((mouseX - panelX) / panelScale).toFloat()
		val localY = ((mouseY - panelY) / panelScale).toFloat()
		if (localX < 0 || localX >= PANEL_WIDTH || localY < 0 || localY >= panelHeight) return false
		hits.firstOrNull { it.has(localX, localY) }?.action?.invoke()
		return true
	}

	/** What the sign takes, between the sign and its Done button. */
	private fun drawSign(graphics: GuiGraphicsExtractor) {
		val shown = hudStep() ?: return
		if (shown.sign == null) return
		val font = client.font
		val centre = graphics.guiWidth() / 2f
		val top = graphics.guiHeight() / 4f + 112f
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(centre - font.width(shown.title) * 1.5f / 2f, top)
		pose.scale(1.5f, 1.5f)
		graphics.text(font, shown.title, 0, 0, AMBER, true)
		pose.popMatrix()
		shown.detail?.let { graphics.text(font, it, (centre - font.width(it) / 2f).toInt(), top.toInt() + 16, TEXT, true) }
	}

	// ---- Saving --------------------------------------------------------------------------------

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val type = object : TypeToken<LinkedHashMap<String, Flip>>() {}.type
			gson.fromJson<LinkedHashMap<String, Flip>>(Files.readString(file), type)?.let(flips::putAll)
		}.onFailure { Cryptic.LOGGER.warn("Could not read the flips under way", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(flips))
		}.onFailure { Cryptic.LOGGER.warn("Could not save the flips under way", it) }
	}

	fun describe(): List<String> = listOf(
		"Flipping: module ${module.enabled}, guide ${if (running) "running" else "stopped"}, menu $shownMenu, " +
			"step ${step?.let { "\"${it.title}\" (slot ${it.slot}, command ${it.command ?: "none"})" } ?: "none"}",
		"Flips under way: " + flips.entries.joinToString { "${it.value.units}x ${it.value.name} (${it.key})" }.ifEmpty { "none" } +
			"; setting up ${target?.let { "${it.units}x ${it.name}" } ?: "nothing"}",
		"Budget ${Bazaar.short(budgetEach())} for the next, room for ${room()}; best: " +
			candidates.take(5).joinToString { "${it.name} ${it.units}x +${Bazaar.short(it.profit)}" }.ifEmpty { emptyReason },
	)
}
