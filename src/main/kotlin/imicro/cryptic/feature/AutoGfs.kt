package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Tops your dungeon consumables back up from your sacks with `/gfs`.
 *
 * NoammAddons' Auto GFS (CC0, Noamm9). Pearls, superbooms, Jerries and leaps
 * all run out partway through a run, and fetching them from the sacks by hand
 * means opening a menu at the worst moment. This sends the same `/gfs` you
 * would have typed: on a timer, one item per check, or — with Refill at the
 * end of a run — everything once the run is over, when nobody is in a hurry.
 *
 * Only items you are already carrying are topped up, and only when at least
 * four are missing, which is NoammAddons' rule: an item you have none of is
 * one you chose not to bring. A sack that answers that it is empty is not
 * asked again until the next server.
 *
 * Twilight arrow poison is asked for at three moments of Master Mode 7 rather
 * than on a timer, each for the classes that want it then.
 */
object AutoGfs {
	/** Fewer missing than this is not worth a command. */
	private const val MIN_MISSING = 4

	/** The gap between two commands of an end-of-run refill. */
	private const val END_SPACING_TICKS = 25

	private val FORMATTING = Regex("§.")

	private val sackEmpty = Regex("""^You have no (.+) in your Sacks!$""")
	private val stormLightning = Regex("""^\[BOSS] Storm: (?:ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$""")
	private const val CORE_OPENING = "The Core entrance is opening!"
	private const val WITHER_KING_RELICS = "[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you."

	/** One consumable: its id in the sacks and on the item, and how many make a full stack. */
	private class Refill(val sackId: String, val itemId: String, val full: Int, val toggle: ToggleModuleSetting)

	private val timingSection = SectionModuleSetting("timing_section", "When")

	@JvmField
	val atRunEnd = ToggleModuleSetting(
		id = "refill_at_run_end",
		label = "Refill at the end of a run",
		defaultValue = false,
		description = "Fills everything up once the run is over, instead of checking on a timer during it.",
	)

	@JvmField
	val delay = SliderModuleSetting(
		id = "delay",
		label = "Check every (seconds)",
		defaultValue = 20.0,
		min = 5.0,
		max = 60.0,
		step = 1.0,
		description = "How often your inventory is checked. One item is topped up per check.",
		visibleIf = { !atRunEnd.value },
	)

	private val itemsSection = SectionModuleSetting("items_section", "Items")

	private fun item(id: String, label: String, default: Boolean) =
		ToggleModuleSetting(id = id, label = label, defaultValue = default)

	@JvmField
	val pearls = item("refill_pearls", "Ender pearls", true)

	@JvmField
	val superbooms = item("refill_superbooms", "Superboom TNT", false)

	@JvmField
	val jerries = item("refill_jerries", "Inflatable Jerry", false)

	@JvmField
	val leaps = item("refill_leaps", "Spirit leaps", true)

	@JvmField
	val twilight = item("refill_twilight", "Twilight arrow poison", false)

	private val twilightSection = SectionModuleSetting("twilight_section", "Twilight", visibleIf = { twilight.value })

	@JvmField
	val twilightLightning = ToggleModuleSetting(
		id = "twilight_after_lightning",
		label = "After Storm's lightning",
		defaultValue = true,
		description = "As archer, once Storm calls his lightning in Master Mode 7.",
		visibleIf = { twilight.value },
	)

	@JvmField
	val twilightCore = ToggleModuleSetting(
		id = "twilight_in_core",
		label = "In core",
		defaultValue = false,
		description = "As archer or berserk, when the core opens in Master Mode 7.",
		visibleIf = { twilight.value },
	)

	@JvmField
	val twilightRelics = ToggleModuleSetting(
		id = "twilight_after_relics",
		label = "After the relics",
		defaultValue = true,
		description = "As healer, mage or tank, once the Wither King gives up in Master Mode 7.",
		visibleIf = { twilight.value },
	)

	@JvmField
	val twilightAmount = SliderModuleSetting(
		id = "twilight_amount",
		label = "Twilight amount",
		defaultValue = 8.0,
		min = 4.0,
		max = 8.0,
		step = 1.0,
		visibleIf = { twilight.value },
	)

