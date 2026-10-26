package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.roundToInt

/**
 * Hoppity's Chocolate Factory: the factory menu worked for you (the cookie clicked, strays caught,
 * the Time Tower used, the best upgrade bought), the eating sound muted, and the hunt's eggs shown
 * where they hide.
 *
 * Ported from OdinLegacy's Chocolate Factory (BSD-3); the upgrade's worth counting the Coach and the
 * Time Tower, and the Time Tower itself, follow quoi's version of it (the ideas only, GPL-3.0).
 * Semi-legit as a whole.
 */
object ChocolateFactory {
	private const val MENU = "Chocolate Factory"
	private const val SOURCE = "Chocolate Factory"

	/** The menu's slots: the cookie, the seven rabbits, the Time Tower, the Coach, the multiplier. */
	private const val COOKIE = 13
	private const val TIME_TOWER = 39
	private const val COACH = 42
	private const val MULTIPLIER = 45
	private val RABBITS = 28..34

	/** What the Time Tower adds while on, and how much of the time it's on (an hour per eight). */
	private const val TOWER_BOOST = 0.1
	private const val TOWER_SHARE = 1.0 / 8

	/** What one Coach level adds to the multiplier. */
	private const val COACH_BOOST = 0.01

	private val menuSection = SectionModuleSetting("menu_section", "Factory menu")

	@JvmField
	val clickCookie = ToggleModuleSetting(
		id = "click_cookie",
		label = "Click the cookie",
		defaultValue = false,
		description = "Clicks the big cookie while the factory menu is open.",
	)

	@JvmField
	val claimStrays = ToggleModuleSetting(
		id = "claim_strays",
		label = "Catch stray rabbits",
		defaultValue = false,
		description = "Clicks a stray or golden rabbit as soon as it turns up in the menu.",
	)

	@JvmField
	val timeTower = ToggleModuleSetting(
		id = "time_tower",
		label = "Use the Time Tower",
		defaultValue = false,
		description = "Turns the Time Tower on whenever it has a charge and isn't running.",
	)

	@JvmField
	val clickDelay = SliderModuleSetting(
		id = "click_delay",
		label = "Click delay",
		unit = "ms",
		defaultValue = 150.0,
		min = 50.0,
		max = 1500.0,
		step = 5.0,
		description = "Time between the menu clicks above.",
		visibleIf = { clickCookie.value || claimStrays.value || timeTower.value },
	)

	@JvmField
	val autoUpgrade = ToggleModuleSetting(
		id = "auto_upgrade",
		label = "Buy the best upgrade",
		defaultValue = false,
		description = "Buys whichever rabbit, Coach or Time Tower level pays back soonest, once there's chocolate for it.",
	)

	@JvmField
	val upgradeDelay = SliderModuleSetting(
		id = "upgrade_delay",
		label = "Upgrade delay",
		unit = "ms",
		defaultValue = 500.0,
		min = 50.0,
		max = 2000.0,
		step = 50.0,
		description = "Time between upgrades.",
		visibleIf = { autoUpgrade.value },
	)

	@JvmField
	val muteEating = ToggleModuleSetting(
		id = "mute_eating",
		label = "Mute eating",
		defaultValue = false,
		description = "No eating sound while the factory menu is open.",
	)

	private val eggSection = SectionModuleSetting("egg_section", "Hoppity's Hunt")

	@JvmField
	val eggWaypoints = ToggleModuleSetting(
		id = "egg_waypoints",
		label = "Egg waypoints",
		defaultValue = false,
		description = "Boxes and names each hidden chocolate egg in sight, through walls, until you find it.",
	)

