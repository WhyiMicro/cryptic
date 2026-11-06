package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.mixin.PlayerTabOverlayAccessor
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.ServerStats
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.max

/**
 * Mining helpers: the pickaxe ability's cooldown on the HUD, the blocks a Pickobulus would take,
 * and Ping Offset Miner's timing, which shows when a block will count as broken on the server so
 * the next can be started without waiting for the break to come back. The timing model and block
 * hardness are Ping Offset Miner's (CC0); the Pickobulus area is how Skyblocker describes it.
 */
object Mining {
	private val abilitySection = SectionModuleSetting("ability_section", "Pickaxe ability")

	@JvmField
	val showOutside = ToggleModuleSetting("show_outside", "Show outside mining islands", false, "Also shows the timer away from the mines while you hold a mining tool.")

	@JvmField
	val abilityNameColor = ColorModuleSetting("ability_name_color", "Name", defaultRgb = 0xFFAA00)

	@JvmField
	val abilityCooldownColor = ColorModuleSetting("ability_cooldown_color", "Cooling down", defaultRgb = 0xFF5555)

	@JvmField
	val abilityReadyColor = ColorModuleSetting("ability_ready_color", "Ready", defaultRgb = 0x55FF55)

	@JvmField
	val abilityShadow = ToggleModuleSetting("ability_shadow", "Text shadow", true)

	@JvmField
	val abilityTimer = ToggleModuleSetting(
		"ability_timer", "Cooldown timer", true,
		"A HUD timer for your pickaxe ability on mining islands, from the tab list when it shows one, otherwise from when you used it.",
	).withMore(showOutside, abilityNameColor, abilityCooldownColor, abilityReadyColor, abilityShadow)

	@JvmField
	val alertTitle = TextModuleSetting(
		id = "alert_title",
		label = "Title",
		defaultValue = "&6{ability}!",
		maxLength = 64,
		hint = "&6{ability}!",
		description = "{ability} is the ability's name. & codes colour it. Empty shows no title.",
	)

	@JvmField
	val alertChat = ToggleModuleSetting("alert_chat", "Say it in chat", false, "Also writes it in chat.")

	@JvmField
	val alertSound = DropdownModuleSetting(
		id = "alert_sound",
		label = "Sound",
		options = listOf("None") + SoundChoices.labels,
		defaultIndex = 1,
	)

	private val alertCustomSound = SoundChoices.customField("alert_custom_sound", alertSound)

	@JvmField
	val alertVolume = SliderModuleSetting(id = "alert_volume", label = "Volume", defaultValue = 1.0, min = 0.0, max = 1.0, step = 0.1)

	@JvmField
	val readyAlert = ToggleModuleSetting("ready_alert", "Alert when ready", true, "Once the ability can be used again, on a mining island.")
		.withMore(alertTitle, alertChat, alertSound, alertCustomSound, alertVolume)

	private val pickobulusSection = SectionModuleSetting("pickobulus_section", "Pickobulus")

	@JvmField
	val pickobulusColor = ColorModuleSetting("pickobulus_color", "Color", defaultRgb = 0x55CCFF)

	@JvmField
	val pickobulus = ToggleModuleSetting(
		"pickobulus", "Pickobulus overlay", true,
		"While holding a Pickobulus tool that's ready, outlines the blocks it would mine where you're looking, and counts them on the HUD.",
	).withMore(pickobulusColor)

	private val offsetSection = SectionModuleSetting("offset_section", "Ping offset")

	@JvmField
	val manualSpeed = SliderModuleSetting(
		id = "manual_speed",
		label = "Mining speed",
		defaultValue = 0.0,
		min = 0.0,
		max = 15000.0,
		step = 50.0,
		description = "0 reads it from your tab list's Stats widget. Set it here if your tab list doesn't show Mining Speed.",
	)

	@JvmField
	val margin = SliderModuleSetting(
		id = "margin",
		label = "Extra ticks",
		defaultValue = 0.0,
		min = -5.0,
		max = 10.0,
		step = 1.0,
		description = "Added to the estimate. Raise it if blocks come back after you move on; lower it to move on sooner.",
	)

	@JvmField
	val miningColor = ColorModuleSetting("mining_color", "Mining", defaultRgb = 0xFF5555)

	@JvmField
	val doneColor = ColorModuleSetting("done_color", "Safe to move on", defaultRgb = 0x55FF55)

	@JvmField
	val offsetStyle = DropdownModuleSetting(
		id = "offset_style",
		label = "Style",
		options = listOf("Outline", "Fill", "Both"),
		defaultIndex = 2,
	)

	@JvmField
	val offsetFillOpacity = SliderModuleSetting(
		id = "offset_fill_opacity", label = "Fill opacity", defaultValue = 0.2, min = 0.05, max = 1.0, step = 0.05,
		visibleIf = { offsetStyle.selectedIndex != 0 },
	)

	@JvmField
	val offsetSound = DropdownModuleSetting(
		id = "offset_sound",
		label = "Sound",
		options = listOf("None") + SoundChoices.labels,
		defaultIndex = 0,
		description = "Played when the block counts as broken.",
	)

	private val offsetCustomSound = SoundChoices.customField("offset_custom_sound", offsetSound)

