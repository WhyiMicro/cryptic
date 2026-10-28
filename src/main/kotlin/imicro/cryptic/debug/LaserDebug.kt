package imicro.cryptic.debug

import imicro.cryptic.Cryptic
import imicro.cryptic.feature.Toasts
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Guardian
import java.nio.file.Files
import java.util.Locale

/**
 * `/cryptic debug lasers`: what a boss's beams are made of. Voidgloom's laser phase comes from an
 * invisible guardian above it; this shows every guardian nearby (hidden ones included), what it
 * rides, and whom it aims at, and with `watch` records how that changes and which particles fall
 * around you, to `config/cryptic/debug/lasers.txt`.
 */
object LaserDebug {
	private const val REACH = 48.0
	private const val SAMPLES_PER_KIND = 6

	private val client: Minecraft get() = Minecraft.getInstance()
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic/debug/lasers.txt")

	private var watchUntil = 0L
	private val log = ArrayList<String>()
	private var startedAt = 0L

	/** Each guardian's last state, so only changes are written while watching. */
	private val lastState = HashMap<Int, String>()

	/** Particles by kind (type and parameters): how many, and where the first few were. */
	private class Kind(var count: Int = 0, val samples: MutableList<String> = ArrayList())

	private val particles = LinkedHashMap<String, Kind>()

	private fun f(value: Double) = String.format(Locale.US, "%.2f", value)

	private fun at(entity: Entity) = "${f(entity.x)} ${f(entity.y)} ${f(entity.z)}"

	private fun describe(entity: Entity): String = "${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path}#${entity.id}" +
		(entity.customName?.let { " \"${it.string}\"" } ?: "")

	/** One guardian, as a line. */
	private fun state(guardian: Guardian): String {
		val target = guardian.activeAttackTarget
		return buildString {
			append(describe(guardian)).append(" at ").append(at(guardian))
			append(if (guardian.isInvisible) " invisible" else " visible")
			guardian.vehicle?.let { append(" riding ").append(describe(it)) }
			if (guardian.passengers.isNotEmpty()) append(" carrying ").append(guardian.passengers.joinToString { describe(it) })
			if (guardian.hasActiveAttackTarget()) {
				append(" | beam to ").append(target?.let { "${describe(it)} at ${at(it)}" } ?: "an entity not loaded")
				append(", charge ").append(f(guardian.getAttackAnimationScale(0f).toDouble()))
				append(" of ").append(guardian.attackDuration).append("t")
			} else {
				append(" | no beam")
			}
		}
	}

	private fun guardians(): List<Guardian> {
		val player = client.player ?: return emptyList()
		val level = client.level ?: return emptyList()
		return level.entitiesForRendering().filterIsInstance<Guardian>().filter { it.distanceTo(player) <= REACH }
	}

	/** The guardians nearby right now. */
	fun snapshot(): List<String> {
		val found = guardians()
		if (found.isEmpty()) return listOf("No guardians within ${REACH.toInt()} blocks.")
		return listOf("${found.size} guardians within ${REACH.toInt()} blocks:") + found.map { " " + state(it) }
	}

	/** Records for [seconds], then saves. */
	fun watch(seconds: Int): String {
		log.clear()
		lastState.clear()
		particles.clear()
		startedAt = System.currentTimeMillis()
		watchUntil = startedAt + seconds * 1000L
		log += "==== Watching for ${seconds}s from ${java.time.LocalTime.now().withNano(0)}"
		client.player?.let { log += "You at ${at(it)}" }
		return "Recording guardians and particles for ${seconds}s. Saved to $file when done."
	}

	fun tick() {
		if (watchUntil == 0L) return
		val now = System.currentTimeMillis()
		val time = "+${(now - startedAt) / 50}t"
		for (guardian in guardians()) {
			val state = state(guardian)
			// Positions drift every tick; a change of beam, target or visibility is what's wanted.
			val key = state.substringAfter(" | ") + (if (guardian.isInvisible) "i" else "v") + (guardian.vehicle?.id ?: -1)
			if (lastState.put(guardian.id, key) != key) log += "$time $state"
		}
		if (now < watchUntil) return
		watchUntil = 0L
		log += "==== Particles near you, by kind"
		for ((kind, seen) in particles) {
			log += "$kind x${seen.count}"
			seen.samples.forEach { log += "   $it" }
		}
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, log.joinToString("\n", postfix = "\n\n"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
		}.onFailure { Cryptic.LOGGER.warn("Couldn't save the laser log", it) }
		Toasts.show("Laser debug", "Saved §f${lastState.size} §7guardians and §f${particles.size} §7particle kinds")
	}

	/** A particle, on the client thread only. Counted by kind while watching. */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (watchUntil == 0L || !client.isSameThread) return
		val player = client.player ?: return
		if (player.distanceToSqr(packet.x, packet.y, packet.z) > REACH * REACH) return
		val type = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle.type)?.path ?: "?"
		val kind = "$type count=${packet.count} speed=${packet.maxSpeed} spread=${packet.xDist}/${packet.yDist}/${packet.zDist}"
		val seen = particles.getOrPut(kind) { Kind() }
		seen.count++
		if (seen.samples.size < SAMPLES_PER_KIND) {
			seen.samples += "+${(System.currentTimeMillis() - startedAt) / 50}t at ${f(packet.x)} ${f(packet.y)} ${f(packet.z)}"
		}
	}
}
