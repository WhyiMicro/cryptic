package imicro.cryptic.feature

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * What saved your life, and when it can do it again.
 *
 * Ported from Odin's Invincibility Timer (BSD 3-Clause, Copyright (c) 2025
 * odtheking), row for row: each thing that takes a death for you gets its own
 * picture, a tick while it is ready and a count of seconds while it is not,
 * and the one on your head is marked. Three things in SkyBlock do it — the
 * Spirit Mask, Bonzo's Mask and the Phoenix pet — and each says so once, in a
 * line of chat that scrolls away in a boss fight. What matters afterwards is
 * the part nothing tells you: how long you are invincible for, and how long
 * until the thing can save you again.
 *
 * Bonzo's cooldown is read off the helmet itself when it is worn, because the
 * mask's own lore carries the number and an upgraded one is quicker than the
 * base three minutes.
 */
object InvincibilityTimer {
	/** Ticks of invincibility each one gives, and the cooldown it takes. */
	private enum class Charm(
		val label: String,
		val pattern: Regex,
		val invincibleTicks: Int,
		val cooldownSeconds: Int,
		/** The head Hypixel draws the thing as, which is its picture here. */
		private val textureHash: String,
		/** What the item is called in SkyBlock, so a worn one can be spotted. */
		val itemIds: Set<String>,
	) {
		SPIRIT(
			"Spirit",
			Regex("""^Second Wind Activated! Your Spirit Mask saved your life!$"""),
			60,
			30,
			"9bbe721d7ad8ab965f08cbec0b834f779b5197f79da4aea3d13d253ece9dec2",
			setOf("SPIRIT_MASK", "STARRED_SPIRIT_MASK"),
		),
		BONZO(
			"Bonzo",
			Regex("""^Your (?:. )?Bonzo's Mask saved your life!$"""),
			60,
			180,
			"12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c",
			setOf("BONZO_MASK", "STARRED_BONZO_MASK"),
		),
		PHOENIX(
			"Phoenix",
			Regex("""^Your Phoenix Pet saved you from certain death!$"""),
			80,
			60,
			"66b1b59bc890c9c97527787dde20600c8b86f6b9912d51a6bfcdb0e4c2aa3c97",
			emptySet(),
		);

		/** Built once, on first use: a head is a profile, and a profile is not free. */
		val icon: ItemStack by lazy { skullOf(textureHash) }

		var invincibleFor = 0
			private set
		var cooldownFor = 0
			private set

		fun proc(seconds: Int?) {
			invincibleFor = invincibleTicks
			cooldownFor = (seconds ?: cooldownSeconds) * 20
		}

		fun tick() {
			if (cooldownFor > 0) cooldownFor--
			if (invincibleFor > 0) invincibleFor--
		}

		fun forget() {
			invincibleFor = 0
			cooldownFor = 0
		}
	}

	@JvmField
	val announce = ToggleModuleSetting(
		id = "announce",
		label = "Announce in chat",
		defaultValue = true,
		description = "Tells the party which mask went, and how many are left.",
	)

	@JvmField
	val playSound = ToggleModuleSetting(
		id = "play_sound",
		label = "Play a sound",
		defaultValue = true,
		description = "A sound the moment something saves you.",
	)

	// Odin's sound by default: the same pling Cryptic always played, but at the
	// pitch Odin plays it at, which is its natural one — Cryptic's was raised to
	// 1.4, a sound nobody coming from Odin had heard on a proc.
	@JvmField
	val sound = DropdownModuleSetting(
		id = "sound",
		label = "Sound",
		options = SoundChoices.labels,
		defaultIndex = SoundChoices.indexOf("Pling"),
		visibleIf = { playSound.value },
	)

	@JvmField
	val volume = SliderModuleSetting(
		id = "volume",
		label = "Volume",
		defaultValue = 1.0,
		min = 0.0,
		max = 1.0,
		step = 0.1,
		visibleIf = { playSound.value },
	)

	@JvmField
	val pitch = SliderModuleSetting(
		id = "pitch",
		label = "Pitch",
		defaultValue = 1.0,
		min = 0.5,
		max = 2.0,
		step = 0.1,
		visibleIf = { playSound.value },
	)

	@JvmField
	val previewSound = ButtonModuleSetting(
		id = "preview_sound",
		label = "Play sound",
		action = { playProcSound() },
		visibleIf = { playSound.value },
	)