	@JvmField
	val offsetVolume = SliderModuleSetting(
		id = "offset_volume", label = "Volume", defaultValue = 1.0, min = 0.0, max = 1.0, step = 0.1,
		visibleIf = { offsetSound.selectedIndex > 0 },
	)

	@JvmField
	val pingOffset = ToggleModuleSetting(
		"ping_offset", "Ping offset", false,
		"Outlines the block you're mining, red until the server will count it broken (allowing for your ping), then green: " +
			"start the next block then. Uses the Mining Speed in your tab list.",
	).withMore(manualSpeed, margin, offsetStyle, offsetFillOpacity, miningColor, doneColor, offsetSound, offsetCustomSound, offsetVolume)

	@JvmField
	val hideAnimation = ToggleModuleSetting(
		"hide_animation", "Hide break animation", true,
		"Hides the cracks (and any texture pack's mining overlay) on the block ping offset is timing.",
		visibleIf = { pingOffset.value },
	)

	@JvmField
	val hideOutline = ToggleModuleSetting(
		"hide_outline", "Hide block outline", true,
		"Hides the block outline, and Block Overlay's, on the block ping offset is timing.",
		visibleIf = { pingOffset.value },
	)

	@JvmField
	val breakResetFix = ToggleModuleSetting(
		"break_reset_fix", "Break reset fix", true,
		"Keeps a block breaking when Hypixel updates your drill or pickaxe mid-break (its fuel or lore), which otherwise starts the block again. From NoFrills.",
	)

	/** Whether [held] is the tool a break started with, changed only by Hypixel rewriting its data. */
	@JvmStatic
	fun sameTool(held: ItemStack, started: ItemStack): Boolean {
		if (!module.enabled || !breakResetFix.value || held.isEmpty || started.isEmpty || held.item != started.item) return false
		fun tag(stack: ItemStack) = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
		val a = tag(held)
		val b = tag(started)
		val uuid = a.getString("uuid").orElse("")
		if (uuid.isNotEmpty()) return uuid == b.getString("uuid").orElse("")
		val id = a.getString("id").orElse("")
		return id.isNotEmpty() && id == b.getString("id").orElse("")
	}

	@JvmField
	val tickCounter = ToggleModuleSetting("tick_counter", "Tick counter", false, "A HUD counter of ticks mined against ticks needed.")

	private val helpersSection = SectionModuleSetting("helpers_section", "Helpers")

	@JvmField
	val precisionColor = ColorModuleSetting("precision_color", "Not aimed at", defaultRgb = 0xFF5555)

	@JvmField
	val precisionHoverColor = ColorModuleSetting("precision_hover_color", "Aimed at", defaultRgb = 0x55FF55)

	@JvmField
	val precisionTracer = ToggleModuleSetting("precision_tracer", "Tracer", false, "A line from your crosshair to the target.")

	@JvmField
	val precisionTracerColor = ColorModuleSetting(
		"precision_tracer_color", "Tracer colour", defaultRgb = 0xFFFFFF, supportsAlpha = true,
		visibleIf = { precisionTracer.value },
	)

	@JvmField
	val precisionInstant = ToggleModuleSetting(
		"precision_instant", "Instant colour", true,
		"Turns green the moment your crosshair is on the target. Off waits for Hypixel's sparks to change, which lags by your ping.",
	)

	@JvmField
	val precisionParticles = ToggleModuleSetting(
		"precision_particles", "Show particles", false,
		"Keeps Hypixel's sparks on the target as well as the box.",
	)

	@JvmField
	val precisionMining = ToggleModuleSetting(
		"precision_mining", "Precision mining highlight", false,
		"Boxes the Precision Mining target on the block you're mining: red until you aim at it, then green.",
	).withMore(precisionColor, precisionHoverColor, precisionInstant, precisionTracer, precisionTracerColor, precisionParticles)

	@JvmField
	val drillFuelBar = ToggleModuleSetting(
		"drill_fuel_bar", "Drill fuel bar", false,
		"Shows a drill's remaining fuel as a durability bar on the item.",
	)

	@JvmField
	val heatmapRadius = SliderModuleSetting("heatmap_radius", "Radius", defaultValue = 8.0, min = 3.0, max = 16.0, step = 1.0)

	@JvmField
	val heatmapStyle = DropdownModuleSetting("heatmap_style", "Style", options = listOf("Outline", "Fill", "Both"), defaultIndex = 0)

	@JvmField
	val heatmapOpacity = SliderModuleSetting("heatmap_opacity", "Opacity", defaultValue = 1.0, min = 0.1, max = 1.0, step = 0.1)

	@JvmField
	val heatmapHotColor = ColorModuleSetting(
		"heatmap_hot_color", "Clay, red sandstone", defaultRgb = 0x00AA00,
		description = "Tungsten's clay and Umber's smooth red sandstone.",
	)

	@JvmField
	val heatmapWarmColor = ColorModuleSetting(
		"heatmap_warm_color", "Brown terracotta", defaultRgb = 0x55FF55,
		description = "Umber's brown terracotta.",
	)

