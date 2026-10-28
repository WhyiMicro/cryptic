package imicro.cryptic.feature

import imicro.cryptic.experiment.EquippedPet
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.terminal.ServerTicks
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipBlockStateContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/**
 * Conveniences for the Crystal Hollows, mostly around the Nucleus: a warp key, waypoints from where
 * you stand, the five crystal slots in their colours, crystals where they're picked up, the Metal
 * Detector narrowed to the chest, and the treasure chests mining uncovers. Only in the Crystal
 * Hollows.
 *
 * Location comes from your position and the world's blocks, not the sidebar. The treasure's 39
 * places are from the SkyHanni repository. Inspired by SkyHanni's crystal boxes, Metal Detector
 * solver and Powder Chest Timer, and Skyblocker's chest highlighter.
 */
object NucleusQol {
	private const val SOURCE = "Nucleus Features"

	private const val MODE_OUTLINE = 0
	private const val MODE_FILL = 1

	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	private const val LINES_OLDEST = 0
	private const val LINES_NEAREST = 1
	private const val LINES_NONE = 2

	private const val NUCLEUS = "Crystal Nucleus"
	private const val MINES_OF_DIVAN = "Mines of Divan"

	/** Each waypoint relative to the block you stand in, as (x, y, z). */
	private val OFFSETS = listOf(
		Triple(4, 10, 65),
		Triple(29, -32, 65),
		Triple(29, -32, 48),
	)

	private val waypointsSection = SectionModuleSetting("waypoints_section", "Waypoints")

	@JvmField
	val autoWaypoints = ToggleModuleSetting(
		"auto_waypoints", "Jungle Temple waypoints", true,
		"Sets the temple's waypoints by itself once you're near its door: from where the right-hand Kalhuiki Door Guardian " +
			"stands, the spot the waypoints are measured from. The temple always faces the same way.",
	)

	private val calculate = ButtonModuleSetting(
		"calculate", "Calculate waypoints", action = { calculate() }, visibleIf = { !autoWaypoints.value },
	)

	private val clear = ButtonModuleSetting(
		id = "clear",
		label = "Clear waypoints",
		action = {
			waypoints = emptyList()
			Toasts.show(SOURCE, "Cleared the waypoints", color = Toasts.Colors.MINING)
		},
		visibleIf = { waypoints.isNotEmpty() },
	)

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0x50,
		visibleIf = { mode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the waypoints through the walls.",
	)

	private val crystalsSection = SectionModuleSetting("crystals_section", "Crystals")

	private val placesSection = SectionModuleSetting("places_section", "Places")

	@JvmField
	val crystalSlots = ToggleModuleSetting(
		id = "crystal_slots",
		label = "Highlight crystal slots",
		defaultValue = true,
		description = "Boxes the five places in the Crystal Nucleus where the crystals go, each in its crystal's colour.",
	)

	@JvmField
	val crystalPickups = ToggleModuleSetting(
		id = "crystal_pickups",
		label = "Highlight crystals to pick up",
		defaultValue = true,
		description = "Boxes a crystal where it is picked up, in its colour, once the area's boss or puzzle has been done.",
	)

	private val anyCrystals: () -> Boolean = { crystalSlots.value || crystalPickups.value }

	/**
	 * Semi-legit: it finds places you can't see, though all it saves is the walk. Hidden at the
	 * Legit level by Client Restrictions.
	 */
	@JvmField
	val structureScanner = ToggleModuleSetting(
		id = "structure_scanner",
		label = "Structure scanner",
		defaultValue = false,
		description = "Each part of the Hollows the server sends is checked for the places' blocks and the crystals' barrier boxes, so they show on the map as soon as they're within the server's view, not only once you're there. It stops once everything ticked is found.",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
	)

	@JvmField
	val scanPlaces = imicro.cryptic.gui.MultiSelectModuleSetting(
		id = "structure_scanner_places",
		label = "Scan for",
		options = HollowsStructures.places.map { it.title },
		description = "What the scanner looks for. Ticking one it hasn't found starts it again.",
		visibleIf = { structureScanner.value },
	)

	@JvmField
	val crystalStyle = DropdownModuleSetting(
		id = "crystal_style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = STYLE_FILL,
		visibleIf = anyCrystals,
	)

	// Each gemstone in Hypixel's own colour for it.
	@JvmField val amberColor = crystalColor("amber_color", "Amber", 0xFFAA00, 110)
	@JvmField val amethystColor = crystalColor("amethyst_color", "Amethyst", 0xAA00AA, 110)
	@JvmField val topazColor = crystalColor("topaz_color", "Topaz", 0xFFFF55, 110)
	@JvmField val jadeColor = crystalColor("jade_color", "Jade", 0x55FF55, 110)
	@JvmField val sapphireColor = crystalColor("sapphire_color", "Sapphire", 0x55FFFF, 110)

	private fun crystalColor(id: String, label: String, rgb: Int, alpha: Int) = ColorModuleSetting(
		id = id,
		label = label,
		defaultRgb = rgb,
		supportsAlpha = true,
		defaultAlpha = alpha,
		visibleIf = anyCrystals,
	)

	private val treasureSection = SectionModuleSetting("treasure_section", "Mines of Divan")

	@JvmField
	val metalDetector = ToggleModuleSetting(
		id = "metal_detector",
		label = "Metal Detector solver",
		defaultValue = true,
		description = "In the Mines of Divan, works out where the treasure is from the Metal Detector's distance. Stand still in a couple of places until one spot is left.",
	)

	@JvmField
	val detectorMode = DropdownModuleSetting(
		id = "detector_mode",
		label = "Mode",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
	)

