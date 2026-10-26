package imicro.cryptic.slayer

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.acos

/**
 * Which Voidgloom Seraph belongs to whom. Hypixel names each slayer boss with an invisible armour
 * stand spawned right after it (its id one past the boss), and used to add a `Spawned by: <player>`
 * stand too. That line is gone, so the owner is worked out from how the boss behaves:
 *
 * - its summoner hits it, again and again (the strongest sign),
 * - it looks at its summoner (Athen, BSD-3, decides on this alone),
 * - it walks toward them, judged across the ground, since they may be standing well above it,
 * - its summoner stands in the safespot about eleven blocks over it, where it can't teleport and
 *   so stands still (worth little when several players share the spot, as in a group carry),
 * - it stays near them (the nearest player is Meridian's (MIT) rule),
 * - and it teleports to them when they get too far away, which settles it at once.
 *
 * Every nearby player builds up a score; their shares are the odds of each being the owner. The
 * boss goes to a carried player as soon as they're clearly ahead, or after the watch time if
 * they're the favourite then. Anyone else needs to be all but certain, so a carried player's boss
 * isn't handed to a bystander. The weights and thresholds are [Weights]. Nobody owns two bosses at
 * once. A `Spawned by` stand, if Hypixel sends one again, still wins straight away.
 */
object VoidgloomBosses {
	private const val NAME_OFFSET = 1
	private const val OWNER_OFFSET = 3

	private const val BOSS_NAME = "Voidgloom Seraph"

	private val ownerPattern = Regex("""Spawned by:\s*(\w+)""")
	private val tierPattern = Regex("""$BOSS_NAME ([IVX]+)""")

	private val formattingPattern = Regex("§.")

