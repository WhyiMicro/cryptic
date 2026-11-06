package imicro.cryptic.feature

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.TabModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.UUID

/**
 * Tabs above and below SkyBlock menus that run a command, like the creative inventory's: seven on
 * top, seven underneath. A tab whose menu is open is drawn raised. The defaults are the user's own
 * Skyblocker layout.
 */
object QuickNav {
	private class Button(
		val index: Int,
		name: String,
		command: String,
		item: String,
		head: String = "",
		title: String = "",
		dye: Int = 0xA06540,
	) {
		val shown = ToggleModuleSetting("button_${index}", "Button ${index % 7 + 1}", true)
		val name = TextModuleSetting("button_${index}_name", "Name", name, maxLength = 48)
		val command = TextModuleSetting("button_${index}_command", "Command", command, maxLength = 128, hint = "/warp hub")
		val item = TextModuleSetting(
			"button_${index}_item", "Item", item, maxLength = 64, hint = "minecraft:diamond_sword",
			suggestions = ::suggestItems,
		)
		val head = TextModuleSetting(
			"button_${index}_head", "Head texture", head, maxLength = 80,
			description = "The hash at the end of a skin URL (textures.minecraft.net/texture/...).",
			visibleIf = { itemId(this.item.value) == "minecraft:player_head" },
		)
		val dye = ColorModuleSetting(
			"button_${index}_dye", "Dye", defaultRgb = dye,
			visibleIf = { itemId(this.item.value).startsWith("minecraft:leather_") },
		)
		val title = TextModuleSetting(
			"button_${index}_title", "Menu title", title, maxLength = 200,
			description = "The tab is raised while a menu whose title matches this regex is open. Empty never matches.",
		)

		init {
			shown.withMore(this.name, this.command, this.item, this.head, this.dye, this.title)
		}

		private var stackKey = ""
		private var stack: ItemStack = ItemStack.EMPTY
		private var titleKey: String? = null
		private var titleRegex: Regex? = null

		fun icon(): ItemStack {
			val key = "${item.value}|${head.value}|${dye.rgb}"
			if (key != stackKey) {
				stackKey = key
				stack = buildStack(item.value, head.value, dye.rgb)
			}
			return stack
		}

		fun matches(menuTitle: String): Boolean {
			if (titleKey != title.value) {
				titleKey = title.value
				titleRegex = title.value.takeIf { it.isNotBlank() && it != "none" }?.let { runCatching { Regex(it) }.getOrNull() }
			}
			return titleRegex?.matches(menuTitle) == true
		}
	}

	private val buttons = listOf(
		Button(0, "Skills", "/skills", "minecraft:diamond_sword", title = "Your Skills"),
		Button(1, "Collections", "/collection", "minecraft:painting", title = "Collections"),
		Button(2, "Pets", "/pets", "minecraft:bone", title = """(?:\(\d+/\d+\) )?Pets"""),
		Button(3, "Armor Sets", "/armor", "minecraft:leather_chestplate", title = """\(\d+/\d+\) Armor Sets""", dye = 8991416),
		Button(4, "Sacks", "/sacks", "minecraft:player_head", "80a077e248d142772ea800864f8c578b9d36885b29daf836b64a706882b6ec10", "Sack of Sacks"),
		Button(5, "Bags", "/bags", "minecraft:player_head", "961a918c0c49ba8d053e522cb91abc74689367b4d8aa06bfc1ba9154730985ff", """Accessory Bag(?: \(\d/\d\))?"""),
		Button(6, "Storage", "/storage", "minecraft:ender_chest", title = """(?:Rift )?Storage(?: \(\d/\d\))?"""),
		Button(7, "Home", "/is", "minecraft:player_head", "c9c8881e42915a9d29bb61a16fb26d059913204d265df5b439b3d792acd56"),
		Button(8, "Garden", "/warp garden", "minecraft:player_head", "f4880d2c1e7b86e87522e20882656f45bafd42f94932b2c5e0d6ecaa490cb4c"),
		Button(9, "Skyblock Hub", "/hub", "minecraft:player_head", "d7cc6687423d0570d556ac53e0676cb563bbdd9717cd8269bdebed6f6d4e7bf8"),
		Button(10, "Dungeons Hub", "/warp dungeon_hub", "minecraft:player_head", "7891d5b273ff0bc50c960b2cd86eef1c40a1b94032ae71e75475a568a8257421"),
		Button(
			11, "Auction House", "/ah", "minecraft:gold_block",
			title = "^(?:Auctions Browser|Co-op Auction House|Auction House|Manage Auctions|Your Bids|BIN Auction View|Auction View|Auctions:.*)$",
		),
		Button(12, "Bazaar", "/bz", "minecraft:player_head", "ffe2dcda41ec3aff8af50f272ec2e06a8f509e0f807f2355a1f5a33c1b66e9b4", "(?:Co-op )?Bazaar .*"),
		Button(13, "Crafting Table", "/craft", "minecraft:crafting_table", title = "Craft Item"),
	)

	private val topTab = TabModuleSetting("top_tab", "Top row")
	private val bottomTab = TabModuleSetting("bottom_tab", "Bottom row")

	@JvmField
	val module = Module(
		id = "quick_nav",
		name = "Quick Navigation",
		description = "Command tabs on menus",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = buildList<ModuleSetting> {
			add(topTab)
			buttons.take(7).forEach { add(it.shown) }
			add(bottomTab)
			buttons.drop(7).forEach { add(it.shown) }
		},
	)

