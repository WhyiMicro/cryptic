package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.BoxColors
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Marks things in a dungeon worth looking at and hides the noise around them. Starred mobs are
 * Odin's `Highlight` (the mob under a starred nametag); bats are NoammAddons' `StarMobESP`; the
 * score-coloured blood portal and the mimic chest box are ideas from Devonian.
 */
object Highlight {
	/**
	 * Every dungeon health tag ends with a heart (NoammAddons' approach), which works for mobs
	 * nobody listed.
	 */
	private const val HEART = "❤"
	private const val STAR = "✯"

	/**
	 * Matched on the word alone, since Hypixel renames him for events ("Prince Nicholas" at new
	 * year); he's a named zombie villager with nothing else marking him.
	 */
	private const val PRINCE_NAME = "Prince"

	/** Fels are named Dinnerbone (which flips them); the tag is dropped even though the Fel is marked. */
	private const val FEL_NAME = "Dinnerbone"

	/** Mobs whose names stay even unstarred: mini bosses are why you're in the room. */
	private val miniBossNames = setOf(
		"Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer",
		"Shadow Assassin", "Diamond Guy", "King Midas",
	)

	private const val OUTLINE = 0
	private const val FILL = 1

	private const val GLOW = 3

	/**
	 * Glow last: the game's own outline, always through walls, so it has no phase, outline colour
	 * or width.
	 */
	private val styles = listOf("Outline", "Fill", "Fill + Outline", "Glow")

	private fun starGlows() = starStyle.selectedIndex == GLOW
	private fun specialGlows() = specialStyle.selectedIndex == GLOW

	/**
	 * Whether anything on is drawn as a line (box outline, mimic chest, tracer), which is all the
	 * width slider changes.
	 */
	private fun drawsLines(): Boolean =
		(highlightStarred.value && !starGlows() && starStyle.selectedIndex != FILL) ||
			(marksAnySpecial() && !specialGlows() && specialStyle.selectedIndex != FILL) ||
			(marksAnySpecial() && specialTracers.value) ||
			highlightMimicChest.value

	/** Whether anything in the special-mob group is on. */
	private fun marksAnySpecial(): Boolean =
		highlightBats.value || highlightMimic.value || highlightPrince.value ||
			HiddenMobs.highlightFels.value || HiddenMobs.highlightShadowAssassins.value

	private const val SCAN_RADIUS = 16
	private const val SCAN_HEIGHT = 8
	private const val BLOCK_SCAN_INTERVAL_TICKS = 20

	/** Rooms with their own trapped chest, mimic or not. */
	private val roomsWithOwnTrappedChests = setOf("Slime")

	@JvmField
	val hideNonStarNames = ToggleModuleSetting(
		id = "hide_non_star_names",
		label = "Hide non-starred nametags",
		defaultValue = true,
		description = "Drops the health tags of mobs that are not starred, which is most of a room.",
	)

	@JvmField
	val highlightStarred = ToggleModuleSetting(
		id = "highlight_starred",
		label = "Highlight starred mobs",
		defaultValue = true,
		description = "Marks the starred mobs, which are the ones that count towards clearing a room.",
	)

	@JvmField
	val starStyle = DropdownModuleSetting(
		id = "star_style",
		label = "Starred style",
		options = styles,
		defaultIndex = OUTLINE,
		// Glow is always seen through walls.
		optionRestrictions = mapOf("Glow" to imicro.cryptic.gui.Restriction.CHEAT),
		visibleIf = { highlightStarred.value },
	)

	@JvmField
	val starPhase = ToggleModuleSetting(
		id = "star_phase",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
		label = "Phase",
		description = "Draws the box through the room rather than only where the mob can be seen.",
		visibleIf = { highlightStarred.value && !starGlows() },
	)

	@JvmField
	val starColor = ColorModuleSetting(
		id = "star_color",
		label = "Starred fill",
		defaultRgb = 0xFFFF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { highlightStarred.value && (starGlows() || starStyle.selectedIndex != OUTLINE) },
	)

