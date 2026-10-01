package imicro.cryptic.feature

import imicro.cryptic.debug.InventoryWatch
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.RoomSecrets
import imicro.cryptic.dungeon.map.DungeonRoom
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
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.ARGB
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.concurrent.ConcurrentHashMap

/**
 * The things that make finding a dungeon's secrets less fiddly.
 *
 * Mostly ported from NoammAddons' `Secrets` (CC0, Noamm9): closing a secret
 * chest for you, growing the hitboxes of the blocks secrets hide behind, and
 * marking what you just clicked so nobody goes back for it. The locked-chest
 * colour is Odin's `SecretClicked` (BSD 3-Clause, Copyright (c) 2025 odtheking).
 *
 * The hitboxes are the part worth understanding, and the part where a full
 * block is the wrong answer. A lever and a mushroom get one, but a button
 * keeps its depth and only grows across the face it is stuck to — a full-block
 * button would be clickable from inside the wall behind it. A skull only grows
 * if it is one of the two Hypixel actually hides secrets in, because every
 * other skull in a dungeon is scenery. Only this client's idea of the shape
 * changes; the server still decides whether an interaction lands.
 */
object Secrets {
	private const val OUTLINE = 0
	private const val FILL = 1
	private const val FILL_OUTLINE = 2

	/**
	 * The skull owners Hypixel puts secrets behind: wither essence and the
	 * redstone key. NoammAddons' list.
	 */
	private val secretSkullOwners = setOf(
		"2865274b-3097-394e-8149-ec629c72d850",
		"e0f3e929-869e-3dca-9504-54c666ee6f23",
		"fed95410-aba1-39df-9b95-1d4f361eb66e",
	)

	/**
	 * Levers on Floor 7's Goldor terminals, which are not secrets and which a
	 * grown hitbox would make easy to open by walking past. NoammAddons' list.
	 */
	private val terminalLevers = setOf(
		BlockPos(61, 136, 142), BlockPos(60, 136, 142), BlockPos(59, 136, 142),
		BlockPos(62, 135, 142), BlockPos(61, 135, 142), BlockPos(59, 135, 142),
		BlockPos(58, 135, 142), BlockPos(62, 134, 142), BlockPos(61, 134, 142),
		BlockPos(59, 134, 142), BlockPos(58, 134, 142), BlockPos(61, 133, 142),
		BlockPos(60, 133, 142), BlockPos(59, 133, 142),
	)

	/**
	 * Everything a secret can give you, which is a short and fixed list. The
	 * healing potion is named several ways across floors, so all of them count.
	 */
	private val secretItems = listOf(
		"Healing Potion" to setOf(
			"Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion",
			"Healing Potion VIII Splash Potion", "Healing VIII Splash Potion",
			"Healing 8 Splash Potion",
		),
		"Decoy" to setOf("Decoy"),
		"Inflatable Jerry" to setOf("Inflatable Jerry"),
		"Spirit Leap" to setOf("Spirit Leap"),
		"Trap" to setOf("Trap"),
		"Training Weights" to setOf("Training Weights"),
		"Defuse Kit" to setOf("Defuse Kit"),
		"Dungeon Chest Key" to setOf("Dungeon Chest Key"),
		"Treasure Talisman" to setOf("Treasure Talisman"),
		"Revive Stone" to setOf("Revive Stone"),
		"Architect's First Draft" to setOf("Architect's First Draft"),
		"Secret Dye" to setOf("Secret Dye"),
	)

	private val soundOptions = listOf(
		"Experience Orb" to SoundEvents.EXPERIENCE_ORB_PICKUP,
		"Pling" to SoundEvents.NOTE_BLOCK_PLING.value(),
		"Bell" to SoundEvents.NOTE_BLOCK_BELL.value(),
		"Harp" to SoundEvents.NOTE_BLOCK_HARP.value(),
		"Button Click" to SoundEvents.UI_BUTTON_CLICK.value(),
		"Amethyst Chime" to SoundEvents.AMETHYST_BLOCK_CHIME,
		"Level Up" to SoundEvents.PLAYER_LEVELUP,
		"Anvil Land" to SoundEvents.ANVIL_LAND,
	)

	@JvmField
	val autoCloseChest = ToggleModuleSetting(
		id = "auto_close_chest",
		label = "Auto close chest",
		description = "Shuts a secret chest as it opens.",
	)

	@JvmField
	val movableCounter = ToggleModuleSetting(
		id = "movable_counter",
		label = "Moveable secrets counter",
		description = "Takes the room's secret count out of the action bar and puts it where you place it: " +
			"red with none found, yellow partway, green once they all are.",
	)

