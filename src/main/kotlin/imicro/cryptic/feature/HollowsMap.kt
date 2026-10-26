package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/**
 * A map of the Crystal Hollows with the places found this lobby, also labelled in the world. Each
 * place keeps how it was found, and a better source always wins:
 * 1. **Crystal**: the crystal's space Nucleus Features found among the barriers.
 * 2. **Said**: a line only said at that place, heard where you stand.
 * 3. **Walked**: the middle of everywhere you've stood in it, by the sidebar; it closes in as you
 * walk, and only overrides the two below after a few samples.
 * 4. **Skyblocker**: read from Skyblocker when installed.
 * 5. **Chat**: coordinates posted next to a place's name; the first post wins.
 *
 * The first two are exact (filled squares on the map); the rest are rings. The map itself is drawn:
 * the four quadrants in their colours with no lines between them, the Nucleus's core (which can't be
 * mined) as a square at its real size, the quadrants' names over everything but you, and an
 * outline. Inspired by Skyblocker's Crystals HUD and waypoints.
 */
object HollowsMap {
	enum class Source(val exact: Boolean = false, val refines: Boolean = false) {
		CHAT,
		COMPASS(refines = true),
		SKYBLOCKER(refines = true),
		WALKED(refines = true),
		/** Recognised by its blocks as its chunks arrived. */
		SCANNED(exact = true),
		/** Its NPC's or boss's nametag, in sight. */
		NPC(exact = true, refines = true),
		SAID(exact = true),
		CRYSTAL(exact = true, refines = true),
	}

	/**
	 * The places that move between lobbies. [zone] is its sidebar zone if it has one; [saidThere]
	 * the start of a line only said there; [words] what players call it in chat.
	 */
	enum class Place(
		val title: String,
		private val rgb: Int,
		val words: List<String>,
		val zone: String? = null,
		val saidThere: String? = null,
		val small: Boolean = false,
		val crystal: String? = null,
		private val crystalColor: (() -> ColorModuleSetting)? = null,
	) {
		JUNGLE_TEMPLE(
			"Jungle Temple", 0xAA00AA, listOf("jungle temple", "temple", "jungle"), zone = "Jungle Temple",
			saidThere = "[NPC] Kalhuiki Door Guardian:", crystal = "Amethyst", crystalColor = { NucleusQol.amethystColor },
		),
		MINES_OF_DIVAN(
			"Mines of Divan", 0x55FF55, listOf("mines of divan", "divan", "mines"), zone = "Mines of Divan",
			saidThere = "Jade Crystal", crystal = "Jade", crystalColor = { NucleusQol.jadeColor },
		),
		GOBLIN_QUEENS_DEN(
			"Goblin Queen's Den", 0xFFAA00, listOf("goblin queen", "queens den", "queen", "den"), zone = "Goblin Queen's Den",
			saidThere = "Amber Crystal", crystal = "Amber", crystalColor = { NucleusQol.amberColor },
		),
		LOST_PRECURSOR_CITY(
			"Lost Precursor City", 0x55FFFF, listOf("precursor city", "precursor", "city", "lpc"), zone = "Lost Precursor City",
			saidThere = "Sapphire Crystal", crystal = "Sapphire", crystalColor = { NucleusQol.sapphireColor },
		),
		KHAZAD_DUM(
			"Khazad-dûm", 0xFFFF55, listOf("khazad-dum", "khazad dum", "khazad"), zone = "Khazad-dûm",
			saidThere = "Topaz Crystal", crystal = "Topaz", crystalColor = { NucleusQol.topazColor },
		),
		FAIRY_GROTTO("Fairy Grotto", 0xFF55FF, listOf("fairy grotto", "grotto", "fairy"), zone = "Fairy Grotto", small = true),
		DRAGONS_LAIR("Dragon's Lair", 0xFFD24A, listOf("dragons lair", "dragon", "lair"), zone = "Dragon's Lair", saidThere = "[NPC] Golden Dragon:"),
		CORLEONE("Corleone", 0xFFFFFF, listOf("corleone"), small = true),
		KING_YOLKAR("King Yolkar", 0xFF5555, listOf("king yolkar", "yolkar", "king"), saidThere = "[NPC] King Yolkar:", small = true),
		ODAWA("Odawa", 0x00AA00, listOf("odawa"), saidThere = "[NPC] Odawa:", small = true),
		KEY_GUARDIAN("Key Guardian", 0xAAAAAA, listOf("key guardian", "guardian"), small = true),
		XALX("Xalx", 0x5555FF, listOf("xalx"), saidThere = "[NPC] Xalx:", small = true),
		/** Where a wishing compass pointed when it couldn't be told what's there. */
		COMPASS_TARGET("Compass target", 0xFFFFFF, listOf("compass target"), small = true),
		;

		val color: Int get() = crystalColor?.invoke()?.rgb ?: rgb

		/** [words] as whole words, for a lower-case line with apostrophes removed. */
		val wordPattern: Regex = Regex(words.joinToString("|", "\\b(?:", ")\\b") { Regex.escape(it) })

		companion object {
			fun named(name: String): Place? = entries.firstOrNull { it.title.equals(name, ignoreCase = true) }
			fun inZone(zone: String?): Place? = zone?.let { z -> entries.firstOrNull { it.zone == z } }
		}
	}

