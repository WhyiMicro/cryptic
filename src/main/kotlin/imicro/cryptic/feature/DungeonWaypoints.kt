package imicro.cryptic.feature

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * Boxes in each dungeon room: where its secrets are, and wherever you put one.
 *
 * Two halves, from two mods.
 *
 * **Secret waypoints** are Devonian's (GPL-3.0, Copyright (c) Synnerz; licence
 * in `licenses/Devonian-LICENSE.txt`): its table of every chest, item, wither
 * essence, bat spot, redstone key and lever in every room, written in the
 * room's own coordinates and turned into the world's once the room's rotation
 * is known. Each kind has its own colour and its own switch, and a secret
 * stops being drawn once it has been taken.
 *
 * **Custom waypoints** are Odin's Dungeon Waypoints (BSD 3-Clause, Copyright
 * (c) 2025 odtheking, by Bonsai): turn on Edit mode, right-click a block in a
 * room to put a waypoint there and again to take it away, sneak-right-click to
 * give it a title. They are kept per room, so one placed in "Atlas" shows in
 * every Atlas whichever way it is turned. Four kinds: plain, secret (hidden
 * once taken), etherwarp (hidden once you warp onto it) and Dungeon Breaker
 * (hidden once the block is broken). `/cryptic dwp` is Odin's `/dwp`, and its
 * import reads waypoint strings exported from Odin.
 *
 * Custom waypoints come in packs, managed by [WaypointPacks]; Devonian's spots
 * are the read-only Default pack beside them.
 *
 * One style for most things, outline, fill or both, so the room reads as one
 * layer. Two kinds are drawn their own way: an etherwarp spot is a fill only,
 * so no outline sits on the block the warp is being aimed at, and etherwarp
 * and Dungeon Breaker spots are never drawn through walls.
 */
object DungeonWaypoints {
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	/** How far a picked-up item, or a dying bat, may be from its spot and still be it. */
	private const val ITEM_RANGE = 6.0
	private const val BAT_RANGE = 10.0

	/** One bat is heard dying twice, by its sound and by its death; this keeps it one bat. */
	private const val BAT_REPEAT_MILLIS = 400L

	/** How soon "That chest is locked!" has to follow a click to undo it. */
	private const val LOCKED_MILLIS = 1_500L

	private val FORMATTING = Regex("§.")

	/** Devonian's six kinds, by the keys its data uses. */
	enum class SecretType(val key: String, val label: String, val defaultRgb: Int) {
		CHEST("chest", "Chests", 0x00FF00),
		ITEM("item", "Items", 0x5555FF),
		ESSENCE("essence", "Wither essence", 0xFF00FF),
		BAT("bat", "Bats", 0x00FF96),
		REDSTONE("redstone", "Redstone key", 0xFF0000),
		LEVER("lever", "Levers", 0x0096FF);

		val show = ToggleModuleSetting(id = "secret_$key", label = label, defaultValue = true)

		val color = ColorModuleSetting(
			id = "secret_${key}_color",
			label = "$label color",
			defaultRgb = defaultRgb,
			visibleIf = { show.value },
			inlineWith = show,
		)
	}

	/** The kinds of waypoint a player can place, with Odin's three and Dungeon Breaker. */
	enum class CustomType(val label: String, val defaultRgb: Int) {
		NORMAL("Normal", 0xFF5555),
		SECRET("Secret", 0x5555FF),
		ETHERWARP("Etherwarp", 0xFFAA00),
		BREAKER("Dungeon Breaker", 0xAA00AA);

		val color = ColorModuleSetting(
			id = "custom_${name.lowercase(Locale.ROOT)}_color",
			label = label,
			defaultRgb = defaultRgb,
		)

		companion object {
			fun parse(text: String): CustomType? = entries.firstOrNull {
				it.name.equals(text, ignoreCase = true) || it.label.replace(" ", "").equals(text, ignoreCase = true)
			}
		}
	}

	// ---- Settings --------------------------------------------------------

	private val styleSection = SectionModuleSetting("style_section", "Style")

