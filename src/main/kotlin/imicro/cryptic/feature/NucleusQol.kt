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
import imicro.cryptic.skyblock.SkyblockLocation
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
 * Small conveniences for the Crystal Hollows, most of them around its Nucleus.
 *
 * A key that warps to the nucleus, waypoints worked out from where you are
 * standing, the five crystal slots in the nucleus drawn in their crystals'
 * colours, the crystals themselves where they are picked up, the Mines of
 * Divan's metal detector narrowed down to the chest, and the treasure chests
 * mining uncovers. Everything only acts inside the Crystal Hollows.
 *
 * Most of it is SkyHanni's (LGPL-2.1, Copyright (c) hannibal2 and
 * contributors): the crystal slots are its NucleusBarriersBox, the metal
 * detector its MetalDetectorSolver — with the treasure's 39 places from the
 * SkyHanni repository (MIT, Copyright (c) 2022 hannibal2) — and the treasure
 * chests its Powder Chest Timer. None of them read the sidebar: where you are
 * comes from where you stand and what the world around you is built of. The
 * lock on a treasure chest is Skyblocker's CrystalsChestHighlighter (LGPL-3.0,
 * Copyright (c) the Skyblocker contributors).
 */
object NucleusQol {
	private const val SOURCE = "Nucleus QoL"

	/** Indices into [mode]. */
	private const val MODE_OUTLINE = 0
	private const val MODE_FILL = 1

	/** Indices into [crystalStyle]. */
	private const val STYLE_FILL = 1

	/** Indices into [chestLines]. */
	private const val LINES_OLDEST = 0
	private const val LINES_NEAREST = 1
	private const val LINES_NONE = 2

	private const val NUCLEUS = "Crystal Nucleus"
	private const val MINES_OF_DIVAN = "Mines of Divan"

	/**
	 * Where each waypoint sits relative to the block the player is standing in,
	 * as (x, y, z).
	 */
	private val OFFSETS = listOf(
		Triple(4, 10, 65),
		Triple(29, -32, 65),
		Triple(29, -32, 48),
	)

	private val waypointsSection = SectionModuleSetting("waypoints_section", "Waypoints")

	private val calculate = ButtonModuleSetting("calculate", "Calculate waypoints", action = { calculate() })

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

	@JvmField
	val crystalStyle = DropdownModuleSetting(
		id = "crystal_style",
		label = "Style",
		options = listOf("Outline", "Fill"),
		defaultIndex = STYLE_FILL,
		visibleIf = anyCrystals,
	)

	// SkyHanni's colours, alpha and all.
	@JvmField val amberColor = crystalColor("amber_color", "Amber", 0xCB6400, 127)
	@JvmField val amethystColor = crystalColor("amethyst_color", "Amethyst", 0xA80BCB, 127)
	@JvmField val topazColor = crystalColor("topaz_color", "Topaz", 0xCDC400, 127)
	@JvmField val jadeColor = crystalColor("jade_color", "Jade", 0x68CB00, 85)
	@JvmField val sapphireColor = crystalColor("sapphire_color", "Sapphire", 0x2964CB, 127)

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

