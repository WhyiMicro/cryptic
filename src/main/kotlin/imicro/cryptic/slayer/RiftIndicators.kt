package imicro.cryptic.slayer

import com.mojang.blaze3d.vertex.PoseStack
import imicro.cryptic.feature.SlayerFeatures
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * What your Bloodfiend is about to do, drawn in the world. All of it is read off the stand with the
 * boss's despawn timer, which counts its skills down ("03:45 TWINCLAWS 1.2s ICHOR 1.4s"):
 *
 * - the line to the Blood Ichor it spawned, picked as the one that appeared when its ICHOR count
 *   ran out (tier V, the only tier with Ichor), so another player's boss's Ichor isn't joined;
 * - the Twinclaws path, a line at your chest height towards you while TWINCLAWS counts down, left
 *   where it was when the count ends;
 * - Stonewrath's lock-on: where you stood when the rising note-block plings played, just after its
 *   STONEWRATH count ran out.
 *
 * Facts, not code: the skill stand (skydiao, MIT) and the Ichor's head (SkyHanni's repo).
 */
object RiftIndicators {
	private val FORMATTING = Regex("§.")
	private val SKILL = Regex("""\b(TWINCLAWS|ICHOR|STONEWRATH)\s+(\d+(?:\.\d+)?)s""")

	/** The skill stand is within this of the boss. */
	private const val SKILL_REACH = 3.0

	private const val ICHOR_SKIN = "c0340923a6de4825a176813d133503eff186db0896e32b6704928c2a2bf68422"
	private const val ICHOR_REACH = 30.0

	/** An Ichor that appears this long after the ICHOR count ends is still the one it spawned. */
	private const val ICHOR_SPAWN_MILLIS = 5_000L

	/** A count missing for this long has ended, rather than flickered. */
	private const val COUNT_GONE_MILLIS = 400L

	/** The Twinclaws line runs this far past you, and at least this long. */
	private const val CLAWS_PAST = 4.0
	private const val CLAWS_MIN_LENGTH = 10.0
	private const val CLAWS_SHOWN_MILLIS = 1_500L
	private const val CHEST = 1.2

	/**
	 * Stonewrath's lock-on box goes when the boss is back down to within this of where it stood
	 * during the count, or after [LOCK_SHOWN_MAX_MILLIS] whatever happens.
	 */
	private const val LANDED_SLACK = 1.0
	private const val LOCK_SHOWN_MAX_MILLIS = 8_000L

	/**
	 * Plings this soon after a STONEWRATH count was last seen belong to it; three in a row, each a
	 * little higher, each within [PLING_GAP_MILLIS] of the one before, are the lock-on.
	 */
	private const val STONE_RECENT_MILLIS = 10_000L
	private const val PLING_GAP_MILLIS = 250L

	// Ichor
	private var ichorCountAt = 0L
	private var ichorsBefore: Set<Int> = emptySet()
	private var ownIchor = -1

	// Twinclaws
	private var clawsSeenAt = 0L
	private var clawsFrozen: Pair<Vec3, Vec3>? = null
	private var clawsFrozenAt = 0L

	// Stonewrath
	private var stoneSeenAt = 0L
	private var stoneLeft = 0.0

	/** Where the boss stood during the STONEWRATH count, which is where it comes back down to. */
	private var groundY = 0.0
	private var stoneLocked = false
	private var lockAt: Vec3? = null
	private var lockedAt = 0L
	private var plingRun = 0
	private var lastPlingAt = 0L
	private var lastPlingPitch = 0f

	private val ichorHeads = HashMap<String, Boolean>()

	fun clear() {
		ownIchor = -1
		ichorCountAt = 0L
		clawsSeenAt = 0L
		clawsFrozen = null
		stoneSeenAt = 0L
		stoneLocked = false
		lockAt = null
		plingRun = 0
	}

	/** Each tick, with your Bloodfiend if one is up. */
	fun tick(level: ClientLevel, player: LocalPlayer, mine: SlayerBosses.Seen?, now: Long) {
		val boss = mine?.boss
		if (boss == null) {
			clear()
			return
		}
		val skills = HashMap<String, Double>()
		level.getEntitiesOfClass(ArmorStand::class.java, boss.boundingBox.inflate(SKILL_REACH)) { it.customName != null }.forEach { stand ->
			val text = stand.customName!!.string.replace(FORMATTING, "")
			for (match in SKILL.findAll(text)) match.groupValues[2].toDoubleOrNull()?.let { skills[match.groupValues[1]] = it }
		}
		tickIchor(level, boss, mine.tier, skills["ICHOR"], now)
		tickClaws(boss, player, skills["TWINCLAWS"], now)
		tickStone(boss, skills["STONEWRATH"], now)
	}

