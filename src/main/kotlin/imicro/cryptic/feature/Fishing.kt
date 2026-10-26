package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fishing helpers around hotspots, the patches of water that grant a perk to whoever fishes in them:
 * a filled circle on the water with a rim, in its perk's colour, a message when
 * you come across one with buttons to share it, and an alert when the one you're fishing goes.
 *
 * How hotspots are found, sized and drawn is SkyOcean's (MIT): a hotspot is an armour stand named
 * "HOTSPOT" with its perk on a stand below; Hypixel marks its edge with particles (pink dust, or
 * smoke on the Crimson Isle), and the distance those land from the stand, to the half block, is its
 * radius. Hotspot and perk stands as Feesh (Apache-2.0) reads them.
 */
object Fishing {
	private val hotspotSection = SectionModuleSetting("hotspot_section", "Hotspots")

	@JvmField
	val useCustomColor = ToggleModuleSetting(
		"use_custom_color", "Use custom color", false,
		"One colour for every hotspot instead of the colour of its perk (gold for trophy fish, blue for double hook...).",
	)

	@JvmField
	val customColor = ColorModuleSetting("custom_color", "Custom color", defaultRgb = 0xFF55FF, visibleIf = { useCustomColor.value })

	@JvmField
	val highlight = ToggleModuleSetting(
		"highlight", "Highlight hotspots", true,
		"Fills each hotspot on the water, with a rim at its edge. Its size is the one Hypixel marks with particles.",
	).withMore(useCustomColor, customColor)

	@JvmField
	val hideParticles = ToggleModuleSetting(
		"hide_particles", "Hide hotspot particles", true,
		"Hides the particles Hypixel draws around a hotspot's edge, which the highlight replaces.",
	)

	@JvmField
	val insideColor = ColorModuleSetting("inside_color", "Bobber inside", defaultRgb = 0x55FF55)

	@JvmField
	val bobberInside = ToggleModuleSetting(
		"bobber_inside", "Show when the bobber is in", true,
		"Outlines your bobber in this colour while it's in a hotspot.",
	).withMore(insideColor)

	@JvmField
	val foundMessage = ToggleModuleSetting(
		"found_message", "Message when found", true,
		"Says in chat when you come across a hotspot, with buttons to share where it is with your party or everyone.",
	)

	@JvmField
	val goneAlert = ToggleModuleSetting("gone_alert", "Alert when gone", true, "A title and a sound when the hotspot you're fishing in disappears.")

	@JvmField
	val module = Module(
		id = "fishing",
		name = "Fishing",
		description = "QoL fishing features",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(hotspotSection, highlight, hideParticles, bobberInside, foundMessage, goneAlert),
	)

	/** The rim's height above the water, as SkyOcean draws it. */
	private const val RIM_HEIGHT = 0.1

	/** The colour of the dust Hypixel rings hotspots with, away from the Crimson Isle. */
	private const val HOTSPOT_DUST = 0xFF69B4

	private class Hotspot(val stand: ArmorStand, val perk: Component?, val surface: Double, val color: Int) {
		val centre: Vec3 get() = Vec3(stand.x, surface, stand.z)
	}

	private val hotspots = ArrayList<Hotspot>()
	private var scanTicks = 0

	/** What the network thread needs: where each hotspot is, to match its particles to it. */
	private class Spot(val id: UUID, val x: Double, val z: Double)

	@Volatile
	private var spots: List<Spot> = emptyList()

	/** How often each radius (to the half block) has been measured, by stand; the most common wins. */
	private val radiusCounts = ConcurrentHashMap<UUID, ConcurrentHashMap<Double, Int>>()

	/** The last two hotspots announced, so moving between two close ones doesn't repeat itself. */
	private val announced = ArrayDeque<UUID>()

	/** The hotspot your bobber was last in, and the world it was in. */
	private var fishedIn: Hotspot? = null
	private var fishedLevel: ClientLevel? = null

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	val needsSkyblock: Boolean get() = module.enabled

	/** The radius measured for a hotspot, or null before any of its particles were seen. */
	private fun radiusOf(id: UUID): Double? = radiusCounts[id]?.maxByOrNull { it.value }?.key

	private fun holdsRod(): Boolean {
		val inventory = Minecraft.getInstance().player?.inventory ?: return false
		return (0 until 9).any { inventory.getItem(it).item is FishingRodItem }
	}

