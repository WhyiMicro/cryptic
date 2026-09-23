package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.carry.Carry
import imicro.cryptic.carry.CarryStore
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.CarryScreen
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
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
 * Keeps track of the slayer carries you are running for other people.
 *
 * Carrying is a job with bookkeeping: somebody pays for eight Tier 4s and you
 * have to know how many of those are done, whose boss is which in a lobby with
 * four other people's bosses in it, and when to stop. Doing that by memory is
 * how people end up giving away a free boss or arguing about a count.
 *
 * The idea and the shape are Athen's `SlayerCarryTracker` (BSD 3-Clause,
 * Copyright (c) 2025-2026 Starred), licence in `licenses/Athen-LICENSE.txt`.
 * What is different: the list lives in a window of its own rather than in chat
 * commands, and nothing is inferred from payments — a carry is added because
 * you said so.
 *
 * Kills are counted from the boss's own death animation rather than from chat,
 * because Hypixel says nothing in chat when somebody else's boss dies.
 */
object CarryManager {
	/** Indices into [highlightStyle]. */
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL_OUTLINE = 1
	private const val STYLE_GLOW = 2

	/** How often the world is searched for bosses, while a carry is running. */
	private const val SCAN_INTERVAL_TICKS = 10

	/** The same token every other module uses for somebody's name. */
	private const val PLAYER_TOKEN = "<player>"

	/** Stands in for the running count, like `3/20`. */
	private const val COUNT_TOKEN = "<count>"

	/** Party chat is Hypixel's own command, not a Minecraft one. */
	private const val PARTY_CHAT_COMMAND = "pc"

	/** How long the "carry is done" notification stays up, being the one to read. */
	private const val DONE_TOAST_SECONDS = 10.0

	private const val FADE_IN_TICKS = 0
	private const val STAY_TICKS = 25
	private const val FADE_OUT_TICKS = 10

	private val manager = ButtonModuleSetting("manager", "Open manager", action = { CarryScreen.request() })

	private val voidgloomSection = SectionModuleSetting("voidgloom_section", "Voidgloom Seraph")

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

