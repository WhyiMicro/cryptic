package imicro.cryptic.feature

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import imicro.cryptic.debug.DebugOverrides
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
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.Locale

/**
 * Waypoints you place in a dungeon boss room.
 *
 * Dungeon Waypoints' editor for the one place it cannot reach: the boss rooms
 * are not rooms on the map, they have no rotation to work out, and every run of
 * a floor fights in the same spot — so a boss waypoint is a plain world
 * position, kept per floor ("F7", "M7"). Turn on Edit mode, right-click a block
 * to place or remove one, sneak-right-click to title it.
 *
 * Each waypoint keeps its own look: outline, fill or both, drawn through walls
 * or not, in its own colour — whatever the editor was set to when it was put
 * down — because a boss room wants a stack spot to read differently from a
 * pre-dev ledge. Packs work as Dungeon Waypoints' do, from `/cryptic bwp`.
 */
object BossWaypoints {
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1
	private const val STYLE_BOTH = 2
	private val STYLE_NAMES = listOf("Outline", "Fill", "Fill + outline")

	/** One waypoint: a world position, how it is drawn, and its colour as ARGB. */
	data class BossWaypoint(
		val x: Int,
		val y: Int,
		val z: Int,
		val style: Int = STYLE_BOTH,
		val phase: Boolean = false,
		val color: Int = 0x6000FFFF,
		val title: String? = null,
		val size: Double? = null,
	) {
		val pos: BlockPos get() = BlockPos(x, y, z)
	}

	/** The packs, by floor. */
	object Packs : PackStore<BossWaypoint>("Boss Waypoint Packs", "boss-waypoint-packs.json", BossWaypoint::class.java, "My Boss Waypoints") {
		override fun toJson(pack: Pack<BossWaypoint>): JsonElement {
			val root = JsonObject()
			pack.rooms.filterValues { it.isNotEmpty() }.forEach { (floor, list) ->
				root.add(floor, JsonArray().apply { list.forEach { add(gson.toJsonTree(it)) } })
			}
			root.addProperty("crypticBossWaypoints", 1)
			return root
		}

		override fun fromJson(root: JsonObject): Map<String, List<BossWaypoint>>? {
			if (!root.has("crypticBossWaypoints")) return null
			return root.entrySet().filter { it.value.isJsonArray }.associate { (floor, list) ->
				floor to list.asJsonArray.map { gson.fromJson(it, BossWaypoint::class.java) }
			}
		}
	}

	// ---- Settings --------------------------------------------------------

