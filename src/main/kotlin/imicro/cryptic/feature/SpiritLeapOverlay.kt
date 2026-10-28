package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
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
import imicro.cryptic.render.RoundedRect
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.phys.Vec3

/**
 * Replaces the Spirit Leap menu with four corners, one per teammate: Odin's Leap Menu, with the
 * teammate's face, name in their class colour and class, and a click anywhere in that quarter of
 * the screen leaping to them. Corners are filled by Odin's sortings and can be pressed by key, per
 * corner or per class.
 *
 * The leap announcement is Blade Addons' Leap Message. The rotate fix is RSM's Leap Rotate Fix
 * (face the way your teammate does; face into the boss room on entry). Classes and colours come from
 * [DungeonTeam] and [ClassColors].
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
	val colorOpacity = SliderModuleSetting(
		id = "color_opacity",
		label = "Opacity",
		unit = "%",
		defaultValue = 100.0,
		min = 10.0,
		max = 100.0,
		step = 5.0,
		description = "How solid the class colour is, so the teammate views behind show through.",
		visibleIf = { colorStyle.value },
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
	 * Top left, top right, bottom left, bottom right, 1 to 4 by default; in the menu those keys
	 * leap. The ids gained "leap_" when defaults were added, since saved unbound values would
	 * otherwise win.
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

	private val announceSection = SectionModuleSetting("announce_section", "Announce")

	private const val PLAYER_TOKEN = "<player>"

	/**
	 * Says in party chat who you leaped to, from Hypixel's "You have teleported to Steve!". Blade
	 * Addons' Leap Message.
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

	private val previewSection = SectionModuleSetting(
		"preview_section", "Teammate views",
		visibleIf = { imicro.cryptic.gui.Restrictions.allows(imicro.cryptic.gui.Restriction.SEMI_LEGIT) },
	)

	@JvmField
	val preview = ToggleModuleSetting(
		id = "pov_preview",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Show their view",
		defaultValue = true,
		description = "Each corner shows what that teammate sees, so you can tell where a leap lands. " +
			"Renders the world once more per view while the menu is open.",
	)

	@JvmField
	val previewResolution = SliderModuleSetting(
		id = "pov_resolution",
		label = "Resolution",
		unit = "%",
		defaultValue = 50.0,
		min = 25.0,
		max = 100.0,
		step = 5.0,
		description = "How sharp each view is, against a quarter of your screen. Lower is cheaper.",
		visibleIf = { preview.value },
	)

	@JvmField
	val previewsPerFrame = SliderModuleSetting(
		id = "pov_per_frame",
		label = "Views per frame",
		defaultValue = 1.0,
		min = 1.0,
		max = 4.0,
		step = 1.0,
		description = "At most this many views are redrawn in one frame; the others keep their last picture.",
		visibleIf = { preview.value },
	)

	@JvmField
	val previewRate = SliderModuleSetting(
		id = "pov_rate",
		label = "Updates",
		unit = "/s",
		defaultValue = 20.0,
		min = 5.0,
		max = 60.0,
		step = 5.0,
		description = "How often each view is redrawn. The cost stays the same whatever your frame rate; lower is cheaper.",
		visibleIf = { preview.value },
	)

	@JvmField
	val previewEntities = ToggleModuleSetting(
		id = "pov_entities",
		label = "Players and mobs",
		defaultValue = false,
		description = "Draws players, mobs and particles in the views. Off, only the world is drawn, which is much cheaper and enough to see where someone is.",
		visibleIf = { preview.value },
	)

	private val configurable: List<ModuleSetting> = listOf(
		sorting,
		customOrder,
		onRelease,
		rotateFix,
		bossRotateFix,
		onlyClasses,
		colorStyle.withMore(colorOpacity),
		backgroundColor,
		cornerRadius,
		renderScale,
		previewSection,
		preview.withMore(previewResolution, previewRate, previewsPerFrame, previewEntities),
		keybindSection,
		useKeybinds,
		keybindMode,
	) + cornerBinds + classBinds.values + listOf(
		announceSection,
		announceLeap.withMore(leapMessage),
	)

	@JvmField
	val module = Module(
		id = "spirit_leap_overlay",
		name = "Spirit Leap Features",
		description = "Leap menu and views",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = configurable,
	)


	val needsDungeon: Boolean get() = module.enabled && bossRotateFix.value

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientTickEvents.END_CLIENT_TICK.register { tickDebug() }
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (!overlay) onGameMessage(message.string)
	}

	private val leapPattern = Regex("^You have teleported to (.*)!$")

	/** Hypixel rate-limits commands, so a repeat for the same leap is dropped. */
	private const val REPEAT_WINDOW_MS = 3_000L

	private var lastTarget = ""
	private var lastSentAt = 0L

	private fun onGameMessage(text: String) {
		if (!module.enabled || !announceLeap.value) return
		// No location check: only the Infinileap says this, and it only works in dungeons.
		val target = leapPattern.find(text.replace(FORMATTING, "").trim())?.groupValues?.get(1) ?: return

		val now = System.currentTimeMillis()
		if (target == lastTarget && now - lastSentAt < REPEAT_WINDOW_MS) return
		lastTarget = target
		lastSentAt = now

		val body = leapMessage.value.replace(PLAYER_TOKEN, target).trim()
		if (body.isEmpty()) return
		// Hypixel rejects command lines over 256 characters.
		val command = "pc $body".take(255)

		// Previewed, the line is shown instead of sent.
		if (DebugOverrides.previewLeapMessage) {
			Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("Leap announcement would send: /$command"))
			return
		}
		Minecraft.getInstance().connection?.sendCommand(command)
	}

	fun simulateLeap(target: String) {
		lastTarget = ""
		onGameMessage("You have teleported to $target!")
	}

	private class Teammate(val name: String, val clazz: DungeonClass, val dead: Boolean)

	// ---- /cryptic debug leap_overlay ------------------------------------------

	/** Four people from the lobby, shown instead of the party while the debug menu is open. */
	private var debugParty: List<Teammate>? = null

	/** The client-only screen the debug menu draws over; nothing in it reaches the server. */
	private var debugScreen: Screen? = null

	private const val DEBUG_TITLE = "Spirit Leap"

	/**
	 * Opens the menu with four random players from the lobby as the party, in your own settings,
	 * anywhere. Players in view come first, so their teammate views have someone to show. Returns
	 * what to say.
	 */
	fun openDebug(): String {
		val client = Minecraft.getInstance()
		val player = client.player ?: return "§cNot in a world."
		val self = player.name.string
		val nearby = client.level?.players().orEmpty().map { it.name.string }.filter { it != self && it.isNotBlank() }.shuffled()
		val listed = client.connection?.onlinePlayers.orEmpty().map { it.profile.name() }
			.filter { it != self && it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' } && it !in nearby }
			.shuffled()
		val names = (nearby + listed).distinct().take(CORNERS)
		if (names.isEmpty()) return "§cThere's nobody else in this lobby to show."
		val classes = DungeonClass.entries.filter { it != DungeonClass.UNKNOWN }.shuffled()
		debugParty = names.mapIndexed { index, name -> Teammate(name, classes[index % classes.size], false) }
		val menu = net.minecraft.world.inventory.ChestMenu.sixRows(DEBUG_CONTAINER_ID, player.inventory)
		val screen = net.minecraft.client.gui.screens.inventory.ContainerScreen(menu, player.inventory, Component.literal(DEBUG_TITLE))
		debugScreen = screen
		// Next tick: the command runs while chat is closing, which would close this too.
		openNextTick = true
		val inView = names.count { it in nearby }
		return "§7Showing §f${names.joinToString(", ")}§7. ${if (inView == 0) "Nobody is in view, so there are no teammate views." else "$inView in view."} Clicks don't leap."
	}

	/** Out of the range Hypixel uses, so it can't match a real menu. */
	private const val DEBUG_CONTAINER_ID = 1_000

	private var openNextTick = false

	private fun tickDebug() {
		val screen = debugScreen ?: return
		val client = Minecraft.getInstance()
		if (openNextTick) {
			openNextTick = false
			client.gui.setScreen(screen)
			return
		}
		if (client.gui.screen() === screen) return
		debugScreen = null
		debugParty = null
	}

	private fun isDebug(screen: Screen): Boolean = debugScreen != null && screen === debugScreen

	/**
	 * The four corners, top left first, null for empty. Built fresh each time, since four names are
	 * cheap and a kept list goes stale when someone dies.
	 */
	private fun corners(): List<Teammate?> {
		val self = Minecraft.getInstance().player?.name?.string
		val party = debugParty ?: DungeonTeam.classes
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
	 * Odin's sorting: each class has a home corner, and the first of a class to claim it keeps it;
	 * the rest fill the gaps in order. Berserk claims first, then tank, then the others.
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

	/** The Spirit Leap chest, while the overlay has something to draw. */
	private fun leapScreen(screen: Screen?): AbstractContainerScreen<*>? {
		val chest = screen as? AbstractContainerScreen<*> ?: return null
		if (!module.enabled && !isDebug(chest)) return null
		val title = chest.title.string
		if (title != "Spirit Leap" && title != "Teleport to Player") return null
		// With nobody to show, the chest is left as it is.
		if (corners().all { it == null }) return null
		return chest
	}

	/** How far each box has grown toward hovered, 0 to 1. */
	private val hover = FloatArray(CORNERS)
	private var lastFrame = 0L

	/** Draws the menu instead of the chest. True when it did. */
	@JvmStatic
	fun render(screen: Screen, context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		leapScreen(screen) ?: return false
		val client = Minecraft.getInstance()
		val font = client.font

		// The dimming the chest would have drawn.
		context.fillGradient(0, 0, context.guiWidth(), context.guiHeight(), BACKDROP_TOP, BACKDROP_BOTTOM)
		LeapPreview.submit(context, corners().map { it?.takeIf { mate -> !mate.dead }?.name })

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

			// Boxes grow out from the screen's middle, never across it.
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
				if (colorStyle.value) net.minecraft.util.ARGB.color((colorOpacity.value / 100.0 * 255).toInt(), classArgb) else background,
				cornerRadius.value.toFloat(),
			)

			skinOf(mate.name)?.let { PlayerFaceExtractor.extractRenderState(context, it, localX + FACE_INSET, localY + FACE_INSET, FACE) }

			val textX = localX + FACE_INSET + FACE + TEXT_GAP
			context.text(
				font,
				if (onlyClasses.value) mate.clazz.name else mate.name,
				textX,
				localY + NAME_Y,
				// White on the class colour, or the class colour on a plain box.
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

	/** A teammate's skin and where it was found, for the menu and `/cryptic debug leap`. */
	private fun skinSource(name: String): Pair<PlayerSkin, String>? {
		val client = Minecraft.getInstance()
		val connection = client.connection ?: return null
		// The player in the world, by their own id: the one answer that can't be someone else's.
		client.level?.players()?.firstOrNull { it.name.string == name }?.let { player ->
			connection.getPlayerInfo(player.uuid)?.let { return it.skin to "player entity" }
		}
		connection.getPlayerInfo(name)?.let { return it.skin to "player entry" }
		DungeonTeam.tabSkins[name]?.let { return it to "tab list row" }
		return null
	}

	/**
	 * A teammate's skin, or none. On Hypixel a teammate's player entry often isn't sent, so their
	 * tab row's head is tried next; no face beats the wrong one (Odin falls back to yours).
	 */
	private fun skinOf(name: String): PlayerSkin? = skinSource(name)?.first

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

	/** A click on the menu. Every click is taken, since the chest underneath isn't shown. */
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

	/** A key on the menu. Only bound keys are taken, so Escape and the inventory key still close it. */
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
	 * Clicks the teammate's head in the real chest. Found by the name on the head (after the rank,
	 * without colour codes), since Hypixel orders them its own way.
	 */
	private fun leapTo(chest: AbstractContainerScreen<*>, mate: Teammate) {
		if (isDebug(chest)) {
			say("§7Would leap to §f${mate.name}§7 (debug menu).")
			Minecraft.getInstance().gui.setScreen(null)
			return
		}
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

	/** How long after the click a teleport still counts as the leap (RSM's default). */
	private const val ROTATE_TIMEOUT_TICKS = 10

	/** The leaped-to teammate's facing, as (pitch, yaw), until the leap lands. */
	private var leapFacing: Pair<Float, Float>? = null
	private var leapClickedAt = 0L

	/** Whether [leapFacing] is a boss room's, so the right toggle is checked. */
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
	 * Where each floor's boss fight puts you and the way into the room, as RSM has them. Floor and
	 * Master Mode share a room.
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
	 * A teleport about to land. Exactly on the floor's boss entrance, the room's facing waits for
	 * [afterServerTeleport]. Client thread only.
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
	 * Where [name] is looking: from the world when loaded, otherwise level and their map marker's
	 * direction (markers pair with living teammates in tab order).
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
	 * The leap landing. Hypixel leaves your rotation unchanged, so you're turned to face the way
	 * your teammate does. Only a teleport soon after the click that doesn't change rotation counts
	 * as the leap; anything else is the server turning you on purpose.
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
		// As RSM does: the new rotation is marked as already sent, so the turn isn't reported on
		// its own.
		(player as LocalPlayerAccessor).`cryptic$setServerXRot`(xRot)
		(player as LocalPlayerAccessor).`cryptic$setServerYRot`(yRot)
	}

	private fun say(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] $text"))
	}

	private const val CORNERS = 4

	/** Odin's box size, and how far each stands off the screen's middle. */
	private const val BOX_WIDTH = 200
	private const val BOX_HEIGHT = 75
	private const val CENTER_GAP = 24

	/** The face is three quarters of the box height, inset from its corner. */
	private const val FACE = (BOX_HEIGHT * 0.76).toInt()
	private const val FACE_INSET = 9
	private const val TEXT_GAP = 6
	private const val NAME_Y = (BOX_HEIGHT / 2.5).toInt()
	private const val CLASS_Y = (BOX_HEIGHT / 1.7).toInt()

	/** How far a hovered box grows each side, and how long it takes. */
	private const val GROW = 5f
	private const val HOVER_MS = 200f

	/** The five heads Hypixel puts in the chest's middle row. */
	private const val HEAD_SLOTS_START = 11
	private const val HEAD_SLOTS_END = 16

	/** Vanilla's in-game screen dimming. */
	private const val BACKDROP_TOP = 0xC0101010.toInt()
	private const val BACKDROP_BOTTOM = 0xD0101010.toInt()

	private const val WHITE = 0xFFFFFFFF.toInt()
	private const val DEAD_RED = 0xFFFF5555.toInt()

	private val FORMATTING = Regex("§.")

}
