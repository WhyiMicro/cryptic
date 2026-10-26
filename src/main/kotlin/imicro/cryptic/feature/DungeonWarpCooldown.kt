package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Counts down the thirty seconds before another dungeon can be entered, starting from Hypixel's
 * entry line, fading red to green. Shown before the run starts or back in the Dungeon Hub, while
 * time is left. Inspired by Devonian's Warp Cooldown.
 */
object DungeonWarpCooldown {
	private const val COOLDOWN_MILLIS = 30_000L
	private const val HUB = "Dungeon Hub"
	private const val LABEL = "Warp: "
	private const val LABEL_COLOR = 0xFFFFFFFF.toInt()

	private val FORMATTING = Regex("§.")

	/** "[MVP+] Name entered The Catacombs, Floor VII!", with MM in front for Master Mode. */
	private val entered = Regex(
		"""^(?:\[[^\]]+] )?\w{1,16} entered (?:MM )?The Catacombs, (?:Floor [IVX]+|Entrance)!""",
	)

	@JvmField
	val module = Module(
		id = "dungeon_warp_cooldown",
		name = "Dungeon Warp Cooldown",
		description = "Time until the next dungeon",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
	)

	@Volatile
	private var readyAt = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(CooldownElement())
	}

	/** A chat packet. The message is framed in dash rules, so every line is tried. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		val text = message.string
		if ("entered" !in text) return
		for (raw in text.split('\n')) {
			if (entered.containsMatchIn(raw.replace(FORMATTING, "").trim())) {
				readyAt = System.currentTimeMillis() + COOLDOWN_MILLIS
				return
			}
		}
	}

	private fun millisLeft(): Long {
		if (DebugOverrides.sampleHudValues) return 12_300L
		return (readyAt - System.currentTimeMillis()).coerceAtLeast(0L)
	}

	private fun inPlace(): Boolean =
		DebugOverrides.sampleHudValues ||
			(DungeonLocation.inDungeon && !DungeonRun.started) ||
			(SkyblockLocation.onSkyblock && SkyblockLocation.area == HUB)

	private fun colorFor(millis: Long): Int {
		val left = (millis.toFloat() / COOLDOWN_MILLIS).coerceIn(0f, 1f)
		val red = if (left >= 0.5f) 0xFF else (0x55 + (0xFF - 0x55) * left * 2f).toInt()
		val green = if (left <= 0.5f) 0xFF else (0xFF - (0xFF - 0x55) * (left - 0.5f) * 2f).toInt()
		return (0xFF shl 24) or (red shl 16) or (green shl 8) or 0x55
	}

	private class CooldownElement : HudElement("dungeon_warp_cooldown", "Dungeon Warp Cooldown", 0.46, 0.20) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(LABEL + "30.0s")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = module.enabled && millisLeft() > 0L && inPlace()

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) = draw(context, millisLeft())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 12_300L)

		private fun draw(context: GuiGraphicsExtractor, millis: Long) {
			val value = String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
			val x = (width - font.width(LABEL + value)) / 2
			context.text(font, LABEL, x, 0, LABEL_COLOR)
			context.text(font, value, x + font.width(LABEL), 0, colorFor(millis))
		}
	}
}
