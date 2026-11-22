package imicro.cryptic.feature

import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.mixin.SignEditScreenAccessor
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import java.util.Locale

/**
 * Amount buttons beside a buy order's "How many do you want?" menu and an instant buy's menu. A
 * button clicks Hypixel's Custom Amount and writes its number into the sign that opens. Besides
 * your own amounts: the most your purse and the limit allow, what your visitors still need, and
 * for a few minutes after a buy order is cancelled, what of it hadn't filled.
 */
object BazaarPresets {
	private const val TITLE = "How many do you want?"

	/**
	 * "Enchanted Wild Rose ➜ Instant Buy". Hypixel cuts titles at 32 characters, so the end is often
	 * missing: "➜ Insta", "➜ I", or for a long name just "➜".
	 */
	private val ARROW_TITLE = Regex("""^(.*?)\s*➜\s*(\S*)""")

	private fun instantMatch(title: String): MatchResult? = ARROW_TITLE.find(title)?.takeIf { match ->
		val tail = match.groupValues[2]
		tail.startsWith("Instant") || tail.isNotEmpty() && "Instant".startsWith(tail) ||
			// Only the arrow left: an instant buy if the product page just open starts that way.
			tail.isEmpty() && match.groupValues[1].isNotEmpty() && Bazaar.lastProduct?.startsWith(match.groupValues[1].trim()) == true
	}
	private const val CUSTOM_AMOUNT = "Custom Amount"
	private const val ORDER_LIMIT = 71_680

	/** How long a relist's amount is offered, and a click waits for its sign. */
	private const val RELIST_MILLIS = 300_000L
	private const val SIGN_WAIT_MILLIS = 3_000L

	private val FORMATTING = Regex("§.")
	private val buyUpTo = Regex("""Buy up to ([\d,]+)x""")

	private val client: Minecraft get() = Minecraft.getInstance()

	/** A cancelled buy order being listed again: its product and how many hadn't filled. */
	private class Relist(val name: String, val amount: Int, val at: Long)

	private var relist: Relist? = null

	/** The amount a click is waiting to write into Hypixel's sign. */
	private var pending: Int? = null
	private var pendingAt = 0L

	/** Called when an order is cancelled, and again when the reopen item opens its product. */
	fun relisting(name: String, sell: Boolean, left: Int) {
		// A sell offer sells what you carry; only a buy order asks how many.
		relist = if (!sell && left > 0) Relist(name, left, System.currentTimeMillis()) else null
	}

	/** The order's name against the product page's, which a long title may have cut short. */
	private fun sameName(order: String, page: String?): Boolean {
		val shown = page?.trim()?.takeIf { it.isNotEmpty() } ?: return false
		return order.equals(shown, ignoreCase = true) || order.startsWith(shown, ignoreCase = true) && shown.length >= 12
	}

