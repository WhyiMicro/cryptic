package imicro.cryptic.slayer

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand

/** The slayer bosses, under every name Hypixel gives them (tier V of some has its own). */
enum class SlayerType(val title: String, val names: List<String>) {
	REVENANT("Revenant Horror", listOf("Revenant Horror", "Atoned Horror")),
	TARANTULA("Tarantula Broodfather", listOf("Tarantula Broodfather", "Conjoined Brood")),
	SVEN("Sven Packmaster", listOf("Sven Packmaster")),
	VOIDGLOOM("Voidgloom Seraph", listOf("Voidgloom Seraph")),
	INFERNO("Inferno Demonlord", listOf("Inferno Demonlord")),
	RIFTSTALKER("Riftstalker Bloodfiend", listOf("Riftstalker Bloodfiend", "Bloodfiend")),
}

/**
 * Every slayer boss loaded, and whose it is. Hypixel puts three stands over a boss: its name (with
 * hits or health), `<player>'s SLAYER BOSS` above that, and the time left to kill it on top. The
 * name stand is spawned right after the boss, so its id is one past it. Read once a tick.
 */
object SlayerBosses {
	private const val NAME_OFFSET = 1

	/** The stands over a boss are within this of its name stand. */
	private const val STACK_WIDTH = 0.6
	private const val STACK_HEIGHT = 1.5

	private val formattingPattern = Regex("§.")

	/** Longest first, so "Bloodfiend" doesn't take "Riftstalker Bloodfiend"'s place. */
	private val byName: List<Pair<String, SlayerType>> =
		SlayerType.entries.flatMap { type -> type.names.map { it to type } }.sortedByDescending { it.first.length }

	private val namePattern = Regex("""(${byName.joinToString("|") { Regex.escape(it.first) }})(?: ([IVX]+)\b)?""")

