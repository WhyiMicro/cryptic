package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import imicro.cryptic.Cryptic
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.AuctionPrices
import imicro.cryptic.skyblock.BazaarMarket
import imicro.cryptic.skyblock.ItemPrices
import imicro.cryptic.skyblock.ItemValue
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.nio.file.Files
import java.util.Locale
import java.util.WeakHashMap

/**
 * Keeps valuable items from being dropped, sold, salvaged, traded, auctioned or put in a dungeon
 * chest by accident. Items are protected by hand (one item by its UUID, or every item of a type by
 * its SkyBlock id) or by rule: starred, recombobulated, max quality, worth more than a price, or
 * one of the consumables that are worth more kept. Ideas from NoammAddons' Protect Item (CC0),
 * NoFrills' and Skyblocker's Item Protection.
 *
 * Only your own clicks on a screen are judged ([blocksClick], from `slotClicked`); Slot Binds and
 * other modules click through the game directly, so a bound swap is never refused. Within your
 * inventory, only throwing an item is refused: moving it around never is.
 */
object ItemProtection {
	enum class Reason(val label: String) {
		MANUAL("protected"),
		TYPE("protected type"),
		STARRED("starred"),
		RECOMBOBULATED("recombobulated"),
		MAX_QUALITY("max quality"),
		VALUE("valuable"),
		CONSUMABLE("valuable consumable"),
	}

	private val keysSection = SectionModuleSetting("keys_section", "Keys")

	@JvmField
	val protectKey = KeybindModuleSetting(
		id = "protect_key",
		label = "Protect item",
		description = "Over an item in a menu, or with it held, protects that one item (by its UUID) or stops protecting it.",
	)

	@JvmField
	val typeKey = KeybindModuleSetting(
		id = "type_key",
		label = "Protect item type",
		description = "Like Protect item, but for every item with the same SkyBlock id (all your Hyperions, say).",
	)

	@JvmField
	val overrideKey = KeybindModuleSetting(
		id = "override_key",
		label = "Override",
		description = "While held, nothing is protected, for when you do mean to sell or drop it.",
	)

	private val rulesSection = SectionModuleSetting("rules_section", "Also protect")

	@JvmField
	val starred = ToggleModuleSetting("starred", "Starred items", true, "Dungeon items with stars on them.")

	@JvmField
	val recombobulated = ToggleModuleSetting("recombobulated", "Recombobulated items", true, "Items with a Recombobulator applied.")

	@JvmField
	val maxQuality = ToggleModuleSetting("max_quality", "Max quality", false, "Dungeon items at 50/50 quality.")

	@JvmField
	val consumables = ToggleModuleSetting(
		"consumables", "Valuable consumables", true,
		"Won't use a Bottle of Jyrre, Dark Cacao Truffle, Discrite, Moby-Duck or Rosewater Flask, which are worth more kept.",
	)

	@JvmField
	val minValue = SliderModuleSetting(
		id = "min_value",
		label = "Worth at least",
		unit = "M",
		defaultValue = 5.0,
		min = 0.5,
		max = 500.0,
		step = 0.5,
		description = "In millions of coins, by the estimated item value: the item with everything applied to it, counted " +
			"the way Tooltip Features counts it (market value or rebuild cost).",
	)

	@JvmField
	val byValue = ToggleModuleSetting("by_value", "Valuable items", false, "Anything worth more than the amount set behind the dots.")
		.withMore(minValue)

	private val displaySection = SectionModuleSetting("display_section", "Display")

	@JvmField
	val tooltip = ToggleModuleSetting("tooltip", "Tooltip line", true, "Says in an item's tooltip that it's protected, and why.")

	@JvmField
	val manualColor = ColorModuleSetting(
		id = "manual_color",
		label = "Protected by hand",
		defaultRgb = 0xFFD45A,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		description = "Its opacity is used by the Background style; the lock and outline are solid.",
		visibleIf = { style.selectedIndex != STYLE_NONE },
	)

