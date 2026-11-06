package imicro.cryptic.feature

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.blaze3d.pipeline.RenderPipeline
import imicro.cryptic.Cryptic
import imicro.cryptic.mixin.AnvilScreenAccessor
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.mixin.PackTweaksMixins
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.inventory.Slot
import java.util.WeakHashMap

/**
 * The format's screen layouts (`overrides/screen_layout`: a background, moved slots, titles and
 * fields) and menu text colours (`overrides/text_colors.json`).
 */
object PackScreens {
	class Background(val texture: Identifier, val x: Int, val y: Int, val width: Int, val height: Int)
	class Mover(val x: Int?, val y: Int?, val width: Int?, val height: Int?, val align: String?, val replace: Component?)
	class SlotMove(val index: Int, val x: Int, val y: Int)

	class Layout(
		val label: PackPredicates.StringMatcher,
		val screenType: Identifier?,
		val background: Background?,
		val slots: List<SlotMove>,
		val titles: Map<String, Mover>,
		val nameField: Mover?,
		val signLines: List<Mover>?,
	)

	class TextColours(val default: Int, val overrides: List<Pair<PackPredicates.StringMatcher, Int>>)

	class Data(val layouts: List<Layout> = emptyList(), val colours: TextColours? = null)

	val SIGN: Identifier = Identifier.fromNamespaceAndPath(PackTweaks.NAMESPACE, "sign")
	val HANGING_SIGN: Identifier = Identifier.fromNamespaceAndPath(PackTweaks.NAMESPACE, "hanging_sign")

	fun read(manager: ResourceManager, readJson: (ResourceManager, Identifier) -> JsonElement?): Data {
		val layouts = PackTweaks.readAll(manager, "overrides/screen_layout").mapNotNull { (file, element) ->
			try {
				parseLayout(element.asJsonObject)
			} catch (e: Exception) {
				Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", file, e.message)
				null
			}
		}
		val colourFile = Identifier.fromNamespaceAndPath(PackTweaks.NAMESPACE, "overrides/text_colors.json")
		val colours = readJson(manager, colourFile)?.let { element ->
			try {
				val json = element.asJsonObject
				TextColours(
					json.get("defaultColor").asInt,
					json.getAsJsonArray("overrides")?.map { o ->
						val obj = o.asJsonObject
						PackPredicates.StringMatcher.parse(obj.get("predicate")) to obj.get("override").asInt
					} ?: emptyList(),
				)
			} catch (e: Exception) {
				Cryptic.LOGGER.warn("Pack Tweaks: couldn't read {}: {}", colourFile, e.message)
				null
			}
		}
		return Data(layouts, colours)
	}

	private fun parseLayout(json: JsonObject): Layout {
		val predicates = json.getAsJsonObject("predicates")
		fun mover(o: JsonObject) = Mover(
			o.get("x")?.asInt, o.get("y")?.asInt, o.get("width")?.asInt, o.get("height")?.asInt,
			o.get("align")?.asString,
			o.get("replace")?.asString?.let { Component.literal(it) },
		)
		return Layout(
			label = PackPredicates.StringMatcher.parse(predicates.get("label")),
			screenType = predicates.get("screenType")?.asString?.let(Identifier::parse),
			background = json.getAsJsonObject("background")?.let {
				Background(Identifier.parse(it.get("texture").asString), it.get("x").asInt, it.get("y").asInt, it.get("width").asInt, it.get("height").asInt)
			},
			slots = json.getAsJsonArray("slots")?.map { s -> s.asJsonObject.let { SlotMove(it.get("index").asInt, it.get("x").asInt, it.get("y").asInt) } } ?: emptyList(),
			titles = listOf("containerTitle", "playerTitle", "repairCostTitle").mapNotNull { key -> json.getAsJsonObject(key)?.let { key to mover(it) } }.toMap(),
			nameField = json.getAsJsonObject("nameField")?.let(::mover),
			signLines = json.getAsJsonArray("signLines")?.map { mover(it.asJsonObject) },
		)
	}

	// ---- Which layout a screen has -----------------------------------------------------------

	private val layouts = WeakHashMap<Screen, Any>()
	private object NoLayout

