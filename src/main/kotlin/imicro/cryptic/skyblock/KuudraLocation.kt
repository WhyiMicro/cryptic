package imicro.cryptic.skyblock

import net.minecraft.network.chat.Component

/**
 * Which Kuudra tier you're in. The sidebar's "Kuudra's Hollow (T5)" says so from the start and after
 * a rejoin; the line that starts a run backs it up.
 */
object KuudraLocation {
	val TIERS = listOf("Basic", "Hot", "Burning", "Fiery", "Infernal")

	private val FORMATTING = Regex("§.")
	private val sidebarTier = Regex("""Kuudra's Hollow.*T([1-5])""")
	private val entered = Regex("""entered Kuudra's Hollow, (\w+) Tier!""")

	private var heard: Int? = null

	val inKuudra: Boolean get() = SkyblockLocation.onSkyblock && SkyblockLocation.area?.startsWith("Kuudra") == true

	/** 1 (Basic) to 5 (Infernal), or null outside Kuudra. */
	val tier: Int?
		get() {
			if (!inKuudra) {
				heard = null
				return null
			}
			return SkyblockLocation.sidebarMatch(sidebarTier)?.groupValues?.get(1)?.toInt() ?: heard
		}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		val named = entered.find(message.string.replace(FORMATTING, ""))?.groupValues?.get(1) ?: return
		TIERS.indexOf(named).takeIf { it >= 0 }?.let { heard = it + 1 }
	}
}