	@JvmField
	val module = Module(
		id = "auto_gfs",
		name = "Auto GFS",
		description = "Refills dungeon items from your sacks",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			timingSection, atRunEnd, delay,
			itemsSection, pearls, superbooms, jerries, leaps, twilight,
			twilightSection, twilightLightning, twilightCore, twilightRelics, twilightAmount,
		),
	)

	private val refills = listOf(
		Refill("ender_pearl", "ENDER_PEARL", 16, pearls),
		Refill("inflatable_jerry", "INFLATABLE_JERRY", 64, jerries),
		Refill("superboom_tnt", "SUPERBOOM_TNT", 64, superbooms),
		Refill("spirit_leap", "SPIRIT_LEAP", 16, leaps),
	)

	/** Sacks that said they are empty, by their gfs id, until the next server. */
	private val emptySacks = HashSet<String>()

	private var nextCheck = 0L
	private var cursor = 0

	/** Commands an end-of-run refill still has to send, one at a time. */
	private val endQueue = ArrayDeque<String>()
	private var endCooldown = 0
	private var runEndHandled = false
	private var lightningHandled = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		emptySacks.clear()
		endQueue.clear()
		lightningHandled = false
	}

	fun tick(client: Minecraft) {
		if (!module.enabled) {
			endQueue.clear()
			return
		}
		val player = client.player ?: return

		if (!DungeonRun.ended) runEndHandled = false

		if (atRunEnd.value) {
			if (DungeonRun.ended && !runEndHandled && DungeonLocation.inDungeon) {
				runEndHandled = true
				endQueue.clear()
				pending(client).forEach { endQueue.addLast(it) }
			}
			if (endCooldown > 0) endCooldown--
			if (endCooldown == 0 && client.gui.screen() == null) {
				endQueue.removeFirstOrNull()?.let {
					endCooldown = END_SPACING_TICKS
					send(client, it)
				}
			}
			return
		}

		// On a timer: only during a run, never with a menu open, never dead.
		val now = System.currentTimeMillis()
		if (now < nextCheck) return
		nextCheck = now + (delay.value * 1000).toLong()
		if (!DungeonLocation.inDungeon || client.gui.screen() != null) return
		if (player.name.string in DungeonTeam.dead) return

		val waiting = pending(client)
		if (waiting.isEmpty()) return
		send(client, waiting[cursor++ % waiting.size])
	}

	/** A `gfs` command for every consumable that is carried, switched on and running low. */
	private fun pending(client: Minecraft): List<String> {
		val inventory = client.player?.inventory ?: return emptyList()
		val counts = HashMap<String, Int>()
		for (slot in 0 until inventory.containerSize) {
			val stack = inventory.getItem(slot)
			if (stack.isEmpty) continue
			val id = SkyblockItem.id(stack)
			if (refills.any { it.itemId == id }) counts.merge(id, stack.count, Int::plus)
		}
		return refills
			.filter { it.toggle.value && it.sackId !in emptySacks }
			.mapNotNull { refill ->
				val have = counts[refill.itemId] ?: 0
				val missing = refill.full - have
				if (have > 0 && missing >= MIN_MISSING) "gfs ${refill.sackId} $missing" else null
			}
	}

	private fun send(client: Minecraft, command: String) {
		if (DebugOverrides.previewPartyCommands) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Would send: §f/$command"))
			return
		}
		client.connection?.sendCommand(command)
	}

	/** A chat packet, on the client thread. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		try {
			onLine(message.string.replace(FORMATTING, "").trim())
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Auto GFS could not read a chat line", error)
		}
	}

	private fun onLine(line: String) {
		sackEmpty.matchEntire(line)?.let {
			val named = it.groupValues[1].lowercase().replace(' ', '_')
			// Hypixel names the item the way its gfs id spells it, spaces for underscores.
			emptySacks += named
			return
		}

		if (!twilight.value || !DungeonRun.inBoss || !DungeonLocation.inFloor7 || !DungeonLocation.masterMode) return
		val dungeonClass = DebugOverrides.dungeonClass
			?: Minecraft.getInstance().player?.name?.string?.let(DungeonTeam::classOf)
			?: return
		val dps = dungeonClass == DungeonClass.ARCHER || dungeonClass == DungeonClass.BERSERK
		val amount = twilightAmount.value.toInt()
		val client = Minecraft.getInstance()

		when {
			twilightCore.value && dps && line == CORE_OPENING ->
				send(client, "gfs twilight_arrow_poison $amount")
			twilightRelics.value && !dps && line == WITHER_KING_RELICS ->
				send(client, "gfs twilight_arrow_poison $amount")
			twilightLightning.value && !lightningHandled && dungeonClass == DungeonClass.ARCHER &&
				stormLightning.matches(line) -> {
				lightningHandled = true
				send(client, "gfs twilight_arrow_poison $amount")
			}
		}
	}
}
