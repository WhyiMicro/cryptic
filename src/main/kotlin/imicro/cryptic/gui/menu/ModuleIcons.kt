package imicro.cryptic.gui.menu

import imicro.cryptic.gui.Module

/**
 * The Font Awesome (6.7.2, free solid) glyph on each module's card, and its colour, by module id.
 * To change a glyph, find a free solid icon on fontawesome.com and put its Unicode value here.
 * Missing modules use their category's icon and a colour picked from their id.
 */
object ModuleIcons {
	private class Icon(val glyph: String, val colour: Int)

	// Soft tones that read on the dark cards; each module picks one that suits its glyph.
	private const val RED = 0xFFF87171.toInt()
	private const val ORANGE = 0xFFFB923C.toInt()
	private const val AMBER = 0xFFFBBF24.toInt()
	private const val YELLOW = 0xFFFACC15.toInt()
	private const val LIME = 0xFFA3E635.toInt()
	private const val GREEN = 0xFF4ADE80.toInt()
	private const val EMERALD = 0xFF34D399.toInt()
	private const val TEAL = 0xFF2DD4BF.toInt()
	private const val CYAN = 0xFF22D3EE.toInt()
	private const val SKY = 0xFF38BDF8.toInt()
	private const val BLUE = 0xFF60A5FA.toInt()
	private const val INDIGO = 0xFF818CF8.toInt()
	private const val VIOLET = 0xFFA78BFA.toInt()
	private const val PURPLE = 0xFFC084FC.toInt()
	private const val FUCHSIA = 0xFFE879F9.toInt()
	private const val PINK = 0xFFF472B6.toInt()
	private const val ROSE = 0xFFFB7185.toInt()
	private const val SLATE = 0xFF94A3B8.toInt()

	private val PALETTE = intArrayOf(RED, ORANGE, AMBER, YELLOW, LIME, GREEN, EMERALD, TEAL, CYAN, SKY, BLUE, INDIGO, VIOLET, PURPLE, FUCHSIA, PINK, ROSE)

