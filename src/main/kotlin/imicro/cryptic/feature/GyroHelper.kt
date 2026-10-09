package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * Shows where a Gyrokinetic Wand's storm will land, and how far it reaches.
 *
 * Ported from NoammAddons' Gyro Helper (CC0, Noamm9), with the cooldown
 * colouring from Odin's Gyro Wand (BSD 3-Clause, Copyright (c) 2025
 * odtheking). The wand drops a storm on the block you are pointing at and
 * pulls everything within ten blocks of it towards the middle — and neither of
 * those two numbers is shown anywhere. Aiming it is otherwise a matter of
 * throwing one and watching where it went.
 *
 * The block is found by the same ray Etherwarp uses, so the two agree about
 * what is in the way. It refuses to draw where the wand would refuse to land:
 * on air, and under anything that is neither air nor a carpet.
 */
object GyroHelper {
	/** What the wand is called in SkyBlock. */
	private const val WAND_ID = "GYROKINETIC_WAND"

	/** How far the wand throws, and how far the storm pulls once it lands. */
	private const val THROW_RANGE = 25.0
	private const val STORM_RADIUS = 10.0

	/** How high the ring floats over the block the storm lands on. */
	private const val STORM_HEIGHT = 1.05

	/** Gravity Storm's own cooldown, before any reduction. */
	private const val COOLDOWN_MILLIS = 30_000L

	@JvmField
	val drawBox = ToggleModuleSetting(
		id = "draw_box",
		label = "Box the block",
		defaultValue = true,
		description = "Marks the block the storm will land on.",
	)

	@JvmField
	val drawRing = ToggleModuleSetting(
		id = "draw_ring",
		label = "Draw the range",
		defaultValue = true,
		description = "Rings the ten blocks the storm pulls from.",
	)

	@JvmField
	val ringWidth = SliderModuleSetting(
		id = "ring_width",
		label = "Ring width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { drawRing.value },
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws through whatever is in front of it.",
	)

	@JvmField
	val hideBlockOutline = ToggleModuleSetting(
		id = "hide_block_outline",
		label = "Hide the block outline",
		defaultValue = true,
		description = "Drops the game's own outline while you aim, so only one box shows.",
	)

	private val colorSection = SectionModuleSetting("color_section", "Colors")

	@JvmField
	val boxColor = ColorModuleSetting(
		id = "box_color",
		label = "Box",
		defaultRgb = 0xAA00AA,
		supportsAlpha = true,
		defaultAlpha = 0x4C,
		visibleIf = { drawBox.value },
		inlineWith = drawBox,
	)

	@JvmField
	val ringColor = ColorModuleSetting(
		id = "ring_color",
		label = "Ring",
		defaultRgb = 0xAA00AA,
		supportsAlpha = true,
		visibleIf = { drawRing.value },
		inlineWith = drawRing,
	)

	@JvmField
	val showCooldown = ToggleModuleSetting(
		id = "show_cooldown",
		label = "Color while on cooldown",
		defaultValue = true,
		description = "Draws in another color until Gravity Storm is ready again.",
	)

	@JvmField
	val cooldownColor = ColorModuleSetting(
		id = "cooldown_color",
		label = "On cooldown",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { showCooldown.value },
		inlineWith = showCooldown,
	)

	private val configurable = listOf(
		drawBox,
		drawRing,
		ringWidth,
		phase,
		hideBlockOutline,
		colorSection,
		boxColor,
		ringColor,
		showCooldown,
		cooldownColor,
	)

	@JvmField
	val module = Module(
		id = "gyro_helper",
		name = "Gyro Helper",
		description = "Shows where a gyro lands and what it pulls",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurable,
	)

