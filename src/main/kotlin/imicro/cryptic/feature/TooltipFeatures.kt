package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.AuctionPrices
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.ItemValue
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale

/**
 * Item tooltips: scrolling and sizing them, and prices added to them.
 *
 * While the cursor is on an item, the wheel belongs to its tooltip (NoammAddons' rule):
 * - wheel: up and down
 * - Shift + wheel: sideways, when allowed (Shift also shows the estimated value's parts)
 * - Control + wheel: bigger or smaller
 *
 * Vanilla's tooltip is drawn as is, just moved. Prices are laid out as Skyblocker's are: a coloured
 * label, the stack's worth, and each one's in brackets.
 */
object TooltipFeatures {
	private val scrollSection = SectionModuleSetting("scroll_section", "Scrolling")

	@JvmField
	val scrolling = ToggleModuleSetting(
		id = "scrolling",
		label = "Scroll and resize",
		defaultValue = true,
		description = "The wheel moves the tooltip of the item under the cursor; control resizes it.",
	)

	@JvmField
	val sideways = ToggleModuleSetting(
		id = "sideways",
		label = "Shift scrolls sideways",
		defaultValue = false,
		description = "Holding shift makes the wheel move the tooltip sideways. Off, shift is left for the estimated value's breakdown and the wheel still moves it up and down.",
		visibleIf = { scrolling.value },
	)

	@JvmField
	val speed = SliderModuleSetting(
		id = "speed",
		label = "Scroll speed",
		defaultValue = 10.0,
		min = 1.0,
		max = 40.0,
		step = 1.0,
		description = "How far one notch of the wheel moves the tooltip, in pixels.",
		visibleIf = { scrolling.value },
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Tooltip scale",
		unit = "%",
		defaultValue = 100.0,
		min = 30.0,
		max = 150.0,
		step = 5.0,
		description = "The size every tooltip starts at. Hold control and scroll to change one as you read it.",
		visibleIf = { scrolling.value },
	)

	@JvmField
	val scaleSpeed = SliderModuleSetting(
		id = "scale_speed",
		label = "Scale speed",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 1.0,
		description = "How much one notch changes the size while control is held.",
		visibleIf = { scrolling.value },
	)

	private val priceSection = SectionModuleSetting("price_section", "Prices")

	@JvmField
	val npcPrice = ToggleModuleSetting(
		id = "npc_price",
		label = "NPC sell price",
		defaultValue = true,
		description = "What an NPC pays for the item, for the stack and each.",
	)

	@JvmField
	val bazaarPrices = ToggleModuleSetting(
		id = "bazaar_prices",
		label = "Bazaar prices",
		defaultValue = true,
		description = "What the item buys and sells for on the Bazaar right now, for the stack and each.",
	)

	@JvmField
	val lowestBin = ToggleModuleSetting(
		id = "lowest_bin",
		label = "Lowest BIN",
		defaultValue = true,
		description = "The cheapest Buy It Now on the Auction House for the item, for the stack and each.",
	)

	@JvmField
	val threeDayAverage = ToggleModuleSetting(
		id = "three_day_average",
		label = "3-day average",
		defaultValue = true,
		description = "The item's lowest BIN averaged over the last three days, steadier than the price right now.",
	)

	@JvmField
	val valueMode = DropdownModuleSetting(
		id = "item_value_mode",
		label = "Counted as",
		options = listOf("Market value", "Rebuild cost"),
		description = "Market value: what it would sell for, from three-day averages (never above the lowest BIN), with each upgrade at the share " +
			"of its price an applied one keeps (as Skyblocker counts it). Rebuild cost: what making it again costs " +
			"today, every upgrade in full (as SkyHanni counts it).",
	)

	@JvmField
	val itemValue = ToggleModuleSetting(
		id = "item_value",
		label = "Estimated value",
		defaultValue = true,
		description = "The item's price plus everything applied to it: books, stars, gemstones, enchantments, " +
			"scrolls, the reforge stone and the rest. Hold shift for the parts.",
	).withMore(valueMode)

	private val textSection = SectionModuleSetting("text_section", "Text")

	@JvmField
	val arabicNumerals = ToggleModuleSetting(
		id = "arabic_numerals",
		label = "Numbers for roman numerals",
		defaultValue = false,
		description = "Writes enchant and tier levels as numbers: Sharpness VII becomes Sharpness 7.",
	)

