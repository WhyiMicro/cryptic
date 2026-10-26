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
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Display
import net.minecraft.world.inventory.Slot
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SoundType
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
 * Stops drawing things a dungeon run doesn't need. Mostly works on packets (an entity never added
 * costs nothing); the rest is decided as render states are built. Everything is off by default.
 * From Odin's and NoammAddons' Render Optimizer, with ideas from NoFrills' No Render.
 */
object RenderOptimizer {
	/** Hypixel's skull textures, the only way to identify these armour stands. Odin's three. */
	private const val HEALER_FAIRY_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTQ2MzA5MTA0NywKICAicHJvZmlsZUlkIiA6ICIyNjRkYzBlYjVlZGI0ZmI3OTgxNWIyZGY1NGY0OTgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJxdWludHVwbGV0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlZWRjZmZjNmExMWEzODM0YTI4ODQ5Y2MzMTZhZjdhMjc1MmEzNzZkNTM2Y2Y4NDAzOWNmNzkxMDhiMTY3YWUiCiAgICB9CiAgfQp9"

	private const val SOUL_WEAVER_TEXTURE =
		"eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ=="

	private const val TENTACLE_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTg1NzI3NzI0OSwKICAicHJvZmlsZUlkIiA6ICIxODA1Y2E2MmM0ZDI0M2NiOWQxYmY4YmM5N2E1YjgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJSdWxsZWQiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdkODM2NzQ5MjZiODk3MTRlNmI1YTU1NDcwNTAxYzA0YjA2NmRkODdiZjZjMzM1Y2RkYzZlNjBhMWExYTVmNSIKICAgIH0KICB9Cn0="

	/**
	 * Zero-health nametags in Hypixel's two formats (with a level and fraction, or bare).
	 * NoammAddons' patterns.
	 */
	private val zeroHealthPatterns = listOf(
		Regex("^§.\\[§.Lv\\d+§.] §.+ (?:§.)+0§f/.+§c❤$"),
		Regex("^.+ (?:§.)+0§c❤$"),
	)

	private val entitiesSection = SectionModuleSetting("entities_section", "Entities")
	private val abilitiesSection = SectionModuleSetting("abilities_section", "Abilities")
	private val fireSection = SectionModuleSetting("fire_section", "Fire")
	private val worldSection = SectionModuleSetting("world_section", "World")
	private val screenSection = SectionModuleSetting("screen_section", "Screen")

	@JvmField
	val hideDeathPuffs = ToggleModuleSetting(
		id = "hide_death_puffs",
		label = "Hide death puffs",
		description = "Hides the little cloud a mob leaves when it dies.",
	)

	@JvmField
	val hideBreakParticles = ToggleModuleSetting(
		id = "hide_break_particles",
		label = "Hide block break particles",
		description = "Hides the bits a block throws while it's mined and when it breaks.",
	)

	@JvmField
	val hideTreeBits = ToggleModuleSetting(
		id = "hide_tree_bits",
		label = "Hide tree bits",
		description = "Hides the wood and leaf pieces a chopped tree drops on Galatea, the Hub and the Park.",
	)

	@JvmField
	val hideVignette = ToggleModuleSetting(
		id = "hide_vignette",
		label = "Hide vignette",
		description = "Hides the dark edges of the screen.",
	)

	@JvmField
	val hideEffectIcons = ToggleModuleSetting(
		id = "hide_effect_icons",
		label = "Hide effect icons",
		description = "Hides the potion effect icons in the top right corner.",
	)

	@JvmField
	val hideEmptyTooltips = ToggleModuleSetting(
		id = "hide_empty_tooltips",
		label = "Hide empty tooltips",
		description = "Shows no tooltip over items without a name, such as the glass panes filling SkyBlock menus.",
	)

	@JvmField
	val hideBossBar = ToggleModuleSetting(
		id = "hide_boss_bar",
		label = "Hide boss bar",
		description = "Hides the boss health bar at the top of the screen. Features reading it keep working.",
	)

