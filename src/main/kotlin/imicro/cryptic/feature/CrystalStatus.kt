package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Which of the five crystals are still to be placed in the Crystal Nucleus, from the tab list's
 * Crystals widget ("Jade: ✖ Not Found", "Topaz: ✔ Placed"). Shown on its own or under the Crystal
 * Hollows map. A crystal leaves once placed.
 */
object CrystalStatus {
	enum class State { NOT_FOUND, FOUND, PLACED }

	/** The five in the nucleus's order, with their slot colours. */
	enum class Crystal(val title: String, val color: () -> Int) {
		JADE("Jade", { NucleusQol.jadeColor.rgb }),
		AMBER("Amber", { NucleusQol.amberColor.rgb }),
		AMETHYST("Amethyst", { NucleusQol.amethystColor.rgb }),
		SAPPHIRE("Sapphire", { NucleusQol.sapphireColor.rgb }),
		TOPAZ("Topaz", { NucleusQol.topazColor.rgb }),
	}

	private const val STYLE_MINIMAL = 0

	private const val UNDER_MAP = 0

	@JvmField
	val missingCrystals = ToggleModuleSetting(
		id = "missing_crystals",
		label = "Missing crystals",
		defaultValue = true,
		description = "Shows which crystals are still to be placed in the nucleus, from the Crystals widget in your tab list.",
	)

	@JvmField
	val style = DropdownModuleSetting(
		id = "missing_crystals_style",
		label = "Style",
		options = listOf("Minimalistic", "Normal"),
		defaultIndex = STYLE_MINIMAL,
		description = "A gem in each crystal's colour, or each crystal written out with where it is at.",
		visibleIf = { missingCrystals.value },
	)

	@JvmField
	val placement = DropdownModuleSetting(
		id = "missing_crystals_placement",
		label = "Placement",
		options = listOf("Under the map", "Its own HUD element"),
		defaultIndex = UNDER_MAP,
		description = "Whether it follows the Crystal Hollows map or is placed on its own.",
		visibleIf = { missingCrystals.value },
	)

	val setting = missingCrystals.withMore(style, placement)

	private val ROW = Regex("""^(Jade|Amber|Amethyst|Sapphire|Topaz): (.+)$""")
	private val FORMATTING = Regex("§.")

	private var states: Map<Crystal, State> = emptyMap()

	/** What the tab list says of [crystal], or null while the Crystals widget isn't shown. */
	fun stateOf(crystal: Crystal): State? = states[crystal]