	/** When Gravity Storm was last cast, which is what the cooldown counts from. */
	private var castAt = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		// Cancelling the game's own outline whether or not Block Overlay is on,
		// because the reason it is in the way is Cryptic's box rather than any
		// particular one of them.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { _, _ -> !hidesBlockOutline() }
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> castAt = 0L }
	}

	/**
	 * Both halves of chat, from the packet: the mana cost is an action bar
	 * message, but Hypixel moves it into the chat line itself while something
	 * else is using the action bar.
	 *
	 * Read from the packet rather than from Fabric's chat event, which is
	 * where it was read before. A mod that rewrites or hides the action bar —
	 * SkyHanni and Skyblocker both can — cancels that event, and the cast was
	 * never heard, so the colour never changed.
	 */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		val text = message.string.replace(FORMATTING, "")
		onActionBar(text)
		onCooldownMessage(text)
	}

	/**
	 * The action bar's own packet, which is the other way Hypixel sends it.
	 * Listening only to the chat packet's overlay half missed every cast that
	 * came this way, and the colour never changed.
	 */
	@JvmStatic
	fun onActionBarPacket(text: Component) {
		onActionBar(text.string.replace(FORMATTING, ""))
	}

	private val FORMATTING = Regex("§.")

	/** Whether the last action bar already said Gravity Storm, so one cast is one cast. */
	private var lastBarHadStorm = false

	/**
	 * The wand's own mana message, "-1200 Mana (Gravity Storm)".
	 *
	 * Matched on the ability's name alone rather than on the whole line: what
	 * Hypixel writes around it is a mana cost that changes with your gear. The
	 * line stays up for a few seconds and is sent again every second, so only
	 * its first appearance is the cast — SkyHanni's rule.
	 */
	private fun onActionBar(text: String) {
		if (!module.enabled) return
		val hasStorm = GRAVITY_STORM in text
		if (hasStorm && !lastBarHadStorm) cast()
		lastBarHadStorm = hasStorm
	}

	/**
	 * "This ability is on cooldown for 12s." — Hypixel saying exactly how long
	 * is left, which puts the count right whatever it thought before.
	 */
	private fun onCooldownMessage(text: String) {
		if (!module.enabled || !isAiming()) return
		val seconds = COOLDOWN_MESSAGE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return
		castAt = System.currentTimeMillis() - (cooldownMillis() - seconds * 1000L)
	}

	private val COOLDOWN_MESSAGE = Regex("""^This ability is on cooldown for (\d+)s\.""")

	/**
	 * Gravity Storm's own sound, which is SkyHanni's way of seeing the cast:
	 * an enderman teleport at a pitch nothing else uses, within a moment of you
	 * left-clicking with the wand. It comes whether or not the action bar shows
	 * the mana, which can be taken over by other messages.
	 */
	@JvmStatic
	fun onSound(packet: ClientboundSoundPacket) {
		if (!module.enabled) return
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		if (packet.sound.value() != SoundEvents.ENDERMAN_TELEPORT) return
		if (Math.abs(packet.pitch - STORM_PITCH) > 0.001f || packet.volume != 1f) return
		if (System.currentTimeMillis() - lastSwingAt > CLICK_WINDOW_MILLIS || !isAiming()) return
		cast()
	}

	/** When the wand was last swung, which the sound has to come right after. */
	private var lastSwingAt = 0L
	private var attackWasDown = false

	/** Watches for a left click with the wand in hand. */
	fun tick(client: Minecraft) {
		val down = client.options.keyAttack.isDown
		if (down && !attackWasDown && client.gui.screen() == null && isAiming()) lastSwingAt = System.currentTimeMillis()
		attackWasDown = down
	}

	private fun cast() {
		castAt = System.currentTimeMillis()
	}

	private const val GRAVITY_STORM = "Gravity Storm"
	private const val STORM_PITCH = 0.61904764f
	private const val CLICK_WINDOW_MILLIS = 400L

	/**
	 * Gravity Storm's cooldown, with a Mage's reduction in a dungeon taken off,
	 * as SkyHanni works it out: a quarter off, or half for the party's only
	 * mage, and another 1% for every two class levels.
	 */
	private fun cooldownMillis(): Long {
		val name = Minecraft.getInstance().player?.name?.string ?: return COOLDOWN_MILLIS
		if (!DungeonLocation.inDungeon || DungeonTeam.classOf(name) != DungeonTeam.DungeonClass.MAGE) return COOLDOWN_MILLIS
		val mages = DungeonTeam.classes.values.count { it == DungeonTeam.DungeonClass.MAGE }
		val level = DungeonTeam.classLevels[name] ?: 0
		val multiplier = 1.0 - (if (mages == 1) 0.5 else 0.25) - 0.01 * (level / 2)
		return (COOLDOWN_MILLIS * multiplier.coerceAtLeast(0.0)).toLong()
	}

	/** True while the last cast is still cooling down. */
	private fun onCooldown(): Boolean =
		castAt != 0L && System.currentTimeMillis() - castAt < cooldownMillis()

	/** Whether the wand is in hand, which is the whole of what makes this show. */
	fun isAiming(): Boolean {
		if (!module.enabled) return false
		val held = Minecraft.getInstance().player?.mainHandItem ?: return false
		return holdsWand(held)
	}

	private fun holdsWand(stack: ItemStack): Boolean =
		!stack.isEmpty && SkyblockItem.id(stack) == WAND_ID

	/**
	 * Whether the game's own block outline should be dropped.
	 *
	 * Only while there is something of ours to look at instead: an outline
	 * removed with nothing in its place is a worse aim than the outline was.
	 */
	fun hidesBlockOutline(): Boolean =
		module.enabled && hideBlockOutline.value && isAiming() && landingBlock() != null

	/**
	 * The block the storm would land on, or null when it would not land.
	 *
	 * The wand needs somewhere solid with room over it: Hypixel drops nothing
	 * when the ray ends in air, and nothing when the space above is taken by
	 * anything but a carpet.
	 */
	private fun landingBlock(): BlockPos? {
		val client = Minecraft.getInstance()
		val player = client.player ?: return null
		val level = client.level ?: return null

		val pos = EtherwarpHelper.target(player.position(), player.lookAngle, THROW_RANGE).pos ?: return null
		if (level.getBlockState(pos).isAir) return null

		val above = level.getBlockState(pos.above())
		if (!above.isAir && !above.`is`(BlockTags.WOOL_CARPETS)) return null
		return pos
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		if (!drawBox.value && !drawRing.value) return
		if (!isAiming()) return

		val pos = landingBlock() ?: return
		val cooling = showCooldown.value && onCooldown()

		if (drawBox.value) {
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = pos,
				outlineArgb = if (cooling) cooldownColor.argb else boxColor.argb,
				fillArgb = if (cooling) cooldownColor.argb else boxColor.argb,
				outline = true,
				fill = true,
				phase = phase.value,
				lineWidth = ringWidth.value.toFloat(),
				fullBlock = true,
			)
		}

		if (drawRing.value) {
			WorldRender.drawCircle(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				center = Vec3(pos.x + 0.5, pos.y + STORM_HEIGHT, pos.z + 0.5),
				radius = STORM_RADIUS,
				argb = if (cooling) cooldownColor.argb else ringColor.argb,
				lineWidth = ringWidth.value.toFloat(),
				phase = phase.value,
			)
		}
	}
}
