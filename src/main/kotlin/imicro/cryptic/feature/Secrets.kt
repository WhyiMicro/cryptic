package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.ARGB
import net.minecraft.world.inventory.ContainerInput
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
	 * Everything a dungeon chest can hold, which is a short and fixed list.
	 * The healing potion is named several ways across floors, so all of them
	 * are matched to the one switch.
	 */
	private val extractableItems = listOf(
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

	// ---- Chest extraction ------------------------------------------------

	private val extractionSection = SectionModuleSetting(id = "extraction_section", label = "Chest extraction")

	@JvmField
	val extractItems = ToggleModuleSetting(
		id = "extract_items",
		label = "Take items out",
		description = "Pulls the chosen items into your inventory before the chest closes.",
	)

	/**
	 * One switch per item, rather than a list to tick inside a popup: a dungeon
	 * chest holds a dozen things at most, and a switch you can see the state of
	 * beats one you have to open something to read.
	 */
	private val extractToggles: List<ToggleModuleSetting> = extractableItems.map { (label, _) ->
		ToggleModuleSetting(
			id = "extract_" + label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'),
			label = label,
			visibleIf = { extractItems.value },
		)
	}

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
		visibleIf = { highlightClicked.value },
	)

	@JvmField
	val clickedOutlineColor = ColorModuleSetting(
		id = "clicked_outline_color",
		label = "Outline",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		visibleIf = { highlightClicked.value },
	)

	@JvmField
	val lockedFillColor = ColorModuleSetting(
		id = "locked_fill_color",
		label = "Locked fill",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x50,
		description = "What a chest is marked in once Hypixel says it is locked.",
		visibleIf = { highlightClicked.value },
	)

	@JvmField
	val lockedOutlineColor = ColorModuleSetting(
		id = "locked_outline_color",
		label = "Locked outline",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		visibleIf = { highlightClicked.value },
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
		settings = listOf(autoCloseChest, extractionSection, extractItems) + extractToggles + listOf(
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

	/**
	 * The chest whose contents are still on their way.
	 *
	 * A chest opening and a chest having anything in it are two packets, and
	 * the second one is the first moment there is something to take.
	 */
	@Volatile
	private var awaitingContents: Int? = null

	private const val ITEM_SECRET_COOLDOWN_MILLIS = 2000L

	/** What Hypixel says when the chest you opened wants a key. */
	private const val LOCKED_MESSAGE = "That chest is locked!"

	fun initialize() {
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onWorldChange() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onWorldChange() }
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay && message.string == LOCKED_MESSAGE) onChestLocked()
		}
	}

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
	 * Whether the chest that just opened should be emptied and shut again.
	 *
	 * Only a plain chest, and only in a dungeon: a menu with a name of its own
	 * is Hypixel asking a question, and closing it would answer for you.
	 * Anything wanted is shift-clicked across first — if the inventory is full
	 * the server simply will not move it, and the chest closes either way.
	 */
	@JvmStatic
	fun closesChest(containerId: Int, type: MenuType<*>, title: String): Boolean {
		if (!module.enabled) return false
		if (!autoCloseChest.value && !extractItems.value) return false
		if (!DungeonLocation.inDungeon) return false
		if (type != MenuType.GENERIC_9x3 && type != MenuType.GENERIC_9x6) return false
		if (title != "Chest" && title != "Large Chest") return false

		// Taking anything out means waiting: this packet only says a chest has
		// opened, and its contents arrive in the next one. Closing here — which
		// is what an earlier version did — shut the chest before there was
		// anything in it to take.
		if (extractItems.value) {
			awaitingContents = containerId
			return true
		}

		close(containerId)
		return true
	}

	/**
	 * The chest's contents, which is the first moment anything can be taken.
	 *
	 * Everything wanted is shift-clicked across and the chest is shut behind
	 * it, whether or not auto close is on — a chest opened to empty it is
	 * finished with either way. A full inventory simply means the server moves
	 * nothing, and it closes all the same.
	 */
	@JvmStatic
	fun onChestFilled(containerId: Int) {
		if (awaitingContents != containerId) return
		awaitingContents = null

		val client = Minecraft.getInstance()
		client.execute {
			takeWantedItems(containerId)
			close(containerId)
		}
	}

	private fun close(containerId: Int) {
		val client = Minecraft.getInstance()
		client.execute {
			client.player?.connection?.send(ServerboundContainerClosePacket(containerId))
			client.gui.setScreen(null)
		}
	}

	private fun takeWantedItems(containerId: Int) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val menu = player.containerMenu
		if (menu.containerId != containerId) return

		// Only the chest's own slots; the rest of the menu is the player's
		// inventory, and shift-clicking there would send things the other way.
		val chestSlots = menu.slots.count { it.container !== player.inventory }
		for (slot in 0 until chestSlots) {
			val stack = menu.slots.getOrNull(slot)?.item ?: continue
			if (stack.isEmpty || !isWanted(stack)) continue
			client.gameMode?.handleContainerInput(containerId, slot, 0, ContainerInput.QUICK_MOVE, player)
		}
	}

	private fun isWanted(stack: ItemStack): Boolean {
		val name = stack.hoverName.string
		extractableItems.forEachIndexed { index, (_, names) ->
			if (extractToggles[index].value && names.any { name.contains(it, ignoreCase = true) }) return true
		}
		return false
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
		return extractableItems.any { (_, names) -> names.any { name.contains(it, ignoreCase = true) } }
	}

	private fun mark(pos: BlockPos) {
		// The same block twice is one secret, not two.
		if (taken.putIfAbsent(pos, Taken(System.currentTimeMillis())) != null) return
		lastMarked = pos
		if (secretSound.value) play()
	}

	/** Hypixel says so in chat, and the box turns to say it too. Odin's idea. */
	@JvmStatic
	fun onChestLocked() {
		if (!module.enabled) return
		lastMarked?.let { taken[it]?.locked = true }
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
