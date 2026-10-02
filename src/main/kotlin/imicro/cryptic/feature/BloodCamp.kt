package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Where the Watcher's mobs are going to stop, and when.
 *
 * Ported from NoammAddons' Blood Camp (CC0, Noamm9). Camping the blood room
 * means standing where the next wave will arrive and hitting it the moment it
 * gets there, and both halves of that are guesswork: the mobs spawn out of
 * sight and walk in, and the Watcher moves the wave on after a length of time
 * nothing announces.
 *
 * Both are worked out from what the server already sends. A mob's walk is a
 * straight line, so the direction of its first few movement packets says where
 * it will end up; the Watcher's speed is the gap between his greeting and the
 * wave starting, which is a number Hypixel rolls per run.
 *
 * The mobs are recognised by the heads they wear rather than by name, because
 * an armour stand carrying one is not otherwise labelled. Those texture lists
 * are NoammAddons' too.
 */
object BloodCamp {
	@JvmField
	val showMobs = ToggleModuleSetting(
		id = "show_mobs",
		label = "Show mob timers",
		defaultValue = true,
		description = "Boxes where each mob stops, and counts it down.",
	)

	@JvmField
	val decimals = SliderModuleSetting(
		id = "decimals",
		label = "Decimal places",
		defaultValue = 1.0,
		min = 0.0,
		max = 2.0,
		step = 1.0,
		visibleIf = { showMobs.value && showTimer.value },
	)

	@JvmField
	val timerColor = ColorModuleSetting(
		id = "timer_color",
		label = "Timer",
		defaultRgb = 0xFFFFFF,
		description = "The countdown's one color, when it is not being run from one to another.",
		visibleIf = { showMobs.value && showTimer.value && !countdownColors.value },
	)

	@JvmField
	val boxColor = ColorModuleSetting(
		id = "box_color",
		label = "Box",
		defaultRgb = 0xFF00FF,
		supportsAlpha = true,
		visibleIf = { showMobs.value },
	)

	@JvmField
	val fillBox = ToggleModuleSetting(
		id = "fill_box",
		label = "Fill the box",
		description = "Fills the cube as well as outlining it.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val countdownColors = ToggleModuleSetting(
		id = "countdown_colors",
		label = "Color the countdown",
		defaultValue = true,
		description = "Runs the timer from one color to another as the mob arrives, instead of one flat color.",
		visibleIf = { showMobs.value && showTimer.value },
	)

	@JvmField
	val farColor = ColorModuleSetting(
		id = "far_color",
		label = "Still walking",
		defaultRgb = 0x55FF55,
		visibleIf = { showMobs.value && showTimer.value && countdownColors.value },
	)

	@JvmField
	val nearColor = ColorModuleSetting(
		id = "near_color",
		label = "About to land",
		defaultRgb = 0xFF5555,
		visibleIf = { showMobs.value && showTimer.value && countdownColors.value },
	)

	@JvmField
	val showMobBox = ToggleModuleSetting(
		id = "show_mob_box",
		label = "Box the mob too",
		description = "Draws a box on the mob itself as well as where it will stop.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val mobBoxColor = ColorModuleSetting(
		id = "mob_box_color",
		label = "Mob box",
		defaultRgb = 0x55FFFF,
		supportsAlpha = true,
		visibleIf = { showMobs.value && showMobBox.value },
	)

	@JvmField
	val finalColor = ColorModuleSetting(
		id = "final_color",
		label = "Arrived",
		defaultRgb = 0x00AAAA,
		supportsAlpha = true,
		description = "The one box left once the mob and its landing spot are the same place.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val boxSize = SliderModuleSetting(
		id = "box_size",
		label = "Box size",
		defaultValue = 1.0,
		min = 0.1,
		max = 1.0,
		step = 0.1,
		description = "Smaller boxes are easier to aim past, and look less accurate than they are.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val showTimer = ToggleModuleSetting(
		id = "show_timer",
		label = "Show the countdown",
		defaultValue = true,
		visibleIf = { showMobs.value },
	)

	private val advancedSection = SectionModuleSetting("advanced_section", "Advanced", visibleIf = { showMobs.value })