	@JvmField
	val starOutlineColor = ColorModuleSetting(
		id = "star_outline_color",
		label = "Starred outline",
		defaultRgb = 0xFFFF55,
		supportsAlpha = true,
		visibleIf = { highlightStarred.value && !starGlows() && starStyle.selectedIndex != FILL },
	)

	private val specialSection = SectionModuleSetting(id = "special_section", label = "Special mobs")

	@JvmField
	val highlightBats = ToggleModuleSetting(
		id = "highlight_bats",
		label = "Highlight bats",
		defaultValue = true,
		description = "Bats are secrets, and are easy to lose against a dark ceiling.",
	)

	@JvmField
	val highlightMimic = ToggleModuleSetting(
		id = "highlight_mimic",
		label = "Highlight mimic",
		defaultValue = true,
		description = "The baby zombie a mimic chest turns into, on floors six and seven.",
	)

	@JvmField
	val highlightMimicChest = ToggleModuleSetting(
		id = "highlight_mimic_chest",
		label = "Highlight mimic chest",
		description = "Boxes the floor's one trapped chest.",
	)

	@JvmField
	val mimicChestColor = ColorModuleSetting(
		id = "mimic_chest_color",
		label = "Mimic chest",
		defaultRgb = 0xD11D05,
		supportsAlpha = true,
		defaultAlpha = 0xA0,
		visibleIf = { highlightMimicChest.value },
		inlineWith = highlightMimicChest,
	)

	@JvmField
	val highlightPrince = ToggleModuleSetting(
		id = "highlight_prince",
		label = "Highlight prince",
		defaultValue = true,
		description = "The crypt mob whose death reads \"A Prince falls\".",
	)

	@JvmField
	val specialStyle = DropdownModuleSetting(
		id = "special_style",
		label = "Special style",
		options = styles,
		defaultIndex = OUTLINE,
		optionRestrictions = mapOf("Glow" to imicro.cryptic.gui.Restriction.SEMI_LEGIT),
		visibleIf = { marksAnySpecial() },
	)

	@JvmField
	val specialPhase = ToggleModuleSetting(
		id = "special_phase",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Phase",
		defaultValue = true,
		description = "A bat behind a wall is the one you most want to know about.",
		visibleIf = { marksAnySpecial() && !specialGlows() },
	)

	@JvmField
	val specialColor = ColorModuleSetting(
		id = "special_color",
		label = "Special fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { marksAnySpecial() && (specialGlows() || specialStyle.selectedIndex != OUTLINE) },
	)

	@JvmField
	val specialOutlineColor = ColorModuleSetting(
		id = "special_outline_color",
		label = "Special outline",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		visibleIf = { marksAnySpecial() && !specialGlows() && specialStyle.selectedIndex != FILL },
	)

	@JvmField
	val specialTracers = ToggleModuleSetting(
		id = "special_tracers",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		label = "Special tracers",
		description = "Draws a line from you to each one, for the ones that are behind you.",
		visibleIf = { marksAnySpecial() },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Line width",
		defaultValue = 2.0,
		min = 1.0,
		max = 5.0,
		step = 0.5,
		description = "How thick the outlines and tracers are drawn.",
		visibleIf = { drawsLines() },
	)

	private val portalSection = SectionModuleSetting(id = "portal_section", label = "Portal")

	@JvmField
	val colorPortal = ToggleModuleSetting(
		id = "color_portal",
		label = "Color portal",
		description = "Colours the portal out of the blood room by the score you are on.",
	)

	@JvmField
	val belowSColor = ColorModuleSetting(
		id = "below_s_color",
		label = "Under 270",
		defaultRgb = 0xFF1010,
		supportsAlpha = true,
		defaultAlpha = 0xA0,
		visibleIf = { colorPortal.value },
	)

	@JvmField
	val sColor = ColorModuleSetting(
		id = "s_color",
		label = "270 and over",
		defaultRgb = 0xFFD700,
		supportsAlpha = true,
		defaultAlpha = 0xA0,
		visibleIf = { colorPortal.value },
	)