	@JvmField
	val heatmap = ToggleModuleSetting(
		"heatmap", "Heat map", false,
		"In the Glacite Tunnels and Mineshafts, outlines the exposed Tungsten and Umber blocks around you, darker for the better ones. " +
			"Coleweight's idea.",
	).withMore(heatmapRadius, heatmapStyle, heatmapOpacity, heatmapHotColor, heatmapWarmColor)

	@JvmField
	val paneHitbox = ToggleModuleSetting(
		"pane_hitbox", "Full glass pane hitbox", false,
		"Gives glass panes (gemstone panes) a full block hitbox on mining islands, so they're as easy to aim at as blocks.",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
	)

	/** Whether a glass pane's aiming shape becomes a full block. Called for every pane shape lookup. */
	@JvmStatic
	fun growsPane(state: BlockState): Boolean {
		if (!module.enabled || !paneHitbox.value) return false
		val block = state.block
		if (block !is net.minecraft.world.level.block.StainedGlassPaneBlock && block !== net.minecraft.world.level.block.Blocks.GLASS_PANE) return false
		return onMiningIsland()
	}

	private val soundSection = SectionModuleSetting("sound_section", "Sounds")

	@JvmField
	val muteXp = ToggleModuleSetting(
		"mute_xp",
		"Mute experience sound",
		true,
		"Silences the experience orb chime from mining, on mining islands or while you hold a mining tool.",
	)

	/** The experience orb pickup, which plays for nearly every block mined. */
	@JvmStatic
	fun mutes(instance: net.minecraft.client.resources.sounds.SoundInstance): Boolean {
		if (!module.enabled || !muteXp.value || !SkyblockLocation.onSkyblock) return false
		if (instance.identifier != net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP.location()) return false
		return onMiningIsland() || heldTool() != null
	}

