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
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
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
 * Marks the things in a dungeon worth looking at, and hides the noise around
 * them.
 *
 * The starred-mob half is Odin's `Highlight` (BSD 3-Clause, Copyright (c) 2025
 * odtheking): a starred mob is found by its nametag, and the mob itself is
 * whatever is standing under that tag. The bats are NoammAddons' `StarMobESP`
 * (CC0, Noamm9). Colouring the blood portal by score and boxing the mimic's
 * chest are both [Devonian](https://github.com/Synnerz/Devonian)'s.
 */
object Highlight {
	/**
	 * What every dungeon health tag ends with, and what marks a starred one.
	 *
	 * NoammAddons reads tags this way and it is the better idea: a name list
	 * only knows the mobs somebody remembered to write down, while every health
	 * bar in the game ends in a heart whatever is under it.
	 */
	private const val HEART = "❤"
	private const val STAR = "✯"

	/**
	 * What the crypt prince's nametag says.
	 *
	 * Matched on the word alone because Hypixel names him after whichever event
	 * is running — "Prince Nicholas" over the new year — and chasing the surname
	 * twice a season is not worth it. Looking for a species instead is what the
	 * first attempt got wrong: he is a zombie villager with a name, and nothing
	 * about the entity says prince.
	 */
	private const val PRINCE_NAME = "Prince"

	/**
	 * The name Hypixel gives its Fels, which is also what turns them upside
	 * down. The tag is scenery rather than information, so it is dropped with
	 * the rest even though a Fel is worth marking.
	 */
	private const val FEL_NAME = "Dinnerbone"

	/**
	 * The mobs whose names are worth keeping even unstarred: a mini boss is
	 * the reason you are in the room, and hiding what it is helps nobody.
	 */
	private val miniBossNames = setOf(
		"Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer",
		"Shadow Assassin", "Diamond Guy", "King Midas",
	)

	private const val FILLED = 0
	private const val OUTLINE = 1

	private val styles = listOf("Filled", "Outline", "Filled outline")

	/** Whether anything in the special-mob group is switched on at all. */
	private fun marksAnySpecial(): Boolean =
		highlightBats.value || highlightMimic.value || highlightPrince.value ||
			HiddenMobs.highlightFels.value || HiddenMobs.highlightShadowAssassins.value

	private const val SCAN_RADIUS = 16
	private const val SCAN_HEIGHT = 8
	private const val BLOCK_SCAN_INTERVAL_TICKS = 20

	/** Rooms that come with a trapped chest of their own, mimic or not. */
	private val roomsWithOwnTrappedChests = setOf("Slime")

	// ---- Starred mobs ----------------------------------------------------

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
	val starGlow = ToggleModuleSetting(
		id = "star_glow",
		label = "Use glow instead",
		description = "Uses the game's own outline pass rather than a drawn box. It always shows through walls.",
		visibleIf = { highlightStarred.value },
	)

	@JvmField
	val starStyle = DropdownModuleSetting(
		id = "star_style",
		label = "Starred style",
		options = styles,
		defaultIndex = OUTLINE,
		visibleIf = { highlightStarred.value && !starGlow.value },
	)

	@JvmField
	val starPhase = ToggleModuleSetting(
		id = "star_phase",
		label = "Phase",
		description = "Draws the box through the room rather than only where the mob can be seen.",
		visibleIf = { highlightStarred.value && !starGlow.value },
	)

	@JvmField
	val starColor = ColorModuleSetting(
		id = "star_color",
		label = "Starred",
		defaultRgb = 0xFFFF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { highlightStarred.value },
	)

	// ---- Special mobs ----------------------------------------------------

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
		description = "Boxes the chest the mimic is in before it is opened: the one trapped chest on the floor.",
	)

	@JvmField
	val mimicChestColor = ColorModuleSetting(
		id = "mimic_chest_color",
		label = "Mimic chest",
		defaultRgb = 0xD11D05,
		supportsAlpha = true,
		defaultAlpha = 0xA0,
		visibleIf = { highlightMimicChest.value },
	)

	@JvmField
	val highlightPrince = ToggleModuleSetting(
		id = "highlight_prince",
		label = "Highlight prince",
		defaultValue = true,
		description = "The crypt mob whose death reads \"A Prince falls\".",
	)

	@JvmField
	val specialGlow = ToggleModuleSetting(
		id = "special_glow",
		label = "Use glow instead",
		description = "Uses the game's own outline pass rather than a drawn box. It always shows through walls.",
		visibleIf = { marksAnySpecial() },
	)

	@JvmField
	val specialStyle = DropdownModuleSetting(
		id = "special_style",
		label = "Special style",
		options = styles,
		defaultIndex = OUTLINE,
		visibleIf = { marksAnySpecial() && !specialGlow.value },
	)

	@JvmField
	val specialPhase = ToggleModuleSetting(
		id = "special_phase",
		label = "Phase",
		defaultValue = true,
		description = "A bat behind a wall is the one you most want to know about.",
		visibleIf = { marksAnySpecial() && !specialGlow.value },
	)

	@JvmField
	val specialColor = ColorModuleSetting(
		id = "special_color",
		label = "Special",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { marksAnySpecial() },
	)

	@JvmField
	val specialTracers = ToggleModuleSetting(
		id = "special_tracers",
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
	)

	// ---- Portal ----------------------------------------------------------

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
		description = "Marks starred mobs, bats, the mimic and the prince",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			hideNonStarNames,
			highlightStarred,
			starGlow,
			starStyle,
			starPhase,
			starColor,
			specialSection,
			highlightBats,
			highlightMimic,
			highlightMimicChest,
			mimicChestColor,
			highlightPrince,
			specialGlow,
			specialStyle,
			specialPhase,
			specialColor,
			specialTracers,
			lineWidth,
			portalSection,
			colorPortal,
			belowSColor,
			sColor,
			sPlusColor,
		),
	)

	/**
	 * The mobs standing under a starred nametag.
	 *
	 * Added to rather than rebuilt, and only pruned of the dead: the search
	 * that finds a mob under its tag comes back empty often enough — the tag
	 * arrives before the mob, or the two are briefly apart — that dropping the
	 * set each tick would make the highlight blink. An earlier version resolved
	 * each tag only once for that reason and got the opposite problem: a tag
	 * that missed on its first look was never looked at again, which is why
	 * some starred mobs went unmarked.
	 */
	private val starred: MutableSet<Entity> = ConcurrentHashMap.newKeySet()

	/** The mobs found under a prince's nametag, kept the same way. */
	private val princes: MutableSet<Entity> = ConcurrentHashMap.newKeySet()

	/**
	 * What each nametag turned out to be, so the patterns run once per armour
	 * stand rather than once per stand per frame. Neither answer can change
	 * while the tag exists — only the health in the middle of it does.
	 */
	private val tagCache = ConcurrentHashMap<Int, TagInfo>()

	private class TagInfo(
		val nameHash: Int,
		val starred: Boolean,
		val healthTag: Boolean,
		val prince: Boolean,
		val miniBoss: Boolean,
	)

	/**
	 * Tags with nothing living under them any more.
	 *
	 * Hypixel does not always take the armour stand away with the mob it was
	 * naming, so a room can be left with health bars floating over nothing.
	 * Two ticks of finding no mob is what separates one of those from a tag
	 * that has simply arrived a moment before its mob.
	 */
	private val orphanedTags = ConcurrentHashMap<Int, Int>()

	private const val TICKS_BEFORE_ORPHANED = 2

	private val portals = CopyOnWriteArrayList<BlockPos>()
	private val mimicChests = CopyOnWriteArrayList<BlockPos>()
	private var ticksUntilBlockScan = 0

	/**
	 * True while any of this has something to do.
	 *
	 * Every part of the module is gated on it, so nothing here can mark
	 * anything outside a dungeon — a bat in a hub is still just a bat.
	 */
	private val active: Boolean
		get() = module.enabled && DungeonLocation.inDungeon

	fun initialize() {
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(::render)
		// What was found in one dungeon means nothing in the next.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onWorldChange() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onWorldChange() }
	}

	fun tick(client: Minecraft) {
		if (!active) {
			if (starred.isNotEmpty() || princes.isNotEmpty() || portals.isNotEmpty()) clear()
			return
		}

		val level = client.level ?: return
		starred.removeIf { !it.isAlive }
		princes.removeIf { !it.isAlive }

		// Run whenever anything reads a tag, not only when something is being
		// highlighted: hiding the tags needs the same look underneath to know
		// which of them have nothing left to name.
		if (highlightStarred.value || highlightPrince.value || hideNonStarNames.value) {
			resolveTaggedMobs(level.entitiesForRendering())
		}
		scanBlocks(client)
	}

	/**
	 * Finds the mob under every tag worth following, every tick.
	 *
	 * Hypixel names a mob by standing an invisible armour stand a block above
	 * it, so the tag says which mob is starred but the tag is not the mob. What
	 * is underneath it is.
	 */
	private fun resolveTaggedMobs(entities: Iterable<Entity>) {
		entities.forEach { entity ->
			if (entity !is ArmorStand || !entity.isAlive) return@forEach
			val info = tagInfo(entity)
			if (!info.healthTag) return@forEach

			// One look under the tag answers both questions: which mob it is
			// naming, and whether there is still one there at all.
			// A little wider than the tag itself. Hypixel stands a tag a block
			// over the mob it names, but how far over depends on how tall the
			// mob is, and an exact box missed the taller ones — which is why the
			// prince went unmarked as often as he did.
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
	 * What a tag says, worked out once and kept until the tag itself changes.
	 *
	 * The name is hashed alongside the answers because an armour stand exists
	 * before it is named: Hypixel spawns it and sends the tag a moment later.
	 * Caching the first look and trusting it forever is what left unstarred
	 * mobs with their health bars still up — at the moment they were asked
	 * about, they were called "Armor Stand" and looked like nothing at all.
	 */
	private fun tagInfo(stand: ArmorStand): TagInfo {
		val name = stand.name.string
		val hash = name.hashCode()
		tagCache[stand.id]?.let { if (it.nameHash == hash) return it }

		val prince = PRINCE_NAME in name
		val miniBoss = miniBossNames.any { it in name }
		val info = TagInfo(
			nameHash = hash,
			// A star anywhere in the tag, the way NoammAddons reads it. Odin's
			// fuller pattern says the same thing and costs a regex to say it.
			starred = STAR in name,
			// What makes a tag a health bar is that it ends in a heart, not
			// which mob it happens to name. An earlier version kept a list of
			// mob names instead, so anything the list had never heard of — a
			// deathmite, say — kept its bar up.
			healthTag = name.endsWith(HEART),
			prince = prince,
			miniBoss = miniBoss,
		)
		tagCache[stand.id] = info
		return info
	}

	/** Whether an entity under a tag is the mob the tag is naming. */
	private fun namesAMob(entity: Entity): Boolean = when (entity) {
		is ArmorStand -> false
		is WitherBoss -> false
		is AbstractArrow -> false
		// Hypixel's mob NPCs are player entities with a version-two uuid, which
		// is what tells them apart from real players.
		is Player -> entity.uuid.version() == 2 && entity != Minecraft.getInstance().player
		else -> !entity.isInvisible
	}

	/**
	 * Looks for the portal and the mimic's chest near the player.
	 *
	 * Devonian works both out from the room's own geometry; this looks for the
	 * blocks instead, which needs nothing to be known about the floor's layout.
	 * The mimic's chest is a *trapped* chest where every real secret chest is an
	 * ordinary one, which is the whole of Devonian's insight. The search is
	 * small and a second apart, because it only has to find something you are
	 * walking towards.
	 */
	private fun scanBlocks(client: Minecraft) {
		// Only up to the boss. The portal worth colouring is the blood room's —
		// a way in, and the last moment the score still decides anything. Every
		// portal after it is a way out: the one in the reward room is scenery,
		// and colouring it by a score already awarded says nothing. Waiting for
		// the run to be declared over was not enough, because that only happens
		// when the summary prints, several seconds after the reward room appears.
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
	 * Whether a trapped chest is the mimic rather than furniture.
	 *
	 * Devonian only ever looks at the chests its waypoint data already calls
	 * secrets, so it never meets this problem. Looking for the blocks instead
	 * finds the Slime room's own trapped chest, which is part of the room and
	 * not a mimic, so that room is asked about by name and passed over.
	 */
	private fun isMimicChest(pos: BlockPos): Boolean {
		val room = DungeonFloor.roomAt(DungeonFloor.tileOf(pos.x.toDouble(), pos.z.toDouble()))
		return room?.data?.name !in roomsWithOwnTrappedChests
	}

	private fun clear() {
		starred.clear()
		princes.clear()
		tagCache.clear()
		orphanedTags.clear()
		portals.clear()
		mimicChests.clear()
	}

	fun onWorldChange() = clear()

	// ---- Drawing ---------------------------------------------------------

	private fun render(context: LevelRenderContext) {
		if (!active) return
		val partialTick = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)

		if (highlightStarred.value && !starGlow.value) {
			starred.forEach {
				drawEntity(context, it, partialTick, starColor, starStyle.selectedIndex, starPhase.value)
			}
		}

		if (!specialGlow.value || specialTracers.value) {
			Minecraft.getInstance().level?.entitiesForRendering()?.forEach { entity ->
				val color = specialColorFor(entity) ?: return@forEach
				if (!specialGlow.value) {
					drawEntity(context, entity, partialTick, color, specialStyle.selectedIndex, specialPhase.value)
				}
				if (specialTracers.value) drawTracer(context, entity, partialTick, color)
			}
		}

		if (colorPortal.value && !DungeonRun.inBoss && !DungeonRun.ended) drawBlocks(context, portals, portalColorForScore())
		if (highlightMimicChest.value) drawBlocks(context, mimicChests, mimicChestColor.argb)
	}

	/**
	 * Boxes an entity where it is being drawn this frame rather than where it
	 * stood on the last tick. Twenty positions a second is what made the boxes
	 * and their tracers stutter; the renderer runs far faster than that, and
	 * interpolates everything else it draws.
	 */
	private fun drawEntity(
		context: LevelRenderContext,
		entity: Entity,
		partialTick: Float,
		color: ColorModuleSetting,
		style: Int,
		phase: Boolean,
	) {
		val at = entity.getPosition(partialTick)
		val halfWidth = entity.bbWidth / 2.0
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			consumers = context.bufferSource(),
			minX = at.x - halfWidth,
			minY = at.y,
			minZ = at.z - halfWidth,
			maxX = at.x + halfWidth,
			maxY = at.y + entity.bbHeight,
			maxZ = at.z + halfWidth,
			outlineArgb = if (style == FILLED) 0 else ARGB.opaque(color.rgb),
			fillArgb = if (style == OUTLINE) 0 else color.argb,
			outline = style != FILLED,
			fill = style != OUTLINE,
			phase = phase,
			lineWidth = lineWidth.value.toFloat(),
		)
	}

	/** From just in front of the camera to the middle of the mob, interpolated. */
	private fun drawTracer(
		context: LevelRenderContext,
		entity: Entity,
		partialTick: Float,
		color: ColorModuleSetting,
	) {
		val at = entity.getPosition(partialTick)
		WorldRender.drawTracer(
			poseStack = context.poseStack(),
			consumers = context.bufferSource(),
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

	private fun drawBlocks(context: LevelRenderContext, positions: List<BlockPos>, argb: Int) {
		positions.forEach { pos ->
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				consumers = context.bufferSource(),
				pos = pos,
				outlineArgb = ARGB.opaque(argb),
				fillArgb = argb,
				outline = true,
				fill = true,
				phase = false,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = true,
			)
		}
	}

	// ---- Asked by the renderer -------------------------------------------

	/**
	 * The colour a special mob should be marked in, or null when it is not one.
	 * Fels have their own because a Fel is a different kind of news from a bat.
	 */
	private fun specialColorFor(entity: Entity): ColorModuleSetting? {
		if (!entity.isAlive) return null

		// None of this group exists in the boss fight — there are no secrets to
		// find, no mimic and no prince — so everything it marks in there is
		// something else wearing the same shape. A Spirit Sceptre throws out a
		// screenful of bats, and every one of them was being boxed as a secret.
		if (DungeonRun.inBoss) return null

		return when {
			entity is Bat ->
				if (highlightBats.value && !entity.isInvisible && !entity.isPassenger) specialColor else null
			// Both of these belong to Hidden Mobs, which is where revealing them
			// is configured; only the drawing is here.
			entity is EnderMan ->
				if (HiddenMobs.highlightFels.value && entity.customName?.string == FEL_NAME) {
					HiddenMobs.felColor
				} else {
					null
				}
			entity is Player && isShadowAssassin(entity) ->
				if (HiddenMobs.highlightShadowAssassins.value) HiddenMobs.shadowAssassinColor else null
			entity is Zombie && entity.isBaby ->
				if (highlightMimic.value && isMimic(entity)) specialColor else null
			else -> if (highlightPrince.value && entity in princes) specialColor else null
		}
	}

	private fun isShadowAssassin(entity: Player): Boolean =
		entity.displayName?.string?.contains(HiddenMobs.SHADOW_ASSASSIN_NAME) == true

	/**
	 * Whether a baby zombie is the mimic rather than something a weapon made.
	 *
	 * "Baby zombie" alone is not enough, and the screenshot that proved it had
	 * a whole crowd of them boxed as mimics — Ragnarök throws up its own, and
	 * they are the same mob. Two things separate them: a mimic only exists on
	 * the floors that have one, and it carries nothing, where the summoned ones
	 * are equipped.
	 *
	 * A heuristic, and worth saying so: it is the difference that showed in the
	 * report rather than one confirmed against a real mimic, and if it ever
	 * misses one `/cryptic debug` is the way to find out what it was carrying.
	 */
	private fun isMimic(entity: Zombie): Boolean {
		if (DungeonLocation.floor < 6) return false
		return EquipmentSlot.entries.all { entity.getItemBySlot(it).isEmpty }
	}

	/**
	 * Whether this entity should be left undrawn.
	 *
	 * Deciding it here rather than removing the armour stand on the tick is
	 * what stops a tag showing for a frame before it goes: by the time the
	 * renderer asks, the answer is already known.
	 */
	@JvmStatic
	fun hidesNameTag(entity: Entity): Boolean {
		if (!active) return false
		if (entity !is ArmorStand || !entity.isInvisible) return false

		// A tag with nothing left under it goes whatever the setting says: it
		// is not information about anything any more.
		if ((orphanedTags[entity.id] ?: 0) >= TICKS_BEFORE_ORPHANED) return true

		if (!hideNonStarNames.value) return false
		val info = tagInfo(entity)
		// A mini boss is the reason you came into the room, so its name stays
		// up whether or not Hypixel starred it.
		return info.healthTag && !info.starred && !info.prince && !info.miniBoss
	}

	/**
	 * Whether an entity's own name should be dropped.
	 *
	 * A Fel is named "Dinnerbone" because that is what turns it upside down, not
	 * because anyone wants to read it, so the label goes with the rest of the
	 * room's noise while the Fel itself stays worth marking.
	 */
	@JvmStatic
	fun hidesOwnNameTag(entity: Entity): Boolean {
		if (!active || !hideNonStarNames.value) return false
		return entity is EnderMan && entity.customName?.string == FEL_NAME
	}

	/**
	 * The colour the game's own outline pass should draw [entity] in, which is
	 * what the glow option is. Asked per entity per frame, so the cheapest
	 * tests come first.
	 */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (!active) return EntityRenderState.NO_OUTLINE
		if (entity !is LivingEntity) return EntityRenderState.NO_OUTLINE

		if (highlightStarred.value && starGlow.value && entity in starred) {
			return ARGB.opaque(starColor.rgb)
		}
		if (specialGlow.value) specialColorFor(entity)?.let { return ARGB.opaque(it.rgb) }
		return EntityRenderState.NO_OUTLINE
	}
}
