package imicro.cryptic.feature

import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.monster.cubemob.MagmaCube
import net.minecraft.world.phys.Vec3

/**
 * Seconds until Bal spawns again, over where it rises. From `/cryptic debug balspawn` recordings:
 * Bal is one magma cube that stays at its spot at size 0 between fights. It rises 60 of Hypixel's
 * seconds after it retreats, on a beat of [HollowsClock], once someone is in Khazad-dûm; it grows
 * a size a second to 25 and, after retreating, shrinks a size a second to 0.
 *
 * The retreat is known for sure from its chat line, said to everyone inside. Arriving later, it's
 * worked out from the size Bal is shrinking at; with nothing seen, the timer says so.
 */
object BalTimer {
	@JvmField
	val setting = ToggleModuleSetting(
		id = "bal_timer",
		label = "Bal spawn timer",
		defaultValue = true,
		description = "Seconds until Bal rises again, over the lava where it spawns. A ~ means it's worked out from Bal's size. " +
			"Hidden while Bal is spawning or fighting.",
	)

	/** From the Topaz crystal's middle to the block corner Bal rises at. */
	private val SPAWN_OFFSET = Vec3(1.5, -6.5, 15.5)

	private const val RESPAWN_BEATS = 60

	/** Bal shrinks to 24 on the beat it retreats, then a size a second. */
	private const val FIRST_SHRUNK_SIZE = 24

	/** The size the fight starts at. */
	private const val FIGHT_SIZE = 25

	/** Long past due with Bal out of sight, someone else may have fought it. */
	private const val STALE_BEATS = 30

	/** Yogs are always this size. */
	private const val YOG_SIZE = 3

	/** How close to its spot a cube must be to be Bal waiting or rising. */
	private const val SPOT_REACH = 0.5

	/** Bal wanders this far while fighting. */
	private const val FIGHT_REACH = 16.0

	private const val SHOWN_WITHIN = 96.0

	private const val SHAKE = "The ground starts to shake and the lava bubbles..."
	private const val RETREAT = "The boss looks weak and tired and retreats into the lava..."

	/** The beat Bal retreated on, and whether that's from its chat line. */
	private var retreatBeat = -1L
	private var retreatSure = false

	/** Rising or fighting: the timer is hidden. Since [risenBeat]. */
	private var risen = false
	private var risenBeat = 0L

	/** Risen this long ago with Bal out of sight, its fight may be over unseen. */
	private const val LOST_FIGHT_BEATS = 300

	private var balId = -1
	private var lastSize = -1
	private var shrinking = false

	fun reset() {
		retreatBeat = -1L
		retreatSure = false
		risen = false
		balId = -1
		lastSize = -1
		shrinking = false
	}

	private fun spawnPoint(): Vec3? = NucleusQol.crystalSpaceCenter("Topaz")?.add(SPAWN_OFFSET)

	/** Chat, stripped. Only said to players inside Khazad-dûm. */
	fun onChat(text: String) {
		when (text) {
			SHAKE -> {
				rise()
			}
			RETREAT -> {
				risen = false
				retreatBeat = HollowsClock.beats
				retreatSure = true
			}
		}
	}

	fun tick(client: Minecraft) {
		val level = client.level ?: return
		val spot = spawnPoint() ?: return
		var bal = level.getEntity(balId) as? MagmaCube
		if (bal == null || bal.isRemoved) {
			bal = findBal(client, spot)
			balId = bal?.id ?: -1
			lastSize = -1
			shrinking = false
		}
		if (bal == null) return
		val size = bal.size
		val last = lastSize
		lastSize = size
		if (last < 0) {
			// First sight: at 0 it's waiting. Bigger, it's up, unless it retreated within the minute
			// and is shrinking; a shrink seen next corrects it either way.
			when {
				size == 0 -> risen = false
				retreatBeat < 0 || HollowsClock.beats - retreatBeat > RESPAWN_BEATS -> rise()
			}
			return
		}
		if (size == last) return
		if (size > last) {
			if (last == 0) rise()
			shrinking = false
			return
		}
		// Shrinking: retreated. Seen from the fight's size, that's the retreat itself.
		if (!shrinking) {
			shrinking = true
			risen = false
			val known = retreatBeat >= 0 && HollowsClock.beats - retreatBeat <= RESPAWN_BEATS
			if (!known) {
				val sure = last >= FIGHT_SIZE
				retreatBeat = HollowsClock.beats - if (sure) 0 else (FIRST_SHRUNK_SIZE - size).coerceAtLeast(0)
				retreatSure = sure
			}
		}
	}

	private fun rise() {
		risen = true
		risenBeat = HollowsClock.beats
		retreatBeat = -1L
	}

	private fun findBal(client: Minecraft, spot: Vec3): MagmaCube? {
		val cubes = client.level?.entitiesForRendering()?.filterIsInstance<MagmaCube>() ?: return null
		// Waiting or rising it sits right on its spot; fighting, it's the one that isn't a Yog.
		return cubes.firstOrNull { Math.abs(it.x - spot.x) <= SPOT_REACH && Math.abs(it.z - spot.z) <= SPOT_REACH }
			?: cubes.filter { it.size != YOG_SIZE && it.position().distanceTo(spot) <= FIGHT_REACH }.minByOrNull { it.position().distanceToSqr(spot) }
	}

	private fun text(inSight: Boolean): String? {
		if (risen) return if (!inSight && HollowsClock.beats - risenBeat > LOST_FIGHT_BEATS) "§6Bal §7Unknown" else null
		if (retreatBeat < 0) return "§6Bal §7Unknown"
		val beatsLeft = retreatBeat + RESPAWN_BEATS - HollowsClock.beats
		if (beatsLeft <= 0) {
			// Due: it rises once someone's inside, unless that's already happened out of sight.
			return if (inSight || beatsLeft > -STALE_BEATS) "§6Bal §7Ready" else "§6Bal §7Unknown"
		}
		val seconds = (beatsLeft - 1) + HollowsClock.ticksToBeat / 20.0
		return "§6Bal §7in §6${if (retreatSure) "" else "~"}${Math.ceil(seconds).toInt()}s"
	}

	fun render(context: LevelRenderContext) {
		if (!setting.value) return
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val spot = spawnPoint() ?: return
		val at = Vec3(spot.x, spot.y + 2.5, spot.z)
		val distance = player.position().distanceTo(at)
		if (distance > SHOWN_WITHIN) return
		val inSight = balId >= 0 && client.level?.getEntity(balId)?.isRemoved == false
		val line = text(inSight) ?: return
		WorldRender.drawText(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			orientation = client.gameRenderer.mainCamera().rotation(),
			text = Component.literal(line),
			x = at.x,
			y = at.y,
			z = at.z,
			scale = (distance / 12.0).coerceIn(1.0, 4.0).toFloat(),
			seeThrough = false,
			centered = true,
		)
	}

	fun describe(): List<String> = listOf(
		"Bal: entity ${if (balId >= 0) "#$balId size $lastSize${if (shrinking) " shrinking" else ""}" else "not in sight"}, " +
			"risen $risen, retreat ${if (retreatBeat < 0) "unknown" else "${HollowsClock.beats - retreatBeat} beats ago (${if (retreatSure) "from chat" else "estimated"})"}, " +
			"spawn spot ${spawnPoint()?.let { "%.1f %.1f %.1f".format(it.x, it.y, it.z) } ?: "unknown (Topaz not found)"}",
	)
}
