package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.map.BossScan
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonMapColors
import imicro.cryptic.dungeon.map.DungeonMapReader
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.dungeon.map.DungeonWorldScan
import imicro.cryptic.dungeon.map.RoomPrediction
import imicro.cryptic.dungeon.map.Vec2i
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
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
 * Draws the dungeon map Hypixel gives you as an item, on the HUD.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who); the full
 * licence is in `licenses/dtMap-LICENSE.txt`, and the check marks and pointer
 * are its artwork. Two things feed it: the map item, read by
 * [DungeonMapReader], which knows how far the run has got, and a scan of the
 * world around you, [DungeonWorldScan], which knows what every room is called.
 * The scan is why the map appears the moment you walk into the dungeon rather
 * than when the run starts.
 */
object DungeonMap {
	private val GREEN_CHECK = Cryptic.id("map/green_check.png")
	private val WHITE_CHECK = Cryptic.id("map/white_check.png")
	private val CROSS = Cryptic.id("map/cross.png")
	private val QUESTION = Cryptic.id("map/question.png")
	private val SELF_MARKER = Cryptic.id("map/self_marker.png")
	private val PRINCE_CROWN = Cryptic.id("map/prince_crown.png")

	/**
	 * How long a head takes to catch up with the map, roughly.
	 *
	 * Short enough that nobody is drawn anywhere they were not just now, long
	 * enough that the jump between two map updates reads as movement.
	 */
	private const val EASE_SECONDS = 0.09