	@JvmField
	val detectorFill = ColorModuleSetting(
		id = "detector_fill",
		label = "Fill",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		visibleIf = { detectorMode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val detectorOutline = ColorModuleSetting(
		id = "detector_outline",
		label = "Outline",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { detectorMode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val detectorPhase = ToggleModuleSetting(
		id = "detector_phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the treasure through the walls.",
	)

	@JvmField
	val detectorTracer = ToggleModuleSetting(
		id = "detector_tracer",
		label = "Tracer",
		defaultValue = true,
		description = "A line from your crosshair to the treasure.",
	)

	@JvmField
	val detectorTracerColor = ColorModuleSetting(
		id = "detector_tracer_color",
		label = "Tracer colour",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		inlineWith = detectorTracer,
	)

	@JvmField
	val detectorLabel = ToggleModuleSetting(
		id = "detector_label",
		label = "Distance label",
		defaultValue = true,
	)

	@JvmField
	val moleReminder = ToggleModuleSetting(
		id = "mole_reminder",
		label = "Mole pet reminder",
		defaultValue = true,
		description = "Reminds you to equip your Mole pet when you walk into the Mines of Divan without it. Needs the pet widget in your tab list.",
	)

	@JvmField
	val allToolsAlert = ToggleModuleSetting(
		id = "all_tools_alert",
		label = "All tools alert",
		defaultValue = true,
		description = "A toast once you have all four Keepers' tools, counting the ones that went to your stash.",
	)

	@JvmField
	val fullInventoryAlert = ToggleModuleSetting(
		id = "full_inventory_alert",
		label = "Full inventory alert",
		defaultValue = true,
		description = "A toast when your inventory is full while you hunt with the Metal Detector, since tools then go to your stash.",
	)

	private val chestsSection = SectionModuleSetting("chests_section", "Treasure chests")

	private val hudSection = SectionModuleSetting("hud_section", "HUD")

	@JvmField
	val showYaw = ToggleModuleSetting("show_yaw", "Yaw", true)

	@JvmField
	val showPitch = ToggleModuleSetting("show_pitch", "Pitch", true)

	private const val ANGLES_TOGETHER = 0

	@JvmField
	val anglesLayout = DropdownModuleSetting(
		id = "angles_layout",
		label = "Layout",
		options = listOf("One element", "Separate elements"),
		defaultIndex = ANGLES_TOGETHER,
		description = "Both in one HUD element, or each in its own, placed apart in the HUD editor.",
	)

	@JvmField
	val anglesPrefix = ToggleModuleSetting("angles_prefix", "Prefix", true, "\"yaw: 54.36\" rather than \"54.36\".")

	@JvmField
	val anglesColor = ColorModuleSetting("angles_color", "Text color", defaultRgb = 0xFFFFFF)

	@JvmField
	val angles = ToggleModuleSetting(
		"angles", "Yaw and pitch", false,
		"Where you're facing, to two decimals, on the HUD in the Crystal Hollows: for lining up with a spot or a guide.",
	).withMore(showYaw, showPitch, anglesLayout, anglesPrefix, anglesColor)

	@JvmField
	val treasureChests = ToggleModuleSetting(
		id = "treasure_chests",
		label = "Treasure chest helper",
		defaultValue = true,
		description = "Highlights the treasure chests mining uncovers with the time they have left, until you lockpick them.",
	)

	@JvmField
	val chestMode = DropdownModuleSetting(
		id = "chest_mode",
		label = "Mode",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
	)

	@JvmField
	val chestTimeColors = ToggleModuleSetting(
		id = "chest_time_colors",
		label = "Colour by time left",
		defaultValue = true,
		description = "Fades each chest from green to red as it runs out of time, in place of the colours below.",
	)

	@JvmField
	val chestFill = ColorModuleSetting(
		id = "chest_fill",
		label = "Fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		visibleIf = { !chestTimeColors.value && chestMode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val chestOutline = ColorModuleSetting(
		id = "chest_outline",
		label = "Outline",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { !chestTimeColors.value && chestMode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val chestTimeFillAlpha = SliderModuleSetting(
		id = "chest_time_fill_alpha",
		label = "Fill opacity",
		defaultValue = 40.0,
		min = 0.0,
		max = 100.0,
		step = 5.0,
		visibleIf = { chestTimeColors.value && chestMode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val chestPhase = ToggleModuleSetting(
		id = "chest_phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the chests through the walls.",
	)

	@JvmField
	val chestTimer = ToggleModuleSetting(
		id = "chest_timer",
		label = "Time left on chests",
		defaultValue = true,
	)

	@JvmField
	val chestLines = DropdownModuleSetting(
		id = "chest_lines",
		label = "Tracer",
		options = listOf("Oldest first", "Nearest first", "None"),
		defaultIndex = LINES_OLDEST,
	)

	@JvmField
	val chestLineCount = SliderModuleSetting(
		id = "chest_line_count",
		label = "Chests in the line",
		defaultValue = 5.0,
		min = 1.0,
		max = 30.0,
		step = 1.0,
		visibleIf = { chestLines.selectedIndex != LINES_NONE },
	)

	@JvmField
	val chestLineRange = SliderModuleSetting(
		id = "chest_line_range",
		label = "Tracer range",
		unit = " blocks",
		defaultValue = 32.0,
		min = 0.0,
		max = 128.0,
		step = 4.0,
		description = "Chests farther than this leave the line, so it doesn't follow you when you're not after treasure. 0 has no limit.",
		visibleIf = { chestLines.selectedIndex != LINES_NONE },
	)

	@JvmField
	val lockHelper = ToggleModuleSetting(
		id = "lock_helper",
		label = "Lock pick helper",
		defaultValue = true,
		description = "While you look at a chest, marks where its next lock is and counts the locks done.",
	)

	@JvmField
	val muteChestFound = ToggleModuleSetting(
		id = "mute_chest_found",
		label = "Mute chest found sound",
		defaultValue = false,
	)

	@JvmField
	val muteChestOpen = ToggleModuleSetting(
		id = "mute_chest_open",
		label = "Mute chest open sound",
		defaultValue = false,
		description = "The chest creaking open and the jingle of notes that follows it.",
	)

	@JvmField
	val muteMetalDetector = ToggleModuleSetting(
		id = "mute_metal_detector",
		label = "Mute Metal Detector",
		defaultValue = false,
		description = "The harp notes the Metal Detector plays while you hold it in the Mines of Divan.",
	)

	/** Unbound by default: it sends a command, so sharing a key could warp you away mid-task. */
	@JvmField
	val warpKey = KeybindModuleSetting(
		id = "warp_key",
		label = "Warp to Nucleus",
		description = "Warps you to the Nucleus while you are in the Crystal Hollows.",
	)

	@JvmField
	val module = Module(
		id = "nucleus_qol",
		name = "Nucleus Features",
		description = "Crystal Hollows tools",
		category = ModuleCategory.GENERAL,
		settings = listOf(
			warpKey,
			waypointsSection, autoWaypoints, calculate, clear, mode, fillColor, outlineColor, lineWidth, phase,
			crystalsSection, crystalSlots, crystalPickups, crystalStyle,
			amberColor, amethystColor, topazColor, jadeColor, sapphireColor,
			placesSection, HollowsMap.labels.withMore(HollowsMap.poiPlaces), structureScanner.withMore(scanPlaces),
		) + HollowsMap.settings + listOf(
			WishingCompass.setting,
			treasureSection,
			metalDetector.withMore(
				detectorMode, detectorFill, detectorOutline, detectorPhase, detectorTracer, detectorTracerColor, detectorLabel,
			),
			moleReminder, allToolsAlert, fullInventoryAlert,
			chestsSection,
			treasureChests.withMore(
				chestMode, chestTimeColors, chestFill, chestOutline, chestTimeFillAlpha, chestPhase,
				chestTimer, chestLines, chestLineCount, chestLineRange,
			),
			lockHelper, muteChestFound, muteChestOpen, muteMetalDetector,
			hudSection, angles,
		),
	)

	private fun angleLine(yaw: Boolean, value: Float): String =
		(if (anglesPrefix.value) (if (yaw) "yaw: " else "pitch: ") else "") + String.format(java.util.Locale.ROOT, "%.2f", value)

	/** Wide enough for the longest line, with or without its prefix. */
	private fun angleWidth(): Int {
		val font = Minecraft.getInstance().font
		return font.width(if (anglesPrefix.value) "pitch: -180.00" else "-180.00")
	}

	/** Prefixed lines line up on the left; bare numbers sit centred in the element. */
	private fun drawAngle(context: net.minecraft.client.gui.GuiGraphicsExtractor, text: String, y: Int) {
		val font = Minecraft.getInstance().font
		val x = if (anglesPrefix.value) 0 else (angleWidth() - font.width(text)) / 2
		context.text(font, text, x, y, anglesColor.argb or (0xFF shl 24))
	}

	private fun currentAngle(yaw: Boolean): Float? {
		val player = Minecraft.getInstance().player ?: return null
		return if (yaw) net.minecraft.util.Mth.wrapDegrees(player.yRot) else player.xRot
	}

	private val anglesShown get() = module.enabled && angles.value

	/** Yaw and pitch together, one per line. */
	private class AnglesElement : imicro.cryptic.hud.HudElement("hollows_angles", "Yaw and Pitch", 0.46, 0.4) {
		private val font get() = Minecraft.getInstance().font

		private fun lines(): List<Boolean> = listOfNotNull(true.takeIf { showYaw.value }, false.takeIf { showPitch.value })

		override val width: Int get() = angleWidth()
		override val height: Int get() = font.lineHeight * maxOf(1, lines().size)

		private fun active() = anglesShown && anglesLayout.selectedIndex == ANGLES_TOGETHER && lines().isNotEmpty()

		override fun isVisible(): Boolean = active() && SkyblockLocation.inCrystalHollows

		override fun showInEditor(): Boolean = active()

		override fun render(context: net.minecraft.client.gui.GuiGraphicsExtractor) = draw(context) { currentAngle(it) }

		override fun renderExample(context: net.minecraft.client.gui.GuiGraphicsExtractor) = draw(context) { if (it) 54.36f else 12.5f }

		private fun draw(context: net.minecraft.client.gui.GuiGraphicsExtractor, value: (Boolean) -> Float?) {
			lines().forEachIndexed { row, yaw ->
				drawAngle(context, angleLine(yaw, value(yaw) ?: return), row * font.lineHeight)
			}
		}
	}

	/** Yaw or pitch on its own, with the Separate elements layout. */
	private class AngleElement(id: String, name: String, x: Double, y: Double, private val yaw: Boolean) :
		imicro.cryptic.hud.HudElement(id, name, x, y) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = angleWidth()
		override val height: Int get() = font.lineHeight

		private fun active() = anglesShown && anglesLayout.selectedIndex != ANGLES_TOGETHER && (if (yaw) showYaw.value else showPitch.value)

		override fun isVisible(): Boolean = active() && SkyblockLocation.inCrystalHollows

		override fun showInEditor(): Boolean = active()

		override fun render(context: net.minecraft.client.gui.GuiGraphicsExtractor) {
			drawAngle(context, angleLine(yaw, currentAngle(yaw) ?: return), 0)
		}

		override fun renderExample(context: net.minecraft.client.gui.GuiGraphicsExtractor) {
			drawAngle(context, angleLine(yaw, if (yaw) 54.36f else 12.5f), 0)
		}
	}

	/** The marked blocks, fixed when they were calculated. */
	private var waypoints: List<BlockPos> = emptyList()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		HollowsMap.initialize()
		CrystalStatus.initialize()
		WishingCompass.initialize()
		imicro.cryptic.hud.Hud.register(AnglesElement())
		imicro.cryptic.hud.Hud.register(AngleElement("hollows_yaw", "Yaw", 0.46, 0.4, yaw = true))
		imicro.cryptic.hud.Hud.register(AngleElement("hollows_pitch", "Pitch", 0.46, 0.43, yaw = false))
		// Each server hop is a new lobby, where waypoints, mines, treasure and chests mean nothing.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetLobby() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetLobby() }
		// Client thread: chunks are applied there.
		ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
			if (!scanning || !searching) return@register
			chunkQueue.addLast(chunk.pos.pack())
			// A box that waited on this chunk can be measured now.
			for (dx in -1..1) for (dz in -1..1) {
				val near = net.minecraft.world.level.ChunkPos.pack(chunk.pos.x + dx, chunk.pos.z + dz)
				if (deferredChunks.remove(near)) {
					scannedChunks.remove(near)
					chunkQueue.addLast(near)
				}
			}
		}
	}

	private fun resetLobby() {
		waypoints = emptyList()
		inNucleus = false
		pickups = emptyList()
		minesBase = null
		lastBaseSearch = 0L
		predicted = emptyList()
		keeperCenter = null
		inMines = false
		crystalSpaces.clear()
		lastAreaScan = 0L
		chunkQueue.clear()
		scannedChunks.clear()
		scannerPrimed = false
		lastSought = emptySet()
		pendingSpaces.clear()
		deferredChunks.clear()
		fromAfar.clear()
		announced.clear()
		toolBucket.clear()
		saidInventoryFull = false
		lastReading = -1.0
		lastReadingPos = null
		chests.clear()
		minedBlocks.clear()
		sightings.clear()
		locks.clear()
		sparks.clear()
	}

	/** Whether the warp key was down last tick, so holding it warps once. */
	private var warpKeyDown = false

	private var ticksUntilScan = 0

	/** Warps on the tick the key goes down, with no menu open. Silent outside the Hollows. */
	fun tick(client: Minecraft) {
		if (!module.enabled) {
			warpKeyDown = false
			return
		}
		val down = client.gui.screen() == null && warpKey.isDown(client.window)
		if (down && !warpKeyDown && SkyblockLocation.inCrystalHollows) client.connection?.sendCommand("warp nucleus")
		warpKeyDown = down

		if (!SkyblockLocation.inCrystalHollows) return
		tickChests(client)
		HollowsMap.tick(client)
		CrystalStatus.tick(client)
		scanChunks(client)

		if (ticksUntilScan-- > 0) return
		ticksUntilScan = 10
		if (autoWaypoints.value && waypoints.isEmpty()) templeDoor(client)?.let { door ->
			waypoints = OFFSETS.map { (x, y, z) -> door.offset(x, y, z) }
			Toasts.show(SOURCE, "Set §f${waypoints.size} §7waypoints from the Jungle Temple's door", color = Toasts.Colors.MINING)
		}
		inNucleus = isInNucleus(client)
		updateInMines(client)
		scanArea(client)
		pickups = if (crystalPickups.value && !inNucleus) findPickups(client) else emptyList()
	}

	private fun calculate() {
		val player = Minecraft.getInstance().player
		when {
			!module.enabled -> Toasts.show(SOURCE, "Enable the module first", true)
			player == null -> Toasts.show(SOURCE, "Not in a world", true)
			!SkyblockLocation.inCrystalHollows -> Toasts.show(SOURCE, "Only works in the Crystal Hollows", true)
			else -> {
				val origin = player.blockPosition()
				waypoints = OFFSETS.map { (x, y, z) -> origin.offset(x, y, z) }
				Toasts.show(SOURCE, "Set §f${waypoints.size} §7waypoints", color = Toasts.Colors.MINING)
			}
		}
	}

	private val FORMATTING = Regex("§.")

	private const val DOOR_GUARDIAN = "Kalhuiki Door Guardian"

	/** The guardians flanking the temple's door, where each stands, from the nameplates over them. */
	private fun doorGuardians(client: Minecraft): List<BlockPos> {
		val level = client.level ?: return emptyList()
		return level.entitiesForRendering()
			.filter { it is net.minecraft.world.entity.decoration.ArmorStand && it.customName?.string?.replace(FORMATTING, "")?.contains(DOOR_GUARDIAN) == true }
			.mapNotNull { stand ->
				// The guardian is the NPC under its nameplate; its feet are the spot.
				level.players().firstOrNull { npc ->
					npc !== client.player && Math.abs(npc.x - stand.x) < 0.6 && Math.abs(npc.z - stand.z) < 0.6 && stand.y - npc.y in 0.0..3.5
				}?.blockPosition()
			}
			.distinct()
	}

	/**
	 * The spot the waypoints are measured from: the right-hand guardian as you face the door. The
	 * temple opens toward +z, so facing in, the right hand is the smaller x. Null until both are seen.
	 */
	private fun templeDoor(client: Minecraft): BlockPos? {
		val guardians = doorGuardians(client)
		if (guardians.size != 2) return null
		return guardians.minBy { it.x }
	}

	/** For `/cryptic debug temple`: the guardians found and which one the waypoints come from. */
	fun describeTemple(): List<String> {
		val client = Minecraft.getInstance()
		val guardians = doorGuardians(client)
		if (guardians.isEmpty()) return listOf("No Kalhuiki Door Guardians in sight.")
		val door = templeDoor(client)
		return guardians.map { "Guardian at ${it.x} ${it.y} ${it.z}${if (it == door) " §a(used)" else ""}" } +
			listOfNotNull(door?.let { "Waypoints: " + OFFSETS.joinToString { (x, y, z) -> "${it.x + x} ${it.y + y} ${it.z + z}" } })
	}

	private enum class Crystal(val title: String, val box: AABB, val color: () -> ColorModuleSetting) {
		AMBER("Amber", slot(474, 524), { amberColor }),
		AMETHYST("Amethyst", slot(474, 492), { amethystColor }),
		TOPAZ("Topaz", slot(508, 473), { topazColor }),
		JADE("Jade", slot(542, 492), { jadeColor }),
		SAPPHIRE("Sapphire", slot(542, 524), { sapphireColor }),
	}

	/**
	 * One slot corner to corner (11 across, 13 high from its north-west corner), grown a hair so
	 * its faces don't z-fight the barriers.
	 */
	private fun slot(x: Int, z: Int) =
		AABB(x.toDouble(), 111.0, z.toDouble(), x + 11.0, 124.0, z + 11.0).inflate(0.002)

	/** The nucleus spans x and z 462 to 564. */
	private const val NUCLEUS_MIN = 462.0
	private const val NUCLEUS_MAX = 564.0

	private var inNucleus = false

	/**
	 * By position: the nucleus is the same square in every lobby. The sidebar is still taken when
	 * it says so.
	 */
	private fun isInNucleus(client: Minecraft): Boolean {
		val player = client.player ?: return false
		if (player.x in NUCLEUS_MIN..NUCLEUS_MAX && player.z in NUCLEUS_MIN..NUCLEUS_MAX) return true
		return SkyblockLocation.zone() == NUCLEUS
	}

	private val crystalName = Regex("""(Amber|Amethyst|Topaz|Jade|Sapphire) Crystal""")

	private fun crystalIn(text: String): Crystal? {
		val name = crystalName.find(text.replace(FORMATTING, ""))?.groupValues?.get(1) ?: return null
		return Crystal.entries.firstOrNull { it.title == name }
	}

	/**
	 * The crystal an item shows. Hypixel draws crystals with its pack's model
	 * (`hypixel_skyblock:item/uncategorized/jade_crystal`) on paper, so the model is surest; the id
	 * and name cover older displays.
	 */
	private fun crystalOf(stack: ItemStack): Crystal? {
		if (stack.isEmpty) return null
		stack.get(DataComponents.ITEM_MODEL)?.path?.let { model ->
			val file = model.substringAfterLast('/')
			if (file.endsWith("_crystal")) {
				Crystal.entries.firstOrNull { it.name.equals(file.removeSuffix("_crystal"), ignoreCase = true) }?.let { return it }
			}
		}
		val id = SkyblockItem.id(stack)
		if (id.endsWith("_CRYSTAL")) Crystal.entries.firstOrNull { it.name == id.removeSuffix("_CRYSTAL") }?.let { return it }
		return crystalIn(stack.hoverName.string)
	}

	/** An armour stand's slots, head first, since crystals are usually worn. */
	private val STAND_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST)

	private class Pickup(val crystal: Crystal, val box: AABB)

	private var pickups: List<Pickup> = emptyList()

	private const val PICKUP_RANGE = 96.0

	/**
	 * Crystals near enough to see, wherever Hypixel can show an item: worn or held by a stand, in
	 * an item display, on the ground, or at a stand named after one. `/cryptic debug crystals`
	 * lists what's around if one is missed.
	 */
	private fun findPickups(client: Minecraft): List<Pickup> {
		val level = client.level ?: return emptyList()
		val player = client.player ?: return emptyList()
		val found = mutableListOf<Pickup>()
		for (entity in level.entitiesForRendering()) {
			if (entity.distanceToSqr(player) > PICKUP_RANGE * PICKUP_RANGE) continue
			when (entity) {
				is ArmorStand -> {
					val worn = STAND_SLOTS.firstNotNullOfOrNull { slot -> crystalOf(entity.getItemBySlot(slot))?.let { slot to it } }
					if (worn != null) {
						val (slot, crystal) = worn
						val box = if (slot == EquipmentSlot.HEAD) {
							val eye = entity.getEyePosition()
							AABB(eye.x - 0.4, eye.y - 0.55, eye.z - 0.4, eye.x + 0.4, eye.y + 0.25, eye.z + 0.4)
						} else {
							entity.boundingBox.inflate(0.2)
						}
						found += Pickup(crystal, box)
						continue
					}
					val named = entity.customName?.let { crystalIn(it.string) } ?: continue
					found += Pickup(named, AABB(entity.x - 0.5, entity.y - 1.0, entity.z - 0.5, entity.x + 0.5, entity.y, entity.z + 0.5))
				}
				is Display.ItemDisplay -> {
					val crystal = crystalOf(entity.itemStack) ?: continue
					// A display has no size; a block around it.
					found += Pickup(crystal, AABB(entity.x - 0.5, entity.y - 0.5, entity.z - 0.5, entity.x + 0.5, entity.y + 0.5, entity.z + 0.5))
				}
				is ItemEntity -> {
					val item = crystalOf(entity.item) ?: continue
					found += Pickup(item, entity.boundingBox.inflate(0.2))
				}
			}
		}
		return found
	}

	private fun describeItem(stack: ItemStack): String {
		if (stack.isEmpty) return "-"
		val model = stack.get(DataComponents.ITEM_MODEL)?.toString() ?: "-"
		return "${stack.hoverName.string.replace(FORMATTING, "")} ${SkyblockItem.id(stack)} model $model"
	}

	fun describeCrystals(): List<String> {
		val client = Minecraft.getInstance()
		val level = client.level ?: return listOf("Not in a world")
		val player = client.player ?: return listOf("Not in a world")
		val lines = mutableListOf(
			"Crystal Hollows: ${SkyblockLocation.inCrystalHollows}. ${placeDescription()}",
			CrystalStatus.describe(),
			"Crystals boxed: ${pickups.size} items, crystal spaces: " +
				(crystalSpaces.keys.joinToString { it.title }.ifEmpty { "none yet" }),
			"Mines of Divan base: ${minesBase?.toShortString() ?: "not found"}, Keepers: ${keeperCenter?.toShortString() ?: "not seen"}, " +
				"treasure guesses: ${predicted.size}, tools: ${toolBucket.size}/${TOOLS.size}, chests: ${chests.size}",
		)
		for (entity in level.entitiesForRendering()) {
			if (entity.distanceToSqr(player) > 32.0 * 32.0) continue
			val pos = "%.1f %.1f %.1f".format(entity.x, entity.y, entity.z)
			when (entity) {
				is ArmorStand -> {
					val head = entity.getItemBySlot(EquipmentSlot.HEAD)
					val texture = SkyblockItem.skullTexture(head)?.let { " tex ${it.take(24)}…" } ?: ""
					val name = entity.customName?.string?.replace(FORMATTING, "") ?: "-"
					val hand = entity.getItemBySlot(EquipmentSlot.MAINHAND)
					lines += "stand $pos \"$name\" head: ${describeItem(head)}$texture" +
						if (hand.isEmpty) "" else ", hand: ${describeItem(hand)}"
				}
				is Display.ItemDisplay -> lines += "display $pos ${describeItem(entity.itemStack)}"
				is ItemEntity -> lines += "item $pos ${describeItem(entity.item)}"
			}
		}
		return lines + HollowsMap.describe() + "Wishing Compass: ${WishingCompass.describe()}"
	}

	/**
	 * Each crystal's part of the Hollows, as the sidebar names it. A waiting crystal stands in a
	 * room of barrier blocks, like Jade on the Mines of Divan pedestal.
	 */
	private val CRYSTAL_ZONES = mapOf(
		MINES_OF_DIVAN to Crystal.JADE,
		"Goblin Queen's Den" to Crystal.AMBER,
		"Jungle Temple" to Crystal.AMETHYST,
		"Lost Precursor City" to Crystal.SAPPHIRE,
		"Khazad-dûm" to Crystal.TOPAZ,
	)

	/**
	 * A crystal's space: 11 across, 13 high, 11 deep, of barriers. While the crystal is there, part
	 * of it is glass, splitting the barriers, so the search goes through both and the space is
	 * drawn at full size.
	 */
	private const val CRYSTAL_WIDTH = 11
	private const val CRYSTAL_HEIGHT = 13

	/** How far the search may go from where it started, a little past a crystal's size. */
	private const val ROOM_REACH = 14

	private const val MAX_ROOM_BLOCKS = CRYSTAL_WIDTH * CRYSTAL_HEIGHT * CRYSTAL_WIDTH + 200

	/** Blocks (glass included) one search may visit before giving up. */
	private const val MAX_ROOM_VISITS = 8_000

	/** Barriers and glass joined face to face, their box, and their count. */
	class BarrierCluster(val box: AABB, val blocks: Int, val crystal: AABB?)

	private fun isRoomBlock(state: BlockState): Boolean =
		state.`is`(Blocks.BARRIER) || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).path.contains("glass")

	/**
	 * Everything joined to [start] through barriers and glass within [ROOM_REACH], and the crystal
	 * space it makes when about that size (null for walls and scraps).
	 */
	private fun barrierCluster(level: net.minecraft.world.level.Level, start: BlockPos, seen: MutableSet<BlockPos> = HashSet()): BarrierCluster {
		val queue = ArrayDeque<BlockPos>()
		queue += start
		seen += start
		// Measured by barriers alone: glass is crossed, but glass beside it (a window, a gemstone)
		// isn't the space.
		var minX = start.x; var minY = start.y; var minZ = start.z
		var maxX = start.x; var maxY = start.y; var maxZ = start.z
		var count = 0
		var visited = 0
		var overflow = false
		while (queue.isNotEmpty()) {
			val pos = queue.removeFirst()
			if (++visited > MAX_ROOM_VISITS) break
			if (level.getBlockState(pos).`is`(Blocks.BARRIER)) {
				if (++count > MAX_ROOM_BLOCKS) {
					overflow = true
					break
				}
				minX = minOf(minX, pos.x); minY = minOf(minY, pos.y); minZ = minOf(minZ, pos.z)
				maxX = maxOf(maxX, pos.x); maxY = maxOf(maxY, pos.y); maxZ = maxOf(maxZ, pos.z)
			}
			for (direction in net.minecraft.core.Direction.entries) {
				val next = pos.relative(direction)
				if (next in seen) continue
				if (Math.abs(next.x - start.x) > ROOM_REACH || Math.abs(next.y - start.y) > ROOM_REACH ||
					Math.abs(next.z - start.z) > ROOM_REACH) continue
				if (!isRoomBlock(level.getBlockState(next))) continue
				seen += next
				queue += next
			}
		}
		val box = AABB(minX.toDouble(), minY.toDouble(), minZ.toDouble(), maxX + 1.0, maxY + 1.0, maxZ + 1.0)
		// The Nucleus's slots are crystal-sized, and scans from just outside its square reach them.
		val inNucleus = box.center.x in NUCLEUS_MIN..NUCLEUS_MAX && box.center.z in NUCLEUS_MIN..NUCLEUS_MAX
		return BarrierCluster(box.inflate(0.002), count, if (overflow || inNucleus) null else crystalSpace(box))
	}

	/**
	 * The crystal's whole space when [found] is close to its size (a couple short for an unreached
	 * corner, or a little over where outside glass touched), centred on what was found.
	 */
	private fun crystalSpace(found: AABB): AABB? {
		fun near(size: Double, wanted: Int) = size >= wanted - 3 && size <= wanted + 2
		if (!near(found.xsize, CRYSTAL_WIDTH) || !near(found.ysize, CRYSTAL_HEIGHT) || !near(found.zsize, CRYSTAL_WIDTH)) return null
		val minX = Math.round(found.center.x - CRYSTAL_WIDTH / 2.0).toDouble()
		val minY = Math.round(found.center.y - CRYSTAL_HEIGHT / 2.0).toDouble()
		val minZ = Math.round(found.center.z - CRYSTAL_WIDTH / 2.0).toDouble()
		return AABB(minX, minY, minZ, minX + CRYSTAL_WIDTH, minY + CRYSTAL_HEIGHT, minZ + CRYSTAL_WIDTH).inflate(0.002)
	}

	private fun barrierClusters(level: net.minecraft.world.level.Level, origin: BlockPos, radius: Int, height: Int): List<BarrierCluster> {
		val seen = HashSet<BlockPos>()
		val found = mutableListOf<BarrierCluster>()
		val probe = BlockPos.MutableBlockPos()
		for (dx in -radius..radius) for (dy in -height..height) for (dz in -radius..radius) {
			probe.set(origin.x + dx, origin.y + dy, origin.z + dz)
			if (probe in seen || !level.getBlockState(probe).`is`(Blocks.BARRIER)) continue
			found += barrierCluster(level, probe.immutable(), seen)
		}
		return found
	}

	/** Each crystal's space once found, kept for the lobby (taking a crystal leaves its space). */
	private val crystalSpaces = HashMap<Crystal, AABB>()

	/** The named crystal's space centre ("Jade") once found this lobby, for the Crystal Hollows map. */
	fun crystalSpaceCenter(title: String): Vec3? = crystalSpaces.entries.firstOrNull { it.key.title == title }?.value?.center

	private var lastAreaScan = 0L

	/** How often the barriers are rescanned until the space is found. */
	private const val AREA_SCAN_MS = 5_000L

	private fun currentCrystalArea(): Crystal? = if (inMines) Crystal.JADE else CRYSTAL_ZONES[SkyblockLocation.zone()]

	/** In a crystal's area, finds its space among the nearby barriers every few seconds until found. */
	private fun scanArea(client: Minecraft) {
		if (!crystalPickups.value || inNucleus) return
		val crystal = currentCrystalArea() ?: return
		// One found from afar is measured again once you're there.
		if (crystal in crystalSpaces && crystal !in fromAfar) return
		val now = System.currentTimeMillis()
		if (now - lastAreaScan < AREA_SCAN_MS) return
		lastAreaScan = now
		val player = client.player ?: return
		barrierClusters(client.level ?: return, player.blockPosition(), 48, 32)
			.mapNotNull { it.crystal }
			.minByOrNull { it.center.distanceToSqr(player.position()) }
			?.let {
				fromAfar.remove(crystal)
				assign(crystal, it)
			}
	}

	/** Places already announced this lobby: one notification each, however it was found. */
	private val announced = HashSet<HollowsMap.Place>()

	private fun announceFound(place: HollowsMap.Place, how: String) {
		if (!announced.add(place)) return
		Toasts.show(SOURCE, "Found §f${place.title} §7from afar ($how)", color = Toasts.Colors.MINING)
	}

	/** Crystals whose box was measured from afar, until it's measured again up close. */
	private val fromAfar = HashSet<Crystal>()

	/** Chunks with a box that ran into a chunk not yet loaded, to look at again when it is. */
	private val deferredChunks = HashSet<Long>()

	/** Whether every chunk a box started at [pos] could reach is loaded, so it's measured whole. */
	private fun loadedAround(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
		for (cx in ((pos.x - ROOM_REACH) shr 4)..((pos.x + ROOM_REACH) shr 4)) {
			for (cz in ((pos.z - ROOM_REACH) shr 4)..((pos.z + ROOM_REACH) shr 4)) {
				if (!level.hasChunk(cx, cz)) return false
			}
		}
		return true
	}

	/**
	 * Files [space] as [crystal]'s. The sidebar's word, or the only crystal it can be, so any other
	 * crystal guessed for the same box is taken off it (and off the map).
	 */
	private fun assign(crystal: Crystal, space: AABB) {
		val wrong = crystalSpaces.entries.filter { (other, box) -> other != crystal && box.intersects(space) }.map { it.key }
		for (other in wrong) {
			crystalSpaces.remove(other)
			CRYSTAL_PLACES[other]?.let(HollowsMap::dropCrystalMark)
		}
		pendingSpaces.removeIf { it.intersects(space) }
		crystalSpaces[crystal] = space
		// The Jade space is also the Metal Detector's origin, and a new measurement replaces it.
		if (crystal == Crystal.JADE) minesBase = topFarCorner(space)
	}

	// ---- Structure scanner --------------------------------------------------------------------
	//
	// Every chunk the server sends is looked through for barriers as it arrives, wherever it is.
	// Most sections are passed over by their palette alone; a section with a barrier is searched,
	// and each barrier group measured as the area scan does. The Nucleus's own five slots are the
	// same size, so its square is skipped.

	private val scanning: Boolean
		get() = module.enabled && structureScanner.value && SkyblockLocation.inCrystalHollows

	/** Chunks still to look through, packed, and those done this lobby. */
	private val chunkQueue = ArrayDeque<Long>()
	private val scannedChunks = HashSet<Long>()

	/** Whether the chunks already loaded when the scanner came on have been queued. */
	private var scannerPrimed = false

	/** Chunks looked through per tick: most are passed over by their palettes, so this is cheap. */
	private const val CHUNKS_PER_TICK = 16

	/** Below this height is the Magma Fields, whatever the quadrant. */
	private const val MAGMA_TOP = 64.0

	/** The quadrants meet here on both axes. */
	private const val HOLLOWS_MIDDLE = 513.0

	/** A place still to find: ticked, and not yet known as well as a scan would know it. */
	private fun sought(place: HollowsMap.Place): Boolean =
		scanPlaces.isSelected(place.title) && !HollowsMap.knownAtLeast(place, HollowsMap.Source.SCANNED)

	/** Whether anything ticked is still missing; when not, the scanner rests. */
	private var searching = true

	/** What was being looked for last tick, to tell when something new is wanted. */
	private var lastSought: Set<HollowsMap.Place> = emptySet()

	private val CRYSTAL_PLACES = mapOf(
		Crystal.AMETHYST to HollowsMap.Place.JUNGLE_TEMPLE,
		Crystal.JADE to HollowsMap.Place.MINES_OF_DIVAN,
		Crystal.AMBER to HollowsMap.Place.GOBLIN_QUEENS_DEN,
		Crystal.SAPPHIRE to HollowsMap.Place.LOST_PRECURSOR_CITY,
		Crystal.TOPAZ to HollowsMap.Place.KHAZAD_DUM,
	)

	private fun scanChunks(client: Minecraft) {
		if (!scanning) {
			scannerPrimed = false
			return
		}
		val level = client.level ?: return
		val player = client.player ?: return
		val wanted = HollowsStructures.places.filterTo(HashSet(), ::sought)
		// Something newly wanted wasn't looked for in the chunks already done: go through them again.
		if (!lastSought.containsAll(wanted)) {
			scannedChunks.clear()
			chunkQueue.clear()
			scannerPrimed = false
		}
		lastSought = wanted
		if (pendingSpaces.isNotEmpty()) resolvePending()
		searching = wanted.isNotEmpty()
		if (!searching) {
			chunkQueue.clear()
			return
		}
		if (!scannerPrimed) {
			scannerPrimed = true
			// What arrived before the scanner was on, nearest first.
			val centre = player.chunkPosition()
			val reach = client.options.renderDistance().get() + 2
			val around = ArrayList<Pair<Int, Int>>()
			for (x in -reach..reach) for (z in -reach..reach) around += x to z
			around.sortBy { (x, z) -> x * x + z * z }
			for ((x, z) in around) chunkQueue.addLast(net.minecraft.world.level.ChunkPos.pack(centre.x + x, centre.z + z))
		}
		repeat(CHUNKS_PER_TICK) {
			val packed = chunkQueue.removeFirstOrNull() ?: return
			if (!scannedChunks.add(packed)) return@repeat
			val chunk = level.chunkSource.getChunk(
				net.minecraft.world.level.ChunkPos.getX(packed),
				net.minecraft.world.level.ChunkPos.getZ(packed),
				net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
				false,
			) as? net.minecraft.world.level.chunk.LevelChunk
			// Not here yet: looked at again when it arrives.
			if (chunk == null) {
				scannedChunks.remove(packed)
				return@repeat
			}
			scanChunk(level, chunk)
		}
	}

	private fun scanChunk(level: net.minecraft.world.level.Level, chunk: net.minecraft.world.level.chunk.LevelChunk) {
		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ
		// The Nucleus holds no places, and its slots are crystal-sized too.
		if (baseX + 15 >= NUCLEUS_MIN && baseX <= NUCLEUS_MAX && baseZ + 15 >= NUCLEUS_MIN && baseZ <= NUCLEUS_MAX) return
		HollowsStructures.scan(level, chunk, wanted = ::sought) { place, pos ->
			HollowsMap.offer(place, pos, HollowsMap.Source.SCANNED)
			announceFound(place, "structure scan")
		}
		if (Crystal.entries.none { it !in crystalSpaces && CRYSTAL_PLACES[it]?.let(::sought) == true }) return
		val seen = HashSet<BlockPos>()
		val probe = BlockPos.MutableBlockPos()
		chunk.sections.forEachIndexed { index, section ->
			if (section.hasOnlyAir() || !section.maybeHas { it.`is`(Blocks.BARRIER) }) return@forEachIndexed
			val baseY = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index))
			for (y in 0..15) for (z in 0..15) for (x in 0..15) {
				if (!section.getBlockState(x, y, z).`is`(Blocks.BARRIER)) continue
				probe.set(baseX + x, baseY + y, baseZ + z)
				val at = Vec3.atCenterOf(probe)
				if (probe in seen || crystalSpaces.values.any { it.contains(at) } || pendingSpaces.any { it.contains(at) }) continue
				// Half a box, cut off by a chunk still on its way, would be measured too small and put
				// in the wrong place.
				if (!loadedAround(level, probe)) {
					deferredChunks += chunk.pos.pack()
					continue
				}
				val space = barrierCluster(level, probe.immutable(), seen).crystal ?: continue
				pendingSpaces += space
			}
		}
		resolvePending()
	}

	/**
	 * Crystal boxes found from afar whose crystal isn't settled yet: near the middle a box could be
	 * either of two quarters' (Divan's Jade sits past the line on some lobbies).
	 */
	private val pendingSpaces = ArrayList<AABB>()

	/** How near a place found by its blocks must be to a box to say whose box it is. */
	private const val EVIDENCE_REACH = 100.0

	/**
	 * Settles what can be settled: a box only one missing crystal can be, or one beside a place the
	 * scanner found by its own blocks. The rest wait, for the sidebar once you're there.
	 */
	private fun resolvePending() {
		val iterator = pendingSpaces.iterator()
		val settled = ArrayList<Pair<Crystal, AABB>>()
		while (iterator.hasNext()) {
			val space = iterator.next()
			val open = crystalsAt(space.center).filter { it !in crystalSpaces }
			val crystal = open.singleOrNull()
				?: open.singleOrNull { crystal ->
					val place = CRYSTAL_PLACES[crystal] ?: return@singleOrNull false
					HollowsMap.markOf(place)?.takeIf { it.source == HollowsMap.Source.SCANNED || it.source == HollowsMap.Source.NPC }
						?.let { Vec3.atCenterOf(it.pos).distanceTo(space.center) <= EVIDENCE_REACH } == true
				}
				?: continue
			iterator.remove()
			settled += crystal to space
		}
		for ((crystal, space) in settled) {
			if (crystal in crystalSpaces) continue
			assign(crystal, space)
			fromAfar += crystal
			CRYSTAL_PLACES[crystal]?.let { announceFound(it, "crystal scan") }
		}
	}

	/**
	 * The crystals whose place a box could be, by where it lies. A quarter's places can sit up to
	 * four chunks past the middle, so near it a box may be either side's.
	 */
	private fun crystalsAt(at: Vec3): List<Crystal> {
		if (at.y < MAGMA_TOP) return listOf(Crystal.TOPAZ)
		val west = at.x <= HOLLOWS_MIDDLE + QUARTER_SLACK
		val east = at.x > HOLLOWS_MIDDLE - QUARTER_SLACK
		val north = at.z <= HOLLOWS_MIDDLE + QUARTER_SLACK
		val south = at.z > HOLLOWS_MIDDLE - QUARTER_SLACK
		return buildList {
			if (west && north) add(Crystal.AMETHYST)
			if (east && north) add(Crystal.JADE)
			if (west && south) add(Crystal.AMBER)
			if (east && south) add(Crystal.SAPPHIRE)
		}
	}

	private const val QUARTER_SLACK = 64.0

	private var debugBarriers: List<AABB> = emptyList()
	private var debugBarriersUntil = 0L

	private fun describeBox(b: AABB): String =
		"${b.xsize.roundToInt()}x${b.ysize.roundToInt()}x${b.zsize.roundToInt()} from ${b.minX.roundToInt()} ${b.minY.roundToInt()} ${b.minZ.roundToInt()} " +
			"to ${b.maxX.roundToInt() - 1} ${b.maxY.roundToInt() - 1} ${b.maxZ.roundToInt() - 1}"

	/**
	 * For `/cryptic debug block`: the barriers and glass [pos] joins, and whether they form a
	 * crystal space.
	 */
	fun describeBarrierAt(pos: BlockPos): List<String> {
		val level = Minecraft.getInstance().level ?: return emptyList()
		val cluster = barrierCluster(level, pos)
		debugBarriers = listOfNotNull(cluster.crystal ?: cluster.box)
		debugBarriersUntil = System.currentTimeMillis() + 60_000
		return listOf(
			"Barriers and glass joined to it: ${if (cluster.crystal == null && cluster.blocks > MAX_ROOM_BLOCKS) "over $MAX_ROOM_BLOCKS" else cluster.blocks} blocks, ${describeBox(cluster.box)}",
			cluster.crystal?.let { "A crystal's space: ${describeBox(it)} (outlined for 60s)" }
				?: "Not a crystal's space (${CRYSTAL_WIDTH}x${CRYSTAL_HEIGHT}x$CRYSTAL_WIDTH)",
		)
	}

	private fun placeDescription(): String {
		val zone = SkyblockLocation.zone()
		val crystal = currentCrystalArea()
		return "Place: ${zone ?: "unknown"}" +
			(crystal?.let { " (the ${it.title} crystal's area)" } ?: " (no crystal here)") +
			", in the nucleus: $inNucleus, in the Mines of Divan: $inMines"
	}

	/** For `/cryptic debug barriers`: every barrier cluster nearby, outlined for a minute and listed. */
	fun describeBarriers(): List<String> {
		val client = Minecraft.getInstance()
		val player = client.player ?: return listOf("Not in a world")
		val clusters = barrierClusters(client.level ?: return listOf("Not in a world"), player.blockPosition(), 48, 32).sortedBy { it.box.center.distanceToSqr(player.position()) }
		debugBarriers = clusters.map { it.crystal ?: it.box }
		debugBarriersUntil = System.currentTimeMillis() + 60_000
		val lines = mutableListOf(
			"Zone: ${SkyblockLocation.zone() ?: "unknown"}, ${clusters.size} barrier clusters within 48 blocks (outlined for 60s, nearest first):",
		)
		clusters.take(15).forEachIndexed { index, cluster ->
			lines += "${index + 1}. ${cluster.blocks} blocks, ${describeBox(cluster.box)}, " +
				"${"%.0f".format(Math.sqrt(cluster.box.center.distanceToSqr(player.position())))}m away" +
				if (cluster.crystal != null) ", a crystal's space" else ""
		}
		lines += "Crystal spaces found this lobby: " +
			(crystalSpaces.entries.joinToString { "${it.key.title} at ${describeBox(it.value)}" }.ifEmpty { "none" })
		return lines
	}

	private val TREASURE = Regex("""TREASURE: (\d+(?:\.\d+)?)m""")

	private val TREASURE_FOUND = Regex("""^You found .*with your Metal Detector!$""")

	/**
	 * Every place the treasure appears, as offsets from the corner of the Jade crystal's barriers
	 * (corner minus offset). From the SkyHanni repository's MetalDetectorChests.
	 */
	private val CHEST_OFFSETS: List<BlockPos> = listOf(
		"-7:38:-2", "-15:38:31", "-17:38:19", "47:37:33", "36:38:45", "48:39:45", "45:39:-13", "-38:38:21",
		"42:38:27", "29:39:-7", "22:38:-15", "-7:39:-26", "-2:38:-6", "43:39:-21", "10:38:-11", "17:38:49",
		"19:38:-17", "-35:39:35", "25:39:5", "-37:36:46", "-24:38:49", "-7:38:48", "-14:39:-24", "-18:39:44",
		"-1:38:-23", "41:37:-37", "19:38:-38", "-7:39:27", "42:38:19", "-33:39:31", "6:39:25", "-2:38:-17",
		"-15:39:5", "-20:39:-12", "-25:38:30", "28:39:-35", "-19:39:-22", "4:38:-15", "36:38:17",
	).map { entry ->
		val (x, y, z) = entry.split(':').map(String::toInt)
		BlockPos(x, y, z)
	}

	/** Looking for the mines' middle again sooner than this would scan a million blocks for nothing. */
	private const val BASE_SEARCH_COOLDOWN_MS = 15_000L

	private const val MINES_REACH = 60.0

	/**
	 * A TREASURE reading keeps you counted as in the mines for this many server ticks, so a lag
	 * spike doesn't run it out. Wall time only when the server sends no ticks.
	 */
	private const val READING_KEEPS_MINES_TICKS = 40L
	private const val READING_KEEPS_MINES_MS = 2_000L

	private fun readingRecent(): Boolean = when {
		lastTreasureReadingAt == 0L -> false
		ServerTicks.sinceLastTick != null -> ServerTicks.total - lastTreasureReadingTick < READING_KEEPS_MINES_TICKS
		else -> System.currentTimeMillis() - lastTreasureReadingAt < READING_KEEPS_MINES_MS
	}

	private val KEEPER = Regex("""^Keeper of (Diamond|Lapis|Emerald|Gold)$""")

	/** From each Keeper to the mines' middle: 33 blocks out, a little to one side. */
	private val KEEPER_OFFSETS = mapOf(
		"Diamond" to BlockPos(33, 0, 3),
		"Lapis" to BlockPos(-33, 0, -3),
		"Emerald" to BlockPos(-3, 0, 33),
		"Gold" to BlockPos(3, 0, -33),
	)

	/** The corner of the Jade pedestal's barriers, the chest offsets' origin. */
	private var minesBase: BlockPos? = null

	/** The mines' middle as the Keepers place it, known before the base is searched for. */
	private var keeperCenter: BlockPos? = null

	private var lastBaseSearch = 0L

	private var inMines = false

	/** Where the treasure could be. One left is the answer, kept for the lobby until dug up. */
	private var predicted: List<BlockPos> = emptyList()

	/** The last reading and where you stood; a reading counts once it repeats while standing still. */
	private var lastReading = -1.0
	private var lastReadingPos: Vec3? = null

	/** The detector rounds to a tenth; a little more for where on the block you stand. */
	private const val READING_TOLERANCE = 0.15

	private fun fits(chest: BlockPos, position: Vec3, distance: Double): Boolean {
		val measured = position.distanceTo(Vec3(chest.x.toDouble(), chest.y + 1.0, chest.z.toDouble()))
		return Math.abs(measured - distance) < READING_TOLERANCE
	}

	/**
	 * The action bar from either packet. A reading counts only after repeating while you stand
	 * still, since the bar lags while walking. Each still reading narrows the guesses; an answer a
	 * later reading contradicts is recalculated.
	 */
	@JvmStatic
	fun onActionBar(text: Component) {
		if (!module.enabled || !SkyblockLocation.inCrystalHollows) return
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		val player = client.player ?: return
		val distance = TREASURE.find(text.string.replace(FORMATTING, ""))?.groupValues?.get(1)?.toDoubleOrNull() ?: return
		// The meter only shows in the mines, whatever the position checks think.
		lastTreasureReadingAt = System.currentTimeMillis()
		lastTreasureReadingTick = ServerTicks.total
		if (!inMines) {
			inMines = true
			onEnterMines()
		}
		checkTools(player)
		if (!metalDetector.value) return

		val position = player.position()
		val still = distance == lastReading && lastReadingPos?.let { it.distanceToSqr(position) < 0.0025 } == true
		lastReading = distance
		lastReadingPos = position
		if (!still) return
		lastStillReading = distance

		// A base far from you can't be this mines' middle: a crystal measured wrong, or another lobby's.
		if (minesBase?.let { horizontalDistance(it, position) > BASE_MAX_DISTANCE } != false) {
			minesBase = null
			findMinesBase(force = true)
		}
		val base = minesBase ?: return

		// Narrow what's left; start over if nothing is, or the answer no longer fits.
		val before = predicted.size
		val narrowed = predicted.filter { fits(it, position, distance) }
		predicted = narrowed.ifEmpty { candidates(base, position, distance) }
		if (predicted.isEmpty()) {
			// No spot fits from this base, so the base is wrong: look for the pedestal again.
			findMinesBase(force = true)
			minesBase?.takeIf { it != base }?.let { predicted = candidates(it, position, distance) }
		}
		if (predicted.isEmpty()) {
			if (++readingsWithoutFit == NO_FIT_HINT) {
				Toasts.show(SOURCE, "No treasure spot fits these readings. Stand still somewhere else.", true)
			}
			return
		}
		readingsWithoutFit = 0
		if (predicted.size > 1 && predicted.size != before) {
			Toasts.show(SOURCE, "§f${predicted.size} §7possible locations, move a little to find the right one", color = Toasts.Colors.MINING)
		}
	}

	private fun candidates(base: BlockPos, position: Vec3, distance: Double): List<BlockPos> =
		CHEST_OFFSETS.map { base.subtract(it) }.filter { fits(it, position, distance) }

	private fun horizontalDistance(pos: BlockPos, position: Vec3): Double =
		Math.hypot(pos.x + 0.5 - position.x, pos.z + 0.5 - position.z)

	/** The treasure spots lie within about 50 blocks of the base, so you're always closer than this. */
	private const val BASE_MAX_DISTANCE = 90.0

	private var lastTreasureReadingAt = 0L
	private var lastTreasureReadingTick = 0L
	private var lastStillReading = -1.0
	private var readingsWithoutFit = 0
	private const val NO_FIT_HINT = 3

	fun describeDetector(): List<String> {
		val player = Minecraft.getInstance().player ?: return listOf("Not in a world")
		val position = player.position()
		val base = minesBase
		val fitting = if (base != null && lastStillReading >= 0) candidates(base, position, lastStillReading).size else -1
		return listOf(
			"In the Crystal Hollows: ${SkyblockLocation.inCrystalHollows}, in the Mines of Divan: $inMines (zone '${SkyblockLocation.zone()}', " +
				"last TREASURE reading ${if (lastTreasureReadingAt == 0L) "never" else "${(System.currentTimeMillis() - lastTreasureReadingAt) / 1000}s ago"})",
			"Solver on: ${metalDetector.value}, Keepers' middle: ${keeperCenter?.toShortString() ?: "not seen"}, " +
				"Jade space: ${crystalSpaces[Crystal.JADE]?.let(::describeBox) ?: "not found"}${if (Crystal.JADE in fromAfar) " (from afar)" else ""}",
			"Base: ${base?.toShortString() ?: "not found"}${base?.let { ", %.1f blocks away".format(horizontalDistance(it, position)) } ?: ""}",
			"Last reading $lastReading m, last still reading $lastStillReading m, spots fitting it from here: $fitting, guesses kept: ${predicted.size}",
			"You stand at %.2f %.2f %.2f".format(position.x, position.y, position.z),
		)
	}

	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay) {
			onActionBar(content)
			return
		}
		if (!module.enabled || !SkyblockLocation.inCrystalHollows) return
		HollowsMap.onSystemChat(content, false)
		WishingCompass.onSystemChat(content, false)
		val text = content.string.replace(FORMATTING, "").trim()
		TREASURE_FOUND.matchEntire(text)?.let { found ->
			// The treasure dug up, so the next reading is new. Until then it stays marked.
			predicted = emptyList()
			// A tool goes in the bucket whether it went to the inventory or the stash.
			TOOLS.entries.firstOrNull { found.value.contains(it.value) }?.let { addTool(it.key) }
		}
		if (tracksChests && !inMines) {
			if (text == "You uncovered a treasure chest!") chestConfirmed()
			// Hypixel leaves a picked chest standing, so it's released here.
			if (text == "CHEST LOCKPICKED" || text == "This chest has already been looted.") chestLooted()
		}
	}

	private val TOOLS = mapOf(
		"DWARVEN_LAPIS_SWORD" to "Scavenged Lapis Sword",
		"DWARVEN_EMERALD_HAMMER" to "Scavenged Emerald Hammer",
		"DWARVEN_GOLD_HAMMER" to "Scavenged Golden Hammer",
		"DWARVEN_DIAMOND_AXE" to "Scavenged Diamond Axe",
	)

	private const val METAL_DETECTOR = "DWARVEN_METAL_DETECTOR"

	/**
	 * The tools you hold this mines visit, one of each at most: what you carried in, plus every
	 * tool chat says you found. Emptied when you leave or the set is complete.
	 */
	private val toolBucket = HashSet<String>()

	private var saidInventoryFull = false

	private fun fillBucketFromInventory(player: net.minecraft.world.entity.player.Player) {
		toolBucket.clear()
		val counts = HashMap<String, Int>()
		for (slot in 0 until 36) {
			val stack = player.inventory.getItem(slot)
			val id = SkyblockItem.id(stack)
			if (id in TOOLS) counts.merge(id, stack.count, Int::plus)
		}
		// Whole sets are set aside; the leftover is the set in progress.
		val sets = TOOLS.keys.minOf { counts[it] ?: 0 }
		for (id in TOOLS.keys) if ((counts[id] ?: 0) > sets) toolBucket += id
	}

	private fun addTool(id: String) {
		if (!inMines || !toolBucket.add(id)) return
		if (toolBucket.size < TOOLS.size) return
		if (allToolsAlert.value) Toasts.show(SOURCE, "§fAll tools found", color = Toasts.Colors.MINING)
		// A full set is done; the bucket restarts, for gathering sets ahead of nucleus runs.
		toolBucket.clear()
	}

	/**
	 * Warns while the inventory is full (the next find goes to the stash). Only with the Metal
	 * Detector in hand.
	 */
	private fun checkTools(player: net.minecraft.world.entity.player.Player) {
		if (!fullInventoryAlert.value || SkyblockItem.id(player.mainHandItem) != METAL_DETECTOR) return
		val free = (0 until 36).any { player.inventory.getItem(it).isEmpty }
		if (!free && !saidInventoryFull) {
			saidInventoryFull = true
			Toasts.show(SOURCE, "§cInventory full§7, tools you find will go to your stash", color = Toasts.Colors.MINING)
		} else if (free) {
			saidInventoryFull = false
		}
	}

	/**
	 * Whether you're in the Mines of Divan: near the middle the Keepers or Jade pedestal give, or a
	 * sidebar saying so. The treasure is only drawn here, and kept while away.
	 */
	private fun updateInMines(client: Minecraft) {
		val player = client.player ?: return
		findKeeperCenter(client)
		fun near(center: BlockPos?) = center != null &&
			Math.abs(player.x - center.x) <= MINES_REACH && Math.abs(player.z - center.z) <= MINES_REACH
		val now = near(minesBase) || near(keeperCenter) || SkyblockLocation.zone() == MINES_OF_DIVAN ||
			readingRecent()
		if (now && !inMines) {
			inMines = true
			fillBucketFromInventory(player)
			onEnterMines()
		}
		// The bucket is per visit: tools given to Keepers are gone next time.
		if (!now && inMines) toolBucket.clear()
		inMines = now
	}

	private fun findKeeperCenter(client: Minecraft) {
		if (keeperCenter != null) return
		val level = client.level ?: return
		val player = client.player ?: return
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand || !entity.hasCustomName()) continue
			if (entity.distanceToSqr(player) > 150.0 * 150.0) continue
			val keeper = KEEPER.matchEntire(entity.name.string.replace(FORMATTING, "").trim()) ?: continue
			val offset = KEEPER_OFFSETS[keeper.groupValues[1]] ?: continue
			keeperCenter = entity.blockPosition().offset(offset)
			return
		}
	}

	/**
	 * Entering the mines: the Mole reminder (from the tab list's Pet widget, which not everyone
	 * shows), and a search for the Jade pedestal.
	 */
	private fun onEnterMines() {
		if (minesBase == null && crystalPickups.value) findMinesBase()
		if (!moleReminder.value) return
		val pet = EquippedPet.name()
		when {
			pet != null && pet.contains("Mole", ignoreCase = true) -> Unit
			pet != null -> {
				Toasts.show(SOURCE, "Equip your §6Mole §7pet, §f$pet §7is out", color = Toasts.Colors.MINING)
				Minecraft.getInstance().gui.hud.setTitle(Component.literal("§6Mole pet!"))
			}
			EquippedPet.widgetShown() -> {
				Toasts.show(SOURCE, "No pet out, equip your §6Mole", color = Toasts.Colors.MINING)
				Minecraft.getInstance().gui.hud.setTitle(Component.literal("§6Mole pet!"))
			}
			else -> Toasts.show(SOURCE, "Can't read your pet from the tab list. Add the §fPet §7widget with §f/widget", true)
		}
	}

	/**
	 * The treasure offsets' origin: the top far corner of the Jade crystal's barrier space on its
	 * pedestal. Read from the space once found (by the area scan or here); until then the space is
	 * searched near the Keepers' middle, or near you.
	 */
	private fun findMinesBase(force: Boolean = false) {
		if (!force) crystalSpaces[Crystal.JADE]?.let {
			minesBase = topFarCorner(it)
			return
		}
		val now = System.currentTimeMillis()
		if (now - lastBaseSearch < (if (force) FORCED_SEARCH_COOLDOWN_MS else BASE_SEARCH_COOLDOWN_MS)) return
		lastBaseSearch = now
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val player = client.player ?: return
		// Around the Keepers' middle first, then around you, since the Keepers can be misread.
		val searches = listOfNotNull(keeperCenter?.let { it to PEDESTAL_REACH }, player.blockPosition() to MINES_REACH.toInt())
		for ((center, reach) in searches) {
			val space = barrierClusters(level, center, reach, PEDESTAL_HEIGHT)
				.mapNotNull { it.crystal }
				.minByOrNull { Math.abs(it.center.x - center.x) + Math.abs(it.center.z - center.z) } ?: continue
			fromAfar.remove(Crystal.JADE)
			assign(Crystal.JADE, space)
			return
		}
	}

	private const val FORCED_SEARCH_COOLDOWN_MS = 4_000L

	/** The largest x, y and z block of [space]. */
	private fun topFarCorner(space: AABB): BlockPos =
		BlockPos(Math.floor(space.maxX).toInt() - 1, Math.floor(space.maxY).toInt() - 1, Math.floor(space.maxZ).toInt() - 1)

	/** How far from the mines' middle, sideways and vertically, the Jade space is searched. */
	private const val PEDESTAL_REACH = 12
	private const val PEDESTAL_HEIGHT = 40

	// --- Treasure chests --------------------------------------------------
	//
	// Mining in the Hollows sometimes uncovers a treasure chest, which lasts a minute and opens by
	// picking its locks. A chest near you might be someone else's, so each waits briefly for
	// evidence: kept if Hypixel confirmed a find around then, or if it stands where you just mined
	// with nobody near.

	private const val CHEST_LIFETIME_MS = 60_000L

	/** How far either side of a chest appearing Hypixel's confirmation can come. */
	private const val CONFIRM_WINDOW_MS = 1_000L

	/** How long a broken block counts as where a chest may appear. */
	private const val MINED_MEMORY_MS = 5_000L

	/** Chests further than this aren't yours to time. */
	private const val CHEST_REACH = 12.0

	/** With another player this close, a chest might be theirs. */
	private const val CROWD_DISTANCE = 20.0

	/** Each kept chest and its despawn time. */
	private val chests = LinkedHashMap<BlockPos, Long>()

	private val minedBlocks = HashMap<BlockPos, Long>()

	/** An appeared chest, waiting to be judged yours or not. */
	private class Sighting(val pos: BlockPos, val at: Long, val whereMined: Boolean, val crowded: Boolean)

	private val sightings = ArrayList<Sighting>()

	/** When Hypixel last said a chest was found (its level-up sound or chat line). */
	@Volatile
	private var lastConfirmation = 0L

	private val tracksChests: Boolean get() = treasureChests.value || lockHelper.value

	@JvmStatic
	fun onBlockChanged(pos: BlockPos, oldState: BlockState, newState: BlockState) {
		// The Metal Detector's treasure is also a chest; in the mines it's the only kind.
		if (!module.enabled || !tracksChests || !SkyblockLocation.inCrystalHollows || inMines) return
		val player = Minecraft.getInstance().player ?: return
		if (pos.distToCenterSqr(player.position()) > CHEST_REACH * CHEST_REACH) return
		val now = System.currentTimeMillis()
		val wasChest = oldState.`is`(Blocks.CHEST)
		val isChest = newState.`is`(Blocks.CHEST)
		when {
			isChest && !wasChest -> {
				val key = pos.immutable()
				// Where a block just broke, or a block that turned straight into the chest.
				val whereMined = minedBlocks.remove(key)?.let { now - it <= MINED_MEMORY_MS } == true || !oldState.isAir
				sightings += Sighting(key, now, whereMined, crowded(player))
				judgeSightings(now)
			}
			wasChest && !isChest -> forgetChest(pos)
			!oldState.isAir && newState.isAir -> minedBlocks[pos.immutable()] = now
		}
	}

	/** Keeps sightings that are yours and drops those that waited too long. */
	private fun judgeSightings(now: Long) {
		val iterator = sightings.iterator()
		while (iterator.hasNext()) {
			val sighting = iterator.next()
			val confirmed = Math.abs(lastConfirmation - sighting.at) <= CONFIRM_WINDOW_MS
			val yours = confirmed || (sighting.whereMined && !sighting.crowded)
			if (yours) {
				chests[sighting.pos] = sighting.at + CHEST_LIFETIME_MS
				locks.remove(sighting.pos)
			}
			if (yours || now - sighting.at > CONFIRM_WINDOW_MS) iterator.remove()
		}
	}

	/** The found sound or "You uncovered a treasure chest!", on either side of its chest appearing. */
	private fun chestConfirmed() {
		val now = System.currentTimeMillis()
		lastConfirmation = now
		judgeSightings(now)
	}

	/** Whether a real player (not an NPC) is close enough that a chest could be theirs. */
	private fun crowded(self: net.minecraft.world.entity.player.Player): Boolean =
		Minecraft.getInstance().level?.players()?.any {
			it !== self && it.uuid.version() == 4 && it.distanceTo(self) < CROWD_DISTANCE
		} == true

	/**
	 * "CHEST LOCKPICKED": the aimed-at chest, or the nearest in reach, is done; Hypixel leaves it
	 * standing a while.
	 */
	private fun chestLooted() {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val looted = (client.hitResult as? BlockHitResult)?.blockPos?.takeIf { it in chests }
			?: chests.keys.filter { it.distToCenterSqr(player.position()) <= 36.0 }
				.minByOrNull { it.distToCenterSqr(player.position()) }
			?: return
		forgetChest(looted)
	}

	private fun forgetChest(pos: BlockPos) {
		chests.remove(pos)
		locks.remove(pos)
	}

	private fun tickChests(client: Minecraft) {
		if (chests.isEmpty() && minedBlocks.isEmpty() && sightings.isEmpty()) return
		val now = System.currentTimeMillis()
		val level = client.level ?: return
		minedBlocks.values.removeIf { now - it > MINED_MEMORY_MS }
		judgeSightings(now)
		// Gone when time's up or once opened.
		val gone = chests.entries.filter { (pos, despawn) ->
			now > despawn + 1_000 || ((level.getBlockEntity(pos) as? ChestBlockEntity)?.getOpenNess(1f) ?: 0f) > 0f
		}.map { it.key }
		gone.forEach(::forgetChest)
	}

	// --- Lock picking -------------------------------------------------------
	//
	// A treasure chest opens once its locks are clicked in turn. Hypixel shows crit sparks at the
	// next lock, plays an orb pickup per lock, a villager "no" for a wrong click, and the chest
	// sound when open. The newest spark burst on the aimed chest is the next lock. Picks are
	// counted per chest; the lock count is learned from the last chest opened.

	private val locks = HashMap<BlockPos, Int>()

	/** How many locks the last opened chest took, which the next will too. */
	private var locksPerChest = 0

	private val sparks = ArrayDeque<Pair<Vec3, Long>>()

	/** Older sparks belong to a lock already picked. */
	private const val SPARK_MEMORY_MS = 300L

	/** Sparks this close in time to the newest are one burst. */
	private const val BURST_MS = 120L

	/** How far from a chest's middle its sparks can be. */
	private const val SPARK_REACH = 0.9

	private const val LOCK_SIZE = 0.1

	/** The crit sparks marking a chest's next lock. */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (!Minecraft.getInstance().isSameThread) return
		if (!module.enabled || !lockHelper.value || chests.isEmpty()) return
		if (packet.particle.type != ParticleTypes.CRIT) return
		val now = System.currentTimeMillis()
		sparks.addLast(Vec3(packet.x, packet.y, packet.z) to now)
		while (sparks.isNotEmpty() && now - sparks.first().second > SPARK_MEMORY_MS) sparks.removeFirst()
	}

	private fun SoundEvent.isSound(other: SoundEvent) = location() == other.location()

	/**
	 * A chest found, lock picked, wrong click or chest opening, by sound, from either packet (some
	 * sounds are positional, some on an entity). Client thread only; muting happens later in
	 * [mutes].
	 */
	@JvmStatic
	fun onSound(sound: SoundEvent, pitch: Float, volume: Float) {
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		if (!module.enabled || !tracksChests || !SkyblockLocation.inCrystalHollows) return
		try {
			if (sound.isSound(SoundEvents.PLAYER_LEVELUP) && pitch == 1f && volume == 1f) {
				chestConfirmed()
				return
			}
			onLockSound(client, sound, pitch)
		} catch (_: Exception) {
			// A throw in a packet handler disconnects you.
		}
	}

	/**
	 * Whether a sound about to play is a treasure chest sound being muted, asked by the sound
	 * manager so every route is caught. In the Hollows, the level-up and chest sounds are almost
	 * always the treasure's.
	 */
	@JvmStatic
	fun mutes(instance: SoundInstance): Boolean {
		if (!module.enabled || !SkyblockLocation.inCrystalHollows) return false
		val id = instance.identifier
		val now = System.currentTimeMillis()
		if (muteChestOpen.value && id == SoundEvents.CHEST_OPEN.location()) {
			chestOpenedAt = now
			return true
		}
		return (muteChestFound.value && id == SoundEvents.PLAYER_LEVELUP.location()) ||
			// The treasure's opening jingle: experience orb notes in the moment after the chest opens.
			(muteChestOpen.value && id == SoundEvents.EXPERIENCE_ORB_PICKUP.location() && now - chestOpenedAt <= OPEN_JINGLE_MS) ||
			(muteMetalDetector.value && inMines && id == SoundEvents.NOTE_BLOCK_HARP.value().location() && heldDetectorLately(now))
	}

	private var chestOpenedAt = 0L
	private const val OPEN_JINGLE_MS = 700L

	/** When the Metal Detector was last in hand: its last notes can still arrive after switching away. */
	private var detectorHeldAt = 0L

	private fun heldDetectorLately(now: Long): Boolean {
		val player = Minecraft.getInstance().player ?: return false
		if (SkyblockItem.id(player.mainHandItem) == METAL_DETECTOR) detectorHeldAt = now
		return now - detectorHeldAt <= DETECTOR_GRACE_MS
	}

	private const val DETECTOR_GRACE_MS = 1000L

	/** The treasure chest under the crosshair, looking through non-chests. */
	private fun aimedChest(client: Minecraft): BlockPos? {
		val player = client.player ?: return null
		val eye = player.getEyePosition(0f)
		val end = eye.add(player.getViewVector(0f).scale(player.blockInteractionRange()))
		val hit = player.level().isBlockInLine(ClipBlockStateContext(eye, end) { it.`is`(Blocks.CHEST) })
		return hit.blockPos.takeIf { hit.type != HitResult.Type.MISS && it in chests }
	}

	private fun onLockSound(client: Minecraft, sound: SoundEvent, pitch: Float) {
		if (!lockHelper.value || chests.isEmpty()) return
		val chest = aimedChest(client)
		when {
			sound.isSound(SoundEvents.EXPERIENCE_ORB_PICKUP) && pitch == 1f -> chest?.let { locks[it] = (locks[it] ?: 0) + 1 }
			sound.isSound(SoundEvents.VILLAGER_NO) -> chest?.let { locks[it] = 0 }
			sound.isSound(SoundEvents.CHEST_OPEN) -> chest?.let { opened ->
				locks[opened]?.takeIf { it > 0 }?.let { locksPerChest = minOf(it, MAX_LOCKS) }
				locks.remove(opened)
			}
			else -> return
		}
		sparks.clear()
	}

	private const val MAX_LOCKS = 5

	/** Green with the full minute left, through yellow, to red as it despawns. */
	private fun timeColor(msLeft: Long): Int {
		val ratio = (msLeft.toDouble() / CHEST_LIFETIME_MS).coerceIn(0.0, 1.0)
		val red = (255 * (1 - ratio)).toInt()
		val green = (255 * ratio).toInt()
		return (0xFF shl 24) or (red shl 16) or (green shl 8)
	}

	/** The tracer colour: the time fade, or the outline colour when that's off. */
	private fun lineColor(msLeft: Long): Int =
		if (chestTimeColors.value) timeColor(msLeft) else chestOutline.argb or (0xFF shl 24)

	private fun secondsLeft(msLeft: Long) ="${((msLeft + 999) / 1000).coerceAtLeast(0)}s"

	/** The chests the line runs through, in order. */
	private fun lineOrder(player: Vec3): List<Map.Entry<BlockPos, Long>> {
		val count = chestLineCount.value.toInt()
		val range = chestLineRange.value
		val near = chests.entries.filter { range <= 0 || Vec3.atCenterOf(it.key).distanceToSqr(player) <= range * range }
		return when (chestLines.selectedIndex) {
			LINES_OLDEST -> near.sortedBy { it.value }.take(count)
			LINES_NEAREST -> near.sortedBy { Vec3.atCenterOf(it.key).distanceToSqr(player) }.take(count)
			else -> emptyList()
		}
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		renderWaypoints(context)
		if (!SkyblockLocation.inCrystalHollows) return
		renderCrystals(context)
		renderMetalDetector(context)
		renderChests(context)
	}

	private fun renderWaypoints(context: LevelRenderContext) {
		if (waypoints.isEmpty()) return
		val fill = mode.selectedIndex != MODE_OUTLINE
		val outline = mode.selectedIndex != MODE_FILL

		for (pos in waypoints) {
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = pos,
				outlineArgb = outlineColor.argb,
				fillArgb = fillColor.argb,
				outline = outline,
				fill = fill,
				phase = phase.value,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = true,
			)
		}
	}

	private fun renderCrystals(context: LevelRenderContext) {
		val style = crystalStyle.selectedIndex
		fun box(box: AABB, crystal: Crystal) {
			val argb = crystal.color().argb
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = box.minX, minY = box.minY, minZ = box.minZ,
				maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
				// An outline at the fill's alpha would vanish, so it's opaque.
				outlineArgb = argb or (0xFF shl 24),
				fillArgb = argb,
				outline = style != STYLE_FILL,
				fill = style != STYLE_OUTLINE,
				phase = false,
				lineWidth = 2f,
			)
		}
		if (crystalSlots.value && inNucleus) {
			for (crystal in Crystal.entries) box(crystal.box, crystal)
		}
		if (crystalPickups.value) {
			for (pickup in pickups) box(pickup.box, pickup.crystal)
			for ((crystal, space) in crystalSpaces) box(space, crystal)
		}
		if (System.currentTimeMillis() < debugBarriersUntil) {
			for (cluster in debugBarriers) {
				WorldRender.drawBox(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					minX = cluster.minX, minY = cluster.minY, minZ = cluster.minZ,
					maxX = cluster.maxX, maxY = cluster.maxY, maxZ = cluster.maxZ,
					outlineArgb = 0xFFFFFFFF.toInt(),
					fillArgb = 0,
					outline = true,
					fill = false,
					phase = true,
					lineWidth = 2f,
				)
			}
		}
	}

	/** The treasure: a block through walls, an edge, a line from the crosshair, and the distance. */
	private fun renderMetalDetector(context: LevelRenderContext) {
		if (!metalDetector.value || !inMines || predicted.isEmpty()) return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val orientation = Minecraft.getInstance().gameRenderer.mainCamera().rotation()
		val eye = Minecraft.getInstance().player?.position() ?: return

		for (pos in predicted) {
			WorldRender.drawBlock(
				poseStack = poseStack,
				collector = collector,
				pos = pos,
				outlineArgb = detectorOutline.argb,
				fillArgb = detectorFill.argb,
				outline = detectorMode.selectedIndex != MODE_FILL,
				fill = detectorMode.selectedIndex != MODE_OUTLINE,
				phase = detectorPhase.value,
				lineWidth = 2f,
				fullBlock = true,
			)
			if (detectorTracer.value) {
				WorldRender.drawTracer(poseStack, collector, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, detectorTracerColor.argb, 3f, true)
			}
			if (detectorLabel.value) {
				val distance = eye.distanceTo(Vec3.atCenterOf(pos))
				WorldRender.drawText(
					poseStack = poseStack,
					collector = collector,
					orientation = orientation,
					text = Component.literal("Treasure: §e${"%.1f".format(distance)}m"),
					x = pos.x + 0.5,
					y = pos.y + 1.5,
					z = pos.z + 0.5,
					scale = (distance / 12.0).coerceIn(1.0, 4.0).toFloat(),
					seeThrough = true,
					centered = true,
				)
			}
		}
	}

	private fun renderChests(context: LevelRenderContext) {
		if (chests.isEmpty() || inMines) return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val client = Minecraft.getInstance()
		val orientation = client.gameRenderer.mainCamera().rotation()
		if (treasureChests.value) renderChestHighlights(poseStack, collector, orientation)
		if (lockHelper.value) renderLock(poseStack, collector, orientation)
	}

	private fun renderChestHighlights(
		poseStack: com.mojang.blaze3d.vertex.PoseStack,
		collector: net.minecraft.client.renderer.SubmitNodeCollector,
		orientation: org.joml.Quaternionf,
	) {
		val player = Minecraft.getInstance().player ?: return
		val now = System.currentTimeMillis()
		val fillAlpha = (chestTimeFillAlpha.value / 100.0 * 255).roundToInt().coerceIn(0, 255)

		for ((chest, despawn) in chests) {
			val left = despawn - now
			val outlineArgb: Int
			val fillArgb: Int
			if (chestTimeColors.value) {
				outlineArgb = timeColor(left)
				fillArgb = (timeColor(left) and 0xFFFFFF) or (fillAlpha shl 24)
			} else {
				outlineArgb = chestOutline.argb
				fillArgb = chestFill.argb
			}
			WorldRender.drawBlock(
				poseStack = poseStack,
				collector = collector,
				pos = chest,
				outlineArgb = outlineArgb,
				fillArgb = fillArgb,
				outline = chestMode.selectedIndex != MODE_FILL,
				fill = chestMode.selectedIndex != MODE_OUTLINE,
				phase = chestPhase.value,
				lineWidth = 2f,
				fullBlock = true,
			)
			if (chestTimer.value) {
				// Above a chest below you, under one above, so the block never hides it.
				val y = if (chest.y <= player.y) chest.y + 1.25 else chest.y - 0.25
				WorldRender.drawText(
					poseStack = poseStack,
					collector = collector,
					orientation = orientation,
					text = Component.literal(secondsLeft(left)),
					x = chest.x + 0.5,
					y = y,
					z = chest.z + 0.5,
					scale = 1f,
					seeThrough = true,
					argb = timeColor(left),
					centered = true,
				)
			}
		}

		val line = lineOrder(player.position())
		line.firstOrNull()?.let { (first, despawn) ->
			WorldRender.drawTracer(poseStack, collector, first.x + 0.5, first.y + 0.5, first.z + 0.5, lineColor(despawn - now), 3f, chestPhase.value)
		}
		for ((from, to) in line.zipWithNext()) {
			WorldRender.drawLine(poseStack, collector, listOf(Vec3.atCenterOf(from.key), Vec3.atCenterOf(to.key)), lineColor(from.value - now), 3f, chestPhase.value)
		}
	}

	/** The next lock on the aimed chest, at its newest sparks, and the locks picked so far. */
	private fun renderLock(
		poseStack: com.mojang.blaze3d.vertex.PoseStack,
		collector: net.minecraft.client.renderer.SubmitNodeCollector,
		orientation: org.joml.Quaternionf,
	) {
		val chest = (Minecraft.getInstance().hitResult as? BlockHitResult)?.blockPos?.takeIf { it in chests } ?: return
		val middle = Vec3.atCenterOf(chest)
		val argb = 0xFFFFAA00.toInt()

		val now = System.currentTimeMillis()
		while (sparks.isNotEmpty() && now - sparks.first().second > SPARK_MEMORY_MS) sparks.removeFirst()
		val newest = sparks.lastOrNull()?.second
		val burst = if (newest == null) emptyList() else sparks.filter { (at, time) -> newest - time <= BURST_MS && at.closerThan(middle, SPARK_REACH) }.map { it.first }
		if (burst.isNotEmpty()) {
			val spot = burst.fold(Vec3.ZERO, Vec3::add).scale(1.0 / burst.size)
			val half = LOCK_SIZE / 2
			WorldRender.drawBox(
				poseStack = poseStack,
				collector = collector,
				minX = spot.x - half, minY = spot.y - half, minZ = spot.z - half,
				maxX = spot.x + half, maxY = spot.y + half, maxZ = spot.z + half,
				outlineArgb = argb,
				fillArgb = argb,
				outline = false,
				fill = true,
				phase = true,
				lineWidth = 1f,
			)
		}

		val picked = locks[chest] ?: 0
		val label = when {
			locksPerChest > 0 -> "${minOf(picked, locksPerChest)}/$locksPerChest"
			picked > 0 -> "$picked picked"
			else -> return
		}
		WorldRender.drawText(
			poseStack = poseStack,
			collector = collector,
			orientation = orientation,
			text = Component.literal(label),
			x = middle.x,
			y = middle.y + 1.3,
			z = middle.z,
			scale = 1f,
			seeThrough = true,
			argb = argb,
			centered = true,
		)
	}
}