	class Mark(val pos: BlockPos, val source: Source)

	private val mapSection = SectionModuleSetting("map_section", "Crystal Hollows map")

	@JvmField
	val showMap = ToggleModuleSetting(
		id = "hollows_map",
		label = "Crystal Hollows map",
		defaultValue = true,
		description = "A map of the Hollows on your HUD with the places found in this lobby and where you are. A filled square is exact; a ring is about there. Move and size it in the HUD editor.",
	)

	private fun mapColour(id: String, label: String, argb: Int) = ColorModuleSetting(
		id = id,
		label = label,
		defaultRgb = argb and 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = argb ushr 24,
		visibleIf = { showMap.value },
	)

	// The user's own picks, made the defaults.
	@JvmField val jungleColour = mapColour("hollows_jungle_colour", "Jungle", 0x80AD2BB6.toInt())
	@JvmField val mithrilColour = mapColour("hollows_mithril_colour", "Mithril", 0x8025E139.toInt())
	@JvmField val goblinColour = mapColour("hollows_goblin_colour", "Goblin", 0x80C57926.toInt())
	@JvmField val precursorColour = mapColour("hollows_precursor_colour", "Precursor", 0x8025AAE1.toInt())
	@JvmField val nucleusColour = mapColour("hollows_nucleus_colour", "Nucleus", 0xFF515151.toInt())
	@JvmField val outlineColour = mapColour("hollows_outline_colour", "Outline", 0xFF000000.toInt())

	@JvmField
	val labels = ToggleModuleSetting(
		id = "hollows_labels",
		label = "POI Waypoints",
		defaultValue = true,
		description = "Each point of interest found, named in the world with how far away it is. A ~ before the distance means it is about there.",
	)

	@JvmField
	val poiPlaces = imicro.cryptic.gui.MultiSelectModuleSetting(
		id = "hollows_poi_places",
		label = "Show",
		options = Place.entries.map { it.title },
		description = "Which places get a waypoint. The map shows them all.",
		visibleIf = { labels.value },
	)

	@JvmField
	val chatCoords = ToggleModuleSetting(
		id = "hollows_chat_coords",
		label = "Places from chat",
		defaultValue = true,
		description = "Coordinates other players post with a place's name, like \"divan 600 90 550\", are added. Several in one message are each matched to the name nearest them.",
	)

	@JvmField
	val fromSkyblocker = ToggleModuleSetting(
		id = "hollows_from_skyblocker",
		label = "Places from Skyblocker",
		defaultValue = true,
		description = "With Skyblocker installed, the places it knows are shown too, including those its users share with each other.",
	)

	val settings by lazy {
		listOf(
			mapSection,
			showMap.withMore(jungleColour, mithrilColour, goblinColour, precursorColour, nucleusColour, outlineColour),
			chatCoords, fromSkyblocker, CrystalStatus.setting,
		)
	}

	private val marks = LinkedHashMap<Place, Mark>()