	private class Button(val label: String, val amount: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
		fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var buttons: List<Button> = emptyList()

	/** The last amount menu drawn, for /cryptic debug visitors (chat closes the menu). */
	private var lastMenuTitle: String? = null
	private var lastMenuProduct: String? = null
	private var lastMenuInstant = false

	fun describe(): List<String> = listOf(
		"Bazaar presets: module ${Bazaar.module.enabled}, presets ${Bazaar.presets.value}, last product page ${Bazaar.lastProduct ?: "none"}",
		lastMenuTitle?.let { "Last amount menu: \"${it.replace("§", "&")}\" (${if (lastMenuInstant) "instant buy" else "buy order"}) for ${lastMenuProduct ?: "no product"}" }
			?: "Last amount menu: none opened since the game started",
	)

	private fun title(screen: Any?) = (screen as? AbstractContainerScreen<*>)?.title?.string?.replace(FORMATTING, "")?.trim()

	private fun inAmountMenu(screen: Any?): Boolean {
		if (!Bazaar.module.enabled || !Bazaar.presets.value) return false
		val title = title(screen) ?: return false
		return title == TITLE || instantMatch(title) != null
	}

	/** An instant buy: its title names the product. */
	private fun instantProduct(screen: Any?): String? {
		val match = title(screen)?.let(::instantMatch) ?: return null
		// A name cut short is the product page's, whose title had room for it.
		val named = match.groupValues[1].trim()
		return Bazaar.lastProduct?.takeIf { named.isEmpty() || (it.startsWith(named) && it != named) } ?: named
	}

	private fun customAmountSlot(screen: AbstractContainerScreen<*>) = screen.menu.slots.firstOrNull {
		it.container !is Inventory && it.item.hoverName.string.replace(FORMATTING, "").trim() == CUSTOM_AMOUNT
	}

	/** Your amounts from the setting: whole numbers the Bazaar takes, smallest first. */
	private fun configured(): List<Int> = Bazaar.presetAmounts.value.split(',', ' ', ';')
		.mapNotNull { it.trim().replace("_", "").toIntOrNull() }
		.filter { it in 1..ORDER_LIMIT }
		.distinct()
		.sorted()

	/**
	 * The most you can buy: the limit Custom Amount names, and what your purse covers at a coin
	 * tenth over the top buy order (or, buying instantly, at the cheapest offer). Null until either
	 * is known.
	 */
	private fun maximum(screen: AbstractContainerScreen<*>, product: String?, instant: Boolean): Int? {
		val lore = customAmountSlot(screen)?.item?.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "") }
		val limit = lore?.firstNotNullOfOrNull { buyUpTo.find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() } ?: ORDER_LIMIT
		val price = unitPrice(product, instant)
		val purse = SkyblockLocation.purse()
		val affordable = if (price != null && purse != null && price > 0) (purse / price).toInt() else null
		return listOfNotNull(limit, affordable).minOrNull()?.takeIf { it > 0 }
	}

	/** What one costs: a coin tenth over the top buy order, or buying instantly, the cheapest offer. */
	private fun unitPrice(product: String?, instant: Boolean): Double? {
		val market = product?.let(BazaarMarket::idForName)?.let(BazaarMarket::product) ?: return null
		return if (instant) market.instantBuy.takeIf { it > 0 } else market.buyOrders.firstOrNull()?.price?.plus(0.1)
	}

	@JvmStatic
	fun render(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (!inAmountMenu(screen)) {
			buttons = emptyList()
			return
		}
		val chest = screen as AbstractContainerScreen<*>
		val accessor = screen as? ContainerScreenAccessor ?: return
		val now = System.currentTimeMillis()
		val instant = instantProduct(screen)
		val product = instant ?: Bazaar.lastProduct
		lastMenuTitle = chest.title.string
		lastMenuProduct = product
		lastMenuInstant = instant != null
		val sections = ArrayList<Section>()
		// A relist is a buy order again, never an instant buy.
		if (instant == null) relist?.takeIf { now - it.at < RELIST_MILLIS && sameName(it.name, product) }?.let {
			sections += Section("§aRelisting", SUGGESTED_FILL, listOf("§aLast order" to it.amount))
		}
		Farming.presetsFor(product).takeIf { it.isNotEmpty() }?.let {
			sections += Section("§2Visitors", SUGGESTED_FILL, it)
		}
		val max = maximum(chest, product, instant != null)
		val amounts = listOfNotNull(max?.let { "§eMax" to it }) + configured().filter { max == null || it <= max }.map { "" to it }
		if (amounts.isNotEmpty()) sections += Section("§6Amount", AMOUNT_FILL, amounts)
		if (sections.isEmpty()) {
			buttons = emptyList()
			return
		}

		val font = client.font
		val rowHeight = font.lineHeight + 5
		val headingHeight = font.lineHeight + 2
		val rowWidth = maxOf(
			sections.flatMap { it.entries }.maxOf { (label, amount) -> font.width(label) + font.width(amountText(amount)) } + 20,
			sections.maxOf { font.width(it.heading) },
		)
		val panelHeight = PADDING * 2 - SECTION_GAP +
			sections.sumOf { headingHeight + it.entries.size * (rowHeight + ROW_GAP) - ROW_GAP + SECTION_GAP }
		val panelX = accessor.`cryptic$leftPos`() + accessor.`cryptic$imageWidth`() + GAP
		val panelY = accessor.`cryptic$topPos`()
		BazaarHistory.panelBox(graphics, panelX, panelY, rowWidth + PADDING * 2, panelHeight)

		val x = panelX + PADDING
		var y = panelY + PADDING
		val placed = ArrayList<Button>()
		var hoveredButton: Button? = null
		for (section in sections) {
			graphics.text(font, section.heading, x, y, -1)
			y += headingHeight
			for ((label, amount) in section.entries) {
				val button = Button(label, amount, x, y, rowWidth, rowHeight)
				val hovered = button.contains(mouseX.toDouble(), mouseY.toDouble())
				if (hovered) hoveredButton = button
				graphics.fill(x, y, x + rowWidth, y + rowHeight, if (hovered) ROW_HOVER else section.fill)
				graphics.text(font, label, x + 5, y + 3, -1)
				val text = amountText(amount)
				graphics.text(font, text, x + rowWidth - 5 - font.width(text), y + 3, -1)
				placed += button
				y += rowHeight + ROW_GAP
			}
			y += SECTION_GAP - ROW_GAP
		}
		buttons = placed

		val hovered = hoveredButton ?: return
		val each = unitPrice(product, instant != null) ?: return
		val cost = hovered.amount * each
		val purse = SkyblockLocation.purse()
		graphics.setComponentTooltipForNextFrame(
			font,
			listOf(
				Component.literal("§f${String.format(Locale.US, "%,d", hovered.amount)}x §7for ${if (purse != null && cost > purse) "§c" else "§6"}${Bazaar.coins(cost)} coins"),
				Component.literal("§8${Bazaar.coins(each)} each, ${if (instant != null) "the cheapest sell offer" else "a tenth over the top buy order"}"),
			),
			mouseX, mouseY,
		)
	}

	/** A group of buttons under a heading; suggestions (a relist, visitors) are tinted apart from your amounts. */
	private class Section(val heading: String, val fill: Int, val entries: List<Pair<String, Int>>)

	// The price history panel's look: same gap from the menu, padding and edge.
	private const val GAP = 6
	private const val PADDING = 6
	private const val ROW_GAP = 2
	private const val SECTION_GAP = 6
	private const val AMOUNT_FILL = 0x18FFFFFF
	private const val ROW_HOVER = 0x40FFFFFF
	private const val SUGGESTED_FILL = 0x2855FF55

	private fun amountText(amount: Int) = "§f" + String.format(Locale.US, "%,d", amount)

	/** A click on a button: Custom Amount is clicked, and the sign it opens is answered. True takes it. */
	@JvmStatic
	fun handleMouseClick(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double): Boolean {
		if (!inAmountMenu(screen)) return false
		val button = buttons.firstOrNull { it.contains(mouseX, mouseY) } ?: return false
		val slot = customAmountSlot(screen) ?: return true
		val player = client.player ?: return true
		pending = button.amount
		pendingAt = System.currentTimeMillis()
		if (button.label.startsWith("§aLast order")) relist = null
		client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
		client.gameMode?.handleContainerInput(screen.menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
		return true
	}

	/** Writes a waiting amount into Hypixel's sign as soon as it opens; closing it sends it. */
	fun tick() {
		val amount = pending ?: return
		if (System.currentTimeMillis() - pendingAt > SIGN_WAIT_MILLIS) {
			pending = null
			return
		}
		val screen = client.gui.screen() as? AbstractSignEditScreen ?: return
		pending = null
		(screen as SignEditScreenAccessor).`cryptic$messages`()[0] = amount.toString()
		client.gui.setScreen(null)
	}
}
