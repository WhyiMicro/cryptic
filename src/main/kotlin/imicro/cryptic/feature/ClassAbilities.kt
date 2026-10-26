package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.entity.EntityTypes
import java.util.Locale

/**
 * Keys for the dungeon class ultimate and ability, which Hypixel otherwise puts on dropping an item
 * (Q, and Ctrl+Q), and a timer for the Mage's Guided Sheep. From NoammAddons' Abilities.
 */
object ClassAbilities {
	@JvmField
	val ultimateKey = KeybindModuleSetting(
		id = "ultimate_key",
		label = "Ultimate",
		description = "Uses your class ultimate, as dropping an item would.",
	)

	@JvmField
	val abilityKey = KeybindModuleSetting(
		id = "ability_key",
		label = "Ability",
		description = "Uses your class ability, as dropping a stack would.",
	)

	@JvmField
	val sheepTimer = ToggleModuleSetting(
		id = "sheep_timer",
		label = "Guided Sheep timer",
		defaultValue = false,
		description = "A HUD timer for the Mage's Guided Sheep: counts down its cooldown, then says it's ready. Shown while you play Mage.",
	)

	@JvmField
	val module = Module(
		id = "class_abilities",
		name = "Class Abilities",
		description = "Ability keys and timers",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(ultimateKey, abilityKey, sheepTimer),
	)

	private const val SHEEP_COOLDOWN_MILLIS = 10_000L

	private val FORMATTING = Regex("§.")
	private val onCooldown = Regex("""^This ability is on cooldown for (\d+)s\.""")

	@Volatile
	private var sheepReadyAt = 0L

	@Volatile
	private var sheepUsedAt = 0L

	private var ultimateWasDown = false
	private var abilityWasDown = false
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(SheepElement())
	}

	fun tick(client: Minecraft) {
		if (!module.enabled) return
		val usable = client.gui.screen() == null && DungeonLocation.inDungeon && DungeonRun.started
		val ultimate = usable && ultimateKey.isDown(client.window)
		val ability = usable && abilityKey.isDown(client.window)
		if (ultimate && !ultimateWasDown) use(client, ultimate = true)
		if (ability && !abilityWasDown) use(client, ultimate = false)
		ultimateWasDown = ultimate
		abilityWasDown = ability
	}

	private fun use(client: Minecraft, ultimate: Boolean) {
		val action = if (ultimate) ServerboundPlayerActionPacket.Action.DROP_ITEM else ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
		client.connection?.send(ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN))
	}

	private fun isMage(): Boolean {
		val self = Minecraft.getInstance().player?.name?.string ?: return false
		return DungeonTeam.classOf(self) == DungeonTeam.DungeonClass.MAGE
	}

	private fun sheepUsed() {
		val now = System.currentTimeMillis()
		// The cast's chat line and its sheep appearing are the same use.
		if (now - sheepUsedAt < 1_000L) return
		sheepUsedAt = now
		sheepReadyAt = now + SHEEP_COOLDOWN_MILLIS
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled || !sheepTimer.value) return
		val line = message.string.replace(FORMATTING, "").trim()
		when {
			line == "Used Guided Sheep!" -> sheepUsed()
			line.startsWith("Guided Sheep is now available") -> sheepReadyAt = 0L
			// Pressed too early: Hypixel's count is the true one.
			isMage() && sheepReadyAt != 0L -> onCooldown.find(line)?.let {
				sheepReadyAt = System.currentTimeMillis() + it.groupValues[1].toLong() * 1000L
			}
		}
	}

	/** A sheep appearing right on you is your own Guided Sheep. On the network thread. */
	@JvmStatic
	fun onAddEntity(packet: ClientboundAddEntityPacket) {
		if (!module.enabled || !sheepTimer.value || packet.type != EntityTypes.SHEEP) return
		try {
			val player = Minecraft.getInstance().player ?: return
			if (!DungeonLocation.inDungeon || !isMage()) return
			if (player.position().distanceToSqr(packet.x, packet.y, packet.z) > 4.0) return
			sheepUsed()
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.warn("Class Abilities could not read a spawned entity", error)
		}
	}

	private fun millisLeft(): Long = (sheepReadyAt - System.currentTimeMillis()).coerceAtLeast(0L)

	private class SheepElement : HudElement("guided_sheep", "Guided Sheep", 0.46, 0.62) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(LABEL + "Ready")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && sheepTimer.value && (DebugOverrides.sampleHudValues || (DungeonLocation.inDungeon && isMage()))

		override fun showInEditor(): Boolean = module.enabled && sheepTimer.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, if (DebugOverrides.sampleHudValues) 8_400L else millisLeft())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 8_400L)

		private fun draw(context: GuiGraphicsExtractor, millis: Long) {
			val value = if (millis <= 0L) "Ready" else String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
			context.text(font, LABEL, 0, 0, 0xFFFFFFFF.toInt())
			context.text(font, value, font.width(LABEL), 0, if (millis <= 0L) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())
		}

		companion object {
			const val LABEL = "Sheep: "
		}
	}
}