	private val TIERS = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5)


	/** Players further than this from the boss aren't considered. */
	private const val RANGE = 24.0

	/** A jump this far between two looks is a teleport... */
	private const val TELEPORT_JUMP = 5.0

	/** ...and the player it lands this close to called it. */
	private const val TELEPORT_LANDING = 4.0

	/**
	 * How much each sign counts, as the Carry Manager's sliders set them. [hit] and [carrierHit] are
	 * per hit; the rest per look, four times a second. [carriedShare] and [otherShare] are the share
	 * of the odds that settles a boss for a carried player or anyone else; [watchMillis] how long a
	 * carried favourite waits to be given it anyway.
	 */
	data class Weights(
		val hit: Double = 6.0,
		val carrierHit: Double = 6.0,
		val look: Double = 2.0,
		val move: Double = 2.0,
		val near: Double = 1.0,
		val safespot: Double = 3.0,
		val carriedShare: Double = 0.75,
		val otherShare: Double = 0.99,
		val watchMillis: Long = 10_000L,
	)

	@JvmStatic
	var weights: () -> Weights = { Weights() }

	/** The safespot: this far above the boss, and within about a 3x3 of straight over it. */
	private val SAFESPOT_HEIGHT = 8.0..14.0
	private const val SAFESPOT_SPREAD = 1.7

	/** Whether a name is on the carry list, as the Carry Manager says. */
	@JvmStatic
	var carried: (String) -> Boolean = { false }

	/** ...once there's this much to go on: some watching, or a couple of hits. */
	private const val CERTAIN_LOOKS = 8
	private const val CERTAIN_HITS = 2

	/**
	 * An unnamed hit goes to the one player this close who's looking this near the boss, or of
	 * several, the one who swung this recently.
	 */
	private const val HIT_REACH = 10.0
	private const val HIT_AIM = 35.0
	private const val SWING_MILLIS = 300L

	/** A boss walking toward someone counts only when they're this far above or below it at most. */
	private const val MOVE_HEIGHT = 15.0

	data class Sighting(val boss: Entity, val owner: String, val tier: Int, val since: Long)

	/** A living Voidgloom and the stand carrying its name, which also shows its hits or health. */
	data class Seen(val boss: LivingEntity, val tier: Int, val nameStand: ArmorStand, val name: String)

	/** Every Voidgloom loaded, without watching any of them. */
	fun visible(level: ClientLevel): List<Seen> {
		val found = mutableListOf<Seen>()
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand) continue
			val name = entity.customName?.string?.replace(formattingPattern, "") ?: continue
			val tier = tierPattern.find(name)?.groupValues?.get(1)?.let(TIERS::get) ?: continue
			val boss = (entity.vehicle ?: level.getEntity(entity.id - NAME_OFFSET)) as? LivingEntity ?: continue
			if (boss.isAlive) found += Seen(boss, tier, entity, name)
		}
		return found
	}

	/** Who [bossId] has been given to, if anyone yet. */
	fun ownerOf(bossId: Int): String? = owners[bossId]?.name?.takeIf { it != NOBODY }

	private class Watch(val tier: Int, val since: Long, var last: Vec3) {
		val scores = HashMap<String, Double>()

		/** Players you said it isn't. */
		val rejected = HashSet<String>()

		val hits = HashMap<String, Int>()
		var looks = 0

		/** When the boss last took a hit someone was credited with, so one hit isn't counted twice. */
		var lastHitAt = 0L

		/**
		 * Rejected, or already owns a live boss: nobody spawns two at once. Their score is kept, so
		 * it counts again once their boss is gone.
		 */
		fun ruledOut(name: String) = name in rejected || owners.values.any { it.name == name }

		fun favourite(): String? = scores.filterKeys { !ruledOut(it) }.maxByOrNull { it.value }?.key

		fun share(name: String): Double {
			val total = scores.filterKeys { !ruledOut(it) }.values.sum().takeIf { it > 0 } ?: return 0.0
			return (scores[name] ?: 0.0) / total
		}

		/** The favourite, once they're far enough ahead with enough seen. */
		fun certain(): String? {
			val best = favourite() ?: return null
			val weights = weights()
			if (share(best) < if (carried(best)) weights.carriedShare else weights.otherShare) return null
			return best.takeIf { looks >= CERTAIN_LOOKS || (hits[best] ?: 0) >= CERTAIN_HITS }
		}

		fun odds(): List<Pair<String, Int>> {
			val total = scores.values.sum().takeIf { it > 0 } ?: return emptyList()
			return scores.entries.sortedByDescending { it.value }.map { it.key to (it.value / total * 100).toInt() }
		}
	}

	/** [watch] is kept, so a wrong guess can fall to the next likeliest player. */
	private class Owner(val name: String, val tier: Int, val since: Long, val watch: Watch?)

	/** Bosses whose owner is decided, by entity id. */
	private val owners = HashMap<Int, Owner>()

	/** Bosses still being watched, by entity id. */
	private val watching = HashMap<Int, Watch>()

	/**
	 * Every Voidgloom whose owner is decided, watching the rest. Called about four times a second
	 * while a carry is active.
	 */
	fun sightings(client: Minecraft): List<Sighting> {
		val level = client.level ?: return emptyList()
		owners.keys.removeIf { id -> level.getEntity(id)?.isAlive != true }
		watching.keys.removeIf { id -> level.getEntity(id)?.isAlive != true }
		val now = System.currentTimeMillis()
		val found = mutableListOf<Sighting>()

		for ((boss, tier, entity) in visible(level)) {
			owners[boss.id]?.let { found += Sighting(boss, it.name, it.tier, it.since); continue }

			level.getEntity(entity.id - NAME_OFFSET + OWNER_OFFSET)?.customName?.string?.replace(formattingPattern, "")
				?.let { ownerPattern.find(it)?.groupValues?.get(1) }
				?.let { owner ->
					decide(boss.id, owner, tier, now, null)
					found += Sighting(boss, owner, tier, now)
					continue
				}

			val watch = watching.getOrPut(boss.id) { Watch(tier, now, boss.position()) }
			observe(level, boss, watch)?.let { owner ->
				decide(boss.id, owner, tier, watch.since, watch)
				found += Sighting(boss, owner, tier, watch.since)
				continue
			}
			// Past the time, only a carried favourite is given it; anyone else still needs certainty.
			val early = watch.certain()
			val late = watch.favourite()?.takeIf { now - watch.since >= weights().watchMillis && carried(it) }
			if (early != null || late != null) {
				val owner = early ?: late ?: continue
				decide(boss.id, owner, tier, watch.since, watch)
				found += Sighting(boss, owner, tier, watch.since)
			}
		}

		return found
	}

	private fun decide(bossId: Int, owner: String, tier: Int, since: Long, watch: Watch?) {
		watching.remove(bossId)
		owners[bossId] = Owner(owner, tier, since, watch)
	}

	/**
	 * You said the boss isn't [name]'s: it goes to the next likeliest player, or nobody, and [name]
	 * is never given it again.
	 */
	fun reject(bossId: Int, name: String) {
		val owner = owners.remove(bossId) ?: return
		val watch = owner.watch ?: Watch(owner.tier, owner.since, Vec3.ZERO)
		watch.rejected += name
		val next = watch.favourite()
		if (next != null) decide(bossId, next, owner.tier, owner.since, watch)
		else owners[bossId] = Owner(NOBODY, owner.tier, owner.since, watch)
	}

	/** You said whose boss it is: settled as theirs, whatever the guess was. */
	fun assign(bossId: Int, name: String, tier: Int, since: Long) {
		decide(bossId, name, tier, since, watching[bossId] ?: owners[bossId]?.watch)
	}

	/** An owner no carry can match, for a boss nobody else is left to own. */
	private const val NOBODY = " "

	/**
	 * One look at a watched boss: scores every candidate, and returns an owner outright when the
	 * boss has just teleported next to one.
	 */
	private fun observe(level: ClientLevel, boss: LivingEntity, watch: Watch): String? {
		val now = boss.position()
		val moved = now.subtract(watch.last)
		val from = watch.last
		watch.last = now

		// Players who already own a boss are still scored; they just can't be given this one.
		val candidates = level.players().filter { player ->
			player.uuid.version() == 4 && player.isAlive &&
				player.gameProfile.name !in watch.rejected &&
				player.distanceToSqr(boss) <= RANGE * RANGE
		}
		if (candidates.isEmpty()) return null

		if (moved.length() > TELEPORT_JUMP) {
			candidates.filter { !watch.ruledOut(it.gameProfile.name) && it.distanceToSqr(boss) <= TELEPORT_LANDING * TELEPORT_LANDING }
				.minByOrNull { it.distanceToSqr(boss) }
				?.let { return it.gameProfile.name }
		}

		watch.looks++
		val weights = weights()
		val inSafespot = candidates.filter { inSafespot(boss, it) }
		// Across the ground only: the boss walks under its summoner even when they're high above.
		val walked = Vec3(moved.x, 0.0, moved.z)
		for (player in candidates) {
			val look = (1.0 - angleTo(boss, player) / 90.0).coerceAtLeast(0.0)
			val towards = Vec3(player.x - from.x, 0.0, player.z - from.z)
			val withinHeight = kotlin.math.abs(player.y - from.y) <= MOVE_HEIGHT
			val move = if (!withinHeight || walked.length() < 0.05 || towards.length() < 1.0) 0.0 else {
				val cos = walked.normalize().dot(towards.normalize())
				cos.coerceAtLeast(0.0) * (walked.length() / 0.5).coerceAtMost(1.0)
			}
			val near = 1.0 - player.distanceTo(boss) / RANGE
			val safespot = if (player in inSafespot) weights.safespot / inSafespot.size else 0.0
			val score = weights.look * look * look + weights.move * move + weights.near * near + safespot
			watch.scores.merge(player.gameProfile.name, score, Double::plus)
		}
		return null
	}

	private fun inSafespot(boss: LivingEntity, player: Player): Boolean {
		val above = player.y - boss.y
		if (above !in SAFESPOT_HEIGHT) return false
		val dx = player.x - boss.x
		val dz = player.z - boss.z
		return dx * dx + dz * dz <= SAFESPOT_SPREAD * SAFESPOT_SPREAD
	}

	// ---- Hits --------------------------------------------------------------------------------

	/** When each player last swung, by entity id. */
	private val swings = HashMap<Int, Long>()

	/** A player's arm swing, from the animate packet. Client thread. */
	@JvmStatic
	fun onSwing(entityId: Int) {
		if (watching.isEmpty()) return
		swings[entityId] = System.currentTimeMillis()
	}

	/**
	 * A watched boss took damage. Minecraft's damage event can name who caused it. When Hypixel
	 * leaves that out, it goes to the one player within reach who's looking at the boss, or of
	 * several, the one who just swung; when that still leaves more than one, or none, nobody gets
	 * it. Client thread.
	 */
	@JvmStatic
	fun onDamaged(entityId: Int, causeId: Int) {
		val watch = watching[entityId] ?: return
		val level = Minecraft.getInstance().level ?: return
		val now = System.currentTimeMillis()
		val boss = level.getEntity(entityId) as? LivingEntity ?: return
		val attacker = level.getEntity(causeId) as? Player
		if (attacker != null && (attacker.uuid.version() == 4 || attacker === Minecraft.getInstance().player)) {
			hitBy(watch, attacker, boss, now)
			return
		}
		if (now - watch.lastHitAt < 100) return
		val self = Minecraft.getInstance().player
		val lookers = level.players().filter { player ->
			(player.uuid.version() == 4 || player === self) && player.isAlive &&
				player.distanceTo(boss) <= HIT_REACH && aimAt(player, boss) <= HIT_AIM
		}
		val hitter = lookers.singleOrNull()
			?: lookers.filter { (swings[it.id] ?: 0L) >= now - SWING_MILLIS }.singleOrNull()
			?: return
		hitBy(watch, hitter, boss, now)
	}

	/**
	 * A hit by [player]. Your own hits, while someone you're carrying is near the boss, count for
	 * them instead (split if several are), since you only hit your clients' bosses.
	 */
	private fun hitBy(watch: Watch, player: Player, boss: LivingEntity, now: Long) {
		val weights = weights()
		if (player === Minecraft.getInstance().player) {
			val clients = boss.level().players().filter { other ->
				other !== player && other.isAlive && carried(other.gameProfile.name) &&
					!watch.ruledOut(other.gameProfile.name) && other.distanceToSqr(boss) <= RANGE * RANGE
			}
			if (clients.isNotEmpty()) {
				for (client in clients) credit(watch, client.gameProfile.name, weights.carrierHit / clients.size, now)
				return
			}
		}
		credit(watch, player.gameProfile.name, weights.hit, now)
	}

	private fun credit(watch: Watch, name: String, points: Double, now: Long) {
		if (name in watch.rejected) return
		watch.lastHitAt = now
		watch.scores.merge(name, points, Double::plus)
		watch.hits.merge(name, 1, Int::plus)
	}

	/** Degrees between where [player] looks and the middle of [boss]. */
	private fun aimAt(player: Player, boss: LivingEntity): Double {
		val toBoss = boss.boundingBox.center.subtract(player.eyePosition)
		val distance = toBoss.length().takeIf { it >= 0.001 } ?: return 0.0
		val cos = player.getViewVector(1f).normalize().dot(toBoss.scale(1.0 / distance)).coerceIn(-1.0, 1.0)
		return Math.toDegrees(acos(cos))
	}

	/** Degrees between where [boss] looks and [player]'s eyes. */
	private fun angleTo(boss: LivingEntity, player: Player): Double {
		val toPlayer = player.eyePosition.subtract(boss.eyePosition)
		val distance = toPlayer.length().takeIf { it >= 0.001 } ?: return 0.0
		val cos = boss.getViewVector(1f).normalize().dot(toPlayer.scale(1.0 / distance)).coerceIn(-1.0, 1.0)
		return Math.toDegrees(acos(cos))
	}

	/**
	 * A watched boss died before its time was up: it goes to the favourite now, so the kill still
	 * counts. Null when nobody was seen near it.
	 */
	fun settle(bossId: Int): Sighting? {
		val watch = watching[bossId] ?: return null
		val owner = watch.favourite() ?: return null
		decide(bossId, owner, watch.tier, watch.since, watch)
		val boss = Minecraft.getInstance().level?.getEntity(bossId) ?: return null
		return Sighting(boss, owner, watch.tier, watch.since)
	}

	fun reset() {
		owners.clear()
		watching.clear()
		swings.clear()
	}

	/** What the scan sees, for `/cryptic debug carry`. */
	fun describe(client: Minecraft): List<String> {
		val level = client.level ?: return listOf("Not in a world.")
		val now = System.currentTimeMillis()

		val stands = level.entitiesForRendering()
			.filterIsInstance<ArmorStand>()
			.mapNotNull { it.customName?.string?.replace(formattingPattern, "")?.takeIf { n -> n.isNotBlank() } }

		val seen = sightings(client)
		return listOf("${stands.size} named armour stands nearby:") +
			stands.take(20).map { "  \"$it\"" } +
			"Decided: ${seen.size} boss(es)" +
			seen.map { "  ${it.owner} — tier ${it.tier} (entity ${it.boss.id})" } +
			"Still watching: ${watching.size}" +
			watching.map { (id, watch) ->
				val odds = watch.odds().take(3).joinToString(", ") { (name, odds) -> "$name $odds%" + if (watch.ruledOut(name)) " (has a boss)" else "" }.ifEmpty { "nobody near" }
				val hits = watch.hits.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" }
				"  entity $id, ${(now - watch.since) / 1000}s in: $odds (hits: $hits)"
			}
	}
}
