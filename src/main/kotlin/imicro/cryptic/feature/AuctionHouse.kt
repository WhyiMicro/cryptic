package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.ItemRarity
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/**
 * The Auction House: a notification when one of your auctions sells or someone outbids you, and
 * (if wanted) for what you do there yourself. Read from Hypixel's chat lines, so it works anywhere
 * on SkyBlock without the API.
 */
object AuctionHouse {
	@JvmField
	val soldAlert = ToggleModuleSetting("sold_alert", "Notify when sold", true, "A notification when one of your auctions sells, with the buyer and the price.")

	@JvmField
	val outbidAlert = ToggleModuleSetting("outbid_alert", "Notify when outbid", true, "A notification when someone outbids you, with by how much.")

	@JvmField
	val ownActions = ToggleModuleSetting(
		"own_actions", "Your own actions", false,
		"Also a notification for bids you place, things you buy, auctions you start and coins you collect. Hypixel says these in chat as well.",
	)

	@JvmField
	val coopAlerts = ToggleModuleSetting(
		"coop_alerts", "Co-op members", true,
		"A notification when someone in your co-op starts an auction or collects one, with the price.",
	)

	@JvmField
	val sounds = ToggleModuleSetting("sounds", "Sounds", true, "A chime when something sells, two quick pings when you're outbid.")

	@JvmField
	val quietInDungeons = ToggleModuleSetting("quiet_in_dungeons", "Not in dungeons", false, "No Auction House notifications while you're in a dungeon.")

	@JvmField
	val module = Module(
		id = "auction_house",
		name = "Auction House",
		description = "Sales and outbid alerts",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(soldAlert, outbidAlert, ownActions, coopAlerts, sounds, quietInDungeons),
	)

	private const val SOURCE = "Auction House"
	private val FORMATTING = Regex("§.")

	// Hypixel's lines, with formatting taken off. Item names keep their stars and reforges.
	private val sold = Regex("""^\[Auction] (\w+) bought (.+) for ([\d,]+) coins""")
	private val outbid = Regex("""^\[Auction] (\w+) outbid you by ([\d,]+) coins for (.+?)(?: CLICK)?$""")
	private val bidPlaced = Regex("""^Bid of ([\d,]+) coins placed for (.+)!$""")
	private val purchased = Regex("""^You purchased (.+) for ([\d,]+) coins!$""")
	private val started = Regex("""^(BIN )?Auction started for (.+)!$""")
	// Said to the whole co-op, the one who did it included: "[MVP+] Name created a BIN auction for X at 1,000 coins!".
	private val coopCreated = Regex("""^(?:\[[^]]+] )?(\w+) created an? (BIN )?auction for (.+) at ([\d,]+) coins!$""")
	private val coopCollected = Regex("""^(?:\[[^]]+] )?(\w+) collected an auction for ([\d,]+) coins!$""")
	private val collected = Regex("""^You collected ([\d,]+) coins from selling (.+) to (?:\[[^]]+] )?(\w+) in an auction!$""")

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val line = message.string.replace(FORMATTING, "").trim()
		sold.find(line)?.let { match ->
			if (!soldAlert.value) return
			val item = match.groupValues[2].removeSuffix(" CLICK").trim()
			notify("§f${match.groupValues[1]} §7bought ${named(item)} §7for §6${match.groupValues[3]} coins§7.", SoundEvents.NOTE_BLOCK_CHIME.value(), 1)
			return
		}
		outbid.find(line)?.let { match ->
			if (!outbidAlert.value) return
			notify(
				"§f${match.groupValues[1]} §7outbid you on ${named(match.groupValues[3])} §7by §6${match.groupValues[2]} coins§7.",
				SoundEvents.EXPERIENCE_ORB_PICKUP, 2,
			)
			return
		}
		if (coopAlerts.value) {
			val me = net.minecraft.client.Minecraft.getInstance().player?.gameProfile?.name
			// Your own show up as these too; those are the own actions' to tell.
			coopCreated.find(line)?.takeIf { !it.groupValues[1].equals(me, ignoreCase = true) }?.let {
				val kind = if (it.groupValues[2].isNotEmpty()) "a BIN auction" else "an auction"
				return notify("§f${it.groupValues[1]} §7started $kind for ${named(it.groupValues[3])} §7at §6${it.groupValues[4]} coins§7.")
			}
			coopCollected.find(line)?.takeIf { !it.groupValues[1].equals(me, ignoreCase = true) }?.let {
				return notify("§f${it.groupValues[1]} §7collected §6${it.groupValues[2]} coins §7from an auction.")
			}
		}
		if (!ownActions.value) return
		bidPlaced.find(line)?.let { return notify("You bid §6${it.groupValues[1]} coins §7on ${named(it.groupValues[2])}§7.") }
		purchased.find(line)?.let { return notify("You bought ${named(it.groupValues[1])} §7for §6${it.groupValues[2]} coins§7.") }
		started.find(line)?.let { return notify("${if (it.groupValues[1].isNotEmpty()) "BIN auction" else "Auction"} started for ${named(it.groupValues[2])}§7.") }
		collected.find(line)?.let { return notify("Collected §6${it.groupValues[1]} coins §7for ${named(it.groupValues[2])} §7from §f${it.groupValues[3]}§7.") }
	}

	/** [name] in its rarity's colour; Hypixel writes these lines in white. */
	private fun named(name: String): String = (ItemRarity.codeForName(name) ?: "§f") + name

	private fun notify(text: String, sound: SoundEvent? = null, times: Int = 1) {
		val quiet = quietInDungeons.value && DungeonLocation.inDungeon
		imicro.cryptic.Cryptic.LOGGER.info("Auction House notification{}: {}", if (quiet) " (held back in a dungeon)" else "", text.replace(FORMATTING, ""))
		if (quiet) return
		Toasts.show(SOURCE, "§7$text", color = Toasts.Colors.AUCTION)
		if (sound != null && sounds.value) {
			// The repeats three ticks apart, as the Bazaar's are.
			for (i in 0 until times) {
				java.util.concurrent.CompletableFuture.delayedExecutor(i * 150L, java.util.concurrent.TimeUnit.MILLISECONDS)
					.execute { net.minecraft.client.Minecraft.getInstance().execute { SoundChoices.play(sound, 0.5f, 1f) } }
			}
		}
	}
}
