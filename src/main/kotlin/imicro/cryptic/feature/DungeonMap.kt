package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonMapColors
import imicro.cryptic.dungeon.map.DungeonMapReader
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.dungeon.map.DungeonWorldScan
import imicro.cryptic.dungeon.map.RoomPrediction
import imicro.cryptic.dungeon.map.ScoreArt
import imicro.cryptic.dungeon.map.Vec2i
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.PanelModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.util.Mth
import net.minecraft.resources.Identifier
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.Blocks
import kotlin.jvm.optionals.getOrNull
import kotlin.math.roundToInt

/**
 * The dungeon map on the HUD. Fed by the map item ([DungeonMapReader]), which knows progress, and a
 * scan of the world ([DungeonWorldScan]), which knows room names, so the map shows as soon as you
 * walk in. Ported from dtMap, whose check marks and pointer are used.
 */
object DungeonMap {
	private val GREEN_CHECK = Cryptic.id("map/green_check.png")
	private val WHITE_CHECK = Cryptic.id("map/white_check.png")
	private val CROSS = Cryptic.id("map/cross.png")
	private val QUESTION = Cryptic.id("map/question.png")
	private val SELF_MARKER = Cryptic.id("map/self_marker.png")
	private val PRINCE_CROWN = Cryptic.id("map/prince_crown.png")

	/**
	 * How long a head takes to catch up with the map: short enough to stay accurate, long enough
	 * that updates read as movement.
	 */
	private const val EASE_SECONDS = 0.09

	/** Past this, a head was leaped rather than walked, and isn't eased. */
	private const val EASE_SNAP_DISTANCE = 24f

	@JvmField
	val background = ColorModuleSetting(
		id = "background",
		label = "Background",
		defaultRgb = 0x000000,
		supportsAlpha = true,
		defaultAlpha = 70,
	)

	@JvmField
	val padding = SliderModuleSetting(
		id = "padding",
		label = "Padding",
		defaultValue = 5.0,
		min = 0.0,
		max = 20.0,
		step = 1.0,
		description = "How much background sits around the rooms.",
	)

	@JvmField
	val reveal = DropdownModuleSetting(
		id = "reveal",
		label = "Reveal",
		options = listOf("Whole floor", "As you explore"),
		defaultIndex = 1,
		description = "Whether rooms nobody has reached yet are already drawn.",
		// Below Cheat it's always as you explore.
		visibleIf = { imicro.cryptic.gui.Restrictions.allows(imicro.cryptic.gui.Restriction.CHEAT) },
		optionRestrictions = mapOf("Whole floor" to imicro.cryptic.gui.Restriction.CHEAT),
	)

	/** True while the map may show what the world scan knows. */
	val revealAll: Boolean get() = reveal.selectedIndex == 0

	@JvmField
	val predictRooms = ToggleModuleSetting(
		id = "predict_rooms",
		label = "Guess closed rooms",
		defaultValue = true,
		description = "Colours a closed one-tile room with what it can still be.",
		visibleIf = { !revealAll },
	)

	@JvmField
	val doorThickness = SliderModuleSetting(
		id = "door_thickness",
		label = "Door thickness",
		defaultValue = 8.0,
		min = 3.0,
		max = 16.0,
		step = 1.0,
	)

	@JvmField
	val normalColor = ColorModuleSetting("normal_color", "Normal", DungeonMapColors.NORMAL)

	@JvmField
	val bloodColor = ColorModuleSetting("blood_color", "Blood", DungeonMapColors.BLOOD)

	@JvmField
	val entranceColor = ColorModuleSetting("entrance_color", "Entrance", DungeonMapColors.ENTRANCE)

	@JvmField
	val puzzleColor = ColorModuleSetting("puzzle_color", "Puzzle", DungeonMapColors.PUZZLE)

	@JvmField
	val trapColor = ColorModuleSetting("trap_color", "Trap", DungeonMapColors.TRAP)

	@JvmField
	val championColor = ColorModuleSetting("champion_color", "Miniboss", DungeonMapColors.CHAMPION)

	@JvmField
	val fairyColor = ColorModuleSetting("fairy_color", "Fairy", DungeonMapColors.FAIRY)

	@JvmField
	val rareColor = ColorModuleSetting("rare_color", "Rare", DungeonMapColors.RARE)