	private val drawingSection = SectionModuleSetting("drawing_section", "Drawing")

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Line width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
	)

	@JvmField
	val labelScale = SliderModuleSetting(
		id = "label_scale",
		label = "Title size",
		defaultValue = 1.0,
		min = 0.5,
		max = 3.0,
		step = 0.1,
	)

	private val editorSection = SectionModuleSetting("editor_section", "Editor")

	@JvmField
	val editMode = ToggleModuleSetting(
		id = "edit_mode",
		label = "Edit mode",
		defaultValue = false,
		description = "In a boss room, right-click a block to place or remove a waypoint, sneak-right-click to " +
			"title it. Clicks do nothing else while it is on. Also /cryptic bwp edit.",
	)

	@JvmField
	val editKey = KeybindModuleSetting(
		id = "edit_key",
		label = "Edit mode key",
		description = "Turns Edit mode on and off while playing.",
	)

	@JvmField
	val placeStyle = DropdownModuleSetting(
		id = "place_style",
		label = "Style",
		options = STYLE_NAMES,
		defaultIndex = STYLE_BOTH,
		description = "How the next waypoint is drawn.",
	)

	@JvmField
	val placePhase = ToggleModuleSetting(
		id = "place_phase",
		label = "Phase",
		defaultValue = false,
		description = "Draws the next waypoint through walls.",
	)

	@JvmField
	val placeColor = ColorModuleSetting(
		id = "place_color",
		label = "Color",
		defaultRgb = 0x00FFFF,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		description = "The next waypoint's colour. Its opacity is the fill's; the outline is always solid.",
	)

	@JvmField
	val useBlockSize = ToggleModuleSetting(
		id = "use_block_size",
		label = "Use block size",
		defaultValue = true,
		description = "Fits the box to the block clicked. Off, every new box is the size below.",
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
		id = "boss_waypoints",
		name = "Boss Waypoints",
		description = "Your own waypoints in boss rooms. /cryptic bwp",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			drawingSection, lineWidth, labelScale,
			editorSection, editMode, editKey, placeStyle, placePhase, placeColor, useBlockSize, size,
		),
	)

	private var openMenu = false
	private var editKeyDown = false
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Packs.load()
		Hud.register(EditorElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	/** True while the floor and the boss have to be read for this module. */
	val needsDungeon: Boolean get() = module.enabled

	/** "F7", "M7", "E" for the entrance: the floor whose boss you are fighting, or null outside one. */
	private fun floorKey(): String? {
		if (!module.enabled || !DungeonLocation.inDungeon || !DungeonRun.inBoss) return null
		val floor = DungeonLocation.floor
		if (floor == 0) return "E"
		return (if (DungeonLocation.masterMode) "M" else "F") + floor
	}

	// ---- Drawing ---------------------------------------------------------

	private fun render(context: LevelRenderContext) {
		val key = floorKey() ?: return
		val waypoints = Packs.waypointsIn(key).map { it.second }.toList()
		// Whole blocks drawn alike, by where they are, so a run of them can be
		// filled as one shape instead of as boxes whose shared sides show.
		val blocks = waypoints.filter { isWholeBlock(it) }.associateBy { it.pos }
		for (waypoint in waypoints) {
			val faces = if (isWholeBlock(waypoint)) openFaces(waypoint, blocks) else WorldRender.ALL_FACES
			draw(context, boxAt(waypoint.pos, waypoint.size), waypoint.style, waypoint.phase, waypoint.color, waypoint.title, faces = faces)
		}
		if (editMode.value) {
			val target = target() ?: return
			draw(context, boxAt(target, if (useBlockSize.value) null else size.value), placeStyle.selectedIndex, placePhase.value, placeColor.argb, null, preview = true)
		}
	}

	private fun boxAt(pos: BlockPos, size: Double?): AABB {
		if (size != null) return AABB(pos).inflate((size - 1.0) / 2.0)
		val level = Minecraft.getInstance().level ?: return AABB(pos)
		val shape = level.getBlockState(pos).getShape(level, pos)
		return if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
	}

	/** True for a waypoint that fills exactly its block, which is the kind that can join its neighbours. */
	private fun isWholeBlock(waypoint: BossWaypoint): Boolean {
		if (waypoint.size != null) return false
		return boxAt(waypoint.pos, null) == AABB(waypoint.pos)
	}

	/**
	 * The sides of [waypoint] to fill: all but those against another whole-block
	 * waypoint drawn the same way, whose shared wall would otherwise be drawn
	 * twice and show through the fill as a line.
	 */
	private fun openFaces(waypoint: BossWaypoint, blocks: Map<BlockPos, BossWaypoint>): Int {
		var faces = WorldRender.ALL_FACES
		fun closeIf(dx: Int, dy: Int, dz: Int, face: Int) {
			val other = blocks[waypoint.pos.offset(dx, dy, dz)] ?: return
			if (other.color == waypoint.color && other.phase == waypoint.phase && other.style != STYLE_OUTLINE) faces = faces and face.inv()
		}
		closeIf(0, -1, 0, WorldRender.FACE_DOWN)
		closeIf(0, 1, 0, WorldRender.FACE_UP)
		closeIf(0, 0, -1, WorldRender.FACE_NORTH)
		closeIf(0, 0, 1, WorldRender.FACE_SOUTH)
		closeIf(-1, 0, 0, WorldRender.FACE_WEST)
		closeIf(1, 0, 0, WorldRender.FACE_EAST)
		return faces
	}

	private fun draw(
		context: LevelRenderContext,
		box: AABB,
		style: Int,
		phase: Boolean,
		argb: Int,
		title: String?,
		preview: Boolean = false,
		faces: Int = WorldRender.ALL_FACES,
	) {
		val rgb = argb and 0xFFFFFF
		val fillAlpha = (argb ushr 24) and 0xFF
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = box.minX,
			minY = box.minY,
			minZ = box.minZ,
			maxX = box.maxX,
			maxY = box.maxY,
			maxZ = box.maxZ,
			outlineArgb = ((if (preview) 0x80 else 0xFF) shl 24) or rgb,
			fillArgb = ((if (preview) fillAlpha / 2 else fillAlpha) shl 24) or rgb,
			outline = style != STYLE_FILL,
			fill = style != STYLE_OUTLINE,
			phase = phase,
			lineWidth = lineWidth.value.toFloat(),
			faces = faces,
		)
		if (title == null) return
		WorldRender.drawText(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			orientation = Minecraft.getInstance().gameRenderer.mainCamera().rotation(),
			text = Component.literal(title).withColor(rgb),
			x = (box.minX + box.maxX) / 2,
			y = box.maxY + 0.2,
			z = (box.minZ + box.maxZ) / 2,
			scale = labelScale.value.toFloat(),
			seeThrough = phase,
		)
	}

	// ---- Editing ---------------------------------------------------------

	private fun target(): BlockPos? {
		floorKey() ?: return null
		val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return null
		if (hit.type != HitResult.Type.BLOCK) return null
		return hit.blockPos
	}

	/** A right click while Edit mode is on, in a boss room. True swallows the click. */
	@JvmStatic
	fun onEditorClick(pos: BlockPos): Boolean {
		if (!module.enabled || !editMode.value) return false
		val key = floorKey() ?: return false
		val list = Packs.editList(key)
		val existing = list.firstOrNull { it.pos == pos }
		val client = Minecraft.getInstance()

		if (existing == null) {
			val owner = Packs.waypointsIn(key).firstOrNull { (pack, waypoint) -> waypoint.pos == pos && pack !== Packs.editPack }?.first
			if (owner != null) {
				note("That waypoint is in the pack §f${owner.name}§7. Edit that pack in /cryptic bwp to change it.")
				return true
			}
		}

		if (client.player?.isShiftKeyDown == true) {
			client.execute {
				client.gui.setScreen(WaypointTitleScreen(existing?.title ?: "") { title ->
					list.removeIf { it.pos == pos }
					list += (existing ?: newWaypoint(pos)).copy(title = title.ifBlank { null })
					Packs.save()
				})
			}
			return true
		}

		if (existing != null) {
			list.remove(existing)
			note("Removed a boss waypoint.")
		} else {
			list += newWaypoint(pos)
			note("Placed a ${STYLE_NAMES[placeStyle.selectedIndex].lowercase()}${if (placePhase.value) ", phased" else ""} boss waypoint.")
		}
		Packs.save()
		return true
	}

	private fun newWaypoint(pos: BlockPos) = BossWaypoint(
		x = pos.x,
		y = pos.y,
		z = pos.z,
		style = placeStyle.selectedIndex,
		phase = placePhase.value,
		color = placeColor.argb,
		size = if (useBlockSize.value) null else size.value,
	)

	private fun note(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$text"))
	}

	private fun toggleEditMode() {
		editMode.value = !editMode.value
		note(
			if (editMode.value) {
				"Boss edit mode §aon§7, into §f${Packs.editName ?: "a new pack"}§7: right-click to place or remove."
			} else {
				"Boss edit mode §coff§7."
			},
		)
	}

	/** Opens the pack manager when asked, and watches the Edit mode key. */
	fun tick(client: Minecraft) {
		if (openMenu) {
			openMenu = false
			client.gui.setScreen(PackScreen(Packs))
		}
		if (!module.enabled || !editKey.bound) return
		val down = client.gui.screen() == null && InputConstants.isKeyDown(client.window, editKey.keyCode)
		if (down && !editKeyDown) toggleEditMode()
		editKeyDown = down
	}

	/** What Edit mode is about to place. */
	private class EditorElement : HudElement("boss_waypoint_editor", "Boss Waypoint Editor", 0.40, 0.74) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("Editing boss waypoints | Fill + outline, phase, block size")
		override val height: Int get() = font.lineHeight * 2

		override fun isVisible(): Boolean = module.enabled && editMode.value && (DebugOverrides.sampleHudValues || floorKey() != null)

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) {
			val title = "Editing boss waypoints | ${floorKey() ?: "F7"}"
			val text = buildString {
				append(STYLE_NAMES[placeStyle.selectedIndex])
				if (placePhase.value) append(", phase")
				append(", ")
				append(if (useBlockSize.value) "block size" else String.format(Locale.ROOT, "size %.2f", size.value))
			}
			context.text(font, title, (width - font.width(title)) / 2, 0, 0xFFFFFFFF.toInt())
			context.text(font, text, (width - font.width(text)) / 2, font.lineHeight, 0xFF000000.toInt() or placeColor.rgb)
		}
	}

	// ---- /cryptic bwp ----------------------------------------------------

	private fun feedback(context: CommandContext<FabricClientCommandSource>, text: String) {
		context.source.sendFeedback(Component.literal("§8[Cryptic] §7$text"))
	}

	fun command(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("bwp")
			.executes {
				openMenu = true
				1
			}
			.then(ClientCommands.literal("info").executes { context ->
				val key = floorKey()
				feedback(
					context,
					"Boss Waypoints ${if (module.enabled) "§aon" else "§coff"}§7, edit mode " +
						"${if (editMode.value) "§aon" else "§coff"}§7 into §f${Packs.editName ?: "no pack yet"}§7. " +
						"Boss: §f${key ?: "not in one"}§7, waypoints here §f${key?.let { Packs.waypointsIn(it).count() } ?: 0}§7.",
				)
				1
			})
			.then(ClientCommands.literal("edit").executes {
				toggleEditMode()
				1
			})
			.then(ClientCommands.literal("style").then(
				ClientCommands.argument("style", StringArgumentType.word())
					.suggests { _, builder ->
						listOf("outline", "fill", "both").forEach(builder::suggest)
						builder.buildFuture()
					}
					.executes { context ->
						val index = when (StringArgumentType.getString(context, "style").lowercase(Locale.ROOT)) {
							"outline" -> STYLE_OUTLINE
							"fill" -> STYLE_FILL
							"both" -> STYLE_BOTH
							else -> {
								feedback(context, "§coutline, fill or both.")
								return@executes 0
							}
						}
						placeStyle.selectedIndex = index
						feedback(context, "New boss waypoints are §f${STYLE_NAMES[index]}§7.")
						1
					},
			))
			.then(ClientCommands.literal("phase").executes { context ->
				placePhase.value = !placePhase.value
				feedback(context, "New boss waypoints ${if (placePhase.value) "§aare" else "§care not"}§7 drawn through walls.")
				1
			})
			.then(ClientCommands.literal("color").then(
				ClientCommands.argument("hex", StringArgumentType.word()).executes { context ->
					if (!placeColor.setHex(StringArgumentType.getString(context, "hex"))) {
						feedback(context, "§cUse AARRGGBB, like 6000FFFF.")
						return@executes 0
					}
					feedback(context, "New boss waypoints are §f${placeColor.hex}§7.")
					1
				},
			))
			.then(ClientCommands.literal("title").then(
				ClientCommands.argument("text", StringArgumentType.greedyString()).executes { context ->
					val key = floorKey()
					val target = target()
					val list = key?.let { Packs.editPack?.rooms?.get(it) }
					val existing = list?.firstOrNull { it.pos == target }
					if (list == null || existing == null) {
						feedback(context, "§cLook at one of the edited pack's boss waypoints first.")
						return@executes 0
					}
					list[list.indexOf(existing)] = existing.copy(title = StringArgumentType.getString(context, "text"))
					Packs.save()
					feedback(context, "Titled it.")
					1
				},
			))
			.then(ClientCommands.literal("clear").executes { context ->
				val key = floorKey()
				val pack = Packs.editPack
				val removed = if (key != null && pack != null) pack.rooms.remove(key)?.size ?: 0 else 0
				Packs.save()
				feedback(context, if (key == null) "§cNot in a boss room." else "Removed $removed waypoint(s) from $key in ${pack?.name}.")
				1
			})
}