	/** Whether the action bar's count is being moved into the counter, which [RoomSecrets] asks. */
	val movesCounter: Boolean get() = module.enabled && movableCounter.value

	// ---- Secret hitboxes -------------------------------------------------

	private val hitboxSection = SectionModuleSetting(id = "hitbox_section", label = "Secret hitboxes")

	@JvmField
	val hitboxesOnlyInDungeons = ToggleModuleSetting(
		id = "hitboxes_only_in_dungeons",
		label = "Only in dungeons",
		defaultValue = true,
		description = "Leaves every block alone outside a dungeon, where a grown hitbox is only in the way.",
	)

	@JvmField
	val leverHitbox = ToggleModuleSetting(
		id = "lever_hitbox",
		label = "Lever",
		description = "Gives levers a full block hitbox. Floor 7's terminal levers are left alone.",
	)

	@JvmField
	val buttonHitbox = ToggleModuleSetting(
		id = "button_hitbox",
		label = "Button",
		description = "Stretches a button across the face it is on.",
	)

	@JvmField
	val skullHitbox = ToggleModuleSetting(
		id = "skull_hitbox",
		label = "Skulls",
		description = "Full hitboxes for essence and key skulls.",
	)

	@JvmField
	val mushroomHitbox = ToggleModuleSetting(
		id = "mushroom_hitbox",
		label = "Mushroom",
		description = "Gives mushrooms a full block hitbox.",
	)

	// ---- Secret clicked --------------------------------------------------

	private val clickedSection = SectionModuleSetting(id = "clicked_section", label = "Secret clicked")

	@JvmField
	val highlightClicked = ToggleModuleSetting(
		id = "highlight_clicked",
		label = "Highlight clicked secret",
		defaultValue = true,
		description = "Marks a secret you have taken — clicked, picked up, or a bat you shot down.",
	)

	@JvmField
	val highlightTime = SliderModuleSetting(
		id = "highlight_time",
		label = "Highlight time (ms)",
		defaultValue = 2000.0,
		min = 500.0,
		max = 5000.0,
		step = 100.0,
		description = "How long the mark stays up.",
		visibleIf = { highlightClicked.value },
	)

	@JvmField
	val clickedStyle = DropdownModuleSetting(
		id = "clicked_style",
		label = "Render style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = FILL_OUTLINE,
		visibleIf = { highlightClicked.value },
	)

	@JvmField
	val clickedFillColor = ColorModuleSetting(
		id = "clicked_fill_color",
		label = "Fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x50,
		visibleIf = { highlightClicked.value && clickedStyle.selectedIndex != OUTLINE },
	)

	@JvmField
	val clickedOutlineColor = ColorModuleSetting(
		id = "clicked_outline_color",
		label = "Outline",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		visibleIf = { highlightClicked.value && clickedStyle.selectedIndex != FILL },
	)

	@JvmField
	val lockedFillColor = ColorModuleSetting(
		id = "locked_fill_color",
		label = "Locked fill",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x50,
		description = "What a chest is marked in once Hypixel says it is locked.",
		visibleIf = { highlightClicked.value && clickedStyle.selectedIndex != OUTLINE },
	)

	@JvmField
	val lockedOutlineColor = ColorModuleSetting(
		id = "locked_outline_color",
		label = "Locked outline",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		visibleIf = { highlightClicked.value && clickedStyle.selectedIndex != FILL },
	)

	@JvmField
	val clickedPhase = ToggleModuleSetting(
		id = "clicked_phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the mark through the room, so it can be seen from where you have moved on to.",
		visibleIf = { highlightClicked.value },
	)

	// ---- Secret sound ----------------------------------------------------

	private val soundSection = SectionModuleSetting(id = "sound_section", label = "Secret sound")

	@JvmField
	val secretSound = ToggleModuleSetting(
		id = "secret_sound",
		label = "Secret sound",
		description = "Plays your own sound when a secret is taken.",
	)

	@JvmField
	val sound = DropdownModuleSetting(
		id = "sound",
		label = "Sound",
		options = soundOptions.map { it.first },
		visibleIf = { secretSound.value },
	)

	@JvmField
	val volume = SliderModuleSetting(
		id = "volume",
		label = "Volume",
		defaultValue = 1.0,
		min = 0.0,
		max = 1.0,
		step = 0.1,
		visibleIf = { secretSound.value },
	)

	@JvmField
	val pitch = SliderModuleSetting(
		id = "pitch",
		label = "Pitch",
		defaultValue = 1.0,
		min = 0.5,
		max = 2.0,
		step = 0.1,
		visibleIf = { secretSound.value },
	)