	private fun screenType(screen: Screen): Identifier? = when (screen) {
		is HangingSignEditScreen -> HANGING_SIGN
		is AbstractSignEditScreen -> SIGN
		// The player's own inventory menu has no type and throws when asked.
		is AbstractContainerScreen<*> -> runCatching { BuiltInRegistries.MENU.getKey(screen.menu.type) }.getOrNull()
		else -> null
	}

	@JvmStatic
	fun layoutFor(screen: Screen): Layout? {
		if (!PackTweaks.on(PackTweaks.screenLayouts)) return null
		layouts[screen]?.let { return it as? Layout }
		val all = PackTweaks.data.screens.layouts
		val type by lazy { screenType(screen) }
		val found = all.firstOrNull { layout ->
			layout.label.matches(PackPredicates.render(screen.title, layout.label.preserve)) && (layout.screenType == null || layout.screenType == type)
		}
		layouts[screen] = found ?: NoLayout
		return found
	}

	// ---- Slots, titles and fields ------------------------------------------------------------

	/** Where moved slots were, to put them back: the player's own menu outlives its screen. */
	private val movedSlots = WeakHashMap<Slot, Pair<Int, Int>>()

	@JvmStatic
	fun onInit(screen: AbstractContainerScreen<*>) {
		layouts.remove(screen)
		restoreSlots()
		val layout = layoutFor(screen) ?: return
		val slots = screen.menu.slots
		for (move in layout.slots) {
			val slot = slots.getOrNull(move.index) ?: continue
			movedSlots.putIfAbsent(slot, slot.x to slot.y)
			(slot as PackTweaksMixins.SlotAccessor).`cryptic$setX`(move.x)
			(slot as PackTweaksMixins.SlotAccessor).`cryptic$setY`(move.y)
		}
		val labels = screen as PackTweaksMixins.LabelAccessor
		layout.titles["containerTitle"]?.let { m ->
			m.x?.let(labels::`cryptic$setTitleLabelX`)
			m.y?.let(labels::`cryptic$setTitleLabelY`)
		}
		layout.titles["playerTitle"]?.let { m ->
			m.x?.let(labels::`cryptic$setInventoryLabelX`)
			m.y?.let(labels::`cryptic$setInventoryLabelY`)
		}
		if (screen is AnvilScreen) {
			layout.nameField?.let { m ->
				val field = (screen as AnvilScreenAccessor).`cryptic$name`()
				val base = screen as ContainerScreenAccessor
				m.x?.let { field.x = base.`cryptic$leftPos`() + it }
				m.y?.let { field.y = base.`cryptic$topPos`() + it }
				m.width?.let { field.width = it }
				m.height?.let { field.height = it }
			}
		}
	}

	@JvmStatic
	fun restoreSlots() {
		for ((slot, pos) in movedSlots) {
			(slot as PackTweaksMixins.SlotAccessor).`cryptic$setX`(pos.first)
			(slot as PackTweaksMixins.SlotAccessor).`cryptic$setY`(pos.second)
		}
		movedSlots.clear()
	}

	// ---- Background --------------------------------------------------------------------------

	/** The screen whose background is being drawn, between its start and its labels. */
	@JvmField
	var drawingBackground: AbstractContainerScreen<*>? = null
	private var replacedTexture: Identifier? = null
	private var drawing = false

	@JvmStatic
	fun beginBackground(screen: AbstractContainerScreen<*>) {
		drawingBackground = if (layoutFor(screen)?.background != null) screen else null
		replacedTexture = null
	}

	@JvmStatic
	fun endBackground() {
		drawingBackground = null
	}

	/**
	 * Swaps the screen's own background texture for the layout's: the first container texture
	 * drawn is replaced, and later parts of the same texture (a chest's lower half) are dropped.
	 */
	@JvmStatic
	fun replaceBlit(graphics: GuiGraphicsExtractor, pipeline: RenderPipeline, texture: Identifier): Boolean {
		val screen = drawingBackground ?: return false
		if (drawing) return false
		replacedTexture?.let { return it == texture }
		if (texture.namespace != "minecraft" || !texture.path.startsWith("textures/gui/container/")) return false
		val background = layoutFor(screen)?.background ?: return false
		replacedTexture = texture
		val base = screen as ContainerScreenAccessor
		drawing = true
		try {
			graphics.blit(
				RenderPipelines.GUI_TEXTURED, background.texture,
				base.`cryptic$leftPos`() + background.x, base.`cryptic$topPos`() + background.y,
				0f, 0f, background.width, background.height, background.width, background.height,
			)
		} finally {
			drawing = false
		}
		return true
	}