	private val byId = mapOf(
		// General
		"bow_tweaks" to Icon("\uf140", RED), // bullseye
		"auto_clicker" to Icon("\uf8cc", SKY), // computer-mouse
		"anti_afk" to Icon("\uf554", TEAL), // person-walking
		"auto_gfs" to Icon("\uf49e", AMBER), // box-open
		"movement" to Icon("\uf70c", LIME), // person-running
		"better_chat" to Icon("\uf086", BLUE), // comments
		"reminders" to Icon("\uf274", ORANGE), // calendar-check
		"combat" to Icon("\uf6de", ROSE), // hand-fist
		"confirm_disconnect" to Icon("\uf2f5", RED), // right-from-bracket
		"party_finder" to Icon("\ue533", INDIGO), // people-group
		"party_blacklist" to Icon("\uf235", ROSE), // user-xmark
		"item_outline" to Icon("\uf5ee", CYAN), // draw-polygon
		"capes" to Icon("\uf024", PURPLE), // flag
		"freelook" to Icon("\uf1e5", TEAL), // binoculars
		"freecam" to Icon("\uf03d", PINK), // video
		"hide_armor" to Icon("\uf070", SLATE), // eye-slash
		"storage_overlay" to Icon("\uf468", ORANGE), // boxes-stacked
		"media_controller" to Icon("\uf001", FUCHSIA), // music
		"client_restrictions" to Icon("\uf023", YELLOW), // lock
		"wither_dragons" to Icon("\uf6d5", VIOLET), // dragon
		"relics" to Icon("\uf521", YELLOW), // crown
		"daily_rewards" to Icon("\uf06b", PINK), // gift
		"experiment_solver" to Icon("\uf0c3", EMERALD), // flask
		"gyro_helper" to Icon("\uf76f", SKY), // tornado
		"lag_detector" to Icon("\uf233", ORANGE), // server
		"loadout_manager" to Icon("\uf553", BLUE), // shirt
		"nucleus_qol" to Icon("\uf3a5", FUCHSIA), // gem
		"party_features" to Icon("\uf0c0", INDIGO), // users
		"sb_kick" to Icon("\ue552", ROSE), // person-walking-arrow-right
		"scrollable_tooltips" to Icon("\uf70e", AMBER), // scroll
		"slot_binds" to Icon("\uf0c1", CYAN), // link
		"spring_boots_helper" to Icon("\uf54b", GREEN), // shoe-prints
		"zoom" to Icon("\uf00e", SKY), // magnifying-glass-plus

		// Dungeon
		"auto_requeue" to Icon("\uf2f9", GREEN), // rotate-right
		"class_abilities" to Icon("\uf72b", PURPLE), // wand-magic-sparkles
		"blessing_display" to Icon("\uf684", YELLOW), // hands-praying
		"blood_camp" to Icon("\uf043", RED), // droplet
		"breaker_helper" to Icon("\uf6e3", ORANGE), // hammer
		"class_colors" to Icon("\uf53f", PINK), // palette
		"croesus_helper" to Icon("\uf51e", YELLOW), // coins
		"door_highlight" to Icon("\uf52b", ORANGE), // door-open
		"door_keys" to Icon("\uf084", AMBER), // key
		"dungeon_map" to Icon("\uf279", EMERALD), // map
		"dungeon_warp_cooldown" to Icon("\uf252", SKY), // hourglass-half
		"dungeon_waypoints" to Icon("\uf276", ROSE), // map-pin
		"hidden_mobs" to Icon("\uf6e2", VIOLET), // ghost
		"highlight" to Icon("\uf591", LIME), // highlighter
		"i_love_glass" to Icon("\uf530", CYAN), // glasses
		"text_outline" to Icon("\uf032", SLATE), // bold
		"invincibility_timer" to Icon("\uf3ed", BLUE), // shield-halved
		"mage_beam" to Icon("\uf6e8", INDIGO), // hat-wizard
		"no_item_place" to Icon("\uf05e", RED), // ban
		"positional_messages" to Icon("\uf5a0", TEAL), // map-location-dot
		"puzzle_hud" to Icon("\uf0ae", GREEN), // list-check
		"puzzle_solver" to Icon("\uf12e", FUCHSIA), // puzzle-piece
		"room_alerts" to Icon("\uf071", AMBER), // triangle-exclamation
		"secrets" to Icon("\uf21b", PURPLE), // user-secret
		"smart_tick_timer" to Icon("\uf2f2", CYAN), // stopwatch
		"spirit_leap_overlay" to Icon("\uf009", VIOLET), // table-cells-large

		// Boss
		"boss_waypoints" to Icon("\uf3c5", ROSE), // location-dot
		"device_solver" to Icon("\uf2db", EMERALD), // microchip
		"f7_qol" to Icon("\uf714", RED), // skull-crossbones
		"livid_solver" to Icon("\uf630", PINK), // masks-theater
		"spirit_bear" to Icon("\uf1b0", AMBER), // paw
		"terminal_esp" to Icon("\uf06e", CYAN), // eye
		"terminal_order" to Icon("\uf0cb", BLUE), // list-ol
		"terminal_simulator" to Icon("\uf11b", INDIGO), // gamepad
		"terminal_solver" to Icon("\uf120", GREEN), // terminal
		"terminal_times" to Icon("\uf017", SKY), // clock
		"terracotta_timer" to Icon("\uf443", ORANGE), // chess-pawn
		"f3_features" to Icon("\uf501", VIOLET), // user-graduate

		// Render
		"animations" to Icon("\uf255", ORANGE), // hand-back-fist
		"better_glow" to Icon("\uf0eb", YELLOW), // lightbulb
		"block_overlay" to Icon("\uf1b2", CYAN), // cube
		"camera" to Icon("\uf030", PINK), // camera
		"custom_nametags" to Icon("\uf02b", TEAL), // tag
		"custom_scale" to Icon("\uf31e", BLUE), // maximize
		"dark_mode" to Icon("\uf186", INDIGO), // moon
		"etherwarp" to Icon("\uf124", VIOLET), // location-arrow
		"hide_players" to Icon("\uf506", SLATE), // user-slash
		"lava_to_water" to Icon("\uf773", SKY), // water
		"no_debuff" to Icon("\uf484", ROSE), // pills
		"pack_tweaks" to Icon("\uf5aa", FUCHSIA), // paint-roller
		"performance_hud" to Icon("\uf625", GREEN), // gauge-high
		"twitch_chat" to Icon("\uf4ad", PURPLE), // comment-dots
		"hotbar" to Icon("\uf0db", AMBER), // table-columns
		"custom_scoreboard" to Icon("\uf46d", EMERALD), // clipboard-list
		"status_bars" to Icon("\uf21e", RED), // heart-pulse
		"render_optimizer" to Icon("\uf135", ORANGE), // rocket
		"chunk_cache" to Icon("\uf5fd", TEAL), // layer-group
		"time_changer" to Icon("\uf185", YELLOW), // sun
		"wither_cloak_effect" to Icon("\uf505", VIOLET), // user-shield

		// General: the managers
		"alias_manager" to Icon("\uf121", CYAN), // code
		"carry_manager" to Icon("\uf4ce", AMBER), // people-carry-box
		"slayer_features" to Icon("\uf54c", RED), // skull
		"chocolate_factory" to Icon("\uf275", ORANGE), // industry
		"crosshair_editor" to Icon("\uf05b", ROSE), // crosshairs
		"keybinds_manager" to Icon("\uf11c", BLUE), // keyboard
		"sound_manager" to Icon("\uf028", FUCHSIA), // volume-high

		// Settings
		"click_gui" to Icon("\uf1de", INDIGO), // sliders
		"credits" to Icon("\uf004", PINK), // heart
		"changelog" to Icon("\uf70e", AMBER), // scroll
		"profiles" to Icon("\uf5fd", TEAL), // layer-group
		"toasts" to Icon("\uf0f3", YELLOW), // bell
		"item_protection" to Icon("\uf132", BLUE), // shield
		"bazaar" to Icon("\uf54e", EMERALD), // store
		"flipping" to Icon("\uf0ec", GREEN), // arrow-right-arrow-left
		"auction_house" to Icon("\uf0e3", ORANGE), // gavel
		"farming" to Icon("\uf4d8", LIME), // seedling
		"rift" to Icon("\uf252", PURPLE), // hourglass-half
		"mining" to Icon("\uf85e", CYAN), // person-digging
		"quick_nav" to Icon("\uf14e", ROSE), // compass
		"fishing" to Icon("\uf578", SKY), // fish
		"hud_editor" to Icon("\uf26c", VIOLET), // display
		"update_checker" to Icon("\uf0ed", GREEN), // cloud-arrow-down
	)

	fun of(module: Module): String? = byId[module.id]?.glyph

	/** The module's own colour; one without an entry gets a steady pick from its id. */
	fun colourOf(module: Module): Int =
		byId[module.id]?.colour ?: PALETTE[Math.floorMod(module.id.hashCode(), PALETTE.size)]
}