	/** Past this, a head has been leaped rather than walked, and is not eased. */
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
	)

	/** True while the map is allowed to show what the world scan already knows. */
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
	val customizeRooms = ToggleModuleSetting(
		id = "customize_rooms",
		label = "Customize rooms",
		description = "Shows the colour of every room type.",
	)

	@JvmField
	val customizeDoors = ToggleModuleSetting(
		id = "customize_doors",
		label = "Customize doors",
		description = "Shows the door colours and how thick they are drawn.",
	)

	@JvmField
	val doorThickness = SliderModuleSetting(
		id = "door_thickness",
		label = "Door thickness",
		defaultValue = 8.0,
		min = 3.0,
		max = 16.0,
		step = 1.0,
		visibleIf = { customizeDoors.value },
	)

	private val roomsSection = SectionModuleSetting("rooms_section", "Rooms")

	@JvmField
	val normalColor = ColorModuleSetting("normal_color", "Normal", DungeonMapColors.NORMAL, visibleIf = { customizeRooms.value })

	@JvmField
	val bloodColor = ColorModuleSetting("blood_color", "Blood", DungeonMapColors.BLOOD, visibleIf = { customizeRooms.value })

	@JvmField
	val entranceColor = ColorModuleSetting("entrance_color", "Entrance", DungeonMapColors.ENTRANCE, visibleIf = { customizeRooms.value })

	@JvmField
	val puzzleColor = ColorModuleSetting("puzzle_color", "Puzzle", DungeonMapColors.PUZZLE, visibleIf = { customizeRooms.value })

	@JvmField
	val trapColor = ColorModuleSetting("trap_color", "Trap", DungeonMapColors.TRAP, visibleIf = { customizeRooms.value })

	@JvmField
	val championColor = ColorModuleSetting("champion_color", "Miniboss", DungeonMapColors.CHAMPION, visibleIf = { customizeRooms.value })

	@JvmField
	val fairyColor = ColorModuleSetting("fairy_color", "Fairy", DungeonMapColors.FAIRY, visibleIf = { customizeRooms.value })

	@JvmField
	val rareColor = ColorModuleSetting("rare_color", "Rare", DungeonMapColors.RARE, visibleIf = { customizeRooms.value })

	@JvmField
	val unexploredColor = ColorModuleSetting("unexplored_color", "Unexplored", DungeonMapColors.UNOPENED, visibleIf = { customizeRooms.value })

	@JvmField
	val darkenUnexplored = SliderModuleSetting(
		id = "darken_unexplored",
		label = "Dim unexplored",
		defaultValue = 40.0,
		min = 10.0,
		max = 100.0,
		step = 5.0,
		description = "How much colour a room keeps before anyone has been in it.",
		visibleIf = { customizeRooms.value },
	)

	private val doorsSection = SectionModuleSetting("doors_section", "Doors")

	@JvmField
	val normalDoorColor = ColorModuleSetting("normal_door_color", "Normal", DungeonMapColors.NORMAL, visibleIf = { customizeDoors.value })

	@JvmField
	val witherDoorColor = ColorModuleSetting("wither_door_color", "Wither", DungeonMapColors.WITHER_DOOR, visibleIf = { customizeDoors.value })

	@JvmField
	val bloodDoorColor = ColorModuleSetting("blood_door_color", "Blood", DungeonMapColors.BLOOD, visibleIf = { customizeDoors.value })

	@JvmField
	val entranceDoorColor = ColorModuleSetting("entrance_door_color", "Entrance", DungeonMapColors.ENTRANCE, visibleIf = { customizeDoors.value })

	private val marksSection = SectionModuleSetting("marks_section", "Marks")

	@JvmField
	val roomStyle = DropdownModuleSetting(
		id = "room_style",
		label = "Room style",
		options = listOf("Checkmarks", "Secrets", "Room name", "Name + secrets"),
		defaultIndex = 2,
		description = "What each room shows: its mark, its secrets or its name.",
	)

	/** True for the two styles that write the room's name across it. */
	private val namedStyle: Boolean get() = roomStyle.selectedIndex >= 2

	/** True for the two styles that count the room's secrets. */
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
	val leapNames = ToggleModuleSetting(
		id = "leap_names",
		label = "Names while leaping",
		defaultValue = true,
		description = "Labels every head while you hold a leap or have its menu open.",
		visibleIf = { playerHeads.value },
	)

	@JvmField
	val bossViewScale = SliderModuleSetting(
		id = "boss_view_scale",
		label = "Boss view scale",
		defaultValue = 1.5,
		min = 0.5,
		max = 4.0,
		step = 0.1,
		description = "How far the boss room view is zoomed in.",
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

	/** Listed rather than read back off the module, which is built from it. */
	private val configurableSettings = listOf(
		background,
		padding,
		reveal,
		predictRooms,
		customizeRooms,
		customizeDoors,
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
		bossViewScale,
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
		// The score rides on this card now, so its Reset covers both.
		DungeonScore.resetSettings()
	})

	@JvmField
	val module = Module(
		id = "dungeon_map",
		name = "Dungeon Map",
		description = "Shows the dungeon map on your HUD",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			SectionModuleSetting("map_section", "Map"),
			background,
			padding,
			reveal,
			predictRooms,
			roomsSection,
			customizeRooms,
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
			doorsSection,
			customizeDoors,
			doorThickness,
			normalDoorColor,
			witherDoorColor,
			bloodDoorColor,
			entranceDoorColor,
			marksSection,
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
			bossViewScale,
		) + DungeonScore.scoreSettings + listOf(reset),
	)

	private var initialized = false

	/**
	 * Whether the floor needs keeping up to date at all.
	 *
	 * The world scan and the room the player is standing in are the map's, but
	 * [DoorHighlight] reads both, so they run for either module rather than
	 * leaving one of them quietly broken when the map is switched off.
	 */
	/**
	 * Whoever needs the floor kept up to date, which is not only the map.
	 *
	 * The room the player is standing in is worked out here and nowhere else,
	 * so every module that asks [currentRoom] has to be on this list. Room
	 * Alerts was not, and that is why its titles came and went with whether the
	 * map happened to be switched on.
	 */
	private val needed: Boolean
		get() = module.enabled ||
			DoorHighlight.module.enabled ||
			RoomAlerts.module.enabled ||
			BreakerHelper.module.enabled

	/** The tile the player was last seen in, so a room is only entered once. */
	private var lastTile: Vec2i? = null

	/** Skyblock's two ways of teleporting to a teammate. */
	private val LEAP_ITEMS = setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")

	/**
	 * Whether a leap is in hand, worked out once a tick.
	 *
	 * Reading a Skyblock item's id copies its custom data, which is too much to
	 * do for every frame the map is drawn.
	 */
	private var holdingLeap = false

	/** The element the HUD draws and the editor lets you place. */
	val element: HudElement = MapElement()

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(element)

		// A chunk arriving is the only thing that can give the world scan more
		// rooms to name, so it is what asks for another pass.
		ClientChunkEvents.CHUNK_LOAD.register { _, _ ->
			if (needed) DungeonWorldScan.requestScan()
		}

		// The action bar arrives as a game message flagged as an overlay, which
		// is where the room's own secret count lives.
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (overlay) readSecretCount(message.string)
		}

		// Hypixel moves you between servers for every floor, and the map from
		// the last one must not survive into the next.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onLeaveFloor() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onLeaveFloor() }
	}

	private fun onLeaveFloor() {
		lastTile = null
		DungeonMapReader.reset()
	}

	/** The room the player is standing in, or null when they are between floors. */
	fun currentRoom(): DungeonRoom? = lastTile?.let(DungeonFloor::roomAt)

	/**
	 * Notes which doorways are now standing open.
	 *
	 * The world is the only honest source: Hypixel repaints an opened wither
	 * door as an ordinary one on the map item, so afterwards the map cannot
	 * tell a door that was never locked from one somebody spent a key on. Kept
	 * here rather than in [DoorHighlight] because the fairy room's name turns
	 * green on its wither door opening, and that should not need a second
	 * module switched on.
	 */
	private fun readOpenDoors(level: net.minecraft.world.level.Level) {
		if (doorCheckCountdown-- > 0) return
		doorCheckCountdown = DOOR_CHECK_INTERVAL_TICKS

		DungeonFloor.doors.forEach { door ->
			if (door.opened) return@forEach
			val block = level.getBlockState(BlockPos(door.worldX, DOORWAY_Y, door.worldZ)).block
			// An empty doorway is an open one. The barrier is what Hypixel
			// leaves in the gap while a door is being opened.
			door.opened = block == Blocks.AIR || block == Blocks.BARRIER
		}
	}

	/** Doors do not move, so noticing one has opened is a rare job. */
	private const val DOOR_CHECK_INTERVAL_TICKS = 10
	private const val DOORWAY_Y = 69

	private var doorCheckCountdown = 0

	/**
	 * Files the action bar's secret count against the room it was counted in.
	 *
	 * Hypixel publishes "2/3 Secrets" only while you are inside the room it is
	 * about, so this is the one moment the number can be attributed to anything.
	 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who).
	 */
	private fun readSecretCount(text: String) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		// The colour code has to come off first: Hypixel writes the count as
		// "§74/4 Secrets", and a digit pattern reading that raw takes the 7 out
		// of the grey colour code as part of the number.
		val plain = text.replace(formattingPattern, "")
		val found = secretCountPattern.find(plain)?.groupValues?.get(1)?.toIntOrNull() ?: return
		currentRoom()?.foundSecrets = found
	}

	private val formattingPattern = Regex("§.")

	/** Hypixel's action bar, e.g. "... 2/3 Secrets" once its colours are gone. */
	private val secretCountPattern = Regex("""(\d+)/\d+ Secrets""")

	/**
	 * Keeps the floor up to date: reads any chunks that have arrived, and marks
	 * the room you just walked into without waiting for the map item to agree.
	 */
	fun tick(client: Minecraft) {
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

		// Which room the player is in is tracked whatever the settings say:
		// the action bar's secret count has to be filed against it, and Room
		// Alerts asks for it too.
		val tile = DungeonFloor.tileOf(player.x, player.z)
		if (tile == lastTile) return
		lastTile = tile

		// A room is marked entered as you walk in rather than when Hypixel
		// catches up, which is always what you want and never worth a switch.
		val room = DungeonFloor.roomAt(tile) ?: return
		if (room.enterNow()) RoomPrediction.update()
	}

	private fun edge(): Int = padding.value.roundToInt()

	/** The whole element's size, which grows when the score is drawn under it. */
	internal fun mapWidth(): Int = DungeonFloor.sizeInPixels().x + edge() * 2

	internal fun mapHeight(): Int = DungeonFloor.sizeInPixels().z + edge() * 2

	private class MapElement : HudElement("dungeon_map", "Dungeon Map", 0.02, 0.15, 1.0) {
		/**
		 * The boss room is not on the floor's map, so the map has nothing left
		 * to say there. The score attached under it still has plenty.
		 */
		private val floorDrawn: Boolean
			get() = module.enabled && DungeonFloor.loaded && !DungeonRun.inBoss

		/** True while the score screen from a finished run is worth drawing. */
		private val artDrawn: Boolean
			get() = module.enabled && DungeonScore.showAtEnd.value &&
				DungeonRun.ended && DungeonMapReader.endArt.isNotEmpty()

		/**
		 * Draws Hypixel's own end-of-run score screen, scaled to the map's box.
		 *
		 * The map item stops being a floor when the run ends and becomes the
		 * picture everybody stops to read: four categories, a grade and a
		 * number. Rather than rebuild that out of the numbers Cryptic already
		 * has, the picture itself is drawn — it is the one on the item in your
		 * hand, and it is the one people screenshot.
		 */
		private fun renderEndArt(context: GuiGraphicsExtractor, width: Int, height: Int) {
			val size = DungeonMapReader.ART_SIZE
			val scale = minOf(width, height) / size.toFloat()
			val originX = (width - size * scale) / 2f
			val originY = (height - size * scale) / 2f

			val pose = context.pose()
			pose.pushMatrix()
			pose.translate(originX, originY)
			pose.scale(scale, scale)
			DungeonMapReader.endArt.forEach { run ->
				context.fill(run.left, run.top, run.right, run.top + 1, run.argb)
			}
			pose.popMatrix()
		}

		/** The boss room from above, which takes the floor map's place. */
		private val bossDrawn: Boolean
			get() = module.enabled && DungeonLocation.inDungeon && DungeonScore.showsBossView

		// The map is square until a floor says otherwise, which is what the
		// editor measures when there is no dungeon to read. The size does not
		// shrink in the boss room even though the floor stops being drawn: the
		// score hangs off the bottom edge, and it should stay where it was put.
		override val width: Int get() = mapWidth()
		override val height: Int get() = mapHeight() + DungeonScore.attachedHeight()

		// Having a floor at all is the whole condition: it can only happen
		// inside a dungeon, and asking the scoreboard as well only adds a
		// second way for the map to come up empty.
		override fun isVisible(): Boolean =
			floorDrawn || bossDrawn || artDrawn || DungeonScore.attachedVisible()

		/** A card that is switched off has nothing on the HUD to arrange. */
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
				// The floor is behind us. Either the boss room is drawn in its
				// place, or only the score is left — and either way it is drawn
				// against the outline the map filled, so nothing moves when the
				// boss starts.
				if (bossDrawn) {
					context.fill(0, 0, size.x + edge * 2, size.z + edge * 2, background.argb)
					val bossPose = context.pose()
					bossPose.pushMatrix()
					bossPose.translate(edge.toFloat(), edge.toFloat())
					renderBossView(context, size.x, size.z)
					bossPose.popMatrix()
				}
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

			// Outside a dungeon there is no map to show, so the editor gets a
			// square the size of the real one to arrange against.
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
		 * The boss room from above, when the floor's map has nothing left to
		 * show.
		 *
		 * Hypixel stops drawing its map item the moment the boss starts, which
		 * is the moment a party most wants to know where everybody is: Goldor's
		 * four sections are four corners of a tower and the terminals are done
		 * in pairs. There is no map to read for it, so this is drawn from the
		 * world — every teammate the client can see, plotted against you in the
		 * middle, at a scale you can set.
		 *
		 * Nothing here is room-shaped, because a boss room has no published
		 * shape: it is a view of the ground around you rather than a plan of
		 * the room, and it travels with you as you cross it.
		 */
		/**
		 * The boss room from above: the room itself, then everybody on it.
		 *
		 * The plan is a square of blocks around you rather than a picture of
		 * the room, so it is right wherever you are — including partway down
		 * Goldor's tower, where the floor you are on is not the floor anybody
		 * drew a picture of.
		 */
		private fun renderBossView(context: GuiGraphicsExtractor, width: Int, height: Int) {
			val client = Minecraft.getInstance()
			val player = client.player ?: return
			val level = client.level ?: return
			BossScan.update(level, player)

			val blocks = BossScan.SIZE
			val scale = minOf(width, height) / blocks.toFloat() * bossViewScale.value.toFloat()
			val centerX = width / 2f
			val centerZ = height / 2f

			// Where the plan's top-left corner falls on screen, given that the
			// player is drawn in the middle of the box.
			val offsetX = centerX - ((player.x - BossScan.originX) * scale).toFloat()
			val offsetZ = centerZ - ((player.z - BossScan.originZ) * scale).toFloat()

			val pose = context.pose()
			pose.pushMatrix()
			// Clipped to the box, or a plan wider than the map paints the HUD.
			context.enableScissor(0, 0, width, height)
			pose.translate(offsetX, offsetZ)
			pose.scale(scale, scale)
			BossScan.runs.forEach { run ->
				context.fill(run.left, run.top, run.right, run.top + 1, run.argb)
			}
			pose.popMatrix()
			context.disableScissor()

			val self = player.name.string
			val names = leapNames.value && namesWanted(client)
			val headPose = context.pose()

			DungeonTeam.classes.keys.forEach { name ->
				if (name == self || name in DungeonTeam.dead) return@forEach
				val teammate = level.players().firstOrNull { it.name.string == name } ?: return@forEach

				val x = centerX + ((teammate.x - player.x) * scale).toFloat()
				val z = centerZ + ((teammate.z - player.z) * scale).toFloat()
				if (x < 0f || z < 0f || x > width || z > height) return@forEach
				drawMarker(context, headPose, x, z, teammate.yRot, name, false, names)
			}

			// Yours last and in its own layer, for the same reason as on the
			// floor map: it is the one that must never end up underneath.
			context.nextStratum()
			drawMarker(context, headPose, centerX, centerZ, player.yRot, self, true, names)
		}


		/**
		 * A room says one thing about itself, chosen by [roomStyle].
		 *
		 * A name already carries how far the room is cleared, in its colour, so
		 * it stands in for the checkmark rather than sitting beside it. Rooms
		 * with nothing to say by name — the entrance, blood, fairy, and anything
		 * the world scan has not reached — fall back to their mark.
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
		 * How many of a room's secrets are found, out of how many it holds.
		 *
		 * Only the room you are standing in reports its count, so the rest show
		 * what they last said. A room with no secrets shows a plain zero, the
		 * way NoammAddons does, rather than a misleading "0/0".
		 */
		private fun secretsText(room: DungeonRoom, revealAll: Boolean): String? {
			val total = room.data?.secrets ?: return null
			if (!room.isIdentified(revealAll)) return null
			// A puzzle is finished by solving it, not by counting it, and its
			// name going green already says whether the secret came with it.
			// The entrance, blood and fairy rooms keep their mark instead.
			if (room.type == DungeonRoom.Type.PUZZLE ||
				room.type == DungeonRoom.Type.FAIRY ||
				room.type == DungeonRoom.Type.ENTRANCE ||
				room.type == DungeonRoom.Type.BLOOD
			) {
				return null
			}
			// A room with no secrets has no count worth writing. Its name going
			// from grey to green already says everything there is to say about
			// it, and a mini boss room never had secrets to begin with.
			if (total == 0 || room.type == DungeonRoom.Type.CHAMPION) return null
			return "${room.foundSecrets}/$total"
		}

		/**
		 * A crown in the corner of a room a Prince can still spawn in.
		 *
		 * The Prince is a bonus point, so it stops being worth pointing at the
		 * moment one has been killed.
		 */
		private fun renderPrince(context: GuiGraphicsExtractor, room: DungeonRoom, revealAll: Boolean) {
			if (room.data?.prince != true || DungeonStats.princeKilled) return
			// A crown on a grey square gives away a room the run has not opened
			// yet, which is a different claim from "there is a Prince here".
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

			val texture = when (room.state) {
				DungeonRoom.State.GREEN -> GREEN_CHECK
				DungeonRoom.State.CLEARED -> WHITE_CHECK
				DungeonRoom.State.FAILED -> CROSS
				// A guessed room is already saying what it might be in its
				// colours, and a question mark on top only hides them.
				DungeonRoom.State.UNOPENED ->
					if (questionMarks.value && room.guess.isEmpty()) QUESTION else null
				else -> null
			}

			if (texture != null && room.type != DungeonRoom.Type.ENTRANCE) {
				val center = room.markCenter()
				val size = checkmarkSize.value.roundToInt()
				blit(context, texture, center.x - size / 2, center.z - size / 2, size)
			}
		}

		/** Where a teammate's head is being drawn, as against where it has got to. */
		private class EasedMarker(var x: Float, var z: Float, var yaw: Float)

		private val easedMarkers = HashMap<String, EasedMarker>()
		private var lastEaseAt = 0L

		/**
		 * How far to move each head toward its real position this frame.
		 *
		 * Hypixel sends the map item a few times a second, so a head drawn
		 * straight from it lurches: it stands still for several frames and then
		 * jumps. There is no way to ask for the data more often — it arrives when
		 * Hypixel sends it — so the fix has to be on this side, and easing toward
		 * the last known position costs nothing per frame where asking again
		 * would cost a packet.
		 *
		 * Worked out from real time rather than from ticks so the smoothing looks
		 * the same at any frame rate, and capped so a frame lost to something
		 * else does not make every head teleport.
		 */
		private fun easeAmount(): Float {
			val now = System.nanoTime()
			val elapsed = if (lastEaseAt == 0L) 0L else now - lastEaseAt
			lastEaseAt = now

			val seconds = (elapsed / 1_000_000_000.0).coerceIn(0.0, 0.25)
			return (1.0 - kotlin.math.exp(-seconds / EASE_SECONDS)).toFloat()
		}

		/**
		 * Moves [name]'s head part of the way to where the map says it is.
		 *
		 * A leap puts somebody across the floor between one update and the next,
		 * and easing that would send the head gliding through every wall on the
		 * way — so anything past [EASE_SNAP_DISTANCE] is simply taken as read.
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
			// Angles wrap, so the short way round has to be worked out rather
			// than interpolated between the raw numbers — otherwise a head
			// turning past south spins the long way back.
			marker.yaw = Mth.rotLerp(amount, marker.yaw, yaw)
			return marker
		}

		/** Drops anyone who is no longer on the map, so the names cannot pile up. */
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

			// Hypixel drops a dead teammate's marker but keeps their tab row, so
			// pairing has to skip them or every head after the dead one belongs
			// to the wrong person.
			val teammates = DungeonTeam.classes.keys.filter { it != self && it !in DungeonTeam.dead }
			val ease = easeAmount()
			teammates.forEachIndexed { index, name ->
				// A teammate close enough to be loaded is drawn from the world
				// instead of from the map: that position is exact and moves
				// every frame, where the map item's is a rounded-off pixel that
				// arrives a few times a second. It is also the same head either
				// way — who this is comes from the tab list, not from which of
				// the two said where they are standing.
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

			// Your own head goes in a later layer than everybody else's, so it is
			// on top of anyone standing where you are. Drawing it last is not
			// enough on its own: the GUI batches by what it is drawing, so a
			// teammate's face can still land over yours.
			context.nextStratum()

			// The player's own position comes from the world, which is exact and
			// updates every frame; everyone else comes from the map item, which
			// is the only place a teammate across the floor exists at all.
			val (selfX, selfZ) = DungeonMapReader.worldPosition(player.x, player.z)
			drawMarker(context, pose, selfX, selfZ, player.yRot, self, true, names)
		}

		/**
		 * Whether this is a moment when knowing who is who matters.
		 *
		 * Choosing a leap target is the one time the map is read for names
		 * rather than for rooms, so the labels appear while a leap is in hand or
		 * its menu is open and stay out of the way otherwise.
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

			// The label is written before the marker is turned, so it stays the
			// right way up however the player happens to be facing.
			if (showNames) renderName(context, name, half)

			pose.rotate(Math.toRadians(180.0 + yaw).toFloat())

			if (arrow) {
				// The map pointer is already an arrow, so a triangle on top of
				// it would be the same thing said twice.
				blit(context, SELF_MARKER, -half, -half, size)
				pose.popMatrix()
				return
			}

			// The ring goes down first and the face on top of it, so the colour
			// reads as an outline rather than as a block behind a floating head.
			val edge = half + 1
			context.fill(-edge, -edge, edge, edge, color)

			val skin = Minecraft.getInstance().connection?.getPlayerInfo(name)?.skin
			if (skin != null) {
				PlayerFaceExtractor.extractRenderState(context, skin, -half, -half, size)
			} else {
				// Someone Cryptic has no skin for still needs to be somewhere.
				context.fill(-half, -half, half, half, 0xFF3C3C3C.toInt())
			}

			if (facingArrow.value) renderFacing(context, edge, size, color)

			pose.popMatrix()
		}

		/**
		 * A triangle above the head pointing where the player is looking.
		 *
		 * Drawn inside the same rotation as the head, so pointing "up" here is
		 * pointing forwards on the floor. There is no triangle primitive to draw
		 * with, and at this size a short stack of rows is one anyway.
		 */
		private fun renderFacing(context: GuiGraphicsExtractor, edge: Int, size: Int, color: Int) {
			val height = (size / 3).coerceAtLeast(2)
			for (row in 0 until height) {
				val halfWidth = row + 1
				val top = -edge - height + row
				context.fill(-halfWidth, top, halfWidth, top + 1, color)
			}
		}

		/** Whose head is whose, for picking someone out of a leap menu quickly. */
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
		 * Heads are ringed in their dungeon class's colour.
		 *
		 * The palette is the Class Colors module's, read whether or not that
		 * module is switched on: it is where those colours are configured, and
		 * wanting them on the map says nothing about wanting teammates to glow.
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
