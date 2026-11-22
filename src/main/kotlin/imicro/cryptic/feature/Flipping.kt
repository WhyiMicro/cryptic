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
import imicro.cryptic.skyblock.BazaarRecipes
import imicro.cryptic.skyblock.ItemRarity
import imicro.cryptic.skyblock.NpcShops
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

	/** A way to flip. [title] names it in the settings, [tab] on the panel. */
	enum class Kind(val title: String, val tab: String) {
		ORDER("Order flips", "Orders"),
		NPC("NPC flips", "NPC"),
		SHOP("Shop flips", "Shops"),
		CRAFT("Craft flips", "Crafts"),
	}

	@JvmField
	val kind = DropdownModuleSetting(
		id = "flip_kind",
		label = "Flips shown",
		options = Kind.entries.map { it.title },
		defaultIndex = Kind.ORDER.ordinal,
		description = "Which flips the finder lists and the guide picks. Order flips buy with a buy order and sell with a sell offer. " +
			"NPC flips buy with a buy order under what NPCs pay, then sell to an NPC. Shop flips buy from an NPC's shop (640 of an item a day) " +
			"and sell on the Bazaar. Craft flips buy a recipe's ingredients with buy orders, craft, and sell what's made. " +
			"The panel's tabs change this too.",
	)

	private val shownKind: Kind get() = Kind.entries.getOrElse(kind.selectedIndex) { Kind.ORDER }

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
		settings = listOf(finderSection, finder, kind, slots, budget, fillWithin, maxMargin, guideSection, guide, forget, summarySection, summary, goal, resetGoal, hudSection, flipHud),
	)

	private val client: Minecraft get() = Minecraft.getInstance()
	private val FORMATTING = Regex("§.")

	fun initialize() = load()

	// ---- Finding flips -------------------------------------------------------------------------

	/**
	 * A flip as it stands now: [units] bought at [buyAt] and sold at [sellAt], [margin] each after
	 * the sell tax. [hourly] is how many trade in an hour on the side that has to fill. An NPC flip
	 * sells at what NPCs pay; a shop flip buys from [shop]; a craft flip buys [needs] (each
	 * ingredient's total), at [buyAt] a unit made, and may need [requires] unlocked.
	 */
	class Candidate(
		val kind: Kind, val id: String, var name: String, val units: Int,
		val buyAt: Double, val sellAt: Double, val margin: Double, val hourly: Double,
		val shop: NpcShops.Offer? = null,
		val needs: Map<String, Int> = emptyMap(),
		val requires: String? = null,
	) {
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
	 * A flip of [product] the [kind] way, sized to what [each] coins buy, [room] holds, and a share
	 * of what trades within the fill time. Orders go a tenth over the best buy order and a tenth
	 * under the cheapest sell offer.
	 */
	private fun evaluate(product: BazaarMarket.Product, kind: Kind, each: Double, room: Int, floor: Double): Candidate? {
		// Books, essences and shards aren't carried or named like other products.
		if (UNCARRIED.any { product.id.startsWith(it) }) return null
		if (kind == Kind.CRAFT) return craft(product, each, room, floor)
		val keep = 1 - Bazaar.tax.value / 100
		val flow = fillWithin.value / 60 * FLOW_SHARE
		val buyAt: Double
		val sellAt: Double
		val margin: Double
		val hourly: Double
		var limit = ORDER_LIMIT.toDouble()
		var shop: NpcShops.Offer? = null
		when (kind) {
			Kind.ORDER -> {
				buyAt = (product.buyOrders.firstOrNull()?.price ?: return null) + 0.1
				sellAt = (product.sellOffers.firstOrNull()?.price ?: return null) - 0.1
				margin = sellAt * keep - buyAt
				if (margin / buyAt * 100 > maxMargin.value) return null
				hourly = minOf(product.boughtWeekly, product.soldWeekly) / 168.0
			}
			// What NPCs pay doesn't move, so only the buy order has to fill.
			Kind.NPC -> {
				sellAt = BazaarMarket.npcSellPrice(product.id) ?: return null
				buyAt = (product.buyOrders.firstOrNull()?.price ?: return null) + 0.1
				margin = sellAt - buyAt
				hourly = product.soldWeekly / 168.0
				limit = minOf(limit, NpcShops.sellRoom() / sellAt)
			}
			Kind.CRAFT -> return null
			Kind.SHOP -> {
				shop = NpcShops.cheapest(product.id) ?: return null
				buyAt = shop.price
				sellAt = (product.sellOffers.firstOrNull()?.price ?: return null) - 0.1
				margin = sellAt * keep - buyAt
				hourly = product.boughtWeekly / 168.0
				limit = NpcShops.buyRoom(product.id).toDouble()
			}
		}
		if (margin <= 0 || buyAt <= 0) return null
		val units = minOf(each / buyAt, hourly * flow, room.toDouble(), limit).toInt()
		if (units < 1 || units * margin < floor) return null
		return Candidate(kind, product.id, "", units, buyAt, sellAt, margin, hourly, shop)
	}

	/**
	 * The best recipe for [product]: buy orders for every ingredient, then a sell offer for what's
	 * made. Sized in whole crafts, to what each ingredient's trading and your inventory allow.
	 */
	private fun craft(product: BazaarMarket.Product, each: Double, room: Int, floor: Double): Candidate? {
		val sellAt = (product.sellOffers.firstOrNull()?.price ?: return null) - 0.1
		val earned = sellAt * (1 - Bazaar.tax.value / 100)
		val flow = fillWithin.value / 60 * FLOW_SHARE
		val hourly = product.boughtWeekly / 168.0
		var best: Candidate? = null
		recipes@ for (recipe in BazaarRecipes.of(product.id)) {
			if (recipe.makes < 1 || recipe.needs.isEmpty()) continue
			var cost = 0.0
			var items = 0
			var crafts = hourly * flow / recipe.makes
			for ((id, amount) in recipe.needs) {
				val part = BazaarMarket.product(id) ?: continue@recipes
				cost += ((part.buyOrders.firstOrNull()?.price ?: continue@recipes) + 0.1) * amount
				items += amount
				crafts = minOf(crafts, part.soldWeekly / 168.0 * flow / amount, ORDER_LIMIT.toDouble() / amount)
			}
			val unit = cost / recipe.makes
			val margin = earned - unit
			if (margin <= 0 || margin / unit * 100 > maxMargin.value) continue
			// The ingredients have to be carried to be crafted.
			val count = minOf(crafts, each / cost, room.toDouble() / items).toInt()
			val units = count * recipe.makes
			if (count < 1 || units * margin < floor) continue
			if (best != null && best.profit >= units * margin) continue
			best = Candidate(
				Kind.CRAFT, product.id, "", units, unit, sellAt, margin, hourly,
				needs = recipe.needs.mapValues { it.value * count }, requires = recipe.requires,
			)
		}
		return best
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
			val shown = shownKind
			// A shop sells 640 a day, so its flips are small whatever the purse.
			val floor = if (shown == Kind.SHOP) 1_000.0 else maxOf(1_000.0, each * 0.001)
			val pool = when (shown) {
				Kind.SHOP -> NpcShops.ids.mapNotNull(BazaarMarket::product)
				Kind.CRAFT -> BazaarRecipes.ids.mapNotNull(BazaarMarket::product)
				else -> BazaarMarket.products.values
			}
			for (candidate in pool.mapNotNull { evaluate(it, shown, each, room, floor) }.sortedByDescending { it.profit }) {
				if (found.size >= ROWS) break
				if (candidate.id in taken || candidate.id in skipped) continue
				candidate.name = nameFor(candidate.id) ?: continue
				// A craft whose product is already on you would look made before it's begun.
				if (shown == Kind.CRAFT && (carried(candidate.id) > 0 || candidate.needs.keys.any { nameFor(it) == null || it in taken })) continue
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
		val fresh = BazaarMarket.product(old.id)?.let { evaluate(it, old.kind, each, room, 0.0) }
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
	private class Flip(
		var name: String = "", var units: Int = 0, var buyAt: Double = 0.0, var startedAt: Long = 0,
		/** A [Kind]'s name. */
		var kind: String = Kind.ORDER.name,
		/** A craft flip's ingredients, each one's total. */
		var needs: MutableMap<String, Int>? = null,
		/** A craft flip's product has been crafted: no more ingredients are bought. */
		var made: Boolean = false,
	) {
		/** The ingredients still being gathered. */
		val parts: Set<String> get() = if (made) emptySet() else needs?.keys ?: emptySet()

		val crafting: Boolean get() = kind == Kind.CRAFT.name && !made
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
			val parts = flip.parts
			if (own.any { order -> idOf(order.name).let { it == id || it in parts } } || carried(id) > 0 || parts.any { carried(it) > 0 }) {
				flip.idleAt = 0L
				return@removeIf false
			}
			if (flip.idleAt == 0L) flip.idleAt = now
			// Bought items that went elsewhere (a chest, an NPC) would hold their slot for ever.
			(!flip.crafting && now - flip.startedAt > SETTLE_MILLIS && bought(id) == 0L) || now - flip.idleAt > IDLE_MILLIS
		}
		if (flips.size != before) save()
	}

	/** The target's buy order is out, or its items are bought and the shop left: the flip is under way. */
	private fun settle(own: List<Bazaar.Order>) {
		val placed = target ?: return
		val under = when (placed.kind) {
			Kind.SHOP -> shownMenu != Menu.SHOP && carried(placed.id) > 0
			Kind.CRAFT -> own.any { !it.sell && idOf(it.name) in placed.needs }
			else -> own.any { !it.sell && idOf(it.name) == placed.id }
		}
		if (!under) return
		flips[placed.id] = Flip(
			placed.name, placed.units, placed.buyAt, System.currentTimeMillis(), placed.kind.name,
			needs = placed.needs.takeIf { it.isNotEmpty() }?.let(::HashMap),
		)
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
			if (order.filled >= order.amount || !flipping(idOf(order.name))) return@firstOrNull false
			when (Bazaar.statusFor(order)) {
				Bazaar.Status.EXPIRED -> true
				Bazaar.Status.UNDERCUT -> wait > 0 && now - (undercutSince[orderKey(order)] ?: now) >= wait
				else -> false
			}
		}
	}

	/** A flip whose bought items are on you, waiting for their sell offer. */
	private fun selling(): Map.Entry<String, Flip>? = flips.entries.firstOrNull { (id, flip) -> waiting(id, flip) }

	// A shop flip's items are its own once the shop is left, whatever chat said of buying them; a
	// craft flip's were never bought at all.
	private fun waiting(id: String, flip: Flip) =
		carried(id) > 0 && (flip.kind == Kind.SHOP.name || flip.kind == Kind.CRAFT.name || bought(id) > 0)

	/** Whether [id] is a flip's product, or an ingredient one is still gathering. */
	private fun flipping(id: String?) = id != null && (id in flips || flips.values.any { id in it.parts })

	/** How many of each ingredient [needs] still lacks: neither carried nor on order. */
	private fun lacking(needs: Map<String, Int>, own: List<Bazaar.Order>): Pair<String, Int>? =
		needs.entries.firstNotNullOfOrNull { (id, need) ->
			val lack = need - carried(id) - own.filter { !it.sell && idOf(it.name) == id }.sumOf { it.amount }
			if (lack > 0) id to lack else null
		}

	/** A craft flip whose ingredients are all on you. */
	private fun craftable(): Map.Entry<String, Flip>? =
		flips.entries.firstOrNull { (_, flip) -> flip.crafting && flip.needs?.all { (id, need) -> carried(id) >= need } == true }

	/** Crafted: the product is on you or on offer. What the ingredients cost goes with it. */
	private fun noteCrafted(own: List<Bazaar.Order>) {
		for ((id, flip) in flips) {
			if (!flip.crafting || (carried(id) == 0 && own.none { it.sell && idOf(it.name) == id })) continue
			flip.made = true
			BazaarFlips.crafted(flip.name, flip.units.toLong(), flip.needs.orEmpty().map { (part, need) -> { name: String -> idOf(name) == part } to need.toLong() })
			save()
		}
	}

	/** The buy order to place next: for a craft under way, for the craft being set up, or the target's own. */
	private class Buy(val id: String, val name: String, val units: Int, val plan: String)

	private var buy: Buy? = null

	private fun nextBuy(own: List<Bazaar.Order>): Buy? {
		for (flip in flips.values) {
			if (!flip.crafting) continue
			val (id, lack) = lacking(flip.needs.orEmpty(), own) ?: continue
			return Buy(id, nameFor(id) ?: continue, lack, "to craft ${flip.name}")
		}
		val placing = target ?: return null
		return when (placing.kind) {
			Kind.SHOP -> null
			Kind.CRAFT -> lacking(placing.needs, own)?.let { (id, lack) ->
				Buy(id, nameFor(id) ?: return null, lack, "to craft ${placing.name}, +${Bazaar.short(placing.profit)}")
			}
			else -> Buy(placing.id, placing.name, placing.units, "for +${Bazaar.short(placing.profit)}")
		}
	}

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

	private enum class Menu { NONE, MAIN, PRODUCT, AMOUNT, BUY_PRICE, BUY_CONFIRM, SELL_PRICE, SELL_CONFIRM, ORDERS, OPTIONS, SIGN, SHOP, CRAFT, OTHER }

	/** Menus in the middle of placing or cancelling an order, or of buying from a shop, where the plan isn't changed. */
	private val FLOW = setOf(Menu.AMOUNT, Menu.SIGN, Menu.BUY_PRICE, Menu.BUY_CONFIRM, Menu.SELL_PRICE, Menu.SELL_CONFIRM, Menu.OPTIONS, Menu.SHOP)

	/** Bazaar menus, which the panel sits beside. */
	private fun trading(menu: Menu) = menu != Menu.NONE && menu != Menu.OTHER && menu != Menu.SIGN && menu != Menu.SHOP && menu != Menu.CRAFT

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
			// A shop's Sell Item slot shows what was last sold once something has been.
			title == "Craft Item" -> Menu.CRAFT
			title == "Trades" || title in NpcShops.npcs || chest.named("Sell Item") != null -> Menu.SHOP
			else -> Menu.OTHER
		}
	}

	private fun count(amount: Int) = String.format(Locale.US, "%,d", amount)

	private fun icon(name: String): ItemStack? = BazaarFlips.iconOf(name) ?: Farming.iconFor(name)

	private fun amountFor(order: Buy) = minOf(order.units, amountCap)

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

	/** "Fishing Merchant, Hub: 20 each". */
	private fun shopLine(shop: NpcShops.Offer) =
		shop.npc + (if (shop.where.isEmpty()) "" else ", ${shop.where}") + ": ${Bazaar.coins(shop.price)} each"

	/**
	 * In an NPC's shop: an NPC flip's items are sold by clicking them, and a shop flip's are bought
	 * from the shop's own slot. Null when the shop has nothing to do with a flip.
	 */
	private fun shopStep(chest: AbstractContainerScreen<*>, buying: Candidate?): Step? {
		flips.entries.firstOrNull { (id, flip) -> flip.kind == Kind.NPC.name && waiting(id, flip) }?.let { (id, flip) ->
			val slot = chest.carrying(id) ?: return@let
			return Step("Click your ${flip.name}", "Sells ${count(carried(id))}x to the NPC", icon = slot.item, slot = slot.index)
		}
		if (buying == null || buying.kind != Kind.SHOP) return null
		val left = buying.units - carried(buying.id)
		if (left <= 0) return Step("That's all ${count(buying.units)}x", "Close the shop to sell them on the Bazaar", urgent = false, icon = icon(buying.name))
		val sold = chest.menu.slots.firstOrNull { slot ->
			slot.container !is Inventory && !slot.item.isEmpty && (SkyblockItem.id(slot.item) == buying.id || plain(slot.item) == buying.name)
		} ?: return Step("Buy ${count(left)}x ${buying.name}", "Not sold here. " + (buying.shop?.let(::shopLine) ?: ""), urgent = false, icon = icon(buying.name))
		return Step("Buy ${count(left)}x ${buying.name}", "Right-click it for stacks", icon = sold.item, slot = sold.index)
	}

	/** The prices a shop's menu shows, which put the shipped table right. Read once per menu. */
	private fun learn(chest: AbstractContainerScreen<*>) {
		if (chest === learnedFrom) return
		val npc = chest.title.string.replace(FORMATTING, "").trim()
		if (npc !in NpcShops.npcs) return
		val stocked = chest.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }
		if (stocked.isEmpty()) return
		learnedFrom = chest
		for (slot in stocked) {
			val id = SkyblockItem.id(slot.item).takeIf { it in NpcShops.ids } ?: idOf(plain(slot.item))?.takeIf { it in NpcShops.ids } ?: continue
			val lines = slot.item.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "").trim() } ?: continue
			// "Cost", then "20 Coins" on the line under it.
			val coins = lines.getOrNull(lines.indexOf("Cost") + 1)?.takeIf { "Cost" in lines }
				?.let { COST.find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() } ?: continue
			NpcShops.learn(id, npc, coins / slot.item.count.coerceAtLeast(1))
		}
	}

	private var learnedFrom: Screen? = null
	private val COST = Regex("""^([\d,.]+) Coins?$""")

	private fun compute(menu: Menu, chest: AbstractContainerScreen<*>?, own: List<Bazaar.Order>): Step {
		val buying = target
		val order = buy
		if (chest != null) when (menu) {
			Menu.AMOUNT -> if (order != null) {
				val custom = chest.named(CUSTOM_AMOUNT)
				custom?.item?.get(DataComponents.LORE)?.lines()
					?.firstNotNullOfOrNull { buyUpTo.find(it.string.replace(FORMATTING, ""))?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
					?.let { amountCap = it }
				return Step("Click Custom Amount", "${count(amountFor(order))}x ${order.name}", icon = icon(order.name), slot = custom?.index ?: -1)
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
			return if (signIsAmount && order != null) {
				val amount = amountFor(order).toString()
				Step("Amount: $amount", if (signs.selectedIndex == SIGN_SHOW) "Type it on the first line" else "Typed in: press Done", sign = amount)
			} else Step("Sign", urgent = false)
		}

		if (chest != null && menu == Menu.SHOP) shopStep(chest, buying)?.let { return it }

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
			if (flip.kind == Kind.NPC.name) {
				return Step("Sell ${units}x ${flip.name} to an NPC", "Any NPC shop, or Trades in the SkyBlock menu", icon = icon(flip.name))
			}
			if (chest != null && menu == Menu.PRODUCT && Bazaar.openProduct == id) {
				return Step("Click Create Sell Offer", "${units}x ${flip.name}", icon = icon(flip.name), slot = chest.named("Create Sell Offer")?.index ?: -1)
			}
			// In a Bazaar menu, clicking an item of yours opens its page. In a shop it would sell it.
			if (chest != null && trading(menu)) chest.carrying(id)?.let { slot ->
				return Step("Click your ${flip.name}", "Opens its page, to sell ${units}x", icon = slot.item, slot = slot.index)
			}
			return typed("bz ${flip.name}", "Sell ${units}x", icon(flip.name))
		}

		craftable()?.let { (_, flip) ->
			val made = "${count(flip.units)}x ${flip.name}"
			if (chest == null || menu != Menu.CRAFT) return typed("craft", "Craft $made", icon(flip.name))
			// The quick-craft slots offer what your inventory can make.
			chest.named(flip.name)?.let { slot -> return Step("Click ${flip.name}", "Crafts it. Make $made in all", icon = slot.item, slot = slot.index) }
			return Step("Craft $made", "Lay its recipe out in the grid", icon = icon(flip.name))
		}

		relistable(own)?.let { stale ->
			val why = if (Bazaar.statusFor(stale) == Bazaar.Status.EXPIRED) "expired" else "undercut"
			if (chest == null || menu != Menu.ORDERS) return toOrders(menu, chest, "Relist ${stale.name}, $why", icon(stale.name))
			chest.menu.slots.firstOrNull { slot -> slot.container !is Inventory && Bazaar.orderOf(slot.item)?.let { same(it, stale) } == true }?.let { slot ->
				return Step("Click ${stale.name}", "It's $why: cancel it, then relist", icon = slot.item, slot = slot.index)
			}
		}

		if (buying != null && buying.kind == Kind.SHOP) {
			val left = (buying.units - carried(buying.id)).coerceAtLeast(1)
			return Step("Buy ${count(left)}x ${buying.name}", buying.shop?.let(::shopLine), icon = icon(buying.name))
		}

		if (order != null) {
			val plan = "${count(order.units)}x ${order.plan}"
			if (chest != null && menu == Menu.PRODUCT && Bazaar.openProduct == order.id) {
				return Step("Click Create Buy Order", plan, icon = icon(order.name), slot = chest.named("Create Buy Order")?.index ?: -1)
			}
			if (chest != null && menu == Menu.MAIN) chest.named(order.name)?.let { slot ->
				return Step("Click ${order.name}", plan, icon = slot.item, slot = slot.index)
			}
			return typed("bz ${order.name}", "Buy $plan", icon(order.name))
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
		// A shop flip starts at the NPC, and a craft with its first ingredient.
		if (candidate.kind == Kind.ORDER || candidate.kind == Kind.NPC) send("bz ${candidate.name}")
	}

	/** The panel's tabs. */
	private fun show(which: Kind) {
		if (which == shownKind) return
		clickSound()
		kind.selectedIndex = which.ordinal
		candidates = emptyList()
		rankedAt = 0L
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
		buy = null
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
		val trading = trading(menu)
		if (menu == Menu.SHOP) learn(screen as AbstractContainerScreen<*>)
		if (!running && !(finder.value && trading)) {
			idle()
			return
		}
		BazaarMarket.refresh(if (trading) 5_000L else 15_000L)
		val own = own()
		prune(own)
		// Before the ranking looks at the target again: a shop flip bought in full has no more to buy.
		settle(own)
		noteCrafted(own)
		rank()
		if (!running) {
			idle()
			return
		}

		val now = System.currentTimeMillis()
		if (menu == Menu.AMOUNT) amountMenuAt = now
		noteUndercut(own)
		if (target == null && flips.size < slotCount && menu !in FLOW) {
			target = candidates.firstOrNull()
			amountCap = ORDER_LIMIT
		}
		val ordering = nextBuy(own)
		// Another product's Custom Amount has its own limit.
		if (ordering?.id != buy?.id) amountCap = ORDER_LIMIT
		buy = ordering

		if (menu == Menu.SIGN) {
			if (screen !== seenSign) {
				seenSign = screen
				// Custom Amount's sign opens straight from the amount menu.
				signIsAmount = buy != null && now - amountMenuAt < 3_000L
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
		val amount = amountFor(buy ?: return)
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

	private fun tooltip(flip: Candidate): List<Component> {
		val title = line((ItemRarity.codeForName(flip.name) ?: "§f") + flip.name)
		val percent = String.format(Locale.US, "%.1f", flip.margin / flip.buyAt * 100)
		val profit = line("§7Profit: §6+${Bazaar.short(flip.profit)}")
		val order = line("§7Buy order: §f${count(flip.units)}x §7at §e${Bazaar.coins(flip.buyAt)}")
		val click = line(if (guide.value) "§eClick to flip it!" else "§eClick to open it!")
		return when (flip.kind) {
			Kind.ORDER -> listOf(
				title, order,
				line("§7Sell offer at §e${Bazaar.coins(flip.sellAt)}"),
				line("§7Margin: §f${Bazaar.coins(flip.margin)} §7each after tax §8($percent%)"),
				line("§7Trades both ways: §f${Bazaar.short(flip.hourly)}§7 an hour"),
				profit, Component.empty(), click,
			)
			Kind.NPC -> listOf(
				title, order,
				line("§7NPCs pay §e${Bazaar.coins(flip.sellAt)} §7each"),
				line("§7Margin: §f${Bazaar.coins(flip.margin)} §7each §8($percent%)"),
				line("§7Sold into buy orders: §f${Bazaar.short(flip.hourly)}§7 an hour"),
				line("§7NPCs still pay you §f${Bazaar.short(NpcShops.sellRoom())} §7today"),
				profit, Component.empty(), click,
			)
			Kind.SHOP -> listOf(
				title,
				line("§7Buy §f${count(flip.units)}x §7from §f${flip.shop?.let(::shopLine) ?: "an NPC"}"),
				line("§7Sell offer at §e${Bazaar.coins(flip.sellAt)}"),
				line("§7Margin: §f${Bazaar.coins(flip.margin)} §7each after tax"),
				line("§7Bought off sell offers: §f${Bazaar.short(flip.hourly)}§7 an hour"),
				line("§7NPCs still sell you §f${NpcShops.buyRoom(flip.id)} §7today"),
				profit, Component.empty(),
				line(if (guide.value) "§eClick to flip it!" else "§eBuy it at the NPC's shop."),
			)
			Kind.CRAFT -> listOf(title, line("§7Buy orders for §f${count(flip.units)}x§7:")) +
				flip.needs.map { (id, amount) ->
					val price = BazaarMarket.product(id)?.buyOrders?.firstOrNull()?.price?.plus(0.1) ?: 0.0
					line("§8- §f${count(amount)}x ${nameFor(id) ?: id} §7at §e${Bazaar.coins(price)}")
				} +
				listOfNotNull(
					line("§7Costs §e${Bazaar.coins(flip.buyAt)} §7each to craft"),
					line("§7Sell offer at §e${Bazaar.coins(flip.sellAt)}"),
					line("§7Margin: §f${Bazaar.coins(flip.margin)} §7each after tax §8($percent%)"),
					line("§7Bought off sell offers: §f${Bazaar.short(flip.hourly)}§7 an hour"),
					flip.requires?.let { line("§7Recipe needs §f$it") },
					profit, Component.empty(),
					line(if (guide.value) "§eClick to flip it!" else "§eBuy the ingredients, then craft."),
				)
		}
	}

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
			var tabX = PAD
			for (tab in Kind.entries) {
				val width = font.width(tab.tab)
				val hit = Hit(tabX - 3, rowY - 2, width + 6, LINE + 2) { show(tab) }
				val active = tab == shownKind
				graphics?.text(font, tab.tab, tabX, rowY, if (active || hit.has(mouseX, mouseY)) TEXT else DIM, false)
				if (active) graphics?.fill(tabX, rowY + 9, tabX + width - 1, rowY + 10, AMBER)
				placed?.add(hit)
				tabX += width + 8
			}
			rowY += LINE + 3
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
		// Beside Bazaar menus; beside a shop only while the guide has something to say there.
		if (!trading(menu) && !((menu == Menu.SHOP || menu == Menu.CRAFT) && running)) return
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
		"Flips under way: " + flips.entries.joinToString { "${it.value.units}x ${it.value.name} (${it.key}, ${it.value.kind})" }.ifEmpty { "none" } +
			"; setting up ${target?.let { "${it.units}x ${it.name} (${it.kind})" } ?: "nothing"}",
		"Showing ${shownKind.title}; budget ${Bazaar.short(budgetEach())} for the next, room for ${room()}; best: " +
			candidates.take(5).joinToString { "${it.name} ${it.units}x +${Bazaar.short(it.profit)}" }.ifEmpty { emptyReason },
	)
}