	@JvmField
	val previewSound = ButtonModuleSetting(
		id = "preview_sound",
		label = "Play sound",
		action = { play() },
		visibleIf = { secretSound.value },
	)

	@JvmField
	val module = Module(
		id = "secrets",
		name = "Secrets",
		description = "Makes dungeon secrets easier to find",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(autoCloseChest, movableCounter) + listOf(
			hitboxSection,
			hitboxesOnlyInDungeons,
			leverHitbox,
			buttonHitbox,
			skullHitbox,
			mushroomHitbox,
			clickedSection,
			highlightClicked,
			highlightTime,
			clickedStyle,
			clickedFillColor,
			clickedOutlineColor,
			lockedFillColor,
			lockedOutlineColor,
			clickedPhase,
			soundSection,
			secretSound,
			sound,
			volume,
			pitch,
			previewSound,
		),
	)

	/** A secret that has been taken: where, when, and whether it turned out locked. */
	private class Taken(val at: Long, var locked: Boolean = false)

	private val taken = ConcurrentHashMap<BlockPos, Taken>()

	/** The last one marked, so Hypixel's "that chest is locked" can find it. */
	@Volatile
	private var lastMarked: BlockPos? = null

	/** An item secret fires once per pickup burst rather than once per item. */
	private var lastItemSecretAt = 0L

	private const val ITEM_SECRET_COOLDOWN_MILLIS = 2000L

	/** What Hypixel says when the chest you opened wants a key. */
	private const val LOCKED_MESSAGE = "That chest is locked!"

