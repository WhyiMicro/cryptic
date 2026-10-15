package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.map.DungeonMapReader
import imicro.cryptic.mixin.LocalPlayerAccessor
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.RoundedRect
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Replaces the Spirit Leap menu with four corners, one per teammate.
 *
 * The menu is Odin's Leap Menu (BSD 3-Clause, Copyright (c) 2025 odtheking):
 * a box in each corner of the screen with the teammate's face, their name in
 * their class colour and their class under it, and a click anywhere in a
 * quarter of the screen leaping to whoever is in that corner — a quarter of the
 * screen being a much bigger thing to hit than a head in a chest. The corners
 * are filled by one of Odin's sortings, and can be pressed by key as well, per
 * corner or per class.
 *
 * Its leap announcement is left out in favour of the Announce section's, which
 * is Blade Addons' Leap Message folded in.
 *
 * The rotate fix is RSM's Leap Rotate Fix (BSD 3-Clause, Copyright (c) 2026,
 * rice.who): after a leap you face the way the teammate does, and on being
 * put into a boss room you face into it.
 *
 * The leap count is Blade Addons' (CC0, BladeMasterGabe, who credits valley):
 * on an early-enter spot in Floor 7, how many of the party have made it there.
 *
 * Classes, names and who is dead all come from [DungeonTeam], and the class
 * colours from [ClassColors], so a colour changed there changes here too.
 */
object SpiritLeapOverlay {
	private const val SORT_ODIN = 0
	private const val SORT_CLASS = 1
	private const val SORT_NAME = 2
	private const val SORT_CUSTOM = 3

	private const val MODE_CORNERS = 0

	@JvmField
	val sorting = DropdownModuleSetting(
		id = "sorting",
		label = "Sorting",
		options = listOf("Odin Sorting", "A-Z Class", "A-Z Name", "Custom sorting", "No Sorting"),
		defaultIndex = SORT_ODIN,
		description = "Which teammate goes in which corner. Odin's puts each class in its own corner where it can.",
	)

	@JvmField
	val customOrder = TextModuleSetting(
		id = "custom_order",
		label = "Custom order",
		hint = "name, name, name",
		description = "Names in the order they fill the corners, top left first. Anyone not listed goes last.",
		visibleIf = { sorting.selectedIndex == SORT_CUSTOM },
	)

	@JvmField
	val onRelease = ToggleModuleSetting(
		id = "on_release",
		label = "On key release",
		defaultValue = false,
		description = "Leaps when the click is let go rather than when it is pressed.",
	)

	@JvmField
	val rotateFix = ToggleModuleSetting(
		id = "rotate_fix",
		label = "Leap rotate fix",
		defaultValue = true,
		description = "After a leap, faces you the way the teammate you leapt to is facing, instead of wherever you were looking.",
	)

	@JvmField
	val bossRotateFix = ToggleModuleSetting(
		id = "boss_rotate_fix",
		label = "Boss enter rotate fix",
		defaultValue = true,
		description = "When you are put into a boss room, faces you into it, level, instead of wherever you were looking.",
	)

	@JvmField
	val onlyClasses = ToggleModuleSetting(
		id = "only_classes",
		label = "Only classes",
		defaultValue = false,
		description = "Writes each teammate's class in place of their name.",
	)

	@JvmField
	val colorStyle = ToggleModuleSetting(
		id = "color_style",
		label = "Color style",
		defaultValue = false,
		description = "Fills each box with the class colour and writes the name in white, instead of the name in the class colour.",
	)

	@JvmField
	val backgroundColor = ColorModuleSetting(
		id = "background_color",
		label = "Background",
		defaultRgb = 0x262626,
		supportsAlpha = true,
		defaultAlpha = 0xBF,
		description = "The box behind each teammate.",
		visibleIf = { !colorStyle.value },
	)

	@JvmField
	val cornerRadius = SliderModuleSetting(
		id = "corner_radius",
		label = "Corner radius",
		defaultValue = 9.0,
		min = 0.0,
		max = 30.0,
		step = 1.0,
		description = "How round the corners of each box are. Zero is square.",
	)

