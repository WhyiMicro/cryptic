package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.carry.Carry
import imicro.cryptic.carry.CarryStore
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.slayer.VoidgloomBosses
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity

/**
 * Tracks slayer carries you're running for others: how many of each order are done, which bosses in
 * the lobby are theirs, and when to stop. Shaped after Athen's `SlayerCarryTracker`, but managed in
 * a window, and carries are added by hand rather than inferred from payments. Kills are counted
 * from the boss's death animation, since Hypixel says nothing about other people's bosses.
 */
object CarryManager {
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL_OUTLINE = 1
	private const val STYLE_GLOW = 2

	/** Often enough to see a boss teleport to its summoner. */
	private const val SCAN_INTERVAL_TICKS = 5

	/** How long a kill's count stays on screen. */
	private const val COUNT_TOAST_SECONDS = 5.0

	private const val VOIDGLOOM_ISLAND = "The End"

	private const val PLAYER_TOKEN = "<player>"

	/** Stands for the running count, e.g. `3/20`. */
	private const val COUNT_TOKEN = "<count>"

	private const val PARTY_CHAT_COMMAND = "pc"

	private const val DONE_TOAST_SECONDS = 10.0

	private const val FADE_IN_TICKS = 0
	private const val STAY_TICKS = 25
	private const val FADE_OUT_TICKS = 10

	private val voidgloomSection = SectionModuleSetting("voidgloom_section", "Voidgloom Seraph")

	@JvmField
	val confirmBoss = ToggleModuleSetting(
		id = "confirm_boss",
		label = "Confirm boss",
		defaultValue = false,
		description = "When a boss is given to someone you're carrying, a notification asks if it's really theirs. Y keeps it; N passes it to the next likeliest player. No answer counts as yes.",
	)

	@JvmField
	val customWeights = ToggleModuleSetting(
		id = "custom_weights",
		label = "Owner guess weights",
		defaultValue = false,
		description = "Set how much each sign counts when working out whose boss is whose. Off, Cryptic's own values are used.",
	)

	private fun weight(id: String, label: String, default: Double, description: String) = SliderModuleSetting(
		id = id, label = label, defaultValue = default, min = 0.0, max = 20.0, step = 0.5,
		description = description, visibleIf = { customWeights.value },
	)

	@JvmField val hitWeight = weight("weight_hit", "Their hits", 6.0, "Points for the player who hits the boss, per hit.")
	@JvmField val carrierHitWeight = weight("weight_carrier_hit", "Your hits", 6.0, "Points your own hit gives the client near the boss (shared if there are several), per hit.")
	@JvmField val lookWeight = weight("weight_look", "Looking at them", 2.0, "Points, four times a second, for the player the boss looks straight at.")
	@JvmField val moveWeight = weight("weight_move", "Walking toward them", 2.0, "Points, four times a second, for the player the boss walks toward.")
	@JvmField val nearWeight = weight("weight_near", "Closeness", 1.0, "Points, four times a second, for being right next to the boss, less with distance.")
	@JvmField val safespotWeight = weight("weight_safespot", "Safespot", 3.0, "Points, four times a second, for standing in the safespot over the boss, shared by everyone there.")

	@JvmField
	val carriedShare = SliderModuleSetting(
		id = "share_carried", label = "Sure enough (client)", defaultValue = 75.0, min = 50.0, max = 100.0, step = 1.0, unit = "%",
		description = "The share of the odds that gives a boss to someone you're carrying.", visibleIf = { customWeights.value },
	)

	@JvmField
	val otherShare = SliderModuleSetting(
		id = "share_other", label = "Sure enough (others)", defaultValue = 99.0, min = 50.0, max = 100.0, step = 1.0, unit = "%",
		description = "The share of the odds that gives a boss to anyone else.", visibleIf = { customWeights.value },
	)

