package imicro.cryptic.feature

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
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
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

	private val treasureSection = SectionModuleSetting("treasure_section", "Treasure")

	@JvmField
	val metalDetector = ToggleModuleSetting(
		id = "metal_detector",
		label = "Metal Detector solver",
		defaultValue = true,
		description = "Works out where the Mines of Divan treasure is from the Metal Detector's distance. Stand still in a couple of places until one spot is left.",
	)

	@JvmField
	val treasureChests = ToggleModuleSetting(
		id = "treasure_chests",
		label = "Treasure chest helper",
		defaultValue = true,
		description = "Highlights the treasure chests mining uncovers with the time they have left, lines them up, and marks the spot to click for each lock.",
	)

	@JvmField
	val chestTimeColors = ToggleModuleSetting(
		id = "chest_time_colors",
		label = "Colour by time left",
		defaultValue = true,
		description = "Fades each chest from green to red as it runs out of time.",
	)

	@JvmField
	val chestColor = ColorModuleSetting(
		id = "chest_color",
		label = "Chest colour",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { !chestTimeColors.value },
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
		label = "Lines",
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
			treasureSection, metalDetector,
			treasureChests.withMore(
				chestTimeColors, chestColor, chestTimer, chestLines, chestLineCount,
				lockHelper, muteChestFound, muteChestOpen,
			),
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
		Hud.register(ChestTimerElement())
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
		ignoredTreasure = null
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
		tickTreasure(client)
		tickChests(client)

		if (ticksUntilScan-- > 0) return
		ticksUntilScan = 10
		inNucleus = isInNucleus(client)
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

	private fun crystalOf(stack: ItemStack): Crystal? {
		if (stack.isEmpty) return null
		val id = SkyblockItem.id(stack)
		if (id.endsWith("_CRYSTAL")) Crystal.entries.firstOrNull { it.name == id.removeSuffix("_CRYSTAL") }?.let { return it }
		return crystalIn(stack.hoverName.string)
	}

	/** A crystal lying about somewhere to be picked up, and the box drawn on it. */
	private class Pickup(val crystal: Crystal, val box: AABB)

	private var pickups: List<Pickup> = emptyList()

	private const val PICKUP_RANGE = 96.0

	/**
	 * Crystals near enough to see. No mod that was looked at for this tracks a
	 * crystal before it is picked up, so this looks for one the general way:
	 * a crystal head on a stand, a stand whose name is a crystal's, or a
	 * crystal item on the ground. `/cryptic debug crystals` lists what is
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
					val head = crystalOf(entity.getItemBySlot(EquipmentSlot.HEAD))
					if (head != null) {
						val eye = entity.getEyePosition()
						found += Pickup(head, AABB(eye.x - 0.4, eye.y - 0.55, eye.z - 0.4, eye.x + 0.4, eye.y + 0.25, eye.z + 0.4))
						continue
					}
					val named = entity.customName?.let { crystalIn(it.string) } ?: continue
					found += Pickup(named, AABB(entity.x - 0.5, entity.y - 1.0, entity.z - 0.5, entity.x + 0.5, entity.y, entity.z + 0.5))
				}
				is ItemEntity -> {
					val item = crystalOf(entity.item) ?: continue
					found += Pickup(item, entity.boundingBox.inflate(0.2))
				}
			}
		}
		return found
	}

	/** For `/cryptic debug crystals`: every stand and item near you, in case a crystal is being missed. */
	fun describeCrystals(): List<String> {
		val client = Minecraft.getInstance()
		val level = client.level ?: return listOf("Not in a world")
		val player = client.player ?: return listOf("Not in a world")
		val lines = mutableListOf(
			"Crystal Hollows: ${SkyblockLocation.inCrystalHollows}, in the nucleus: $inNucleus, " +
				"zone: ${SkyblockLocation.zone() ?: "unknown"}, highlighted: ${pickups.size}",
			"Mines of Divan base: ${minesBase?.toShortString() ?: "not found"}, treasure guesses: ${predicted.size}, chests: ${chests.size}",
		)
		for (entity in level.entitiesForRendering()) {
			if (entity.distanceToSqr(player) > 32.0 * 32.0) continue
			val pos = "%.1f %.1f %.1f".format(entity.x, entity.y, entity.z)
			when (entity) {
				is ArmorStand -> {
					val head = entity.getItemBySlot(EquipmentSlot.HEAD)
					val texture = SkyblockItem.skullTexture(head)?.let { " tex ${it.take(24)}…" } ?: ""
					val name = entity.customName?.string?.replace(FORMATTING, "") ?: "-"
					val headName = if (head.isEmpty) "-" else "${head.hoverName.string.replace(FORMATTING, "")} ${SkyblockItem.id(head)}"
					lines += "stand $pos \"$name\" head: $headName$texture"
				}
				is ItemEntity -> lines += "item $pos ${entity.item.hoverName.string.replace(FORMATTING, "")} ${SkyblockItem.id(entity.item)}"
			}
		}
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

	private var minesBase: BlockPos? = null
	private var lastBaseSearch = 0L
	private var baseMissingToasted = false

	/** Where the treasure could be. One left is the answer. */
	private var predicted: List<BlockPos> = emptyList()

	/** A treasure walked up to and dug, so the next reading does not point back at it. */
	private var ignoredTreasure: BlockPos? = null

	/**
	 * The action bar, from either packet Hypixel sends it in. Every reading is
	 * checked against all the places the treasure can be, rounded as the
	 * detector rounds — no reading needs to agree with the one before.
	 */
	@JvmStatic
	fun onActionBar(text: Component) {
		if (!module.enabled || !metalDetector.value || !SkyblockLocation.inCrystalHollows) return
		if (predicted.size == 1) return
		val player = Minecraft.getInstance().player ?: return
		val distance = TREASURE.find(text.string.replace(FORMATTING, ""))?.groupValues?.get(1)?.toDoubleOrNull() ?: return

		if (minesBase == null) findMinesBase()
		val base = minesBase ?: return

		val before = predicted.size
		val position = player.position()
		predicted = CHEST_OFFSETS
			.map { base.subtract(it) }
			.filter { it != ignoredTreasure }
			.filter { chest ->
				val measured = position.distanceTo(Vec3(chest.x.toDouble(), chest.y + 1.0, chest.z.toDouble()))
				(measured * 10).roundToInt() == (distance * 10).roundToInt()
			}

		if (predicted.size == 1) {
			Toasts.show(SOURCE, "Found the treasure", color = Toasts.Colors.MINING)
		} else if (predicted.size != before && predicted.size > 1) {
			Toasts.show(SOURCE, "§f${predicted.size} §7places it could be, stand still somewhere else", color = Toasts.Colors.MINING)
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
		val text = content.string.replace(FORMATTING, "").trim()
		// The treasure dug up, so the next reading is a new one.
		if (TREASURE_FOUND.matches(text)) predicted = emptyList()
		if (treasureChests.value && text == "You uncovered a treasure chest!") chestConfirmed()
	}

	/** Clears a found treasure once you are on top of it, and forgets it once you have walked away. */
	private fun tickTreasure(client: Minecraft) {
		val player = client.player ?: return
		val only = predicted.singleOrNull()
		if (only != null) {
			if (player.distanceToSqr(Vec3.atLowerCornerOf(only)) <= 25) {
				ignoredTreasure = only
				predicted = emptyList()
			}
		} else if (predicted.isEmpty()) {
			ignoredTreasure?.let { if (player.distanceToSqr(Vec3.atLowerCornerOf(it)) > 100) ignoredTreasure = null }
		}
	}

	/**
	 * The middle of the mines: quartz stairs under a barrier 13 blocks above,
	 * which is the Jade crystal's pedestal, then the barriers followed out to
	 * their far corner. SkyHanni's search.
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
			baseMissingToasted = false
			return
		}
		if (!baseMissingToasted) {
			baseMissingToasted = true
			Toasts.show(SOURCE, "Get closer to the middle of the Mines of Divan", true)
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
		if (!module.enabled || !treasureChests.value || !SkyblockLocation.inCrystalHollows) return
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
		if (!module.enabled || !treasureChests.value || !lockHelper.value || chests.isEmpty()) return
		if (packet.particle.type == ParticleTypes.CRIT) {
			activeParticles[Vec3(packet.x, packet.y, packet.z)] = System.currentTimeMillis()
		}
	}

	private fun SoundEvent.isSound(other: SoundEvent) = location() == other.location()

	/**
	 * A chest found, a lock picked, a wrong click, or a chest opening, each told
	 * by its sound. True when the sound is muted.
	 *
	 * The first pass is on the network thread and can only look at the packet;
	 * a muted sound never reaches the second, so the found sound is noted on
	 * both. Everything that reads the player waits for the client thread.
	 */
	@JvmStatic
	fun onSound(packet: ClientboundSoundPacket): Boolean {
		if (!module.enabled || !treasureChests.value || !SkyblockLocation.inCrystalHollows) return false
		val sound = packet.sound.value()
		val plain = packet.pitch == 1f && packet.volume == 1f
		if (sound.isSound(SoundEvents.PLAYER_LEVELUP) && plain) {
			lastFoundSound = System.currentTimeMillis()
			// The held chests are the client thread's to touch; when this is the
			// network's pass and the sound is muted, the chat line still follows.
			if (Minecraft.getInstance().isSameThread) chestConfirmed()
			return muteChestFound.value
		}
		val muted = sound.isSound(SoundEvents.CHEST_OPEN) && plain && muteChestOpen.value

		val client = Minecraft.getInstance()
		if (client.isSameThread || muted) {
			try {
				onLockSound(client, sound, packet.pitch)
			} catch (_: Exception) {
				// A packet handler that throws disconnects you. Not over a lock.
			}
		}
		return muted
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

	private fun secondsLeft(msLeft: Long) = "${((msLeft + 999) / 1000).coerceAtLeast(0)}s"

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
		}
	}

	/** SkyHanni's look: a red block through walls, a gold edge, a line from the crosshair and the distance. */
	private fun renderMetalDetector(context: LevelRenderContext) {
		if (!metalDetector.value || predicted.isEmpty()) return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val orientation = Minecraft.getInstance().gameRenderer.mainCamera().rotation()
		val eye = Minecraft.getInstance().player?.position() ?: return

		for (pos in predicted) {
			WorldRender.drawBlock(
				poseStack = poseStack,
				collector = collector,
				pos = pos,
				outlineArgb = 0xFFFFAA00.toInt(),
				fillArgb = 0x60FF5555,
				outline = true,
				fill = true,
				phase = true,
				lineWidth = 2f,
				fullBlock = true,
			)
			WorldRender.drawTracer(poseStack, collector, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 0xFFFFFFFF.toInt(), 3f, true)
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

	private fun renderChests(context: LevelRenderContext) {
		if (!treasureChests.value || chests.isEmpty()) return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val orientation = client.gameRenderer.mainCamera().rotation()
		val now = System.currentTimeMillis()

		for ((chest, despawn) in chests) {
			val left = despawn - now
			val color = if (chestTimeColors.value) (timeColor(left) and 0xFFFFFF) or 0x80000000.toInt() else chestColor.argb
			WorldRender.drawBlock(
				poseStack = poseStack,
				collector = collector,
				pos = chest,
				outlineArgb = color or (0xFF shl 24),
				fillArgb = color,
				outline = true,
				fill = true,
				phase = true,
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
			WorldRender.drawTracer(poseStack, collector, first.x + 0.5, first.y + 0.5, first.z + 0.5, timeColor(despawn - now), 3f, false)
		}
		for ((from, to) in line.zipWithNext()) {
			WorldRender.drawLine(poseStack, collector, listOf(Vec3.atCenterOf(from.key), Vec3.atCenterOf(to.key)), timeColor(from.value - now), 3f, false)
		}

		if (lockHelper.value) renderLock(poseStack, collector, orientation)
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

	/** SkyHanni's Powder Chest Timer display: the soonest to go, and how many there are. */
	private class ChestTimerElement : HudElement("treasure_chest_timer", "Treasure Chests", 0.45, 0.35) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int = 110
		override val height: Int = 10

		override fun isVisible(): Boolean =
			module.enabled && treasureChests.value && SkyblockLocation.inCrystalHollows && chests.isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled && treasureChests.value

		override fun render(context: GuiGraphicsExtractor) {
			val soonest = chests.values.minOrNull() ?: return
			draw(context, soonest - System.currentTimeMillis(), chests.size)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 42_000, 3)

		private fun draw(context: GuiGraphicsExtractor, msLeft: Long, count: Int) {
			val text = Component.literal(secondsLeft(msLeft)).withColor(timeColor(msLeft) and 0xFFFFFF)
				.append(Component.literal(" §8(§e$count §b${if (count == 1) "chest" else "chests"}§8)"))
			context.text(font, text, 0, 1, 0xFFFFFFFF.toInt())
		}
	}
}