	@JvmField
	val procTitle = ToggleModuleSetting(
		id = "proc_title",
		label = "Title on proc",
		defaultValue = true,
		description = "Says which one saved you — Spirit, Bonzo or Phoenix — across the middle of the screen.",
	)

	@JvmField
	val onlyInDungeons = ToggleModuleSetting(
		id = "only_in_dungeons",
		label = "Only in dungeons",
		defaultValue = true,
		description = "Ignores a mask going anywhere else.",
	)

	private val shownSection = SectionModuleSetting("shown_section", "Shown")

	@JvmField
	val showSpirit = ToggleModuleSetting(
		id = "show_spirit",
		label = "Spirit Mask",
		defaultValue = true,
	)

	@JvmField
	val showBonzo = ToggleModuleSetting(
		id = "show_bonzo",
		label = "Bonzo's Mask",
		defaultValue = true,
	)

	@JvmField
	val showPhoenix = ToggleModuleSetting(
		id = "show_phoenix",
		label = "Phoenix Pet",
		defaultValue = true,
	)

	private val hudSection = SectionModuleSetting("hud_section", "Display")

	@JvmField
	val showIcons = ToggleModuleSetting(
		id = "show_icons",
		label = "Show pictures",
		defaultValue = true,
		description = "Draws each mask, rather than writing its name.",
	)

	@JvmField
	val showWhen = DropdownModuleSetting(
		id = "show_when",
		label = "Show",
		options = listOf("Always", "Active or cooling", "Only while active", "Only on cooldown"),
		defaultIndex = 0,
		description = "When a row is worth the space it takes.",
	)

	@JvmField
	val onlyInBoss = ToggleModuleSetting(
		id = "only_in_boss",
		label = "Only in boss",
		description = "Keeps the rows off the screen until the boss fight.",
	)

	@JvmField
	val readyColor = ColorModuleSetting(
		id = "ready_color",
		label = "Ready",
		defaultRgb = 0x55FF55,
	)

	@JvmField
	val activeColor = ColorModuleSetting(
		id = "active_color",
		label = "Invincible",
		defaultRgb = 0xFFAA00,
	)

	@JvmField
	val cooldownColor = ColorModuleSetting(
		id = "cooldown_color",
		label = "On cooldown",
		defaultRgb = 0xFF5555,
	)

	@JvmField
	val equippedColor = ColorModuleSetting(
		id = "equipped_color",
		label = "Equipped mask",
		defaultRgb = 0xAA00AA,
		description = "Marks the mask you are actually wearing.",
	)

	private val itemSection = SectionModuleSetting("item_section", "On the item")

	@JvmField
	val showOnItem = ToggleModuleSetting(
		id = "show_on_item",
		label = "Show on the item",
		description = "Draws the cooldown over the mask in your inventory.",
	)

	@JvmField
	val asDurability = ToggleModuleSetting(
		id = "as_durability",
		label = "As a durability bar",
		description = "A bar under the mask instead of a shade rising up it.",
		visibleIf = { showOnItem.value },
	)

	@JvmField
	val itemColor = ColorModuleSetting(
		id = "item_color",
		label = "Item cooldown",
		defaultRgb = 0x262626,
		supportsAlpha = true,
		defaultAlpha = 0xA0,
		visibleIf = { showOnItem.value },
	)

