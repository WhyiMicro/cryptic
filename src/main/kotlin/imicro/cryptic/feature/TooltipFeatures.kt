package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale

/**
 * Item tooltips: scrolling and sizing them, and prices added to them.
 *
 * While the cursor is on an item, the wheel belongs to its tooltip (NoammAddons' rule):
 * - wheel: up and down
 * - Shift + wheel: sideways
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
		description = "The wheel moves the tooltip of the item under the cursor; shift moves it sideways, control resizes it.",
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
			scrolling.withMore(speed, scale, scaleSpeed),
			priceSection,
			npcPrice,
			bazaarPrices,
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
		return lines.map(RomanNumerals::inLine)
	}

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

	val needsSkyblock: Boolean get() = module.enabled && (npcPrice.value || bazaarPrices.value)

	/** Keeps the Bazaar's prices fresh enough for tooltips, and the NPC prices loaded. */
	fun tick() {
		if (!needsSkyblock || !SkyblockLocation.onSkyblock) return
		if (bazaarPrices.value) BazaarMarket.refresh(PRICE_MAX_AGE_MILLIS) else BazaarMarket.ensureItems()
	}

	private const val PRICE_MAX_AGE_MILLIS = 120_000L

	private fun addPrices(stack: ItemStack, lines: MutableList<Component>) {
		if (!module.enabled || lines.isEmpty() || !SkyblockLocation.onSkyblock) return
		val id = SkyblockItem.id(stack).ifEmpty { return }
		val count = stack.count
		val rows = ArrayList<Triple<String, String, Double>>()
		if (npcPrice.value) BazaarMarket.npcSellPrice(id)?.takeIf { it > 0 }?.let { rows += Triple("§e", "NPC Sell Price:", it) }
		if (bazaarPrices.value) {
			BazaarMarket.product(id)?.let { product ->
				rows += Triple("§6", "Bazaar Buy Price:", product.instantBuy)
				rows += Triple("§6", "Bazaar Sell Price:", product.instantSell)
			}
		}
		if (rows.isEmpty()) return
		// The values line up after the longest label, padded with spaces.
		val font = Minecraft.getInstance().font
		val labelWidth = rows.maxOf { font.width(it.second) } + font.width(" ")
		for ((colour, label, each) in rows) {
			var padded = "$label "
			while (font.width(padded + " ") <= labelWidth) padded += " "
			val worth = "§3${coins(each * count)} Coins"
			val per = if (count > 1) " §7(${coins(each)} each)" else ""
			lines += Component.literal("$colour$padded$worth$per")
		}
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
			shift && !control -> offsetX -= step
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
