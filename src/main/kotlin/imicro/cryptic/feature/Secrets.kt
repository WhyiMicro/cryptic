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
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
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
 * Makes finding secrets less fiddly: closes secret chests for you, grows the hitboxes of blocks
 * secrets hide behind, and marks what you clicked (red if locked, Odin's `SecretClicked`). Mostly
 * from NoammAddons' `Secrets`.
 *
 * A lever or mushroom grows to a full block, but a button only grows across its face, or it would
 * be clickable from behind the wall. A skull only grows if it's one of the two Hypixel hides
 * secrets in. Client-side only; the server decides.
 */
object Secrets {
	private const val OUTLINE = 0
	private const val FILL = 1
	private const val FILL_OUTLINE = 2

	/** Skull owners Hypixel hides secrets behind: wither essence and the redstone key (NoammAddons' list). */
	private val secretSkullOwners = setOf(
		"2865274b-3097-394e-8149-ec629c72d850",
		"e0f3e929-869e-3dca-9504-54c666ee6f23",
		"fed95410-aba1-39df-9b95-1d4f361eb66e",
	)

	/**
	 * F7 terminal levers, which aren't secrets and would be too easy to hit with a grown hitbox
	 * (NoammAddons' list).
	 */
	private val terminalLevers = setOf(
		BlockPos(61, 136, 142), BlockPos(60, 136, 142), BlockPos(59, 136, 142),
		BlockPos(62, 135, 142), BlockPos(61, 135, 142), BlockPos(59, 135, 142),
		BlockPos(58, 135, 142), BlockPos(62, 134, 142), BlockPos(61, 134, 142),
		BlockPos(59, 134, 142), BlockPos(58, 134, 142), BlockPos(61, 133, 142),
		BlockPos(60, 133, 142), BlockPos(59, 133, 142),
	)

	/** Everything a secret can give; the healing potion has several names across floors. */
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
		"Candycomb" to setOf("Candycomb"),
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
		description = "Shuts a secret chest for you.",
	)

	private const val CLOSE_INSTANTLY = 0
	private const val CLOSE_ON_MOVE = 1

	@JvmField
	val autoCloseMode = DropdownModuleSetting(
		id = "auto_close_mode",
		label = "Close",
		options = listOf("Instantly", "On move"),
		defaultIndex = CLOSE_INSTANTLY,
		description = "Instantly shuts it as it opens. On move shuts it the same way if you're moving, otherwise when you next press a movement key.",
		visibleIf = { autoCloseChest.value },
		optionRestrictions = mapOf("Instantly" to imicro.cryptic.gui.Restriction.CHEAT),
	)

	@JvmField
	val movableCounter = ToggleModuleSetting(
		id = "movable_counter",
		label = "Moveable secrets counter",
		description = "Takes the room's secret count out of the action bar and puts it where you place it: " +
			"red with none found, yellow partway, green once they all are.",
	)

	val movesCounter: Boolean get() = module.enabled && movableCounter.value

	private val hitboxSection = SectionModuleSetting(
		id = "hitbox_section", label = "Secret hitboxes",
		visibleIf = { imicro.cryptic.gui.Restrictions.allows(imicro.cryptic.gui.Restriction.SEMI_LEGIT) },
	)

	@JvmField
	val hitboxesOnlyInDungeons = ToggleModuleSetting(
		id = "hitboxes_only_in_dungeons",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Only in dungeons",
		defaultValue = true,
		description = "Leaves every block alone outside a dungeon, where a grown hitbox is only in the way.",
	)

	@JvmField
	val leverHitbox = ToggleModuleSetting(
		id = "lever_hitbox",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Lever",
		description = "Gives levers a full block hitbox. Floor 7's terminal levers are left alone.",
	)

	@JvmField
	val buttonHitbox = ToggleModuleSetting(
		id = "button_hitbox",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Button",
		description = "Stretches a button across the face it is on.",
	)

	@JvmField
	val skullHitbox = ToggleModuleSetting(
		id = "skull_hitbox",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Skulls",
		description = "Full hitboxes for essence and key skulls.",
	)

	@JvmField
	val mushroomHitbox = ToggleModuleSetting(
		id = "mushroom_hitbox",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Mushroom",
		description = "Gives mushrooms a full block hitbox.",
	)

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
		label = "Highlight time",
		unit = "ms",
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
		options = soundOptions.map { it.first } + SoundChoices.CUSTOM,
		visibleIf = { secretSound.value },
	)

	private val customSound = SoundChoices.customField("custom_sound", sound) { secretSound.value }

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
		description = "Helps you find secrets",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(autoCloseChest.withMore(autoCloseMode), movableCounter) + listOf(
			hitboxSection,
			hitboxesOnlyInDungeons,
			leverHitbox,
			buttonHitbox,
			skullHitbox,
			mushroomHitbox,
			clickedSection,
			highlightClicked.withMore(
				highlightTime, clickedStyle, clickedFillColor, clickedOutlineColor,
				lockedFillColor, lockedOutlineColor, clickedPhase,
			),
			soundSection,
			secretSound.withMore(sound, customSound, volume, pitch, previewSound),
		),
	)

	/** A taken secret: when, and whether it was locked. */
	private class Taken(val at: Long, var locked: Boolean = false)

	private val taken = ConcurrentHashMap<BlockPos, Taken>()

	/** The last one marked, for Hypixel's "that chest is locked". */
	@Volatile
	private var lastMarked: BlockPos? = null

	/** Item secrets count once per pickup burst. */
	private var lastItemSecretAt = 0L

	private const val ITEM_SECRET_COOLDOWN_MILLIS = 2000L

	private const val LOCKED_MESSAGE = "That chest is locked!"

	/** Hypixel's line for a chest opened before, by anyone. */
	private const val SEARCHED_MESSAGE = "This chest has already been searched!"

	/**
	 * Every secret the counter counted on this server, for the whole floor, so re-clicking a chest
	 * doesn't count it twice.
	 */
	private val counted = HashSet<BlockPos>()

	/** The secret last added, which Hypixel's answer may take back. */
	private var lastCounted: BlockPos? = null

	private val FORMATTING = Regex("§.")

	fun initialize() {
		Hud.register(CounterElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onWorldChange() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onWorldChange() }
		// Chat is read in [onSystemChat] from the packet, before any mod can hide it.
	}

	/**
	 * The room's secret count, where Hypixel's action bar showed it. Taken from the current room,
	 * since the action bar lags a second on entry and after each secret. A secret taken here counts
	 * at once; Hypixel's own count takes over once it agrees and corrects any wrong guess.
	 */
	private class CounterElement: HudElement("secrets_counter", "Secrets Counter", 0.47, 0.84) {
		private val font get() = Minecraft.getInstance().font

		// As wide as the widest count, so the text stays centred.
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

	private fun counterValues(): Pair<Int, Int>? {
		if (!movesCounter || !DungeonLocation.inDungeon || DungeonRun.inBoss) return null

		val room = DungeonMap.currentRoom()
		val total = room?.data?.secrets ?: 0
		val now = System.currentTimeMillis()
		// A secret just taken shows at once; the bar lags a second behind it.
		val guessing = room != null && room === guessRoom && now - guessAt < GUESS_MILLIS
		// A known room's own count, which only takes readings that are this room's, never the raw
		// bar, which shows the last room's for a moment after you walk in.
		if (room != null && total > 0) {
			val found = if (guessing) maxOf(room.foundSecrets, guessCount) else room.foundSecrets
			return found.coerceIn(0, total) to total
		}

		// A named room with no secrets (most puzzles, blood, the entrance) hides the counter
		// immediately.
		if (room?.data != null) return null

		// An unnamed room has no total, so Hypixel's count stands in while the action bar would
		// show it.
		if (RoomSecrets.found < 0 || System.currentTimeMillis() - RoomSecrets.seenAt >= COUNTER_SHOWN_MILLIS) return null
		return RoomSecrets.found to RoomSecrets.total
	}

	/**
	 * A secret taken in this room before Hypixel counts it, added to the room's last confirmed
	 * number for a few seconds.
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

	private fun hitboxesApply(): Boolean =
		module.enabled && (!hitboxesOnlyInDungeons.value || DungeonLocation.inDungeon)

	@JvmStatic
	fun growsLever(pos: BlockPos): Boolean {
		if (!hitboxesApply() || !leverHitbox.value) return false
		return !(DungeonLocation.inFloor7 && pos in terminalLevers)
	}

	@JvmStatic
	fun growsMushroom(): Boolean = hitboxesApply() && mushroomHitbox.value

	/** A skull grows only if its skin owner is one Hypixel hides a secret behind. */
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
	 * A button stretched across its wall, keeping its depth (NoammAddons' shape), so it can't be
	 * clicked through the wall.
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

	/**
	 * Whether the chest that just opened should close. Only plain chests in a dungeon; a named menu
	 * is Hypixel asking something.
	 */
	@JvmStatic
	fun closesChest(containerId: Int, type: MenuType<*>, title: String): Boolean {
		if (!module.enabled) return false
		if (!autoCloseChest.value) return false
		if (!DungeonLocation.inDungeon) return false
		if (type != MenuType.GENERIC_9x3 && type != MenuType.GENERIC_9x6) return false
		if (title != "Chest" && title != "Large Chest") return false

		// Already moving as it opens: shut it at once, as Instantly does, so it never shows.
		if (autoCloseMode.selectedIndex == CLOSE_ON_MOVE && !movementPressed(Minecraft.getInstance())) {
			closeOnMove = containerId
			return false
		}
		close(containerId)
		return true
	}

	/** The secret chest left open until a movement key is pressed. */
	@Volatile
	private var closeOnMove: Int? = null

	/**
	 * Movement keys, read from the keyboard itself: while a screen is open the game stops updating
	 * its key mappings.
	 */
	private fun movementPressed(client: Minecraft): Boolean {
		val options = client.options
		val window = client.window
		return listOf(options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump).any { mapping ->
			val key = (mapping as imicro.cryptic.mixin.KeyMappingAccessor).`cryptic$key`()
			key.type == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM && com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, key.value)
		}
	}

	private fun tickCloseOnMove() {
		val containerId = closeOnMove ?: return
		val client = Minecraft.getInstance()
		val open = (client.gui.screen() as? AbstractContainerScreen<*>)?.menu?.containerId
		// Opening waits for its contents; anything else open means it's already gone.
		if (open == null && client.player?.containerMenu?.containerId == containerId) return
		if (open != containerId) {
			closeOnMove = null
			return
		}
		if (!movementPressed(client)) return
		closeOnMove = null
		close(containerId)
	}

	/**
	 * Closes the chest like escape does. Removing just the screen leaves the player holding the
	 * chest's menu, and the next inventory then refuses every click until reopened ("Ignoring click
	 * in mismatching container").
	 */
	private fun close(containerId: Int) {
		val client = Minecraft.getInstance()
		client.execute {
			val player = client.player ?: return@execute
			InventoryWatch.note("Secrets auto-closed chest $containerId")
			player.connection.send(ServerboundContainerClosePacket(containerId))
			if (player.containerMenu.containerId == containerId) {
				// Puts the player's inventory back as the open menu and removes the screen.
				player.clientSideCloseContainer()
			} else if ((client.gui.screen() as? AbstractContainerScreen<*>)?.menu?.containerId == containerId) {
				client.gui.setScreen(null)
			}
		}
	}

	/** Whether a block holds secrets: any chest or lever, and the two secret skulls (NoammAddons' rule). */
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

	@JvmStatic
	fun onBlockUsed(pos: BlockPos) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		if (!isSecretBlock(pos)) return
		mark(pos)
	}

	/** A bat secret: Hypixel's death sound is the only announcement. */
	@JvmStatic
	fun onBatDied(x: Double, y: Double, z: Double) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		mark(BlockPos(Math.floor(x).toInt(), Math.floor(y).toInt(), Math.floor(z).toInt()))
	}

	/**
	 * An item secret picked up. A chest emptied at once is several pickups, so a short cooldown
	 * counts one.
	 */
	@JvmStatic
	fun onSecretItemPickedUp(pos: BlockPos) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		val now = System.currentTimeMillis()
		if (now - lastItemSecretAt < ITEM_SECRET_COOLDOWN_MILLIS) return
		lastItemSecretAt = now
		mark(pos)
	}

	@JvmStatic
	fun isSecretDrop(stack: ItemStack): Boolean {
		val name = stack.hoverName.string
		return secretItems.any { (_, names) -> names.any { name.contains(it, ignoreCase = true) } }
	}

	private fun mark(pos: BlockPos) {
		// The same block twice is one secret.
		if (taken.putIfAbsent(pos, Taken(System.currentTimeMillis())) != null) return
		lastMarked = pos
		if (secretSound.value) play()
		// Counted once per floor however often it's clicked.
		lastCounted = null
		if (movesCounter && counted.add(pos.immutable())) {
			lastCounted = pos.immutable()
			guessFound()
		}
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		when (message.string.replace(FORMATTING, "").trim()) {
			LOCKED_MESSAGE -> onChestLocked()
			SEARCHED_MESSAGE -> onChestSearched()
		}
	}

	/**
	 * Hypixel says the chest was opened before, so the counter takes it back; it stays counted so
	 * clicking again adds nothing.
	 */
	private fun onChestSearched() {
		if (!module.enabled || lastCounted == null) return
		lastCounted = null
		if (guessRoom === DungeonMap.currentRoom() && guessCount > 0) guessCount--
	}

	/** Hypixel says it's locked, and the box turns red. Odin's idea. */
	@JvmStatic
	fun onChestLocked() {
		if (!module.enabled) return
		lastMarked?.let { taken[it]?.locked = true }
		// A locked chest wasn't a secret taken, so the counter takes it back until it's opened with
		// a key.
		if (lastCounted != null && lastCounted == lastMarked) {
			counted.remove(lastCounted)
			lastCounted = null
			if (guessRoom === DungeonMap.currentRoom() && guessCount > 0) guessCount--
		}
	}

	fun tick() {
		tickCloseOnMove()
		if (taken.isEmpty()) return
		val cutoff = System.currentTimeMillis() - highlightTime.value.toLong()
		taken.entries.removeIf { it.value.at < cutoff }
	}

	fun onWorldChange() {
		taken.clear()
		counted.clear()
		lastCounted = null
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
				// The block's own shape, which already reflects the hitbox settings.
				fullBlock = false,
			)
		}
	}

	/** The chosen sound, or null when Custom holds no id. */
	private fun selectedSound(): SoundEvent? =
		if (sound.selected == SoundChoices.CUSTOM) SoundChoices.custom(customSound.value)
		else soundOptions[sound.selectedIndex.coerceIn(soundOptions.indices)].second

	private fun play() {
		SoundChoices.play(selectedSound() ?: return, volume.value.toFloat(), pitch.value.toFloat())
	}
}
