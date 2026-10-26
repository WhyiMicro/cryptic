package imicro.cryptic.gui.menu.managers

/** A Credits page entry. Licences and what still comes from each project are in THIRD_PARTY_NOTICES.md. */
data class Credit(
	val name: String,
	val what: String,
	val licence: String? = null,
	val url: String? = null,
)

data class CreditGroup(val title: String, val blurb: String, val credits: List<Credit>)

object CreditList {
	const val REPO = "https://github.com/WhyiMicro/cryptic"
	const val LICENCE_URL = "$REPO/blob/master/LICENSE"
	const val NOTICES_URL = "$REPO/blob/master/THIRD_PARTY_NOTICES.md"

	val groups = listOf(
		CreditGroup(
			"Inspired by",
			"Mods whose features Cryptic grew from.",
			listOf(
				Credit(
					"Odin",
					"The terminal solver and simulator, the puzzle solvers, Simon Says, the Spirit Leap menu, " +
						"Dungeon Waypoints, the Croesus Helper, Slot Binds, the Melody HUD's live relay and much of the Floor 7 boss.",
					"BSD 3-Clause", "https://github.com/odtheking/Odin",
				),
				Credit(
					"OdinLegacy",
					"The Chocolate Factory module: the factory menu helpers and the egg waypoints.",
					"BSD 3-Clause", "https://github.com/odtheking/OdinLegacy",
				),
				Credit(
					"NoammAddons",
					"Blood Camp, door keys and room alerts, Breaker Helper, Wither Dragons, Relics, Render Optimizer, the Arrow Align and " +
						"Sharp Shooter devices, Secrets, Gyro Helper, Auto GFS, Class Abilities, Item Protection, the Alias and Sound managers, and many smaller modules.",
					"CC0", "https://github.com/Noamm9/NoammAddons",
				),
				Credit(
					"Skyblocker",
					"Tic Tac Toe, Ice Path and the Water Board's previews, Lights On, Smooth teleport, the Booster Cookie reading, " +
						"Nucleus Features's lock picks and Crystal Hollows map, the Pickobulus overlay, Item Protection's protected menus, " +
						"and the idea of the Party Finder.",
					"LGPL-3.0", "https://github.com/SkyblockerMod/Skyblocker",
				),
				Credit(
					"SkyHanni",
					"Nucleus Features: the crystal slots, the Metal Detector solver and the treasure chest timers, and the Croesus profit lists.",
					"LGPL-2.1", "https://github.com/hannibal002/SkyHanni",
				),
				Credit("dtMap", "The dungeon map, its room database and the score.", "BSD 3-Clause", "https://github.com/ricedotwho/dtMap"),
				Credit(
					"Devonian",
					"Door highlighting, the Puzzle HUD, the Dungeon Warp Cooldown and Dungeon Waypoints' secret spots.",
					"GPL-3.0", "https://github.com/Synnerz/Devonian",
				),
				Credit(
					"Athen",
					"The Lag Detector, Bow Tweaks' arrow hitboxes, Custom Scale, the Carry Manager, the Keybinds Manager and Slayer Features' laser timer.",
					"BSD 3-Clause", "https://github.com/skies-starred/Athen",
				),
				Credit(
					"Meridian",
					"The Carry Manager's fallback for telling whose slayer boss is whose.",
					"MIT", "https://github.com/chillypine/meridian",
				),
				Credit(
					"IQ",
					"Slayer Features' Fire Veil overlay: the wand's ring, and its hidden flames.",
					"Apache-2.0", "https://github.com/iqaddons/IQ",
				),
				Credit(
					"Lumen",
					"Breaker Helper's zero ping mining, the Floor 7 levers, Three Weirdos and Tic Tac Toe's early answer.",
					"AGPL-3.0", "https://github.com/jizu1/lumen",
				),
				Credit(
					"Blade Addons",
					"The secret spawn timer, Spirit Leap Features' leap count and Class Colors' labels.",
					"CC0", "https://github.com/BladeMasterGabe/blade-addons",
				),
				Credit("RSM", "Spirit Leap Features' two rotate fixes.", "BSD 3-Clause", "https://github.com/rs-mod/rsm"),
				Credit("Ping Offset Miner", "Mining's ping offset timing and block hardness.", "CC0", "https://github.com/Revvilon/PingOffsetMiner"),
				Credit("SkyOcean", "Fishing's hotspot highlight: finding, sizing and hiding the hotspot ring.", "MIT", "https://github.com/meowdding/SkyOcean"),
				Credit(
					"Custom Block Highlight",
					"Block Overlay's advanced highlight.",
					"GPL-3.0", "https://github.com/Tektonikal/CustomBlockHighlight",
				),
				Credit("DailyRewards", "Reading and claiming Hypixel's daily reward.", "LGPL-3.0", "https://github.com/Cqseur/DailyRewards"),
				Credit(
					"Astrail Experiment",
					"The Experimentation Table's rules.",
					"MIT", "https://github.com/AzureSky0116/astrail-experiment",
				),
				Credit(
					"OdinClient",
					"Keeping your own clicks out of the Experiment Solver.",
					"BSD 3-Clause", "https://github.com/skies-starred/OdinClient",
				),
				Credit(
					"NotEnoughUpdates",
					"The Wither Cloak effect.",
					"LGPL-3.0", "https://github.com/NotEnoughUpdates/NotEnoughUpdates",
				),
			),
		),
		CreditGroup(
			"Ideas from",
			"Mods that showed how something should work. None of their code is in Cryptic.",
			listOf(
				Credit("Essential", "The look and feel of the notifications.", url = "https://essential.gg"),
				Credit("RewardClaim", "How the daily reward picker should behave.", url = "https://github.com/meowdding/RewardClaim"),
				Credit("ADM", "Spirit Leap's teammate views.", url = "https://github.com/undonecoffee/adm"),
				Credit("quoi", "The blocks that give each Crystal Hollows place away to the structure scanner (after GumTuneClient), the Bazaar's escrow fix, the autoclicker blacklist, and the Chocolate Factory's Time Tower and Coach upgrades.", "GPL-3.0", "https://github.com/jcnlk/quoi"),
				Credit("Enhanced Chat","Better Chat's tabs, search, right-click menu, merged repeats and longer history.", "GPL-3.0", "https://github.com/KdGaming0/Enhanced-Chat"),
				Credit("NoFrills", "Better Chat's skipped command confirmations and kept history, Item Protection's rules and override key, and the pickaxe ability timer.", "GPL-3.0", "https://github.com/WhatYouThing/NoFrills"),
				Credit("BtrBz", "The Bazaar module: order tracking, undercut alerts and tooltip prices.", "LGPL-3.0", "https://github.com/LutzLuca/BtrBz"),
				Credit("Feesh", "Fishing's hotspot found message and gone alert.", "Apache-2.0", "https://github.com/Sleepy-Panda/Feesh"),
				Credit("Debugify", "Camera's riding input fix.", "LGPL-3.0", "https://github.com/isXander/Debugify"),
				Credit("Zoomify", "What a zoom ought to offer.", "LGPL-3.0", "https://modrinth.com/mod/zoomify"),
				Credit("RavenB++", "How the Auto Clicker holds and paces its clicks.", url = "https://github.com/OlziYT/RavenBS-Plus-Plus"),
				Credit("RBDT V5", "How the Experiment Solver works the table between games.", url = "https://github.com/V5-Client/V5"),
				Credit("NoJumpDelay", "Which cooldown to clear."),
				Credit("M7 Guides", "Terminal Order's numbering and class assignments, as published in Stella's terminal data."),
			),
		),
		CreditGroup(
			"Data and artwork",
			"Kept as they are, under their own licences.",
			listOf(
				Credit(
					"dtMap",
					"The room database, the map's check marks, question mark, player pointer and Prince crown (by ziyno), " +
						"and the door key sound.",
					"BSD 3-Clause", "https://github.com/ricedotwho/dtMap",
				),
				Credit("Odin", "The puzzle answer files and the Croesus Helper's price table.", "BSD 3-Clause", "https://github.com/odtheking/Odin"),
				Credit("Devonian", "Every room's secret spots, for Dungeon Waypoints.", "GPL-3.0", "https://github.com/Synnerz/Devonian"),
				Credit(
					"SkyHanni Repository",
					"The 39 places the Mines of Divan treasure turns up.",
					"MIT", "https://github.com/hannibal002/SkyHanni-REPO",
				),
				Credit(
					"Skyblocker",
					"The Water Board's starting gates, the Lights On levers and how the Wishing Compass solver reads a compass.",
					"LGPL-3.0", "https://github.com/SkyblockerMod/Skyblocker",
				),
				Credit("NotEnoughUpdates", "The Wither Cloak's shield texture.", "LGPL-3.0", "https://github.com/NotEnoughUpdates/NotEnoughUpdates"),
			),
		),
		CreditGroup(
			"Fonts and icons",
			"",
			listOf(
				Credit("Poppins", "The menu's text.", "SIL OFL", "https://github.com/itfoundry/Poppins"),
				Credit("Font Awesome Free", "The icons.", "CC BY 4.0", "https://fontawesome.com"),
			),
		),
	)
}