	@JvmField
	val module = Module(
		id = "mining",
		name = "Mining",
		description = "QoL mining features",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			abilitySection, abilityTimer, readyAlert,
			pickobulusSection, pickobulus,
			offsetSection, pingOffset, hideAnimation, hideOutline, tickCounter, breakResetFix,
			helpersSection, precisionMining, heatmap, drillFuelBar, paneHitbox,
			soundSection, muteXp,
		),
	)

	private val client: Minecraft get() = Minecraft.getInstance()
	private val FORMATTING = Regex("§.")

	private val MINING_ISLANDS = setOf("Dwarven Mines", "Crystal Hollows", "Mineshaft", "Glacite Mineshafts", "Gold Mine", "Deep Caverns")

	fun onMiningIsland(): Boolean = DebugOverrides.sampleHudValues || (SkyblockLocation.onSkyblock && SkyblockLocation.area in MINING_ISLANDS)

	val needsSkyblock: Boolean get() = module.enabled

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(AbilityElement())
		Hud.register(TickElement())
		Hud.register(PickobulusElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		// False cancels the vanilla outline.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { _, outline -> !hidesOutline(outline.pos()) }
	}

	// ---- The held tool -------------------------------------------------------------------

	private class Tool(val ability: String?, val cooldownSeconds: Int?, val boostPercent: Int?, val boostSeconds: Int?)

	private var toolStack: ItemStack? = null
	private var tool: Tool? = null

	private fun lore(stack: ItemStack): List<String> =
		stack.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "").trim() } ?: emptyList()

	/** A drill, pickaxe or gauntlet, by the rarity line closing its lore. */
	private fun readTool(stack: ItemStack): Tool? {
		if (stack.isEmpty) return null
		val lines = lore(stack).filter { it.isNotEmpty() }
		val last = lines.lastOrNull() ?: return null
		if (" DRILL" !in last && " PICKAXE" !in last && " GAUNTLET" !in last) return null
		val at = lines.indexOfFirst { it.startsWith("Ability: ") }
		val ability = if (at < 0) null else lines[at].removePrefix("Ability: ").replace("RIGHT CLICK", "").trim()
		val after = if (at < 0) emptyList() else lines.drop(at + 1)
		val cooldown = after.firstNotNullOfOrNull { Regex("""^Cooldown: (\d+)s""").find(it)?.groupValues?.get(1)?.toInt() }
		val boost = after.firstNotNullOfOrNull { Regex("""\+(\d+)%""").find(it)?.groupValues?.get(1)?.toInt() }
		val boostFor = after.firstNotNullOfOrNull { Regex("""for (\d+)s""").find(it)?.groupValues?.get(1)?.toInt() }
		return Tool(ability, cooldown, boost, boostFor)
	}

	private fun heldTool(): Tool? {
		val held = client.player?.mainHandItem ?: return null
		if (held !== toolStack) {
			toolStack = held
			tool = readTool(held)
		}
		return tool
	}

	// ---- The tab list ------------------------------------------------------------------

	private var tabSpeed = 0
	private var tabAbility: String? = null

	/** Seconds left as the tab list last said; 0 for available, null when it doesn't show. */
	private var tabSecondsLeft: Int? = null
	private var tabTicks = 0

	private val speedPattern = Regex("""Mining Speed: \D*([\d,]+)""")
	private val abilityRow = Regex("""^([A-Za-z' ]+): (Available|(\d+)s)$""")

	private fun readTab() {
		val connection = client.connection ?: return
		val rows = connection.listedOnlinePlayers
			.sortedWith(PlayerTabOverlayAccessor.`cryptic$ordering`())
			.mapNotNull { it.tabListDisplayName?.string?.replace(FORMATTING, "")?.trim() }
		var speed = 0
		var ability: String? = null
		var left: Int? = null
		for ((index, row) in rows.withIndex()) {
			speedPattern.find(row)?.let { speed = it.groupValues[1].replace(",", "").toIntOrNull() ?: speed }
			if (row == "Pickaxe Ability:") {
				abilityRow.find(rows.getOrNull(index + 1) ?: continue)?.let { match ->
					ability = match.groupValues[1].trim()
					left = match.groupValues[3].toIntOrNull() ?: 0
				}
			}
		}
		tabSpeed = speed
		tabAbility = ability
		tabSecondsLeft = left
	}

	// ---- The ability -------------------------------------------------------------------

	private var abilityName: String? = null
	private var readyAt = 0L
	private var waitingForReady = false
	private var boostUntil = 0L
	private var boostMultiplier = 1f

	private val usedPattern = Regex("""^You used your (.+) Pickaxe Ability!$""")
	private val availablePattern = Regex("""^(.+) is now available!$""")

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val line = message.string.replace(FORMATTING, "").trim()
		usedPattern.find(line)?.let { match ->
			val name = match.groupValues[1]
			abilityName = name
			val tool = heldTool()
			tool?.cooldownSeconds?.let { readyAt = System.currentTimeMillis() + it * 1000L }
			waitingForReady = true
			if (name == "Mining Speed Boost") {
				boostMultiplier = 1f + (tool?.boostPercent ?: 0) / 100f
				boostUntil = System.currentTimeMillis() + (tool?.boostSeconds ?: 0) * 1000L
			}
			return
		}
		availablePattern.find(line)?.let { match ->
			if (abilityName == null || match.groupValues[1] == abilityName) {
				abilityName = match.groupValues[1]
				readyAt = 0L
			}
		}
	}

	private fun secondsLeft(): Long = ((readyAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)

	fun tick(client: Minecraft) {
		if (!module.enabled) return
		if (--tabTicks <= 0) {
			tabTicks = 10
			readTab()
			// The tab list is the server's own count; trusted over the one kept here.
			tabAbility?.let { abilityName = it }
			tabSecondsLeft?.let { left ->
				val counted = secondsLeft()
				if (left == 0) readyAt = 0L
				else if (kotlin.math.abs(left - counted) > 1) {
					readyAt = System.currentTimeMillis() + left * 1000L
					waitingForReady = true
				}
			}
		}
		if (abilityName == null) heldTool()?.ability?.let { abilityName = it }
		if (waitingForReady && System.currentTimeMillis() >= readyAt) {
			waitingForReady = false
			if (readyAlert.value && onMiningIsland()) alertReady(client)
		}
		updatePickobulus()
		updateBreak(client)
		updateHeatmap(client)
		if (precisionSeen > 0) precisionSeen--
		warnIfNoSpeed(client)
	}

	private var speedCheckedLevel: Any? = null
	private var speedCheckTicks = 0

	/**
	 * Each time a mining island is joined, a few seconds in: ping offset can't time anything without
	 * a Mining Speed, so say where it comes from.
	 */
	private fun warnIfNoSpeed(client: Minecraft) {
		val level = client.level ?: return
		if (level !== speedCheckedLevel) {
			speedCheckedLevel = level
			speedCheckTicks = 0
			warnedNoSpeed = false
		}
		if (warnedNoSpeed || !pingOffset.value || !onMiningIsland() || ++speedCheckTicks < 100) return
		warnedNoSpeed = true
		if (tabSpeed > 0 || manualSpeed.value > 0) return
		val message = "Ping offset can't read your Mining Speed. Add it in /widget > Stats Widget > Mining Speed, or set it in Mining's settings."
		Toasts.show("Mining", message, life = 10.0, color = Toasts.Colors.MINING)
	}

	private fun timing(pos: BlockPos): Boolean = module.enabled && pingOffset.value && breakNeeded > 0 && breakPos == pos

	@JvmStatic
	fun hidesOutline(pos: BlockPos): Boolean = hideOutline.value && timing(pos)

	@JvmStatic
	fun hidesBreakAnimation(pos: BlockPos): Boolean = hideAnimation.value && timing(pos)

	private fun alertReady(client: Minecraft) {
		val name = abilityName ?: "Pickaxe Ability"
		val title = DungeonScore.colored(alertTitle.value.replace("{ability}", name), "§6")
		if (alertTitle.value.isNotBlank()) {
			client.gui.hud.setTimes(0, 40, 10)
			client.gui.hud.setTitle(Component.literal(title))
			client.gui.hud.setSubtitle(Component.empty())
		}
		if (alertChat.value) client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$name is ready."))
		if (alertSound.selectedIndex > 0) SoundChoices.play(alertSound.selectedIndex - 1, alertVolume.value.toFloat(), 1f, alertCustomSound.value)
	}

	// ---- Ping offset ------------------------------------------------------------------

	/** Hardness of the blocks worth timing, by block id. From Ping Offset Miner's table. */
	private val HARDNESS: Map<String, Int> = buildMap {
		listOf("coal_block", "gold_block", "diamond_block", "emerald_block", "iron_block", "lapis_block", "redstone_block").forEach { put(it, 600) }
		put("obsidian", 500)
		// Hard stone, the bulk of the Crystal Hollows and the tunnels.
		put("stone", 50)
		listOf("gray_wool", "cyan_terracotta").forEach { put(it, 500) }
		listOf("prismarine", "prismarine_bricks", "dark_prismarine").forEach { put(it, 800) }
		put("light_blue_wool", 1500)
		put("polished_diorite", 2000)
		listOf("clay", "infested_cobblestone", "brown_terracotta", "terracotta", "smooth_red_sandstone").forEach { put(it, 5600) }
		put("packed_ice", 6000)
		val gems = mapOf(
			"red" to 2300, "orange" to 3000, "light_blue" to 3000, "lime" to 3000, "purple" to 3000, "white" to 3000,
			"yellow" to 3800, "magenta" to 4800, "black" to 5200, "blue" to 5200, "brown" to 5200, "green" to 5200,
		)
		for ((colour, hardness) in gems) {
			put("${colour}_stained_glass", hardness)
			put("${colour}_stained_glass_pane", hardness)
		}
	}

	private fun blockId(state: BlockState): String = BuiltInRegistries.BLOCK.getKey(state.block).path

	private var breakPos: BlockPos? = null
	/**
	 * Something ping offset can time: a pickaxe, or SkyBlock's drills, pickaxes and gauntlets (by
	 * their rarity line, or the Breaking Power every mining tool lists). Not a hoe or an axe.
	 */
	private fun isMiningTool(stack: ItemStack): Boolean {
		if (stack.isEmpty) return false
		if (stack.`is`(net.minecraft.tags.ItemTags.PICKAXES)) return true
		if (readTool(stack) != null) return true
		return lore(stack).any { it.startsWith("Breaking Power") }
	}

	private var breakStartTick = 0
	private var breakNeeded = 0
	private var breakElapsed = 0
	private var breakAnnounced = false

	/** The game starting to mine a block. */
	@JvmStatic
	fun onStartBreak(pos: BlockPos) {
		if (!module.enabled || !pingOffset.value && !tickCounter.value) return
		val player = client.player ?: return
		breakPos = pos.immutable()
		breakStartTick = player.tickCount
		breakAnnounced = false
	}

	private fun speed(): Int {
		val base = if (manualSpeed.value > 0) manualSpeed.value.toInt() else tabSpeed
		val boosted = System.currentTimeMillis() < boostUntil
		return (base * (if (boosted) boostMultiplier else 1f)).toInt()
	}

	private var warnedNoSpeed = false

	/** Ticks until the server counts a block broken, less half the round trip so you can move on as the packet leaves. */
	private fun ticksNeeded(hardness: Int): Int {
		val speed = speed()
		if (speed <= 0 || speed >= hardness * 30) return 0
		val raw = max(4, hardness * 30 / speed)
		val ping = ServerStats.ping.coerceAtLeast(0) / 1000.0
		val offset = raw - ping * ServerStats.tps / 2.0 + margin.value
		return max(4, offset.toInt())
	}

	private fun updateBreak(client: Minecraft) {
		val pos = breakPos ?: return
		val level = client.level ?: return
		val player = client.player ?: return
		val gameMode = client.gameMode
		val hardness = HARDNESS[blockId(level.getBlockState(pos))]
		if (gameMode == null || !gameMode.isDestroying || hardness == null || !onMiningIsland() || !isMiningTool(player.mainHandItem)) {
			breakPos = null
			return
		}
		breakNeeded = ticksNeeded(hardness)
		breakElapsed = player.tickCount - breakStartTick
		if (breakNeeded > 0 && breakElapsed >= breakNeeded && !breakAnnounced) {
			breakAnnounced = true
			if (pingOffset.value && offsetSound.selectedIndex > 0) {
				SoundChoices.play(offsetSound.selectedIndex - 1, offsetVolume.value.toFloat(), 1f, offsetCustomSound.value)
			}
		}
	}

	// ---- Pickobulus -------------------------------------------------------------------

	private val pickobulusBlocks = ArrayList<BlockPos>()
	private var pickobulusGems = 0

	/** What a Pickobulus can break where you are, by block id. Null breaks anything but bedrock. */
	private fun breakableHere(): ((String) -> Boolean)? {
		val zone = SkyblockLocation.zone()
		return when {
			SkyblockLocation.inCrystalHollows -> null
			SkyblockLocation.area == "Mineshaft" || SkyblockLocation.area == "Glacite Mineshafts" -> null
			zone == "Glacite Tunnels" || zone == "Great Glacite Lake" -> { id -> id in GLACITE_BREAKABLE || id.contains("stained_glass") }
			else -> { id -> id in DWARVEN_BREAKABLE || id.contains("stained_glass") || id.endsWith("_ore") }
		}
	}

	private val DWARVEN_BREAKABLE = setOf(
		"stone", "cobblestone", "polished_diorite", "prismarine", "prismarine_bricks", "dark_prismarine",
		"cyan_terracotta", "light_blue_wool", "gray_wool", "lapis_block", "gold_block", "iron_block", "diamond_block",
		"emerald_block", "redstone_block", "coal_block", "quartz_block", "netherrack", "glowstone", "obsidian", "end_stone",
	)

	private val GLACITE_BREAKABLE = setOf(
		"packed_ice", "polished_diorite", "infested_stone", "light_gray_carpet", "prismarine", "prismarine_bricks",
		"dark_prismarine", "light_blue_wool", "gray_wool", "cyan_terracotta", "brown_terracotta", "terracotta",
		"smooth_red_sandstone", "infested_cobblestone", "clay",
	)

	private fun pickobulusReady(): Boolean {
		val tool = heldTool() ?: return false
		// Tools that don't name an ability use the one picked in the Heart of the Mountain.
		val pickobulusTool = tool.ability?.startsWith("Pickobulus") ?: (abilityName == "Pickobulus")
		return pickobulusTool && (readyAt == 0L || System.currentTimeMillis() >= readyAt)
	}

	private fun updatePickobulus() {
		pickobulusBlocks.clear()
		pickobulusGems = 0
		if (!pickobulus.value || !onMiningIsland() || !pickobulusReady()) return
		val player = client.player ?: return
		val level = client.level ?: return
		// Thrown from a little above the eyes.
		val start = player.getEyePosition(1f).add(0.0, 0.53625, 0.0)
		val end = start.add(player.getViewVector(1f).scale(20.0))
		val hit = level.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
		if (hit.type != HitResult.Type.BLOCK) return
		val centre = hit.blockPos
		val breakable = breakableHere()
		// A snapshot with a block's margin, so exposure can be checked at the edges and blocks the
		// blast clears expose the ones behind them, in the order the server goes.
		val air = Array(8) { Array(8) { BooleanArray(8) } }
		val ids = Array(8) { Array(8) { arrayOfNulls<String>(8) } }
		for (x in 0 until 8) for (y in 0 until 8) for (z in 0 until 8) {
			val state = level.getBlockState(centre.offset(x - 4, y - 4, z - 4))
			air[x][y][z] = state.isAir
			ids[x][y][z] = blockId(state)
		}
		for (x in 1 until 7) for (y in 1 until 7) for (z in 1 until 7) {
			if (air[x][y][z]) continue
			val id = ids[x][y][z] ?: continue
			if (id == "bedrock" || id == "barrier") continue
			val exposed = air[x - 1][y][z] || air[x + 1][y][z] || air[x][y - 1][z] || air[x][y + 1][z] || air[x][y][z - 1] || air[x][y][z + 1]
			if (!exposed) continue
			if (breakable != null && !breakable(id)) continue
			pickobulusBlocks += centre.offset(x - 4, y - 4, z - 4)
			if (id.contains("stained_glass")) pickobulusGems++
			// Where it breaks to air (not bedrock), the block behind is exposed next.
			if (breakable == null || id == "packed_ice" || id.contains("stained_glass")) air[x][y][z] = true
		}
	}

	// ---- Drawing -----------------------------------------------------------------------

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		renderPrecision(context)
		renderHeatmap(context)
		val colour = pickobulusColor.argb or (0xFF shl 24)
		for (pos in pickobulusBlocks) {
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = pos.x.toDouble(), minY = pos.y.toDouble(), minZ = pos.z.toDouble(),
				maxX = pos.x + 1.0, maxY = pos.y + 1.0, maxZ = pos.z + 1.0,
				outlineArgb = colour,
				fillArgb = 0,
				outline = true,
				fill = false,
				phase = false,
				lineWidth = 2f,
			)
		}
		val pos = breakPos ?: return
		if (!pingOffset.value || breakNeeded <= 0) return
		val level = client.level ?: return
		val shape = level.getBlockState(pos).getShape(level, pos)
		val box = if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
		val done = breakElapsed >= breakNeeded
		val rgb = (if (done) doneColor.argb else miningColor.argb) and 0xFFFFFF
		val fillAlpha = (offsetFillOpacity.value * 255).toInt().coerceIn(0, 255)
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = box.minX, minY = box.minY, minZ = box.minZ,
			maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
			outlineArgb = (0xFF shl 24) or rgb,
			fillArgb = (fillAlpha shl 24) or rgb,
			outline = offsetStyle.selectedIndex != 1,
			fill = offsetStyle.selectedIndex != 0,
			phase = false,
			lineWidth = 2f,
		)
	}

	private fun renderPrecision(context: LevelRenderContext) {
		if (!precisionMining.value || precisionSeen <= 0) return
		val half = PRECISION_HALF
		val rgb = (if (aimingAtPrecision(half)) precisionHoverColor.argb else precisionColor.argb) and 0xFFFFFF
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = precisionX - half, minY = precisionY - half, minZ = precisionZ - half,
			maxX = precisionX + half, maxY = precisionY + half, maxZ = precisionZ + half,
			outlineArgb = (0xFF shl 24) or rgb,
			fillArgb = (0x80 shl 24) or rgb,
			outline = true,
			fill = true,
			phase = true,
			lineWidth = 2f,
		)
		if (precisionTracer.value) {
			WorldRender.drawTracer(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				x = precisionX, y = precisionY, z = precisionZ,
				argb = precisionTracerColor.argb,
				lineWidth = 2f,
				phase = true,
			)
		}
	}

	/**
	 * Whether the crosshair is on the target this frame. Hypixel's green sparks say the same, but a
	 * round trip and a few ticks late; they're used when Instant colour is off.
	 */
	private fun aimingAtPrecision(half: Double): Boolean {
		if (!precisionInstant.value) return precisionAimed
		val player = client.player ?: return precisionAimed
		val partial = client.deltaTracker.getGameTimeDeltaPartialTick(false)
		val eye = player.getEyePosition(partial)
		val end = eye.add(player.getViewVector(partial).scale(player.blockInteractionRange()))
		val box = AABB(precisionX - half, precisionY - half, precisionZ - half, precisionX + half, precisionY + half, precisionZ + half)
		return box.contains(eye) || box.clip(eye, end).isPresent
	}

	private fun renderHeatmap(context: LevelRenderContext) {
		if (!heatmap.value || heatBlocks.isEmpty()) return
		val alpha = (heatmapOpacity.value * 255).toInt().coerceIn(0, 255)
		val outline = heatmapStyle.selectedIndex != 1
		val fill = heatmapStyle.selectedIndex != 0
		// A fill under an outline is kept fainter so the outline still reads.
		val fillAlpha = if (outline) alpha / 3 else alpha / 2
		for (index in heatBlocks.indices) {
			val pos = heatBlocks[index]
			val rgb = (if (heatHot[index]) heatmapHotColor.argb else heatmapWarmColor.argb) and 0xFFFFFF
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = pos.x.toDouble(), minY = pos.y.toDouble(), minZ = pos.z.toDouble(),
				maxX = pos.x + 1.0, maxY = pos.y + 1.0, maxZ = pos.z + 1.0,
				outlineArgb = (alpha shl 24) or rgb,
				fillArgb = (fillAlpha shl 24) or rgb,
				outline = outline,
				fill = fill,
				phase = false,
				lineWidth = 2f,
			)
		}
	}

	// ---- Precision mining ---------------------------------------------------------------

	private const val PRECISION_HALF = 0.12
	private const val PRECISION_TICKS = 5

	private var precisionX = 0.0
	private var precisionY = 0.0
	private var precisionZ = 0.0
	private var precisionAimed = false

	/** Ticks the last target stays drawn, since Hypixel resends it every few ticks. */
	private var precisionSeen = 0

	/**
	 * Hypixel marks the Precision Mining target with crit particles, turned to happy villager ones
	 * while you aim at it; only those on the block being mined count. Returns whether the particle
	 * should be hidden. Client thread only: cancelling the network thread's pass would keep the
	 * packet from ever reaching it.
	 */
	@JvmStatic
	fun onParticle(packet: net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket): Boolean {
		if (!client.isSameThread || !module.enabled || !precisionMining.value) return false
		val type = packet.particle.type
		val aimed = type == net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER
		if (!aimed && type != net.minecraft.core.particles.ParticleTypes.CRIT) return false
		if (!client.options.keyAttack.isDown || !onMiningIsland()) return false
		val hit = client.hitResult as? BlockHitResult ?: return false
		if (hit.type != HitResult.Type.BLOCK) return false
		val pos = hit.blockPos
		val h = PRECISION_HALF
		if (packet.x + h < pos.x || packet.x - h > pos.x + 1 || packet.y + h < pos.y || packet.y - h > pos.y + 1 ||
			packet.z + h < pos.z || packet.z - h > pos.z + 1) return false
		precisionX = packet.x
		precisionY = packet.y
		precisionZ = packet.z
		precisionAimed = aimed
		precisionSeen = PRECISION_TICKS
		return !precisionParticles.value
	}

	// ---- Heat map ----------------------------------------------------------------------

	private val heatBlocks = ArrayList<BlockPos>()
	private val heatHot = ArrayList<Boolean>()
	private var heatTicks = 0

	private val HEAT_HOT = setOf("clay", "smooth_red_sandstone")
	private const val HEAT_WARM = "brown_terracotta"
	private val HEAT_OPEN = setOf("air", "cave_air", "snow", "light_gray_carpet")

	private fun inGlaciteTunnels(): Boolean {
		if (DebugOverrides.sampleHudValues) return true
		if (!SkyblockLocation.onSkyblock) return false
		if (SkyblockLocation.area == "Mineshaft" || SkyblockLocation.area == "Glacite Mineshafts") return true
		val zone = SkyblockLocation.zone()
		return zone == "Glacite Tunnels" || zone == "Great Glacite Lake"
	}

	/** Every few ticks, the exposed Tungsten and Umber blocks in a box around the player. */
	private fun updateHeatmap(client: Minecraft) {
		if (!heatmap.value || !inGlaciteTunnels()) {
			heatBlocks.clear()
			heatHot.clear()
			return
		}
		if (--heatTicks > 0) return
		heatTicks = 4
		val level = client.level ?: return
		val player = client.player ?: return
		heatBlocks.clear()
		heatHot.clear()
		val radius = heatmapRadius.value.toInt()
		val centre = player.blockPosition()
		val cursor = BlockPos.MutableBlockPos()
		val side = BlockPos.MutableBlockPos()
		for (dx in -radius..radius) for (dy in -2..radius) for (dz in -radius..radius) {
			cursor.set(centre.x + dx, centre.y + dy, centre.z + dz)
			val id = blockId(level.getBlockState(cursor))
			val hot = id in HEAT_HOT
			if (!hot && id != HEAT_WARM) continue
			var exposed = false
			for (direction in net.minecraft.core.Direction.entries) {
				side.setWithOffset(cursor, direction)
				if (blockId(level.getBlockState(side)) in HEAT_OPEN) { exposed = true; break }
			}
			if (!exposed) continue
			heatBlocks += cursor.immutable()
			heatHot += hot
		}
	}

	// ---- Drill fuel -------------------------------------------------------------------

	private val fuelPattern = Regex("""^Fuel: ([\d,.]+k?)/([\d,.]+k?)""")

	/** Fuel left from 0 to 1 by stack, or -1 for no fuel line; read once per stack. */
	private val fuelCache = java.util.WeakHashMap<ItemStack, Float>()

	private fun amount(text: String): Float? {
		val k = text.endsWith("k")
		val number = text.removeSuffix("k").replace(",", "").toFloatOrNull() ?: return null
		return if (k) number * 1000f else number
	}

	private fun fuelOf(stack: ItemStack): Float = fuelCache.getOrPut(stack) {
		val line = stack.get(DataComponents.LORE)?.lines()?.firstNotNullOfOrNull {
			fuelPattern.find(it.string.replace(FORMATTING, "").trim())
		}
		val left = line?.let { amount(it.groupValues[1]) }
		val max = line?.let { amount(it.groupValues[2]) }
		if (left == null || max == null || max <= 0f) -1f else (left / max).coerceIn(0f, 1f)
	}

	/** The fuel left as a bar fraction, or null to leave the bar to vanilla. */
	@JvmStatic
	fun fuelFraction(stack: ItemStack): Float? {
		if (!module.enabled || !drillFuelBar.value || stack.isEmpty || !SkyblockLocation.onSkyblock) return null
		if (!stack.has(DataComponents.LORE)) return null
		val fraction = fuelOf(stack)
		return if (fraction < 0f) null else fraction
	}

	private class AbilityElement : HudElement("pickaxe_ability", "Pickaxe Ability", 0.02, 0.45) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("Mining Speed Boost: 120s")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && abilityTimer.value && abilityName != null && (onMiningIsland() || (showOutside.value && heldTool() != null))

		override fun showInEditor(): Boolean = module.enabled && abilityTimer.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, abilityName ?: "Pickaxe Ability", secondsLeft())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, "Mining Speed Boost", 42L)

		private fun draw(context: GuiGraphicsExtractor, name: String, seconds: Long) {
			val label = "$name: "
			val shadow = abilityShadow.value
			context.text(font, label, 0, 0, abilityNameColor.argb or (0xFF shl 24), shadow)
			val value = if (seconds <= 0L) "Ready" else "${seconds}s"
			val colour = if (seconds <= 0L) abilityReadyColor.argb else abilityCooldownColor.argb
			context.text(font, value, font.width(label), 0, colour or (0xFF shl 24), shadow)
		}
	}

	private class TickElement : HudElement("mining_ticks", "Mining Ticks", 0.49, 0.56) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("88/88")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = module.enabled && tickCounter.value && breakPos != null && breakNeeded > 0

		override fun showInEditor(): Boolean = module.enabled && tickCounter.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, breakElapsed, breakNeeded)

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 9, 14)

		private fun draw(context: GuiGraphicsExtractor, elapsed: Int, needed: Int) {
			val text = "${elapsed.coerceAtMost(needed)}/$needed"
			val colour = if (elapsed >= needed) doneColor.argb else miningColor.argb
			context.text(font, text, (width - font.width(text)) / 2, 0, colour or (0xFF shl 24))
		}
	}

	private class PickobulusElement : HudElement("pickobulus", "Pickobulus", 0.49, 0.6) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("Pickobulus: 888 blocks, 88 gemstones")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = module.enabled && pickobulus.value && pickobulusBlocks.isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled && pickobulus.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, pickobulusBlocks.size, pickobulusGems)

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 42, 6)

		private fun draw(context: GuiGraphicsExtractor, blocks: Int, gems: Int) {
			val text = "§bPickobulus: §f$blocks blocks" + if (gems > 0) "§7, §d$gems gemstones" else ""
			context.text(font, text, (width - font.width(text)) / 2, 0, 0xFFFFFFFF.toInt())
		}
	}

	fun describe(): List<String> = listOf(
		"Mining: speed $tabSpeed (used ${speed()}), ability ${abilityName ?: "unknown"}, ${secondsLeft()}s left, tab says ${tabSecondsLeft ?: "nothing"}",
		"Breaking ${breakPos?.toShortString() ?: "nothing"}: $breakElapsed/$breakNeeded ticks, Pickobulus ${pickobulusBlocks.size} blocks",
	)
}
