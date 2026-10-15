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
 * A map of the Crystal Hollows with the places found in this lobby, and those
 * places labelled in the world.
 *
 * Ported from Skyblocker (LGPL-3.0, Copyright (c) the Skyblocker
 * contributors): its Crystals HUD — the map picture, where on it a block
 * lands, and its player marker — and its CrystalsLocationsManager, which finds
 * a place from the sidebar as you walk in, pins it down from a line only said
 * there, and picks up coordinates other players post in chat.
 *
 * Skyblocker also shares places between its users through its own server. That
 * server is Skyblocker's and only lets Skyblocker in, so Cryptic does not
 * connect to it; when Skyblocker is installed alongside, the places it knows —
 * the shared ones included — are read from it in game instead.
 */
object HollowsMap {
	/** The places the Hollows move between lobbies, as Skyblocker lists them, in its colours. */
	enum class Place(
		val title: String,
		private val ownColor: Int,
		val linkedMessage: String? = null,
		val small: Boolean = false,
		/** A crystal's place takes the colour Nucleus QoL gives that crystal. */
		private val crystal: (() -> ColorModuleSetting)? = null,
	) {
		JUNGLE_TEMPLE("Jungle Temple", 0x8932B8, "[NPC] Kalhuiki Door Guardian:", crystal = { NucleusQol.amethystColor }),
		MINES_OF_DIVAN("Mines of Divan", 0x00FF00, "Jade Crystal", crystal = { NucleusQol.jadeColor }),
		GOBLIN_QUEENS_DEN("Goblin Queen's Den", 0xF9801D, "Amber Crystal", crystal = { NucleusQol.amberColor }),
		LOST_PRECURSOR_CITY("Lost Precursor City", 0x00FFFF, "Sapphire Crystal", crystal = { NucleusQol.sapphireColor }),
		KHAZAD_DUM("Khazad-dûm", 0xFFFF00, "Topaz Crystal", crystal = { NucleusQol.topazColor }),
		FAIRY_GROTTO("Fairy Grotto", 0xFFAFAF, small = true),
		DRAGONS_LAIR("Dragon's Lair", 0xFFAA00, "[NPC] Golden Dragon:"),
		CORLEONE("Corleone", 0xFFFFFF, small = true),
		KING_YOLKAR("King Yolkar", 0xFF0000, "[NPC] King Yolkar:", small = true),
		ODAWA("Odawa", 0xFF00FF, "[NPC] Odawa:", small = true),
		KEY_GUARDIAN("Key Guardian", 0xC0C0C0, small = true),
		XALX("Xalx", 0x00FF00, "[NPC] Xalx:", small = true),
		;

		/** Its colour, without alpha: the crystal's as set in Nucleus QoL, or Skyblocker's for the rest. */
		val color: Int get() = crystal?.invoke()?.rgb ?: ownColor

		companion object {
			fun named(name: String): Place? = entries.firstOrNull { it.title.equals(name, ignoreCase = true) }
		}
	}

	/** Where a place is, and whether something said there has confirmed it. */
	class Found(val pos: BlockPos, val verified: Boolean, val fromSkyblocker: Boolean)

	private val mapSection = SectionModuleSetting("map_section", "Crystal Hollows map")

	@JvmField
	val showMap = ToggleModuleSetting(
		id = "hollows_map",
		label = "Crystal Hollows map",
		defaultValue = true,
		description = "A map of the Hollows on your HUD with the places found in this lobby and where you are. Move and size it in the HUD editor.",
	)

	@JvmField
	val labels = ToggleModuleSetting(
		id = "hollows_labels",
		label = "Label places in the world",
		defaultValue = true,
		description = "Each place found, named in the world with how far away it is.",
	)

	@JvmField
	val chatCoords = ToggleModuleSetting(
		id = "hollows_chat_coords",
		label = "Places from chat",
		defaultValue = true,
		description = "Coordinates other players post with a place's name, like \"divan 600 90 550\", are added.",
	)

	@JvmField
	val fromSkyblocker = ToggleModuleSetting(
		id = "hollows_from_skyblocker",
		label = "Places from Skyblocker",
		defaultValue = true,
		description = "With Skyblocker installed, the places it knows are shown too, including those its users share with each other.",
	)

	/** The map's section of Nucleus QoL, in order. */
	val settings by lazy {
		listOf(mapSection, showMap, labels, chatCoords, fromSkyblocker, CrystalStatus.setting)
	}