	@JvmField
	val watchSeconds = SliderModuleSetting(
		id = "watch_seconds", label = "Watch time", defaultValue = 10.0, min = 3.0, max = 30.0, step = 1.0, unit = "s",
		description = "After this long, a client who's the favourite is given the boss even below their share.", visibleIf = { customWeights.value },
	)

	@JvmField
	val assignKey = imicro.cryptic.gui.KeybindModuleSetting(
		id = "assign_key",
		label = "Assign or remove boss",
		description = "Look at a boss and press: takes it off the carry it's counted for, or gives it to the carry standing nearest it.",
	)

	/** How long the confirmation waits for Y or N. */
	private const val CONFIRM_SECONDS = 10.0

	@JvmField
	val counterHud = ToggleModuleSetting(
		id = "counter_hud",
		label = "Counter HUD",
		defaultValue = false,
		description = "A HUD element listing each carry's kills so far, like \"Notch T4 3/5\". Place it in the HUD editor.",
	)

	@JvmField
	val highlightBoss = ToggleModuleSetting(
		id = "highlight_boss",
		label = "Highlight carried player's boss",
		defaultValue = true,
		description = "Marks the boss of whoever you are carrying.",
	)

	@JvmField
	val highlightStyle = DropdownModuleSetting(
		id = "highlight_style",
		label = "Style",
		options = listOf("Outline", "Outline + Fill", "Glow"),
		defaultIndex = STYLE_OUTLINE,
		description = "Glow follows the boss's shape instead of boxing it.",
		visibleIf = { highlightBoss.value },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { highlightBoss.value },
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0x40,
		visibleIf = { highlightBoss.value && highlightStyle.selectedIndex == STYLE_FILL_OUTLINE },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { highlightBoss.value && highlightStyle.selectedIndex != STYLE_GLOW },
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the box through walls, which the End's terrain is full of.",
		visibleIf = { highlightBoss.value && highlightStyle.selectedIndex != STYLE_GLOW },
	)

	@JvmField
	val tracer = ToggleModuleSetting(
		id = "tracer",
		label = "Tracer",
		defaultValue = false,
		description = "A line from you to the boss.",
	)

	@JvmField
	val tracerColor = ColorModuleSetting(
		id = "tracer_color",
		label = "Tracer",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { tracer.value },
		inlineWith = tracer,
	)

	@JvmField
	val alertSpawn = ToggleModuleSetting(
		id = "alert_spawn",
		label = "Alert boss spawn",
		defaultValue = true,
		description = "A title the moment a boss spawns for somebody you are carrying.",
	)

	@JvmField
	val spawnTitle = TextModuleSetting(
		id = "spawn_title",
		label = "Spawn text ($PLAYER_TOKEN becomes their name)",
		defaultValue = "$PLAYER_TOKEN's boss spawned!",
		maxLength = 64,
		description = "$PLAYER_TOKEN is replaced with whoever the boss belongs to.",
		visibleIf = { alertSpawn.value },
	)

	@JvmField
	val announceProgress = ToggleModuleSetting(
		id = "announce_progress",
		label = "Tell them the count",
		defaultValue = false,
		description = "Sends the count to party chat after each boss.",
	)

	@JvmField
	val progressMessage = TextModuleSetting(
		id = "progress_message",
		label = "Count text ($PLAYER_TOKEN and $COUNT_TOKEN)",
		defaultValue = "$PLAYER_TOKEN $COUNT_TOKEN",
		maxLength = 64,
		description = "$COUNT_TOKEN becomes the progress, like 3/20.",
		visibleIf = { announceProgress.value },
	)

