package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.mixin.ContainerScreenAccessor
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.ItemRarity
import imicro.cryptic.skyblock.ParticleTrail
import imicro.cryptic.skyblock.SackContents
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.phys.Vec3
import java.util.Base64
import java.util.Locale

/**
 * The Garden: what your visitors want and what you're short of, the pest a vacuum points at, and
 * lines to the pests in sight.
 */
object Farming {
	private val visitorsSection = SectionModuleSetting("visitors_section", "Visitors")

	@JvmField
	val visitorList = ToggleModuleSetting(
		"visitor_list", "Visitor list", true,
		"Beside menus in the Garden and the Bazaar: everything your visitors want, together, and how many you're short counting " +
			"your inventory and sacks. Click an item to open it on the Bazaar. A visitor is read when you open their menu, and leaves the list once you give to them or refuse them.",
	)

	@JvmField
	val visitorPresets = ToggleModuleSetting(
		"visitor_presets", "Bazaar amounts", true,
		"On a visitor item's buy order or instant buy, two amount presets: what you're short counting your sacks, and counting only your " +
			"inventory, to keep what's in your sacks.",
	)

	private val toolsSection = SectionModuleSetting("tools_section", "Tools")

	@JvmField
	val mousematPresets = ToggleModuleSetting(
		"mousemat_presets", "Mousemat presets", true,
		"Beside a mousemat's sign, each crop's yaw and pitch. Click one to write it into the sign, then press Done.",
	)

	private val pestsSection = SectionModuleSetting("pests_section", "Pests")

	@JvmField
	val vacuumTracer = ToggleModuleSetting("vacuum_tracer", "Line to it", true, "A line from the crosshair to where the vacuum points.")

	@JvmField
	val vacuumSolver = ToggleModuleSetting(
		"vacuum_solver", "Vacuum solver", true,
		"Left-clicking a vacuum sends a trail of particles toward the nearest pest. The trail is followed to its end, " +
			"and that spot is marked with a beam and its distance.",
	).withMore(vacuumTracer)

	@JvmField
	val pestTracers = ToggleModuleSetting(
		"pest_tracers", "Pest tracers", false,
		"Lines to every pest close enough to be seen, and a box around each.",
		restriction = Restriction.SEMI_LEGIT,
	)

	@JvmField
	val module = Module(
		id = "farming",
		name = "Farming",
		description = "Visitors, vacuum and pests",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(visitorsSection, visitorList, visitorPresets, toolsSection, mousematPresets, pestsSection, vacuumSolver, pestTracers),
	)

	private val client: Minecraft get() = Minecraft.getInstance()
	private val FORMATTING = Regex("§.")

	val inGarden: Boolean get() = SkyblockLocation.onSkyblock && SkyblockLocation.area == "Garden"

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private var level: ClientLevel? = null
	private var ticks = 0

	fun tick(client: Minecraft) {
		if (!module.enabled) return
		if (client.level !== level) {
			level = client.level
			clearGuess()
			pests = emptyList()
		}
		val screen = client.gui.screen()
		SackContents.readMenu(screen)
		SackContents.tick()
		if (inGarden) (screen as? AbstractContainerScreen<*>)?.let(::readVisitor)
		val held = client.player?.mainHandItem
		if (inGarden && held != null && SkyblockItem.id(held) in VACUUMS) vacuumHeldAt = System.currentTimeMillis()
		if (++ticks % 5 == 0) {
			if (visitors.isNotEmpty()) SackContents.ensureFromApi()
			pests = if (pestTracers.value && inGarden) findPests() else emptyList()
		}
		tickGuess()
	}

	// ---- Visitors -----------------------------------------------------------------------------

	/** A visitor and what they want, by item name. */
	private class Visitor(val name: String, val wants: Map<String, Int>)

	private val visitors = LinkedHashMap<String, Visitor>()

	/** "Enchanted Hay Bale x64", or just a name for one. */
	private val requirement = Regex("""^(.+?)(?: x([\d,]+))?$""")

