package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.map.DungeonDoor
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft

/**
 * Boxes the doorways worth walking through: locked doors (green once the party holds the key), and
 * open doorways that still lead to unfinished rooms, worked out by walking the floor. Inspired by
 * Devonian's Box Doors.
 */
object DoorHighlight {
	/** A doorway is three blocks across and four tall, from floor level. */
	private const val DOOR_BOTTOM = 69.0
	private const val DOOR_TOP = 73.0
	private const val DOOR_RADIUS = 1.5

	private val doorsSection = SectionModuleSetting("doors_section", "Doors")

	@JvmField
	val showLocked = ToggleModuleSetting(
		id = "show_locked",
		label = "Locked doors",
		defaultValue = true,
		description = "Wither and blood doors you have no key for yet.",
	)

	@JvmField
	val showUnlocked = ToggleModuleSetting(
		id = "show_unlocked",
		label = "Unlocked doors",
		defaultValue = true,
		description = "Wither and blood doors you are holding the key for.",
	)

	@JvmField
	val showNormal = ToggleModuleSetting(
		id = "show_normal",
		label = "Normal doors",
		defaultValue = true,
		description = "The plain doorways out of the room you are standing in.",
	)

	@JvmField
	val hideUseless = ToggleModuleSetting(
		id = "hide_useless",
		label = "Hide useless doors",
		defaultValue = true,
		description = "Drops a doorway once everything reachable through it is finished.",
		visibleIf = { showNormal.value },
	)

	@JvmField
	val showFairy = ToggleModuleSetting(
		id = "show_fairy",
		label = "Fairy room",
		defaultValue = true,
		description = "Marks the fairy room, and its wither door from next door.",
	)

	@JvmField
	val lockedOutline = ColorModuleSetting(
		id = "locked_outline",
		label = "Locked outline",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		defaultAlpha = 255,
		visibleIf = { showLocked.value },
	)

	@JvmField
	val lockedFill = ColorModuleSetting(
		id = "locked_fill",
		label = "Locked fill",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		defaultAlpha = 64,
		visibleIf = { showLocked.value },
	)

	@JvmField
	val unlockedOutline = ColorModuleSetting(
		id = "unlocked_outline",
		label = "Unlocked outline",
		defaultRgb = 0x00FF00,
		supportsAlpha = true,
		defaultAlpha = 255,
		visibleIf = { showUnlocked.value },
	)

	@JvmField
	val unlockedFill = ColorModuleSetting(
		id = "unlocked_fill",
		label = "Unlocked fill",
		defaultRgb = 0x00FF00,
		supportsAlpha = true,
		defaultAlpha = 64,
		visibleIf = { showUnlocked.value },
	)

	@JvmField
	val normalOutline = ColorModuleSetting(
		id = "normal_outline",
		label = "Normal outline",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = 255,
		visibleIf = { showNormal.value },
	)

	@JvmField
	val normalFill = ColorModuleSetting(
		id = "normal_fill",
		label = "Normal fill",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = 0,
		visibleIf = { showNormal.value },
	)

	@JvmField
	val fairyOutline = ColorModuleSetting(
		id = "fairy_outline",
		label = "Fairy outline",
		defaultRgb = 0xF4138B,
		supportsAlpha = true,
		defaultAlpha = 255,
		visibleIf = { showFairy.value },
	)

	@JvmField
	val fairyFill = ColorModuleSetting(
		id = "fairy_fill",
		label = "Fairy fill",
		defaultRgb = 0xF4138B,
		supportsAlpha = true,
		defaultAlpha = 64,
		visibleIf = { showFairy.value },
	)

