package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
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
 * Shows where a Gyrokinetic Wand's storm lands and its ten-block pull radius, neither of which the
 * game shows. Uses the same ray as Etherwarp, and draws nothing where the wand wouldn't land (on
 * air, or under anything but air or a carpet). From NoammAddons' Gyro Helper, with Odin's cooldown
 * colouring.
 */
object GyroHelper {
	private const val WAND_ID = "GYROKINETIC_WAND"

	private const val THROW_RANGE = 25.0
	private const val STORM_RADIUS = 10.0

	private const val STORM_HEIGHT = 1.05

	/** Gravity Storm's cooldown before reductions. */
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
		drawBox.withMore(boxColor),
		drawRing.withMore(ringWidth, ringColor),
		phase,
		showCooldown.withMore(cooldownColor),
	)

	@JvmField
	val module = Module(
		id = "gyro_helper",
		name = "Gyro Helper",
		description = "Where a gyro lands and pulls",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = configurable,
	)

	private var castAt = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		// Cancels the game's outline whether or not Block Overlay is on, since Cryptic's box is
		// what's in the way.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { _, _ -> !hidesBlockOutline() }
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> castAt = 0L }
	}

	/**
	 * Chat and action bar from the packet. The mana cost is an action bar line, which Hypixel moves
	 * into chat while something else uses the action bar. Read from the packet, since mods that
	 * hide the action bar cancel Fabric's event.
	 */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		val text = message.string.replace(FORMATTING, "")
		onActionBar(text)
		onCooldownMessage(text)
	}

	/** The action bar's own packet, Hypixel's other way of sending it. */
	@JvmStatic
	fun onActionBarPacket(text: Component) {
		onActionBar(text.string.replace(FORMATTING, ""))
	}

	private val FORMATTING = Regex("§.")

	/** Whether the last action bar already said Gravity Storm, so one cast counts once. */
	private var lastBarHadStorm = false

	/**
	 * The wand's mana line, "-1200 Mana (Gravity Storm)", matched on the ability's name. It's
	 * resent every second while up, so only its first appearance is the cast (SkyHanni's rule).
	 */
	private fun onActionBar(text: String) {
		if (!module.enabled) return
		val hasStorm = GRAVITY_STORM in text
		if (hasStorm && !lastBarHadStorm) cast()
		lastBarHadStorm = hasStorm
	}

	/** "This ability is on cooldown for 12s.": Hypixel's exact remaining time. */
	private fun onCooldownMessage(text: String) {
		if (!module.enabled || !isAiming()) return
		val seconds = COOLDOWN_MESSAGE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return
		castAt = System.currentTimeMillis() - (cooldownMillis() - seconds * 1000L)
	}

	private val COOLDOWN_MESSAGE = Regex("""^This ability is on cooldown for (\d+)s\.""")

	/**
	 * Gravity Storm's sound (an enderman teleport at a pitch nothing else uses) right after a
	 * left-click with the wand, SkyHanni's way of seeing the cast. Works when the action bar is
	 * taken.
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

	private var lastSwingAt = 0L
	private var attackWasDown = false

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
	 * Gravity Storm's cooldown with a Mage's reduction in a dungeon, as SkyHanni works it out: a
	 * quarter off, half for the party's only mage, and 1% more per two class levels.
	 */
	private fun cooldownMillis(): Long {
		val name = Minecraft.getInstance().player?.name?.string ?: return COOLDOWN_MILLIS
		if (!DungeonLocation.inDungeon || DungeonTeam.classOf(name) != DungeonTeam.DungeonClass.MAGE) return COOLDOWN_MILLIS
		val mages = DungeonTeam.classes.values.count { it == DungeonTeam.DungeonClass.MAGE }
		val level = DungeonTeam.classLevels[name] ?: 0
		val multiplier = 1.0 - (if (mages == 1) 0.5 else 0.25) - 0.01 * (level / 2)
		return (COOLDOWN_MILLIS * multiplier.coerceAtLeast(0.0)).toLong()
	}

	private fun onCooldown(): Boolean =
		castAt != 0L && System.currentTimeMillis() - castAt < cooldownMillis()

	fun isAiming(): Boolean {
		if (!module.enabled) return false
		val held = Minecraft.getInstance().player?.mainHandItem ?: return false
		return holdsWand(held)
	}

	private fun holdsWand(stack: ItemStack): Boolean =
		!stack.isEmpty && SkyblockItem.id(stack) == WAND_ID

	/** Whether to drop the game's outline: only while there's something of ours to aim with instead. */
	fun hidesBlockOutline(): Boolean =
		module.enabled && isAiming() && landingBlock() != null

	/** The block the storm would land on, or null. It needs a solid block with only air or a carpet above. */
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