	/** Everywhere you've stood in each place, summed, for its walked mark. */
	private class Walk {
		var x = 0L
		var y = 0L
		var z = 0L
		var count = 0
		fun add(pos: BlockPos) { x += pos.x; y += pos.y; z += pos.z; count++ }
		val middle get() = BlockPos((x / count).toInt(), (y / count).toInt(), (z / count).toInt())
	}

	private val walks = HashMap<Place, Walk>()

	/** Walked samples (two seconds apart) before a walked mark replaces one from chat or Skyblocker. */
	private const val WALKED_SAMPLES_TO_TAKE_OVER = 3

	/**
	 * Keeps [pos] for [place] if found a better way than what's known, or the same way when that
	 * way refines.
	 */
	fun markOf(place: Place): Mark? = marks[place]

	/** Takes a crystal-box mark back, when the box turned out to be another crystal's. */
	fun dropCrystalMark(place: Place) {
		if (marks[place]?.source == Source.CRYSTAL) marks.remove(place)
	}

	/** Whether [place] is known, at least as well as [source] would know it. */
	fun knownAtLeast(place: Place, source: Source): Boolean = (marks[place]?.source ?: return false) >= source

	fun offer(place: Place, pos: BlockPos, source: Source) {
		val current = marks[place]
		if (current != null && (current.source > source || (current.source == source && !source.refines))) return
		marks[place] = Mark(pos, source)
	}