	/** Your bobber, once it's in water or lava. */
	private fun bobber(): FishingHook? {
		val client = Minecraft.getInstance()
		val hook = client.player?.fishing ?: return null
		val level = client.level ?: return null
		val wet = hook.isInWater || hook.isInLava || !level.getFluidState(hook.blockPosition()).isEmpty
		return hook.takeIf { wet }
	}

	fun tick(client: Minecraft) {
		if (!module.enabled || !SkyblockLocation.onSkyblock) {
			hotspots.clear()
			spots = emptyList()
			return
		}
		val level = client.level ?: return
		val player = client.player ?: return
		if (level !== fishedLevel) {
			// A new world: the old hotspot left with it, not by going.
			fishedIn = null
			fishedLevel = level
			announced.clear()
			radiusCounts.clear()
		}
		if (--scanTicks <= 0) {
			scanTicks = 10
			scan(level, player.position())
			checkGone(player.position())
			announce(player.position())
		}
		bobber()?.let { hook -> hotspots.firstOrNull { inside(it, hook.position()) }?.let { fishedIn = it } }
	}

	/** The largest radius a hotspot has here, squared, so far-off particles aren't counted. */
	private fun maxRadiusSquared(): Double = when (SkyblockLocation.area) {
		"Crimson Isle", "Torrhus Canyon" -> 25.0
		"Jerry's Workshop", "Lotus Atoll" -> 16.0
		else -> 9.0
	} + 0.5