	@JvmField
	val sPlusColor = ColorModuleSetting(
		id = "s_plus_color",
		label = "300 and over",
		defaultRgb = 0x40FF00,
		supportsAlpha = true,
		defaultAlpha = 0xA0,
		visibleIf = { colorPortal.value },
	)

	@JvmField
	val module = Module(
		id = "highlight",
		name = "Highlight",
		description = "Highlights starred mobs",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(
			hideNonStarNames,
			highlightStarred.withMore(starStyle, starPhase, starColor, starOutlineColor),
			// The shared special-mob look sits behind the heading's "⋯".
			specialSection.withMore(specialStyle, specialPhase, specialColor, specialOutlineColor, specialTracers),
			highlightBats,
			highlightMimic,
			highlightMimicChest.withMore(mimicChestColor),
			highlightPrince,
			lineWidth,
			portalSection,
			colorPortal.withMore(belowSColor, sColor, sPlusColor),
		),
	)

	/**
	 * Mobs under a starred nametag. Added to and only pruned of the dead, since the search under a
	 * tag often comes back empty for a moment (the tag arrives first), and rebuilding each tick
	 * made the highlight blink.
	 */
	private val starred: MutableSet<Entity> = ConcurrentHashMap.newKeySet()

	private val starColors = BoxColors(starColor, starOutlineColor)
	private val specialColors = BoxColors(specialColor, specialOutlineColor)

	/** The mobs under a prince's nametag, kept the same way. */
	private val princes: MutableSet<Entity> = ConcurrentHashMap.newKeySet()

	/**
	 * The special mobs in the world and their colours, found once a tick so drawing them doesn't
	 * check every entity every frame.
	 */
	@Volatile
	private var special: Map<Entity, BoxColors> = emptyMap()

	/** What each nametag turned out to be, so the patterns run once per armour stand, not per frame. */
	private val tagCache = ConcurrentHashMap<Int, TagInfo>()

	private class TagInfo(
		val nameHash: Int,
		val starred: Boolean,
		val healthTag: Boolean,
		val prince: Boolean,
		val miniBoss: Boolean,
	)

	/**
	 * Tags with nothing living under them. Hypixel sometimes leaves a tag floating after its mob
	 * dies; two ticks with no mob tells that apart from a tag arriving early.
	 */
	private val orphanedTags = ConcurrentHashMap<Int, Int>()

	private const val TICKS_BEFORE_ORPHANED = 2

	private val portals = CopyOnWriteArrayList<BlockPos>()
	private val mimicChests = CopyOnWriteArrayList<BlockPos>()
	private var ticksUntilBlockScan = 0

	/**
	 * True while any of this has something to do; everything is gated on it, so nothing is marked
	 * outside a dungeon.
	 */
	private val active: Boolean
		get() = module.enabled && DungeonLocation.inDungeon

