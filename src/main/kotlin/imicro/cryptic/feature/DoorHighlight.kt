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
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft

/**
 * Boxes the doorways worth walking through.
 *
 * Ported from Devonian (https://github.com/Synnerz/devonian). Two questions
 * matter in a run: which locked door can I open right now, and which way out of
 * this room still leads to something. Devonian answers both, and the second
 * answer is the clever one — a door is hidden when everything reachable through
 * it is already finished, worked out by walking the floor rather than by
 * looking at the room next door.
 *
 * The drawing is Cryptic's own rather than Devonian's, whose boxes are hidden
 * by water and by skulls.
 */
object DoorHighlight {
	/** A doorway is three blocks across and four tall, standing on floor level. */
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
		description = "Marks the way in to the fairy room, and from the room next to it " +
			"shows its wither door too, so you can see whether the key is already in hand.",
	)

	private val colorsSection = SectionModuleSetting("colors_section", "Colours")

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
		defaultRgb = 0x008080,
		supportsAlpha = true,
		defaultAlpha = 255,
		visibleIf = { showNormal.value },
	)

	@JvmField
	val normalFill = ColorModuleSetting(
		id = "normal_fill",
		label = "Normal fill",
		defaultRgb = 0x008080,
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
		label = "Fill through walls",
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
		description = "Boxes the doors worth going through, and the ones you can open",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(doorsSection, showLocked, showUnlocked, showNormal, hideUseless, showFairy) +
			listOf(
				colorsSection,
				lockedOutline, lockedFill,
				unlockedOutline, unlockedFill,
				normalOutline, normalFill,
				fairyOutline, fairyFill,
			) +
			listOf(drawingSection, lineWidth, fillThroughWalls, reset),
	)

	/** Keys in the party's hands, which is what makes a locked door openable. */
	private var witherKeys = 0
	private var hasBloodKey = false

	/** Once blood is open the fairy room has served its purpose. */
	private var bloodOpened = false

	private var initialized = false

	private val witherClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Wither Key!$""")
	private val bloodClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Blood Key!$""")
	private val witherOpened = Regex("""^[A-Za-z0-9_]+ opened a WITHER door!$""")

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(::render)
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
	}

	/**
	 * Keys are only ever announced in chat, and the count matters: a party
	 * holding two wither keys can open two doors before anything changes.
	 */
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

	private fun render(context: LevelRenderContext) {
		if (!active) return

		val current = DungeonMap.currentRoom()
		DungeonFloor.doors.forEach { door ->
			val colors = colorsFor(door, current) ?: return@forEach
			draw(context, door, colors.first, colors.second)
		}
	}

	/**
	 * The outline and fill a door should be drawn in, or null to leave it alone.
	 *
	 * Every door falls into one of four cases: it needs a key you do not have,
	 * it needs one you do, it belongs to the fairy room, or it is an ordinary
	 * way out of the room you are standing in.
	 */
	private fun colorsFor(
		door: DungeonDoor,
		current: DungeonRoom?,
	): Pair<ColorModuleSetting, ColorModuleSetting>? {
		if (current == null) return null

		// Only the doorways of the room you are standing in. Anything further
		// out is a wireframe of a floor you have not walked yet.
		val leadsOut = current in door.rooms
		val peeked = !leadsOut && peeksFairyWitherDoor(door, current)
		if (!leadsOut && !peeked) return null

		if (door.type == DungeonDoor.Type.WITHER || door.type == DungeonDoor.Type.BLOOD) {
			if (door.opened) return null
			val keyed = if (door.type == DungeonDoor.Type.WITHER) witherKeys > 0 else hasBloodKey
			if (keyed && !showUnlocked.value) return null
			if (!keyed && !showLocked.value) return null
			return if (keyed) unlockedOutline to unlockedFill else lockedOutline to lockedFill
		}

		// The look into the fairy room is for its wither door alone; its other
		// doorways are the next room's business, not this one's.
		if (peeked) return null

		// The way in to the fairy room, marked from outside. Standing in the
		// room it leads to, the same doorway is just the way back out, so it
		// only reads as the fairy door while you are still looking for it.
		if (showFairy.value &&
			current.type != DungeonRoom.Type.FAIRY &&
			door.rooms.any { it.type == DungeonRoom.Type.FAIRY }
		) {
			return fairyOutline to fairyFill
		}

		if (!showNormal.value) return null
		if (hideUseless.value && door.rooms.none { isUseful(it, current, mutableSetOf()) }) return null
		return normalOutline to normalFill
	}

	/**
	 * True for the wither door inside the fairy room, seen from next door.
	 *
	 * This is the one door worth showing from outside the room it belongs to:
	 * whether it is red or green says whether anybody has picked the wither key
	 * up yet, and that is worth knowing before you walk in rather than after.
	 */
	private fun peeksFairyWitherDoor(door: DungeonDoor, current: DungeonRoom): Boolean {
		if (!showFairy.value || bloodOpened) return false
		if (door.type != DungeonDoor.Type.WITHER) return false
		val fairy = door.rooms.firstOrNull { it.type == DungeonRoom.Type.FAIRY } ?: return false
		return current.doors.any { fairy in it.rooms }
	}

	/**
	 * Whether anything reachable through [room] is still worth doing.
	 *
	 * A room that is not fully cleared is worth going to. A room that is counts
	 * only for what lies beyond it, so the search walks on through its doors —
	 * which is how a door into a finished wing of the floor disappears while a
	 * door into a finished room that leads somewhere unfinished stays.
	 *
	 * Devonian relies on the room it came from to stop the walk doubling back;
	 * dungeon floors have loops in them, so [visited] is kept as well.
	 */
	private fun isUseful(room: DungeonRoom, excluding: DungeonRoom, visited: MutableSet<DungeonRoom>): Boolean {
		if (room === excluding) return false
		if (!visited.add(room)) return false

		// The entrance holds nothing to clear and nothing to find, so the way
		// back to it is never the way to anything — but the search still walks
		// through it, in case the floor is laid out so that it has to.
		val worthwhile = room.type != DungeonRoom.Type.ENTRANCE && room.state != DungeonRoom.State.GREEN
		if (worthwhile) return true

		return room.doors.any { door ->
			door.rooms.any { it !== room && it !== excluding && isUseful(it, room, visited) }
		}
	}

	private fun draw(
		context: LevelRenderContext,
		door: DungeonDoor,
		outline: ColorModuleSetting,
		fill: ColorModuleSetting,
	) {
		val minX = door.worldX - DOOR_RADIUS + 0.5
		val minZ = door.worldZ - DOOR_RADIUS + 0.5

		// Two passes, because the outline is worth seeing through a wall and a
		// fill that does the same turns the floor into soup.
		if (outline.alpha > 0) {
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				consumers = context.bufferSource(),
				minX = minX,
				minY = DOOR_BOTTOM,
				minZ = minZ,
				maxX = minX + DOOR_RADIUS * 2,
				maxY = DOOR_TOP,
				maxZ = minZ + DOOR_RADIUS * 2,
				outlineArgb = outline.argb,
				fillArgb = 0,
				outline = true,
				fill = false,
				phase = true,
				lineWidth = lineWidth.value.toFloat(),
			)
		}

		if (fill.alpha > 0) {
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				consumers = context.bufferSource(),
				minX = minX,
				minY = DOOR_BOTTOM,
				minZ = minZ,
				maxX = minX + DOOR_RADIUS * 2,
				maxY = DOOR_TOP,
				maxZ = minZ + DOOR_RADIUS * 2,
				outlineArgb = 0,
				fillArgb = fill.argb,
				outline = false,
				fill = true,
				phase = fillThroughWalls.value,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}
}
