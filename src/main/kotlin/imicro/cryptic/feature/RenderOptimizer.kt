package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.mixin.AbstractArrowAccessor
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Stops the game drawing things a dungeon run never needs to see.
 *
 * Ported from Odin's and NoammAddons' modules of the same name (BSD 3-Clause,
 * Copyright (c) 2025 odtheking; CC0, Noamm9). Most of it works on the packet
 * rather than the renderer: an entity that is never added to the world costs
 * nothing to not draw, which is where the name comes from. The rest is decided
 * as the entity's render state is built.
 *
 * Everything here is off by default. Each switch hides something real, and
 * which of them are noise rather than information depends on what you are
 * doing.
 */
object RenderOptimizer {
	/**
	 * Hypixel's own skulls, identified by the texture they carry rather than by
	 * name, because the armour stand wearing one is not otherwise labelled.
	 * These three are Odin's.
	 */
	private const val HEALER_FAIRY_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTQ2MzA5MTA0NywKICAicHJvZmlsZUlkIiA6ICIyNjRkYzBlYjVlZGI0ZmI3OTgxNWIyZGY1NGY0OTgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJxdWludHVwbGV0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlZWRjZmZjNmExMWEzODM0YTI4ODQ5Y2MzMTZhZjdhMjc1MmEzNzZkNTM2Y2Y4NDAzOWNmNzkxMDhiMTY3YWUiCiAgICB9CiAgfQp9"

	private const val SOUL_WEAVER_TEXTURE =
		"eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ=="

	private const val TENTACLE_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTg1NzI3NzI0OSwKICAicHJvZmlsZUlkIiA6ICIxODA1Y2E2MmM0ZDI0M2NiOWQxYmY4YmM5N2E1YjgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJSdWxsZWQiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdkODM2NzQ5MjZiODk3MTRlNmI1YTU1NDcwNTAxYzA0YjA2NmRkODdiZjZjMzM1Y2RkYzZlNjBhMWExYTVmNSIKICAgIH0KICB9Cn0="

	/**
	 * A nametag reading zero health, in the two shapes Hypixel writes it: with
	 * a level prefix and a fraction, or bare. NoammAddons' patterns.
	 */
	private val zeroHealthPatterns = listOf(
		Regex("^§.\\[§.Lv\\d+§.] §.+ (?:§.)+0§f/.+§c❤$"),
		Regex("^.+ (?:§.)+0§c❤$"),
	)

	private val entitiesSection = SectionModuleSetting("entities_section", "Entities")
	private val abilitiesSection = SectionModuleSetting("abilities_section", "Abilities")
	private val fireSection = SectionModuleSetting("fire_section", "Fire")

	@JvmField
	val hideFallingBlocks = ToggleModuleSetting(
		id = "hide_falling_blocks",
		label = "Hide falling blocks",
		description = "Drops falling blocks before they are drawn.",
	)

	@JvmField
	val hideExperienceOrbs = ToggleModuleSetting(
		id = "hide_experience_orbs",
		label = "Hide experience orbs",
		description = "Hides experience orbs, which a dungeon floor can be knee deep in.",
	)

	@JvmField
	val hideLightning = ToggleModuleSetting(
		id = "hide_lightning",
		label = "Hide lightning bolts",
		description = "Hides lightning bolts and the flash that comes with them.",
	)

	@JvmField
	val hideDeathAnimation = ToggleModuleSetting(
		id = "hide_death_animation",
		label = "Hide death animation",
		description = "Stops drawing a mob once it is dead, rather than watching it topple over.",
	)

	@JvmField
	val hideDyingArmorStands = ToggleModuleSetting(
		id = "hide_dying_armor_stands",
		label = "Hide armor stands",
		description = "Also drops the nametag left floating over a mob that is dying.",
		visibleIf = { hideDeathAnimation.value },
	)

	@JvmField
	val hideZeroHealth = ToggleModuleSetting(
		id = "hide_zero_health",
		label = "Hide 0 health",
		description = "Removes the nametags of mobs that are already dead but still on screen.",
	)

	@JvmField
	val hideExplosionParticles = ToggleModuleSetting(
		id = "hide_explosion_particles",
		label = "Hide explosion particles",
		description = "Hides explosion particles, which is mostly Hyperion.",
	)

	@JvmField
	val hideArcherPassive = ToggleModuleSetting(
		id = "hide_archer_passive",
		label = "Hide archer passive",
		description = "Hides the bone meal the archer passive leaves floating around its target.",
	)