	private var widgetShown = false
	private var ticksUntilRead = 0

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(OwnElement())
	}

	fun tick(client: Minecraft) {
		if (ticksUntilRead-- > 0) return
		ticksUntilRead = 10
		val connection = client.connection ?: return
		read(connection.onlinePlayers.mapNotNull { it.tabListDisplayName?.string })
	}

	fun read(rows: List<String>) {
		val read = HashMap<Crystal, State>()
		var heading = false
		for (raw in rows) {
			val row = raw.replace(FORMATTING, "").trim()
			if (row == "Crystals:") heading = true
			val match = ROW.matchEntire(row) ?: continue
			val crystal = Crystal.entries.first { it.title == match.groupValues[1] }
			val text = match.groupValues[2]
			read[crystal] = when {
				text.contains("Not Found", ignoreCase = true) -> State.NOT_FOUND
				text.contains("Placed", ignoreCase = true) -> State.PLACED
				else -> State.FOUND
			}
		}
		widgetShown = heading || read.isNotEmpty()
		states = read
	}

	private val missing: List<Pair<Crystal, State>>
		get() = Crystal.entries.mapNotNull { crystal ->
			val state = states[crystal] ?: return@mapNotNull null
			if (state == State.PLACED) null else crystal to state
		}

	/** Something to say: crystals still missing, or the widget isn't there to read. */
	private val wanted: Boolean
		get() = NucleusQol.module.enabled && missingCrystals.value && SkyblockLocation.inCrystalHollows &&
			(!widgetShown || shown(false).isNotEmpty())

	private val attached: Boolean get() = placement.selectedIndex == UNDER_MAP && HollowsMap.mapShown

	private const val GEM = "💎"

	private val font get() = Minecraft.getInstance().font

	private fun lines(list: List<Pair<Crystal, State>>, example: Boolean): List<Component> {
		if (!widgetShown && !example) {
			return listOf(
				Component.literal(if (style.selectedIndex == STYLE_MINIMAL) "§7Crystals: §c/widget" else "§7Add the §fCrystals §7widget with §f/widget"),
			)
		}
		return list.map { (crystal, state) ->
			val status = if (state == State.FOUND) "§eFound, place it" else "§7Not found"
			Component.literal("$GEM ${crystal.title}").withColor(crystal.color() and 0xFFFFFF)
				.append(Component.literal("  $status"))
		}
	}

	/** How big [render] draws, for the element and the map. */
	fun size(example: Boolean = false): Pair<Int, Int> {
		val list = shown(example)
		if (style.selectedIndex == STYLE_MINIMAL && (widgetShown || example)) {
			return (list.size * GEM_STEP).coerceAtLeast(GEM_STEP) to font.lineHeight
		}
		val rows = lines(list, example)
		return (rows.maxOfOrNull { font.width(it) } ?: 0) to rows.size * (font.lineHeight + 1)
	}

	private const val GEM_STEP = 11

	/**
	 * Every crystal not placed, or in the minimal style only those not found (one in your pocket is
	 * as good as placed).
	 */
	private fun shown(example: Boolean): List<Pair<Crystal, State>> {
		val list = if (example) EXAMPLE else missing
		return if (style.selectedIndex == STYLE_MINIMAL) list.filter { it.second == State.NOT_FOUND } else list
	}

	/**
	 * Drawn from the top-left. Under something [frameWidth] wide, gems are centred and lines align
	 * to the edge nearer the screen's middle ([alignRight]).
	 */
	fun render(
		context: GuiGraphicsExtractor, x: Int, y: Int,
		frameWidth: Int = 0, alignRight: Boolean = false, example: Boolean = false,
	) {
		val list = shown(example)
		val (width, _) = size(example)
		if (style.selectedIndex == STYLE_MINIMAL && (widgetShown || example)) {
			val left = if (frameWidth > 0) x + (frameWidth - width) / 2 else x
			list.forEachIndexed { index, (crystal, _) ->
				val gx = left + index * GEM_STEP
				context.text(font, GEM, gx, y, 0xFF000000.toInt() or crystal.color())
			}
			return
		}
		lines(list, example).forEachIndexed { index, line ->
			val lineX = if (frameWidth > 0 && alignRight) x + frameWidth - font.width(line) else x
			context.text(font, line, lineX, y + index * (font.lineHeight + 1), 0xFFFFFFFF.toInt())
		}
	}

	fun attachedHeight(): Int = if (attached && wanted) size().second + 4 else 0

	fun renderUnderMap(context: GuiGraphicsExtractor, mapWidth: Int, top: Int, alignRight: Boolean) {
		if (attached && wanted) render(context, 0, top + 4, mapWidth, alignRight)
	}

	fun renderExampleUnderMap(context: GuiGraphicsExtractor, mapWidth: Int, top: Int, alignRight: Boolean) {
		if (missingCrystals.value && placement.selectedIndex == UNDER_MAP) render(context, 0, top + 4, mapWidth, alignRight, example = true)
	}

	fun exampleHeightUnderMap(): Int =
		if (missingCrystals.value && placement.selectedIndex == UNDER_MAP) size(example = true).second + 4 else 0

	private val EXAMPLE = listOf(Crystal.JADE to State.NOT_FOUND, Crystal.AMBER to State.FOUND, Crystal.TOPAZ to State.NOT_FOUND)

	fun describe(): String =
		if (!widgetShown) "Crystals widget: not in the tab list" else
			"Crystals: " + Crystal.entries.joinToString { "${it.title} ${states[it]?.name?.lowercase() ?: "?"}" }

	private class OwnElement : HudElement("missing_crystals", "Missing Crystals", 0.02, 0.62) {
		override val width: Int get() = maxOf(size(example = !wanted).first, 20)
		override val height: Int get() = maxOf(size(example = !wanted).second, 9)

		override fun isVisible(): Boolean = wanted && !attached

		override fun showInEditor(): Boolean =
			NucleusQol.module.enabled && missingCrystals.value && !(placement.selectedIndex == UNDER_MAP && HollowsMap.showMap.value)

		override fun render(context: GuiGraphicsExtractor) = render(context, 0, 0)

		override fun renderExample(context: GuiGraphicsExtractor) =
			if (wanted) render(context, 0, 0) else render(context, 0, 0, example = true)
	}
}