	@JvmField
	val style = DropdownModuleSetting(
		id = "style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + outline"),
		defaultIndex = 2,
		description = "How every waypoint is drawn, secret and custom alike.",
	)

	@JvmField
	val fillOpacity = SliderModuleSetting(
		id = "fill_opacity",
		label = "Fill opacity",
		defaultValue = 30.0,
		min = 5.0,
		max = 100.0,
		step = 5.0,
		visibleIf = { style.selectedIndex != STYLE_OUTLINE },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Line width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { style.selectedIndex != STYLE_FILL },
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Through walls",
		defaultValue = true,
	)

	@JvmField
	val labels = ToggleModuleSetting(
		id = "labels",
		label = "Labels",
		defaultValue = false,
		description = "Writes each waypoint's kind above it, and a custom one's title always.",
	)

	@JvmField
	val labelScale = SliderModuleSetting(
		id = "label_scale",
		label = "Label size",
		defaultValue = 1.0,
		min = 0.5,
		max = 3.0,
		step = 0.1,
	)

	private val secretSection = SectionModuleSetting("secret_section", "Secret waypoints")

	@JvmField
	val secrets = ToggleModuleSetting(
		id = "secrets",
		label = "Secret waypoints",
		defaultValue = true,
		description = "Devonian's spots for every secret in the room you are in.",
	)

	@JvmField
	val hideFound = ToggleModuleSetting(
		id = "hide_found",
		label = "Hide found secrets",
		defaultValue = true,
		description = "Takes a secret's box away once it has been clicked, picked up or killed.",
	)

	private val customSection = SectionModuleSetting("custom_section", "Custom waypoints")

	@JvmField
	val custom = ToggleModuleSetting(
		id = "custom",
		label = "Custom waypoints",
		defaultValue = true,
		description = "The ones you place yourself in Edit mode, or import.",
	)

	private val editorSection = SectionModuleSetting("editor_section", "Editor", startsCollapsed = true)

	@JvmField
	val editMode = ToggleModuleSetting(
		id = "edit_mode",
		label = "Edit mode",
		defaultValue = false,
		description = "Right-click a block to place or remove a waypoint, sneak-right-click to title it. " +
			"Clicks do nothing else while it is on. Also /cryptic dwp edit.",
	)

	@JvmField
	val editKey = KeybindModuleSetting(
		id = "edit_key",
		label = "Edit mode key",
		description = "Turns Edit mode on and off while playing.",
	)

	@JvmField
	val placeType = DropdownModuleSetting(
		id = "place_type",
		label = "Placing",
		options = CustomType.entries.map { it.label },
		defaultIndex = 0,
		description = "The kind of waypoint the next click places.",
	)

	@JvmField
	val useBlockSize = ToggleModuleSetting(
		id = "use_block_size",
		label = "Use block size",
		defaultValue = true,
		description = "Fits the box to the block clicked, a slab as a slab. Off, every box is the size below.",
	)

	@JvmField
	val size = SliderModuleSetting(
		id = "size",
		label = "Size",
		defaultValue = 1.0,
		min = 0.1,
		max = 3.0,
		step = 0.05,
		visibleIf = { !useBlockSize.value },
	)

	@JvmField
	val module = Module(
		id = "dungeon_waypoints",
		name = "Dungeon Waypoints",
		description = "Secret spots in every room, and your own. /cryptic dwp",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(styleSection, style, fillOpacity, lineWidth, throughWalls, labels, labelScale, secretSection, secrets, hideFound) +
			SecretType.entries.flatMap { listOf(it.show, it.color) } +
			listOf(customSection, custom) + CustomType.entries.map { it.color } +
			listOf(editorSection, editMode, editKey, placeType, useBlockSize, size),
	)

	// ---- Data ------------------------------------------------------------

	/** Devonian's data: room name, then kind, then positions in the room's coordinates. */
	private val secretData: Map<String, Map<SecretType, List<BlockPos>>> by lazy { loadSecretData() }

	/** A waypoint you placed. Position in the room's own coordinates; [size] null is the block's own. */
	data class Custom(val x: Int, val y: Int, val z: Int, val type: String, val title: String? = null, val size: Double? = null) {
		val pos: BlockPos get() = BlockPos(x, y, z)
		val kind: CustomType get() = runCatching { CustomType.valueOf(type) }.getOrDefault(CustomType.NORMAL)
	}

	/** How many spots the Default pack holds, for the pack manager. */
	val defaultCount: Int get() = secretData.values.sumOf { kinds -> kinds.values.sumOf { it.size } }

	/** Secrets taken this run, as world positions. */
	private val found = HashSet<BlockPos>()

	private var lastChest: BlockPos? = null
	private var lastChestAt = 0L

	private var lastBatAt = 0L
	private var lastBatPos: BlockPos? = null

	/** Set by `/cryptic dwp`, and acted on next tick once the chat has closed. */
	private var openMenu = false
	private var editKeyDown = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		WaypointPacks.load()
		Hud.register(EditorElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> found.clear() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> found.clear() }
	}

	private fun loadSecretData(): Map<String, Map<SecretType, List<BlockPos>>> = runCatching {
		val stream = DungeonWaypoints::class.java.getResourceAsStream("/assets/cryptic/dungeon/secret_waypoints.json")
			?: return emptyMap()
		val root = JsonParser.parseString(stream.bufferedReader().use { it.readText() }).asJsonObject
		root.entrySet().associate { (room, kinds) ->
			room to kinds.asJsonObject.entrySet().mapNotNull { (key, list) ->
				val type = SecretType.entries.firstOrNull { it.key == key } ?: return@mapNotNull null
				type to list.asJsonArray.map { point ->
					val xyz = point.asJsonArray
					BlockPos(xyz[0].asInt, xyz[1].asInt, xyz[2].asInt)
				}
			}.toMap()
		}
	}.onFailure { Cryptic.LOGGER.error("Could not read the secret waypoints", it) }.getOrDefault(emptyMap())

	private fun saveCustoms() = WaypointPacks.save()

	// ---- Where -----------------------------------------------------------

	/** True while the floor and the room have to be read for this module. */
	val needsDungeon: Boolean get() = module.enabled

	/** The room being stood in, once it is known which way it is turned. */
	private fun room(): DungeonRoom? {
		if (!module.enabled || !DungeonLocation.inDungeon || DungeonRun.inBoss) return null
		val level = Minecraft.getInstance().level ?: return null
		val room = DungeonMap.currentRoom() ?: return null
		if (room.data == null || !room.resolveRotation(level)) return null
		return room
	}

	private fun secretsIn(room: DungeonRoom): Sequence<Pair<SecretType, BlockPos>> {
		val data = secretData[room.data?.name ?: return emptySequence()] ?: return emptySequence()
		return data.asSequence().flatMap { (type, list) ->
			list.asSequence().mapNotNull { rel -> room.getRealCoords(rel)?.let { type to it } }
		}
	}

	/** Every custom waypoint in the room from the packs switched on, placed in the world. */
	private fun customsIn(room: DungeonRoom): Sequence<Pair<Custom, BlockPos>> {
		val name = room.data?.name ?: return emptySequence()
		return WaypointPacks.waypointsIn(name).mapNotNull { (_, custom) -> room.getRealCoords(custom.pos)?.let { custom to it } }
	}

	/**
	 * The room at a world position, once its turn is known, for a secret taken
	 * in a room you are not standing in: a bat flies, and dies where it likes.
	 */
	private fun roomAt(x: Double, z: Double): DungeonRoom? {
		val level = Minecraft.getInstance().level ?: return null
		val room = DungeonFloor.roomAt(DungeonFloor.tileOf(x, z)) ?: return null
		if (room.data == null || !room.resolveRotation(level)) return null
		return room
	}

	// ---- Secrets being taken ---------------------------------------------

	/** A block right-clicked: a chest, a skull, a lever. */
	@JvmStatic
	fun onBlockUsed(pos: BlockPos) {
		val room = room() ?: return
		val match = secretsIn(room).any { (type, at) -> at == pos && type != SecretType.ITEM && type != SecretType.BAT } ||
			customsIn(room).any { (custom, at) -> at == pos && custom.kind == CustomType.SECRET }
		if (!match) return
		found += pos
		lastChest = pos
		lastChestAt = System.currentTimeMillis()
	}

	/** A secret item picked up, wherever it had rolled to. */
	@JvmStatic
	fun onItemPickedUp(pos: BlockPos) = takeNearest(pos, ITEM_RANGE, SecretType.ITEM)

	/**
	 * A bat dying, which is a bat secret taken: from its death sound, or from the
	 * death itself. Both arrive for one bat, so a second within a moment and a
	 * few blocks of the first is the same bat. Only the client thread's pass.
	 *
	 * Bats fly, so the nearest bat spot within ten blocks is taken, in the
	 * room the bat died in as well as the one you are standing in.
	 */
	@JvmStatic
	fun onBatDied(x: Double, y: Double, z: Double) {
		if (!Minecraft.getInstance().isSameThread) return
		val pos = BlockPos.containing(x, y, z)
		val now = System.currentTimeMillis()
		val last = lastBatPos
		if (last != null && now - lastBatAt < BAT_REPEAT_MILLIS && last.distSqr(pos) < 25.0) return
		lastBatAt = now
		lastBatPos = pos
		takeNearest(pos, BAT_RANGE, SecretType.BAT, roomAt(x, z))
	}

	private fun takeNearest(pos: BlockPos, range: Double, type: SecretType, other: DungeonRoom? = null) {
		val rooms = listOfNotNull(room(), other).distinct()
		if (rooms.isEmpty()) return
		val candidates = rooms.asSequence().flatMap { room ->
			secretsIn(room).filter { it.first == type }.map { it.second } +
				customsIn(room).filter { it.first.kind == CustomType.SECRET }.map { it.second }
		}
		candidates
			.filter { it !in found && it.distSqr(pos) <= range * range }
			.minByOrNull { it.distSqr(pos) }
			?.let { found += it }
	}

	/** After a server teleport: an etherwarp waypoint you are now standing on is done with. */
	@JvmStatic
	fun onTeleport() {
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		val room = room() ?: return
		val player = client.player ?: return
		val feet = player.blockPosition()
		customsIn(room)
			.filter { (custom, at) -> custom.kind == CustomType.ETHERWARP && (at == feet.below() || at == feet) }
			.forEach { found += it.second }
	}

	/** A chat packet, on the client thread. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		when (message.string.replace(FORMATTING, "").trim()) {
			"That chest is locked!" -> {
				// The click opened nothing, so the chest is still to do.
				if (System.currentTimeMillis() - lastChestAt < LOCKED_MILLIS) lastChest?.let { found -= it }
				lastChest = null
			}
			"You found a Secret Redstone Key!" -> {
				val room = room() ?: return
				secretsIn(room).filter { it.first == SecretType.REDSTONE }.forEach { found += it.second }
			}
		}
	}

	// ---- Drawing ---------------------------------------------------------

	private fun render(context: LevelRenderContext) {
		val room = room() ?: return
		val client = Minecraft.getInstance()
		val level = client.level ?: return

		if (secrets.value) {
			for ((type, at) in secretsIn(room)) {
				if (!type.show.value) continue
				if (hideFound.value && at in found) continue
				draw(context, AABB(at), type.color.rgb, if (labels.value) type.label.removeSuffix("s") else null)
			}
		}

		if (custom.value) {
			for ((waypoint, at) in customsIn(room)) {
				val kind = waypoint.kind
				when (kind) {
					CustomType.SECRET, CustomType.ETHERWARP -> if (at in found) continue
					CustomType.BREAKER -> if (level.getBlockState(at).isAir) continue
					CustomType.NORMAL -> Unit
				}
				val label = waypoint.title ?: if (labels.value) kind.label else null
				// Etherwarp spots are a fill and nothing else, whatever the style:
				// an outline on the block you are aiming the warp at is in the way
				// of the crosshair. Etherwarp and breaker spots are never drawn
				// through walls, since the block you can actually see is the one
				// that counts for both.
				val fillOnly = kind == CustomType.ETHERWARP
				val depthTested = kind == CustomType.ETHERWARP || kind == CustomType.BREAKER
				draw(context, boxAt(at, waypoint.size), kind.color.rgb, label, fillOnly = fillOnly, phase = !depthTested && throughWalls.value)
			}
		}

		if (editMode.value) {
			val target = target() ?: return
			draw(context, boxAt(target, if (useBlockSize.value) null else size.value), placing().color.rgb, null, preview = true)
		}
	}

	/** The block's own outline, or a cube of [size] centred on it. */
	private fun boxAt(pos: BlockPos, size: Double?): AABB {
		if (size != null) return AABB(pos).inflate((size - 1.0) / 2.0)
		val level = Minecraft.getInstance().level ?: return AABB(pos)
		val shape = level.getBlockState(pos).getShape(level, pos)
		return if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
	}

	private fun draw(
		context: LevelRenderContext,
		box: AABB,
		rgb: Int,
		label: String?,
		preview: Boolean = false,
		fillOnly: Boolean = false,
		phase: Boolean = throughWalls.value,
	) {
		val mode = if (fillOnly) STYLE_FILL else style.selectedIndex
		val opacity = (fillOpacity.value / 100.0 * 255).toInt().coerceIn(0, 255)
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = box.minX,
			minY = box.minY,
			minZ = box.minZ,
			maxX = box.maxX,
			maxY = box.maxY,
			maxZ = box.maxZ,
			outlineArgb = (if (preview) 0x80 shl 24 else 0xFF shl 24) or (rgb and 0xFFFFFF),
			fillArgb = ((if (preview) opacity / 2 else opacity) shl 24) or (rgb and 0xFFFFFF),
			outline = mode != STYLE_FILL,
			fill = mode != STYLE_OUTLINE,
			phase = phase,
			lineWidth = lineWidth.value.toFloat(),
		)
		if (label == null) return
		val client = Minecraft.getInstance()
		WorldRender.drawText(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			orientation = client.gameRenderer.mainCamera().rotation(),
			text = Component.literal(label).withColor(rgb and 0xFFFFFF),
			x = (box.minX + box.maxX) / 2,
			y = box.maxY + 0.2,
			z = (box.minZ + box.maxZ) / 2,
			scale = labelScale.value.toFloat(),
			seeThrough = phase,
		)
	}

	// ---- Editing ---------------------------------------------------------

	private fun placing(): CustomType = CustomType.entries[placeType.selectedIndex.coerceIn(0, CustomType.entries.size - 1)]

	/** The block under the crosshair, while there is a room to put it in. */
	private fun target(): BlockPos? {
		val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return null
		if (hit.type != HitResult.Type.BLOCK) return null
		room() ?: return null
		return hit.blockPos
	}

	/**
	 * A right click on a block while Edit mode is on: places a waypoint, takes
	 * one away, or — sneaking — asks for a title. True swallows the click, so
	 * the chest or lever under it is not touched while editing.
	 */
	@JvmStatic
	fun onEditorClick(pos: BlockPos): Boolean {
		if (!module.enabled || !editMode.value) return false
		val room = room() ?: return false
		val name = room.data?.name ?: return false
		val relative = room.getRelativeCoords(pos) ?: return false
		val list = WaypointPacks.editList(name)
		val existing = list.firstOrNull { it.pos == relative }
		val client = Minecraft.getInstance()

		// A waypoint from another pack is that pack's, and is left alone.
		if (existing == null) {
			val owner = WaypointPacks.waypointsIn(name).firstOrNull { (pack, custom) ->
				custom.pos == relative && pack !== WaypointPacks.editPack
			}?.first
			if (owner != null) {
				note("That waypoint is in the pack §f${owner.name}§7. Edit that pack in /cryptic dwp to change it.")
				return true
			}
		}

		if (client.player?.isShiftKeyDown == true) {
			client.execute {
				client.gui.setScreen(WaypointTitleScreen(existing?.title ?: "") { title ->
					list.removeIf { it.pos == relative }
					list += (existing ?: newWaypoint(relative)).copy(title = title.ifBlank { null })
					saveCustoms()
				})
			}
			return true
		}

		if (existing != null) {
			list.remove(existing)
			note("Removed the ${existing.kind.label.lowercase()} waypoint.")
		} else {
			list += newWaypoint(relative)
			note("Placed a ${placing().label.lowercase()} waypoint.")
		}
		saveCustoms()
		return true
	}

	private fun newWaypoint(relative: BlockPos) = Custom(
		x = relative.x,
		y = relative.y,
		z = relative.z,
		type = placing().name,
		size = if (useBlockSize.value) null else size.value,
	)

	private fun note(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$text"))
	}

	/** What Edit mode is about to place, or the waypoint under the crosshair. Odin's editor HUD. */
	private class EditorElement : HudElement("dungeon_waypoint_editor", "Waypoint Editor", 0.40, 0.70) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("Editing waypoints | Etherwarp, block size, through walls")
		override val height: Int get() = font.lineHeight * 2

		override fun isVisible(): Boolean = module.enabled && editMode.value && (DebugOverrides.sampleHudValues || room() != null)

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) {
			val room = room()
			val target = target()
			val hovered = if (room != null && target != null) {
				val relative = room.getRelativeCoords(target)
				val name = room.data?.name
				if (name == null) null else WaypointPacks.waypointsIn(name).map { it.second }.firstOrNull { it.pos == relative }
			} else {
				null
			}
			if (hovered != null) {
				draw(context, "Viewing", describe(hovered.kind, hovered.size, hovered.title), hovered.kind.color.rgb)
			} else {
				draw(context, "Placing", describe(placing(), if (useBlockSize.value) null else size.value, null), placing().color.rgb)
			}
		}

		override fun renderExample(context: GuiGraphicsExtractor) =
			draw(context, "Placing", describe(CustomType.ETHERWARP, null, null), CustomType.ETHERWARP.color.rgb)

		private fun describe(type: CustomType, size: Double?, title: String?): String = buildString {
			append(type.label)
			append(", ")
			append(if (size == null) "block size" else String.format(Locale.ROOT, "size %.2f", size))
			title?.let { append(", \"$it\"") }
		}

		private fun draw(context: GuiGraphicsExtractor, mode: String, text: String, rgb: Int) {
			val title = "Editing waypoints | $mode"
			context.text(font, title, (width - font.width(title)) / 2, 0, 0xFFFFFFFF.toInt())
			context.text(font, text, (width - font.width(text)) / 2, font.lineHeight, 0xFF000000.toInt() or rgb)
		}
	}

	// ---- /cryptic dwp ----------------------------------------------------

	private fun feedback(context: CommandContext<FabricClientCommandSource>, text: String) {
		context.source.sendFeedback(Component.literal("§8[Cryptic] §7$text"))
	}

	fun command(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("dwp")
			.executes {
				// Opened next tick: the chat screen closing after the command would
				// otherwise close this straight after it opened.
				openMenu = true
				1
			}
			.then(ClientCommands.literal("info").executes { context ->
				val room = DungeonMap.currentRoom()
				val name = room?.data?.name
				feedback(
					context,
					"Dungeon Waypoints ${if (module.enabled) "§aon" else "§coff"}§7, edit mode " +
						"${if (editMode.value) "§aon" else "§coff"}§7, placing §f${placing().label}§7 into §f" +
						"${WaypointPacks.editName ?: "no pack yet"}§7. Room: §f${name ?: "none"}§7, rotation §f" +
						"${room?.rotation ?: "unknown"}§7, custom here §f${name?.let { WaypointPacks.waypointsIn(it).count() } ?: 0}§7.",
				)
				1
			})
			.then(ClientCommands.literal("edit").executes { context ->
				toggleEditMode()
				1
			})
			.then(ClientCommands.literal("type").then(
				ClientCommands.argument("kind", StringArgumentType.word())
					.suggests { _, builder ->
						listOf("normal", "secret", "etherwarp", "breaker").forEach(builder::suggest)
						builder.buildFuture()
					}
					.executes { context ->
						val kind = CustomType.parse(StringArgumentType.getString(context, "kind"))
						if (kind == null) {
							feedback(context, "§cNo such kind. §7normal, secret, etherwarp or breaker.")
							return@executes 0
						}
						placeType.selectedIndex = kind.ordinal
						feedback(context, "Placing §f${kind.label}§7 waypoints.")
						1
					},
			))
			.then(ClientCommands.literal("size").then(
				ClientCommands.argument("size", DoubleArgumentType.doubleArg(0.1, 3.0)).executes { context ->
					size.value = DoubleArgumentType.getDouble(context, "size")
					useBlockSize.value = false
					feedback(context, "New waypoints are §f${size.value}§7 blocks.")
					1
				},
			))
			.then(ClientCommands.literal("useblocksize").executes { context ->
				useBlockSize.value = !useBlockSize.value
				feedback(context, "Use block size ${if (useBlockSize.value) "§aon" else "§coff"}§7.")
				1
			})
			.then(ClientCommands.literal("title").then(
				ClientCommands.argument("text", StringArgumentType.greedyString()).executes { context ->
					val room = room()
					val target = target()
					val name = room?.data?.name
					val relative = if (room != null && target != null) room.getRelativeCoords(target) else null
					val list = name?.let { WaypointPacks.editPack?.rooms?.get(it) }
					val existing = list?.firstOrNull { it.pos == relative }
					if (list == null || existing == null) {
						feedback(context, "§cLook at one of the edited pack's waypoints first.")
						return@executes 0
					}
					list[list.indexOf(existing)] = existing.copy(title = StringArgumentType.getString(context, "text"))
					saveCustoms()
					feedback(context, "Titled it.")
					1
				},
			))
			.then(ClientCommands.literal("clear").executes { context ->
				val name = DungeonMap.currentRoom()?.data?.name
				val pack = WaypointPacks.editPack
				val removed = if (name != null && pack != null) pack.rooms.remove(name)?.size ?: 0 else 0
				saveCustoms()
				feedback(context, if (name == null) "§cNot in a known room." else "Removed $removed waypoint(s) from $name in ${pack?.name}.")
				1
			})
			.then(ClientCommands.literal("resetsecrets").executes { context ->
				found.clear()
				feedback(context, "Every secret is shown again.")
				1
			})

	/** Edit mode on or off, from the key or the command, and says which. */
	private fun toggleEditMode() {
		editMode.value = !editMode.value
		note(
			if (editMode.value) {
				"Edit mode §aon§7, into §f${WaypointPacks.editName ?: "a new pack"}§7: right-click to place or remove."
			} else {
				"Edit mode §coff§7."
			},
		)
	}

	/** Opens the pack manager when asked, and watches the Edit mode key. */
	fun tick(client: Minecraft) {
		if (openMenu) {
			openMenu = false
			client.gui.setScreen(PackScreen(WaypointPacks))
		}
		if (!module.enabled || !editKey.bound) return
		val down = client.gui.screen() == null && InputConstants.isKeyDown(client.window, editKey.keyCode)
		if (down && !editKeyDown) toggleEditMode()
		editKeyDown = down
	}

	/** For `/cryptic debug waypoints`: the room, its turn, and what is drawn in it. */
	fun describe(): List<String> {
		val room = DungeonMap.currentRoom()
		val level = Minecraft.getInstance().level
		val resolved = room != null && level != null && room.resolveRotation(level)
		val name = room?.data?.name
		return listOf(
			"§8[Cryptic] §7Room: §f${name ?: "none"}§7, tiles §f${room?.tiles?.size ?: 0}§7, shape §f${room?.shape}§7, " +
				"rotation §f${room?.rotation ?: if (resolved) "?" else "not found"}§7, clay §f${room?.clayPos}",
			"§8[Cryptic] §7Secret spots here: §f${secretData[name]?.values?.sumOf { it.size } ?: 0}§7, " +
				"custom: §f${name?.let { WaypointPacks.waypointsIn(it).count() } ?: 0}§7, found this run: §f${found.size}",
		)
	}
}