	private val drawingSection = SectionModuleSetting("drawing_section", "Drawing")

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
	)

	@JvmField
	val fillThroughWalls = ToggleModuleSetting(
		id = "fill_through_walls",
		label = "Fill phase",
		defaultValue = false,
		description = "The outline always shows through walls; this decides whether the fill does too.",
	)

	private val configurableSettings = listOf(
		showLocked,
		showUnlocked,
		showNormal,
		hideUseless,
		showFairy,
		lockedOutline,
		lockedFill,
		unlockedOutline,
		unlockedFill,
		normalOutline,
		normalFill,
		fairyOutline,
		fairyFill,
		lineWidth,
		fillThroughWalls,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "door_highlight",
		name = "Door Highlight",
		description = "Boxes the doors worth taking",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		// Each kind of door carries its two colours in its "⋯".
		settings = listOf(
			doorsSection,
			showLocked.withMore(lockedOutline, lockedFill),
			showUnlocked.withMore(unlockedOutline, unlockedFill),
			showNormal.withMore(normalOutline, normalFill),
			hideUseless,
			showFairy.withMore(fairyOutline, fairyFill),
			drawingSection, lineWidth, fillThroughWalls, reset,
		),
	)

	private var witherKeys = 0
	private var hasBloodKey = false

	/** Once blood is open, the fairy room has served its purpose. */
	private var bloodOpened = false

	private var initialized = false

	// Hypixel's lines for a key changing hands and a door opening.
	private val witherClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Wither Key!$""")
	private val bloodClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Blood Key!$""")
	private val witherOpened = Regex("""^[A-Za-z0-9_]+ opened a WITHER door!$""")

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay) onMessage(message.string)
		}
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		witherKeys = 0
		hasBloodKey = false
		bloodOpened = false
		routes = Routes.NONE
	}

	/** Keys are only announced in chat. The count matters: two wither keys open two doors. */
	private fun onMessage(line: String) {
		when {
			line == "A Wither Key was picked up!" || witherClaimed.matches(line) -> witherKeys++
			line == "A Blood Key was picked up!" || bloodClaimed.matches(line) -> hasBloodKey = true
			line == "The BLOOD DOOR has been opened!" -> {
				hasBloodKey = false
				bloodOpened = true
			}
			witherOpened.matches(line) -> witherKeys = (witherKeys - 1).coerceAtLeast(0)
		}
	}

	private val active: Boolean
		get() = module.enabled && DungeonLocation.inDungeon && !DungeonRun.inBoss

	/**
	 * For the current room: how many rooms away the nearest unfinished one is through each door
	 * (doors leading nowhere unfinished are absent). Worked out once a tick.
	 */
	private class Routes(val room: DungeonRoom?, val tick: Long, val stepsThrough: Map<DungeonDoor, Int>) {
		companion object {
			val NONE = Routes(null, -1L, emptyMap())
		}
	}

	private var routes = Routes.NONE

	private fun routesFrom(room: DungeonRoom): Routes {
		val tick = Minecraft.getInstance().level?.gameTime ?: 0L
		if (routes.room === room && routes.tick == tick) return routes
		val steps = HashMap<DungeonDoor, Int>()
		for (door in room.doors) {
			val beyond = door.rooms.firstOrNull { it !== room } ?: continue
			stepsToUnfinished(beyond, room)?.let { steps[door] = it }
		}
		routes = Routes(room, tick, steps)
		return routes
	}

	private fun unfinished(room: DungeonRoom): Boolean =
		room.type != DungeonRoom.Type.ENTRANCE && room.state != DungeonRoom.State.GREEN

	/**
	 * Rooms from [start] to the nearest unfinished one, never back through [behind]; null if all is
	 * done that way. Finished rooms count for what lies past them. Each room is visited once, since
	 * floors loop.
	 */
	private fun stepsToUnfinished(start: DungeonRoom, behind: DungeonRoom): Int? {
		val seen = hashSetOf(behind, start)
		var frontier = listOf(start)
		var steps = 0
		while (frontier.isNotEmpty()) {
			if (frontier.any(::unfinished)) return steps
			steps++
			frontier = frontier.flatMap { room ->
				room.doors.flatMap { it.rooms }.filter(seen::add)
			}
		}
		return null
	}

	private fun render(context: LevelRenderContext) {
		if (!active) return
		val current = DungeonMap.currentRoom() ?: return
		for (door in DungeonFloor.doors) {
			val (outline, fill) = colorsFor(door, current) ?: continue
			draw(context, door, outline, fill)
		}
	}

	/**
	 * A door's outline and fill, or null to skip it. Only doors of your current room, plus the
	 * fairy room's wither door seen from next door, which shows whether anyone has the key.
	 */
	private fun colorsFor(door: DungeonDoor, current: DungeonRoom): Pair<ColorModuleSetting, ColorModuleSetting>? {
		val ownDoor = current in door.rooms
		if (!ownDoor) return if (fairyDoorFromOutside(door, current)) lockColors(door) else null

		// The way into the fairy room is painted like a wither door on the map, so it would
		// otherwise be dropped once open.
		if (current.type != DungeonRoom.Type.FAIRY && door.rooms.any { it.type == DungeonRoom.Type.FAIRY }) {
			return if (showFairy.value) fairyOutline to fairyFill else null
		}

		val locked = !door.opened && (door.type == DungeonDoor.Type.WITHER || door.type == DungeonDoor.Type.BLOOD)
		if (locked) return lockColors(door)

		// An open doorway, wither or not: marked while something past it is unfinished.
		if (!showNormal.value) return null
		val routes = routesFrom(current)
		val steps = routes.stepsThrough[door]
		if (hideUseless.value && steps == null) return null
		return normalOutline to normalFill
	}

	private fun lockColors(door: DungeonDoor): Pair<ColorModuleSetting, ColorModuleSetting>? {
		val keyed = if (door.type == DungeonDoor.Type.BLOOD) hasBloodKey else witherKeys > 0
		return when {
			keyed && showUnlocked.value -> unlockedOutline to unlockedFill
			!keyed && showLocked.value -> lockedOutline to lockedFill
			else -> null
		}
	}

	/** The wither door inside the fairy room, from a neighbouring room, until blood is open. */
	private fun fairyDoorFromOutside(door: DungeonDoor, current: DungeonRoom): Boolean {
		if (!showFairy.value || bloodOpened || door.type != DungeonDoor.Type.WITHER || door.opened) return false
		val fairy = door.rooms.firstOrNull { it.type == DungeonRoom.Type.FAIRY } ?: return false
		return current.doors.any { fairy in it.rooms }
	}

	/**
	 * The doorway, three across and four tall. The outline always shows through walls; the fill
	 * only if asked.
	 */
	private fun draw(context: LevelRenderContext, door: DungeonDoor, outline: ColorModuleSetting, fill: ColorModuleSetting) {
		val minX = door.worldX - DOOR_RADIUS + 0.5
		val minZ = door.worldZ - DOOR_RADIUS + 0.5
		for ((color, outlinePass) in listOf(outline to true, fill to false)) {
			if (color.alpha == 0) continue
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = minX, minY = DOOR_BOTTOM, minZ = minZ,
				maxX = minX + DOOR_RADIUS * 2, maxY = DOOR_TOP, maxZ = minZ + DOOR_RADIUS * 2,
				outlineArgb = if (outlinePass) color.argb else 0,
				fillArgb = if (outlinePass) 0 else color.argb,
				outline = outlinePass,
				fill = !outlinePass,
				phase = outlinePass || fillThroughWalls.value,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}
}