	// ---- Ichor ---------------------------------------------------------------------------------

	private fun tickIchor(level: ClientLevel, boss: Entity, tier: Int, count: Double?, now: Long) {
		if (!SlayerFeatures.ichorLine.value || tier < 5) {
			ownIchor = -1
			return
		}
		if (ownIchor != -1 && level.getEntity(ownIchor)?.isAlive != true) ownIchor = -1
		val ichors = level.getEntitiesOfClass(ArmorStand::class.java, boss.boundingBox.inflate(ICHOR_REACH)) { isIchorHead(it) }
		if (count != null) {
			// Counting down: every Ichor up now is someone else's or an old one.
			ichorCountAt = now
			ichorsBefore = ichors.mapTo(HashSet()) { it.id }
			return
		}
		if (ownIchor == -1 && ichorCountAt != 0L && now - ichorCountAt <= ICHOR_SPAWN_MILLIS) {
			ichors.filter { it.id !in ichorsBefore }.minByOrNull { it.distanceToSqr(boss) }?.let { ownIchor = it.id }
		}
	}

	private fun isIchorHead(stand: ArmorStand): Boolean {
		val head = stand.getItemBySlot(EquipmentSlot.HEAD)
		if (!head.`is`(Items.PLAYER_HEAD)) return false
		val texture = SkyblockItem.skullTexture(head) ?: return false
		return ichorHeads.getOrPut(texture) { SkyblockItem.textureHash(texture) == ICHOR_SKIN }
	}

	// ---- Twinclaws -----------------------------------------------------------------------------

	private fun tickClaws(boss: Entity, player: LocalPlayer, count: Double?, now: Long) {
		if (!SlayerFeatures.twinclawsPath.value) {
			clawsSeenAt = 0L
			clawsFrozen = null
			return
		}
		if (count != null && count > 0.0) {
			clawsSeenAt = now
			clawsFrozen = null
		} else if (clawsSeenAt != 0L && (count != null || now - clawsSeenAt >= COUNT_GONE_MILLIS)) {
			// The count ran out: the claws go where the line points now.
			clawsFrozen = clawsLine(boss.position(), player.position())
			clawsFrozenAt = now
			clawsSeenAt = 0L
		}
		if (clawsFrozen != null && now - clawsFrozenAt > CLAWS_SHOWN_MILLIS) clawsFrozen = null
	}

	/**
	 * From the boss towards you and a little past, at your chest height rather than the boss's, so
	 * it stays level with you while the boss floats during Mania.
	 */
	private fun clawsLine(boss: Vec3, player: Vec3): Pair<Vec3, Vec3>? {
		val dx = player.x - boss.x
		val dz = player.z - boss.z
		val distance = Math.sqrt(dx * dx + dz * dz)
		if (distance < 1e-3) return null
		val length = maxOf(distance + CLAWS_PAST, CLAWS_MIN_LENGTH)
		val y = player.y + CHEST
		return Vec3(boss.x, y, boss.z) to Vec3(boss.x + dx / distance * length, y, boss.z + dz / distance * length)
	}

	// ---- Stonewrath ----------------------------------------------------------------------------

	/** The lock-on itself is the plings ([onSound]); the count only says a Stonewrath is on. */
	/**
	 * The STONEWRATH count runs down to the boss rising, while it still stands where it'll land
	 * again; the lock-on comes later in the air ([onSound]), and the box goes once it's down.
	 */
	private fun tickStone(boss: Entity, count: Double?, now: Long) {
		if (count != null) {
			// A count back up from where it was is the next Stonewrath.
			if (count > stoneLeft + 1.0 || now - stoneSeenAt > STONE_RECENT_MILLIS) {
				stoneLocked = false
				lockAt = null
			}
			stoneSeenAt = now
			stoneLeft = count
			groundY = boss.y
		}
		if (lockAt == null) return
		val landed = boss.y <= groundY + LANDED_SLACK
		if (landed || now - lockedAt > LOCK_SHOWN_MAX_MILLIS) {
			lockAt = null
			SlayerAutomation.recordEvent("Stonewrath box cleared (${if (landed) "boss landed" else "timed out"})")
		}
	}