	private fun isHotspotParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		if (SkyblockLocation.area == "Crimson Isle") {
			return packet.particle.type == ParticleTypes.SMOKE && (packet.count == 5 || packet.count == 2)
		}
		val dust = packet.particle as? DustParticleOptions ?: return false
		val color = dust.color
		val rgb = ((color.x() * 255).roundToInt() shl 16) or ((color.y() * 255).roundToInt() shl 8) or (color.z() * 255).roundToInt()
		return rgb == HOTSPOT_DUST && packet.count == 0 && packet.xDist == 1f && packet.maxSpeed == 1f
	}

	/**
	 * A particle, on the network thread (the first of the two calls; the second, on the client
	 * thread, is ignored). Measures the hotspot it marks; true when it's to be hidden.
	 */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		if (Minecraft.getInstance().isSameThread || !module.enabled) return false
		val current = spots
		if (current.isEmpty() || !isHotspotParticle(packet)) return false
		val limit = maxRadiusSquared()
		val (spot, distanceSquared) = current.map { it to (packet.x - it.x) * (packet.x - it.x) + (packet.z - it.z) * (packet.z - it.z) }
			.filter { it.second <= limit }
			.minByOrNull { it.second } ?: return false
		// To the half block; counted, so a stray particle (a pet's, say) doesn't make it flicker.
		val radius = (sqrt(distanceSquared) * 2).roundToInt() / 2.0
		radiusCounts.getOrPut(spot.id) { ConcurrentHashMap() }.merge(radius, 1, Int::plus)
		return hideParticles.value && highlight.value
	}

	private fun scan(level: ClientLevel, from: Vec3) {
		hotspots.clear()
		val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.position().distanceToSqr(from) < 64.0 * 64.0 }
		for (stand in stands) {
			if (stand.customName?.string != "HOTSPOT") continue
			// The perk sits on its own stand just under the title.
			val perk = stands.firstOrNull { other ->
				other !== stand && other.x == stand.x && other.z == stand.z && other.y < stand.y && stand.y - other.y <= 1.0
			}?.customName
			hotspots += Hotspot(stand, perk, surfaceUnder(level, stand), colorOf(perk))
		}
		spots = hotspots.map { Spot(it.stand.uuid, it.stand.x, it.stand.z) }
	}

	/** A perk's colour, as SkyOcean colours them. */
	private fun colorOf(perk: Component?): Int {
		val text = perk?.string?.lowercase() ?: return 0xFF55FF
		return when {
			"sea creature chance" in text -> 0x00AAAA
			"fishing speed" in text -> 0x55FFFF
			"double hook" in text -> 0x5555FF
			"treasure chance" in text || "trophy chance" in text -> 0xFFAA00
			"shard" in text -> 0xFFFF55
			else -> 0xFF55FF
		}
	}

	/** The water (or lava) surface under a hotspot's stand. */
	private fun surfaceUnder(level: ClientLevel, stand: ArmorStand): Double {
		val start = BlockPos.containing(stand.x, stand.y, stand.z)
		for (down in 0..6) {
			val pos = start.below(down)
			val fluid = level.getFluidState(pos)
			if (!fluid.isEmpty) return pos.y + fluid.getHeight(level, pos).toDouble() + 0.1
		}
		return stand.y - 1.5
	}

	private fun inside(hotspot: Hotspot, at: Vec3): Boolean {
		val radius = radiusOf(hotspot.stand.uuid) ?: return false
		val dx = at.x - hotspot.stand.x
		val dz = at.z - hotspot.stand.z
		return dx * dx + dz * dz <= radius * radius && kotlin.math.abs(at.y - hotspot.surface) < 3.0
	}

	private fun checkGone(at: Vec3) {
		val last = fishedIn ?: return
		if (!last.stand.isRemoved) return
		fishedIn = null
		// Walking away unloads it too; only one gone close by has really gone.
		if (!goneAlert.value || last.stand.position().distanceToSqr(at) > 30.0 * 30.0) return
		val client = Minecraft.getInstance()
		client.gui.hud.setTimes(0, 40, 10)
		client.gui.hud.setTitle(Component.literal("§dHotspot §cis gone"))
		client.gui.hud.setSubtitle(last.perk ?: Component.empty())
		SoundChoices.play(0, 1f, 1f)
	}

	private fun announce(at: Vec3) {
		if (!foundMessage.value || !holdsRod()) return
		val near = hotspots.filter { it.stand.position().distanceToSqr(at) < 10.0 * 10.0 }
			.minByOrNull { it.stand.position().distanceToSqr(at) } ?: return
		val id = near.stand.uuid
		if (id in announced) return
		announced.addFirst(id)
		while (announced.size > 2) announced.removeLast()

		val perk = near.perk?.string?.trim().orEmpty()
		val where = "x: ${near.stand.blockX}, y: ${near.stand.blockY}, z: ${near.stand.blockZ}"
		val zone = SkyblockLocation.zone()?.let { " at $it" }.orEmpty()
		val share = "$where | ${if (perk.isEmpty()) "" else "$perk "}Hotspot$zone"
		val message = Component.literal("§8[Cryptic] §7You found ")
			.append(near.perk?.copy()?.append(" ") ?: Component.empty())
			.append(Component.literal("§dHotspot§7. "))
			.append(button("§9[Party]", "pc $share", "Share it in party chat"))
			.append(Component.literal(" "))
			.append(button("§e[All]", "ac $share", "Share it with everyone"))
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)
	}

	private fun button(label: String, command: String, hover: String): Component =
		Component.literal(label).withStyle {
			it.withClickEvent(ClickEvent.RunCommand(command)).withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
		}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !highlight.value || hotspots.isEmpty()) return
		val client = Minecraft.getInstance()
		val hook = bobber()
		for (hotspot in hotspots) {
			if (hotspot.stand.isRemoved) continue
			// Drawn once Hypixel's particles have said how big it is.
			val radius = radiusOf(hotspot.stand.uuid) ?: continue
			val rgb = (if (useCustomColor.value) customColor.argb else hotspot.color) and 0xFFFFFF
			WorldRender.drawFilledCylinder(
				context.poseStack(), context.submitNodeCollector(), hotspot.centre, radius, RIM_HEIGHT,
				wallArgb = (0xFF shl 24) or rgb, floorArgb = (0x80 shl 24) or rgb, phase = false,
			)
			if (bobberInside.value && hook != null && inside(hotspot, hook.position())) {
				val box = hook.boundingBox
				val green = insideColor.argb and 0xFFFFFF
				WorldRender.drawBox(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					minX = box.minX, minY = box.minY, minZ = box.minZ,
					maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
					outlineArgb = (0xFF shl 24) or green,
					fillArgb = (0x50 shl 24) or green,
					outline = true,
					fill = true,
					phase = true,
					lineWidth = 2f,
				)
			}
		}
	}

	fun describe(): List<String> = hotspots.map {
		val counts = radiusCounts[it.stand.uuid]?.entries?.sortedByDescending { e -> e.value }?.joinToString { (r, n) -> "${r}x$n" } ?: "none yet"
		"Hotspot ${it.perk?.string ?: "?"} at ${it.stand.blockPosition().toShortString()}: radius ${radiusOf(it.stand.uuid) ?: "unknown"} (measured $counts)"
	}.ifEmpty { listOf("No hotspots in range.") }
}