	private val places = LinkedHashMap<Place, Found>()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(MapElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> places.clear() }
	}

	val mapShown: Boolean get() = NucleusQol.module.enabled && showMap.value && SkyblockLocation.inCrystalHollows

	private var ticksUntilUpdate = 0

	/** Every two seconds, from [NucleusQol.tick]. */
	fun tick(client: Minecraft) {
		if (ticksUntilUpdate-- > 0) return
		ticksUntilUpdate = 40
		val player = client.player ?: return
		// Walking into a place: noted where you stand the first time, as Skyblocker does.
		SkyblockLocation.zone()?.let(Place::named)?.let { place ->
			if (place !in places) places[place] = Found(player.blockPosition(), verified = false, fromSkyblocker = false)
		}
		if (fromSkyblocker.value) importSkyblocker()
	}

	private val FORMATTING = Regex("§.")

	/** "600 90 550", "x600, y90, z550": Skyblocker's pattern for coordinates in chat. */
	private val COORDS = Regex("""\Dx?(\d{3})(?=[, ]),? ?y?(\d{2,3})(?=[, ]),? ?z?(\d{3})\D?(?!\d)""")

	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || !NucleusQol.module.enabled || !SkyblockLocation.inCrystalHollows) return
		val player = Minecraft.getInstance().player ?: return
		val text = content.string.replace(FORMATTING, "")
		val trimmed = text.trim()

		// A line only ever said at a place pins it to where you are.
		for (place in Place.entries) {
			val linked = place.linkedMessage ?: continue
			val matches = if (linked.endsWith(":")) trimmed.startsWith(linked) else trimmed == linked
			if (!matches || places[place]?.verified == true) continue
			places[place] = Found(player.blockPosition(), verified = true, fromSkyblocker = false)
		}

		// Somebody else's coordinates, with a word of a place's name.
		if (!chatCoords.value || !text.contains(":")) return
		val message = text.substringAfter(":")
		val match = COORDS.find(" $message ") ?: return
		val pos = BlockPos(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
		if (!inHollows(pos)) return
		val lower = message.lowercase()
		val place = Place.entries.firstOrNull { place ->
			place.title.lowercase().split(' ', '\'', '-').filter { it.length >= 4 }.any { it in lower }
		} ?: return
		if (place !in places) places[place] = Found(pos, verified = false, fromSkyblocker = false)
	}

	private fun inHollows(pos: BlockPos) = pos.x in 202..823 && pos.z in 202..823 && pos.y in 31..188

	// --- Skyblocker, when it is installed ----------------------------------

	private val skyblockerInstalled by lazy { FabricLoader.getInstance().isModLoaded("skyblocker") }

	private val skyblockerWaypoints: java.lang.reflect.Field? by lazy {
		runCatching {
			Class.forName("de.hysky.skyblocker.skyblock.dwarven.CrystalsLocationsManager")
				.getDeclaredField("activeWaypoints").apply { isAccessible = true }
		}.onFailure { Cryptic.LOGGER.info("Skyblocker's Crystal Hollows waypoints could not be read: {}", it.toString()) }.getOrNull()
	}

	/**
	 * The places Skyblocker has, its shared ones among them, read out of it.
	 * Its own places never replace one this has confirmed.
	 */
	private fun importSkyblocker() {
		if (!skyblockerInstalled) return
		val field = skyblockerWaypoints ?: return
		runCatching {
			val map = field.get(null) as? Map<*, *> ?: return
			for ((name, label) in map) {
				val place = Place.named(name as? String ?: continue) ?: continue
				if (places[place]?.verified == true) continue
				val pos = positionOf(label ?: continue) ?: continue
				places[place] = Found(pos, verified = false, fromSkyblocker = true)
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

	// --- In the world -------------------------------------------------------

	private fun render(context: LevelRenderContext) {
		if (!NucleusQol.module.enabled || !labels.value || !SkyblockLocation.inCrystalHollows || places.isEmpty()) return
		val client = Minecraft.getInstance()
		val eye = client.player?.eyePosition ?: return
		val orientation = client.gameRenderer.mainCamera().rotation()
		for ((place, found) in places) {
			val at = Vec3.atCenterOf(found.pos)
			val distance = eye.distanceTo(at)
			if (distance < 6) continue
			// The same size on screen near or far.
			val scale = (distance / 10.0).coerceIn(1.0, 40.0).toFloat()
			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = orientation,
				text = Component.literal(place.title).withColor(place.color)
					.append(Component.literal(" §7${distance.roundToInt()}m")),
				x = at.x,
				y = at.y + 1.5,
				z = at.z,
				scale = scale,
				seeThrough = true,
				centered = true,
			)
		}
	}

	// --- The map ------------------------------------------------------------

	private val MAP_TEXTURE: Identifier = Cryptic.id("textures/gui/hollows_map.png")
	private val PLAYER_ICON: Identifier = Identifier.withDefaultNamespace("textures/map/decorations/player.png")

	/** The picture is one pixel to a block of the Hollows, which run from 202 to 823. */
	private const val TEXTURE_SIZE = 621
	private const val HOLLOWS_MIN = 202.0

	/** The map's size on the HUD at scale one. */
	private const val MAP_SIZE = 96

	private fun mapX(x: Double) = ((x - HOLLOWS_MIN) / TEXTURE_SIZE * MAP_SIZE).toFloat().coerceIn(0f, MAP_SIZE.toFloat())

	private fun drawMap(context: GuiGraphicsExtractor, example: Boolean) {
		context.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE, 0, 0, 0f, 0f, MAP_SIZE, MAP_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE)

		val shown = if (example) EXAMPLE_PLACES else places.mapValues { it.value.pos }
		for ((place, pos) in shown) {
			val half = if (place.small) 1.5f else 3f
			val cx = mapX(pos.x.toDouble())
			val cy = mapX(pos.z.toDouble())
			context.fill((cx - half - 1).roundToInt(), (cy - half - 1).roundToInt(), (cx + half + 1).roundToInt(), (cy + half + 1).roundToInt(), 0xFF000000.toInt())
			context.fill((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt(), 0xFF000000.toInt() or place.color)
		}

		// You, as the vanilla map draws a player: the arrow, turned to where you face.
		val player = Minecraft.getInstance().player ?: return
		val px = if (example) MAP_SIZE / 2f else mapX(player.x)
		val py = if (example) MAP_SIZE / 2f else mapX(player.z)
		val pose = context.pose()
		pose.pushMatrix()
		pose.translate(px, py)
		pose.rotate(Mth.DEG_TO_RAD * (player.yRot + 180f))
		pose.translate(-4f, -4f)
		context.blit(RenderPipelines.GUI_TEXTURED, PLAYER_ICON, 0, 0, 0f, 0f, 8, 8, 8, 8)
		pose.popMatrix()
	}

	private val EXAMPLE_PLACES = mapOf(
		Place.MINES_OF_DIVAN to BlockPos(700, 90, 300),
		Place.JUNGLE_TEMPLE to BlockPos(320, 110, 330),
		Place.GOBLIN_QUEENS_DEN to BlockPos(300, 90, 700),
		Place.KHAZAD_DUM to BlockPos(520, 60, 760),
		Place.FAIRY_GROTTO to BlockPos(420, 80, 420),
	)

	/** For `/cryptic debug crystals`. */
	fun describe(): List<String> = listOf(
		"Places this lobby: " + places.entries.joinToString {
			"${it.key.title} ${it.value.pos.toShortString()}${if (it.value.verified) " (confirmed)" else ""}${if (it.value.fromSkyblocker) " (Skyblocker)" else ""}"
		}.ifEmpty { "none" },
		"Skyblocker installed: $skyblockerInstalled, readable: ${skyblockerWaypoints != null}",
	)

	private class MapElement : HudElement("hollows_map", "Crystal Hollows Map", 0.74, 0.04) {
		override val width: Int = MAP_SIZE
		override val height: Int get() = MAP_SIZE + maxOf(CrystalStatus.attachedHeight(), if (mapShown) 0 else CrystalStatus.exampleHeightUnderMap())

		override fun isVisible(): Boolean = mapShown

		override fun showInEditor(): Boolean = NucleusQol.module.enabled && showMap.value

		override fun render(context: GuiGraphicsExtractor) {
			drawMap(context, example = false)
			CrystalStatus.renderUnderMap(context, MAP_SIZE, MAP_SIZE, alignRight = x > 0.5)
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			if (mapShown) return render(context)
			drawMap(context, example = true)
			CrystalStatus.renderExampleUnderMap(context, MAP_SIZE, MAP_SIZE, alignRight = x > 0.5)
		}
	}
}
