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
 * Outlines Maxor, Storm, Goldor and Necron during the Floor 7 boss fight.
 *
 * All four are ordinary withers wearing Hypixel's own skins, which is how
 * NoammAddons (CC0) identifies them too, and its four colours are the defaults
 * here.
 *
 * The outline itself is the one the game already draws around glowing
 * entities: the render state's outline colour is all that decides whether an
 * entity gets one, so Cryptic sets it directly and never touches glowing, team
 * colours, or anything the server controls. [BetterGlow] is what makes that
 * outline a clean line rather than a dark-fringed one.
 */
object WitherOutline {
	/**
	 * Hypixel parks decoy withers in the arena with the spawn timer pinned at
	 * its maximum. NoammAddons skips those, and outlining them would put a line
	 * around a boss that is not there yet.
	 */
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

	/** Listed on the F7/M7 QOL card. */
	val settings: List<ModuleSetting> = listOf(
		enabled, perBossColors, color, maxorColor, stormColor, goldorColor, necronColor,
	)

	/** True while the withers are to be outlined. */
	val active: Boolean get() = F7Qol.module.enabled && enabled.value

	/** True while the boss bar has to be read to know which colour to use. */
	val needsBossTracking: Boolean get() = active && perBossColors.value

	/**
	 * The colour to outline [entity] with, or [EntityRenderState.NO_OUTLINE] to
	 * leave it alone.
	 *
	 * This is asked once per entity per frame, so the checks are ordered
	 * cheapest and most selective first. Invisible withers are passed over
	 * because Hypixel hides Goldor while his terminals are running, and an
	 * outline would give away a boss the fight means to keep out of sight.
	 */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (!active || entity !is WitherBoss) return EntityRenderState.NO_OUTLINE
		if (!DungeonLocation.inFloor7 && !DebugOverrides.outlineEveryWither) return EntityRenderState.NO_OUTLINE
		if (entity.isInvisible || entity.invulnerableTicks == DECOY_INVULNERABLE_TICKS) {
			return EntityRenderState.NO_OUTLINE
		}

		// The outline is written into a mask the game later blends, where an
		// alpha below full would come out faded.
		return ARGB.opaque(currentColor())
	}

	private fun currentColor(): Int {
		if (!perBossColors.value) return color.rgb

		return when (DungeonBoss.current) {
			DungeonBoss.Wither.MAXOR -> maxorColor.rgb
			DungeonBoss.Wither.STORM -> stormColor.rgb
			DungeonBoss.Wither.GOLDOR -> goldorColor.rgb
			DungeonBoss.Wither.NECRON -> necronColor.rgb
			// Between phases there is no boss bar to read, and the shared
			// colour is a better guess than picking one of the four.
			null -> color.rgb
		}
	}
}
