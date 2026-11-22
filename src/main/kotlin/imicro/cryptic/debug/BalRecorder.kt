package imicro.cryptic.debug

import imicro.cryptic.Cryptic
import imicro.cryptic.feature.HollowsBounds
import imicro.cryptic.feature.HollowsMap
import imicro.cryptic.feature.NucleusQol
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.cubemob.MagmaCube
import net.minecraft.world.phys.Vec3
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * `/cryptic debug balspawn`: writes what a Bal spawn timer needs to `config/cryptic/bal-spawn.log`.
 * Bal's every size from first sight to removal and why it went, where it stood against the Topaz
 * crystal, who was inside Khazad-dûm, whether you were near enough to see it, and the chat lines
 * and particles around it, each stamped with the time and the server tick.
 */
object BalRecorder {
	var active = false
		private set

	private var writer: BufferedWriter? = null
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("bal-spawn.log")

	private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
	private val FORMATTING = Regex("§.")

	/** Each magma cube in the area that isn't a plain Yog: last size seen, and its entity. */
	private class Watched(val cube: MagmaCube, var size: Int, var name: String?, val firstTick: Long)

	private val watched = HashMap<Int, Watched>()
	private var wasInside: Boolean? = null
	private var playersInside: Set<String>? = null
	private var lastZone: String? = null
	private var lastHeartbeat = 0L
	private val particleSeen = HashMap<String, Long>()

	/** Yogs are always this size; any other is Bal (or one growing into it). */
	private const val YOG_SIZE = 3

	private const val HEARTBEAT_MS = 30_000L

	fun toggle(): List<String> {
		active = !active
		if (!active) {
			log("Recording stopped.")
			writer?.close()
			writer = null
			watched.clear()
			return listOf("§7Bal recording §coff§7. It's in §fconfig/cryptic/bal-spawn.log§7.")
		}
		runCatching {
			Files.createDirectories(file.parent)
			writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
		}.onFailure { Cryptic.LOGGER.warn("Could not open the Bal log", it) }
		wasInside = null
		playersInside = null
		lastZone = null
		lastHeartbeat = 0L
		log("Recording started. ${where()}")
		return listOf(
			"§7Bal recording §aon§7, into §fconfig/cryptic/bal-spawn.log§7. Stay for a few spawns and kills, step out of",
			"§7Khazad-dûm and back, and leave render distance for a while; §f/cryptic debug balspawn §7again stops it.",
		)
	}

	private fun log(line: String) {
		val stamped = "${LocalTime.now().format(TIME)} t${ServerTicks.total} phase ${imicro.cryptic.feature.HollowsClock.phase} $line"
		Cryptic.LOGGER.info("[bal] $stamped")
		runCatching {
			writer?.apply {
				write(stamped)
				newLine()
				flush()
			}
		}
	}

	private fun fmt(v: Vec3) = "%.2f %.2f %.2f".format(v.x, v.y, v.z)

	private fun topaz(): Vec3? = NucleusQol.crystalSpaceCenter("Topaz")

	private fun relative(v: Vec3): String = topaz()?.let { "(from Topaz ${fmt(v.subtract(it))})" } ?: "(Topaz not found)"

	private fun where(): String {
		val player = Minecraft.getInstance().player ?: return "No player."
		val area = HollowsBounds.inside(HollowsMap.Place.KHAZAD_DUM)
		return "You at ${fmt(player.position())}, zone '${SkyblockLocation.zone()}', Topaz ${topaz()?.let(::fmt) ?: "not found"}, " +
			"area ${area?.let { "x %.0f..%.0f y %.0f..%.0f z %.0f..%.0f".format(it.minX, it.maxX, it.minY, it.maxY, it.minZ, it.maxZ) } ?: "unknown"}"
	}

