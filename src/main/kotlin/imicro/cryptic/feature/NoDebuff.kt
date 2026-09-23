package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffects

/**
 * Throws away the potion effects that only make the screen harder to look at.
 *
 * Blindness and nausea change nothing about what you can do — they change what
 * you can see doing it, and in a dungeon that is the difference between reading
 * a terminal and guessing at one. The effect is dropped from the client's own
 * copy of the player rather than from the server's, so Hypixel still believes
 * you are blind and everything that depends on that carries on as normal;
 * only the fog and the wobble go away.
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

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach { if (it is ToggleModuleSetting) it.reset() }
	})

	@JvmField
	val module = Module(
		id = "no_debuff",
		name = "No Debuff",
		description = "Removes blindness and nausea",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurableSettings + reset,
	)

	/**
	 * Effects arrive as packets and are then counted down by the client, so
	 * taking one off once is enough until the server sends it again — there is
	 * nothing here that has to fight the game every frame.
	 */
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