	fun initialize() {
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onWorldChange() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onWorldChange() }
	}

	fun tick(client: Minecraft) {
		if (!active) {
			if (starred.isNotEmpty() || princes.isNotEmpty() || portals.isNotEmpty() || special.isNotEmpty()) clear()
			return
		}

		val level = client.level ?: return
		starred.removeIf { !it.isAlive }
		princes.removeIf { !it.isAlive }

		// Whenever anything reads tags, since hiding tags needs the same lookup.
		if (highlightStarred.value || highlightPrince.value || hideNonStarNames.value) {
			resolveTaggedMobs(level.entitiesForRendering())
		}
		special = if (marksAnySpecial()) findSpecial(level.entitiesForRendering()) else emptyMap()
		scanBlocks(client)
	}

	private fun findSpecial(entities: Iterable<Entity>): Map<Entity, BoxColors> {
		val found = HashMap<Entity, BoxColors>()
		for (entity in entities) specialColorFor(entity)?.let { found[entity] = it }
		return found
	}

	/**
	 * Finds the mob under every relevant tag, every tick. Hypixel names a mob with an invisible
	 * armour stand a block above it, so the mob is what's underneath.
	 */
	private fun resolveTaggedMobs(entities: Iterable<Entity>) {
		entities.forEach { entity ->
			if (entity !is ArmorStand || !entity.isAlive) return@forEach
			val info = tagInfo(entity)
			if (!info.healthTag) return@forEach

			// One look under the tag says which mob it names and whether one is still there.
			// Slightly wider than the tag, since how far above it stands depends on the mob's
			// height (the prince was missed often).
			val mob = entity.level()
				.getEntities(entity, entity.boundingBox.move(0.0, -1.0, 0.0).inflate(0.3, 0.7, 0.3), ::namesAMob)
				.firstOrNull()

			if (mob == null) {
				orphanedTags.merge(entity.id, 1, Int::plus)
				return@forEach
			}
			orphanedTags.remove(entity.id)

			if (info.prince && highlightPrince.value) princes.add(mob)
			else if (info.starred && highlightStarred.value) starred.add(mob)
		}
	}

	/**
	 * What a tag says, cached until the tag changes. The name is part of the key, since a stand
	 * exists before it's named; caching its first look forever left unstarred mobs with health
	 * bars.
	 */
	private fun tagInfo(stand: ArmorStand): TagInfo {
		val name = stand.name.string
		val hash = name.hashCode()
		tagCache[stand.id]?.let { if (it.nameHash == hash) return it }

		val prince = PRINCE_NAME in name
		val miniBoss = miniBossNames.any { it in name }
		val info = TagInfo(
			nameHash = hash,
			// A star anywhere in the tag, as NoammAddons reads it.
			starred = STAR in name,
			// A health bar is a tag ending in a heart, whatever the mob.
			healthTag = name.endsWith(HEART),
			prince = prince,
			miniBoss = miniBoss,
		)
		tagCache[stand.id] = info
		return info
	}

	/**
	 * Hypixel's people in a dungeon who aren't mobs. Mort is a player entity like other NPCs, and
	 * got boxed as a mob in the entrance.
	 */
	private val dungeonNpcNames = setOf("Mort", "Boo", "Guild Master", "Fairy", "Mystic Skeleton")

	private fun isDungeonNpc(entity: Entity): Boolean {
		if (entity !is Player) return false
		// Real accounts' uuids are version four; Hypixel's NPCs are version two.
		if (entity.uuid.version() == 4) return false
		val name = entity.name.string.trim()
		if (name in dungeonNpcNames) return true
		return entity.displayName.string.let { display ->
			dungeonNpcNames.any { display.contains(it) }
		} == true
	}

	private fun namesAMob(entity: Entity): Boolean = when (entity) {
		is ArmorStand -> false
		is WitherBoss -> false
		is AbstractArrow -> false
		// Hypixel's mob NPCs are version-two player entities; the named ones with nothing to fight
		// aren't mobs.
		is Player ->
			entity.uuid.version() == 2 &&
				entity != Minecraft.getInstance().player &&
				!isDungeonNpc(entity)
		else -> !entity.isInvisible
	}

	/**
	 * Looks for the portal and the mimic chest in the blocks around you. The mimic chest is a
	 * trapped chest, where real secret chests are normal. A small search, once a second.
	 */
	private fun scanBlocks(client: Minecraft) {
		// Only before the boss: the blood portal is the one worth colouring by score. The run is
		// only declared over when the summary prints, seconds after the reward room appears.
		val wantsPortal = colorPortal.value && !DungeonRun.inBoss && !DungeonRun.ended
		val wantsChest = highlightMimicChest.value && DungeonLocation.floor >= 6
		if (!wantsPortal && portals.isNotEmpty()) portals.clear()
		if (!wantsChest && mimicChests.isNotEmpty()) mimicChests.clear()
		if (!wantsPortal && !wantsChest) return
		if (ticksUntilBlockScan-- > 0) return
		ticksUntilBlockScan = BLOCK_SCAN_INTERVAL_TICKS

		val level = client.level ?: return
		val origin = client.player?.blockPosition() ?: return
		val foundPortals = mutableListOf<BlockPos>()
		val foundChests = mutableListOf<BlockPos>()
		val cursor = BlockPos.MutableBlockPos()

		for (x in -SCAN_RADIUS..SCAN_RADIUS) {
			for (z in -SCAN_RADIUS..SCAN_RADIUS) {
				for (y in -SCAN_HEIGHT..SCAN_HEIGHT) {
					cursor.set(origin.x + x, origin.y + y, origin.z + z)
					when (level.getBlockState(cursor).block) {
						Blocks.NETHER_PORTAL -> if (wantsPortal) foundPortals.add(cursor.immutable())
						Blocks.TRAPPED_CHEST -> if (wantsChest && isMimicChest(cursor)) foundChests.add(cursor.immutable())
						else -> Unit
					}
				}
			}
		}

		if (wantsPortal) {
			portals.clear()
			portals.addAll(foundPortals)
		}
		if (wantsChest) {
			mimicChests.clear()
			mimicChests.addAll(foundChests)
		}
	}

	/**
	 * Whether a trapped chest is the mimic. The Slime room has its own trapped chest, so it's
	 * skipped by name.
	 */
	private fun isMimicChest(pos: BlockPos): Boolean {
		val room = DungeonFloor.roomAt(DungeonFloor.tileOf(pos.x.toDouble(), pos.z.toDouble()))
		return room?.data?.name !in roomsWithOwnTrappedChests
	}

	private fun clear() {
		starred.clear()
		princes.clear()
		special = emptyMap()
		tagCache.clear()
		orphanedTags.clear()
		portals.clear()
		mimicChests.clear()
	}

	fun onWorldChange() = clear()

	private fun render(context: LevelRenderContext) {
		if (!active) return
		val partialTick = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)

		if (highlightStarred.value && !starGlows()) {
			starred.forEach {
				drawEntity(context, it, partialTick, starColors, starStyle.selectedIndex, starPhase.value)
			}
		}

		if (!specialGlows() || specialTracers.value) {
			special.forEach { (entity, colors) ->
				if (!entity.isAlive) return@forEach
				if (!specialGlows()) {
					drawEntity(context, entity, partialTick, colors, specialStyle.selectedIndex, specialPhase.value)
				}
				// A tracer follows the outline colour.
				if (specialTracers.value) drawTracer(context, entity, partialTick, colors.outline)
			}
		}

		if (colorPortal.value && !DungeonRun.inBoss && !DungeonRun.ended) drawBlocks(context, portals, portalColorForScore(), outline = false)
		if (highlightMimicChest.value) drawBlocks(context, mimicChests, mimicChestColor.argb)
	}

	/** Boxes an entity where it's drawn this frame rather than its last tick position, which stuttered. */
	private fun drawEntity(
		context: LevelRenderContext,
		entity: Entity,
		partialTick: Float,
		colors: BoxColors,
		style: Int,
		phase: Boolean,
	) {
		val at = entity.getPosition(partialTick)
		val halfWidth = entity.bbWidth / 2.0
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = at.x - halfWidth,
			minY = at.y,
			minZ = at.z - halfWidth,
			maxX = at.x + halfWidth,
			maxY = at.y + entity.bbHeight,
			maxZ = at.z + halfWidth,
			outlineArgb = colors.outlineArgb(style != FILL),
			fillArgb = colors.fillArgb(style != OUTLINE),
			outline = style != FILL,
			fill = style != OUTLINE,
			phase = phase,
			lineWidth = lineWidth.value.toFloat(),
		)
	}

	private fun drawTracer(
		context: LevelRenderContext,
		entity: Entity,
		partialTick: Float,
		color: ColorModuleSetting,
	) {
		val at = entity.getPosition(partialTick)
		WorldRender.drawTracer(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			x = at.x,
			y = at.y + entity.bbHeight / 2.0,
			z = at.z,
			argb = ARGB.opaque(color.rgb),
			lineWidth = lineWidth.value.toFloat(),
			phase = true,
		)
	}

	private fun portalColorForScore(): Int {
		val score = DungeonStats.score
		return when {
			score >= 300 -> sPlusColor.argb
			score >= 270 -> sColor.argb
			else -> belowSColor.argb
		}
	}

	private fun drawBlocks(
		context: LevelRenderContext,
		positions: List<BlockPos>,
		argb: Int,
		outline: Boolean = true,
	) {
		positions.forEach { pos ->
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = pos,
				outlineArgb = ARGB.opaque(argb),
				fillArgb = argb,
				outline = outline,
				fill = true,
				phase = false,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = true,
			)
		}
	}

	/** A special mob's colours, or null. Fels have their own. */
	private fun specialColorFor(entity: Entity): BoxColors? {
		if (!entity.isAlive) return null
		if (isDungeonNpc(entity)) return null

		// None of these exist in the boss fight, and a Spirit Sceptre's bats were being boxed as
		// secrets.
		if (DungeonRun.inBoss) return null

		return when {
			entity is Bat ->
				if (highlightBats.value && !entity.isInvisible && !entity.isPassenger) specialColors else null
			// Both belong to Hidden Mobs, which configures revealing them; only the drawing is
			// here.
			entity is EnderMan ->
				if (HiddenMobs.highlightFels.value && entity.customName?.string == FEL_NAME) {
					HiddenMobs.felColors
				} else {
					null
				}
			entity is Player && isShadowAssassin(entity) ->
				if (HiddenMobs.highlightShadowAssassins.value) HiddenMobs.shadowAssassinColors else null
			entity is Zombie && entity.isBaby ->
				if (highlightMimic.value && isMimic(entity)) specialColors else null
			else -> if (highlightPrince.value && entity in princes) specialColors else null
		}
	}

	private fun isShadowAssassin(entity: Player): Boolean =
		entity.displayName.string.contains(HiddenMobs.SHADOW_ASSASSIN_NAME)

	/**
	 * Whether a baby zombie is the mimic rather than a weapon's summon (Ragnarök makes them too):
	 * only on floors with a mimic, and only if it carries nothing. A heuristic; `/cryptic debug`
	 * shows what a missed one carried.
	 */
	private fun isMimic(entity: Zombie): Boolean {
		if (DungeonLocation.floor < 6) return false
		if (HAND_SLOTS.any { !entity.getItemBySlot(it).isEmpty }) return false
		return isYellowLeather(entity)
	}

	private val HAND_SLOTS = listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)

	/**
	 * Whether this baby zombie's leather is the mimic's. The mimic and the Berserk ability's
	 * summons are otherwise identical (same mob, leather, head, empty hands, floor, health); only
	 * the dye differs. Matched exactly, so a future dyed zombie doesn't false-match; a retint stops
	 * it matching, and `/cryptic debug mimic` prints the shade.
	 */
	private fun isYellowLeather(entity: Zombie): Boolean {
		val dyed = LEATHER_SLOTS.firstNotNullOfOrNull {
			entity.getItemBySlot(it).get(DataComponents.DYED_COLOR)
		} ?: return false
		return (dyed.rgb and 0xFFFFFF) == MIMIC_LEATHER
	}

	private val LEATHER_SLOTS =
		listOf(EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

	/**
	 * The mimic's pale yellow leather, read off a real F7 mimic. Berserk summons wear `#9d9d9d`;
	 * other mobs no dye.
	 */
	private const val MIMIC_LEATHER = 0xEDDF85

	/** Every baby zombie nearby and what this makes of it, for `/cryptic debug mimic`. */
	fun describeMimics(): List<String> {
		val client = Minecraft.getInstance()
		val player = client.player ?: return listOf("No player.")
		val level = client.level ?: return listOf("No world.")

		val zombies = level.getEntitiesOfClass(Zombie::class.java, player.boundingBox.inflate(MIMIC_DEBUG_RADIUS))
		val lines = mutableListOf(
			"Floor ${DungeonLocation.floor}, ${zombies.size} zombies within ${MIMIC_DEBUG_RADIUS.toInt()} blocks:",
		)

		zombies.forEach { zombie ->
			val held = EquipmentSlot.entries
				.filter { !zombie.getItemBySlot(it).isEmpty }
				.joinToString { "$it=${zombie.getItemBySlot(it).hoverName.string}" }
			lines += "  ${zombie.type.description.string} baby=${zombie.isBaby} " +
				"name=${zombie.customName?.string ?: "-"} mimic=${zombie.isBaby && isMimic(zombie)} " +
				"hp=${zombie.health.toInt()} " +
				"leather=${leatherFingerprint(zombie)} " +
				"carrying=${held.ifEmpty { "nothing" }}"
		}
		return lines
	}

	/**
	 * What this module decided about everything nearby, for `/cryptic debug highlight`: the entity,
	 * its tag and which list it landed in.
	 */
	fun describeHighlights(): List<String> {
		val client = Minecraft.getInstance()
		val player = client.player ?: return listOf("No player.")
		val level = client.level ?: return listOf("No world.")

		val lines = mutableListOf(
			"Highlight: active=$active starred=${starred.size} princes=${princes.size}",
		)

		level.getEntities(player, player.boundingBox.inflate(MIMIC_DEBUG_RADIUS)) { true }.forEach { entity ->
			val marked = entity in starred || entity in princes || specialColorFor(entity) != null
			val outlined = outlineColorFor(entity) != EntityRenderState.NO_OUTLINE
			if (!marked && !outlined) return@forEach
			lines += "  ${entity.type.description.string} name=${entity.name.string} " +
				"display=${entity.displayName.string} " +
				"npc=${isDungeonNpc(entity)} starred=${entity in starred} prince=${entity in princes} " +
				"special=${specialColorFor(entity) != null} glow=$outlined"
		}

		if (lines.size == 1) lines += "  Nothing within ${MIMIC_DEBUG_RADIUS.toInt()} blocks is marked."
		return lines
	}

	/**
	 * A baby zombie's leather dye, the one thing separating the mimic from a Berserk summon.
	 * Saturation is shown too, since that's what the check reads.
	 */
	private fun leatherFingerprint(entity: Zombie): String {
		val dyed = LEATHER_SLOTS.firstNotNullOfOrNull {
			entity.getItemBySlot(it).get(DataComponents.DYED_COLOR)
		} ?: return "undyed"

		val rgb = dyed.rgb
		val red = (rgb shr 16) and 0xFF
		val green = (rgb shr 8) and 0xFF
		val blue = rgb and 0xFF
		val brightest = maxOf(red, green, blue)
		val saturation = if (brightest == 0) 0 else (brightest - minOf(red, green, blue)) * 100 / brightest
		return "#%06x(sat $saturation%%)".format(rgb and 0xFFFFFF)
	}

	private const val MIMIC_DEBUG_RADIUS = 24.0

	/** Whether this entity should be left undrawn, decided at render so a tag never flashes for a frame. */
	@JvmStatic
	fun hidesNameTag(entity: Entity): Boolean {
		if (!active) return false
		if (entity !is ArmorStand || !entity.isInvisible) return false

		// A tag with nothing under it goes regardless of settings.
		if ((orphanedTags[entity.id] ?: 0) >= TICKS_BEFORE_ORPHANED) return true

		if (!hideNonStarNames.value) return false
		val info = tagInfo(entity)
		// A mini boss's name stays, starred or not.
		return info.healthTag && !info.starred && !info.prince && !info.miniBoss
	}

	/**
	 * Whether an entity's own name should be dropped. A Fel's "Dinnerbone" is noise, though the Fel
	 * stays marked.
	 */
	@JvmStatic
	fun hidesOwnNameTag(entity: Entity): Boolean {
		if (!active || !hideNonStarNames.value) return false
		return entity is EnderMan && entity.customName?.string == FEL_NAME
	}

	/**
	 * The outline colour for [entity] (the glow option). Asked per entity per frame, so cheap tests
	 * first.
	 */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (!active) return EntityRenderState.NO_OUTLINE
		if (entity !is LivingEntity) return EntityRenderState.NO_OUTLINE

		if (highlightStarred.value && starGlows() && entity in starred) {
			return ARGB.opaque(starColor.rgb)
		}
		// A glow is one colour; the fill reads as the mob's colour.
		if (specialGlows()) special[entity]?.let { return ARGB.opaque(it.fill.rgb) }
		return EntityRenderState.NO_OUTLINE
	}
}