	@JvmField
	val tickOffset = SliderModuleSetting(
		id = "tick_offset",
		label = "Offset",
		defaultValue = 40.0,
		min = -100.0,
		max = 100.0,
		step = 5.0,
		description = "Milliseconds added to every countdown, to tune it against what you see.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val spawnTick = SliderModuleSetting(
		id = "spawn_tick",
		label = "Tick",
		defaultValue = 38.0,
		min = 35.0,
		max = 41.0,
		step = 1.0,
		description = "Which tick a mob is assumed to land on. They spawn somewhere between 37 and 41.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val interpolate = ToggleModuleSetting(
		id = "interpolate",
		label = "Interpolation",
		defaultValue = true,
		description = "Smooths the boxes between ticks, at the cost of a little accuracy.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val pingOffset = ToggleModuleSetting(
		id = "ping_offset",
		label = "Ping offset",
		defaultValue = true,
		description = "Moves the mob box forward by your latency, which is where your hits land.",
		visibleIf = { showMobs.value },
	)

	@JvmField
	val manualOffset = SliderModuleSetting(
		id = "manual_offset",
		label = "Mob box offset",
		defaultValue = 0.0,
		min = 0.0,
		max = 300.0,
		step = 10.0,
		description = "Milliseconds to move the mob box forward by, when the ping is not used.",
		visibleIf = { showMobs.value && !pingOffset.value },
	)

	@JvmField
	val watcherBar = ToggleModuleSetting(
		id = "watcher_bar",
		label = "Watcher bar",
		defaultValue = true,
		description = "Writes how many mobs are left into the Watcher's own boss bar.",
	)

	@JvmField
	val lineColor = ColorModuleSetting(
		id = "line_color",
		label = "Line",
		defaultRgb = 0x55FFFF,
		supportsAlpha = true,
		visibleIf = { showMobs.value },
	)

	private val alertSection = SectionModuleSetting("alert_section", "Alerts")

	@JvmField
	val killTitle = ToggleModuleSetting(
		id = "kill_title",
		label = "Kill title",
		description = "A title when the wave is about to be moved on. A guess, not a promise.",
	)

	@JvmField
	val customTitle = TextModuleSetting(
		id = "custom_title",
		label = "Title text",
		defaultValue = "Kill Mobs",
		hint = "Kill Mobs",
		description = "What the kill title says.",
		visibleIf = { killTitle.value },
	)

	@JvmField
	val titleColor = ColorModuleSetting(
		id = "title_color",
		label = "Title color",
		defaultRgb = 0xFF5555,
		description = "Rounded to the nearest color a title can be written in.",
		visibleIf = { killTitle.value },
		inlineWith = killTitle,
	)

	@JvmField
	val doneTitle = ToggleModuleSetting(
		id = "done_title",
		label = "Blood done title",
		description = "Says \"Blood done\" across the screen when the Watcher lets you through.",
	)

	@JvmField
	val speedAlert = ToggleModuleSetting(
		id = "speed_alert",
		label = "Watcher speed",
		description = "Says whether this run's Watcher is fast, normal or slow.",
	)

	@JvmField
	val sendSpeed = ToggleModuleSetting(
		id = "send_speed",
		label = "Tell the party",
		description = "Sends the speed to party chat as well as showing it.",
		visibleIf = { speedAlert.value },
	)

	private val configurable = listOf(
		showMobs,
		boxColor,
		fillBox,
		showMobBox,
		mobBoxColor,
		finalColor,
		boxSize,
		lineColor,
		showTimer,
		decimals,
		countdownColors,
		timerColor,
		farColor,
		nearColor,
		advancedSection,
		tickOffset,
		spawnTick,
		interpolate,
		pingOffset,
		manualOffset,
		watcherBar,
		alertSection,
		killTitle,
		customTitle,
		titleColor,
		doneTitle,
		speedAlert,
		sendSpeed,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurable.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				is TextModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "blood_camp",
		name = "Blood Camp",
		description = "Times the Watcher's waves and where they land",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurable + reset,
	)

	/**
	 * One mob on its way in.
	 *
	 * [start] is where it was first seen and [heading] the sum of every step it
	 * has taken since, which is a direction once there is more than one of them.
	 * A mob that has only just appeared has no heading and so no end point yet.
	 */
	private class BloodMob(val start: Vec3, val bornAt: Long, val firstWave: Boolean) {
		var last: Vec3 = start
		var heading: Vec3 = Vec3.ZERO
		var end: Vec3? = null

		/** How far it moves per millisecond, for the box that leads it. */
		var speed: Vec3 = Vec3.ZERO

		/** Where its two boxes were drawn last, so they can be smoothed. */
		var lastEnd: Vec3? = null
		var lastAhead: Vec3? = null
	}

	private val mobs = LinkedHashMap<ArmorStand, BloodMob>()
	private var watcher: Zombie? = null

	/** Hypixel's own clock, counted from the ping the way every timer here is. */
	private var ticks = 0L