	@JvmField
	val ruleColor = ColorModuleSetting(
		id = "rule_color",
		label = "Protected by a rule",
		defaultRgb = 0x55FF7F,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		description = "Its opacity is used by the Background style; the lock and outline are solid.",
		visibleIf = { style.selectedIndex != STYLE_NONE },
	)

	private const val STYLE_LOCK = 0
	private const val STYLE_OUTLINE = 1
	private const val STYLE_BACKGROUND = 2
	private const val STYLE_NONE = 3

	@JvmField
	val style = DropdownModuleSetting(
		id = "style",
		label = "Style",
		options = listOf("Lock", "Outline", "Background", "None"),
		defaultIndex = STYLE_LOCK,
		description = "A lock in the corner, a frame around the slot, a tinted slot, or nothing at all. The lock and frame don't clash with mods that colour slots by rarity.",
	)

	@JvmField
	val onHotbar = ToggleModuleSetting("on_hotbar", "On the hotbar", true, "Marks protected items on your hotbar too, not only in menus.")

	@JvmField
	val highlight = ToggleModuleSetting("highlight", "Mark protected items", true, "Shows which items are protected, in menus and on the hotbar.")
		.withMore(style, manualColor, ruleColor, onHotbar)

	@JvmField
	val notify = ToggleModuleSetting("notify", "Say when it stops you", true, "A notification each time something is refused.")