	fun initialize() {
		Hud.register(CounterElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onWorldChange() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onWorldChange() }
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay && message.string == LOCKED_MESSAGE) onChestLocked()
		}
	}

	// ---- The counter -----------------------------------------------------

	/**
	 * The room's secret count, where Hypixel's action bar used to show it.
	 *
	 * Taken from the room being stood in rather than from the action bar, which
	 * is a second behind on both counts that matter: walking into a room, when
	 * it still shows the last one, and taking a secret, when it still shows the
	 * number from before. The room is known the moment it is walked into, and its
	 * total comes with it. A secret taken here counts the moment Cryptic sees it
	 * taken; Hypixel's own count, read off the action bar, takes over once it
	 * agrees, and puts right any guess it does not agree with.
	 */
	private class CounterElement: HudElement("secrets_counter", "Secrets Counter", 0.47, 0.84) {
		private val font get() = Minecraft.getInstance().font

		// As wide as the widest count, so the text stays centred as it changes.
		override val width: Int get() = font.width(COUNTER_WIDEST)
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = counterValues() != null

		override fun showInEditor(): Boolean = movesCounter

		override fun render(context: GuiGraphicsExtractor) {
			val (found, total) = counterValues() ?: return
			draw(context, found, total)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 1, 3)

		private fun draw(context: GuiGraphicsExtractor, found: Int, total: Int) {
			val color = when {
				found <= 0 -> COUNTER_RED
				found >= total -> COUNTER_GREEN
				else -> COUNTER_YELLOW
			}
			val text = "$found/$total"
			context.text(font, text, (width - font.width(text)) / 2, 0, color)
		}
	}

	/** The count to show, found then total, or null for none. */
	private fun counterValues(): Pair<Int, Int>? {
		if (!movesCounter || !DungeonLocation.inDungeon || DungeonRun.inBoss) return null

		val room = DungeonMap.currentRoom()
		val total = room?.data?.secrets ?: 0
		if (room != null && total > 0) {
			val fresh = room === guessRoom && System.currentTimeMillis() - guessAt < GUESS_MILLIS
			val found = if (fresh) maxOf(room.foundSecrets, guessCount) else room.foundSecrets
			return found.coerceIn(0, total) to total
		}

		// A room the scan has named and that holds no secrets at all - most
		// puzzles, the blood room, the entrance - has nothing to count, so the
		// counter goes the moment you step in rather than showing the last
		// room's number until Hypixel's own text would have faded.
		if (room?.data != null) return null

		// A room the scan has not named has no total of its own, so Hypixel's
		// count stands in, for as long as the action bar would have shown it.
		if (RoomSecrets.found < 0 || System.currentTimeMillis() - RoomSecrets.seenAt >= COUNTER_SHOWN_MILLIS) return null
		return RoomSecrets.found to RoomSecrets.total
	}

	/**
	 * A secret taken in the room being stood in, before Hypixel has counted it.
	 *
	 * Counted from the room's last confirmed number, and only for a few seconds:
	 * long enough for the action bar to catch up, short enough that a click that
	 * turned out not to be a secret stops showing as one on its own.
	 */
	private fun guessFound() {
		val room = DungeonMap.currentRoom() ?: return
		val now = System.currentTimeMillis()
		if (room !== guessRoom || now - guessAt >= GUESS_MILLIS) {
			guessRoom = room
			guessCount = room.foundSecrets
		}
		guessCount++
		guessAt = now
	}

	private var guessRoom: DungeonRoom? = null
	private var guessCount = 0
	private var guessAt = 0L

	private const val GUESS_MILLIS = 4000L
	private const val COUNTER_WIDEST = "00/00"
	private const val COUNTER_SHOWN_MILLIS = 3000L
	private const val COUNTER_RED = 0xFFFF5555.toInt()
	private const val COUNTER_YELLOW = 0xFFFFFF55.toInt()
	private const val COUNTER_GREEN = 0xFF55FF55.toInt()

	// ---- Hitboxes --------------------------------------------------------

	private fun hitboxesApply(): Boolean =
		module.enabled && (!hitboxesOnlyInDungeons.value || DungeonLocation.inDungeon)

	@JvmStatic
	fun growsLever(pos: BlockPos): Boolean {
		if (!hitboxesApply() || !leverHitbox.value) return false
		return !(DungeonLocation.inFloor7 && pos in terminalLevers)
	}

	@JvmStatic
	fun growsMushroom(): Boolean = hitboxesApply() && mushroomHitbox.value

	/**
	 * A skull only grows if it is one Hypixel hides a secret behind, which is
	 * the owner of the skin rather than anything about the block.
	 */
	@JvmStatic
	fun growsSkull(pos: BlockPos): Boolean {
		if (!hitboxesApply() || !skullHitbox.value) return false
		val owner = (Minecraft.getInstance().level?.getBlockEntity(pos) as? SkullBlockEntity)
			?.ownerProfile
			?.partialProfile()
			?.id
			?: return false
		return owner.toString() in secretSkullOwners
	}

	@JvmStatic
	fun growsButton(): Boolean = hitboxesApply() && buttonHitbox.value

	/**
	 * A button stretched across the wall it is on, keeping the depth it already
	 * had. NoammAddons' shape: a full block would stick through the wall and be
	 * clickable from the room on the other side.
	 */
	@JvmStatic
	fun buttonShape(state: BlockState): VoxelShape {
		val face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)
		val facing = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)
		val depth = (if (state.getValue(ButtonBlock.POWERED)) 1 else 2) / 16.0

		return when (face) {
			AttachFace.CEILING -> Shapes.box(0.0, 1.0 - depth, 0.0, 1.0, 1.0, 1.0)
			AttachFace.FLOOR -> Shapes.box(0.0, 0.0, 0.0, 1.0, depth, 1.0)
			else -> when (facing) {
				Direction.EAST -> Shapes.box(0.0, 0.0, 0.0, depth, 1.0, 1.0)
				Direction.WEST -> Shapes.box(1.0 - depth, 0.0, 0.0, 1.0, 1.0, 1.0)
				Direction.SOUTH -> Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, depth)
				Direction.NORTH -> Shapes.box(0.0, 0.0, 1.0 - depth, 1.0, 1.0, 1.0)
				else -> Shapes.block()
			}
		}
	}

	// ---- Chests ----------------------------------------------------------

	/**
	 * Whether the chest that just opened should be shut again.
	 *
	 * Only a plain chest, and only in a dungeon: a menu with a name of its own
	 * is Hypixel asking a question, and closing it would answer for you.
	 */
	@JvmStatic
	fun closesChest(containerId: Int, type: MenuType<*>, title: String): Boolean {
		if (!module.enabled) return false
		if (!autoCloseChest.value) return false
		if (!DungeonLocation.inDungeon) return false
		if (type != MenuType.GENERIC_9x3 && type != MenuType.GENERIC_9x6) return false
		if (title != "Chest" && title != "Large Chest") return false

		close(containerId)
		return true
	}

	/**
	 * Shuts the chest the way pressing escape would.
	 *
	 * Taking the screen down is only half of closing a container. The player
	 * also holds the menu that is open, and the game refuses any click whose
	 * menu is not the one it holds — so a chest whose screen was removed and
	 * nothing else left the player holding a chest that no longer existed, and
	 * the next inventory opened would not take a single click until it had been
	 * closed and opened again. That was the inventory that needed opening twice,
	 * and the log said so every time: "Ignoring click in mismatching container".
	 */
	private fun close(containerId: Int) {
		val client = Minecraft.getInstance()
		client.execute {
			val player = client.player ?: return@execute
			InventoryWatch.note("Secrets auto-closed chest $containerId")
			player.connection.send(ServerboundContainerClosePacket(containerId))
			if (player.containerMenu.containerId == containerId) {
				// Puts the player's own inventory back as the open menu, and
				// takes the screen down with it.
				player.clientSideCloseContainer()
			} else if ((client.gui.screen() as? AbstractContainerScreen<*>)?.menu?.containerId == containerId) {
				client.gui.setScreen(null)
			}
		}
	}

	// ---- Taking a secret -------------------------------------------------

	/**
	 * Whether a block is one Hypixel hides secrets in. NoammAddons' rule: any
	 * chest or lever, and the two skulls that carry one.
	 */
	private fun isSecretBlock(pos: BlockPos): Boolean {
		val level = Minecraft.getInstance().level ?: return false
		val block = level.getBlockState(pos).block
		return when {
			block is SkullBlock -> {
				val owner = (level.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.partialProfile()?.id
				owner?.toString() in secretSkullOwners
			}
			else -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.LEVER
		}
	}

	/** Called when the player interacts with a block, whatever it turns out to be. */
	@JvmStatic
	fun onBlockUsed(pos: BlockPos) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		if (!isSecretBlock(pos)) return
		mark(pos)
	}

	/**
	 * A bat is a secret, and dies where it was. Hypixel's own death sound is
	 * the only announcement, so it is what this listens for.
	 */
	@JvmStatic
	fun onBatDied(x: Double, y: Double, z: Double) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		mark(BlockPos(Math.floor(x).toInt(), Math.floor(y).toInt(), Math.floor(z).toInt()))
	}

	/**
	 * An item secret, picked up rather than clicked.
	 *
	 * A chest emptied at once is a handful of pickups a tick apart, so a short
	 * cooldown keeps that one secret rather than five.
	 */
	@JvmStatic
	fun onSecretItemPickedUp(pos: BlockPos) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		val now = System.currentTimeMillis()
		if (now - lastItemSecretAt < ITEM_SECRET_COOLDOWN_MILLIS) return
		lastItemSecretAt = now
		mark(pos)
	}

	/** Whether a dropped item is one of the things a secret gives you. */
	@JvmStatic
	fun isSecretDrop(stack: ItemStack): Boolean {
		val name = stack.hoverName.string
		return secretItems.any { (_, names) -> names.any { name.contains(it, ignoreCase = true) } }
	}

	private fun mark(pos: BlockPos) {
		// The same block twice is one secret, not two.
		if (taken.putIfAbsent(pos, Taken(System.currentTimeMillis())) != null) return
		lastMarked = pos
		if (secretSound.value) play()
		if (movesCounter) guessFound()
	}

	/** Hypixel says so in chat, and the box turns to say it too. Odin's idea. */
	@JvmStatic
	fun onChestLocked() {
		if (!module.enabled) return
		lastMarked?.let { taken[it]?.locked = true }
		// A locked chest was not a secret taken, so the counter takes it back.
		if (guessRoom === DungeonMap.currentRoom() && guessCount > 0) guessCount--
	}

	fun tick() {
		if (taken.isEmpty()) return
		val cutoff = System.currentTimeMillis() - highlightTime.value.toLong()
		taken.entries.removeIf { it.value.at < cutoff }
	}

	fun onWorldChange() {
		taken.clear()
		lastMarked = null
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !highlightClicked.value || taken.isEmpty()) return
		val style = clickedStyle.selectedIndex

		taken.forEach { (pos, secret) ->
			val fill = if (secret.locked) lockedFillColor.argb else clickedFillColor.argb
			val outline = if (secret.locked) lockedOutlineColor.argb else clickedOutlineColor.argb
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = pos,
				outlineArgb = if (style == FILL) 0 else outline,
				fillArgb = if (style == OUTLINE) 0 else fill,
				outline = style != FILL,
				fill = style != OUTLINE,
				phase = clickedPhase.value,
				lineWidth = 2f,
				// The block's own shape, which already reflects whatever the
				// hitbox switches above have done to it: a lever boxed as a
				// full block when its hitbox is off is a lie about where it is.
				fullBlock = false,
			)
		}
	}

	// ---- Sound -----------------------------------------------------------

	private fun selectedSound(): SoundEvent =
		soundOptions[sound.selectedIndex.coerceIn(soundOptions.indices)].second

	private fun play() {
		val client = Minecraft.getInstance()
		client.soundManager.play(
			SimpleSoundInstance.forUI(selectedSound(), pitch.value.toFloat(), volume.value.toFloat()),
		)
	}
}