	/** How opaque the fill is while it is coloured by time. */
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
	)

	/**
	 * Unbound out of the box. It sends a command, so a key that is also used
	 * for something else would warp somebody away mid-task — which one to give
	 * up is theirs to decide.
	 */
	@JvmField
	val warpKey = KeybindModuleSetting(
		id = "warp_key",
		label = "Warp to Nucleus",
		description = "Warps you to the Nucleus while you are in the Crystal Hollows.",
	)

	@JvmField
	val module = Module(
		id = "nucleus_qol",
		name = "Nucleus QoL",
		description = "Nucleus warp, waypoints, crystals and treasure",
		category = ModuleCategory.GENERAL,
		settings = listOf(
			warpKey,
			waypointsSection, calculate, clear, mode, fillColor, outlineColor, lineWidth, phase,
			crystalsSection, crystalSlots, crystalPickups, crystalStyle,
			amberColor, amethystColor, topazColor, jadeColor, sapphireColor,
		) + HollowsMap.settings + listOf(
			treasureSection,
			metalDetector.withMore(
				detectorMode, detectorFill, detectorOutline, detectorPhase, detectorTracer, detectorTracerColor, detectorLabel,
			),
			moleReminder, allToolsAlert, fullInventoryAlert,
			chestsSection,
			treasureChests.withMore(
				chestMode, chestTimeColors, chestFill, chestOutline, chestTimeFillAlpha, chestPhase,
				chestTimer, chestLines, chestLineCount,
			),
			lockHelper, muteChestFound, muteChestOpen,
		),
	)

	/** The blocks being marked, fixed at the moment they were calculated. */
	private var waypoints: List<BlockPos> = emptyList()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		HollowsMap.initialize()
		CrystalStatus.initialize()
		// Waypoints are measured from a spot in one lobby. Every server hop is a
		// different lobby, where the same numbers mark nothing — and the same
		// goes for the mines, the treasure and the chests.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetLobby() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetLobby() }
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
		toolBucket.clear()
		saidInventoryFull = false
		lastReading = -1.0
		lastReadingPos = null
		chests.clear()
		minedBlocks.clear()
		doubtfulChests.clear()
		activeParticles.clear()
		currentLockCount = 0
	}

	/** Whether the warp key was down last tick, so a held key warps once. */
	private var warpKeyDown = false

	private var ticksUntilScan = 0

	/**
	 * Warps on the tick the key goes down, with no menu open.
	 *
	 * Silent everywhere but the Hollows. A key that does nothing where it makes
	 * no sense needs no explanation — the setting's name says where it works,
	 * and a notification every time it is pressed for some other reason is noise.
	 */
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

		if (ticksUntilScan-- > 0) return
		ticksUntilScan = 10
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

	// --- Crystals ---------------------------------------------------------

	private enum class Crystal(val title: String, val box: AABB, val color: () -> ColorModuleSetting) {
		AMBER("Amber", slot(474, 524), { amberColor }),
		AMETHYST("Amethyst", slot(474, 492), { amethystColor }),
		TOPAZ("Topaz", slot(508, 473), { topazColor }),
		JADE("Jade", slot(542, 492), { jadeColor }),
		SAPPHIRE("Sapphire", slot(542, 524), { sapphireColor }),
	}

	/**
	 * One slot, corner to corner as SkyHanni gives it: 11 across and 13 high
	 * from its north-west corner, grown a hair so its faces do not fight the
	 * barrier blocks'.
	 */
	private fun slot(x: Int, z: Int) =
		AABB(x.toDouble(), 111.0, z.toDouble(), x + 11.0, 124.0, z + 11.0).inflate(0.002)

	/** The middle of the Hollows, where the nucleus stands, from x and z 462 to 564. */
	private const val NUCLEUS_MIN = 462.0
	private const val NUCLEUS_MAX = 564.0

	private var inNucleus = false

	/**
	 * Where you stand rather than what the sidebar says, which is what SkyHanni
	 * goes by too: the nucleus is the same square in every lobby. The sidebar is
	 * still taken when it does say so.
	 */
	private fun isInNucleus(client: Minecraft): Boolean {
		val player = client.player ?: return false
		if (player.x in NUCLEUS_MIN..NUCLEUS_MAX && player.z in NUCLEUS_MIN..NUCLEUS_MAX) return true
		return SkyblockLocation.zone() == NUCLEUS
	}

	/** "Amber Crystal", or an item's SkyBlock id "AMBER_CRYSTAL". */
	private val crystalName = Regex("""(Amber|Amethyst|Topaz|Jade|Sapphire) Crystal""")

	private fun crystalIn(text: String): Crystal? {
		val name = crystalName.find(text.replace(FORMATTING, ""))?.groupValues?.get(1) ?: return null
		return Crystal.entries.firstOrNull { it.title == name }
	}

	/**
	 * The crystal an item shows, if any. Hypixel draws its crystals with its
	 * resource pack's model, `hypixel_skyblock:item/uncategorized/jade_crystal`,
	 * on plain paper, so the model is the surest sign; the SkyBlock id and the
	 * name are kept for anything shown the older way.
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

	/** An armour stand's slots, head first, since a crystal shown on a stand is usually worn. */
	private val STAND_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST)

	/** A crystal lying about somewhere to be picked up, and the box drawn on it. */
	private class Pickup(val crystal: Crystal, val box: AABB)

	private var pickups: List<Pickup> = emptyList()

	private const val PICKUP_RANGE = 96.0

	/**
	 * Crystals near enough to see. No mod that was looked at for this tracks a
	 * crystal before it is picked up, so this looks wherever Hypixel can show
	 * an item: worn or held by a stand, in an item display, or on the ground —
	 * and at a stand named after one. `/cryptic debug crystals` lists what is
	 * around if Hypixel shows them some other way.
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
					// A display has no size of its own; a block around where it stands.
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

	/** What an item is, for the debug list: its name, SkyBlock id and model. */
	private fun describeItem(stack: ItemStack): String {
		if (stack.isEmpty) return "-"
		val model = stack.get(DataComponents.ITEM_MODEL)?.toString() ?: "-"
		return "${stack.hoverName.string.replace(FORMATTING, "")} ${SkyblockItem.id(stack)} model $model"
	}

	/** For `/cryptic debug crystals`: every stand and item near you, in case a crystal is being missed. */
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
		return lines + HollowsMap.describe()
	}

	// --- Crystal barriers -------------------------------------------------

	/**
	 * Each crystal's own part of the Hollows, as the sidebar names it. A
	 * crystal waiting to be picked up stands in a little room of barrier
	 * blocks, the way the Jade crystal stands on the Mines of Divan's pedestal.
	 */
	private val CRYSTAL_ZONES = mapOf(
		MINES_OF_DIVAN to Crystal.JADE,
		"Goblin Queen's Den" to Crystal.AMBER,
		"Jungle Temple" to Crystal.AMETHYST,
		"Lost Precursor City" to Crystal.SAPPHIRE,
		"Khazad-dûm" to Crystal.TOPAZ,
	)

	/**
	 * A crystal's space: 11 blocks across, 13 high and 11 deep, filled with
	 * barriers. While the crystal is there, part of it is the crystal's glass
	 * instead, which splits the barriers up — so the space is followed through
	 * both, and then drawn at its full size whatever was found of it.
	 */
	private const val CRYSTAL_WIDTH = 11
	private const val CRYSTAL_HEIGHT = 13

	/** How far from where it started the search may go: a little past a crystal's own size. */
	private const val ROOM_REACH = 14

	/** No crystal's space holds more blocks than this. */
	private const val MAX_ROOM_BLOCKS = CRYSTAL_WIDTH * CRYSTAL_HEIGHT * CRYSTAL_WIDTH + 200

	/** How many blocks, glass included, one search may look at before giving up. */
	private const val MAX_ROOM_VISITS = 8_000

	/** Barriers and glass joined face to face, the box around them, and how many there were. */
	class BarrierCluster(val box: AABB, val blocks: Int, val crystal: AABB?)

	/** A barrier, or any glass a crystal is built from. */
	private fun isRoomBlock(state: BlockState): Boolean =
		state.`is`(Blocks.BARRIER) || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).path.contains("glass")

	/**
	 * Everything joined to [start] through barriers and glass, kept within
	 * [ROOM_REACH] of it, and the crystal's space it makes up when it is
	 * about that size: null for the crystal when it is a wall or a scrap.
	 */
	private fun barrierCluster(level: net.minecraft.world.level.Level, start: BlockPos, seen: MutableSet<BlockPos> = HashSet()): BarrierCluster {
		val queue = ArrayDeque<BlockPos>()
		queue += start
		seen += start
		// Measured by the barriers alone: glass is crossed to reach every part of
		// the space, but glass beside it — a window, a gemstone — is not the space.
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
		return BarrierCluster(box.inflate(0.002), count, if (overflow) null else crystalSpace(box))
	}

	/**
	 * The crystal's whole space, when [found] is close enough to its size to be
	 * one: a couple of blocks short for a corner the search could not reach, or
	 * a little over where glass outside it touched. Centred on what was found.
	 */
	private fun crystalSpace(found: AABB): AABB? {
		fun near(size: Double, wanted: Int) = size >= wanted - 3 && size <= wanted + 2
		if (!near(found.xsize, CRYSTAL_WIDTH) || !near(found.ysize, CRYSTAL_HEIGHT) || !near(found.zsize, CRYSTAL_WIDTH)) return null
		val minX = Math.round(found.center.x - CRYSTAL_WIDTH / 2.0).toDouble()
		val minY = Math.round(found.center.y - CRYSTAL_HEIGHT / 2.0).toDouble()
		val minZ = Math.round(found.center.z - CRYSTAL_WIDTH / 2.0).toDouble()
		return AABB(minX, minY, minZ, minX + CRYSTAL_WIDTH, minY + CRYSTAL_HEIGHT, minZ + CRYSTAL_WIDTH).inflate(0.002)
	}

	/** Every cluster of barriers within [radius] blocks across and [height] up and down of you. */
	private fun barrierClusters(client: Minecraft, radius: Int, height: Int): List<BarrierCluster> {
		val level = client.level ?: return emptyList()
		val origin = client.player?.blockPosition() ?: return emptyList()
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

	/**
	 * Each crystal's space once it has been found, for the rest of the lobby:
	 * the Hollows are built once per lobby, and a crystal taken leaves its
	 * space where it was.
	 */
	private val crystalSpaces = HashMap<Crystal, AABB>()

	private var lastAreaScan = 0L

	/** How often the barriers around you are looked through again, until the crystal's space is found. */
	private const val AREA_SCAN_MS = 5_000L

	/** The crystal whose part of the Hollows you are in, if any. */
	private fun currentCrystalArea(): Crystal? = if (inMines) Crystal.JADE else CRYSTAL_ZONES[SkyblockLocation.zone()]

	/**
	 * In a crystal's part of the Hollows, its space among the barriers near you.
	 * Looked for every few seconds until it turns up, then kept.
	 */
	private fun scanArea(client: Minecraft) {
		if (!crystalPickups.value || inNucleus) return
		val crystal = currentCrystalArea() ?: return
		if (crystal in crystalSpaces) return
		val now = System.currentTimeMillis()
		if (now - lastAreaScan < AREA_SCAN_MS) return
		lastAreaScan = now
		val player = client.player ?: return
		barrierClusters(client, 48, 32)
			.mapNotNull { it.crystal }
			.minByOrNull { it.center.distanceToSqr(player.position()) }
			?.let { crystalSpaces[crystal] = it }
	}

	/** Clusters `/cryptic debug barriers` is showing, and until when. */
	private var debugBarriers: List<AABB> = emptyList()
	private var debugBarriersUntil = 0L

	private fun describeBox(b: AABB): String =
		"${b.xsize.roundToInt()}x${b.ysize.roundToInt()}x${b.zsize.roundToInt()} from ${b.minX.roundToInt()} ${b.minY.roundToInt()} ${b.minZ.roundToInt()} " +
			"to ${b.maxX.roundToInt() - 1} ${b.maxY.roundToInt() - 1} ${b.maxZ.roundToInt() - 1}"

	/** For `/cryptic debug block`: the barriers and glass [pos] is joined to, and whether they make a crystal's space. */
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

	/** Which crystal's part of the Hollows you are in, by name, or "none". */
	private fun placeDescription(): String {
		val zone = SkyblockLocation.zone()
		val crystal = currentCrystalArea()
		return "Place: ${zone ?: "unknown"}" +
			(crystal?.let { " (the ${it.title} crystal's area)" } ?: " (no crystal here)") +
			", in the nucleus: $inNucleus, in the Mines of Divan: $inMines"
	}

	/** For `/cryptic debug barriers`: every barrier cluster near you, outlined for a minute and listed. */
	fun describeBarriers(): List<String> {
		val client = Minecraft.getInstance()
		val player = client.player ?: return listOf("Not in a world")
		val clusters = barrierClusters(client, 48, 32).sortedBy { it.box.center.distanceToSqr(player.position()) }
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

	// --- Metal Detector ---------------------------------------------------

	private val TREASURE = Regex("""TREASURE: (\d+(?:\.\d+)?)m""")

	/** "You found Gemstone Powder x147 with your Metal Detector!" */
	private val TREASURE_FOUND = Regex("""^You found .*with your Metal Detector!$""")

	/**
	 * Every place the treasure turns up, from the corner of the barriers around
	 * the Jade crystal in the middle of the mines: the corner minus each of
	 * these is a chest. SkyHanni's repository, MetalDetectorChests.
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

	/** The Mines of Divan reach about 50 blocks out from their middle; a little more for the walls. */
	private const val MINES_REACH = 60.0

	private val KEEPER = Regex("""^Keeper of (Diamond|Lapis|Emerald|Gold)$""")

	/** From each Keeper to the middle of the mines, as Skyblocker has them. */
	private val KEEPER_OFFSETS = mapOf(
		"Diamond" to BlockPos(33, 0, 3),
		"Lapis" to BlockPos(-33, 0, -3),
		"Emerald" to BlockPos(-3, 0, 33),
		"Gold" to BlockPos(3, 0, -33),
	)

	/** The corner of the barriers around the Jade pedestal, which the chest offsets are measured from. */
	private var minesBase: BlockPos? = null

	/** The middle of the mines as the Keepers place it, known before the base has been searched for. */
	private var keeperCenter: BlockPos? = null

	private var lastBaseSearch = 0L

	/** Whether you are in the Mines of Divan, which is the only place the solver works. */
	private var inMines = false

	/** Where the treasure could be. One left is the answer, and is kept for the lobby until it is dug up. */
	private var predicted: List<BlockPos> = emptyList()

	/** The last reading, and where you stood for it: a reading counts once it repeats with you standing still. */
	private var lastReading = -1.0
	private var lastReadingPos: Vec3? = null

	/** The detector rounds to a tenth; a little more allows for where on the block you stand. */
	private const val READING_TOLERANCE = 0.15

	private fun fits(chest: BlockPos, position: Vec3, distance: Double): Boolean {
		val measured = position.distanceTo(Vec3(chest.x.toDouble(), chest.y + 1.0, chest.z.toDouble()))
		return Math.abs(measured - distance) < READING_TOLERANCE
	}

	/**
	 * The action bar, from either packet Hypixel sends it in.
	 *
	 * A reading is only used once it has come twice with you standing still,
	 * as Skyblocker does: the bar lags behind you while you walk, and a stale
	 * distance is how a wrong spot got singled out and kept. Each still reading
	 * narrows the guesses left from the last one, checked against SkyHanni's
	 * places, and an answer a later reading disagrees with is thrown out and
	 * worked out again.
	 */
	@JvmStatic
	fun onActionBar(text: Component) {
		if (!module.enabled || !SkyblockLocation.inCrystalHollows || !inMines) return
		val player = Minecraft.getInstance().player ?: return
		val distance = TREASURE.find(text.string.replace(FORMATTING, ""))?.groupValues?.get(1)?.toDoubleOrNull() ?: return
		checkTools(player)
		if (!metalDetector.value) return

		val position = player.position()
		val still = distance == lastReading && lastReadingPos?.let { it.distanceToSqr(position) < 0.0025 } == true
		lastReading = distance
		lastReadingPos = position
		if (!still) return

		if (minesBase == null) findMinesBase()
		val base = minesBase ?: return

		// Narrow what is left; start over when nothing is, or the answer no longer agrees.
		val before = predicted.size
		val narrowed = predicted.filter { fits(it, position, distance) }
		predicted = narrowed.ifEmpty {
			CHEST_OFFSETS.map { base.subtract(it) }.filter { fits(it, position, distance) }
		}
		if (predicted.size > 1 && predicted.size != before) {
			Toasts.show(SOURCE, "§f${predicted.size} §7possible locations, move a little to find the right one", color = Toasts.Colors.MINING)
		}
	}

	/** Chat and the action bar, which Hypixel can also send as an overlay chat line. */
	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay) {
			onActionBar(content)
			return
		}
		if (!module.enabled || !SkyblockLocation.inCrystalHollows) return
		HollowsMap.onSystemChat(content, false)
		val text = content.string.replace(FORMATTING, "").trim()
		TREASURE_FOUND.matchEntire(text)?.let { found ->
			// The treasure dug up, so the next reading is a new one. Until then it
			// stays marked, however close you get or however often you leave.
			predicted = emptyList()
			// A tool goes in the bucket wherever it ended up, inventory or stash.
			TOOLS.entries.firstOrNull { found.value.contains(it.value) }?.let { addTool(it.key) }
		}
		if (tracksChests && !inMines) {
			if (text == "You uncovered a treasure chest!") chestConfirmed()
			// Hypixel leaves a picked chest standing, so it is let go of here.
			if (text == "CHEST LOCKPICKED" || text == "This chest has already been looted.") chestLooted()
		}
	}

	// --- Tools ------------------------------------------------------------

	/** The four Keepers' tools, by SkyBlock id, and the name the found message gives each. */
	private val TOOLS = mapOf(
		"DWARVEN_LAPIS_SWORD" to "Scavenged Lapis Sword",
		"DWARVEN_EMERALD_HAMMER" to "Scavenged Emerald Hammer",
		"DWARVEN_GOLD_HAMMER" to "Scavenged Golden Hammer",
		"DWARVEN_DIAMOND_AXE" to "Scavenged Diamond Axe",
	)

	private const val METAL_DETECTOR = "DWARVEN_METAL_DETECTOR"

	/**
	 * The tools you have for this visit to the mines, one of each at most. It
	 * starts with what is in your inventory as you walk in, takes every tool
	 * the chat says you found — whether it went to the inventory or the stash —
	 * and is emptied when you leave.
	 */
	private val toolBucket = HashSet<String>()

	private var saidInventoryFull = false

	/** Fills the bucket from the inventory, on walking into the mines. */
	private fun fillBucketFromInventory(player: net.minecraft.world.entity.player.Player) {
		toolBucket.clear()
		for (slot in 0 until 36) {
			val id = SkyblockItem.id(player.inventory.getItem(slot))
			if (id in TOOLS) toolBucket += id
		}
	}

	private fun addTool(id: String) {
		if (!inMines || !toolBucket.add(id)) return
		if (allToolsAlert.value && toolBucket.size == TOOLS.size) {
			Toasts.show(SOURCE, "§aAll tools found", color = Toasts.Colors.MINING)
		}
	}

	/**
	 * A warning while the inventory is full, since whatever is dug up next goes
	 * to the stash. Only while the Metal Detector is in your hand.
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
	 * Whether you are in the Mines of Divan: near the middle the Keepers or the
	 * Jade pedestal give, or on a sidebar that says so. The treasure is only
	 * drawn here, and kept while you are away.
	 */
	private fun updateInMines(client: Minecraft) {
		val player = client.player ?: return
		findKeeperCenter(client)
		fun near(center: BlockPos?) = center != null &&
			Math.abs(player.x - center.x) <= MINES_REACH && Math.abs(player.z - center.z) <= MINES_REACH
		val now = near(minesBase) || near(keeperCenter) || SkyblockLocation.zone() == MINES_OF_DIVAN
		if (now && !inMines) {
			inMines = true
			fillBucketFromInventory(player)
			onEnterMines()
		}
		// The bucket is per visit: tools given to the Keepers are gone next time.
		if (!now && inMines) toolBucket.clear()
		inMines = now
	}

	/** The Keepers' nametags, each a known step from the middle of the mines. Skyblocker's way in. */
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
	 * Walking into the mines: the Mole reminder, and the Jade pedestal looked
	 * for so its crystal can be boxed. The pet is read from the tab list's Pet
	 * widget, which not everybody shows.
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
	 * The middle of the mines: quartz stairs under a barrier 13 blocks above,
	 * which is the Jade crystal's pedestal, then the barriers followed out to
	 * their far corner. SkyHanni's search. The barriers themselves are where
	 * the Jade crystal stands.
	 */
	private fun findMinesBase() {
		val now = System.currentTimeMillis()
		if (now - lastBaseSearch < BASE_SEARCH_COOLDOWN_MS) return
		lastBaseSearch = now
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val origin = client.player?.blockPosition() ?: return
		val probe = BlockPos.MutableBlockPos()
		val above = BlockPos.MutableBlockPos()
		for (dx in -50 until 50) for (dy in 30 downTo -30) for (dz in -50 until 50) {
			probe.set(origin.x + dx, origin.y + dy, origin.z + dz)
			if (!level.getBlockState(probe).`is`(Blocks.QUARTZ_STAIRS)) continue
			above.set(probe.x, probe.y + 13, probe.z)
			if (!level.getBlockState(above).`is`(Blocks.BARRIER)) continue
			var corner = above.immutable()
			var moved = true
			while (moved) {
				moved = false
				for (step in listOf(BlockPos(1, 0, 0), BlockPos(0, 1, 0), BlockPos(0, 0, 1))) {
					val next = corner.offset(step)
					if (level.getBlockState(next).`is`(Blocks.BARRIER)) {
						corner = next
						moved = true
					}
				}
			}
			minesBase = corner
			barrierCluster(level, above.immutable()).crystal?.let { crystalSpaces[Crystal.JADE] = it }
			return
		}
	}

	// --- Treasure chests --------------------------------------------------

	/** How long a treasure chest stays before it despawns. */
	private const val CHEST_LIFETIME_MS = 60_000L

	/** Further than this from you, a chest appearing is not one you uncovered. */
	private const val MAX_CHEST_DISTANCE = 15.0

	/** With someone else this close, a chest could be theirs. */
	private const val NEAR_PLAYER_DISTANCE = 25.0

	/** A particle older than this is from a lock already picked. */
	private const val PARTICLE_LIFETIME_MS = 250L

	private const val LOCK_SIZE = 0.1

	/** Each chest found, and when it despawns. */
	private val chests = LinkedHashMap<BlockPos, Long>()

	/** Blocks you broke in the last five seconds, and when, since a chest is uncovered where one was. */
	private val minedBlocks = HashMap<BlockPos, Long>()

	/** When Hypixel last played the chest-found level-up, which a chest appearing can be checked against. */
	@Volatile
	private var lastFoundSound = 0L

	private val activeParticles = HashMap<Vec3, Long>()
	private var currentLockCount = 0
	private var neededLockCount = 0

	/**
	 * A block the server set, on the client thread. A chest turning up within
	 * reach where a block was just mined is a treasure chest; one turning up
	 * anywhere else counts only when the found sound has just played, or when
	 * someone else is near enough that it could be theirs.
	 */
	@JvmStatic
	fun onBlockChanged(pos: BlockPos, oldState: BlockState, newState: BlockState) {
		// The Metal Detector's treasure is a chest too; in the mines it is the only kind.
		if (!module.enabled || !tracksChests || !SkyblockLocation.inCrystalHollows || inMines) return
		val player = Minecraft.getInstance().player ?: return
		if (pos.distToCenterSqr(player.position()) > MAX_CHEST_DISTANCE * MAX_CHEST_DISTANCE) return
		val now = System.currentTimeMillis()
		val wasChest = oldState.`is`(Blocks.CHEST)
		val isChest = newState.`is`(Blocks.CHEST)

		when {
			oldState.`is`(Blocks.STONE) && newState.isAir -> minedBlocks[pos.immutable()] = now
			!wasChest && isChest -> {
				val mined = minedBlocks.remove(pos)?.let { now - it < 5_000 } == true
				val doubtful = playersNearby() || (!mined && oldState.isAir)
				if (doubtful && now - lastFoundSound > 200) {
					// The sound or the chat line may still be on its way.
					doubtfulChests[pos.immutable()] = now
					return
				}
				chests[pos.immutable()] = now + CHEST_LIFETIME_MS
				currentLockCount = 0
			}
			wasChest && !isChest -> {
				chests.remove(pos)
				currentLockCount = 0
			}
		}
	}

	/** Whether chests are being followed at all: for their highlight, or for the lock pick helper. */
	private val tracksChests: Boolean get() = treasureChests.value || lockHelper.value

	/**
	 * "CHEST LOCKPICKED": the chest you are looking at, or else the nearest
	 * one within reach, is done with. Hypixel leaves it standing a while.
	 */
	private fun chestLooted() {
		val player = Minecraft.getInstance().player ?: return
		val target = (Minecraft.getInstance().hitResult as? BlockHitResult)?.blockPos
		val looted = target?.takeIf { it in chests }
			?: chests.keys.filter { it.distToCenterSqr(player.position()) <= 36.0 }
				.minByOrNull { it.distToCenterSqr(player.position()) }
			?: return
		chests.remove(looted)
		currentLockCount = 0
		activeParticles.clear()
	}

	/** Chests that appeared without a sign they were yours, held a second in case one follows. */
	private val doubtfulChests = HashMap<BlockPos, Long>()

	/** The found sound or "You uncovered a treasure chest!", either side of the chest itself. */
	private fun chestConfirmed() {
		val now = System.currentTimeMillis()
		lastFoundSound = now
		val iterator = doubtfulChests.entries.iterator()
		while (iterator.hasNext()) {
			val (pos, seen) = iterator.next()
			if (now - seen < 1_000) chests[pos] = seen + CHEST_LIFETIME_MS
			iterator.remove()
		}
	}

	private var playersNearbyCheckedAt = 0L
	private var playersNearbyCached = false

	/** Whether a real player — not one of Hypixel's NPCs — is close by, checked every five seconds. */
	private fun playersNearby(): Boolean {
		val now = System.currentTimeMillis()
		if (now - playersNearbyCheckedAt < 5_000) return playersNearbyCached
		playersNearbyCheckedAt = now
		val client = Minecraft.getInstance()
		val self = client.player ?: return false
		playersNearbyCached = client.level?.players()?.any {
			it !== self && it.uuid.version() == 4 && it.distanceTo(self) < NEAR_PLAYER_DISTANCE
		} == true
		return playersNearbyCached
	}

	private fun tickChests(client: Minecraft) {
		if (chests.isEmpty() && minedBlocks.isEmpty() && doubtfulChests.isEmpty()) return
		val now = System.currentTimeMillis()
		val level = client.level ?: return
		minedBlocks.values.removeIf { now - it > 5_000 }
		doubtfulChests.values.removeIf { now - it > 1_000 }
		// Gone when the time is up, and as soon as it opens.
		chests.entries.removeIf { (pos, despawn) ->
			now > despawn + 1_000 || ((level.getBlockEntity(pos) as? ChestBlockEntity)?.getOpenNess(1f) ?: 0f) > 0f
		}
	}

	/** The crit sparks a chest gives off where the next lock is. */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (!Minecraft.getInstance().isSameThread) return
		if (!module.enabled || !lockHelper.value || chests.isEmpty()) return
		if (packet.particle.type == ParticleTypes.CRIT) {
			activeParticles[Vec3(packet.x, packet.y, packet.z)] = System.currentTimeMillis()
		}
	}

	private fun SoundEvent.isSound(other: SoundEvent) = location() == other.location()

	/**
	 * A chest found, a lock picked, a wrong click, or a chest opening, each told
	 * by its sound, from either packet: Hypixel plays some sounds at a place
	 * and some on an entity. Only the client thread's pass, since the player is
	 * read; muting happens later, where the game plays the sound ([mutes]).
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
			// A packet handler that throws disconnects you. Not over a lock.
		}
	}

	/**
	 * Whether a sound about to play is one of the treasure chest sounds being
	 * muted. Asked by the sound manager, so it catches every way the sound
	 * arrived. The found sound is a level-up and the open one a chest, which
	 * in the Crystal Hollows are near enough always the treasure's.
	 */
	@JvmStatic
	fun mutes(instance: SoundInstance): Boolean {
		if (!module.enabled || !SkyblockLocation.inCrystalHollows) return false
		val id = instance.identifier
		return (muteChestFound.value && id == SoundEvents.PLAYER_LEVELUP.location()) ||
			(muteChestOpen.value && id == SoundEvents.CHEST_OPEN.location())
	}

	private fun onLockSound(client: Minecraft, sound: SoundEvent, pitch: Float) {
		if (!lockHelper.value) return
		val player = client.player ?: return
		when {
			sound.isSound(SoundEvents.EXPERIENCE_ORB_PICKUP) && pitch == 1f && chests.isNotEmpty() -> {
				if (!client.isSameThread) return
				val eye = player.getEyePosition(0f)
				val reach = player.getViewVector(0f).scale(player.blockInteractionRange())
				val hit = player.level().isBlockInLine(ClipBlockStateContext(eye, eye.add(reach)) { it.`is`(Blocks.CHEST) })
				if (hit.type != HitResult.Type.MISS) {
					currentLockCount++
					activeParticles.clear()
				}
			}
			sound.isSound(SoundEvents.VILLAGER_NO) -> {
				currentLockCount = 0
				activeParticles.clear()
			}
			sound.isSound(SoundEvents.CHEST_OPEN) -> {
				// However many locks it took is how many every chest has.
				neededLockCount = minOf(currentLockCount, 5)
				currentLockCount = 0
				activeParticles.clear()
			}
		}
	}

	/** Green with the full minute left, through yellow, to red as it despawns. */
	private fun timeColor(msLeft: Long): Int {
		val ratio = (msLeft.toDouble() / CHEST_LIFETIME_MS).coerceIn(0.0, 1.0)
		val red = (255 * (1 - ratio)).toInt()
		val green = (255 * ratio).toInt()
		return (0xFF shl 24) or (red shl 16) or (green shl 8)
	}

	/** The tracer's colour: the time fade, or the outline colour when that is off. */
	private fun lineColor(msLeft: Long): Int =
		if (chestTimeColors.value) timeColor(msLeft) else chestOutline.argb or (0xFF shl 24)

	private fun secondsLeft(msLeft: Long) ="${((msLeft + 999) / 1000).coerceAtLeast(0)}s"

	/** The chests the line runs through, in the order it runs. */
	private fun lineOrder(player: Vec3): List<Map.Entry<BlockPos, Long>> {
		val count = chestLineCount.value.toInt()
		return when (chestLines.selectedIndex) {
			LINES_OLDEST -> chests.entries.sortedBy { it.value }.take(count)
			LINES_NEAREST -> chests.entries.sortedBy { Vec3.atCenterOf(it.key).distanceToSqr(player) }.take(count)
			else -> emptyList()
		}
	}

	// --- Rendering --------------------------------------------------------

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
		val fill = crystalStyle.selectedIndex == STYLE_FILL
		fun box(box: AABB, crystal: Crystal) {
			val argb = crystal.color().argb
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = box.minX, minY = box.minY, minZ = box.minZ,
				maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
				// An outline at the fill's alpha would all but vanish, so it is drawn solid.
				outlineArgb = argb or (0xFF shl 24),
				fillArgb = argb,
				outline = !fill,
				fill = fill,
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

	/** SkyHanni's look by default: a red block through walls, a gold edge, a line from the crosshair and the distance. */
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

	/** Skyblocker's: the middle of the chest's sparks is the next lock, and the count of locks done. */
	private fun renderLock(
		poseStack: com.mojang.blaze3d.vertex.PoseStack,
		collector: net.minecraft.client.renderer.SubmitNodeCollector,
		orientation: org.joml.Quaternionf,
	) {
		val target = Minecraft.getInstance().hitResult as? BlockHitResult ?: return
		if (target.blockPos !in chests) return
		val chestCenter = Vec3.atCenterOf(target.blockPos)
		val argb = 0xFFFFAA00.toInt()

		val now = System.currentTimeMillis()
		activeParticles.entries.removeIf { now - it.value > PARTICLE_LIFETIME_MS }
		val near = activeParticles.keys.filter { it.closerThan(chestCenter, 0.8) }
		if (near.isNotEmpty()) {
			val spot = near.fold(Vec3.ZERO) { sum, it -> sum.add(it) }.scale(1.0 / near.size)
			val h = LOCK_SIZE / 2
			WorldRender.drawBox(
				poseStack = poseStack,
				collector = collector,
				minX = spot.x - h, minY = spot.y - h, minZ = spot.z - h,
				maxX = spot.x + h, maxY = spot.y + h, maxZ = spot.z + h,
				outlineArgb = argb,
				fillArgb = argb,
				outline = false,
				fill = true,
				phase = true,
				lineWidth = 1f,
			)
		}

		if (neededLockCount > 0) {
			WorldRender.drawText(
				poseStack = poseStack,
				collector = collector,
				orientation = orientation,
				text = Component.literal("${minOf(currentLockCount, neededLockCount)}/$neededLockCount"),
				x = chestCenter.x,
				y = chestCenter.y + 1.3,
				z = chestCenter.z,
				scale = 1f,
				seeThrough = true,
				argb = argb,
				centered = true,
			)
		}
	}
}
