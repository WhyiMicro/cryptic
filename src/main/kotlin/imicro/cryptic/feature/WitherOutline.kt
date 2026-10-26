package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonBoss
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.wither.WitherBoss

/**
 * Outlines Maxor, Storm, Goldor and Necron in the Floor 7 fight, identified by their skins with
 * NoammAddons' colours. Sets the render state's outline colour directly, never glowing or teams;
 * [BetterGlow] makes it a clean line.
 */
object WitherOutline {
	/** Hypixel parks decoy withers with the spawn timer at max; NoammAddons skips them too. */
	private const val DECOY_INVULNERABLE_TICKS = 800

	@JvmField
	val enabled = ToggleModuleSetting(
		id = "wither_outline",
		label = "Wither outline",
		defaultValue = false,
		description = "Outlines Maxor, Storm, Goldor and Necron.",
	)

	@JvmField
	val perBossColors = ToggleModuleSetting(
		id = "wither_per_boss_colors",
		label = "Per-boss colors",
		defaultValue = false,
		description = "Colors each wither of the fight separately instead of all of them alike.",
		visibleIf = { enabled.value },
	)

	@JvmField
	val color = ColorModuleSetting(
		id = "wither_color",
		label = "Outline",
		defaultRgb = 0xFFFFFF,
		visibleIf = { enabled.value && !perBossColors.value },
	)

	@JvmField
	val maxorColor = ColorModuleSetting(
		id = "wither_maxor_color",
		label = "Maxor",
		defaultRgb = 0x5804A4,
		visibleIf = { enabled.value && perBossColors.value },
	)

	@JvmField
	val stormColor = ColorModuleSetting(
		id = "wither_storm_color",
		label = "Storm",
		defaultRgb = 0x00D0FF,
		visibleIf = { enabled.value && perBossColors.value },
	)

	@JvmField
	val goldorColor = ColorModuleSetting(
		id = "wither_goldor_color",
		label = "Goldor",
		defaultRgb = 0xFFFFFF,
		visibleIf = { enabled.value && perBossColors.value },
	)

	@JvmField
	val necronColor = ColorModuleSetting(
		id = "wither_necron_color",
		label = "Necron",
		defaultRgb = 0xFF0000,
		visibleIf = { enabled.value && perBossColors.value },
	)

	/** Listed on the F7/M7 Features card. */
	val settings: List<ModuleSetting> = listOf(
		enabled, perBossColors, color, maxorColor, stormColor, goldorColor, necronColor,
	)

	val active: Boolean get() = F7Qol.module.enabled && enabled.value

	/** True while the boss bar has to be read to pick a colour. */
	val needsBossTracking: Boolean get() = active && perBossColors.value

	/**
	 * The outline colour for [entity], or [EntityRenderState.NO_OUTLINE]. Asked per entity per
	 * frame, so cheap checks go first. Invisible withers are skipped: Hypixel hides Goldor during
	 * terminals.
	 */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (!active || entity !is WitherBoss) return EntityRenderState.NO_OUTLINE
		if (!DungeonLocation.inFloor7 && !DebugOverrides.outlineEveryWither) return EntityRenderState.NO_OUTLINE
		if (entity.isInvisible || entity.invulnerableTicks == DECOY_INVULNERABLE_TICKS) {
			return EntityRenderState.NO_OUTLINE
		}

		// The outline mask needs full alpha.
		return ARGB.opaque(currentColor())
	}

	private fun currentColor(): Int {
		if (!perBossColors.value) return color.rgb

		return when (DungeonBoss.current) {
			DungeonBoss.Wither.MAXOR -> maxorColor.rgb
			DungeonBoss.Wither.STORM -> stormColor.rgb
			DungeonBoss.Wither.GOLDOR -> goldorColor.rgb
			DungeonBoss.Wither.NECRON -> necronColor.rgb
			// Between phases there's no boss bar; the shared colour is the best guess.
			null -> color.rgb
		}
	}
}