	private const val TAB_WIDTH = 26
	private const val TAB_HEIGHT = 32
	private const val TAB_SPACING = 25

	private fun sprite(top: Boolean, selected: Boolean, column: Int): Identifier = Identifier.withDefaultNamespace(
		"container/creative_inventory/tab_${if (top) "top" else "bottom"}_${if (selected) "selected" else "unselected"}_${column + 1}",
	)

	private fun applies(screen: Screen): AbstractContainerScreen<*>? {
		if (!module.enabled || !SkyblockLocation.onSkyblock) return null
		if (screen !is AbstractContainerScreen<*> || screen is CreativeModeInventoryScreen) return null
		if (StorageOverlay.active(screen)) return null
		return screen
	}

	private val FORMATTING = Regex("§.")

	private fun menuTitle(screen: Screen) = screen.title.string.replace(FORMATTING, "")

	/** Where a tab sits: its x, its y, and whether it's on top. */
	private fun place(screen: AbstractContainerScreen<*>, index: Int): IntArray {
		val accessor = screen as ContainerScreenAccessor
		val top = index < 7
		val column = index % 7
		val x = accessor.`cryptic$leftPos`() + column * TAB_SPACING
		val y = if (top) accessor.`cryptic$topPos`() - 28 else accessor.`cryptic$topPos`() + accessor.`cryptic$imageHeight`() - 4
		return intArrayOf(x, y, if (top) 1 else 0)
	}

	private fun hovered(screen: AbstractContainerScreen<*>, index: Int, mouseX: Double, mouseY: Double): Boolean {
		val (x, y, top) = place(screen, index)
		// Only the part outside the menu takes the mouse.
		val minY = if (top == 1) y else y + 4
		return mouseX >= x && mouseX < x + TAB_WIDTH && mouseY >= minY && mouseY < minY + 28
	}

	/** Unselected tabs, behind the menu's background, as the creative inventory draws them. */
	@JvmStatic
	fun renderBack(screen: Screen, graphics: GuiGraphicsExtractor) {
		val container = applies(screen) ?: return
		val title = menuTitle(screen)
		for (button in buttons) {
			if (!button.shown.value || button.matches(title)) continue
			val (x, y, top) = place(container, button.index)
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(top == 1, false, button.index % 7), x, y, TAB_WIDTH, TAB_HEIGHT)
		}
	}

	/** The open menu's tab, raised in front of the background, then every icon and the tooltip. */
	@JvmStatic
	fun renderFront(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val container = applies(screen) ?: return
		val title = menuTitle(screen)
		val font = Minecraft.getInstance().font
		for (button in buttons) {
			if (!button.shown.value) continue
			val (x, y, top) = place(container, button.index)
			if (button.matches(title)) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(top == 1, true, button.index % 7), x, y, TAB_WIDTH, TAB_HEIGHT)
			}
			graphics.item(button.icon(), x + 5, y + 8 + if (top == 1) 1 else -1)
			if (hovered(container, button.index, mouseX.toDouble(), mouseY.toDouble())) {
				val lines = listOf<Component>(Component.literal(button.name.value), Component.literal("§8${button.command.value}"))
				graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY)
			}
		}
	}

	/** Runs the tab's command when one is clicked; true takes the click from the menu. */
	@JvmStatic
	fun handleMouseClick(screen: Screen, mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
		val container = applies(screen) ?: return false
		val button = buttons.firstOrNull { it.shown.value && hovered(container, it.index, mouseX, mouseY) } ?: return false
		if (mouseButton != 0) return true
		if (button.matches(menuTitle(screen))) return true
		val command = button.command.value.trim().removePrefix("/")
		if (command.isEmpty()) return true
		val client = Minecraft.getInstance()
		client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
		try {
			client.player?.connection?.sendCommand(command)
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Quick Navigation could not send /$command", error)
		}
		return true
	}

	private fun itemId(text: String): String {
		val trimmed = text.trim().lowercase()
		return if (':' in trimmed) trimmed else "minecraft:$trimmed"
	}

	private fun buildStack(item: String, head: String, dye: Int): ItemStack {
		val id = Identifier.tryParse(itemId(item)) ?: return ItemStack(Items.BARRIER)
		val type = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.BARRIER)
		val stack = ItemStack(type)
		val hash = head.trim()
		if (type == Items.PLAYER_HEAD && hash.isNotEmpty()) {
			val payload = """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/$hash"}}}"""
			val property = Property("textures", Base64.getEncoder().encodeToString(payload.toByteArray()))
			val properties = PropertyMap(ImmutableMultimap.builder<String, Property>().put("textures", property).build())
			stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(UUID.nameUUIDFromBytes(hash.toByteArray()), "_", properties)))
		}
		if (id.path.startsWith("leather_")) stack.set(DataComponents.DYED_COLOR, DyedItemColor(dye))
		return stack
	}

	private fun suggestItems(typed: String): List<String> {
		val query = typed.trim().lowercase().removePrefix("minecraft:")
		if (query.isEmpty()) return emptyList()
		return BuiltInRegistries.ITEM.keySet()
			.filter { it.namespace == "minecraft" && it.path.startsWith(query) }
			.map { it.toString() }
			.sorted()
			.take(6)
	}
}