	@JvmField
	val hideHealerFairy = ToggleModuleSetting(
		id = "hide_healer_fairy",
		label = "Hide healer fairy",
		description = "Hides the fairy a healer's wand leaves following its target.",
	)

	@JvmField
	val hideSoulWeaver = ToggleModuleSetting(
		id = "hide_soul_weaver",
		label = "Hide soul weaver",
		description = "Hides the heads the Soul Weaver gloves send flying.",
	)

	@JvmField
	val hideTentacleHead = ToggleModuleSetting(
		id = "hide_tentacle_head",
		label = "Hide tentacle head",
		description = "Hides the Wither King's tentacles in the last phase of Master Mode 7.",
	)

	@JvmField
	val hidePlayerArrows = ToggleModuleSetting(
		id = "hide_player_arrows",
		label = "Hide player stuck arrows",
		description = "Drops the arrows left sticking out of a player.",
	)

	@JvmField
	val hideGroundArrows = ToggleModuleSetting(
		id = "hide_ground_arrows",
		label = "Hide ground stuck arrows",
		description = "Drops arrows once they have landed, which is most of an archer's room.",
	)

	@JvmField
	val hideFireOnEntities = ToggleModuleSetting(
		id = "hide_fire_on_entities",
		label = "Hide fire on entities",
		description = "Hides the flames drawn over a burning mob, without hiding the mob.",
	)

	@JvmField
	val hideFireOverlay = ToggleModuleSetting(
		id = "hide_fire_overlay",
		label = "Hide fire overlay",
		description = "Hides the flames drawn across your own screen while you are burning.",
	)

	@JvmField
	val fireOverlayOpacity = SliderModuleSetting(
		id = "fire_overlay_opacity",
		label = "Fire overlay opacity",
		defaultValue = 100.0,
		min = 5.0,
		max = 100.0,
		step = 5.0,
		description = "Fades the fire overlay instead of hiding it, so you can still tell you are on fire.",
		visibleIf = { !hideFireOverlay.value },
	)