	@JvmField
	val renderScale = SliderModuleSetting(
		id = "render_scale",
		label = "Scale",
		defaultValue = 1.0,
		min = 0.1,
		max = 2.0,
		step = 0.1,
	)

	private val keybindSection = SectionModuleSetting("keybind_section", "Keybinds")

	@JvmField
	val useKeybinds = ToggleModuleSetting(
		id = "use_keybinds",
		label = "Use keybinds",
		defaultValue = true,
		description = "Leap with a key while the menu is open: 1 to 4 for the four corners to begin with.",
	)

	@JvmField
	val keybindMode = DropdownModuleSetting(
		id = "keybind_mode",
		label = "Mode",
		options = listOf("Corners", "Class"),
		defaultIndex = MODE_CORNERS,
		description = "Whether a key picks a corner of the menu, or a class wherever it ended up.",
		visibleIf = { useKeybinds.value },
	)

	private fun cornerBind(id: String, label: String, key: Int) = KeybindModuleSetting(
		id = id,
		label = label,
		visibleIf = { useKeybinds.value && keybindMode.selectedIndex == MODE_CORNERS },
		defaultKey = key,
	)

	private fun classBind(id: String, label: String) = KeybindModuleSetting(
		id = id,
		label = label,
		visibleIf = { useKeybinds.value && keybindMode.selectedIndex != MODE_CORNERS },
	)

	/**
	 * Top left, top right, bottom left, bottom right — the order the corners
	 * are numbered in, and 1 to 4 to begin with. In the menu those keys leap
	 * rather than swap the hovered item into the hotbar.
	 *
	 * The ids gained a "leap_" when the defaults came in: every profile had
	 * these saved unbound, and a saved value wins over a default, so under the
	 * old ids nobody would ever have got 1 to 4.
	 */
	private val cornerBinds = listOf(
		cornerBind("leap_top_left_key", "Top left", InputConstants.KEY_1),
		cornerBind("leap_top_right_key", "Top right", InputConstants.KEY_2),
		cornerBind("leap_bottom_left_key", "Bottom left", InputConstants.KEY_3),
		cornerBind("leap_bottom_right_key", "Bottom right", InputConstants.KEY_4),
	)

	private val classBinds = linkedMapOf(
		DungeonClass.ARCHER to classBind("archer_key", "Archer"),
		DungeonClass.BERSERK to classBind("berserk_key", "Berserker"),
		DungeonClass.HEALER to classBind("healer_key", "Healer"),
		DungeonClass.MAGE to classBind("mage_key", "Mage"),
		DungeonClass.TANK to classBind("tank_key", "Tank"),
	)

	private val leapCountSection = SectionModuleSetting("leap_count_section", "Leap count")

	@JvmField
	val leapCount = ToggleModuleSetting(
		id = "leap_count",
		label = "Player leap count",
		defaultValue = false,
		description = "On an early-enter spot in Floor 7, how many of the party are there with you.",
	)

	@JvmField
	val assumeCore = ToggleModuleSetting(
		id = "assume_core",
		label = "Assume core",
		defaultValue = false,
		description = "Counts to three on EE3, for a party that leaves someone at core.",
		visibleIf = { leapCount.value },
	)

	@JvmField
	val assumeSplitEe2 = ToggleModuleSetting(
		id = "assume_split_ee2",
		label = "Assume split EE2",
		defaultValue = false,
		description = "Counts to three on EE2, for a party that splits it.",
		visibleIf = { leapCount.value },
	)

	// ---- Announcing the leap ---------------------------------------------

	private val announceSection = SectionModuleSetting("announce_section", "Announce")

	private const val PLAYER_TOKEN = "<player>"

	/**
	 * Says in party chat who you leaped to. Leap Message's job, folded in here
	 * because it is about the same leap: ported from Blade Addons (MIT), and
	 * triggered by Hypixel's "You have teleported to Steve!", the one sign of a
	 * leap the client gets.
	 */
	@JvmField
	val announceLeap = ToggleModuleSetting(
		id = "announce_leap",
		label = "Announce leaps",
		defaultValue = false,
		description = "Tells your party who you leaped to.",
	)