	private fun resetLobby() {
		marks.clear()
		walks.clear()
	}

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(MapElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetLobby() }
	}

	val mapShown: Boolean get() = NucleusQol.module.enabled && showMap.value && SkyblockLocation.inCrystalHollows

	private var ticksUntilUpdate = 0

	fun tick(client: Minecraft) {
		if (ticksUntilUpdate-- > 0) return
		ticksUntilUpdate = 40
		val player = client.player ?: return

		Place.inZone(SkyblockLocation.zone())?.let { place ->
			val walk = walks.getOrPut(place, ::Walk)
			walk.add(player.blockPosition())
			if (place !in marks || walk.count >= WALKED_SAMPLES_TO_TAKE_OVER) offer(place, walk.middle, Source.WALKED)
		}
		for (place in Place.entries) {
			val crystal = place.crystal ?: continue
			NucleusQol.crystalSpaceCenter(crystal)?.let { offer(place, BlockPos.containing(it), Source.CRYSTAL) }
		}
		if (fromSkyblocker.value) importSkyblocker()
		readNametags(client)
	}

	/**
	 * Places told by who's standing there: an NPC's or a boss's nametag (King Yolkar, Odawa, Bal...)
	 * is the place, exactly, as soon as it's in sight. Names are matched as whole words, after the
	 * level and health Hypixel puts round a mob's.
	 */
	private val NAMETAGS = listOf(
		"King Yolkar" to Place.KING_YOLKAR,
		"Odawa" to Place.ODAWA,
		"Xalx" to Place.XALX,
		"Key Guardian" to Place.KEY_GUARDIAN,
		"Corleone" to Place.CORLEONE,
		"Professor Robot" to Place.LOST_PRECURSOR_CITY,
		"Kalhuiki Door Guardian" to Place.JUNGLE_TEMPLE,
		"Golden Dragon" to Place.DRAGONS_LAIR,
		"Bal" to Place.KHAZAD_DUM,
	).map { (name, place) -> Regex("""\b${Regex.escape(name)}\b""") to place }

	private fun readNametags(client: Minecraft) {
		val level = client.level ?: return
		for (entity in level.entitiesForRendering()) {
			if (entity !is net.minecraft.world.entity.decoration.ArmorStand) continue
			val name = entity.customName?.string?.replace(FORMATTING, "") ?: continue
			val place = NAMETAGS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(name) }?.second ?: continue
			offer(place, entity.blockPosition(), Source.NPC)
		}
	}

	private val FORMATTING = Regex("§.")

	/**
	 * Three numbers that could be a Hollows block: x and z of three digits, y of two or three,
	 * separated by commas or spaces, optionally named ("x: 600, y: 90, z: 550"). Not part of a
	 * longer number or a decimal.
	 */
	private val COORDINATES = Regex(
		"""(?<![\d.])(?:x\s*:?\s*)?(\d{3})\s*[,\s]\s*(?:y\s*:?\s*)?(\d{2,3})\s*[,\s]\s*(?:z\s*:?\s*)?(\d{3})(?![\d.])""",
		RegexOption.IGNORE_CASE,
	)

	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || !NucleusQol.module.enabled || !SkyblockLocation.inCrystalHollows) return
		val player = Minecraft.getInstance().player ?: return
		val text = content.string.replace(FORMATTING, "").trim()

		for (place in Place.entries) {
			val said = place.saidThere ?: continue
			val matches = if (said.endsWith(":")) text.startsWith(said) else text == said
			if (matches) offer(place, player.blockPosition(), Source.SAID)
		}

		if (chatCoords.value) readPostedPlaces(text)
	}

	/**
	 * Places in a player's message: each coordinate set goes to the place named nearest it, so
	 * "divan 600 90 550, temple 300 110 330" gives both.
	 */
	private fun readPostedPlaces(line: String) {
		val colon = line.indexOf(": ")
		if (colon < 0) return
		val lower = line.substring(colon + 2).lowercase().replace("'", "").replace('û', 'u')

		val names = Place.entries.flatMap { place -> place.wordPattern.findAll(lower).map { place to it.range } }
		if (names.isEmpty()) return
		for (match in COORDINATES.findAll(lower)) {
			val (x, y, z) = match.destructured
			val pos = BlockPos(x.toInt(), y.toInt(), z.toInt())
			if (!inHollows(pos)) continue
			val place = names.minByOrNull { (_, range) -> gap(range, match.range) }?.first ?: continue
			offer(place, pos, Source.CHAT)
		}
	}

	private fun gap(a: IntRange, b: IntRange): Int = when {
		a.last < b.first -> b.first - a.last
		b.last < a.first -> a.first - b.last
		else -> 0
	}

	private fun inHollows(pos: BlockPos) = pos.x in 202..823 && pos.z in 202..823 && pos.y in 31..188

	private val skyblockerInstalled by lazy { FabricLoader.getInstance().isModLoaded("skyblocker") }

	private val skyblockerWaypoints: java.lang.reflect.Field? by lazy {
		runCatching {
			Class.forName("de.hysky.skyblocker.skyblock.dwarven.CrystalsLocationsManager")
				.getDeclaredField("activeWaypoints").apply { isAccessible = true }
		}.onFailure { Cryptic.LOGGER.info("Skyblocker's Crystal Hollows waypoints could not be read: {}", it.toString()) }.getOrNull()
	}

	private fun importSkyblocker() {
		if (!skyblockerInstalled) return
		val field = skyblockerWaypoints ?: return
		runCatching {
			val map = field.get(null) as? Map<*, *> ?: return
			for ((name, label) in map) {
				val place = Place.named(name as? String ?: continue) ?: continue
				val pos = positionOf(label ?: continue) ?: continue
				offer(place, pos, Source.SKYBLOCKER)
			}
		}.onFailure { Cryptic.LOGGER.debug("Reading Skyblocker's waypoints failed", it) }
	}

	private fun positionOf(label: Any): BlockPos? {
		var type: Class<*>? = label.javaClass
		while (type != null) {
			for (name in listOf("pos", "centerPos")) {
				val field = runCatching { type.getDeclaredField(name) }.getOrNull() ?: continue
				field.isAccessible = true
				when (val value = field.get(label)) {
					is BlockPos -> return value
					is Vec3 -> return BlockPos.containing(value)
				}
			}
			type = type.superclass
		}
		return null
	}

	private fun render(context: LevelRenderContext) {
		if (!NucleusQol.module.enabled || !labels.value || !SkyblockLocation.inCrystalHollows || marks.isEmpty()) return
		val client = Minecraft.getInstance()
		val eye = client.player?.eyePosition ?: return
		val orientation = client.gameRenderer.mainCamera().rotation()
		// Down on screen, so the second line sits under the first however you look at it.
		val down = org.joml.Vector3f(0f, -1f, 0f).rotate(orientation)
		for ((place, mark) in marks) {
			if (!poiPlaces.isSelected(place.title)) continue
			val at = Vec3.atCenterOf(mark.pos)
			val distance = eye.distanceTo(at)
			if (distance < 6) continue
			val about = if (mark.source.exact) "" else "~"
			// The same size on screen near or far.
			val scale = (distance / 10.0).coerceIn(1.0, 40.0).toFloat()
			val name = Vec3(at.x, at.y + 1.5, at.z)
			// The distance on its own line, centred under the name, a line down on screen.
			val under = name.add(down.x * LINE_HEIGHT * scale, down.y * LINE_HEIGHT * scale, down.z * LINE_HEIGHT * scale)
			label(context, orientation, Component.literal(place.title).withColor(place.color), name, scale)
			label(context, orientation, Component.literal("§7$about${distance.roundToInt()}m"), under, scale)
		}
	}

	/** One line of world text: the font's height at [WorldRender]'s scale, in blocks at size one. */
	private const val LINE_HEIGHT = 0.25

	private fun label(context: LevelRenderContext, orientation: org.joml.Quaternionf, text: Component, at: Vec3, scale: Float) =
		WorldRender.drawText(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			orientation = orientation,
			text = text,
			x = at.x,
			y = at.y,
			z = at.z,
			scale = scale,
			seeThrough = true,
			centered = true,
		)

	private val PLAYER_ICON: Identifier = Identifier.withDefaultNamespace("textures/map/decorations/player.png")

	/** The Hollows run from 201 to 824 on both axes, as measured in game. */
	private const val HOLLOWS_MIN = 201.0
	private const val HOLLOWS_SIZE = 624.0

	/** The quadrants meet here, on both axes. */
	private const val HOLLOWS_MIDDLE = 513.0

	/** The Nucleus's core, which can't be mined, as measured in game (ends inclusive). */
	private const val CORE_MIN_X = 456.0
	private const val CORE_MAX_X = 566.0
	private const val CORE_MIN_Z = 458.0
	private const val CORE_MAX_Z = 565.0

	private const val MAP_SIZE = 102

	/** Text keeps this many pixels clear of its area's edges. */
	private const val LABEL_MARGIN = 1

	private fun onMap(blocks: Double) = ((blocks - HOLLOWS_MIN) / HOLLOWS_SIZE * MAP_SIZE).toFloat().coerceIn(0f, MAP_SIZE.toFloat())

	/** [scale] is the element's, for an outline one pixel wide on screen whatever the size. */
	private fun drawMap(context: GuiGraphicsExtractor, example: Boolean, scale: Double) {
		val middle = onMap(HOLLOWS_MIDDLE).roundToInt()
		val size = MAP_SIZE
		// The quadrants, edge to edge.
		context.fill(0, 0, middle, middle, jungleColour.argb)
		context.fill(middle, 0, size, middle, mithrilColour.argb)
		context.fill(0, middle, middle, size, goblinColour.argb)
		context.fill(middle, middle, size, size, precursorColour.argb)

		val coreLeft = onMap(CORE_MIN_X).roundToInt()
		val coreTop = onMap(CORE_MIN_Z).roundToInt()
		val coreRight = onMap(CORE_MAX_X + 1).roundToInt()
		val coreBottom = onMap(CORE_MAX_Z + 1).roundToInt()
		context.fill(coreLeft, coreTop, coreRight, coreBottom, nucleusColour.argb)

		for ((place, mark) in if (example) EXAMPLE_MARKS else marks) {
			drawMarker(context, place, mark, onMap(mark.pos.x.toDouble()), onMap(mark.pos.z.toDouble()))
		}

		// The names over the places, each centred in its own area.
		label(context, "Jungle", 0, 0, middle, middle)
		label(context, "Mithril", middle, 0, size, middle)
		label(context, "Goblin", 0, middle, middle, size)
		label(context, "Precursor", middle, middle, size, size)
		label(context, "Nucleus", coreLeft, coreTop, coreRight, coreBottom, margin = 1)

		// The outline, inside the edge so the element's size doesn't change, drawn unscaled so every
		// side is one whole pixel.
		val outline = outlineColour.argb
		val pose = context.pose()
		pose.pushMatrix()
		pose.scale((1 / scale).toFloat(), (1 / scale).toFloat())
		val edge = (size * scale).roundToInt()
		context.fill(0, 0, edge, 1, outline)
		context.fill(0, edge - 1, edge, edge, outline)
		context.fill(0, 0, 1, edge, outline)
		context.fill(edge - 1, 0, edge, edge, outline)
		pose.popMatrix()

		// You, as the vanilla map draws a player.
		val player = Minecraft.getInstance().player ?: return
		val px = if (example) MAP_SIZE / 2f else onMap(player.x)
		val py = if (example) MAP_SIZE / 2f else onMap(player.z)
		pose.pushMatrix()
		pose.translate(px, py)
		pose.rotate(Mth.DEG_TO_RAD * (player.yRot + 180f))
		pose.translate(-4f, -4f)
		context.blit(RenderPipelines.GUI_TEXTURED, PLAYER_ICON, 0, 0, 0f, 0f, 8, 8, 8, 8)
		pose.popMatrix()
	}

	/** [text] centred in the box, in Minecraft's font, shrunk to fit it if need be. */
	private fun label(context: GuiGraphicsExtractor, text: String, left: Int, top: Int, right: Int, bottom: Int, margin: Int = LABEL_MARGIN) {
		val font = Minecraft.getInstance().font
		val room = (right - left - margin * 2).toFloat()
		val scale = minOf(1f, room / font.width(text))
		if (scale <= 0f) return
		val pose = context.pose()
		pose.pushMatrix()
		pose.translate((left + right) / 2f, (top + bottom) / 2f)
		pose.scale(scale, scale)
		context.text(font, text, -font.width(text) / 2, -(font.lineHeight - 1) / 2, -1)
		pose.popMatrix()
	}

	/** A filled square where a place is exact; a ring where it's approximate. */
	private fun drawMarker(context: GuiGraphicsExtractor, place: Place, mark: Mark, cx: Float, cy: Float) {
		val half = if (place.small) 2 else 3
		val left = cx.roundToInt() - half
		val top = cy.roundToInt() - half
		val size = half * 2
		val color = 0xFF000000.toInt() or place.color
		val black = 0xFF000000.toInt()
		context.fill(left - 1, top - 1, left + size + 1, top + size + 1, black)
		context.fill(left, top, left + size, top + size, color)
		if (!mark.source.exact) context.fill(left + 1, top + 1, left + size - 1, top + size - 1, black)
	}

	private val EXAMPLE_MARKS = mapOf(
		Place.MINES_OF_DIVAN to Mark(BlockPos(700, 90, 300), Source.CRYSTAL),
		Place.JUNGLE_TEMPLE to Mark(BlockPos(320, 110, 330), Source.WALKED),
		Place.GOBLIN_QUEENS_DEN to Mark(BlockPos(300, 90, 700), Source.CRYSTAL),
		Place.KHAZAD_DUM to Mark(BlockPos(520, 60, 760), Source.CHAT),
		Place.FAIRY_GROTTO to Mark(BlockPos(420, 80, 420), Source.WALKED),
	)

	fun describe(): List<String> = listOf(
		"Places this lobby: " + marks.entries.joinToString {
			"${it.key.title} ${it.value.pos.toShortString()} (${it.value.source.name.lowercase()})"
		}.ifEmpty { "none" },
		"Skyblocker installed: $skyblockerInstalled, readable: ${skyblockerWaypoints != null}",
	)

	private class MapElement : HudElement("hollows_map", "Crystal Hollows Map", 0.74, 0.04) {
		override val width: Int = MAP_SIZE
		override val height: Int get() = MAP_SIZE + maxOf(CrystalStatus.attachedHeight(), if (mapShown) 0 else CrystalStatus.exampleHeightUnderMap())

		override fun isVisible(): Boolean = mapShown

		override fun showInEditor(): Boolean = NucleusQol.module.enabled && showMap.value

		override fun render(context: GuiGraphicsExtractor) {
			drawMap(context, example = false, scale)
			CrystalStatus.renderUnderMap(context, MAP_SIZE, MAP_SIZE, alignRight = x > 0.5)
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			if (mapShown) return render(context)
			drawMap(context, example = true, scale)
			CrystalStatus.renderExampleUnderMap(context, MAP_SIZE, MAP_SIZE, alignRight = x > 0.5)
		}
	}
}
