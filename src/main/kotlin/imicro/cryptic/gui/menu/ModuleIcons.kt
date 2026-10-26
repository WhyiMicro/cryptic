package imicro.cryptic.gui.menu

import imicro.cryptic.gui.Module

/**
 * The Font Awesome (6.7.2, free solid) glyph on each module's card, by module id. To change one,
 * find a free solid icon on fontawesome.com and put its Unicode value here. Missing modules use
 * their category's icon.
 */
object ModuleIcons {
	private val byId = mapOf(
		// General
		"bow_tweaks" to "\uf140", // bullseye
		"auto_clicker" to "\uf8cc", // computer-mouse
		"auto_gfs" to "\uf49e", // box-open
		"auto_sprint" to "\uf70c", // person-running
		"better_chat" to "\uf086", // comments
		"cookie_reminder" to "\uf564", // cookie-bite
		"confirm_disconnect" to "\uf2f5", // right-from-bracket
		"party_finder" to "\ue533", // people-group
		"party_blacklist" to "\uf235", // user-xmark
		"item_outline" to "\uf5ee", // draw-polygon
		"capes" to "\uf024", // flag
		"freelook" to "\uf1e5", // binoculars
		"freecam" to "\uf03d", // video
		"hide_armor" to "\uf070", // eye-slash
		"client_restrictions" to "\uf023", // lock
		"wither_dragons" to "\uf6d5", // dragon
		"relics" to "\uf521", // crown
		"daily_rewards" to "\uf06b", // gift
		"experiment_solver" to "\uf0c3", // flask
		"gyro_helper" to "\uf76f", // tornado
		"lag_detector" to "\uf233", // server
		"loadout_manager" to "\uf553", // shirt
		"no_jump_delay" to "\uf102", // angles-up
		"nucleus_qol" to "\uf3a5", // gem
		"party_features" to "\uf0c0", // users
		"sb_kick" to "\ue552", // person-walking-arrow-right
		"scrollable_tooltips" to "\uf70e", // scroll
		"slot_binds" to "\uf0c1", // link
		"spring_boots_helper" to "\uf54b", // shoe-prints
		"zoom" to "\uf00e", // magnifying-glass-plus

		// Dungeon
		"auto_requeue" to "\uf2f9", // rotate-right
		"class_abilities" to "\uf72b", // wand-magic-sparkles
		"blessing_display" to "\uf684", // hands-praying
		"blood_camp" to "\uf043", // droplet
		"breaker_helper" to "\uf6e3", // hammer
		"class_colors" to "\uf53f", // palette
		"croesus_helper" to "\uf51e", // coins
		"door_highlight" to "\uf52b", // door-open
		"door_keys" to "\uf084", // key
		"dungeon_map" to "\uf279", // map
		"dungeon_warp_cooldown" to "\uf252", // hourglass-half
		"dungeon_waypoints" to "\uf276", // map-pin
		"hidden_mobs" to "\uf6e2", // ghost
		"highlight" to "\uf591", // highlighter
		"i_love_glass" to "\uf530", // glasses
		"invincibility_timer" to "\uf3ed", // shield-halved
		"mage_beam" to "\uf6e8", // hat-wizard
		"no_item_place" to "\uf05e", // ban
		"positional_messages" to "\uf5a0", // map-location-dot
		"puzzle_hud" to "\uf0ae", // list-check
		"puzzle_solver" to "\uf12e", // puzzle-piece
		"room_alerts" to "\uf071", // triangle-exclamation
		"secrets" to "\uf21b", // user-secret
		"smart_tick_timer" to "\uf2f2", // stopwatch
		"spirit_leap_overlay" to "\uf009", // table-cells-large

		// Boss
		"boss_waypoints" to "\uf3c5", // location-dot
		"device_solver" to "\uf2db", // microchip
		"f7_qol" to "\uf714", // skull-crossbones
		"livid_solver" to "\uf630", // masks-theater
		"spirit_bear" to "\uf1b0", // paw
		"terminal_esp" to "\uf06e", // eye
		"terminal_order" to "\uf0cb", // list-ol
		"terminal_simulator" to "\uf11b", // gamepad
		"terminal_solver" to "\uf120", // terminal
		"terminal_times" to "\uf017", // clock
		"terracotta_timer" to "\uf443", // chess-pawn
		"f3_features" to "\uf501", // user-graduate

		// Render
		"animations" to "\uf255", // hand-back-fist
		"better_glow" to "\uf0eb", // lightbulb
		"block_overlay" to "\uf1b2", // cube
		"camera" to "\uf030", // camera
		"custom_nametags" to "\uf02b", // tag
		"custom_scale" to "\uf31e", // maximize
		"dark_mode" to "\uf186", // moon
		"etherwarp" to "\uf124", // location-arrow
		"hide_players" to "\uf506", // user-slash
		"lava_to_water" to "\uf773", // water
		"no_debuff" to "\uf484", // pills
		"performance_hud" to "\uf625", // gauge-high
		"render_optimizer" to "\uf135", // rocket
		"time_changer" to "\uf185", // sun
		"wither_cloak_effect" to "\uf505", // user-shield

		// General: the managers
		"alias_manager" to "\uf121", // code
		"carry_manager" to "\uf4ce", // people-carry-box
		"slayer_features" to "\uf54c", // skull
		"chocolate_factory" to "\uf275", // industry
		"crosshair_editor" to "\uf05b", // crosshairs
		"keybinds_manager" to "\uf11c", // keyboard
		"sound_manager" to "\uf028", // volume-high

		// Settings
		"click_gui" to "\uf1de", // sliders
		"credits" to "\uf004", // heart
		"example_module" to "\uf61f", // shapes
		"profiles" to "\uf5fd", // layer-group
		"toasts" to "\uf0f3", // bell
		"item_protection" to "\uf132", // shield
		"bazaar" to "\uf54e", // store
		"mining" to "\uf85e", // person-digging
		"fishing" to "\uf578", // fish
		"hud_editor" to "\uf26c", // display
		"update_checker" to "\uf0ed", // cloud-arrow-down
	)

	fun of(module: Module): String? = byId[module.id]
}