	@JvmField
	val module = Module(
		id = "chocolate_factory",
		restriction = Restriction.SEMI_LEGIT,
		name = "Chocolate Factory",
		description = "Hoppity's factory and eggs",
		category = ModuleCategory.GENERAL,
		settings = listOf(
			menuSection,
			clickCookie, claimStrays, timeTower, clickDelay,
			autoUpgrade.withMore(upgradeDelay),
			muteEating,
			eggSection,
			eggWaypoints,
		),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			eggs.clear()
			found.clear()
		}
	}

	val needsSkyblock: Boolean get() = module.enabled && eggWaypoints.value

	private var lastClickAt = 0L
	private var lastUpgradeAt = 0L
	private var ticks = 0

	private fun factory(client: Minecraft): AbstractContainerScreen<*>? =
		(client.gui.screen() as? AbstractContainerScreen<*>)?.takeIf { it.title.string == MENU }

	fun tick(client: Minecraft) {
		if (!module.enabled) return
		val now = System.currentTimeMillis()
		factory(client)?.let { screen ->
			if (now - lastClickAt >= clickDelay.value) {
				lastClickAt = now
				clickInMenu(client, screen)
			}
			if (autoUpgrade.value && now - lastUpgradeAt >= upgradeDelay.value) {
				lastUpgradeAt = now
				upgrade(client, screen)
			}
		}
		if (eggWaypoints.value && ++ticks % EGG_SCAN_TICKS == 0) scanEggs(client)
	}

	private fun click(client: Minecraft, screen: AbstractContainerScreen<*>, slot: Int, button: Int, input: ContainerInput) {
		val player = client.player ?: return
		client.gameMode?.handleContainerInput(screen.menu.containerId, slot, button, input, player)
	}

	/** One menu action per turn: the Time Tower first, then the cookie, then a stray. */
	private fun clickInMenu(client: Minecraft, screen: AbstractContainerScreen<*>) {
		val menu = screen.menu
		if (timeTower.value && towerReady(menu.getSlot(TIME_TOWER).item)) {
			click(client, screen, TIME_TOWER, 1, ContainerInput.PICKUP)
			return
		}
		if (clickCookie.value) click(client, screen, COOKIE, 1, ContainerInput.PICKUP)
		if (!claimStrays.value) return
		val stray = menu.slots.firstOrNull { slot ->
			val name = slot.item.hoverName.string
			name.contains("CLICK ME!") || name.contains("Golden Rabbit")
		} ?: return
		click(client, screen, stray.index, 0, ContainerInput.PICKUP)
	}

	private val PER_SECOND = Regex("""([\d.,]+)\s+per second""")
	private val TOTAL_MULTIPLIER = Regex("""Total Multiplier:\s+([\d.]+)x""")
	private val TOWER_STATUS = Regex("""Status:\s+(ACTIVE|INACTIVE)""")
	private val TOWER_CHARGES = Regex("""Charges:\s*(\d+)\s*/\s*\d+""")
	private val FORMATTING = Regex("§.")

	private fun lore(stack: ItemStack): List<String> =
		stack.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "") } ?: emptyList()

	private fun towerReady(stack: ItemStack): Boolean {
		val lines = lore(stack)
		val charges = lines.firstNotNullOfOrNull { TOWER_CHARGES.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return false
		val active = lines.any { TOWER_STATUS.find(it)?.groupValues?.get(1) == "ACTIVE" }
		return charges > 0 && !active
	}

	/** What an upgrade costs: the line after "Cost", in chocolate. */
	private fun cost(stack: ItemStack): Long? {
		val lines = lore(stack)
		val at = lines.indexOfFirst { it.contains("Cost") }.takeIf { it >= 0 } ?: return null
		return lines.getOrNull(at + 1)?.replace(Regex("""\D"""), "")?.toLongOrNull()
	}

	/**
	 * Buys the upgrade that pays for itself soonest: its cost over the chocolate a second it adds. A
	 * rabbit adds its number in raw chocolate (Bro one, Dog seven), the Coach a hundredth to the
	 * multiplier, and the Time Tower a tenth to it, on an hour in eight.
	 */
	private fun upgrade(client: Minecraft, screen: AbstractContainerScreen<*>) {
		val menu = screen.menu
		val chocolate = menu.getSlot(COOKIE).item.hoverName.string.replace(Regex("""\D"""), "").toLongOrNull() ?: return
		val best = bestUpgrade(menu) ?: return
		if (chocolate >= best.second) click(client, screen, best.first, 2, ContainerInput.CLONE)
	}

	private fun bestUpgrade(menu: AbstractContainerMenu): Pair<Int, Long>? {
		val perSecond = lore(menu.getSlot(COOKIE).item).firstNotNullOfOrNull {
			PER_SECOND.find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
		} ?: return null
		val multiplier = lore(menu.getSlot(MULTIPLIER).item).firstNotNullOfOrNull {
			TOTAL_MULTIPLIER.find(it)?.groupValues?.get(1)?.toDoubleOrNull()
		}?.takeIf { it > 0 } ?: return null
		val raw = perSecond / multiplier

		val options = ArrayList<Triple<Int, Long, Double>>()
		for ((index, slot) in RABBITS.withIndex()) {
			val cost = cost(menu.getSlot(slot).item) ?: continue
			options += Triple(slot, cost, (index + 1) * multiplier)
		}
		cost(menu.getSlot(COACH).item)?.let { options += Triple(COACH, it, raw * COACH_BOOST) }
		cost(menu.getSlot(TIME_TOWER).item)?.let { options += Triple(TIME_TOWER, it, raw * TOWER_BOOST * TOWER_SHARE) }
		return options.filter { it.third > 0 }.minByOrNull { it.second / it.third }?.let { it.first to it.second }
	}

	/** The eating sound, muted while the factory menu is open. From the sound manager's mixin. */
	@JvmStatic
	fun mutes(sound: SoundInstance): Boolean {
		if (!module.enabled || !muteEating.value) return false
		val client = Minecraft.getInstance()
		return factory(client) != null && sound.identifier.path == "entity.generic.eat"
	}

	// ---- Hoppity's eggs -----------------------------------------------------------------------

	/** The hunt's three egg heads, by their skins' ids; the later meals reuse them. */
	private enum class Meal(val skin: String, val colour: String) {
		BREAKFAST("a49333d85b8a315d0336eb2df37d8a714ca24c51b8c6074f1b5b927deb516c24", "§6"),
		LUNCH("7ae6d2d31d8167bcaf95293b68a4acd872d66e751db5a34f2cbc6766a0356d0a", "§9"),
		DINNER("e5e36165819fd2850f98552edcd763ff986313119283c126ace0c4cc495e76a8", "§a"),
	}

	/** Each chat meal name and the head it uses. */
	private val MEALS = mapOf(
		"Breakfast" to Meal.BREAKFAST, "Brunch" to Meal.BREAKFAST,
		"Lunch" to Meal.LUNCH, "Déjeuner" to Meal.LUNCH,
		"Dinner" to Meal.DINNER, "Supper" to Meal.DINNER,
	)

	private class Egg(val stand: ArmorStand, val meal: Meal)

	private val eggs = HashMap<Int, Egg>()

	/** Eggs found, by their stand's id, so they stop showing. */
	private val found = HashSet<Int>()

	private val skinsSeen = HashMap<String, Meal?>()

	private const val EGG_SCAN_TICKS = 20

	private fun mealOf(stand: ArmorStand): Meal? {
		val head = stand.getItemBySlot(EquipmentSlot.HEAD)
		if (!head.`is`(Items.PLAYER_HEAD)) return null
		val texture = SkyblockItem.skullTexture(head) ?: return null
		return skinsSeen.getOrPut(texture) {
			val decoded = runCatching { String(java.util.Base64.getDecoder().decode(texture)) }.getOrDefault("")
			Meal.entries.firstOrNull { it.skin in decoded }
		}
	}

	private fun scanEggs(client: Minecraft) {
		val level = client.level ?: return
		if (!SkyblockLocation.onSkyblock) return
		eggs.values.removeIf { it.stand.isRemoved }
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand || entity.id in eggs) continue
			val meal = mealOf(entity) ?: continue
			eggs[entity.id] = Egg(entity, meal)
		}
	}

	private val FOUND = Regex("""(?:found a|collected this) Chocolate (\S+) Egg""")

	/** "You found a Chocolate Lunch Egg": the nearest egg of that kind is taken off. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled || !eggWaypoints.value) return
		val meal = FOUND.find(message.string.replace(FORMATTING, ""))?.groupValues?.get(1)?.let(MEALS::get) ?: return
		val player = Minecraft.getInstance().player ?: return
		eggs.values.filter { it.meal == meal && it.stand.id !in found }
			.minByOrNull { it.stand.distanceToSqr(player) }
			?.let { found += it.stand.id }
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !eggWaypoints.value || eggs.isEmpty()) return
		val client = Minecraft.getInstance()
		val eye = client.player?.eyePosition ?: return
		val orientation = client.gameRenderer.mainCamera().rotation()
		for (egg in eggs.values) {
			if (egg.stand.id in found || egg.stand.isRemoved) continue
			// The head sits at the top of the stand.
			val head = egg.stand.position().add(0.0, 1.75, 0.0)
			WorldRender.drawBox(
				poseStack = context.poseStack(), collector = context.submitNodeCollector(),
				minX = head.x - 0.3, minY = head.y - 0.3, minZ = head.z - 0.3,
				maxX = head.x + 0.3, maxY = head.y + 0.3, maxZ = head.z + 0.3,
				outlineArgb = 0xFFFFFFFF.toInt(), fillArgb = 0, outline = true, fill = false, phase = true, lineWidth = 2f,
			)
			val distance = eye.distanceTo(head)
			val name = egg.meal.name.lowercase().replaceFirstChar { it.uppercase() }
			WorldRender.drawText(
				poseStack = context.poseStack(), collector = context.submitNodeCollector(), orientation = orientation,
				text = Component.literal("${egg.meal.colour}$name Egg §7${distance.roundToInt()}m"),
				x = head.x, y = head.y + 0.7, z = head.z,
				scale = (distance / 10.0).coerceIn(1.0, 20.0).toFloat(), seeThrough = true, centered = true,
			)
		}
	}
}