	@JvmField
	val module = Module(
		id = "invincibility_timer",
		name = "Invincibility Timer",
		description = "Times the masks that save you from a death",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			announce,
			playSound,
			sound,
			volume,
			pitch,
			previewSound,
			procTitle,
			onlyInDungeons,
			shownSection,
			showSpirit,
			showBonzo,
			showPhoenix,
			hudSection,
			showIcons,
			showWhen,
			onlyInBoss,
			readyColor,
			activeColor,
			cooldownColor,
			equippedColor,
			itemSection,
			showOnItem,
			asDurability,
			itemColor,
		),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(TimerElement())
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay) onMessage(message.string)
		}
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> Charm.entries.forEach { it.forget() } }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Charm.entries.forEach { it.forget() } }
	}

	/** Counted off Hypixel's own clock, like every other timer here. */
	@JvmStatic
	fun onServerTick() {
		if (!module.enabled) return
		Charm.entries.forEach { it.tick() }
	}

	private fun onMessage(line: String) {
		if (!module.enabled) return
		if (onlyInDungeons.value && !DungeonLocation.inDungeon) return

		val charm = Charm.entries.firstOrNull { it.pattern.matches(line) } ?: return
		charm.proc(if (charm == Charm.BONZO) wornBonzoCooldown() else null)

		val client = Minecraft.getInstance()
		if (playSound.value) playProcSound()
		if (procTitle.value) {
			// Plain white and short: the name of the thing, which is all there is
			// time to read in the moment it happens.
			client.gui.hud.setTimes(0, TITLE_STAY_TICKS, TITLE_FADE_TICKS)
			client.gui.hud.setTitle(Component.literal("§f${charm.label}"))
		}

		if (!announce.value) return
		val used = Charm.entries.count { it.cooldownFor > 0 }
		val message = "${charm.label} procced! ($used/${Charm.entries.size})"
		// The same preview switch the score announcements use, so the wording
		// can be read back without a party to send it to.
		if (DebugOverrides.previewScoreMessage) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Party chat: §f$message"))
		} else {
			client.connection?.sendCommand("pc $message")
		}
	}

	private fun playProcSound() =
		SoundChoices.play(sound.selectedIndex, volume.value.toFloat(), pitch.value.toFloat())

	/** How long the proc title holds, and how long it takes to fade, in ticks: Odin's. */
	private const val TITLE_STAY_TICKS = 20
	private const val TITLE_FADE_TICKS = 5

	/**
	 * The cooldown written on the Bonzo's Mask being worn.
	 *
	 * An upgraded mask comes back faster than the base three minutes, and the
	 * only place that number exists is the item's own lore.
	 */
	private fun wornBonzoCooldown(): Int? {
		val helmet = Minecraft.getInstance().player?.getItemBySlot(EquipmentSlot.HEAD) ?: return null
		val lore = helmet.get(DataComponents.LORE) ?: return null
		return lore.lines()
			.asReversed()
			.firstNotNullOfOrNull { COOLDOWN_LINE.matchEntire(it.string.trim())?.groupValues?.get(1)?.toIntOrNull() }
	}

	private val COOLDOWN_LINE = Regex("""^Cooldown: (\d+)s$""")

	/**
	 * A player head wearing one of Hypixel's own textures.
	 *
	 * The hash is the tail of the skin's URL, which is how every mod that draws
	 * a SkyBlock item it does not own refers to it. Resolved rather than left
	 * to be looked up, so nothing is fetched from Mojang to draw a mask.
	 */
	private fun skullOf(textureHash: String): ItemStack {
		val payload = """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/$textureHash"}}}"""
		val property = Property("textures", Base64.getEncoder().encodeToString(payload.toByteArray()))
		val properties = PropertyMap(
			ImmutableMultimap.builder<String, Property>().put("textures", property).build(),
		)
		return ItemStack(Items.PLAYER_HEAD).apply {
			set(
				DataComponents.PROFILE,
				ResolvableProfile.createResolved(GameProfile(UUID.randomUUID(), "_", properties)),
			)
		}
	}

	/** Which mask is on your head, so the row for it can say so. */
	private fun wornCharm(): Charm? {
		val helmet = Minecraft.getInstance().player?.getItemBySlot(EquipmentSlot.HEAD) ?: return null
		val id = SkyblockItem.id(helmet)
		return Charm.entries.firstOrNull { id in it.itemIds }
	}

	private fun shows(charm: Charm): Boolean {
		val wanted = when (charm) {
			Charm.SPIRIT -> showSpirit.value
			Charm.BONZO -> showBonzo.value
			Charm.PHOENIX -> showPhoenix.value
		}
		if (!wanted) return false

		return when (showWhen.selectedIndex) {
			1 -> charm.invincibleFor > 0 || charm.cooldownFor > 0
			2 -> charm.invincibleFor > 0
			3 -> charm.cooldownFor > 0
			else -> true
		}
	}

	/** Whether the rows have any business being on screen where you are now. */
	private fun showsHere(): Boolean {
		if (onlyInDungeons.value && !DungeonLocation.inDungeon) return false
		if (onlyInBoss.value && !DungeonRun.inBoss) return false
		return true
	}

	/** The seconds left, or the tick that says there is nothing to wait for. */
	private fun rowText(charm: Charm): String = when {
		charm.invincibleFor > 0 -> "${seconds(charm.invincibleFor)}s"
		charm.cooldownFor > 0 -> "${seconds(charm.cooldownFor)}s"
		else -> READY_MARK
	}

	private fun rowColor(charm: Charm): Int = when {
		charm.invincibleFor > 0 -> activeColor.argb
		charm.cooldownFor > 0 -> cooldownColor.argb
		else -> readyColor.argb
	}

	private fun seconds(ticks: Int): String = String.format(Locale.ROOT, "%.1f", ticks / 20f)

	/**
	 * Draws a mask's cooldown over the item itself, in an inventory.
	 *
	 * Returns true when the slot has been drawn here and the game should not
	 * draw it again. The item goes down first and the shade over it second,
	 * because the point is to cover the picture rather than sit beside it.
	 */
	@JvmStatic
	fun drawSlotCooldown(context: GuiGraphicsExtractor, slot: Slot): Boolean {
		if (!module.enabled || !showOnItem.value) return false

		val stack = slot.item
		if (stack.isEmpty) return false
		val id = SkyblockItem.id(stack)
		val charm = Charm.entries.firstOrNull { id in it.itemIds } ?: return false

		val left = charm.cooldownFor.toDouble() / (charm.cooldownSeconds * 20)
		if (left <= 0.0) return false

		context.fakeItem(stack, slot.x, slot.y)
		if (asDurability.value) {
			context.fill(slot.x + 2, slot.y + 13, slot.x + 14, slot.y + 15, 0xFF000000.toInt())
			context.fill(slot.x + 2, slot.y + 13, slot.x + 14 - ((1 - left) * 12).toInt(), slot.y + 14, itemColor.argb)
		} else {
			context.fill(slot.x, slot.y + ((1 - left) * 16).toInt(), slot.x + 16, slot.y + 16, itemColor.argb)
		}
		return true
	}

	/** A tick rather than a number, which is Odin's way of saying ready. */
	private const val READY_MARK = "✔"

	private class TimerElement : HudElement("invincibility_timer", "Invincibility Timer", 0.45, 0.40) {
		private val font get() = Minecraft.getInstance().font

		private fun rows(): List<Charm> = Charm.entries.filter { shows(it) }

		/**
		 * What the frame is measured around.
		 *
		 * With nothing to show the element is not drawn at all, so the size
		 * that matters then is the one the editor's example takes: all three.
		 */
		private fun sized(): List<Charm> = rows().ifEmpty { Charm.entries }

		private fun labelOf(charm: Charm): String =
			if (showIcons.value) rowText(charm) else "${charm.label}: ${rowText(charm)}"

		override val width: Int
			get() {
				val text = sized().maxOfOrNull { font.width(labelOf(it)) } ?: 0
				return maxOf(EXAMPLE_WIDTH, text + if (showIcons.value) ICON_ROOM else MARK_WIDTH)
			}

		override val height: Int
			get() = maxOf(1, sized().size) * rowHeight()

		private fun rowHeight(): Int = if (showIcons.value) ICON_ROW else font.lineHeight

		override fun isVisible(): Boolean = module.enabled && showsHere() && rows().isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled

		override fun render(context: GuiGraphicsExtractor) = draw(context, rows(), wornCharm())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, Charm.entries, wornCharm())

		private fun draw(context: GuiGraphicsExtractor, charms: List<Charm>, worn: Charm?) {
			charms.forEachIndexed { index, charm ->
				if (showIcons.value) {
					context.item(charm.icon, 0, index * ICON_ROW - 1)
					val y = index * ICON_ROW + 3
					// A stripe down the side of the mask you are wearing, which
					// is the one whose cooldown is about to matter.
					if (charm == worn) context.fill(14, y, 15, y + 9, equippedColor.argb)
					context.text(font, rowText(charm), ICON_ROOM - 4, y, rowColor(charm))
					return@forEachIndexed
				}

				val y = index * font.lineHeight
				if (charm == worn) context.fill(0, y, 2, y + font.lineHeight - 1, equippedColor.argb)
				context.text(font, labelOf(charm), MARK_WIDTH, y, rowColor(charm))
			}
		}

		private companion object {
			const val MARK_WIDTH = 5

			/** A head is sixteen wide; the text starts clear of it and its stripe. */
			const val ICON_ROOM = 20
			const val ICON_ROW = 14
			const val EXAMPLE_WIDTH = 46
		}
	}
}