	/**
	 * A visitor's menu: their head (with "Times Visited") in the middle of the top, and the accept
	 * button below it listing "Items Required:" down to a blank line.
	 */
	private fun readVisitor(screen: AbstractContainerScreen<*>) {
		val head = screen.menu.slots.getOrNull(13)?.item ?: return
		val headLore = head.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "").trim() } ?: return
		if (headLore.none { it.contains("Times Visited") }) return
		val name = head.hoverName.string.replace(FORMATTING, "").trim()
		if (name in visitors) return
		val accept = screen.menu.slots.getOrNull(29)?.item?.get(DataComponents.LORE)?.lines()
			?.map { it.string.replace(FORMATTING, "").trim() } ?: return
		val wants = LinkedHashMap<String, Int>()
		accept.dropWhile { !it.contains("Items Required") }.drop(1).takeWhile { it.isNotEmpty() }.forEach { line ->
			val match = requirement.find(line) ?: return@forEach
			val amount = match.groupValues[2].replace(",", "").toIntOrNull() ?: 1
			wants.merge(match.groupValues[1].trim(), amount, Int::plus)
		}
		if (wants.isNotEmpty()) visitors[name] = Visitor(name, wants)
	}

	/** A click in a visitor's menu: giving or refusing sends them away. */
	@JvmStatic
	fun onSlotClicked(screen: AbstractContainerScreen<*>, slotId: Int) {
		if (!module.enabled || visitors.isEmpty()) return
		val stack = screen.menu.slots.getOrNull(slotId)?.item ?: return
		val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "").trim() } ?: return
		if (lore.none { it == "Click to give!" || it == "Click to refuse!" }) return
		val name = screen.menu.slots.getOrNull(13)?.item?.hoverName?.string?.replace(FORMATTING, "")?.trim() ?: return
		visitors.remove(name)
	}

	/** One item every visitor wants together, and what you hold of it. */
	private class Wanted(val name: String, val amount: Int, val by: List<String>) {
		val inventory = inventoryCount(name)
		val sacks = SackContents.count(name)
		val short: Int get() = (amount - inventory - sacks).coerceAtLeast(0)
		val shortWithoutSacks: Int get() = (amount - inventory).coerceAtLeast(0)
	}

	private fun wanted(): List<Wanted> {
		val amounts = LinkedHashMap<String, Int>()
		val by = HashMap<String, MutableList<String>>()
		for (visitor in visitors.values) for ((item, amount) in visitor.wants) {
			amounts.merge(item, amount, Int::plus)
			by.getOrPut(item) { ArrayList() } += visitor.name
		}
		return amounts.map { (item, amount) -> Wanted(item, amount, by[item].orEmpty()) }
	}

	private fun inventoryCount(name: String): Int {
		val inventory = client.player?.inventory ?: return 0
		return (0 until inventory.containerSize).sumOf { slot ->
			val stack = inventory.getItem(slot)
			if (!stack.isEmpty && stack.hoverName.string.replace(FORMATTING, "").trim().equals(name, ignoreCase = true)) stack.count else 0
		}
	}

	/**
	 * Amounts for the Bazaar's presets, when [product] is something a visitor wants: what's still
	 * short with your sacks counted, and without them. One button when they agree (nothing in sacks).
	 */
	fun presetsFor(product: String?): List<Pair<String, Int>> {
		if (!module.enabled || !visitorPresets.value || product == null) return emptyList()
		val item = wanted().firstOrNull { it.name.equals(product, ignoreCase = true) } ?: return emptyList()
		if (item.short == item.shortWithoutSacks) return if (item.short > 0) listOf("§aShort" to item.short) else emptyList()
		return buildList {
			if (item.short > 0) add("§aWith sacks" to item.short)
			add("§eWithout sacks" to item.shortWithoutSacks)
		}
	}

	/** Only in a visitor's menu and in the Bazaar's, where it's of use. */
	private fun showsList(screen: Any?): Boolean {
		if (!module.enabled || !visitorList.value || visitors.isEmpty()) return false
		val chest = screen as? AbstractContainerScreen<*> ?: return false
		val title = chest.title.string.replace(FORMATTING, "")
		val bazaar = title.startsWith("Bazaar") || title.contains("➜") || title == "How many do you want?" || title == "How much do you want to pay?"
		return bazaar || (inGarden && isVisitorMenu(chest))
	}

	private fun isVisitorMenu(screen: AbstractContainerScreen<*>): Boolean =
		screen.menu.slots.getOrNull(13)?.item?.get(DataComponents.LORE)?.lines()?.any { it.string.contains("Times Visited") } == true

	/** Where each row of the list was drawn, for clicks and hovering. */
	private class Row(val item: Wanted, val x: Float, val y: Float, val width: Float, val height: Float) {
		fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var rows: List<Row> = emptyList()

	private const val GAP = 12
	private const val MIN_SCALE = 0.5f

	/** The list, left of the menu, shrunk only when there's no room. */
	@JvmStatic
	fun renderList(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (!showsList(screen)) {
			rows = emptyList()
			return
		}
		val accessor = screen as? ContainerScreenAccessor ?: return
		val font = client.font
		val items = wanted()
		val lineHeight = font.lineHeight + 2
		val left = items.map { "§f${it.amount}x ${ItemRarity.codeForName(it.name) ?: "§f"}${it.name}" }
		val right = items.map { if (it.short == 0) "§a✔" else "§c${String.format(Locale.US, "%,d", it.short)} short" }
		val heading = "§a§lVisitors §7(${visitors.size})"
		val width = maxOf(font.width(heading), left.indices.maxOf { font.width(left[it]) + GAP + font.width(right[it]) })
		val height = (items.size + 1) * lineHeight
		val room = (accessor.`cryptic$leftPos`() - 10).toFloat()
		val scale = if (width <= room) 1f else (room / width).coerceAtLeast(MIN_SCALE)
		val x = maxOf(2f, accessor.`cryptic$leftPos`() - 8 - width * scale)
		val y = maxOf(2f, accessor.`cryptic$topPos`().toFloat())

		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		pose.scale(scale, scale)
		graphics.text(font, heading, 0, 0, -1)
		val placed = ArrayList<Row>()
		items.forEachIndexed { i, item ->
			val rowY = (i + 1) * lineHeight
			val row = Row(item, x, y + rowY * scale, width * scale, lineHeight * scale)
			if (row.contains(mouseX.toDouble(), mouseY.toDouble())) graphics.fill(-2, rowY - 1, width + 2, rowY + lineHeight - 1, 0x40FFFFFF)
			graphics.text(font, left[i], 0, rowY, -1)
			graphics.text(font, right[i], width - font.width(right[i]), rowY, -1)
			placed += row
		}
		pose.popMatrix()
		rows = placed

		placed.firstOrNull { it.contains(mouseX.toDouble(), mouseY.toDouble()) }?.let { row ->
			val item = row.item
			graphics.setComponentTooltipForNextFrame(font, listOf(
				Component.literal("${ItemRarity.codeForName(item.name) ?: "§f"}${item.name}"),
				Component.literal("§7Wanted by §f${item.by.joinToString("§7, §f")}"),
				Component.literal("§7Needed: §f${item.amount}"),
				Component.literal("§7Inventory: §f${item.inventory}  §7Sacks: §f${item.sacks}"),
				Component.literal("§7To buy: §e${item.short} §8(§7${item.shortWithoutSacks} keeping your sacks§8)"),
				Component.empty(),
				Component.literal("§eClick to open it on the Bazaar"),
			), mouseX, mouseY)
		}
	}

	/** A click on an item in the list opens it on the Bazaar. True takes it. */
	@JvmStatic
	fun handleMouseClick(mouseX: Double, mouseY: Double): Boolean {
		val row = rows.firstOrNull { it.contains(mouseX, mouseY) } ?: return false
		client.player?.connection?.sendCommand("bz ${row.item.name}")
		return true
	}

	// ---- Mousemat -----------------------------------------------------------------------------

	/** A crop's angles for the mousemat, the ones SkyHanni suggests. */
	private class Angles(val crop: String, val icon: net.minecraft.world.item.Item, val yaw: Float, val pitch: Float)

	private val ANGLES = listOf(
		Angles("Wheat", net.minecraft.world.item.Items.WHEAT, 90f, 0f),
		Angles("Carrot", net.minecraft.world.item.Items.CARROT, -90f, 2.8f),
		Angles("Potato", net.minecraft.world.item.Items.POTATO, -90f, 2.8f),
		Angles("Nether Wart", net.minecraft.world.item.Items.NETHER_WART, 90f, 0f),
		Angles("Pumpkin", net.minecraft.world.item.Items.CARVED_PUMPKIN, 90f, -58.5f),
		Angles("Melon Slice", net.minecraft.world.item.Items.MELON_SLICE, 90f, -58.5f),
		Angles("Cocoa Beans", net.minecraft.world.item.Items.COCOA_BEANS, 180f, -45f),
		Angles("Sugar Cane", net.minecraft.world.item.Items.SUGAR_CANE, -135f, 0f),
		Angles("Cactus", net.minecraft.world.item.Items.CACTUS, -90f, 0f),
		Angles("Mushroom", net.minecraft.world.item.Items.RED_MUSHROOM, 116.5f, 0f),
		Angles("Sunflower", net.minecraft.world.item.Items.SUNFLOWER, 45f, 0f),
		Angles("Moonflower", net.minecraft.world.item.Items.BLUE_ORCHID, 45f, 0f),
		Angles("Wild Rose", net.minecraft.world.item.Items.ROSE_BUSH, 45f, 0f),
	)

	/** A mousemat asks for yaw on its first line and pitch on its last, saying so in between. */
	private fun mousematSign(screen: Any?): net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen? {
		if (!module.enabled || !mousematPresets.value) return null
		val sign = screen as? net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen ?: return null
		val lines = (sign as imicro.cryptic.mixin.SignEditScreenAccessor).`cryptic$messages`()
		return sign.takeIf { lines.getOrNull(1)?.contains("Set Yaw Above") == true && lines.getOrNull(2)?.contains("Set Pitch Below") == true }
	}

	private class AngleRow(val angles: Angles, val x: Float, val y: Float, val width: Float, val height: Float) {
		fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
	}

	private var angleRows: List<AngleRow> = emptyList()

	private fun angleText(angles: Angles) = "§a${angles.crop} §7- §e${angles.yaw}§7/§e${angles.pitch}"

	/** The crops, right of the sign; shrunk when the screen is too small for them. */
	@JvmStatic
	fun renderMousemat(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (mousematSign(screen) == null) {
			angleRows = emptyList()
			return
		}
		val font = client.font
		val rowHeight = 18
		val width = 20 + ANGLES.maxOf { font.width(angleText(it)) }
		val height = rowHeight * ANGLES.size
		val left = screen.width / 2f + 70
		val top = 40f
		val scale = minOf(1f, (screen.height - top - 4) / height, (screen.width - left - 4) / width).coerceAtLeast(0.4f)
		val rows = ANGLES.mapIndexed { i, angles -> AngleRow(angles, left, top + i * rowHeight * scale, width * scale, rowHeight * scale) }
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(left, top)
		pose.scale(scale, scale)
		rows.forEachIndexed { i, row ->
			val y = i * rowHeight
			if (row.contains(mouseX.toDouble(), mouseY.toDouble())) graphics.fill(-2, y, width + 2, y + rowHeight, 0x40FFFFFF)
			graphics.item(net.minecraft.world.item.ItemStack(row.angles.icon), 0, y + 1)
			graphics.text(font, angleText(row.angles), 20, y + 5, -1)
		}
		pose.popMatrix()
		angleRows = rows
	}

	/** A click on a crop writes its yaw and pitch into the sign. True takes the click. */
	@JvmStatic
	fun onMousePressed(): Boolean {
		val sign = mousematSign(client.gui.screen()) ?: return false
		val mouseX = client.mouseHandler.getScaledXPos(client.window)
		val mouseY = client.mouseHandler.getScaledYPos(client.window)
		val row = angleRows.firstOrNull { it.contains(mouseX, mouseY) } ?: return false
		val accessor = sign as imicro.cryptic.mixin.SignEditScreenAccessor
		val lines = accessor.`cryptic$messages`()
		lines[0] = row.angles.yaw.toString()
		lines[3] = row.angles.pitch.toString()
		// The cursor was on a line that may now be shorter.
		accessor.`cryptic$signField`()?.setCursorToEnd()
		client.soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f))
		return true
	}

	// ---- Vacuum solver --------------------------------------------------------------------------

	private val VACUUMS = setOf("SKYMART_VACUUM", "SKYMART_TURBO_VACUUM", "SKYMART_HYPER_VACUUM", "INFINI_VACUUM", "INFINI_VACUUM_HOOVERIUS")

	/** When a vacuum was last in your hand; its trail is only read just after. */
	private var vacuumHeldAt = 0L

	/** A trail being read, one per particle kind Hypixel may draw it with. */
	private val trails = arrayOf(ParticleTrail(), ParticleTrail())

	/** Where the last trail leads, and when it was found. */
	private var guess: Vec3? = null
	private var guessAt = 0L

	private const val HELD_MILLIS = 1_500L
	private const val GUESS_MILLIS = 60_000L
	private const val ARRIVED = 6.0
	private const val MAX_GUESS_DISTANCE = 300.0

	/**
	 * Which trail a particle belongs to: Hypixel's vacuum trail has been drawn with angry villager
	 * particles and with enchanting ones; both are followed. -1 for neither.
	 */
	private fun trailKind(packet: ClientboundLevelParticlesPacket): Int {
		val still = packet.xDist == 0f && packet.yDist == 0f && packet.zDist == 0f
		if (!still) return -1
		return when {
			packet.particle.type == ParticleTypes.ANGRY_VILLAGER && packet.count == 1 && packet.maxSpeed == 0f -> 0
			packet.particle.type == ParticleTypes.ENCHANT && packet.count == 10 && packet.maxSpeed == -2f -> 1
			else -> -1
		}
	}

	/** A particle; on the client thread only (the network thread's call is skipped). */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (!client.isSameThread || !module.enabled || !vacuumSolver.value || !inGarden) return
		val now = System.currentTimeMillis()
		if (now - vacuumHeldAt > HELD_MILLIS) return
		val kind = trailKind(packet)
		if (kind < 0) return
		val player = client.player ?: return
		// The trail aims at the pest's middle, half a block above where it stands.
		val end = trails[kind].offer(Vec3(packet.x, packet.y, packet.z), player.eyePosition, now)?.add(0.0, -0.5, 0.0) ?: return
		if (end.distanceTo(player.position()) > MAX_GUESS_DISTANCE) return
		guess = end
		guessAt = now
	}

	private fun tickGuess() {
		val at = guess ?: return
		val player = client.player ?: return
		val flat = Vec3(at.x - player.x, 0.0, at.z - player.z).length()
		if (flat < ARRIVED || System.currentTimeMillis() - guessAt > GUESS_MILLIS) clearGuess()
	}

	private fun clearGuess() {
		guess = null
		trails.forEach { it.clear() }
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		if (message.string.replace(FORMATTING, "").trim() == "There are not any Pests on your Garden right now! Keep farming!") clearGuess()
	}

	// ---- Pests --------------------------------------------------------------------------------

	/**
	 * The pests' head textures, by the hash in each skin's address. Facts about Hypixel's pests,
	 * as Skyblocker lists them.
	 */
	private val PEST_TEXTURES = setOf(
		"70a1e836bf1968b2eaa4837227a19204f17295d870ee9e754bd6b6d60ddbed3c", // beetle
		"a24c69f96ce556221e195c8ef2bfad71ebf7f95f5ae914a484a8d0ec21672674", // cricket
		"254aff4c0b2dce3a672349cc0ee9e6f3a9deebe4b3556e84611eca250a7821bf", // dragonfly
		"6403ba4027a333d8d2fd32ab59d1cfdbaa7d908d80d2381db2a69cbe65450ad8", // earthworm
		"fd40aa50905235b628e7379eb31fa45cd41b503f09721db3c437fcee39207dfc", // earthworm's tail
		"f379e09252817314bd0b694f7d53b48af2c7fa8499109802a41bb294d2f93e3e", // field mouse
		"4ce79e90adf34718f313ec24d6c6135b69b3788c618498446ccc83ca640c0b14", // firefly
		"3e52782d7f2aaee8af5ba2928fec7885e94879334c2296bc9e7e2dbc5418e58f", // firefly, lit
		"9d90e777826a52461368e26d1b2e19bfa1ba582d602483e545f4124d0f731842", // fly
		"4b24a482a32db1ea78fb98060b0c2fa4a373cbd18a68edddeb7419455a59cda9", // locust
		"bee4fcc5aac27bdbb0be210df08b05bca5aebc89bf2821f608a55fd2cf0434be", // lunar moth
		"be6baf6431a9daa2ca604d5a3c26e9a761d5952f0817174a4fe0b764616e21ff", // mite
		"52a9fe05bc663efcd12e56a3ccc5ec035bf577b78708548b6f4ffcf1d30eccfe", // mosquito
		"65485c4b34e5b5470be94de100e61f7816f81bc5a11dfdf0eccf890172da5d0a", // moth
		"1e04bb6367caa4e88f5fd0ee80f0745d137a6060223dbbc42a16471fdf64bb83", // praying mantis
		"a8abb471db0ab78703011979dc8b40798a941f3a4dec3ec61cbeec2af8cffe8", // rat
		"7a79d0fd677b54530961117ef84adc206e2cc5045c1344d61d776bf8ac2fe1ba", // slug
	)

	private val TEXTURE_HASH = Regex("""texture/([0-9a-f]+)""")

	/** Whether a head texture is a pest's, by the base64 Hypixel sends; remembered per texture. */
	private val pestTexture = HashMap<String, Boolean>()

	private var pests: List<Vec3> = emptyList()

	private fun findPests(): List<Vec3> {
		val level = client.level ?: return emptyList()
		return level.entitiesForRendering().mapNotNull { entity ->
			val stand = entity as? ArmorStand ?: return@mapNotNull null
			val texture = SkyblockItem.skullTexture(stand.getItemBySlot(EquipmentSlot.HEAD)) ?: return@mapNotNull null
			val pest = pestTexture.getOrPut(texture) {
				runCatching { String(Base64.getDecoder().decode(texture)) }.getOrNull()
					?.let { TEXTURE_HASH.find(it)?.groupValues?.get(1) } in PEST_TEXTURES
			}
			if (pest) stand.eyePosition else null
		}
	}

	// ---- Drawing ------------------------------------------------------------------------------

	private const val PEST_COLOR = 0xFFB62F00.toInt()
	private const val GUESS_COLOR = 0xFFFF5555.toInt()
	private const val BEAM_HEIGHT = 40.0

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !inGarden) return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		for (pest in pests) {
			WorldRender.drawBox(
				poseStack, collector, pest.x - 0.35, pest.y - 0.35, pest.z - 0.35, pest.x + 0.35, pest.y + 0.35, pest.z + 0.35,
				outlineArgb = PEST_COLOR, fillArgb = 0x40000000 or (PEST_COLOR and 0xFFFFFF), outline = true, fill = true, phase = true, lineWidth = 2f,
			)
			WorldRender.drawTracer(poseStack, collector, pest.x, pest.y, pest.z, PEST_COLOR, 2f, phase = true)
		}
		val at = guess ?: return
		val player = client.player ?: return
		WorldRender.drawBox(
			poseStack, collector, at.x - 0.15, at.y, at.z - 0.15, at.x + 0.15, at.y + BEAM_HEIGHT, at.z + 0.15,
			outlineArgb = 0, fillArgb = 0x60000000 or (GUESS_COLOR and 0xFFFFFF), outline = false, fill = true, phase = true, lineWidth = 1f,
		)
		WorldRender.drawBox(
			poseStack, collector, at.x - 0.5, at.y - 0.5, at.z - 0.5, at.x + 0.5, at.y + 0.5, at.z + 0.5,
			outlineArgb = GUESS_COLOR, fillArgb = 0x30000000 or (GUESS_COLOR and 0xFFFFFF), outline = true, fill = true, phase = true, lineWidth = 2f,
		)
		if (vacuumTracer.value) WorldRender.drawTracer(poseStack, collector, at.x, at.y, at.z, GUESS_COLOR, 2f, phase = true)
		val distance = player.eyePosition.distanceTo(at)
		WorldRender.drawText(
			poseStack = poseStack,
			collector = collector,
			orientation = client.gameRenderer.mainCamera().rotation(),
			text = Component.literal("§cPest §e${String.format(Locale.US, "%.0f", distance)}m"),
			x = at.x, y = at.y + 1.2, z = at.z,
			scale = (distance / 12.0).coerceIn(1.0, 6.0).toFloat(),
			seeThrough = true,
			centered = true,
		)
	}

	fun describe(): List<String> = listOf(
		"Farming: in the Garden ${inGarden}, ${visitors.size} visitors (${visitors.keys.joinToString()})",
		"Vacuum: guess ${guess?.let { "%.1f %.1f %.1f".format(it.x, it.y, it.z) } ?: "none"}, trails ${trails.joinToString { it.size.toString() }}",
		"Pests in sight: ${pests.size}",
		SackContents.describe(),
	)
}