	private val configurable = listOf(
		highlightBoss, highlightStyle, outlineColor, fillColor, lineWidth, phase, tracer, tracerColor,
		alertSpawn, spawnTitle, announceProgress, progressMessage,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurable.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				is TextModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	/** The card's bind button, pointed at the real key mapping. */
	private val openKeybind = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.carryManagerKey.isUnbound) {
				"None"
			} else {
				CrypticClient.carryManagerKey.translatedKeyMessage.string.uppercase()
			}
		},
		onKeyChanged = { key ->
			CrypticClient.carryManagerKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "carry_manager",
		name = "Carry Manager",
		description = "Tracks your slayer carries",
		category = ModuleCategory.GENERAL,
		supportsKeybind = true,
		keybind = openKeybind,
		settings = listOf(
			counterHud,
			confirmBoss,
			assignKey,
			customWeights.withMore(hitWeight, carrierHitWeight, lookWeight, moveWeight, nearWeight, safespotWeight, carriedShare, otherShare, watchSeconds),
			voidgloomSection,
			highlightBoss.withMore(highlightStyle, outlineColor, fillColor, lineWidth, phase),
			tracer.withMore(tracerColor),
			alertSpawn.withMore(spawnTitle),
			announceProgress.withMore(progressMessage),
			reset,
		),
	)

	/** Boss entity id to its carry, and when first seen. */
	private val tracked = HashMap<Int, TrackedBoss>()

	private class TrackedBoss(val carry: Carry, val spawnedAt: Long)

	/** Bosses already dead, until the world removes them (the death animation leaves them in place). */
	private val killed = HashSet<Int>()

	/** Bosses drawn this tick, so rendering does no scanning. */
	private var boxes: List<Entity> = emptyList()

	private var ticksUntilScan = 0

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		imicro.cryptic.hud.Hud.register(CounterElement())
		VoidgloomBosses.carried = { name -> carries.any { !it.finished && it.name.equals(name, ignoreCase = true) } }
		VoidgloomBosses.weights = {
			if (!customWeights.value) VoidgloomBosses.Weights() else VoidgloomBosses.Weights(
				hit = hitWeight.value, carrierHit = carrierHitWeight.value, look = lookWeight.value, move = moveWeight.value,
				near = nearWeight.value, safespot = safespotWeight.value,
				carriedShare = carriedShare.value / 100.0, otherShare = otherShare.value / 100.0,
				watchMillis = (watchSeconds.value * 1000).toLong(),
			)
		}
	}

	/** Each unfinished carry's count, one per line. */
	private class CounterElement : imicro.cryptic.hud.HudElement("carry_counter", "Carry Counter", 0.005, 0.3) {
		private val font get() = Minecraft.getInstance().font

		private fun lines(example: Boolean): List<Carry> =
			if (example) listOf(Carry("Notch", 4, 5).also { it.done = 3 }) else carries.filter { !it.finished }

		override val width: Int get() = (lines(carries.isEmpty()).maxOfOrNull { font.width(text(it)) } ?: 60).coerceAtLeast(60)
		override val height: Int get() = font.lineHeight * maxOf(1, lines(carries.isEmpty()).size)

		override fun isVisible(): Boolean = module.enabled && counterHud.value && carries.any { !it.finished }

		override fun showInEditor(): Boolean = module.enabled && counterHud.value

		override fun render(context: net.minecraft.client.gui.GuiGraphicsExtractor) = draw(context, lines(false))

		override fun renderExample(context: net.minecraft.client.gui.GuiGraphicsExtractor) = draw(context, lines(true))

		private fun text(carry: Carry) = "§d${carry.name} §8T${carry.tier} §e${carry.done}§7/§e${carry.ordered}"

		private fun draw(context: net.minecraft.client.gui.GuiGraphicsExtractor, carries: List<Carry>) {
			carries.forEachIndexed { row, carry -> context.text(font, text(carry), 0, row * font.lineHeight, -1) }
		}
	}

	val carries: MutableList<Carry> get() = CarryStore.active

	val activeCarry: Carry? get() = carries.firstOrNull { !it.finished }

	fun add(name: String, tier: Int, count: Int): Boolean {
		val trimmed = name.trim()
		if (trimmed.isEmpty() || count <= 0) return false
		// One carry per name: a second order is more of the same job.
		val existing = carries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) && it.tier == tier }
		if (existing != null) {
			existing.ordered += count
		} else {
			carries.add(Carry(trimmed, tier, count))
		}
		CarryStore.save()
		return true
	}

	fun remove(carry: Carry) {
		carries.removeIf { it === carry }
		tracked.values.removeIf { it.carry === carry }
		CarryStore.save()
	}

	/** Every carry for [name], any tier, for the remove command. */
	fun removeByName(name: String): Int {
		val matches = carries.filter { it.name.equals(name.trim(), ignoreCase = true) }
		matches.forEach { remove(it) }
		return matches.size
	}

	fun trackedNames(): List<String> = carries.map { it.name }

	/** The list as chat lines, for `/cryptic carry list`, readable mid-fight. */
	fun describeCarries(): List<String> {
		if (carries.isEmpty()) return listOf("§7No carries on the list.")

		return listOf("§dCarries:") + carries.map { carry ->
			val bar = if (carry.finished) "§a" else "§e"
			" §8- §b${carry.name} §8[§7T${carry.tier}§8] $bar${carry.done}§7/§f${carry.ordered}"
		}
	}

	fun complete(carry: Carry) {
		tracked.values.removeIf { it.carry === carry }
		CarryStore.complete(carry)
	}

	/** Finished carries, newest first, for `/cryptic carry history`. */
	fun describeHistory(limit: Int): List<String> {
		val history = CarryStore.history
		if (history.isEmpty()) return listOf("§7No finished carries yet.")

		return listOf("§dFinished carries:") + history.asReversed().take(limit).map { entry ->
			val took = if (entry.durationMillis > 0) " §7in §f${readableDuration(entry.durationMillis)}" else ""
			" §8- §b${entry.name} §8[§7T${entry.tier}§8] §f${entry.count}§7/§f${entry.orderedOrCount} " +
				"§8${entry.bossType}$took"
		}
	}

	private fun readableDuration(millis: Long): String {
		val seconds = millis / 1000
		if (seconds < 60) return "${seconds}s"
		return "${seconds / 60}m ${seconds % 60}s"
	}

	/** Watches for bosses belonging to anyone on the list. Does nothing while the list is empty. */
	fun tick(client: Minecraft) {
		// Voidgloom only spawns in The End.
		if (!module.enabled || carries.isEmpty() || !SkyblockLocation.onSkyblock || SkyblockLocation.area != VOIDGLOOM_ISLAND) {
			if (tracked.isNotEmpty()) tracked.clear()
			if (killed.isNotEmpty()) killed.clear()
			boxes = emptyList()
			return
		}

		// A boss gone without a death animation left the world (failed quest, out of range).
		val level = client.level
		if (level != null) {
			tracked.keys.removeIf { level.getEntity(it) == null }
			// A dead boss lingers for its animation; forget it only once the world removes it, or
			// the next scan counts it as a new spawn.
			killed.removeIf { level.getEntity(it) == null }
		}

		val assignDown = client.gui.screen() == null && assignKey.isDown(client.window)
		if (assignDown && !assignKeyDown) assignLookedAt(client)
		assignKeyDown = assignDown

		if (ticksUntilScan-- <= 0) {
			ticksUntilScan = SCAN_INTERVAL_TICKS
			scan(client)
		}

		boxes = if (highlightBoss.value || tracer.value) {
			tracked.keys.mapNotNull { level?.getEntity(it) }
		} else {
			emptyList()
		}
	}

	private var assignKeyDown = false

	/** How far off the crosshair a boss may be and still be the one looked at. */
	private const val AIM_DEGREES = 8.0
	private const val AIM_REACH = 48.0

	/**
	 * The boss under the crosshair taken off its carry, or given to the carried player nearest it
	 * (the first unfinished carry when none is in sight). Fixes a wrong guess in one press.
	 */
	private fun assignLookedAt(client: Minecraft) {
		val level = client.level ?: return
		val player = client.player ?: return
		val eye = player.eyePosition
		val look = player.lookAngle
		val seen = VoidgloomBosses.visible(level).filter { it.boss.distanceTo(player) <= AIM_REACH }.minByOrNull { entry ->
			val to = entry.boss.boundingBox.center.subtract(eye).normalize()
			Math.toDegrees(kotlin.math.acos(look.dot(to).coerceIn(-1.0, 1.0)))
		}?.takeIf { entry ->
			val to = entry.boss.boundingBox.center.subtract(eye).normalize()
			Math.toDegrees(kotlin.math.acos(look.dot(to).coerceIn(-1.0, 1.0))) <= AIM_DEGREES
		}
		if (seen == null) {
			Toasts.show("Carry Manager", "Look at a boss first", true)
			return
		}
		val boss = seen.boss
		tracked.remove(boss.id)?.let { removed ->
			VoidgloomBosses.reject(boss.id, removed.carry.name)
			Toasts.show("Carry Manager", "Took the boss off §f${removed.carry.name}", head = removed.carry.name)
			return
		}
		val open = carries.filter { !it.finished }
		val carry = open.mapNotNull { carry ->
			level.players().firstOrNull { it.gameProfile.name.equals(carry.name, ignoreCase = true) }?.let { carry to it.distanceTo(boss) }
		}.minByOrNull { it.second }?.first
			?: open.firstOrNull { it.tier == seen.tier }
			?: open.firstOrNull()
		if (carry == null) {
			Toasts.show("Carry Manager", "No carry to give it to", true)
			return
		}
		// Back-dated to when the client first saw it, as a spotted boss is.
		val since = System.currentTimeMillis() - boss.tickCount * 50L
		VoidgloomBosses.assign(boss.id, carry.name, seen.tier, since)
		tracked[boss.id] = TrackedBoss(carry, since)
		Toasts.show("Carry Manager", "Gave the boss to §f${carry.name}", head = carry.name)
	}

	private fun scan(client: Minecraft) {
		for (sighting in VoidgloomBosses.sightings(client)) {
			if (tracked.containsKey(sighting.boss.id) || sighting.boss.id in killed) continue

			val carry = carries.firstOrNull {
				!it.finished && it.name.equals(sighting.owner, ignoreCase = true) && it.tier == sighting.tier
			} ?: continue

			// Timed from when the boss was first seen, not from when its owner was decided.
			tracked[sighting.boss.id] = TrackedBoss(carry, sighting.since)
			announceSpawn(client, carry)
			if (confirmBoss.value) askToConfirm(sighting.boss.id, carry)
		}
	}

	/** Y keeps the boss; N takes it off this carry and lets the next likeliest player have it. */
	private fun askToConfirm(bossId: Int, carry: Carry) {
		val id = "carry_confirm_$bossId"
		Toasts.ask(
			source = "Carry Manager",
			id = id,
			life = CONFIRM_SECONDS,
			head = carry.name,
			footer = "§a[Y] §fYes   §c[N] §fNo",
			keys = mapOf(
				org.lwjgl.glfw.GLFW.GLFW_KEY_Y to {},
				org.lwjgl.glfw.GLFW.GLFW_KEY_N to {
					if (tracked[bossId]?.carry === carry) tracked.remove(bossId)
					VoidgloomBosses.reject(bossId, carry.name)
					// Re-read now, so the boss can go to someone else straight away.
					scan(Minecraft.getInstance())
				},
			),
		) { "Is this §d${carry.name}§7's boss?" }
	}

	private fun announceSpawn(client: Minecraft, carry: Carry) {
		if (!alertSpawn.value) return
		val text = spawnTitle.value.replace(PLAYER_TOKEN, carry.name).trim()
		if (text.isEmpty()) return

		client.gui.hud.setTimes(FADE_IN_TICKS, STAY_TICKS, FADE_OUT_TICKS)
		client.gui.hud.setTitle(Component.literal("§d$text"))
	}

	/** A boss died. The entity-event mixin is the only signal for someone else's boss. */
	@JvmStatic
	fun onEntityDied(entityId: Int) {
		if (!module.enabled) return
		val boss = tracked.remove(entityId) ?: settled(entityId) ?: return
		killed.add(entityId)
		val carry = boss.carry

		val now = System.currentTimeMillis()
		val took = now - boss.spawnedAt

		carry.done++
		carry.killTimes.add(took)
		if (carry.firstKillAt == 0L) carry.firstKillAt = now
		carry.lastKillAt = now
		CarryStore.recordKill(took)

		val client = Minecraft.getInstance()
		val counter = "${carry.done}/${carry.ordered}"
		Toasts.show("", "§d${carry.name} §e${carry.done}§7/§e${carry.ordered}", life = COUNT_TOAST_SECONDS, head = carry.name)

		if (announceProgress.value) {
			val message = progressMessage.value
				.replace(PLAYER_TOKEN, carry.name)
				.replace(COUNT_TOKEN, counter)
				.trim()
			if (message.isNotEmpty()) {
				client.connection?.sendCommand("$PARTY_CHAT_COMMAND $message")
			}
		}

		if (carry.finished) {
			// A notification rather than a title: a finished carry isn't urgent.
			Toasts.show("", "§d${carry.name}§7's carry is §adone", life = DONE_TOAST_SECONDS, head = carry.name)
			// Filed away automatically once the last boss dies; the window's tick button ends one
			// early.
			complete(carry)
			return
		}
		CarryStore.save()
	}

	/** A boss that died while its owner was still being worked out, if it was a carried player's. */
	private fun settled(entityId: Int): TrackedBoss? {
		val sighting = VoidgloomBosses.settle(entityId) ?: return null
		val carry = carries.firstOrNull { !it.finished && it.name.equals(sighting.owner, ignoreCase = true) && it.tier == sighting.tier } ?: return null
		return TrackedBoss(carry, sighting.since)
	}

	/** The outline colour for [entity] (the glow style). Asked per entity per frame, so cheap tests first. */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (!module.enabled || tracked.isEmpty()) return EntityRenderState.NO_OUTLINE
		if (!highlightBoss.value || highlightStyle.selectedIndex != STYLE_GLOW) {
			return EntityRenderState.NO_OUTLINE
		}
		if (!tracked.containsKey(entity.id)) return EntityRenderState.NO_OUTLINE
		return ARGB.opaque(outlineColor.rgb)
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || boxes.isEmpty()) return

		// A Voidgloom teleports behind you every few seconds; the line helps find it again.
		if (tracer.value) {
			for (boss in boxes) {
				val middle = boss.boundingBox.center
				WorldRender.drawTracer(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					x = middle.x,
					y = middle.y,
					z = middle.z,
					argb = tracerColor.argb,
					lineWidth = lineWidth.value.toFloat(),
					phase = true,
				)
			}
		}

		if (!highlightBoss.value || highlightStyle.selectedIndex == STYLE_GLOW) return

		val fill = highlightStyle.selectedIndex == STYLE_FILL_OUTLINE
		for (boss in boxes) {
			val box = boss.boundingBox
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = box.minX,
				minY = box.minY,
				minZ = box.minZ,
				maxX = box.maxX,
				maxY = box.maxY,
				maxZ = box.maxZ,
				outlineArgb = outlineColor.argb,
				fillArgb = fillColor.argb,
				outline = true,
				fill = fill,
				phase = phase.value,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}

	fun currentBossMillis(carry: Carry): Long {
		val boss = tracked.values.firstOrNull { it.carry === carry } ?: return 0L
		return System.currentTimeMillis() - boss.spawnedAt
	}
}