	@JvmField
	val unexploredColor = ColorModuleSetting(
		"unexplored_color",
		"Undiscovered",
		DungeonMapColors.UNOPENED,
		description = "The grey a room is drawn in until somebody opens it, as dtMap draws it.",
	)

	@JvmField
	val darkenUnexplored = SliderModuleSetting(
		id = "darken_unexplored",
		label = "Dim unexplored",
		defaultValue = 40.0,
		min = 10.0,
		max = 100.0,
		step = 5.0,
		description = "How much colour the blood room, and a closed room's guessed types, keep before anyone has been in them.",
	)

	@JvmField
	val normalDoorColor = ColorModuleSetting("normal_door_color", "Normal", DungeonMapColors.NORMAL)

	@JvmField
	val witherDoorColor = ColorModuleSetting("wither_door_color", "Wither", DungeonMapColors.WITHER_DOOR)

	@JvmField
	val bloodDoorColor = ColorModuleSetting("blood_door_color", "Blood", DungeonMapColors.BLOOD)

	@JvmField
	val entranceDoorColor = ColorModuleSetting("entrance_door_color", "Entrance", DungeonMapColors.ENTRANCE)

	private val marksSection = SectionModuleSetting("marks_section", "Marks")

	@JvmField
	val roomStyle = DropdownModuleSetting(
		id = "room_style",
		label = "Room style",
		options = listOf("Checkmarks", "Secrets", "Room name", "Name + secrets"),
		defaultIndex = 2,
		description = "What each room shows: its mark, its secrets or its name.",
	)

	private val namedStyle: Boolean get() = roomStyle.selectedIndex >= 2

	private val secretStyle: Boolean get() = roomStyle.selectedIndex == 1 || roomStyle.selectedIndex == 3

	@JvmField
	val checkmarkSize = SliderModuleSetting(
		id = "checkmark_size",
		label = "Checkmark size",
		defaultValue = 10.0,
		min = 4.0,
		max = 16.0,
		step = 1.0,
		visibleIf = { roomStyle.selectedIndex == 0 },
	)

	@JvmField
	val nameScale = SliderModuleSetting(
		id = "name_scale",
		label = "Text size",
		defaultValue = 0.45,
		min = 0.2,
		max = 1.0,
		step = 0.05,
		visibleIf = { roomStyle.selectedIndex != 0 },
	)

	@JvmField
	val questionMarks = ToggleModuleSetting(
		id = "question_marks",
		label = "Question marks",
		defaultValue = false,
		description = "Marks rooms that are on the map but still shut.",
	)

	@JvmField
	val princeMarker = ToggleModuleSetting(
		id = "prince_marker",
		label = "Prince marker",
		defaultValue = true,
		description = "Puts a crown in the corner of a room a Prince can still spawn in.",
	)

	@JvmField
	val playerHeads = ToggleModuleSetting(
		id = "player_heads",
		label = "Player markers",
		defaultValue = true,
	)

	@JvmField
	val markerSize = SliderModuleSetting(
		id = "marker_size",
		label = "Marker size",
		defaultValue = 10.0,
		min = 4.0,
		max = 20.0,
		step = 1.0,
		visibleIf = { playerHeads.value },
	)

	@JvmField
	val classOutline = ToggleModuleSetting(
		id = "class_outline",
		label = "Class-coloured outline",
		defaultValue = true,
		description = "Rings each head in its dungeon class's colour, from the Class Colors module.",
		visibleIf = { playerHeads.value },
	)

	@JvmField
	val playerColor = ColorModuleSetting(
		id = "player_color",
		label = "Outline",
		defaultRgb = 0xFFFFFF,
		description = "The ring around a head whose class Cryptic does not know.",
		visibleIf = { playerHeads.value },
		inlineWith = playerHeads,
	)

	@JvmField
	val selfMarker = DropdownModuleSetting(
		id = "self_marker",
		label = "Your marker",
		options = listOf("Your head", "Map pointer"),
		defaultIndex = 1,
		description = "Whether you are drawn as your own head or as the vanilla map arrow.",
		visibleIf = { playerHeads.value },
	)

	@JvmField
	val facingArrow = ToggleModuleSetting(
		id = "facing_arrow",
		label = "Facing arrow",
		defaultValue = true,
		description = "Puts a small triangle over each head pointing where they are looking.",
		visibleIf = { playerHeads.value },
	)