	/** The id is the old Scrollable Tooltips', so a saved setup carries over. */
	@JvmField
	val module = Module(
		id = "scrollable_tooltips",
		name = "Tooltip Features",
		description = "Scrolling, sizes and prices",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			scrollSection,
			scrolling.withMore(speed, sideways, scale, scaleSpeed),
			priceSection,
			npcPrice,
			bazaarPrices,
			lowestBin,
			threeDayAverage,
			itemValue,
			textSection,
			arabicNumerals,
		),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ItemTooltipCallback.EVENT.register { stack, _, _, lines -> addPrices(stack, lines) }
	}

	/**
	 * A tooltip's text as it's about to be drawn, after every mod has changed it (SkyHanni rewrites
	 * enchant lines late). Numerals are replaced here, and tooltips dumped while asked to.
	 */
	@JvmStatic
	fun beforeDraw(lines: List<Component>): List<Component> {
		if (dumpUntil > 0L) dump(lines)
		if (!module.enabled || !arabicNumerals.value || lines.isEmpty()) return lines
		// The same tooltip comes every frame while it's hovered; comparing it is cheaper than redoing it.
		if (lines == numeralsIn) return numeralsOut
		numeralsIn = lines.toList()
		numeralsOut = lines.map(RomanNumerals::inLine)
		return numeralsOut
	}

	private var numeralsIn: List<Component> = emptyList()
	private var numeralsOut: List<Component> = emptyList()

	// ---- /cryptic debug tooltip ------------------------------------------------------------------

	private var dumpUntil = 0L
	private val dumped = HashSet<String>()
	private const val DUMP_SECONDS = 30
	private val dumpFile get() = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("cryptic/debug/tooltips.txt")

	/** Saves each new tooltip shown in the next half minute, with every piece's style. */
	fun startDump(): String {
		dumpUntil = System.currentTimeMillis() + DUMP_SECONDS * 1000L
		dumped.clear()
		return "Hover the items you want for the next $DUMP_SECONDS seconds. Each new tooltip is added to $dumpFile"
	}

	private fun dump(lines: List<Component>) {
		if (System.currentTimeMillis() > dumpUntil) {
			dumpUntil = 0L
			Toasts.show("Tooltip dump", "Stopped: §f${dumped.size} §7tooltips saved")
			return
		}
		val key = lines.joinToString("\n") { it.string }
		if (lines.isEmpty() || !dumped.add(key)) return
		val text = StringBuilder("==== ${lines.first().string} (${java.time.LocalTime.now().withNano(0)})\n")
		for ((index, line) in lines.withIndex()) {
			text.append("[$index] ").append(line.string).append('\n')
			line.visit({ style: net.minecraft.network.chat.Style, piece: String ->
				text.append("    ").append(describeStyle(style)).append(" \"").append(piece).append("\"\n")
				java.util.Optional.empty<Unit>()
			}, net.minecraft.network.chat.Style.EMPTY)
		}
		runCatching {
			java.nio.file.Files.createDirectories(dumpFile.parent)
			java.nio.file.Files.writeString(
				dumpFile, text.append('\n').toString(),
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
			)
		}.onFailure { imicro.cryptic.Cryptic.LOGGER.warn("Couldn't save a tooltip", it) }
		Toasts.show("Tooltip dump", "Saved §f${lines.first().string.take(40)}")
	}

	private fun describeStyle(style: net.minecraft.network.chat.Style): String = buildList {
		style.color?.let { add(it.serialize()) }
		if (style.isBold) add("bold")
		if (style.isItalic) add("italic")
		if (style.isUnderlined) add("underlined")
		if (style.isStrikethrough) add("strike")
		if (style.isObfuscated) add("obfuscated")
		style.font?.let { add("font=$it") }
	}.joinToString(",", "{", "}")

	val needsSkyblock: Boolean
		get() = module.enabled && (npcPrice.value || bazaarPrices.value || lowestBin.value || threeDayAverage.value || itemValue.value)

	/** Keeps the prices fresh enough for tooltips, and the items list (NPC prices, star costs) loaded. */
	fun tick() {
		if (!needsSkyblock || !SkyblockLocation.onSkyblock) return
		// The auction lines need the Bazaar too, to know which items trade there instead.
		if (bazaarPrices.value || itemValue.value || lowestBin.value || threeDayAverage.value) BazaarMarket.refresh(PRICE_MAX_AGE_MILLIS)
		else BazaarMarket.ensureItems()
		AuctionPrices.refresh(lowestBin = lowestBin.value || itemValue.value, threeDay = threeDayAverage.value)
	}

	private const val PRICE_MAX_AGE_MILLIS = 120_000L

	private fun addPrices(stack: ItemStack, lines: MutableList<Component>) {
		if (!module.enabled || lines.isEmpty() || !SkyblockLocation.onSkyblock) return
		val id = SkyblockItem.id(stack).ifEmpty { return }
		val key = AuctionPrices.key(stack) ?: id
		val count = stack.count
		val rows = ArrayList<Triple<String, String, Double>>()
		if (npcPrice.value) BazaarMarket.npcSellPrice(id)?.takeIf { it > 0 }?.let { rows += Triple("§e", "NPC Sell Price:", it) }
		val product = BazaarMarket.product(bazaarId(key))
		if (bazaarPrices.value && product != null) {
			rows += Triple("§6", "Bazaar Buy Price:", product.instantBuy)
			rows += Triple("§6", "Bazaar Sell Price:", product.instantSell)
		}
		// Odin's table lists Bazaar items as well; they don't go on the Auction House, and their
		// worth is the Bazaar's.
		val auctioned = product == null
		if (lowestBin.value && auctioned) AuctionPrices.lowestBin(key)?.let { rows += Triple("§6", "Lowest BIN Price:", it) }
		if (threeDayAverage.value && auctioned) AuctionPrices.threeDayAverage(key)?.let { rows += Triple("§6", "3 Day Avg. Price:", it) }
		val rebuild = valueMode.selectedIndex == REBUILD
		val estimate = if (itemValue.value && auctioned) estimateOf(stack, if (rebuild) ItemValue.Mode.REBUILD else ItemValue.Mode.MARKET) else null
		if (estimate != null) rows += Triple("§6", if (rebuild) "Est. Rebuild Cost:" else "Est. Item Value:", estimate.total)
		if (rows.isEmpty()) return
		lines += aligned(rows.map { (colour, label, each) ->
			val per = if (count > 1) " §7(${coins(each)} each)" else ""
			"$colour$label" to "§3${coins(each * count)} Coins$per"
		})
		if (estimate != null && estimate.parts.isNotEmpty() && Minecraft.getInstance().hasShiftDown()) {
			val parts = listOf("Base" to estimate.base) + estimate.parts.sortedByDescending { it.second }
			lines += aligned(parts.map { (part, value) -> " §8• §7$part:" to "§3${coins(value)}" })
		}
	}

	private const val REBUILD = 1

	/** The last estimate per stack: a tooltip asks every frame, and prices change far less often. */
	private class Kept(val mode: ItemValue.Mode, val at: Long, val estimate: ItemValue.Estimate?)

	private val estimates = java.util.WeakHashMap<ItemStack, Kept>()
	private const val ESTIMATE_MILLIS = 2_000L

	private fun estimateOf(stack: ItemStack, mode: ItemValue.Mode): ItemValue.Estimate? {
		val now = System.currentTimeMillis()
		estimates[stack]?.takeIf { it.mode == mode && now - it.at < ESTIMATE_MILLIS }?.let { return it.estimate }
		return ItemValue.estimate(stack, mode).also { estimates[stack] = Kept(mode, now, it) }
	}

	/**
	 * Label and value lines with the values in one column, as Skyblocker lays them out. The gap is
	 * made of plain and bold spaces, whose widths differ by a pixel, so any width past a few spaces
	 * can be met exactly and the lines stay plain text.
	 */
	private fun aligned(rows: List<Pair<String, String>>): List<Component> {
		val font = Minecraft.getInstance().font
		val space = font.width(" ")
		val bold = font.width(Component.literal(" ").withStyle(ChatFormatting.BOLD))
		// Every gap from here up is some mix of the two (their Frobenius number, plus one).
		val minGap = if (bold > space) space * bold - space - bold + 1 else space
		val column = rows.maxOf { font.width(it.first) } + minGap
		return rows.map { (label, value) ->
			val gap = column - font.width(label)
			val bolds = (0 until space).firstOrNull { (gap - it * bold).let { rest -> rest >= 0 && rest % space == 0 } } ?: 0
			val plain = ((gap - bolds * bold) / space).coerceAtLeast(1)
			Component.literal(label)
				.append(Component.literal(" ".repeat(plain)))
				.append(Component.literal(" ".repeat(bolds)).withStyle(ChatFormatting.BOLD))
				.append(Component.literal(value))
		}
	}

	/** A book trades on the Bazaar as its enchantment ("ENCHANTMENT_ULTIMATE_WISE_5"). */
	private fun bazaarId(key: String): String {
		if (!key.startsWith("ENCHANTED_BOOK-")) return key
		val (name, level) = key.removePrefix("ENCHANTED_BOOK-").let { it.substringBeforeLast('-') to it.substringAfterLast('-') }
		return "ENCHANTMENT_${name}_$level"
	}

	private fun coins(value: Double): String = String.format(Locale.US, "%,.1f", value)

	private var offsetX = 0f
	private var offsetY = 0f

	private var scaleOffset = 0f

	private var lastScreen: Screen? = null
	private var lastSlot = -1

	private const val MIN_SCALE = 0.3f
	private const val MAX_SCALE = 2.0f

	/** A wheel turn, before the screen sees it. Taken whenever the cursor is on an item in a container. */
	@JvmStatic
	fun onScroll(verticalAmount: Double): Boolean {
		if (!module.enabled || !scrolling.value || verticalAmount == 0.0) return false
		val client = Minecraft.getInstance()
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
		// The creative inventory scrolls and wants its wheel.
		if (screen is CreativeModeInventoryScreen) return false
		val slot = (screen as? ContainerScreenAccessor)?.`cryptic$hoveredSlot`() ?: return false
		if (!slot.hasItem()) return false
		// Terminal items are hidden, and so are their tooltips.
		if (TerminalSolver.hidesTooltip()) return false

		noteWhatIsHovered(screen, slot.index)

		val shift = client.hasShiftDown()
		val control = client.hasControlDown()
		val step = (verticalAmount * speed.value).toFloat()

		when {
			shift && !control && sideways.value -> offsetX -= step
			control && !shift -> {
				val base = scale.value.toFloat() / 100f
				val next = (base + scaleOffset + (verticalAmount * scaleSpeed.value / 100.0).toFloat())
					.coerceIn(MIN_SCALE, MAX_SCALE)
				scaleOffset = next - base
			}
			else -> offsetY += step
		}
		return true
	}

	/**
	 * Moves and sizes the tooltip, scaling about the cursor so it shrinks toward its item. The push
	 * is unconditional so the matching pop always has something to undo.
	 */
	@JvmStatic
	fun beforeTooltip(graphics: GuiGraphicsExtractor, anchorX: Int, anchorY: Int) {
		val pose = graphics.pose()
		pose.pushMatrix()
		if (!module.enabled || !scrolling.value) return

		val screen = Minecraft.getInstance().gui.screen()
		val slot = (screen as? AbstractContainerScreen<*>)
			?.let { it as? ContainerScreenAccessor }
			?.`cryptic$hoveredSlot`()
			?.index
			?: -1
		noteWhatIsHovered(screen, slot)

		// Only item tooltips move.
		if (slot < 0) return

		val size = (scale.value.toFloat() / 100f + scaleOffset).coerceIn(MIN_SCALE, MAX_SCALE)
		pose.translate(anchorX.toFloat(), anchorY.toFloat())
		pose.scale(size, size)
		pose.translate(offsetX, offsetY)
		pose.translate(-anchorX.toFloat(), -anchorY.toFloat())
	}

	@JvmStatic
	fun afterTooltip(graphics: GuiGraphicsExtractor) {
		graphics.pose().popMatrix()
	}

	/** Resets the offsets when the hovered slot changes, so each item starts where the game put it. */
	private fun noteWhatIsHovered(screen: Screen?, slot: Int) {
		if (screen === lastScreen && slot == lastSlot) return
		lastScreen = screen
		lastSlot = slot
		offsetX = 0f
		offsetY = 0f
		scaleOffset = 0f
	}
}