	@JvmField
	val module = Module(
		id = "render_optimizer",
		name = "Render Optimizer",
		description = "Stops drawing what a run does not need",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			entitiesSection,
			hideFallingBlocks,
			hideExperienceOrbs,
			hideLightning,
			hideDeathAnimation,
			hideDyingArmorStands,
			hideZeroHealth,
			hidePlayerArrows,
			hideGroundArrows,
			abilitiesSection,
			hideExplosionParticles,
			hideArcherPassive,
			hideHealerFairy,
			hideSoulWeaver,
			hideTentacleHead,
			fireSection,
			hideFireOnEntities,
			hideFireOverlay,
			fireOverlayOpacity,
		),
	)

	// ---- Packets ---------------------------------------------------------

	/** True when an entity of [type] should never reach the world at all. */
	@JvmStatic
	fun blocksSpawn(type: EntityType<*>): Boolean {
		if (!module.enabled) return false
		return when (type) {
			EntityTypes.FALLING_BLOCK -> hideFallingBlocks.value
			EntityTypes.EXPERIENCE_ORB -> hideExperienceOrbs.value
			EntityTypes.LIGHTNING_BOLT -> hideLightning.value
			else -> false
		}
	}

	@JvmStatic
	fun blocksParticle(particle: ParticleOptions): Boolean {
		if (!module.enabled || !hideExplosionParticles.value) return false
		val type = particle.type
		return type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER
	}

	/**
	 * Whether the entity carrying this nametag should go.
	 *
	 * The tag is the only thing that says what an armour stand is holding up, so
	 * a mob that is already dead — and the orbs and fairies that are only ever
	 * decoration — are recognised by reading it.
	 */
	@JvmStatic
	fun hidesNameTag(name: Component): Boolean {
		if (!module.enabled || !hideZeroHealth.value) return false
		val text = name.string
		// Asked of every nametag update, and a mob being hit updates its tag
		// every time: both patterns end on the heart after a zero, so anything
		// that does not is turned away before a pattern is tried.
		if (!text.endsWith("§c❤") || !text.contains("0§")) return false
		return zeroHealthPatterns.any { it.matches(text) }
	}

	/** The archer passive is a floating item rather than a mob, so it is read off the item it holds. */
	@JvmStatic
	fun hidesHeldItem(item: ItemStack): Boolean {
		if (!module.enabled || !hideArcherPassive.value) return false
		if (!DungeonLocation.inDungeon) return false
		return !item.isEmpty && item.item == Items.BONE_MEAL
	}

	/** Whether a piece of equipment marks its wearer as something to drop. */
	@JvmStatic
	fun hidesEquipment(slot: EquipmentSlot, item: ItemStack): Boolean {
		if (!module.enabled || item.isEmpty) return false
		if (!DungeonLocation.inDungeon) return false

		val texture = skullTexture(item) ?: return false
		return when {
			slot == EquipmentSlot.MAINHAND -> hideHealerFairy.value && texture == HEALER_FAIRY_TEXTURE
			slot != EquipmentSlot.HEAD -> false
			texture == SOUL_WEAVER_TEXTURE -> hideSoulWeaver.value
			texture == TENTACLE_TEXTURE -> hideTentacleHead.value
			else -> false
		}
	}

	private fun skullTexture(item: ItemStack): String? =
		item.get(DataComponents.PROFILE)
			?.partialProfile()
			?.properties
			?.get("textures")
			?.firstOrNull()
			?.value

	/** Takes an entity out of the world on the client thread, where that is safe. */
	@JvmStatic
	fun discard(entityId: Int) {
		val client = Minecraft.getInstance()
		client.execute { client.level?.removeEntity(entityId, Entity.RemovalReason.DISCARDED) }
	}

	// ---- Rendering -------------------------------------------------------

	/**
	 * Whether [entity] should be left undrawn.
	 *
	 * A mob is dead well before it is gone: the server keeps it around for the
	 * topple, and in a busy room that is a lot of corpses. The armour stand
	 * holding its nametag is a separate entity and is judged separately, so the
	 * body can go while the tag stays.
	 */
	@JvmStatic
	fun hidesEntity(entity: Entity): Boolean {
		if (!module.enabled) return false

		// An arrow that has landed is scenery, and a bow with any fire rate at
		// all leaves a floor full of it.
		if (hideGroundArrows.value && entity is AbstractArrow) {
			if ((entity as AbstractArrowAccessor).`cryptic$isInGround`()) return true
		}

		if (!hideDeathAnimation.value) return false
		if (entity is ArmorStand) return hideDyingArmorStands.value && standIsOverACorpse(entity)
		return entity is LivingEntity && (!entity.isAlive || entity.health <= 0f)
	}

	/**
	 * A nametag counts as belonging to a corpse when the thing under it is one.
	 * Hypixel stands its tags a block above the mob they name.
	 *
	 * Asked of every armour stand every frame, so it must not search the world
	 * itself: that was one search per nametag per frame, and in a crowd of
	 * mobs — Bal's blazes in the Crystal Hollows — a frame of searches. The
	 * dying mobs are found once a tick in [tick] instead, and there are almost
	 * never any, so this is usually one empty-list check.
	 */
	private fun standIsOverACorpse(stand: ArmorStand): Boolean {
		val corpses = dyingBoxes
		if (corpses.isEmpty()) return false
		val below = stand.boundingBox.move(0.0, -1.0, 0.0)
		return corpses.any { it.intersects(below) }
	}

	/** Where every dying mob is, as of the last tick, for [standIsOverACorpse]. */
	@Volatile
	private var dyingBoxes: List<net.minecraft.world.phys.AABB> = emptyList()

	/** Finds the dying mobs, once a tick, only while the setting that needs them is on. */
	fun tick(client: net.minecraft.client.Minecraft) {
		val level = client.level
		if (level == null || !module.enabled || !hideDeathAnimation.value || !hideDyingArmorStands.value) {
			if (dyingBoxes.isNotEmpty()) dyingBoxes = emptyList()
			return
		}
		var found: MutableList<net.minecraft.world.phys.AABB>? = null
		for (entity in level.entitiesForRendering()) {
			if (entity !is LivingEntity || entity is ArmorStand) continue
			if (entity.isAlive && entity.health > 0f) continue
			(found ?: ArrayList<net.minecraft.world.phys.AABB>().also { found = it }).add(entity.boundingBox)
		}
		dyingBoxes = found ?: emptyList()
	}

	@JvmStatic
	fun hidesPlayerArrows(): Boolean = module.enabled && hidePlayerArrows.value

	@JvmStatic
	fun hidesEntityFire(): Boolean = module.enabled && hideFireOnEntities.value

	@JvmStatic
	fun hidesFireOverlay(): Boolean = module.enabled && hideFireOverlay.value

	/** What the screen's fire is drawn at, where 1 is the game's own. */
	@JvmStatic
	fun fireOverlayAlpha(): Float =
		if (!module.enabled) 1f else (fireOverlayOpacity.value / 100.0).toFloat()
}
