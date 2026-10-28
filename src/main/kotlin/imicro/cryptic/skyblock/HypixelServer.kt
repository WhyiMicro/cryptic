package imicro.cryptic.skyblock

import net.minecraft.client.Minecraft

/** Whether you're on Hypixel, by the address you joined: for features that only make sense there. */
object HypixelServer {
	val connected: Boolean
		get() = Minecraft.getInstance().currentServer?.ip?.lowercase()?.contains("hypixel") == true
}
