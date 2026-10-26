package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffects

/**
 * Drops blindness and nausea from the client's copy of the player. The server still thinks you're
 * blind, so nothing else changes; only the fog and wobble go.
 */
object NoDebuff {
	@JvmField
	val blindness = ToggleModuleSetting(
		id = "blindness",
		label = "Blindness",
		defaultValue = true,
		description = "Drops the black fog blindness draws over everything.",
	)

	@JvmField
	val nausea = ToggleModuleSetting(
		id = "nausea",
		label = "Nausea",
		defaultValue = true,
		description = "Drops the screen wobble, which fades out rather than snapping.",
	)

	private val configurableSettings = listOf(blindness, nausea)

	@JvmField
	val module = Module(
		id = "no_debuff",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		name = "No Debuff",
		description = "No blindness or nausea",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = configurableSettings,
	)

	/** Effects are counted down by the client, so removing one once lasts until the server resends it. */
	fun tick(client: Minecraft) {
		if (!module.enabled) return
		val player = client.player ?: return

		if (blindness.value && player.hasEffect(MobEffects.BLINDNESS)) {
			player.removeEffectNoUpdate(MobEffects.BLINDNESS)
		}

		if (nausea.value && player.hasEffect(MobEffects.NAUSEA)) {
			player.removeEffectNoUpdate(MobEffects.NAUSEA)
		}
	}
}
