package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import kotlin.math.sqrt

/**
 * M7's last phase: which dragon spawns where and when, the box to kill it in for a skip, its health,
 * a glow, and a line when it dies (how long it lived, when it was sprayed, how many arrows hit).
 * Priority picks the dragon you should go to when two spawn together. Ported from NoammAddons.
 */
object WitherDragons {
	private const val MS = 0
	private const val SECONDS = 1

	@JvmField
	val timer = ToggleModuleSetting(id = "timer", label = "Spawn timer", defaultValue = true,
		description = "Counts down over each dragon's spawn spot, and the priority dragon in big on screen.")

	@JvmField
	val timerStyle = DropdownModuleSetting(id = "timer_style", label = "Shown as", options = listOf("Milliseconds", "Seconds", "Ticks"),
		defaultIndex = SECONDS, visibleIf = { timer.value })

	@JvmField
	val boxes = ToggleModuleSetting(id = "boxes", label = "Skip boxes", defaultValue = true,
		description = "Outlines where each dragon has to die to count as a skip, until it's dead.")

	@JvmField
	val health = ToggleModuleSetting(id = "health", label = "Health", defaultValue = true,
		description = "Writes each living dragon's health under it.")

	@JvmField
	val glow = ToggleModuleSetting(id = "glow", label = "Glow", defaultValue = false, description = "Outlines each living dragon in its colour.")

	@JvmField
	val tracer = ToggleModuleSetting(id = "tracer", label = "Tracer to priority", defaultValue = false,
		description = "A line to where the priority dragon spawns.")

	@JvmField
	val arrowStack = ToggleModuleSetting(id = "arrow_stack", label = "Arrow stack aim", defaultValue = true,
		description = "While a dragon spawns, a ring where to aim so arrows land on its spawn, allowing for their drop.")

	@JvmField
	val arrowStackColor = ColorModuleSetting(id = "arrow_stack_color", label = "Ring", defaultRgb = 0x55FFFF, visibleIf = { arrowStack.value })

	@JvmField
	val spawnAlert = ToggleModuleSetting(id = "spawn_alert", label = "Spawn alert", defaultValue = true,
		description = "A big title when your priority dragon starts spawning: \"RED IS SPAWNING\".")

	@JvmField
	val spawnChat = ToggleModuleSetting(id = "spawn_chat", label = "Say in chat", defaultValue = false,
		description = "Also writes which dragon spawns in chat.", visibleIf = { spawnAlert.value })

	private val alertsSection = SectionModuleSetting("alerts_section", "When one dies")

	@JvmField
	val sayTime = ToggleModuleSetting(id = "say_time", label = "Time alive", defaultValue = true)

	@JvmField
	val saySpray = ToggleModuleSetting(id = "say_spray", label = "When it was sprayed", defaultValue = true)

	@JvmField
	val sayArrows = ToggleModuleSetting(id = "say_arrows", label = "Arrows hit", defaultValue = true,
		description = "Arrows that hit the priority dragon in its skip window.")

	private val prioritySection = SectionModuleSetting("priority_section", "Priority")

	@JvmField
	val priority = ToggleModuleSetting(id = "priority", label = "Split priority", defaultValue = false,
		description = "When two dragons spawn together, the healer or tank (pick below) takes the later one; everyone else the first.")

	@JvmField
	val soloClass = DropdownModuleSetting(id = "solo_class", label = "Splits off", options = listOf("Healer", "Tank"), defaultIndex = 0,
		visibleIf = { priority.value })

	@JvmField
	val firstSplitOnly = ToggleModuleSetting(id = "first_split_only", label = "First split only", defaultValue = true,
		description = "After the first pair, everyone follows the first dragon.", visibleIf = { priority.value })

	@JvmField
	val module = Module(
		id = "wither_dragons",
		name = "Wither Dragons",
		description = "M7 dragon timers and boxes",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf(
			timer.withMore(timerStyle), spawnAlert.withMore(spawnChat), boxes, health, glow, tracer, arrowStack.withMore(arrowStackColor),
			alertsSection, sayTime, saySpray, sayArrows,
			prioritySection, priority.withMore(soloClass, firstSplitOnly),
		),
	)

