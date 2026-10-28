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
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import java.util.Locale

/**
 * Amount buttons beside a buy order's "How many do you want?" menu and an instant buy's menu. A
 * button clicks Hypixel's Custom Amount and writes its number into the sign that opens. Besides
 * your own amounts: the most your purse and the limit allow, what your visitors still need, and
 * when relisting a cancelled order through the reopen item, what of it hadn't filled.
 */
object BazaarPresets {
	private const val TITLE = "How many do you want?"

	/**
	 * "Enchanted Wild Rose ➜ Instant Buy". Hypixel cuts titles at 32 characters, so the end is often
	 * missing ("➜ Instan").
	 */
	private val INSTANT_TITLE = Regex("""^(.*?)\s*➜\s*Instan""")
	private const val CUSTOM_AMOUNT = "Custom Amount"
	private const val ORDER_LIMIT = 71_680

	/** How long a relist's amount is offered, and a click waits for its sign. */
	private const val RELIST_MILLIS = 120_000L
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

	/** Called when the reopen item opens a cancelled order's product again. */
	fun relisting(name: String, sell: Boolean, left: Int) {
		// A sell offer sells what you carry; only a buy order asks how many.
		relist = if (!sell && left > 0) Relist(name, left, System.currentTimeMillis()) else null
	}

	private class Button(val label: String, val amount: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
		fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var buttons: List<Button> = emptyList()

	private fun title(screen: Any?) = (screen as? AbstractContainerScreen<*>)?.title?.string?.replace(FORMATTING, "")?.trim()

	private fun inAmountMenu(screen: Any?): Boolean {
		if (!Bazaar.module.enabled || !Bazaar.presets.value) return false
		val title = title(screen) ?: return false
		return title == TITLE || INSTANT_TITLE.containsMatchIn(title)
	}

	/** An instant buy: its title names the product. */
	private fun instantProduct(screen: Any?): String? {
		val match = title(screen)?.let { INSTANT_TITLE.find(it) } ?: return null
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
		val market = product?.let(BazaarMarket::idForName)?.let(BazaarMarket::product)
		val price = if (instant) market?.instantBuy?.takeIf { it > 0 } else market?.buyOrders?.firstOrNull()?.price?.plus(0.1)
		val purse = SkyblockLocation.purse()
		val affordable = if (price != null && purse != null && price > 0) (purse / price).toInt() else null
		return listOfNotNull(limit, affordable).minOrNull()?.takeIf { it > 0 }
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
		val sections = ArrayList<Section>()
		// A relist is a buy order again, never an instant buy.
		if (instant == null) relist?.takeIf { now - it.at < RELIST_MILLIS && it.name == product }?.let {
			sections += Section("§aRelisting", SUGGESTED_ACCENT, SUGGESTED_FILL, listOf("§aLast order" to it.amount))
		}
		Farming.presetsFor(product).takeIf { it.isNotEmpty() }?.let {
			sections += Section("§2Visitors", SUGGESTED_ACCENT, SUGGESTED_FILL, it)
		}
		val max = maximum(chest, product, instant != null)
		val amounts = listOfNotNull(max?.let { "§eMax" to it }) + configured().filter { max == null || it <= max }.map { "" to it }
		if (amounts.isNotEmpty()) sections += Section("§7Amount", 0x60FFFFFF, 0xC0101418.toInt(), amounts)
		if (sections.isEmpty()) {
			buttons = emptyList()
			return
		}

		val font = client.font
		val height = font.lineHeight + 6
		val width = sections.flatMap { it.entries }.maxOf { (label, amount) -> font.width(label) + font.width(amountText(amount)) } + 20
		val x = accessor.`cryptic$leftPos`() + accessor.`cryptic$imageWidth`() + 6
		var y = accessor.`cryptic$topPos`() + 4
		val placed = ArrayList<Button>()
		for (section in sections) {
			graphics.text(font, section.heading, x, y, -1)
			y += font.lineHeight + 3
			for ((label, amount) in section.entries) {
				val button = Button(label, amount, x, y, width, height)
				val hovered = button.contains(mouseX.toDouble(), mouseY.toDouble())
				graphics.fill(x, y, x + width, y + height, if (hovered) 0xD0303A44.toInt() else section.fill)
				graphics.fill(x, y, x + 1, y + height, if (hovered) 0xFFFFFFFF.toInt() else section.accent)
				graphics.text(font, label, x + 6, y + 3, -1)
				val text = amountText(amount)
				graphics.text(font, text, x + width - 6 - font.width(text), y + 3, -1)
				placed += button
				y += height + 2
			}
			y += 4
		}
		buttons = placed
	}

	/** A group of buttons under a heading; suggestions (a relist, visitors) are tinted apart from your amounts. */
	private class Section(val heading: String, val accent: Int, val fill: Int, val entries: List<Pair<String, Int>>)

	private const val SUGGESTED_ACCENT = 0xFF55FF55.toInt()
	private const val SUGGESTED_FILL = 0xC0102A16.toInt()

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