	/** The module card's bind button, pointed at the real key mapping. */
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
		description = "Tracks the slayer carries you run",
		category = ModuleCategory.MISC,
		hasDemoSettings = false,
		supportsKeybind = true,
		keybind = openKeybind,
		settings = listOf(manager, voidgloomSection, highlightBoss, highlightStyle) +
			listOf(outlineColor, fillColor, lineWidth, phase, tracer, tracerColor) +
			listOf(alertSpawn, spawnTitle, announceProgress, progressMessage, reset),
	)

	/** Boss entity id to the carry it belongs to, and when it was first seen. */
	private val tracked = HashMap<Int, TrackedBoss>()

	private class TrackedBoss(val carry: Carry, val spawnedAt: Long)

	/**
	 * Bosses that have already died, kept until the world removes them.
	 *
	 * A death animation leaves the entity and its stands in place for a second,
	 * which the scan would otherwise read as a boss spawning.
	 */
	private val killed = HashSet<Int>()

	/** Bosses drawn this tick, so the render pass does no scanning of its own. */
	private var boxes: List<Entity> = emptyList()

	private var ticksUntilScan = 0

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	/** The carries being run, which the window edits in place. */
	val carries: MutableList<Carry> get() = CarryStore.active

	val activeCarry: Carry? get() = carries.firstOrNull { !it.finished }

	fun add(name: String, tier: Int, count: Int): Boolean {
		val trimmed = name.trim()
		if (trimmed.isEmpty() || count <= 0) return false
		// One carry per name: a second order from the same person is more of the
		// same job, not a separate row to keep straight.
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

	/** Every carry for [name], whatever tier, for the remove command. */
	fun removeByName(name: String): Int {
		val matches = carries.filter { it.name.equals(name.trim(), ignoreCase = true) }
		matches.forEach { remove(it) }
		return matches.size
	}

	/** The names on the list, for the remove command's suggestions. */
	fun trackedNames(): List<String> = carries.map { it.name }

	/**
	 * The list as lines of chat, for `/cryptic carry list`.
	 *
	 * The window says the same thing better, but a list you can read without
	 * leaving the fight is worth having — and it is the answer to "how many left"
	 * while a boss is on you.
	 */
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

	/**
	 * The finished carries as lines of chat, for `/cryptic carry history`.
	 *
	 * Newest first, which is the order anybody asking has in mind.
	 */
	fun describeHistory(limit: Int): List<String> {
		val history = CarryStore.history
		if (history.isEmpty()) return listOf("§7No finished carries yet.")

		return listOf("§dFinished carries:") + history.asReversed().take(limit).map { entry ->
			val took = if (entry.durationMillis > 0) " §7in §f${readableDuration(entry.durationMillis)}" else ""
			" §8- §b${entry.name} §8[§7T${entry.tier}§8] §f${entry.count}§7/§f${entry.orderedOrCount} " +
				"§8${entry.bossType}$took"
		}
	}

	/** Milliseconds as `3m 35s`, for the chat lines. */
	private fun readableDuration(millis: Long): String {
		val seconds = millis / 1000
		if (seconds < 60) return "${seconds}s"
		return "${seconds / 60}m ${seconds % 60}s"
	}

	/**
	 * Watches for bosses belonging to anybody on the list.
	 *
	 * Nothing runs while the list is empty, which is nearly always — the scan
	 * is the only per-tick cost this module has and it is gated on having a job
	 * to do.
	 */
	fun tick(client: Minecraft) {
		if (!module.enabled || carries.isEmpty() || !SkyblockLocation.onSkyblock) {
			if (tracked.isNotEmpty()) tracked.clear()
			if (killed.isNotEmpty()) killed.clear()
			boxes = emptyList()
			return
		}

		// A boss whose entity has gone without a death animation left the world
		// rather than died — a failed quest, or simply walking out of range.
		val level = client.level
		if (level != null) {
			tracked.keys.removeIf { level.getEntity(it) == null }
			// A dead boss stands around for its death animation, stands and all,
			// so it is only forgotten once the world has really taken it away.
			// Without this the next scan finds it again, reads it as a fresh
			// spawn and announces the boss you have just killed.
			killed.removeIf { level.getEntity(it) == null }
		}

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

	private fun scan(client: Minecraft) {
		for (sighting in VoidgloomBosses.sightings(client)) {
			if (tracked.containsKey(sighting.boss.id) || sighting.boss.id in killed) continue

			val carry = carries.firstOrNull {
				!it.finished && it.name.equals(sighting.owner, ignoreCase = true) && it.tier == sighting.tier
			} ?: continue

			tracked[sighting.boss.id] = TrackedBoss(carry, System.currentTimeMillis())
			announceSpawn(client, carry)
		}
	}

	private fun announceSpawn(client: Minecraft, carry: Carry) {
		if (!alertSpawn.value) return
		val text = spawnTitle.value.replace(PLAYER_TOKEN, carry.name).trim()
		if (text.isEmpty()) return

		client.gui.hud.setTimes(FADE_IN_TICKS, STAY_TICKS, FADE_OUT_TICKS)
		client.gui.hud.setTitle(Component.literal("§d$text"))
	}

	/**
	 * A boss died. Called from the entity-event mixin, which is the only place
	 * the client is told — a boss that is not yours says nothing in chat.
	 */
	@JvmStatic
	fun onEntityDied(entityId: Int) {
		if (!module.enabled) return
		val boss = tracked.remove(entityId) ?: return
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
		Toasts.show("Carry Manager", "${carry.name} $counter")

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
			// No title for this one. A title is for something you have to react
			// to inside a second, and a finished carry is the opposite — it is
			// the thing you stop for, so it goes in a notification that waits.
			Toasts.show("Carry Manager", "${carry.name}'s carry is done", false, DONE_TOAST_SECONDS)
			// Filed away on its own once the last boss is dead. The tick button
			// in the window is still there for ending one early, which is the
			// only case left that a person has to decide.
			complete(carry)
			return
		}
		CarryStore.save()
	}

	/**
	 * The colour the game's own outline pass should draw [entity] in, which is
	 * what the glow style is. Asked per entity per frame, so the cheap tests
	 * come first.
	 */
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

		// A Voidgloom teleports behind you every few seconds, and a box you
		// cannot see is no help finding it again — which is what the line is for.
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

	/** How many bosses are being watched right now, for the overview. */
	val trackedCount: Int get() = tracked.size

	/** How long the boss belonging to [carry] has been up, or zero for none. */
	fun currentBossMillis(carry: Carry): Long {
		val boss = tracked.values.firstOrNull { it.carry === carry } ?: return 0L
		return System.currentTimeMillis() - boss.spawnedAt
	}
}