	private enum class State { SPAWNING, ALIVE, DEAD }

	/** The five dragons, their spawn spot, skip box, colour and the chin block that breaks when one dies. */
	private enum class Dragon(
		val spawn: Vec3, val box: AABB, val code: Char, val rgb: Int, val scoreboardName: String,
		val xRange: ClosedRange<Double>, val zRange: ClosedRange<Double>, val skipTicks: Long, val chin: BlockPos,
		val stackAim: Vec3,
	) {
		RED(Vec3(27.0, 14.0, 59.0), AABB(14.5, 13.0, 45.5, 39.5, 28.0, 70.5), 'c', 0xFF5555, "Power Dragon", 24.0..30.0, 56.0..62.0, 50, BlockPos(32, 19, 59), Vec3(28.0, 14.0, 58.0)),
		ORANGE(Vec3(85.0, 14.0, 56.0), AABB(72.0, 8.0, 47.0, 102.0, 28.0, 77.0), '6', 0xFFAA00, "Flame Dragon", 82.0..88.0, 53.0..59.0, 62, BlockPos(80, 19, 56), Vec3(85.0, 14.0, 56.0)),
		GREEN(Vec3(27.0, 14.0, 94.0), AABB(7.0, 8.0, 80.0, 37.0, 28.0, 110.0), 'a', 0x55FF55, "Apex Dragon", 23.0..29.0, 91.0..97.0, 52, BlockPos(32, 18, 94), Vec3(27.0, 14.0, 90.0)),
		BLUE(Vec3(84.0, 14.0, 94.0), AABB(71.5, 16.0, 82.5, 96.5, 26.0, 107.5), 'b', 0x55FFFF, "Ice Dragon", 82.0..88.0, 91.0..97.0, 47, BlockPos(79, 19, 94), Vec3(84.0, 14.0, 97.0)),
		PURPLE(Vec3(56.0, 14.0, 125.0), AABB(45.5, 13.0, 113.5, 68.5, 23.0, 136.5), '5', 0xAA00AA, "Soul Dragon", 53.0..59.0, 122.0..128.0, 38, BlockPos(56, 18, 128), Vec3(56.0, 14.0, 125.0));

		var state = State.DEAD
		var ticksToSpawn = SPAWN_TICKS
		var entityId: Int? = null
		var spawnedAt = 0L
		var sprayedAfter: Long? = null
		var arrowsHit = 0
		var hp = FULL_HEALTH
		var offScoreboard = 0

		val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

		fun entity(): EnderDragon? = entityId?.let { Minecraft.getInstance().level?.getEntity(it) as? EnderDragon }

		fun spawned(id: Int) {
			state = State.ALIVE
			spawnCount++
			ticksToSpawn = SPAWN_TICKS
			entityId = id
			spawnedAt = ticks
			sprayedAfter = null
			arrowsHit = 0
			offScoreboard = 0
			hp = FULL_HEALTH
		}

		fun died(silent: Boolean = false) {
			if (state == State.DEAD) return
			state = State.DEAD
			ticksToSpawn = SPAWN_TICKS
			entityId = null
			if (module.enabled && !silent) {
				val parts = ArrayList<String>()
				if (sayTime.value) parts += "§7Time: §6${(ticks - spawnedAt) / 20.0}s"
				if (sayArrows.value && this == priorityDragon) parts += "§fArrows: §6$arrowsHit"
				sprayedAfter?.let { if (saySpray.value) parts += "§bSprayed: §c${it}t" }
				if (parts.isNotEmpty()) say("§$code$label: §f${parts.joinToString(" §7| ")}")
			}
			if (priorityDragon == this) priorityDragon = null
		}

		fun reset() {
			state = State.DEAD
			ticksToSpawn = SPAWN_TICKS
			entityId = null
			sprayedAfter = null
			spawnedAt = 0
			hp = FULL_HEALTH
			offScoreboard = 0
		}
	}

	private const val SPAWN_TICKS = 100
	private const val FULL_HEALTH = 1_000_000_000f