	fun tick(client: Minecraft) {
		if (!active) return
		val level = client.level ?: return
		val player = client.player ?: return
		val area = HollowsBounds.inside(HollowsMap.Place.KHAZAD_DUM)

		val zone = SkyblockLocation.zone()
		if (zone != lastZone) {
			log("Zone '$lastZone' -> '$zone' at ${fmt(player.position())}")
			lastZone = zone
		}
		if (area != null) {
			val inside = area.contains(player.position())
			if (inside != wasInside) {
				log("You ${if (inside) "entered" else "left"} the area at ${fmt(player.position())}")
				wasInside = inside
			}
			// Hypixel's NPCs are version 2 UUIDs; real players 4. Only players in render range show.
			val others = level.players().filter { it !== player && it.uuid.version() == 4 && area.contains(it.position()) }
				.mapTo(sortedSetOf()) { it.gameProfile.name() }
			if (others != playersInside) {
				log("Other players seen inside: ${others.size} ${others.joinToString()}")
				playersInside = others
			}
		}

		val cubes = level.entitiesForRendering().filterIsInstance<MagmaCube>()
			.filter { area == null || area.inflate(8.0).contains(it.position()) }
		for (cube in cubes) {
			val name = nameplate(client, cube)
			val known = watched[cube.id]
			if (known == null) {
				if (cube.size == YOG_SIZE && name?.contains("Bal") != true) continue
				watched[cube.id] = Watched(cube, cube.size, name, ServerTicks.total)
				log("Cube #${cube.id} seen: size ${cube.size}, at ${fmt(cube.position())} ${relative(cube.position())}, " +
					"%.1f blocks from you, name '${name ?: "-"}'".format(cube.position().distanceTo(player.position())))
				continue
			}
			if (cube.size != known.size) {
				log("Cube #${cube.id} size ${known.size} -> ${cube.size} at ${fmt(cube.position())}")
				known.size = cube.size
			}
			if (name != known.name) {
				log("Cube #${cube.id} name '${known.name ?: "-"}' -> '${name ?: "-"}'")
				known.name = name
			}
		}
		val iterator = watched.values.iterator()
		while (iterator.hasNext()) {
			val w = iterator.next()
			if (!w.cube.isRemoved) continue
			log("Cube #${w.cube.id} gone: ${w.cube.removalReason}, last size ${w.size}, at ${fmt(w.cube.position())}, " +
				"%.1f blocks from you, seen for %d ticks".format(w.cube.position().distanceTo(player.position()), ServerTicks.total - w.firstTick))
			iterator.remove()
		}

		val now = System.currentTimeMillis()
		if (now - lastHeartbeat >= HEARTBEAT_MS) {
			lastHeartbeat = now
			val t = topaz()
			val loaded = t != null && level.hasChunk(net.minecraft.util.Mth.floor(t.x) shr 4, net.minecraft.util.Mth.floor(t.z) shr 4)
			log("Still recording. ${where()}, Topaz chunk loaded: $loaded, ${t?.let { "%.1f blocks from it".format(it.distanceTo(player.position())) } ?: ""}, " +
				"render distance ${client.options.renderDistance().get()}")
		}
	}

	private fun nameplate(client: Minecraft, cube: MagmaCube): String? {
		val level = client.level ?: return null
		val reach = cube.bbWidth / 2.0 + 1.0
		val top = cube.y + cube.bbHeight + 3.0
		return level.entitiesForRendering().firstNotNullOfOrNull { entity ->
			if (entity !is ArmorStand || !entity.hasCustomName()) return@firstNotNullOfOrNull null
			if (Math.abs(entity.x - cube.x) > reach || Math.abs(entity.z - cube.z) > reach) return@firstNotNullOfOrNull null
			if (entity.y < cube.y || entity.y > top) return@firstNotNullOfOrNull null
			entity.customName?.string?.replace(FORMATTING, "")
		}
	}

	/** Chat in or about Khazad-dûm. Client thread. */
	fun onChat(content: Component) {
		if (!active) return
		val text = content.string.replace(FORMATTING, "").trim()
		if (text.isEmpty()) return
		val inside = wasInside == true || SkyblockLocation.zone() == "Khazad-dûm"
		if (inside || Regex("""\b(Bal|Yog|Khazad)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) log("Chat: $text")
	}

	/** Particles near the Topaz, each kind once a second, for a spawn's sign. */
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (!active || !Minecraft.getInstance().isSameThread) return
		val t = topaz() ?: return
		val at = Vec3(packet.x, packet.y, packet.z)
		if (at.distanceTo(t) > 40.0) return
		val kind = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle.type)?.path ?: "?"
		val now = System.currentTimeMillis()
		if (now - (particleSeen[kind] ?: 0L) < 1_000L) return
		particleSeen[kind] = now
		log("Particle $kind x${packet.count} at ${fmt(at)} ${relative(at)}")
	}
}