	@JvmField
	val module = Module(
		id = "item_protection",
		name = "Item Protection",
		description = "Guards your valuable items",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			keysSection, protectKey, typeKey, overrideKey,
			rulesSection, starred, recombobulated, maxQuality, byValue, consumables,
			displaySection, highlight, tooltip, notify,
		),
	)

	/** The consumables whose value grows while kept. */
	private val VALUABLE_CONSUMABLES = setOf("NEW_BOTTLE_OF_JYRRE", "DARK_CACAO_TRUFFLE", "DISCRITE", "MOBY_DUCK", "ROSEWATER_FLASK")

	private const val OUTSIDE = -999

	private class Saved(val uuids: MutableSet<String> = linkedSetOf(), val ids: MutableSet<String> = linkedSetOf())

	private var saved = Saved()
	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("protected-items.json")

	/** Reasons by stack, so slots drawn every frame don't re-read their item's data. */
	private val cache = WeakHashMap<ItemStack, Any>()
	private val NONE = Any()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
		ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
			if (!module.enabled || !tooltip.value || lines.isEmpty()) return@register
			val reason = reasonFor(stack) ?: return@register
			lines.add(1, Component.literal("§aItem Protected §7(${reason.label})"))
		}
		UseItemCallback.EVENT.register { player, level, hand ->
			if (level.isClientSide && refusesUse(player.getItemInHand(hand))) InteractionResult.FAIL else InteractionResult.PASS
		}
		// Consumables that are heads would otherwise be placed, which uses them too.
		UseBlockCallback.EVENT.register { player, level, hand, _ ->
			if (level.isClientSide && refusesUse(player.getItemInHand(hand))) InteractionResult.FAIL else InteractionResult.PASS
		}
		// An empty item frame takes whatever is held for good.
		UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
			if (!level.isClientSide || entity !is ItemFrame || !entity.item.isEmpty || !active()) return@register InteractionResult.PASS
			val held = player.getItemInHand(hand)
			if (reasonFor(held) == null) return@register InteractionResult.PASS
			refused(held, "put in an item frame")
			InteractionResult.FAIL
		}
	}

	private fun active(): Boolean = module.enabled && SkyblockLocation.onSkyblock && !overridden()

	private fun overridden(): Boolean = overrideKey.isDown(Minecraft.getInstance().window)

	private fun refusesUse(stack: ItemStack): Boolean {
		if (!active() || !consumables.value) return false
		if (SkyblockItem.id(stack) !in VALUABLE_CONSUMABLES) return false
		refused(stack, "used")
		return true
	}

	// ---- What's protected ---------------------------------------------------------------

	private fun customData(stack: ItemStack) = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()

	private var rulesSeen = ""

	/** The rules as one string; any change to them clears what was worked out. */
	private fun rules(): String =
		"${starred.value}${recombobulated.value}${maxQuality.value}${consumables.value}${byValue.value}${minValue.value}" +
			"${ItemPrices.loaded}${AuctionPrices.loaded}${BazaarMarket.products.isNotEmpty()}${TooltipFeatures.valueMode.selectedIndex}"

	fun reasonFor(stack: ItemStack): Reason? {
		if (stack.isEmpty) return null
		val now = rules()
		if (now != rulesSeen) {
			rulesSeen = now
			cache.clear()
		}
		val known = cache.getOrPut(stack) { compute(stack) ?: NONE }
		return known as? Reason
	}

	private fun compute(stack: ItemStack): Reason? {
		val data = customData(stack)
		val id = data.getString("id").orElse("")
		if (id.isEmpty()) return null
		val uuid = data.getString("uuid").orElse("")
		if (uuid.isNotEmpty() && uuid in saved.uuids) return Reason.MANUAL
		if (id in saved.ids) return Reason.TYPE
		if (starred.value && data.getIntOr("upgrade_level", 0) > 0) return Reason.STARRED
		if (recombobulated.value && data.getIntOr("rarity_upgrades", 0) > 0) return Reason.RECOMBOBULATED
		if (maxQuality.value && data.getIntOr("baseStatBoostPercentage", 0) >= 50) return Reason.MAX_QUALITY
		if (consumables.value && id in VALUABLE_CONSUMABLES) return Reason.CONSUMABLE
		if (byValue.value && worth(stack, id)?.let { it >= minValue.value * 1_000_000 } == true) return Reason.VALUE
		return null
	}

	/**
	 * What a stack is worth: its estimated item value, as the tooltip shows it. Until those prices
	 * have arrived, the seven-day average stands in.
	 */
	private fun worth(stack: ItemStack, id: String): Double? {
		AuctionPrices.refresh(lowestBin = true, threeDay = true)
		BazaarMarket.refresh(120_000L)
		ItemPrices.ensureFetched()
		val mode = if (TooltipFeatures.valueMode.selectedIndex == 1) ItemValue.Mode.REBUILD else ItemValue.Mode.MARKET
		val each = ItemValue.estimate(stack, mode)?.total ?: ItemPrices.price(id) ?: return null
		return each * stack.count
	}

	// ---- Refusing ----------------------------------------------------------------------

	private enum class Risk {
		/** Clicking an item of yours hands it over: NPC shops, salvaging, trading, auctioning. */
		TAKES_ON_CLICK,

		/** Only putting it in loses it: a dungeon secret chest. */
		TAKES_WHAT_IS_PUT_IN,
	}

	private fun riskOf(screen: AbstractContainerScreen<*>): Risk? {
		val title = screen.title.string
		return when {
			title == "Salvage Items" || title == "Draconic Sacrifice" || title.startsWith("You  ") ||
				title.endsWith("Auction House") || title == "Create Auction" || title == "Create BIN Auction" ||
				isSellMenu(screen.menu) -> Risk.TAKES_ON_CLICK
			DungeonLocation.inDungeon && title == "Chest" -> Risk.TAKES_WHAT_IS_PUT_IN
			else -> null
		}
	}

	private fun isSellMenu(menu: AbstractContainerMenu): Boolean = menu.slots.any { slot ->
		slot.container !is Inventory && !slot.item.isEmpty && isSellButton(slot.item)
	}

	private fun isSellButton(stack: ItemStack): Boolean {
		val name = stack.hoverName.string
		return name == "Sell Item" || name == "Sell Inventory" || SkyblockItem.loreContains(stack, "buyback")
	}

	private fun isSalvageButton(stack: ItemStack): Boolean {
		val name = stack.hoverName.string
		return name == "Salvage Items" || name == "Confirm Salvage" || name == "Draconic Altar"
	}

	/** A click of yours in a menu. True refuses it. */
	@JvmStatic
	fun blocksClick(screen: AbstractContainerScreen<*>, slot: Slot?, slotId: Int, button: Int, input: ContainerInput): Boolean {
		if (!active()) return false
		val menu = screen.menu
		val carried = menu.carried
		// Clicking outside the window drops what's on the cursor.
		// A plain click there; placing by dragging also passes it, at the drag's start and end.
		if (slotId == OUTSIDE && input == ContainerInput.PICKUP && reasonFor(carried) != null) return refused(carried, "dropped")
		val stack = slot?.item ?: ItemStack.EMPTY
		if (input == ContainerInput.THROW && reasonFor(stack) != null) return refused(stack, "dropped")

		val risk = riskOf(screen) ?: return false
		slot ?: return false
		val yours = slot.container is Inventory

		if (!yours && isSalvageButton(stack)) {
			menu.slots.firstOrNull { it.container !is Inventory && reasonFor(it.item) != null }?.let { return refused(it.item, "salvaged") }
		}
		return when (input) {
			ContainerInput.PICKUP, ContainerInput.QUICK_MOVE, ContainerInput.PICKUP_ALL -> when {
				yours && reasonFor(stack) != null && (risk == Risk.TAKES_ON_CLICK || input == ContainerInput.QUICK_MOVE) -> refused(stack, "handed over")
				!yours && reasonFor(carried) != null -> refused(carried, "put in")
				else -> false
			}
			// Dragging what's carried across the menu's own slots spreads it into them.
			ContainerInput.QUICK_CRAFT -> if (!yours && reasonFor(carried) != null) refused(carried, "put in") else false
			// A hotbar key over the menu's own slot moves that hotbar item into it.
			ContainerInput.SWAP -> {
				if (yours) return false
				val player = Minecraft.getInstance().player ?: return false
				val hotbar = player.inventory.getItem(if (button == Inventory.SLOT_OFFHAND) Inventory.SLOT_OFFHAND else button)
				if (reasonFor(hotbar) != null) refused(hotbar, "put in") else false
			}
			else -> false
		}
	}

	/** The drop key with nothing open. In a dungeon run that key is the class ultimate, so it's left alone. */
	@JvmStatic
	fun blocksDrop(stack: ItemStack): Boolean {
		if (!active() || (DungeonLocation.inDungeon && DungeonRun.started)) return false
		if (reasonFor(stack) == null) return false
		return refused(stack, "dropped")
	}

	private var lastNotice = 0L

	private fun refused(stack: ItemStack, what: String): Boolean {
		val now = System.currentTimeMillis()
		if (notify.value && now - lastNotice > 1_000L) {
			lastNotice = now
			Toasts.show("Item Protection", "§f${stack.hoverName.string} §7is protected and wasn't $what.", error = true)
		}
		return true
	}

	// ---- Protecting by hand ---------------------------------------------------------------

	/** The protect keys over a menu. True takes the key. */
	@JvmStatic
	fun handleKey(screen: AbstractContainerScreen<*>, key: Int): Boolean {
		if (!module.enabled) return false
		val byType = typeKey.matches(key)
		if (!byType && !protectKey.matches(key)) return false
		val stack = (screen as ContainerScreenAccessor).`cryptic$hoveredSlot`()?.item ?: return false
		if (stack.isEmpty) return false
		toggle(stack, byType)
		return true
	}

	/** The protect keys as mouse buttons over a menu. */
	@JvmStatic
	fun handleMouse(screen: AbstractContainerScreen<*>, button: Int): Boolean {
		if (!module.enabled) return false
		val byType = typeKey.matchesMouse(button)
		if (!byType && !protectKey.matchesMouse(button)) return false
		val stack = (screen as ContainerScreenAccessor).`cryptic$hoveredSlot`()?.item ?: return false
		if (stack.isEmpty) return false
		toggle(stack, byType)
		return true
	}

	private var protectWasDown = false
	private var typeWasDown = false

	/** The protect keys with nothing open act on the held item. */
	fun tick(client: Minecraft) {
		if (!module.enabled) return
		val free = client.gui.screen() == null
		val protect = free && protectKey.isDown(client.window)
		val type = free && typeKey.isDown(client.window)
		val held = client.player?.mainHandItem
		if (held != null && !held.isEmpty) {
			if (protect && !protectWasDown) toggle(held, byType = false)
			if (type && !typeWasDown) toggle(held, byType = true)
		}
		protectWasDown = protect
		typeWasDown = type
	}

	private fun toggle(stack: ItemStack, byType: Boolean) {
		val data = customData(stack)
		val id = data.getString("id").orElse("")
		val uuid = data.getString("uuid").orElse("")
		val name = stack.hoverName.string
		// One item needs a UUID; stackables have none, so they go by type.
		val asType = byType || uuid.isEmpty()
		val key = if (asType) id else uuid
		if (key.isEmpty()) {
			Toasts.show("Item Protection", "§f$name §7isn't a SkyBlock item, so it can't be protected.", error = true)
			return
		}
		val set = if (asType) saved.ids else saved.uuids
		val added = set.add(key)
		if (!added) set.remove(key)
		cache.clear()
		save()
		val scope = if (asType) "every §f${id.lowercase(Locale.ROOT).replace('_', ' ')}§7" else "§f$name§7"
		Toasts.show("Item Protection", if (added) "Now protecting $scope." else "No longer protecting $scope.")
	}

	// ---- Drawing --------------------------------------------------------------------------

	/**
	 * A protected item's mark at the item's corner ([x], [y]). Called once under the item and once
	 * over it; the background goes under, the lock and frame over.
	 */
	@JvmStatic
	fun drawSlot(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int, over: Boolean, hotbar: Boolean) {
		if (!module.enabled || !highlight.value || (hotbar && !onHotbar.value)) return
		val reason = reasonFor(stack) ?: return
		val argb = if (reason == Reason.MANUAL || reason == Reason.TYPE) manualColor.argb else ruleColor.argb
		val solid = argb or (0xFF shl 24)
		when (style.selectedIndex) {
			// The whole slot, so it lines up with any slot frame.
			STYLE_BACKGROUND -> if (!over) graphics.fill(x - 1, y - 1, x + 17, y + 17, argb)
			STYLE_OUTLINE -> if (over) {
				graphics.fill(x - 1, y - 1, x + 17, y, solid)
				graphics.fill(x - 1, y + 16, x + 17, y + 17, solid)
				graphics.fill(x - 1, y, x, y + 16, solid)
				graphics.fill(x + 16, y, x + 17, y + 16, solid)
			}
			STYLE_NONE -> Unit
			else -> if (over) drawLock(graphics, x - 1, y - 1, solid)
		}
	}

	private val LOCK = net.minecraft.resources.Identifier.fromNamespaceAndPath("cryptic", "textures/gui/protected_lock.png")

	/**
	 * A 5 by 6 padlock with a dark shadow, so it reads over any item: one image, tinted. Drawn as
	 * eight fills it cost half the frame in a full chest, since each piece over an item makes
	 * Minecraft search everything under it.
	 */
	private fun drawLock(graphics: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, LOCK, x, y, 0f, 0f, 6, 7, 6, 7, color)
	}

	// ---- Saving --------------------------------------------------------------------------

	private fun load() {
		runCatching {
			if (Files.exists(file)) saved = gson.fromJson(Files.readString(file), Saved::class.java) ?: Saved()
		}.onFailure { Cryptic.LOGGER.warn("Could not read protected items", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(saved))
		}.onFailure { Cryptic.LOGGER.warn("Could not save protected items", it) }
	}
}