	@JvmField
	val hideCheapCoins = ToggleModuleSetting(
		id = "hide_cheap_coins",
		label = "Hide cheap coins",
		description = "Hides the small coin piles mobs drop on the ground.",
	)

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
		description = "Skips drawing run clutter",
		category = ModuleCategory.VISUAL,
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
			hideDeathPuffs,
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
			worldSection,
			hideBreakParticles,
			hideTreeBits,
			hideCheapCoins,
			screenSection,
			hideVignette,
			hideEffectIcons,
			hideEmptyTooltips,
			hideBossBar,
		),
	)

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

	/** Read on the network thread first; must not throw. */
	@JvmStatic
	fun blocksParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		if (!module.enabled) return false
		val type = packet.particle.type
		if (hideExplosionParticles.value && (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER)) return true
		return hideDeathPuffs.value && type == ParticleTypes.POOF && isDeathPuff(packet)
	}

	/** One puff, still, nudged by a hair on every axis: a death, not a spell's cloud. */
	private fun isDeathPuff(packet: ClientboundLevelParticlesPacket): Boolean {
		if (packet.count != 1 || packet.maxSpeed != 0f) return false
		return listOf(packet.xDist, packet.yDist, packet.zDist).all { it != 0f && it > -0.1f && it < 0.1f }
	}

	val needsSkyblock: Boolean get() = module.enabled && hideTreeBits.value

	private val TREE_AREAS = setOf("Galatea", "Hub", "The Park")

	private fun isTreeBit(entity: Display.BlockDisplay): Boolean {
		if (SkyblockLocation.area !in TREE_AREAS) return false
		val state = entity.blockState
		val block = state.block
		if (block is LeavesBlock) return true
		if (block !is RotatedPillarBlock) return false
		val sound = state.soundType
		return sound == SoundType.WOOD || sound == SoundType.CHERRY_WOOD || sound == SoundType.BAMBOO_WOOD || sound == SoundType.NETHER_WOOD
	}

	@JvmStatic
	fun hidesBreakParticles(): Boolean = module.enabled && hideBreakParticles.value

	@JvmStatic
	fun hidesVignette(): Boolean = module.enabled && hideVignette.value

	@JvmStatic
	fun hidesEffectIcons(): Boolean = module.enabled && hideEffectIcons.value

	@JvmStatic
	fun hidesBossBar(): Boolean = module.enabled && hideBossBar.value

	/**
	 * The two coin-pile heads Hypixel drops (the textures SkyHanni and Skytils know as COINS_1 and
	 * COINS_2), matched by their skin's id so a re-signed texture still counts.
	 */
	private val COIN_SKINS = listOf(
		"16b90f4fa3ec106bfef21f3b75f541a18e4757674f7d58250fa7e74952f087dc",
		"ccbcce22af559ed6ba6038845db38ccbca2e62b77b87a26a06657109ce0efba6",
	)

	/** Whether each texture seen is a coin pile, so each is decoded once. */
	private val coinTextures = HashMap<String, Boolean>()

	private fun isCheapCoin(entity: net.minecraft.world.entity.item.ItemEntity): Boolean {
		val item = entity.item
		if (item.item != Items.PLAYER_HEAD) return false
		val texture = skullTexture(item) ?: return false
		return coinTextures.getOrPut(texture) {
			val decoded = runCatching { String(java.util.Base64.getDecoder().decode(texture)) }.getOrDefault("")
			COIN_SKINS.any { it in decoded }
		}
	}

	/** Whether the hovered [slot]'s tooltip is an empty box. */
	@JvmStatic
	fun hidesTooltip(slot: Slot?): Boolean {
		if (!module.enabled || !hideEmptyTooltips.value || slot == null || !slot.hasItem()) return false
		return slot.item.hoverName.string.isBlank()
	}

	/**
	 * Whether the entity carrying this nametag should go: dead mobs, and decorative orbs and
	 * fairies, are recognised by their tag.
	 */
	@JvmStatic
	fun hidesNameTag(name: Component): Boolean {
		if (!module.enabled || !hideZeroHealth.value) return false
		val text = name.string
		// Checked on every nametag update, so anything not ending on a zero and a heart is turned
		// away before the patterns.
		if (!text.endsWith("§c❤") || !text.contains("0§")) return false
		return zeroHealthPatterns.any { it.matches(text) }
	}

	/** The archer passive is a floating item, read off the item it holds. */
	@JvmStatic
	fun hidesHeldItem(item: ItemStack): Boolean {
		if (!module.enabled || !hideArcherPassive.value) return false
		if (!DungeonLocation.inDungeon) return false
		return !item.isEmpty && item.item == Items.BONE_MEAL
	}

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

	/** Removes an entity on the client thread, where that's safe. */
	@JvmStatic
	fun discard(entityId: Int) {
		val client = Minecraft.getInstance()
		client.execute { client.level?.removeEntity(entityId, Entity.RemovalReason.DISCARDED) }
	}

	/**
	 * Whether [entity] should be left undrawn. Dead mobs linger for their topple animation; their
	 * nametag stand is judged separately, so the body can go while the tag stays.
	 */
	@JvmStatic
	fun hidesEntity(entity: Entity): Boolean {
		if (!module.enabled) return false

		if (hideTreeBits.value && entity is Display.BlockDisplay && isTreeBit(entity)) return true
		if (hideCheapCoins.value && entity is net.minecraft.world.entity.item.ItemEntity && isCheapCoin(entity)) return true

		// Landed arrows are scenery, and fast bows leave a floor full of them.
		if (hideGroundArrows.value && entity is AbstractArrow) {
			if ((entity as AbstractArrowAccessor).`cryptic$isInGround`()) return true
		}

		if (!hideDeathAnimation.value) return false
		if (entity is ArmorStand) return hideDyingArmorStands.value && standIsOverACorpse(entity)
		return entity is LivingEntity && (!entity.isAlive || entity.health <= 0f)
	}

	/**
	 * A nametag belongs to a corpse when the mob a block under it is one. Asked per stand per
	 * frame, so dying mobs are found once a tick in [tick] instead of searching here.
	 */
	private fun standIsOverACorpse(stand: ArmorStand): Boolean {
		val corpses = dyingBoxes
		if (corpses.isEmpty()) return false
		val below = stand.boundingBox.move(0.0, -1.0, 0.0)
		return corpses.any { it.intersects(below) }
	}

	@Volatile
	private var dyingBoxes: List<net.minecraft.world.phys.AABB> = emptyList()

	/** Finds dying mobs once a tick, only while the setting needing them is on. */
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

	/** The screen fire's strength, 1 being the game's own. */
	@JvmStatic
	fun fireOverlayAlpha(): Float =
		if (!module.enabled) 1f else (fireOverlayOpacity.value / 100.0).toFloat()
}