	private val TIERS = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5)

	/** Tier V forms that may leave the numeral off. */
	private val TIER_FIVE_NAMES = setOf("Atoned Horror", "Conjoined Brood", "Bloodfiend")

	/** `woulvii's SLAYER BOSS`; `Spawned by: woulvii` was the old line. */
	private val ownerPattern = Regex("""^(\w{1,16})['’]s SLAYER BOSS$""", RegexOption.IGNORE_CASE)
	private val oldOwnerPattern = Regex("""Spawned by:\s*(\w{1,16})""")

	/** "03:56": how long is left before the boss despawns. */
	private val timerPattern = Regex("""^(\d{1,2}):(\d{2})$""")

	/** Slayer mini-bosses, which spawn while the quest's kills are being made. */
	private val MINIBOSSES = listOf(
		"Revenant Sycophant", "Revenant Champion", "Deformed Revenant", "Atoned Champion", "Atoned Revenant",
		"Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula", "Primordial Jockey", "Primordial Viscount",
		"Pack Enforcer", "Sven Follower", "Sven Alpha",
		"Voidling Devotee", "Voidling Radical", "Voidcrazed Maniac",
		"Flare Demon", "Kindleheart Demon", "Burningsoul Demon",
	)

	/**
	 * A living boss and the stand with its name, which also shows its hits or health. [owner] is the
	 * player who spawned it; [timeLeft] the seconds before it despawns. Either can be missing for a
	 * tick while the stands load.
	 */
	data class Seen(
		val boss: LivingEntity,
		val type: SlayerType,
		val tier: Int,
		val nameStand: ArmorStand,
		val name: String,
		val owner: String?,
		val timeLeft: Int?,
	)

	/** A mini-boss and the stand with its name. */
	data class Miniboss(val mob: LivingEntity, val name: String)

	/** This tick's bosses. */
	var bosses: List<Seen> = emptyList()
		private set

	var minibosses: List<Miniboss> = emptyList()
		private set

	/** [watching] is whether any feature wants the bosses; nothing is scanned otherwise. */
	fun tick(client: Minecraft, watching: Boolean) {
		val level = client.level
		if (!watching || level == null) {
			bosses = emptyList()
			minibosses = emptyList()
			return
		}
		val found = ArrayList<Seen>()
		val minis = ArrayList<Miniboss>()
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand) continue
			val raw = entity.customName?.string ?: continue
			val name = raw.replace(formattingPattern, "")
			val match = namePattern.find(name)
			if (match == null) {
				MINIBOSSES.firstOrNull { it in name }?.let { mini -> mobUnder(level, entity)?.let { minis += Miniboss(it, mini) } }
				continue
			}
			val bossName = match.groupValues[1]
			val type = byName.first { it.first == bossName }.second
			val tier = match.groupValues[2].takeIf { it.isNotEmpty() }?.let(TIERS::get)
				?: if (bossName in TIER_FIVE_NAMES) 5 else continue
			val boss = mobUnder(level, entity) ?: continue
			val (owner, timeLeft) = readStack(level, entity)
			found += Seen(boss, type, tier, entity, name, owner, timeLeft)
		}
		bosses = found
		minibosses = minis
	}

	private fun mobUnder(level: ClientLevel, stand: ArmorStand): LivingEntity? {
		val mob = (stand.vehicle ?: level.getEntity(stand.id - NAME_OFFSET)) as? LivingEntity ?: return null
		return mob.takeIf { it !is ArmorStand && it.isAlive }
	}

	/** The owner and time left, off the stands just over the name. */
	private fun readStack(level: ClientLevel, nameStand: ArmorStand): Pair<String?, Int?> {
		var owner: String? = null
		var ownerDistance = Double.MAX_VALUE
		var timeLeft: Int? = null
		var timerDistance = Double.MAX_VALUE
		val near = level.getEntitiesOfClass(ArmorStand::class.java, nameStand.boundingBox.inflate(STACK_WIDTH, STACK_HEIGHT, STACK_WIDTH)) { other ->
			other !== nameStand && other.y >= nameStand.y && other.customName != null
		}
		// Two bosses close together: the nearer stands are this one's.
		for (other in near) {
			val text = other.customName?.string?.replace(formattingPattern, "")?.trim() ?: continue
			val distance = other.distanceToSqr(nameStand)
			val who = (ownerPattern.matchEntire(text) ?: oldOwnerPattern.find(text))?.groupValues?.get(1)
			if (who != null) {
				if (distance < ownerDistance) {
					owner = who
					ownerDistance = distance
				}
				continue
			}
			val left = timerPattern.matchEntire(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() } ?: continue
			if (distance < timerDistance) {
				timeLeft = left
				timerDistance = distance
			}
		}
		return owner to timeLeft
	}

	/** Whether [name] is you. */
	fun isMe(name: String?): Boolean =
		name != null && name.equals(Minecraft.getInstance().player?.gameProfile?.name, ignoreCase = true)

	/** Your own boss, if one is loaded. */
	fun mine(): Seen? = bosses.firstOrNull { isMe(it.owner) }

	fun byId(entityId: Int): Seen? = bosses.firstOrNull { it.boss.id == entityId }

	/** What the scan sees, for `/cryptic debug slayer`. */
	fun describe(client: Minecraft): List<String> {
		val level = client.level ?: return listOf("Not in a world.")
		val stands = level.entitiesForRendering()
			.filterIsInstance<ArmorStand>()
			.mapNotNull { it.customName?.string?.replace(formattingPattern, "")?.takeIf { n -> n.isNotBlank() } }
		return listOf("${stands.size} named armour stands nearby:") +
			stands.take(20).map { "  \"$it\"" } +
			"Bosses: ${bosses.size}" +
			bosses.map { "  ${it.type.title} ${it.tier} (entity ${it.boss.id}): owner ${it.owner ?: "unknown"}, ${it.timeLeft?.let { s -> "${s}s left" } ?: "no timer"}" } +
			"Mini-bosses: ${minibosses.size}" +
			minibosses.map { "  ${it.name} (entity ${it.mob.id})" }
	}
}
