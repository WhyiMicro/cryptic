package imicro.cryptic.feature

import imicro.cryptic.gui.ModuleRegistry
import net.minecraft.client.Minecraft

/** Keeps vanilla sprint enabled while the player is moving forward. */
object AutoSprint {
	fun tick(client: Minecraft) {
		if (!ModuleRegistry.autoSprint.enabled) return

		// Already sprinting is the common case; re-asserting the flag every tick
		// only costs entity-data work for no change in behavior.
		val player = client.player ?: return
		if (player.isSprinting) return
		if (player.input.hasForwardImpulse() && !player.isCrouching) {
			player.setSprinting(true)
		}
	}
}
