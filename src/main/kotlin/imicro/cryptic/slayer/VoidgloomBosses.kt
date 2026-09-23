package imicro.cryptic.slayer

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Which Voidgloom Seraph belongs to whom.
 *
 * SkyBlock hangs three invisible armour stands on every slayer boss, and they
 * are spawned in one burst immediately after the boss itself — so their entity
 * ids are the boss's plus one, two and three. The first carries the boss's name
 * and tier, the third carries `Spawned by: <player>`, and between them they are
 * the only place the client is told whose boss this is.
 *
 * The offsets are Athen's (BSD 3-Clause, Copyright (c) 2025-2026 Starred),
 * whose `SlayerInfo` reads the same two stands the same way; the licence is in
 * `licenses/Athen-LICENSE.txt`. They are worth stating plainly because they are
 * the fragile part: if Hypixel ever spawns those stands in another order this
 * stops finding anything, which is why nothing here throws and every step
 * simply gives up.
 */
object VoidgloomBosses {
	/** How far past the boss the name stand sits, and the owner stand. */
	private const val NAME_OFFSET = 1
	private const val OWNER_OFFSET = 3

	private const val BOSS_NAME = "Voidgloom Seraph"

	private val ownerPattern = Regex("""Spawned by:\s*(\w+)""")
	private val tierPattern = Regex("""$BOSS_NAME ([IVX]+)""")

	private val formattingPattern = Regex("§.")

	/** Roman numerals as far as a slayer goes. */
	private val TIERS = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5)

	/** One boss that is in the world right now, and what is known about it. */
	data class Sighting(val boss: Entity, val owner: String, val tier: Int)

	/**
	 * Every Voidgloom in the world whose owner can be read.
	 *
	 * Driven off the owner stands rather than the bosses: there is exactly one
	 * per boss, it is the thing that names a carry, and a boss whose stands have
	 * not arrived yet is one nothing can be said about anyway.
	 *
	 * Only called while a carry is active, so the scan costs nothing the rest of
	 * the time.
	 */
	fun sightings(client: Minecraft): List<Sighting> {
		val level = client.level ?: return emptyList()
		val found = mutableListOf<Sighting>()

		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand) continue
			val name = entity.customName?.string?.replace(formattingPattern, "") ?: continue
			val owner = ownerPattern.find(name)?.groupValues?.get(1) ?: continue

			val boss = level.getEntity(entity.id - OWNER_OFFSET) ?: continue
			val tier = tierOf(level.getEntity(entity.id - OWNER_OFFSET + NAME_OFFSET)) ?: continue
			found += Sighting(boss, owner, tier)
		}

		return found
	}

	/** The tier off the name stand, or null when it is not a Voidgloom at all. */
	private fun tierOf(nameStand: Entity?): Int? {
		val name = nameStand?.customName?.string?.replace(formattingPattern, "") ?: return null
		val numeral = tierPattern.find(name)?.groupValues?.get(1) ?: return null
		return TIERS[numeral]
	}

	/**
	 * Everything the scan can see, for `/cryptic debug carry`.
	 *
	 * The stand offsets are the one part of this that cannot be checked from
	 * outside a lobby with a boss in it.
	 */
	fun describe(client: Minecraft): List<String> {
		val level = client.level ?: return listOf("Not in a world.")

		val stands = level.entitiesForRendering()
			.filterIsInstance<ArmorStand>()
			.mapNotNull { it.customName?.string?.replace(formattingPattern, "")?.takeIf { n -> n.isNotBlank() } }

		val seen = sightings(client)
		return listOf("${stands.size} named armour stands nearby:") +
			stands.take(20).map { "  \"$it\"" } +
			"Read as ${seen.size} boss(es):" +
			seen.map { "  ${it.owner} — tier ${it.tier} (entity ${it.boss.id})" }
	}
}