	/** Counts a click on the wider background as inside, so it doesn't drop the held item. */
	@JvmStatic
	fun insideBackground(screen: AbstractContainerScreen<*>, mx: Double, my: Double): Boolean {
		val background = layoutFor(screen)?.background ?: return false
		val base = screen as ContainerScreenAccessor
		val x = base.`cryptic$leftPos`() + background.x
		val y = base.`cryptic$topPos`() + background.y
		return mx >= x && my >= y && mx < x + background.width && my < y + background.height
	}

	@JvmStatic
	fun drawSignBackground(screen: AbstractSignEditScreen, graphics: GuiGraphicsExtractor): Boolean {
		val background = layoutFor(screen)?.background ?: return false
		graphics.blit(
			RenderPipelines.GUI_TEXTURED, background.texture, background.x, background.y,
			0f, 0f, background.width, background.height, background.width, background.height,
		)
		return true
	}

	/** A sign line's offset, found from its height on the sign. */
	@JvmStatic
	fun signLineOffset(screen: AbstractSignEditScreen, y: Int, lineHeight: Int): Mover? {
		val lines = layoutFor(screen)?.signLines ?: return null
		val index = (y + 2 * lineHeight) / lineHeight.coerceAtLeast(1)
		return lines.getOrNull(index)
	}

	// ---- Labels ------------------------------------------------------------------------------

	/** The screen whose labels are being drawn. */
	@JvmField
	var drawingLabels: AbstractContainerScreen<*>? = null
	private var relabelling = false

	/**
	 * Draws a label of [drawingLabels] moved, replaced or recoloured as the layout and text
	 * colours say. False leaves the call alone.
	 */
	@JvmStatic
	fun drawLabel(graphics: GuiGraphicsExtractor, font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean): Boolean {
		val screen = drawingLabels ?: return false
		if (relabelling) return false
		val labels = screen as PackTweaksMixins.LabelAccessor
		val layout = layoutFor(screen)
		val isTitle = x == labels.`cryptic$titleLabelX`() && y == labels.`cryptic$titleLabelY`()
		val isInventory = !isTitle && x == labels.`cryptic$inventoryLabelX`() && y == labels.`cryptic$inventoryLabelY`()
		// An anvil's only other label is its repair cost.
		val isRepairCost = !isTitle && !isInventory && screen is AnvilScreen
		val mover = when {
			isTitle -> layout?.titles?.get("containerTitle")
			isInventory -> layout?.titles?.get("playerTitle")
			isRepairCost -> layout?.titles?.get("repairCostTitle")
			else -> null
		}
		val colours = if (PackTweaks.on(PackTweaks.textColours)) PackTweaks.data.screens.colours else null
		if (mover == null && colours == null) return false

		val shown = mover?.replace?.visualOrderText ?: text
		var nx = x
		var ny = y
		// Titles were moved at init; the repair cost has no field to move.
		if (mover != null && isRepairCost) {
			mover.x?.let { nx = it }
			mover.y?.let { ny = it }
		}
		when (mover?.align) {
			"center" -> nx -= font.width(shown) / 2
			"right" -> nx -= font.width(shown)
		}
		var newColor = color
		if (colours != null) {
			val plain = plain(shown)
			newColor = colours.overrides.firstOrNull { (matcher, _) -> matcher.matches(plain) }?.second ?: colours.default
		}
		relabelling = true
		try {
			graphics.text(font, shown, nx, ny, newColor, shadow)
		} finally {
			relabelling = false
		}
		return true
	}

	private fun plain(text: FormattedCharSequence): String {
		val out = StringBuilder()
		text.accept { _, _, codePoint -> out.appendCodePoint(codePoint); true }
		return out.toString()
	}
}