	@JvmField
	val shareSecrets = ToggleModuleSetting(
		id = "share_secrets",
		label = "Share secrets through Odin",
		defaultValue = false,
		description = "Joins the lobby's room on Odin's relay (ws.odtheking.com) as Odin's map does, so teammates' secret counts " +
			"show on your map and yours on theirs, Odin users included. Sends your name and the lobby's server id.",
	)

	@JvmField
	val leapNames = ToggleModuleSetting(
		id = "leap_names",
		label = "Names while leaping",
		defaultValue = true,
		description = "Labels every head while you hold a leap or have its menu open.",
		visibleIf = { playerHeads.value },
	)

	@JvmField
	val leapNameScale = SliderModuleSetting(
		id = "leap_name_scale",
		label = "Name size",
		defaultValue = 0.75,
		min = 0.3,
		max = 1.5,
		step = 0.05,
		visibleIf = { playerHeads.value && leapNames.value },
	)

	/** Listed here rather than read off the module, which is built from it. */
	private val configurableSettings = listOf(
		background,
		padding,
		reveal,
		predictRooms,
		normalColor,
		bloodColor,
		entranceColor,
		puzzleColor,
		trapColor,
		championColor,
		fairyColor,
		rareColor,
		unexploredColor,
		darkenUnexplored,
		doorThickness,
		normalDoorColor,
		witherDoorColor,
		bloodDoorColor,
		entranceDoorColor,
		roomStyle,
		checkmarkSize,
		nameScale,
		questionMarks,
		princeMarker,
		playerHeads,
		markerSize,
		classOutline,
		playerColor,
		selfMarker,
		facingArrow,
		leapNames,
		leapNameScale,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				else -> Unit
			}
		}
		// The score is on this card now, so Reset covers it.
		DungeonScore.resetSettings()
	})

	@JvmField
	val module = Module(
		id = "dungeon_map",
		name = "Dungeon Map",
		description = "The dungeon map on screen",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(
			SectionModuleSetting("map_section", "Map"),
			background,
			padding,
			reveal,
			predictRooms,
			PanelModuleSetting("room_panel", "Room colors", "The colour of each kind of room, and how dim the unexplored ones are.").withMore(
				normalColor, bloodColor, entranceColor, puzzleColor, trapColor,
				championColor, fairyColor, rareColor, unexploredColor, darkenUnexplored,
			),
			PanelModuleSetting("door_panel", "Doors", "How thick doors are drawn, and the colour of each kind.").withMore(
				doorThickness, normalDoorColor, witherDoorColor, bloodDoorColor, entranceDoorColor,
			),
			marksSection,
			roomStyle.withMore(checkmarkSize, nameScale),
			questionMarks,
			princeMarker,
			playerHeads.withMore(markerSize, classOutline, playerColor, selfMarker, facingArrow),
			leapNames.withMore(leapNameScale),
			shareSecrets,
		) + DungeonScore.scoreSettings + listOf(reset),
	)

	private var initialized = false

	/**
	 * Whether the floor needs keeping up to date: the world scan and current room serve
	 * [DoorHighlight] too.
	 */
	/** Every module asking [currentRoom] must be on this list; the current room is worked out only here. */
	private val needed: Boolean
		get() = module.enabled ||
			DoorHighlight.module.enabled ||
			RoomAlerts.module.enabled ||
			BreakerHelper.module.enabled ||
			PuzzleSolver.module.enabled ||
			Secrets.movesCounter ||
			PuzzleHud.needsScan ||
			ILoveGlass.needsDoors ||
			DungeonWaypoints.needsDungeon

	/** The tile the player was last in, so a room is entered once. */
	private var lastTile: Vec2i? = null

	private val LEAP_ITEMS = setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")

	/** Whether a leap is in hand, worked out once a tick (reading the id copies custom data). */
	private var holdingLeap = false

	val element: HudElement = MapElement()

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(element)

		// A chunk arriving is what can give the scan more rooms.
		ClientChunkEvents.CHUNK_LOAD.register { _, _ ->
			if (needed) DungeonWorldScan.requestScan()
		}

		// Each floor is a new server, so the map resets.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onLeaveFloor() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onLeaveFloor() }
	}

	private fun onLeaveFloor() {
		lastTile = null
		lastRoom = null
		DungeonMapReader.reset()
	}

	/** The room the player is in, or null between floors. */
	fun currentRoom(): DungeonRoom? = lastTile?.let(DungeonFloor::roomAt)

	/**
	 * Notes which doorways are open, from the world (the map repaints opened wither doors as
	 * normal). Kept here rather than in [DoorHighlight] because the fairy room's name depends on
	 * its wither door.
	 */
	private fun readOpenDoors(level: net.minecraft.world.level.Level) {
		if (doorCheckCountdown-- > 0) return
		doorCheckCountdown = DOOR_CHECK_INTERVAL_TICKS

		DungeonFloor.doors.forEach { door ->
			if (door.opened) return@forEach
			val block = level.getBlockState(BlockPos(door.worldX, DOORWAY_Y, door.worldZ)).block
			// An empty doorway is open; Hypixel leaves a barrier in the gap while a door opens.
			door.opened = block == Blocks.AIR || block == Blocks.BARRIER
		}
	}

	private const val DOOR_CHECK_INTERVAL_TICKS = 10
	private const val DOORWAY_Y = 69

	private var doorCheckCountdown = 0

	/**
	 * Files the action bar's secret count against the room it was counted in; Hypixel only shows it
	 * while you're in that room. Called by [imicro.cryptic.dungeon.RoomSecrets]. From dtMap.
	 */
	fun onRoomSecretCount(found: Int, total: Int) {
		// Whenever rooms are followed, not just with the map on; the Secrets counter reads this
		// too.
		if (!needed || !DungeonLocation.inDungeon) return
		val room = currentRoom() ?: return
		// For about a second after walking into a room the bar still shows the last one's count;
		// a total that isn't this room's isn't this room's count.
		val known = room.data?.secrets ?: 0
		if (known > 0 && total != known) return
		// Only ever up: Hypixel's count never falls, so a lower one is a stale reading (Odin's rule).
		room.secretsRead = true
		if (found > room.foundSecrets) {
			room.foundSecrets = found
			imicro.cryptic.dungeon.SecretSync.send(room)
		}
	}

	/**
	 * Keeps the floor up to date: reads new chunks and marks the room you walked into without
	 * waiting for the map item.
	 */
	fun tick(client: Minecraft) {
		imicro.cryptic.dungeon.SecretSync.tick()
		if (!needed) return

		val level = client.level ?: return
		if (DungeonLocation.inDungeon) {
			DungeonFloor.assumeSize(DungeonLocation.floor)
			DungeonWorldScan.scan(level)
			readOpenDoors(level)
		}

		val player = client.player ?: return

		holdingLeap = leapNames.value && run {
			val data = player.mainHandItem.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
			data.copyTag().getString("id").getOrNull() in LEAP_ITEMS
		}

		if (!DungeonFloor.loaded) return

		// Tracked regardless of settings: the secret count and Room Alerts need it.
		val tile = DungeonFloor.tileOf(player.x, player.z)
		if (tile == lastTile) return
		lastTile = tile

		val room = DungeonFloor.roomAt(tile) ?: return
		if (room !== lastRoom) {
			lastRoom = room
			imicro.cryptic.dungeon.RoomSecrets.onRoomEntered()
			imicro.cryptic.dungeon.SecretSync.send(room)
		}
		if (room.enterNow()) RoomPrediction.update()
	}

	/** The room you were last in, to tell when you walk into another. */
	private var lastRoom: DungeonRoom? = null

	private fun edge(): Int = padding.value.roundToInt()

	/** The element's size, which grows when the score sits under it. */
	internal fun mapWidth(): Int = DungeonFloor.sizeInPixels().x + edge() * 2

	internal fun mapHeight(): Int = DungeonFloor.sizeInPixels().z + edge() * 2

	private class MapElement : HudElement("dungeon_map", "Dungeon Map", 0.02, 0.15, 1.0) {
		/** The boss room isn't on the floor's map; the score under it still shows. */
		private val floorDrawn: Boolean
			get() = module.enabled && DungeonFloor.loaded && !DungeonRun.inBoss

		private val artDrawn: Boolean
			get() = module.enabled && DungeonScore.showAtEnd.value && DungeonRun.ended && ScoreArt.ready

		/** Draws Hypixel's end-of-run score screen from the map item, scaled to the map's box. */
		private fun renderEndArt(context: GuiGraphicsExtractor, width: Int, height: Int) {
			val size = ScoreArt.SIZE
			val drawn = minOf(width, height)
			// Scaled, not cropped: the single-size overload uses one size for source and
			// destination, which cut off the right side on small floors.
			context.blit(
				RenderPipelines.GUI_TEXTURED,
				ScoreArt.TEXTURE,
				(width - drawn) / 2,
				(height - drawn) / 2,
				0f,
				0f,
				drawn,
				drawn,
				size,
				size,
				size,
				size,
			)
		}

		// Square until a floor says otherwise. Doesn't shrink in the boss room, so the score under
		// it stays put.
		override val width: Int get() = mapWidth()
		override val height: Int get() = mapHeight() + DungeonScore.attachedHeight()

		// Having a floor is the whole condition; it only happens in a dungeon.
		override fun isVisible(): Boolean =
			floorDrawn || artDrawn || DungeonScore.attachedVisible()

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) {
			val size = DungeonFloor.sizeInPixels()
			val edge = edge()

			if (artDrawn) {
				context.fill(0, 0, size.x + edge * 2, size.z + edge * 2, background.argb)
				renderEndArt(context, size.x + edge * 2, size.z + edge * 2)
				DungeonScore.renderAttached(context, size.x + edge * 2, size.z + edge * 2)
				return
			}

			if (!floorDrawn) {
				// Hypixel blanks its map in the boss room, and so does this; only the score is
				// left, where it was.
				DungeonScore.renderAttached(context, size.x + edge * 2, size.z + edge * 2)
				return
			}

			context.fill(0, 0, size.x + edge * 2, size.z + edge * 2, background.argb)

			val pose = context.pose()
			pose.pushMatrix()
			pose.translate(edge.toFloat(), edge.toFloat())

			val all = revealAll
			DungeonFloor.rooms.forEach { it.render(context, all) }
			DungeonFloor.doors.forEach { it.render(context, doorThickness.value.roundToInt(), all) }
			DungeonFloor.rooms.forEach { renderLabel(context, it, all) }
			if (playerHeads.value) renderPlayers(context)

			pose.popMatrix()

			DungeonScore.renderAttached(context, size.x + edge * 2, size.z + edge * 2)
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			if (floorDrawn) {
				render(context)
				return
			}

			// Outside a dungeon, the editor gets a square the size of the real map.
			val size = DungeonFloor.sizeInPixels()
			val edge = edge()
			context.fill(0, 0, size.x + edge * 2, size.z + edge * 2, background.argb)
			context.centeredText(
				Minecraft.getInstance().font,
				"Dungeon Map",
				(size.x + edge * 2) / 2,
				(size.z + edge * 2 - Minecraft.getInstance().font.lineHeight) / 2,
				0xFFE4E4E4.toInt(),
			)
			DungeonScore.renderAttachedExample(context, size.x + edge * 2, size.z + edge * 2)
		}

		/**
		 * What a room shows about itself, by [roomStyle]. A name already shows progress in its
		 * colour, so it replaces the checkmark; rooms without a useful name (entrance, blood,
		 * fairy, unscanned) show their mark.
		 */
		private fun renderLabel(context: GuiGraphicsExtractor, room: DungeonRoom, revealAll: Boolean) {
			if (princeMarker.value) renderPrince(context, room, revealAll)

			val scale = nameScale.value.toFloat()
			val secrets = if (secretStyle) secretsText(room, revealAll) else null

			if (namedStyle && room.renderName(context, scale, revealAll, secrets)) return
			if (secrets != null) {
				room.renderCentered(context, scale, listOf(secrets))
				return
			}

			renderCheckmark(context, room, revealAll)
		}

		/**
		 * Found secrets out of the room's total. Only your current room reports, so others show
		 * their last count. A room with no secrets shows 0, as NoammAddons does.
		 */
		private fun secretsText(room: DungeonRoom, revealAll: Boolean): String? {
			val total = room.data?.secrets ?: return null
			if (!room.isIdentified(revealAll)) return null
			// A puzzle is finished by solving it; its name going green says so. Entrance, blood and
			// fairy keep their mark.
			if (room.type == DungeonRoom.Type.PUZZLE ||
				room.type == DungeonRoom.Type.FAIRY ||
				room.type == DungeonRoom.Type.ENTRANCE ||
				room.type == DungeonRoom.Type.BLOOD
			) {
				return null
			}
			// No secrets, no count: the name's colour says everything (mini boss rooms never have
			// any).
			if (total == 0 || room.type == DungeonRoom.Type.CHAMPION) return null
			return "${room.foundSecrets}/$total"
		}

		/** A crown in the corner of a room a Prince can still spawn in, until one has been killed. */
		private fun renderPrince(context: GuiGraphicsExtractor, room: DungeonRoom, revealAll: Boolean) {
			if (room.data?.prince != true || DungeonStats.princeKilled) return
			// A crown on a grey square would give away an unopened room.
			if (!room.isIdentified(revealAll)) return

			val corner = room.bottomRightTile()
			val pose = context.pose()
			pose.pushMatrix()
			pose.translate(corner.x + 9f, corner.z + 10f)
			pose.scale(0.7f, 0.7f)
			blit(context, PRINCE_CROWN, 0, 0, 10)
			pose.popMatrix()
		}

		private fun renderCheckmark(context: GuiGraphicsExtractor, room: DungeonRoom, revealAll: Boolean) {
			if (!revealAll && room.hidden) return

			val texture = when {
				// Hypixel marks the fairy room done on entry; it's really done when its inner
				// wither door opens: no mark before anyone's in, white once they are, green once
				// the door opens.
				room.type == DungeonRoom.Type.FAIRY -> when {
					room.fairyPassed -> GREEN_CHECK
					room.hidden || room.unopened -> null
					else -> WHITE_CHECK
				}
				else -> when (room.state) {
					DungeonRoom.State.GREEN -> GREEN_CHECK
					DungeonRoom.State.CLEARED -> WHITE_CHECK
					DungeonRoom.State.FAILED -> CROSS
					// A guessed room shows its possibilities in colour; a question mark would hide
					// them.
					DungeonRoom.State.UNOPENED ->
						if (questionMarks.value && room.guess.isEmpty()) QUESTION else null
					else -> null
				}
			}

			if (texture != null && room.type != DungeonRoom.Type.ENTRANCE) {
				val center = room.markCenter()
				val size = checkmarkSize.value.roundToInt()
				blit(context, texture, center.x - size / 2, center.z - size / 2, size)
			}
		}

		/** Where a teammate's head is drawn, versus where it actually is. */
		private class EasedMarker(var x: Float, var z: Float, var yaw: Float)

		private val easedMarkers = HashMap<String, EasedMarker>()
		private var lastEaseAt = 0L

		/**
		 * How far each head moves toward its real position this frame. The map item arrives a few
		 * times a second, so heads would lurch; easing on our side costs nothing. Based on real
		 * time so it looks the same at any frame rate, and capped so a dropped frame doesn't
		 * teleport heads.
		 */
		private fun easeAmount(): Float {
			val now = System.nanoTime()
			val elapsed = if (lastEaseAt == 0L) 0L else now - lastEaseAt
			lastEaseAt = now

			val seconds = (elapsed / 1_000_000_000.0).coerceIn(0.0, 0.25)
			return (1.0 - kotlin.math.exp(-seconds / EASE_SECONDS)).toFloat()
		}

		/**
		 * Moves [name]'s head part of the way toward the map's position. Past [EASE_SNAP_DISTANCE]
		 * (a leap) it snaps, rather than gliding through walls.
		 */
		private fun ease(name: String, x: Float, z: Float, yaw: Float, amount: Float): EasedMarker {
			val marker = easedMarkers.getOrPut(name) { EasedMarker(x, z, yaw) }

			val dx = x - marker.x
			val dz = z - marker.z
			if (dx * dx + dz * dz > EASE_SNAP_DISTANCE * EASE_SNAP_DISTANCE) {
				marker.x = x
				marker.z = z
				marker.yaw = yaw
				return marker
			}

			marker.x += dx * amount
			marker.z += dz * amount
			// Angles wrap, so the short way round is used.
			marker.yaw = Mth.rotLerp(amount, marker.yaw, yaw)
			return marker
		}

		/** Drops anyone no longer on the map. */
		private fun forgetMarkersExcept(names: List<String>) {
			if (easedMarkers.size <= names.size) return
			easedMarkers.keys.retainAll(names.toSet())
		}

		private fun renderPlayers(context: GuiGraphicsExtractor) {
			val client = Minecraft.getInstance()
			val player = client.player ?: return
			val pose = context.pose()
			val self = player.name.string
			val names = leapNames.value && namesWanted(client)

			// Hypixel drops a dead teammate's marker but keeps their tab row, so pairing skips
			// them.
			val teammates = DungeonTeam.classes.keys.filter { it != self && it !in DungeonTeam.dead }
			val ease = easeAmount()
			teammates.forEachIndexed { index, name ->
				// A teammate close enough to be loaded is drawn from the world, which is exact and
				// updates every frame; identity still comes from the tab list.
				val seen = client.level?.players()?.firstOrNull { it.name.string == name }
				val (x, z) = if (seen != null) {
					DungeonMapReader.worldPosition(seen.x, seen.z)
				} else {
					DungeonMapReader.markerPosition(DungeonMapReader.markers.getOrNull(index) ?: return@forEachIndexed)
				}
				val yaw = seen?.yRot ?: DungeonMapReader.markers[index].yaw
				val at = ease(name, x, z, yaw, ease)
				drawMarker(context, pose, at.x, at.z, at.yaw, name, false, names)
			}
			forgetMarkersExcept(teammates)

			// Your own head goes in a later layer, since the GUI batches by type and a teammate's
			// face could land over yours.
			context.nextStratum()

			// Your position comes from the world; teammates across the floor exist only on the map
			// item.
			val (selfX, selfZ) = DungeonMapReader.worldPosition(player.x, player.z)
			drawMarker(context, pose, selfX, selfZ, player.yRot, self, true, names)
		}

		/**
		 * Names show while a leap is in hand or its menu is open, the one time the map is read for
		 * who's who.
		 */
		private fun namesWanted(client: Minecraft): Boolean {
			val title = client.gui.screen()?.title?.string
			if (title == "Spirit Leap" || title == "Teleport to Player") return true
			return holdingLeap
		}

		private fun drawMarker(
			context: GuiGraphicsExtractor,
			pose: org.joml.Matrix3x2fStack,
			x: Float,
			z: Float,
			yaw: Float,
			name: String,
			self: Boolean,
			showNames: Boolean,
		) {
			val size = markerSize.value.roundToInt()
			val half = size / 2
			val arrow = self && selfMarker.selectedIndex == 1
			val color = outlineColor(name)

			pose.pushMatrix()
			pose.translate(x, z)

			// Written before the marker turns, so it stays upright.
			if (showNames) renderName(context, name, half)

			pose.rotate(Math.toRadians(180.0 + yaw).toFloat())

			if (arrow) {
				// The pointer is already an arrow; no triangle on top.
				blit(context, SELF_MARKER, -half, -half, size)
				pose.popMatrix()
				return
			}

			// The ring goes down first, so the colour reads as an outline.
			val edge = half + 1
			context.fill(-edge, -edge, edge, edge, color)

			val skin = Minecraft.getInstance().connection?.getPlayerInfo(name)?.skin
			if (skin != null) {
				PlayerFaceExtractor.extractRenderState(context, skin, -half, -half, size)
			} else {
				// Someone without a skin still needs a marker.
				context.fill(-half, -half, half, half, 0xFF3C3C3C.toInt())
			}

			if (facingArrow.value) renderFacing(context, edge, size, color)

			pose.popMatrix()
		}

		/**
		 * A triangle above the head pointing where they look, drawn inside the head's rotation. A
		 * short stack of rows, since there's no triangle primitive.
		 */
		private fun renderFacing(context: GuiGraphicsExtractor, edge: Int, size: Int, color: Int) {
			val height = (size / 3).coerceAtLeast(2)
			for (row in 0 until height) {
				val halfWidth = row + 1
				val top = -edge - height + row
				context.fill(-halfWidth, top, halfWidth, top + 1, color)
			}
		}

		private fun renderName(context: GuiGraphicsExtractor, name: String, half: Int) {
			val pose = context.pose()
			val scale = leapNameScale.value.toFloat()
			pose.pushMatrix()
			pose.translate(0f, half + 2f)
			pose.scale(scale, scale)
			context.centeredText(Minecraft.getInstance().font, name, 0, 0, 0xFFFFFFFF.toInt())
			pose.popMatrix()
		}

		/**
		 * Heads ringed in their class colour, from Class Colors' palette whether or not that module
		 * is on.
		 */
		private fun outlineColor(name: String): Int {
			val dungeonClass = DungeonTeam.classOf(name)
			val rgb = if (classOutline.value && dungeonClass != null) {
				ClassColors.getClassColor(dungeonClass)
			} else {
				playerColor.rgb
			}
			return rgb or 0xFF000000.toInt()
		}

		private fun blit(context: GuiGraphicsExtractor, texture: Identifier, x: Int, y: Int, size: Int) {
			context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, size, size, size, size)
		}
	}
}