	/** How long a dragon out of range must be missing from the sidebar to count as dead. */
	private const val SCOREBOARD_GRACE_TICKS = 40

	private var priorityDragon: Dragon? = null
	private var spawnCount = 0

	/** Server ticks seen in this phase, which every timer here counts in. */
	private var ticks = 0L

	private val client: Minecraft get() = Minecraft.getInstance()

	private val active: Boolean
		get() = module.enabled && ((DungeonLocation.floor == 7 && DungeonLocation.masterMode && Floor7.phase == 5) || DebugOverrides.sampleHudValues)

	/** Floor 7's phase is only tracked while something wants it. */
	val needsPhase: Boolean get() = module.enabled && DungeonLocation.inDungeon

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		Hud.register(TimerElement())
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
	}

	private fun reset() {
		priorityDragon = null
		spawnCount = 0
		ticks = 0
		Dragon.entries.forEach { it.reset() }
	}

	// ---- Packets -----------------------------------------------------------------
	//
	// Packet handlers run twice, first on the network thread; these act on the client-thread pass.

	/** From the network thread alone, so it's moved to the client thread. */
	@JvmStatic
	fun onServerTick() {
		if (!module.enabled) return
		if (!client.isSameThread) {
			client.execute(::onServerTick)
			return
		}
		if (!active) return
		ticks++
		for (dragon in Dragon.entries) {
			if (dragon.state == State.SPAWNING) {
				dragon.ticksToSpawn--
				if (dragon.ticksToSpawn <= -20) dragon.died(silent = true)
			}
			if (dragon.state == State.ALIVE && dragon.entity() == null) {
				if (aliveOnSidebar(dragon)) dragon.offScoreboard = 0
				else if (++dragon.offScoreboard >= SCOREBOARD_GRACE_TICKS) dragon.died()
			}
		}
	}

	/** Hypixel's spawn flames: twenty flame particles on a whole block at y 19, spread 2 by 3 by 2. */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (!client.isSameThread || !active || packet.particle.type != ParticleTypes.FLAME) return
		if (packet.x % 1 != 0.0 || packet.z % 1 != 0.0 || packet.y != 19.0 || packet.count != 20) return
		if (packet.xDist != 2f || packet.yDist != 3f || packet.zDist != 2f || packet.maxSpeed != 0f || !packet.isOverrideLimiter) return

		var soonest: Dragon? = null
		var latest: Dragon? = null
		var started = false
		for (dragon in Dragon.entries) {
			if (dragon.state != State.SPAWNING) {
				if (packet.x !in dragon.xRange || packet.z !in dragon.zRange) continue
				dragon.state = State.SPAWNING
				started = true
			}
			if (soonest == null || dragon.ticksToSpawn < soonest.ticksToSpawn) soonest = dragon
			if (latest == null || dragon.ticksToSpawn > latest.ticksToSpawn) latest = dragon
		}
		val before = priorityDragon
		priorityDragon = pickPriority(soonest, latest) ?: priorityDragon
		val now = priorityDragon
		if (started && now != null && (now != before || now.ticksToSpawn >= SPAWN_TICKS - 1)) alertSpawn(now)
	}

	private fun alertSpawn(dragon: Dragon) {
		if (!spawnAlert.value) return
		val text = "§${dragon.code}§l${dragon.name} IS SPAWNING"
		client.gui.hud.setTimes(0, 30, 5)
		client.gui.hud.setTitle(Component.literal(text))
		client.gui.hud.setSubtitle(Component.empty())
		if (spawnChat.value) say("§${dragon.code}${dragon.label} §7is spawning.")
	}

	private fun pickPriority(soonest: Dragon?, latest: Dragon?): Dragon? {
		if (!priority.value || (firstSplitOnly.value && spawnCount >= 2)) return soonest
		val splits = if (soloClass.selectedIndex == 0) DungeonClass.HEALER else DungeonClass.TANK
		val mine = client.player?.name?.string?.let(DungeonTeam::classOf)
		return if (mine == splits && latest != null) latest else soonest
	}

	@JvmStatic
	fun onAddEntity(packet: ClientboundAddEntityPacket) {
		if (!client.isSameThread || !active || packet.type != net.minecraft.world.entity.EntityTypes.ENDER_DRAGON) return
		val at = Vec3(packet.x, packet.y, packet.z)
		Dragon.entries.firstOrNull { it.state == State.SPAWNING && at.x in it.box.minX..it.box.maxX && at.z in it.box.minZ..it.box.maxZ }
			?.spawned(packet.id)
	}

	/** Index 9 is a living entity's health. On the client thread already (the handler's tail). */
	@JvmStatic
	fun onEntityData(packet: ClientboundSetEntityDataPacket) {
		if (!active) return
		val dragon = Dragon.entries.firstOrNull { it.entityId == packet.id() } ?: return
		dragon.hp = packet.packedItems().firstOrNull { it.id() == 9 }?.value() as? Float ?: return
		if (dragon.hp <= 0f) dragon.died()
	}

	/** An Ice Spray: a stand putting on packed ice next to a living dragon. Client thread (the tail). */
	@JvmStatic
	fun onEquipment(packet: ClientboundSetEquipmentPacket) {
		if (!active || packet.slots.none { it.second.item == Items.PACKED_ICE }) return
		val stand = client.level?.getEntity(packet.entity) as? ArmorStand ?: return
		for (dragon in Dragon.entries) {
			val entity = dragon.entity() ?: continue
			if (dragon.sprayedAfter != null || dragon.state != State.ALIVE || stand.distanceTo(entity) > 8) continue
			dragon.sprayedAfter = ticks - dragon.spawnedAt
		}
	}

	@JvmStatic
	fun onSound(packet: ClientboundSoundPacket) {
		if (!client.isSameThread || !active || packet.sound.value() != SoundEvents.ARROW_HIT_PLAYER) return
		val dragon = priorityDragon ?: return
		if (dragon.state == State.ALIVE && ticks - dragon.spawnedAt <= dragon.skipTicks) dragon.arrowsHit++
	}

	/** The chin slab of a dead dragon's statue breaks. Client thread, before the change. */
	@JvmStatic
	fun onBlockChanged(pos: BlockPos, old: net.minecraft.world.level.block.state.BlockState, new: net.minecraft.world.level.block.state.BlockState) {
		if (!active || !new.isAir || old.block != Blocks.COBBLESTONE_SLAB) return
		Dragon.entries.firstOrNull { it.chin.x == pos.x && it.chin.z == pos.z }?.died(silent = true)
	}

	private val HEALTH_NUMBER = Regex("""\d+(?:\.\d+)?[bBmMkK]""")
	private val FORMATTING = Regex("§.")

	private fun aliveOnSidebar(dragon: Dragon): Boolean {
		val scoreboard = client.level?.scoreboard ?: return false
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false
		return scoreboard.listPlayerScores(objective).any { entry ->
			val line = PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).string.replace(FORMATTING, "")
			line.contains(dragon.scoreboardName, ignoreCase = true) && HEALTH_NUMBER.find(line)?.value != "0"
		}
	}

	// ---- Drawing ----------------------------------------------------------------

	@JvmStatic
	fun outlineColorFor(entity: Entity): Int? {
		if (!active || !glow.value || entity !is EnderDragon) return null
		val dragon = Dragon.entries.firstOrNull { it.state == State.ALIVE && it.entityId == entity.id } ?: return null
		return 0xFF000000.toInt() or dragon.rgb
	}

	private fun timerText(ticks: Int): String = when (timerStyle.selectedIndex) {
		MS -> "${ticks * 50}ms"
		SECONDS -> String.format("%.1fs", ticks / 20.0)
		else -> "${ticks}t"
	}

	private fun render(context: LevelRenderContext) {
		if (!active) return
		val player = client.player ?: return
		val pose = context.poseStack()
		val collector = context.submitNodeCollector()
		val orientation = client.gameRenderer.mainCamera().rotation()
		for (dragon in Dragon.entries) {
			if (health.value && dragon.state == State.ALIVE) dragon.entity()?.let { entity ->
				WorldRender.drawText(pose, collector, orientation, Component.literal(healthText(dragon.hp)), entity.x, entity.y - 1, entity.z, 6f, seeThrough = true, centered = true)
			}
			if (timer.value && dragon.state == State.SPAWNING && dragon.ticksToSpawn > 0) {
				WorldRender.drawText(pose, collector, orientation, Component.literal("§${dragon.code}${dragon.label}: ${timerText(dragon.ticksToSpawn)}"),
					dragon.spawn.x + 0.5, dragon.spawn.y + 2, dragon.spawn.z + 0.5, 6f, seeThrough = true, centered = true)
			}
			if (boxes.value && dragon.state != State.DEAD) {
				val b = dragon.box
				WorldRender.drawBox(pose, collector, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
					outlineArgb = 0x80000000.toInt() or dragon.rgb, fillArgb = 0, outline = true, fill = false, phase = false, lineWidth = 2f)
			}
			if (arrowStack.value && dragon.state == State.SPAWNING) {
				val target = dragon.stackAim.add(0.5, 3.5, 0.5)
				val aim = leadFor(player.eyePosition, target) ?: continue
				val size = 2.0 * sqrt(player.eyePosition.distanceTo(target) / 50.0).coerceAtLeast(0.5)
				WorldRender.drawCircle(pose, collector, aim, size, 0xFF000000.toInt() or arrowStackColor.rgb, 3f, phase = true)
			}
		}
		val target = priorityDragon ?: return
		if (tracer.value && target.state == State.SPAWNING) {
			WorldRender.drawTracer(pose, collector, target.spawn.x + 0.5, target.spawn.y + 3.5, target.spawn.z + 0.5, 0xFF000000.toInt() or target.rgb, 2f, phase = true)
		}
	}

	/** Where to aim above [target] so an arrow's drop lands on it: three blocks a tick, slowed by drag. */
	private fun leadFor(eyes: Vec3, target: Vec3): Vec3? {
		val distanceSq = target.distanceToSqr(eyes)
		var travelled = 0.0
		var speed = 3.0
		var fallSpeed = 0.0
		var drop = 0.0
		repeat(160) {
			travelled += speed
			speed *= 0.99
			drop += fallSpeed
			fallSpeed = (fallSpeed - 0.05) * 0.99
			if (travelled * travelled >= distanceSq) return target.subtract(0.0, drop, 0.0)
		}
		return null
	}

	private fun healthText(hp: Float): String {
		val color = when {
			hp >= 750_000_000 -> "§a"
			hp >= 500_000_000 -> "§e"
			hp >= 250_000_000 -> "§6"
			else -> "§c"
		}
		val amount = when {
			hp >= 1_000_000_000 -> String.format("%.1fb", hp / 1_000_000_000).replace(".0b", "b")
			hp >= 1_000_000 -> "${(hp / 1_000_000).toInt()}m"
			hp >= 1_000 -> "${(hp / 1_000).toInt()}k"
			else -> "${hp.toInt()}"
		}
		return color + amount
	}

	private fun say(message: String) {
		client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] $message"))
	}

	/** The priority dragon's countdown in big, mid-screen. */
	private class TimerElement : HudElement("dragon_timer", "Dragon Spawn Timer", 0.5, 0.4) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("Purple: 4.95s")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean {
			if (!module.enabled || !timer.value) return false
			if (DebugOverrides.sampleHudValues) return true
			val dragon = priorityDragon ?: return false
			return active && dragon.state == State.SPAWNING && dragon.ticksToSpawn > 0
		}

		override fun showInEditor(): Boolean = module.enabled && timer.value

		override fun render(context: GuiGraphicsExtractor) {
			val dragon = priorityDragon ?: return renderExample(context)
			draw(context, "§${dragon.code}${dragon.label}: ${timerText(dragon.ticksToSpawn)}")
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, "§5Purple: ${timerText(99)}")

		private fun draw(context: GuiGraphicsExtractor, text: String) {
			context.text(font, text, (width - font.width(text)) / 2, 0, 0xFFFFFFFF.toInt())
		}
	}
}