	/** When the Watcher first spoke, which is what his speed is measured from. */
	private var greetedAt: Long? = null

	/** True until the first wave has been sent, which walks in further. */
	private var firstWave = true

	/** Ticks until the kill title, or zero when there is none coming. */
	private var titleIn = 0

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay) onMessage(message.string)
		}
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	fun forget() {
		mobs.clear()
		watcher = null
		ticks = 0
		greetedAt = null
		firstWave = true
		titleIn = 0
	}

	/** Whether anything here has a reason to be watching packets right now. */
	private fun tracking(): Boolean =
		module.enabled && DungeonLocation.inDungeon && !DungeonRun.inBoss


	/**
	 * Writes the mobs left into the Watcher's boss bar.
	 *
	 * Odin's trick: Hypixel gives the bar as a fraction and never says of what,
	 * but the wave is twelve mobs plus the floor number, so the fraction can be
	 * turned back into a count. Below a twentieth the bar is rounding noise
	 * rather than a mob, and is left alone.
	 */
	@JvmStatic
	fun labelWatcherBar(event: BossEvent) {
		if (!module.enabled || !watcherBar.value || !tracking()) return
		val name = event.name.string
		if (name != "§c§lThe Watcher") return

		val total = WAVE_BASE + DungeonLocation.floor
		val progress = event.progress
		if (progress < WATCHER_BAR_FLOOR) return
		event.name = Component.literal("$name ${(total * progress).roundToInt()}/$total")
	}

	@JvmStatic
	fun onServerTick() {
		if (!module.enabled) return
		ticks++

		val fireTitle = titleIn > 0 && --titleIn == 0
		val client = Minecraft.getInstance()
		client.execute {
			if (fireTitle) {
				client.gui.hud.setTimes(0, 20, 10)
				client.gui.hud.setTitle(Component.literal(killTitleText()))
			}

			// A mob whose armour stand has gone has been killed, or its wave
			// has been moved on; either way there is nothing left to time.
			mobs.entries.removeIf { it.key.isRemoved }
			if (watcher?.isRemoved == true) watcher = null
		}
	}

	/**
	 * What the kill title says, and in what colour.
	 *
	 * The wording is NoammAddons' until you change it. A title is read out of
	 * the corner of an eye in the middle of a fight, so which words do that
	 * best is personal; the colour is rounded to the sixteen a title can
	 * actually be written in.
	 */
	private fun killTitleText(): String {
		val text = customTitle.value.trim().ifEmpty { DEFAULT_KILL_TITLE }
		return colorCode(titleColor.argb) + "§l" + text
	}

	/**
	 * The nearest of Minecraft's sixteen colours to a chosen one.
	 *
	 * A title takes a colour code rather than a colour, so the picker has to be
	 * rounded to the palette the game will accept. Distance is measured in
	 * plain RGB, which is close enough across sixteen well-spread colours.
	 */
	private fun colorCode(argb: Int): String {
		val red = (argb shr 16) and 0xFF
		val green = (argb shr 8) and 0xFF
		val blue = argb and 0xFF
		var best = "§c"
		var bestDistance = Int.MAX_VALUE
		CHAT_COLORS.forEach { (code, rgb) ->
			val dr = red - ((rgb shr 16) and 0xFF)
			val dg = green - ((rgb shr 8) and 0xFF)
			val db = blue - (rgb and 0xFF)
			val distance = dr * dr + dg * dg + db * db
			if (distance < bestDistance) {
				bestDistance = distance
				best = code
			}
		}
		return best
	}

	private const val DEFAULT_KILL_TITLE = "Kill Mobs"

	/** Minecraft's palette, which is all a title can be written in. */
	private val CHAT_COLORS = listOf(
		"§0" to 0x000000, "§1" to 0x0000AA, "§2" to 0x00AA00, "§3" to 0x00AAAA,
		"§4" to 0xAA0000, "§5" to 0xAA00AA, "§6" to 0xFFAA00, "§7" to 0xAAAAAA,
		"§8" to 0x555555, "§9" to 0x5555FF, "§a" to 0x55FF55, "§b" to 0x55FFFF,
		"§c" to 0xFF5555, "§d" to 0xFF55FF, "§e" to 0xFFFF55, "§f" to 0xFFFFFF,
	)

	private fun onMessage(line: String) {
		if (!tracking()) return
		if (!line.startsWith(WATCHER_PREFIX)) return

		// The Watcher letting you through is the end of the camp, and the one
		// moment in the room worth looking up for. Checked before the greeting:
		// the first thing he says is taken as his greeting, so for anyone who
		// walked in after it, this line was being used up as one.
		if (line == DONE_MESSAGE) {
			if (doneTitle.value) {
				val client = Minecraft.getInstance()
				client.gui.hud.setTimes(0, DONE_TITLE_STAY_TICKS, DONE_TITLE_FADE_TICKS)
				client.gui.hud.setTitle(Component.literal("§fBlood done"))
			}
			return
		}

		if (greetedAt == null) {
			greetedAt = ticks
			return
		}


		if (line != WAVE_MESSAGE) return
		val greeted = greetedAt ?: return
		val seconds = ((ticks - greeted) / 20).toInt()
		firstWave = false

		val client = Minecraft.getInstance()
		if (killTitle.value) {
			// NoammAddons' table: the gap between the greeting and the wave
			// decides how long the Watcher leaves it there.
			val moveTicks = when (seconds) {
				in 31..33 -> 36
				in 28..30 -> 33
				in 25..27 -> 30
				in 22..24 -> 27
				in 1..21 -> 24
				else -> seconds + 3
			}
			titleIn = moveTicks
			client.gui.hud.chat.addClientSystemMessage(
				Component.literal("§8[Cryptic] §7Watcher moves in §f${seconds(moveTicks.toDouble(), 2)}s§7."),
			)
		}

		if (!speedAlert.value) return
		val title = when {
			seconds < 22 -> "§4§lFAST WATCHER"
			seconds < 25 -> "§cNormal Watcher"
			else -> "§8Slow Watcher"
		}
		val sound = when {
			seconds < 22 -> SoundEvents.TRIDENT_THUNDER.value()
			seconds < 25 -> SoundEvents.WARDEN_DEATH
			else -> SoundEvents.VILLAGER_DEATH
		}

		client.player?.let { player -> repeat(5) { player.playSound(sound, 0.25f, 1f) } }
		client.gui.hud.setTimes(0, 30, 10)
		client.gui.hud.setTitle(Component.literal(title))
		if (sendSpeed.value) client.connection?.sendCommand("pc ${title.replace(Regex("§."), "")}")
	}

	/**
	 * The Watcher himself, found by the head he is wearing.
	 *
	 * Called for every equipment packet, so the cheap tests come first: he is
	 * only looked for while there is not one already, and only in a dungeon.
	 */
	@JvmStatic
	fun onEquipment(entityId: Int, slot: EquipmentSlot, stack: ItemStack) {
		if (!tracking() || watcher != null) return
		if (slot != EquipmentSlot.HEAD || !stack.`is`(Items.PLAYER_HEAD)) return
		if (SkyblockItem.skullTexture(stack) !in watcherSkulls) return

		val client = Minecraft.getInstance()
		watcher = client.level?.getEntity(entityId) as? Zombie
	}

	/**
	 * One step of a mob's walk in.
	 *
	 * Taken off the packet rather than off the entity, because the entity is
	 * moved by interpolation: what the server actually said is one step per
	 * tick, and a direction built out of those is straight where one built out
	 * of drawn positions wobbles.
	 */
	@JvmStatic
	fun onEntityMove(packet: ClientboundMoveEntityPacket) {
		if (!tracking() || !showMobs.value) return
		val xa = packet.xa
		val ya = packet.ya
		val za = packet.za
		if (xa == 0.toShort() && ya == 0.toShort() && za == 0.toShort()) return

		val level = Minecraft.getInstance().level ?: return
		val entity = packet.getEntity(level) as? ArmorStand ?: return
		// Only the stands standing on the Watcher's own mobs, which is what
		// keeps the room's nametags and the party out of it.
		val boss = watcher ?: return
		if (boss.distanceToSqr(entity) > WATCHER_RADIUS_SQUARED) return

		val head = entity.getItemBySlot(EquipmentSlot.HEAD)
		if (!head.`is`(Items.PLAYER_HEAD)) return
		if (SkyblockItem.skullTexture(head) !in mobSkulls) return

		val moved = Vec3(
			entity.x + xa / PACKET_UNITS,
			entity.y + ya / PACKET_UNITS,
			entity.z + za / PACKET_UNITS,
		)

		val mob = mobs.getOrPut(entity) { BloodMob(moved, ticks, firstWave) }
		val step = moved.subtract(mob.last)
		mob.last = moved
		if (step.lengthSqr() > 0) mob.heading = mob.heading.add(step)
		if (mob.heading.lengthSqr() <= 0) return

		// The walk is the same length every time, so where it ends is the start
		// plus that many blocks in the direction it is going.
		val reach = if (mob.firstWave) FIRST_WAVE_REACH else LATER_WAVE_REACH
		mob.end = mob.start.add(mob.heading.normalize().scale(reach))
	}

	/**
	 * The two boxes and the countdown, drawn Odin's way.
	 *
	 * A mob on its way in gets two: where it is, moved forward by however far
	 * your hits are behind you, and where it will stop. Once those two are the
	 * same place there is only one box left, in a colour of its own, and that
	 * is the moment to swing.
	 */
	private fun render(context: LevelRenderContext) {
		if (!tracking() || !showMobs.value || mobs.isEmpty()) return

		val client = Minecraft.getInstance()
		val partial = client.deltaTracker.getGameTimeDeltaPartialTick(false)
		val size = boxSize.value
		val half = size / 2.0
		val lead = if (pingOffset.value) ping().toDouble() else manualOffset.value

		mobs.forEach { (stand, mob) ->
			val end = mob.end ?: return@forEach

			// Milliseconds until it lands, on Hypixel's clock rather than the
			// client's: the tick it spawns on is a setting because Hypixel
			// picks one between thirty-seven and forty-one.
			val since = (ticks - mob.bornAt) * MILLIS_PER_TICK
			val left = (if (mob.firstWave) FIRST_WAVE_MILLIS else 0) +
				(spawnTick.value * MILLIS_PER_TICK) - since + tickOffset.value

			val arrived = lead >= left
			val landing = interpolated(mob.lastEnd, end, partial)
			mob.lastEnd = end

			// Where a hit of yours would land, which is ahead of where the mob
			// is drawn by exactly as long as the packet takes to arrive.
			val speed = mob.speed
			val ahead = Vec3(
				stand.x + speed.x * lead,
				stand.y + speed.y * lead,
				stand.z + speed.z * lead,
			)

			if (!arrived && showMobBox.value) {
				drawCube(context, interpolated(mob.lastAhead, ahead, partial), half, size, mobBoxColor.argb)
			}
			mob.lastAhead = ahead

			drawCube(context, landing, half, size, if (arrived) finalColor.argb else boxColor.argb)

			if (lineColor.alpha > 0) {
				WorldRender.drawLine(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					points = listOf(stand.position().add(0.0, 2.0, 0.0), landing.add(0.0, 2.0, 0.0)),
					argb = lineColor.argb,
					lineWidth = 2f,
					phase = true,
				)
			}

			if (!showTimer.value) return@forEach
			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = client.gameRenderer.mainCamera().rotation(),
				text = Component.literal(seconds(left / 1000.0, decimals.value.toInt())),
				x = landing.x,
				y = landing.y + BOX_HEIGHT + 1.0,
				z = landing.z,
				scale = 2f,
				seeThrough = true,
				argb = countdownArgb(left / 1000.0),
			)
		}
	}

	/** One of the boxes: a cube of the chosen size, at head height. */
	private fun drawCube(context: LevelRenderContext, at: Vec3, half: Double, size: Double, argb: Int) {
		if (((argb ushr 24) and 0xFF) == 0) return
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = at.x - half,
			minY = at.y + BOX_HEIGHT,
			minZ = at.z - half,
			maxX = at.x - half + size,
			maxY = at.y + BOX_HEIGHT + size,
			maxZ = at.z - half + size,
			outlineArgb = argb,
			fillArgb = argb,
			outline = true,
			fill = fillBox.value,
			phase = true,
			lineWidth = 2f,
		)
	}

	/**
	 * Part of the way from where a box was to where it is.
	 *
	 * Positions arrive once a tick and frames are drawn far more often than
	 * that, so without this the boxes step twenty times a second. Odin offers
	 * the same choice for the same reason: the smoothing is a guess between two
	 * known points, and a guess is slightly wrong.
	 */
	private fun interpolated(from: Vec3?, to: Vec3, partial: Float): Vec3 {
		if (!interpolate.value || from == null) return to
		return Vec3(
			from.x + (to.x - from.x) * partial,
			from.y + (to.y - from.y) * partial,
			from.z + (to.z - from.z) * partial,
		)
	}

	/**
	 * The colour of a mob's countdown, which is either flat or a fade.
	 *
	 * Green while there is time and red as it runs out, mixed rather than
	 * switched so the change is something you notice out of the corner of an
	 * eye without having to read the number.
	 */
	private fun countdownArgb(secondsLeft: Double): Int {
		if (!countdownColors.value) return timerColor.argb

		val part = (secondsLeft / COUNTDOWN_FADE_SECONDS).coerceIn(0.0, 1.0)
		return blend(nearColor.argb, farColor.argb, part.toFloat())
	}

	/** [amount] of the way from [from] to [to], channel by channel. */
	private fun blend(from: Int, to: Int, amount: Float): Int {
		fun mix(shift: Int): Int {
			val a = (from shr shift) and 0xFF
			val b = (to shr shift) and 0xFF
			return (a + (b - a) * amount).toInt().coerceIn(0, 255)
		}
		return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
	}

	private fun seconds(value: Double, places: Int): String =
		if (places > 0) String.format(Locale.ROOT, "%.${places}f", value) else ceil(value).toInt().toString()

	/** Your own latency, which is how far ahead of the server a swing lands. */
	private fun ping(): Int {
		val client = Minecraft.getInstance()
		val uuid = client.player?.uuid ?: return 0
		return client.connection?.getPlayerInfo(uuid)?.latency ?: 0
	}

	/** A wave is this many mobs plus the floor number, which is Odin's count. */
	private const val WAVE_BASE = 12

	/** Below this the bar is rounding noise rather than a mob left standing. */
	private const val WATCHER_BAR_FLOOR = 0.05f

	/** How long the "Blood done" title holds, and how long it takes to fade, in ticks. */
	private const val DONE_TITLE_STAY_TICKS = 30
	private const val DONE_TITLE_FADE_TICKS = 10

	private const val WATCHER_PREFIX = "[BOSS] The Watcher: "
	private const val WAVE_MESSAGE = "[BOSS] The Watcher: Let's see how you can handle this."
	private const val DONE_MESSAGE = "[BOSS] The Watcher: You have proven yourself. You may pass."

	/** A movement packet counts in 1/4096ths of a block. */
	private const val PACKET_UNITS = 4096.0

	/** How near the Watcher one of his mobs stands, squared. */
	private const val WATCHER_RADIUS_SQUARED = 400.0

	/** How far a mob walks in, which is further on the first wave. */
	private const val FIRST_WAVE_REACH = 16.1
	private const val LATER_WAVE_REACH = 11.9

	/** The first wave walks in two seconds later than the ones after it. */
	private const val FIRST_WAVE_MILLIS = 2_000

	/** How long the countdown takes to run from one colour to the other. */
	private const val COUNTDOWN_FADE_SECONDS = 2.0
	private const val MILLIS_PER_TICK = 50

	/** How high the cube sits, which is where the mob's head arrives. */
	private const val BOX_HEIGHT = 1.5

	/** The Watcher's own heads, which is how he is told from any other zombie. */
	private val watcherSkulls = setOf(
		"ewogICJ0aW1lc3RhbXAiIDogMTY5NzMwOTQxNzI1NiwKICAicHJvZmlsZUlkIiA6ICJjYjYxY2U5ODc4ZWI0NDljODA5MzliNWYxNTkwMzE1MiIsCiAgInByb2ZpbGVOYW1lIiA6ICJWb2lkZWRUcmFzaDUxODUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTY2MmI2ZmI0YjhiNTg2ZGM0Y2RmODAzYjA0NDRkOWI0MWQyNDVjZGY2NjhkYWIzOGZhNmMwNjRhZmU4ZTQ2MSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjM1MjMyMiwKICAicHJvZmlsZUlkIiA6ICI3MmY5MTdjNWQyNDU0OTk0YjlmYzQ1YjVhM2YyMjIzMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGF0X0d1eV9Jc19NZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yNzM5ZDdmNGU2NmE3ZGIyZWE2Y2Q0MTRlNGM0YmE0MWRmN2E5MjQ1NWM5ZmM0MmNhYWIwMTQ2NjVjMzY3YWQ1IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjI5MjgzNiwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iZjZlMWU3ZWQzNjU4NmMyZDk4MDU3MDAyYmMxYWRjOTgxZTI4ODlmN2JkN2I1YjM4NTJiYzU1Y2M3ODAyMjA0IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTY5NzIzODQ0NjgxMiwKICAicHJvZmlsZUlkIiA6ICJmMjc0YzRkNjI1MDQ0ZTQxOGVmYmYwNmM3NWIyMDIxMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJIeXBpZ3NlbCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS80Y2VjNDAwMDhlMWMzMWMxOTg0ZjRkNjUwYWJiMzQxMGYyMDM3MTE5ZmQ2MjRhZmM5NTM1NjNiNzM1MTVhMDc3IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjAwOTg2NywKICAicHJvZmlsZUlkIiA6ICJiMGQ0YjI4YmMxZDc0ODg5YWYwZTg2NjFjZWU5NmFhYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lU2tpbl9vcmciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjM3ZGQxOGI1OTgzYTc2N2U1NTZkYzY0NDI0YWY0YjlhYmRiNzVkNGM5ZThiMDk3ODE4YWZiYzQzMWJmMGUwOSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNTkyNDIwNSwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9mNWYwZDc4ZmUzOGQxZDdmNzVmMDhjZGNmMmExODU1ZDZkYTAzMzdlMTE0YTNjNjNlM2JmM2M2MThiYzczMmIwIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTU1MDkyNjM2MSwKICAicHJvZmlsZUlkIiA6ICI0ZDcwNDg2ZjUwOTI0ZDMzODZiYmZjOWMxMmJhYjRhZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJzaXJGYWJpb3pzY2hlIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzUxOTY3ZGI1ZTMxOTk5MTYyNTIwMjE5MDNjZjRlOTk1MmVmN2NlYzIyMGZhYWNhMWJhNzliYWZlNTkzOGJkODAiCiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIxMjc1NSwKICAicHJvZmlsZUlkIiA6ICI2NGRiNmMwNTliOTk0OTM2YTY0M2QwODEwODE0ZmJkMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVTaWx2ZXJEcmVhbXMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWZkNjFlODA1NWY2ZWU5N2FiNWI2MTk2YThkN2VjOTgwNzhhYzM3ZTAwMzc2MTU3YjZiNTIwZWFhYTJmOTNhZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIzOTU4NiwKICAicHJvZmlsZUlkIiA6ICJhYWZmMDUwYTExOTk0NzM1YjEyNDVlNDk0MGFlZjY4NCIsCiAgInByb2ZpbGVOYW1lIiA6ICJMYXN0SW1tb3J0YWwiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTVjMWRjNDdhMDRjZTU3MDAxYThiNzI2ZjAxOGNkZWY0MGI3ZWE5ZDdiZDZkODM1Y2E0OTVhMGVmMTY5Zjg5MyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
	)

	/** The heads his mobs wear, which is how a wave is told from the scenery. */
	private val mobSkulls = setOf(
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDEwNjQwNTAsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVhNzk4NjBhY2E3OTk0MDdjMGZhYTEwYjFiYmNmNDI5OThmYWQ0ZWJjZjMxZDdhMjE0MTgwODI2YjRhYzk0ZTEifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDExODY2MzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzQ3NzQ4NzExOTBjODc4YzlhMmM0NDk2YzFlMTAyNTdjNmM0ZWExMzgwN2Q3MmMxNWQ3YWM2YWIzYTdhOWE4ZGMifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDAyMDM1NzMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2Y0NjI0YTlhOGM2OWNhMjA0NTA0YWJiMDQzZDQ3NDU2Y2Q5YjA5NzQ5YTM2MzU3NDYyMzAzZjI3NmEyMjlkNCJ9fX0=",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDExNDUyMjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2M5MTllNWI4ZDU2ZjA2MmEyMWQyMjRkZTE0YWY3NzFlMmY1NWQwOWI1OWU3YjA5OWQwOWRhYTU3NTQwYjc5Y2YiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA1MzgzODIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2E4OWY2MzAzYWY4NTg3NzYxMDkxMmRjMDRiOGIxZTg5NzI0NzUyZjBhN2VlYTA1YWI2NTQ3ZTIyODE3OWMwNmYiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA5ODk1NTgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzY3MjM3ZWRkYWViZGJiZGFhY2ZhOTEyODg1NTYwY2NkYzY1ZGE5M2I0YzNkNTEzNTMyODY4ZWMyM2JiNWI0NDgifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA0OTUwMjgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2ZmMTg0YzE5ZTcyNTYyM2QzMjgyOGEwYTRlNzQxZTg2ZjEzNWFjNjNkYmM4MjhmZjNjODQ2ODMzOGYzNjgzYiJ9fX0=",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDEwMzA3NjUsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVjY2NkNTNmNTE5MWMyOWE5ZGM4ZjAxNzBmYmRjNGU1OWU2NjQ3NmFhZTMzZGUyN2I0NjhmMWRlMWI3Y2YzYjIifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA5MTc4NzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2I1YmE3NmUwMmNhYjcyZmE3ZDhhYzU0Y2VlYzg0OTk3NmFiMGIwMGEwMTA2OGQ2OGMyNjY3NjZiZjcwYzM5OTcifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA3Njk2MTQsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FhMjNjOGNkZTI5NDNjODQyNDlkZTgzNTFiYzM1NDBiZTVmOGFmYWFiYThiMmNiMDMyZmM1YWNhZDc4YTI2OWIifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA4MTg4MDMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzkxNzFmMzViOGY1MDgxNDJiZDhjNjU0MTdkMGYzMjQxNTNhYjkxNDc3MzllZTRkMTBkZWE3MzNjYzgwZWFhMjAifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA5NTY0MjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzdkMTJiMmFkZTQxM2E2Y2Q3Y2NhM2M5NWU5NjFiYTlmMGFlNzE2NWZhNDFmYzdiNWQ1ZjA5NGEwMTI0MGM2MDkifX19",
		"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTZjM2UzMWNmYzY2NzMzMjc1YzQyZmNmYjVkOWE0NDM0MmQ2NDNiNTVjZDE0YzljNzdkMjczYTIzNTIifX19",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzE2OTIxMSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODQyMWJhNWI4ZTM1NzNlZjk3YmViNWI0MGUxNWQxNWIyMGYzMDYzMWM0YzUzMzBjM2RlZGEzMDQ3ZGYwZTkyIgogICAgfQogIH0KfQ==",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzExMjUwMCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQyMjc3MmY3NjkwNDVmZGM1YmU4MTlhZDY4YjAxYTk3YWMwNGM2MDg4NmQyY2E3YWZlZTM5YjI4MmY3YTM4MyIKICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzM4Njc5NCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQ2N2Y5N2Q3ZjgyMTcyOWJlYjM0YTgyYzNmMTM1OTJiNDA0MzlmZTUyNDhlNzI1NzZmZGU3YWExODBiZjc3IgogICAgfQogIH0KfQ==",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzIxNTkwNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmIzOTczYTc1MmIyNGEyZjNhYmIwMDM0MjdmNmRiZTZjYTNhNjFkYjBhMWJjZjM1MWM2ZWFiMjdlYzI3ZTUwIgogICAgfQogIH0KfQ==",
		"eyJ0aW1lc3RhbXAiOjE1NzQ0MTkzMTAxNjQsInByb2ZpbGVJZCI6Ijc1MTQ0NDgxOTFlNjQ1NDY4Yzk3MzlhNmUzOTU3YmViIiwicHJvZmlsZU5hbWUiOiJUaGFua3NNb2phbmciLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzEyNzE2ZWNiZjViOGRhMDBiMDVmMzE2ZWM2YWY2MWU4YmQwMjgwNWIyMWViOGU0NDAxNTE0NjhkYzY1NjU0OWMifX19",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzAyODAxNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzI2MDMyNTE3MWE3YmE4NDYwODMwYzBlZWE1MTVjNzU3YTY2NWU1YjE2YTE0MjA3YmExYTMxODI3NTJiZWU4NyIKICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTU5NTQyODIyMDAyMCwKICAicHJvZmlsZUlkIiA6ICJkYTQ5OGFjNGU5Mzc0ZTVjYjYxMjdiMzgwODU1Nzk4MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJOaXRyb2hvbGljXzIiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjJkOGZkM2FhNTYxN2IxZGFjMGFhZTljODFmNmRkNzBhZDkzYTU5OTQyZjQ2MGQyN2U0ZDU1YTVjYjg5MThlOCIKICAgIH0KICB9Cn0=",
		"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZmYzg1NGJiODRjZjRiNzY5NzI5Nzk3M2UwMmI3OWJjMTA2OTg0NjBiNTFhNjM5YzYwZTVlNDE3NzM0ZTExIn19fQ==",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTc5MzA2ODgzOSwKICAicHJvZmlsZUlkIiA6ICIyYzEwNjRmY2Q5MTc0MjgyODRlM2JmN2ZhYTdlM2UxYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOYWVtZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS83ZGU3YmJiZGYyMmJmZTE3OTgwZDRlMjA2ODdlMzg2ZjExZDU5ZWUxZGI2ZjhiNDc2MjM5MWI3OWE1YWM1MzJkIgogICAgfQogIH0KfQ==",
		"ewogICJ0aW1lc3RhbXAiIDogMTU5ODk3NzI1OTM1NywKICAicHJvZmlsZUlkIiA6ICJlNzkzYjJjYTdhMmY0MTI2YTA5ODA5MmQ3Yzk5NDE3YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVfSG9zdGVyX01hbiIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMTAwN2M1YjcxMTRhYmVjNzM0MjA2ZDRmYzYxM2RhNGYzYTBlOTlmNzFmZjk0OWNlZGFkYzk5MDc5MTM1YTBiIgogICAgfQogIH0KfQ=="
	)
}