	private fun lock(player: LocalPlayer, now: Long, why: String) {
		stoneLocked = true
		if (!SlayerFeatures.stonewrathMark.value) return
		lockAt = player.position()
		lockedAt = now
		SlayerAutomation.recordEvent("Stonewrath lock-on ($why) at ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)}")
	}

	/**
	 * A server sound, client thread. Three note-block plings in quick succession, each a little
	 * higher, while a Stonewrath is on are its lock-on; only the first such run counts, once per
	 * Stonewrath. Compared by id, since a sound the server names isn't always the game's own object.
	 */
	@JvmStatic
	fun onSound(sound: SoundEvent, pitch: Float) {
		if (!Minecraft.getInstance().isSameThread || sound.location() != PLING) return
		if (!SlayerFeatures.module.enabled || !SlayerFeatures.stonewrathMark.value) return
		val now = System.currentTimeMillis()
		if (stoneSeenAt == 0L || stoneLocked || now - stoneSeenAt > STONE_RECENT_MILLIS) return
		plingRun = if (now - lastPlingAt <= PLING_GAP_MILLIS && pitch > lastPlingPitch) plingRun + 1 else 1
		lastPlingAt = now
		lastPlingPitch = pitch
		SlayerAutomation.recordEvent("Stonewrath pling, pitch ${"%.2f".format(pitch)}, ${plingRun} rising")
		if (plingRun >= 3) {
			plingRun = 0
			Minecraft.getInstance().player?.let { lock(it, now, "three rising plings") }
		}
	}

	private val PLING = SoundEvents.NOTE_BLOCK_PLING.value().location()

	// ---- Drawing -------------------------------------------------------------------------------

	fun render(poseStack: PoseStack, collector: SubmitNodeCollector, partialTick: Float, now: Long) {
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val boss = SlayerBosses.mine()?.takeIf { it.type == SlayerType.RIFTSTALKER }?.boss

		if (SlayerFeatures.ichorLine.value && boss != null && ownIchor != -1) {
			level.getEntity(ownIchor)?.let { ichor ->
				val from = at(boss, partialTick).add(0.0, boss.bbHeight / 2.0, 0.0)
				WorldRender.drawLine(poseStack, collector, listOf(from, at(ichor, partialTick).add(0.0, 1.2, 0.0)), SlayerFeatures.ichorColor.argb, LINE_WIDTH, phase = true)
			}
		}

		if (SlayerFeatures.twinclawsPath.value) {
			val player = client.player
			val line = clawsFrozen ?: if (clawsSeenAt != 0L && boss != null && player != null) clawsLine(at(boss, partialTick), at(player, partialTick)) else null
			line?.let { (from, to) -> WorldRender.drawLine(poseStack, collector, listOf(from, to), SlayerFeatures.twinclawsColor.argb, LINE_WIDTH, phase = true) }
		}

		val lock = lockAt
		if (SlayerFeatures.stonewrathMark.value && lock != null) {
			val colour = SlayerFeatures.stonewrathColor.argb
			WorldRender.drawBox(
				poseStack = poseStack, collector = collector,
				minX = lock.x - 0.5, minY = lock.y, minZ = lock.z - 0.5,
				maxX = lock.x + 0.5, maxY = lock.y + 0.1, maxZ = lock.z + 0.5,
				outlineArgb = ARGB.opaque(colour), fillArgb = colour,
				outline = true, fill = true, phase = true, lineWidth = LINE_WIDTH,
			)
		}
	}

	private const val LINE_WIDTH = 3f

	private fun at(entity: Entity, partialTick: Float) = Vec3(
		Mth.lerp(partialTick.toDouble(), entity.xo, entity.x),
		Mth.lerp(partialTick.toDouble(), entity.yo, entity.y),
		Mth.lerp(partialTick.toDouble(), entity.zo, entity.z),
	)

	fun describe(): String =
		"Ichor ${if (ownIchor != -1) "entity $ownIchor" else "none"}; Twinclaws ${when {
			clawsFrozen != null -> "path held"
			clawsSeenAt != 0L -> "counting"
			else -> "-"
		}}; Stonewrath ${if (stoneSeenAt == 0L) "not seen" else "last ${stoneLeft}s, ${if (stoneLocked) "locked" else "not locked"}"}"
}