	@JvmField
	val leapMessage = TextModuleSetting(
		id = "leap_message",
		label = "Message",
		defaultValue = "Leaped to $PLAYER_TOKEN!",
		maxLength = 200,
		hint = "Leaped to $PLAYER_TOKEN!",
		description = "$PLAYER_TOKEN becomes who you leaped to.",
		visibleIf = { announceLeap.value },
	)

	private val configurable: List<ModuleSetting> = listOf(
		sorting,
		customOrder,
		onRelease,
		rotateFix,
		bossRotateFix,
		onlyClasses,
		colorStyle,
		backgroundColor,
		cornerRadius,
		renderScale,
		keybindSection,
		useKeybinds,
		keybindMode,
	) + cornerBinds + classBinds.values + listOf(
		leapCountSection,
		leapCount,
		assumeCore,
		assumeSplitEe2,
		announceSection,
		announceLeap.withMore(leapMessage),
	)

	@JvmField
	val module = Module(
		id = "spirit_leap_overlay",
		name = "Spirit Leap Overlay",
		description = "A four-corner Spirit Leap",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = configurable,
	)

	/** Whether Floor 7's phase tracking is needed, which only the leap count wants. */
	val needsFloor7: Boolean get() = module.enabled && leapCount.value

	/** Whether the floor is needed at all: the leap count, and which boss room you are being put into. */
	val needsDungeon: Boolean get() = module.enabled && (leapCount.value || bossRotateFix.value)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(LeapCountElement())
		ClientTickEvents.END_CLIENT_TICK.register(::tickLeapCount)
	}

	/** A chat packet, on the client thread, before any mod has hidden it. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (!overlay) onGameMessage(message.string)
	}

	/** Hypixel's confirmation of a leap, e.g. "You have teleported to Steve!". */
	private val leapPattern = Regex("^You have teleported to (.*)!$")

	/**
	 * Hypixel rate-limits commands, and a second announcement for one leap would
	 * only earn a cooldown error, so repeats inside this window are dropped.
	 */
	private const val REPEAT_WINDOW_MS = 3_000L

	private var lastTarget = ""
	private var lastSentAt = 0L

	private fun onGameMessage(text: String) {
		if (!module.enabled || !announceLeap.value) return
		// No location check: only the Infinileap says this, and Hypixel refuses
		// to let it be used outside a dungeon.
		val target = leapPattern.find(text.replace(FORMATTING, "").trim())?.groupValues?.get(1) ?: return

		val now = System.currentTimeMillis()
		if (target == lastTarget && now - lastSentAt < REPEAT_WINDOW_MS) return
		lastTarget = target
		lastSentAt = now

		val body = leapMessage.value.replace(PLAYER_TOKEN, target).trim()
		if (body.isEmpty()) return
		// Hypixel rejects a command line over 256 characters outright.
		val command = "pc $body".take(255)

		// Previewed, the line is shown instead of sent, which is the only way to
		// read it back without a party to send it to.
		if (DebugOverrides.previewLeapMessage) {
			Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("Leap announcement would send: /$command"))
			return
		}
		Minecraft.getInstance().connection?.sendCommand(command)
	}

	/** Runs one announcement as if [target] had just been leaped to. */
	fun simulateLeap(target: String) {
		lastTarget = ""
		onGameMessage("You have teleported to $target!")
	}

	// ---- The menu -----------------------------------------------------------

	private class Teammate(val name: String, val clazz: DungeonClass, val dead: Boolean)

	/**
	 * The four corners, top left first, with null for an empty one.
	 *
	 * Built fresh each time it is asked for rather than kept: it is four names,
	 * and a list kept around is a list that can be out of date the moment
	 * someone dies.
	 */
	private fun corners(): List<Teammate?> {
		val self = Minecraft.getInstance().player?.name?.string
		val party = DungeonTeam.classes
			.filterKeys { it != self }
			.map { (name, clazz) -> Teammate(name, clazz, name in DungeonTeam.dead) }

		return when (sorting.selectedIndex) {
			SORT_ODIN -> odinSorting(party)
			SORT_CLASS -> party.sortedWith(compareBy({ it.clazz.ordinal }, { it.name })).padded()
			SORT_NAME -> party.sortedBy { it.name }.padded()
			SORT_CUSTOM -> {
				val order = customOrder.value.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
				party.sortedBy { order.indexOf(it.name.lowercase()).takeIf { at -> at >= 0 } ?: Int.MAX_VALUE }.padded()
			}
			else -> party.padded()
		}
	}

	private fun List<Teammate>.padded(): List<Teammate?> = List(CORNERS) { getOrNull(it) }

	/**
	 * Odin's sorting: every class has a corner it belongs in, and the first of
	 * a class to claim it keeps it. Whoever is left over fills the gaps in
	 * order. Berserk claims first, then tank, then the rest.
	 */
	private fun odinSorting(party: List<Teammate>): List<Teammate?> {
		val result = arrayOfNulls<Teammate>(CORNERS)
		val secondRound = ArrayDeque<Teammate>()

		for (mate in party.sortedBy { priorityOf(it.clazz) }) {
			val corner = homeCornerOf(mate.clazz)
			if (result[corner] == null) result[corner] = mate else secondRound.addLast(mate)
		}
		for (corner in result.indices) {
			if (result[corner] == null) result[corner] = secondRound.removeFirstOrNull() ?: break
		}
		return result.toList()
	}

	private fun homeCornerOf(clazz: DungeonClass): Int = when (clazz) {
		DungeonClass.ARCHER -> 0
		DungeonClass.BERSERK -> 1
		DungeonClass.HEALER -> 2
		DungeonClass.MAGE, DungeonClass.TANK -> 3
		DungeonClass.UNKNOWN -> 0
	}

	private fun priorityOf(clazz: DungeonClass): Int = when (clazz) {
		DungeonClass.BERSERK, DungeonClass.UNKNOWN -> 0
		DungeonClass.TANK -> 1
		else -> 2
	}

	/** The Spirit Leap chest, while the overlay has something to put over it. */
	private fun leapScreen(screen: Screen?): AbstractContainerScreen<*>? {
		if (!module.enabled) return null
		val chest = screen as? AbstractContainerScreen<*> ?: return null
		val title = chest.title.string
		if (title != "Spirit Leap" && title != "Teleport to Player") return null
		// With nobody to show, the chest is left alone — it still works, and
		// four empty corners would not.
		if (corners().all { it == null }) return null
		return chest
	}

	/** How far each box has grown towards being hovered, 0 to 1, per corner. */
	private val hover = FloatArray(CORNERS)
	private var lastFrame = 0L

	/** Draws the menu in place of the chest. True when it did, and the chest should not draw. */
	@JvmStatic
	fun render(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		leapScreen(screen) ?: return false
		val client = Minecraft.getInstance()
		val font = client.font

		// The dimming the chest would have drawn, since the chest is not drawn.
		context.fillGradient(0, 0, context.guiWidth(), context.guiHeight(), BACKDROP_TOP, BACKDROP_BOTTOM)

		val now = System.nanoTime()
		val step = if (lastFrame == 0L) 1f else ((now - lastFrame) / 1_000_000f / HOVER_MS).coerceIn(0f, 1f)
		lastFrame = now

		val halfW = context.guiWidth() / 2
		val halfH = context.guiHeight() / 2
		val scale = renderScale.value.toFloat()
		val corners = corners()

		for (corner in 0 until CORNERS) {
			val mate = corners[corner] ?: continue
			val column = corner % 2
			val row = corner / 2

			// The box grows out from the middle of the screen, never across it.
			val nearX = if (column == 0) halfW - CENTER_GAP else halfW + CENTER_GAP
			val nearY = if (row == 0) halfH - CENTER_GAP else halfH + CENTER_GAP
			val localX = if (column == 0) -BOX_WIDTH else 0
			val localY = if (row == 0) -BOX_HEIGHT else 0

			val hovered = (if (column == 0) mouseX < halfW else mouseX >= halfW) &&
				(if (row == 0) mouseY < halfH else mouseY >= halfH)
			hover[corner] = (hover[corner] + if (hovered) step else -step).coerceIn(0f, 1f)
			val grow = GROW * hover[corner]

			val pose = context.pose()
			pose.pushMatrix()
			pose.translate(nearX.toFloat(), nearY.toFloat())
			pose.scale(
				scale * (BOX_WIDTH + grow * 2f) / BOX_WIDTH,
				scale * (BOX_HEIGHT + grow * 2f) / BOX_HEIGHT,
			)

			val classArgb = 0xFF000000.toInt() or ClassColors.getClassColor(mate.clazz)
			val background = backgroundColor.argb
			RoundedRect.fill(
				context,
				localX,
				localY,
				localX + BOX_WIDTH,
				localY + BOX_HEIGHT,
				if (colorStyle.value) classArgb else background,
				cornerRadius.value.toFloat(),
			)

			skinOf(mate.name)?.let { PlayerFaceExtractor.extractRenderState(context, it, localX + FACE_INSET, localY + FACE_INSET, FACE) }

			val textX = localX + FACE_INSET + FACE + TEXT_GAP
			context.text(
				font,
				if (onlyClasses.value) mate.clazz.name else mate.name,
				textX,
				localY + NAME_Y,
				// White on the class colour, the class colour on the plain box.
				if (colorStyle.value) WHITE else classArgb,
			)
			if (!onlyClasses.value || mate.dead) {
				context.text(
					font,
					if (mate.dead) "DEAD" else mate.clazz.name.lowercase(),
					textX,
					localY + CLASS_Y,
					if (mate.dead) DEAD_RED else WHITE,
				)
			}

			pose.popMatrix()
		}
		return true
	}

	/** A teammate's skin, and where it was found, for the menu and `/cryptic debug leap`. */
	private fun skinSource(name: String): Pair<PlayerSkin, String>? {
		val client = Minecraft.getInstance()
		val connection = client.connection ?: return null
		// The player standing in the world, by their own id: the one answer
		// that cannot belong to anybody else.
		client.level?.players()?.firstOrNull { it.name.string == name }?.let { player ->
			connection.getPlayerInfo(player.uuid)?.let { return it.skin to "player entity" }
		}
		connection.getPlayerInfo(name)?.let { return it.skin to "player entry" }
		DungeonTeam.tabSkins[name]?.let { return it to "tab list row" }
		return null
	}

	/**
	 * A teammate's skin, or none at all.
	 *
	 * Odin falls back to your own face for a teammate it cannot find, which is
	 * how the menu ended up showing your head on somebody else's box: on
	 * Hypixel a teammate's own player entry is often not sent at all. Their
	 * tab list row has their head on it whether or not, so that is asked next,
	 * and a box with no face is better than one with the wrong face.
	 */
	private fun skinOf(name: String): PlayerSkin? = skinSource(name)?.first

	/** For `/cryptic debug leap`: which corner holds whom, and whose face they are drawn with. */
	fun describe(): List<String> {
		val corners = corners()
		if (corners.all { it == null }) return listOf("§8[Cryptic] §7Spirit Leap: no teammates read from the tab list.")
		return corners.mapIndexedNotNull { index, mate ->
			mate ?: return@mapIndexedNotNull null
			val source = skinSource(mate.name)
			"§8[Cryptic] §7Corner ${index + 1}: §f${mate.name} §7(${mate.clazz.name.lowercase()}) face from §f" +
				(source?.let { "${it.second}§7, ${it.first.body().texturePath()}" } ?: "nowhere")
		}
	}

	/** A click on the menu. Every click is taken, since the chest under it is not on screen. */
	@JvmStatic
	fun handleMouseClick(screen: Screen, mouseX: Double, mouseY: Double): Boolean {
		val chest = leapScreen(screen) ?: return false
		if (!onRelease.value) leapToCorner(chest, mouseX, mouseY)
		return true
	}

	@JvmStatic
	fun handleMouseRelease(screen: Screen, mouseX: Double, mouseY: Double): Boolean {
		val chest = leapScreen(screen) ?: return false
		if (onRelease.value) leapToCorner(chest, mouseX, mouseY)
		return true
	}

	/**
	 * A key pressed on the menu. Only a key bound here is taken: every other
	 * one reaches the chest, so Escape and the inventory key still close it.
	 */
	@JvmStatic
	fun handleKeyPress(screen: Screen, key: Int): Boolean {
		if (!useKeybinds.value) return false
		val chest = leapScreen(screen) ?: return false
		val corners = corners()

		val target = if (keybindMode.selectedIndex == MODE_CORNERS) {
			val corner = cornerBinds.indexOfFirst { it.matches(key) }
			if (corner < 0) return false
			corners[corner]
		} else {
			val clazz = classBinds.entries.firstOrNull { it.value.matches(key) }?.key ?: return false
			corners.firstOrNull { it?.clazz == clazz }
		}

		target?.let { leapTo(chest, it) }
		return true
	}

	private fun leapToCorner(chest: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double) {
		val corner = (if (mouseY >= chest.height / 2.0) 2 else 0) + (if (mouseX >= chest.width / 2.0) 1 else 0)
		corners().getOrNull(corner)?.let { leapTo(chest, it) }
	}

	/**
	 * Clicks the teammate's head in the real chest, which is the leap.
	 *
	 * Found by the name on the head rather than by where the head is, because
	 * Hypixel orders them its own way. The name is after the rank, if there is
	 * one, and the colour codes are not part of it.
	 */
	private fun leapTo(chest: AbstractContainerScreen<*>, mate: Teammate) {
		if (mate.dead) {
			say("§cThis player is dead, can't leap.")
			return
		}

		val slots = chest.menu.slots
		val slot = (HEAD_SLOTS_START until minOf(HEAD_SLOTS_END, slots.size)).firstOrNull { index ->
			slots[index].item.hoverName.string.replace(FORMATTING, "").substringAfter(' ').equals(mate.name, ignoreCase = true)
		} ?: return

		val client = Minecraft.getInstance()
		val player = client.player ?: return
		noteLeapFacing(mate.name)
		client.gameMode?.handleContainerInput(chest.menu.containerId, slot, 0, ContainerInput.PICKUP, player)
		say("§7Teleporting to §f${mate.name}§7.")
	}

	/** How long after the click a teleport still counts as the leap. RSM's default. */
	private const val ROTATE_TIMEOUT_TICKS = 10

	/** The way the teammate just leapt to was facing, as (pitch, yaw), until the leap lands. */
	private var leapFacing: Pair<Float, Float>? = null
	private var leapClickedAt = 0L

	/** Whether [leapFacing] is a boss room's rather than a teammate's, so the right toggle is asked. */
	private var facingFromBoss = false

	private fun noteLeapFacing(name: String) {
		if (!rotateFix.value) return
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		leapFacing = facingOf(name) ?: return
		leapClickedAt = level.gameTime
		facingFromBoss = false
	}

	/**
	 * Where each floor's boss fight puts you, and the way into the room from
	 * there, as RSM has them. Floor and Master Mode share a room.
	 */
	private val BOSS_ENTRANCES = mapOf(
		1 to (Vec3(-42.5, 71.5, 34.5) to 180f),
		2 to (Vec3(-7.5, 69.5, -28.5) to 0f),
		3 to (Vec3(1.5, 69.5, -27.55) to 0f),
		4 to (Vec3(5.5, 69.5, -20.5) to 0f),
		5 to (Vec3(5.5, 69.5, 0.5) to 0f),
		7 to (Vec3(73.5, 221.5, 14.5) to 0f),
	)

	/**
	 * A teleport about to be applied. When it lands exactly on the floor's
	 * boss entrance, the room's facing is waiting for [afterServerTeleport].
	 * Only on the client thread's pass, since the player is read.
	 */
	@JvmStatic
	fun beforeServerTeleport(packet: ClientboundPlayerPositionPacket) {
		val client = Minecraft.getInstance()
		if (!client.isSameThread || !module.enabled || !bossRotateFix.value || !DungeonLocation.inDungeon) return
		val player = client.player ?: return
		val level = client.level ?: return
		val (entrance, yaw) = BOSS_ENTRANCES[DungeonLocation.floor] ?: return
		val landing = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player), packet.change(), packet.relatives())
		if (landing.position() != entrance) return
		leapFacing = 0f to yaw
		leapClickedAt = level.gameTime
		facingFromBoss = true
	}

	/**
	 * Where [name] is looking: from the world when they are loaded, otherwise
	 * level and the way their map marker points. The map pairs markers with
	 * living teammates in tab order, as the dungeon map does.
	 */
	private fun facingOf(name: String): Pair<Float, Float>? {
		val client = Minecraft.getInstance()
		client.level?.players()?.firstOrNull { it.name.string == name }?.let { return it.xRot to it.yRot }
		val self = client.player?.name?.string
		val living = DungeonTeam.classes.keys.filter { it != self && it !in DungeonTeam.dead }
		val marker = DungeonMapReader.markers.getOrNull(living.indexOf(name)) ?: return null
		return 0f to marker.yaw
	}

	/**
	 * The leap landing. Hypixel's leap teleport leaves your rotation as it
	 * was, so you arrive looking wherever you were looking before; this turns
	 * you the way your teammate faces instead. Only a teleport that arrives
	 * soon after the click and changes nothing about the rotation is taken
	 * for the leap — anything else is the server turning you on purpose.
	 */
	@JvmStatic
	fun afterServerTeleport(packet: ClientboundPlayerPositionPacket) {
		val facing = leapFacing ?: return
		leapFacing = null
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val level = client.level ?: return
		val enabled = if (facingFromBoss) bossRotateFix.value else rotateFix.value
		if (!module.enabled || !enabled || level.gameTime - leapClickedAt > ROTATE_TIMEOUT_TICKS) return

		val relatives = packet.relatives()
		val change = packet.change()
		if (Relative.X_ROT !in relatives || Relative.Y_ROT !in relatives) return
		if (change.xRot() != 0f || change.yRot() != 0f) return

		val (xRot, yRot) = facing
		player.xRot = xRot
		player.yRot = yRot
		player.xRotO = xRot
		player.yRotO = yRot
		// As RSM does: the client takes the new rotation as already sent, so
		// turning on the spot is not reported on its own.
		(player as LocalPlayerAccessor).`cryptic$setServerXRot`(xRot)
		(player as LocalPlayerAccessor).`cryptic$setServerYRot`(yRot)
	}

	private fun say(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] $text"))
	}

	// ---- The leap count -----------------------------------------------------

	/** Which early-enter spot you are on, 2 to 5, or -1 when not on one. */
	@Volatile
	private var spot = -1

	@Volatile
	private var leapedCount = 0

	private fun tickLeapCount(client: Minecraft) {
		val player = client.player
		val level = client.level
		if (!module.enabled || !leapCount.value || player == null || level == null ||
			!DungeonLocation.inFloor7 || !Floor7.inBossRoom
		) {
			spot = -1
			return
		}

		spot = spotOf(player.position())
		if (spot < 1) {
			leapedCount = 0
			return
		}
		leapedCount = level.getEntities(player, REGIONS[spot - 1]) { isRealPlayer(it) }.size
	}

	/** Blade's spots, checked most specific first. */
	private fun spotOf(position: Vec3): Int = when {
		HEE2_BOX.contains(position) || EE2_BOX.contains(position) -> 2
		EE3_BOX.contains(position) -> 3
		CORE_BOX.contains(position) -> 4
		RELIC_BOX.contains(position) && Floor7.phase != 5 -> 5
		else -> -1
	}

	private fun maxCount(): Int = when {
		spot == 2 && assumeSplitEe2.value -> 3
		spot == 3 && assumeCore.value -> 3
		else -> CORNERS
	}

	/**
	 * Whether an entity is a player rather than one of Hypixel's NPCs.
	 *
	 * Blade's test, and Blade calls it a hack: an NPC is drawn as a player but
	 * has no proper tab list entry, while every real player has one with a name
	 * that has no spaces in it.
	 */
	private fun isRealPlayer(entity: Entity): Boolean {
		if (entity !is Player) return false
		val info = Minecraft.getInstance().connection?.getPlayerInfo(entity.uuid) ?: return false
		val name = info.profile.name()
		return name.isNotEmpty() && ' ' !in name
	}

	/** Blade's own format: blue once it is one short of full or better, dark red until then. */
	private fun leapCountText(count: Int, max: Int): String =
		(if (max - count <= 1) "§9" else "§4") + count + "§9/" + max + " Players Leaped"

	private class LeapCountElement: HudElement("leap_count", "Leap Count", 0.45, 0.6) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int = COUNT_WIDTH
		override val height: Int = COUNT_HEIGHT

		override fun isVisible(): Boolean = module.enabled && leapCount.value && spot >= 1

		override fun showInEditor(): Boolean = module.enabled && leapCount.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, leapCountText(leapedCount, maxCount()))

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, leapCountText(2, CORNERS))

		private fun draw(context: GuiGraphicsExtractor, text: String) {
			context.text(
				font,
				text,
				(COUNT_WIDTH - font.width(text)) / 2,
				(COUNT_HEIGHT - font.lineHeight) / 2 + 1,
				WHITE,
			)
		}
	}

	// ---- Numbers ------------------------------------------------------------

	private const val CORNERS = 4

	/** Odin's box, and how far each one stands off the middle of the screen. */
	private const val BOX_WIDTH = 200
	private const val BOX_HEIGHT = 75
	private const val CENTER_GAP = 24

	/** The face is three quarters of the box's height, inset from its corner. */
	private const val FACE = (BOX_HEIGHT * 0.76).toInt()
	private const val FACE_INSET = 9
	private const val TEXT_GAP = 6
	private const val NAME_Y = (BOX_HEIGHT / 2.5).toInt()
	private const val CLASS_Y = (BOX_HEIGHT / 1.7).toInt()

	/** How far a hovered box grows on each side, and how long it takes to. */
	private const val GROW = 5f
	private const val HOVER_MS = 200f

	/** The five heads Hypixel puts in the menu, in the chest's middle row. */
	private const val HEAD_SLOTS_START = 11
	private const val HEAD_SLOTS_END = 16

	/** Vanilla's in-game screen dimming, the same the terminal overlay paints. */
	private const val BACKDROP_TOP = 0xC0101010.toInt()
	private const val BACKDROP_BOTTOM = 0xD0101010.toInt()

	private const val WHITE = 0xFFFFFFFF.toInt()
	private const val DEAD_RED = 0xFFFF5555.toInt()

	/** Blade's element, whose width is what the count is centred in. */
	private const val COUNT_WIDTH = 110
	private const val COUNT_HEIGHT = 10

	private val FORMATTING = Regex("§.")

	/** The Floor 7 boss room's early-enter spots and the area each one counts. Blade's numbers. */
	private val REGIONS = arrayOf(
		AABB(91.0, 105.0, 46.0, 111.0, 127.0, 123.0),
		AABB(17.0, 106.0, 121.0, 108.0, 145.0, 143.0),
		AABB(-2.0, 106.0, 51.0, 20.0, 145.0, 128.0),
		AABB(20.0, 26.0, 29.0, 191.0, 145.0, 58.0),
		AABB(3.0, 5.0, 0.0, 128.0, 48.0, 140.0),
	)
	private val EE2_BOX = AABB(57.0, 108.0, 130.0, 59.0, 110.0, 132.0)
	private val HEE2_BOX = AABB(59.0, 132.0, 138.0, 62.0, 133.0, 140.0)
	private val EE3_BOX = AABB(1.0, 108.0, 103.0, 3.0, 110.0, 105.0)
	private val CORE_BOX = AABB(53.5, 114.0, 49.5, 55.5, 116.0, 51.5)
	private val RELIC_BOX = AABB(51.5, 3.0, 73.5, 57.5, 8.0, 79.5)
}
