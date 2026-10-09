package imicro.cryptic

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.KeyMapping
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import java.util.Locale
import org.lwjgl.glfw.GLFW
import imicro.cryptic.gui.AliasScreen
import imicro.cryptic.gui.CarryScreen
import imicro.cryptic.gui.CrosshairScreen
import imicro.cryptic.gui.CrypticScreen
import imicro.cryptic.gui.ImGuiRuntime
import imicro.cryptic.gui.KeybindsScreen
import imicro.cryptic.gui.SoundsScreen
import imicro.cryptic.hud.Hud
import imicro.cryptic.puzzle.BlazeSolver
import imicro.cryptic.config.ConfigManager
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.debug.InventoryWatch
import imicro.cryptic.feature.BlessingDisplay
import imicro.cryptic.feature.DungeonWarpCooldown
import imicro.cryptic.feature.ExampleModule
import imicro.cryptic.feature.ILoveGlass
import imicro.cryptic.feature.PuzzleHud
import imicro.cryptic.feature.MelodyHud
import imicro.cryptic.feature.PartyFeatures
import imicro.cryptic.feature.PerformanceHud
import imicro.cryptic.feature.SpringBootsHelper
import imicro.cryptic.feature.TerracottaTimer
import imicro.cryptic.skyblock.ServerStats
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import imicro.cryptic.feature.ArrowHitboxes
import imicro.cryptic.feature.BlockOverlay
import imicro.cryptic.feature.BloodCamp
import imicro.cryptic.feature.CameraTweaks
import imicro.cryptic.feature.CarryManager
import imicro.cryptic.feature.KeybindManager
import imicro.cryptic.feature.AliasManager
import imicro.cryptic.feature.SoundVolumes
import imicro.cryptic.feature.UpdateChecker
import imicro.cryptic.feature.CrosshairEditor
import imicro.cryptic.feature.DoorFix
import imicro.cryptic.feature.LavaToWater
import imicro.cryptic.feature.NoItemPlace
import imicro.cryptic.feature.SbKick
import imicro.cryptic.feature.TimeChanger
import imicro.cryptic.feature.CookieReminder
import imicro.cryptic.feature.LagDetector
import imicro.cryptic.feature.NucleusQol
import imicro.cryptic.feature.SmartTickTimer
import imicro.cryptic.feature.SpiritLeapOverlay
import imicro.cryptic.dungeon.DungeonBoss
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.Floor7Progress
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonMapReader
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.BossTimings
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.RoomSecrets
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.MayorPaul
import imicro.cryptic.feature.BreakerHelper
import imicro.cryptic.feature.ClassColors
import imicro.cryptic.feature.ClassNames
import imicro.cryptic.feature.CustomNametags
import imicro.cryptic.feature.DeviceSolver
import imicro.cryptic.feature.DoorHighlight
import imicro.cryptic.feature.DoorKeys
import imicro.cryptic.feature.DungeonMap
import imicro.cryptic.feature.DungeonScore
import imicro.cryptic.feature.HiddenMobs
import imicro.cryptic.feature.HidePlayers
import imicro.cryptic.feature.GateHighlight
import imicro.cryptic.feature.F7Qol
import imicro.cryptic.feature.AutoGfs
import imicro.cryptic.feature.AutoRequeue
import imicro.cryptic.feature.SpiritBear
import imicro.cryptic.feature.LividSolver
import imicro.cryptic.feature.PositionalMessages
import imicro.cryptic.feature.DungeonWaypoints
import imicro.cryptic.feature.BossWaypoints
import imicro.cryptic.feature.GyroHelper
import imicro.cryptic.feature.LoadoutManager
import imicro.cryptic.feature.CroesusHelper
import imicro.cryptic.feature.Highlight
import imicro.cryptic.feature.InvincibilityTimer
import imicro.cryptic.feature.PuzzleSolver
import imicro.cryptic.feature.RenderOptimizer
import imicro.cryptic.feature.Secrets
import imicro.cryptic.feature.SlotBinds
import imicro.cryptic.feature.LeapMessage
import imicro.cryptic.feature.MageBeam
import imicro.cryptic.feature.NoDebuff
import imicro.cryptic.feature.RoomAlerts
import imicro.cryptic.feature.TerminalEsp
import imicro.cryptic.feature.TerminalOrder
import imicro.cryptic.feature.TerminalSimulator
import imicro.cryptic.feature.TerminalSolver
import imicro.cryptic.feature.TerminalTimes
import imicro.cryptic.feature.Toasts
import imicro.cryptic.feature.Etherwarp
import imicro.cryptic.feature.ExperimentSolver
import imicro.cryptic.experiment.EquippedPet
import imicro.cryptic.experiment.ExperimentDebug
import imicro.cryptic.experiment.ExperimentRunner
import imicro.cryptic.experiment.ExperimentTracker
import imicro.cryptic.feature.WitherCloakEffect
import imicro.cryptic.feature.WitherOutline
import imicro.cryptic.feature.Zoom
import imicro.cryptic.terminal.TerminalDebug
import imicro.cryptic.skyblock.BoosterCookie
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.slayer.VoidgloomBosses
import imicro.cryptic.terminal.Terminals

/** Client-only setup: key mappings and screens belong here, not in [Cryptic]. */
object CrypticClient : ClientModInitializer {
	private val keyCategory = KeyMapping.Category.register(Cryptic.id("cryptic"))
	/** How many finished carries `/cryptic carry history` shows without asking. */
	private const val CARRY_HISTORY_LINES = 10

	private var openGuiRequested = false
	private var hudEditorRequested = false
	private var termSimRequested = false

	/**
	 * Right shift out of the box, which almost nothing else in Minecraft wants.
	 *
	 * Only a fresh install sees this: once a key has been bound, Minecraft's own
	 * options file is what remembers it, and rebinding through the controls
	 * screen or the menu's own bind button overrides it for good.
	 */
	val openGuiKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.open_gui",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_SHIFT,
			keyCategory,
		),
	)

	/**
	 * Unbound out of the box, unlike the menu's key.
	 *
	 * A zoom key is held rather than tapped, so it has to be a key the person
	 * using it already has a spare thumb or finger on, and there is no answer to
	 * that which is right for everybody. Binding C — the usual choice — would
	 * also silently take the key from whatever else claimed it.
	 */
	val zoomKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.zoom",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/**
	 * Unbound out of the box, for the same reason as the zoom key.
	 *
	 * Registered as KEYSYM only because something has to be the default for a
	 * bind that has none. The menu will happily rebind it onto a mouse button,
	 * which arrives through this same mapping system.
	 */
	val autoClickerKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.auto_clicker",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/**
	 * Unbound out of the box, like the other two.
	 *
	 * It is pressed over a slot in the inventory rather than in the world, so it
	 * can safely share a key with something that only acts in the world — but
	 * choosing which is not Cryptic's to decide.
	 */
	val slotBindKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.slot_bind",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/**
	 * Unbound out of the box, like the others.
	 *
	 * It sends a command, so a key that is also used for something else would
	 * warp somebody away mid-task — which one to give up is theirs to decide.
	 */
	val nucleusWarpKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.nucleus_warp",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/**
	 * Unbound out of the box, like the others.
	 *
	 * It opens a window, so it wants a key that is free while both hands are on
	 * the keyboard — and which one that is depends on the rest of the binds.
	 */
	val carryManagerKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.carry_manager",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/** Unbound out of the box, for the same reason as the Carry Manager's. */
	val crosshairEditorKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.crosshair_editor",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	override fun onInitializeClient() {
		Hud.initialize()
		// Every module registers before the profile is read. A HUD element that
		// registers afterwards has its saved placement applied to a list it is
		// not in yet, and the placement is dropped without a word - which sent
		// the lag display back to the middle of the screen every session, and
		// later the quiz timer and the mask timer, because those two were added
		// below the line rather than above it. There is no line now: the config
		// is read once everything has said what it owns.
		DungeonMap.initialize()
		DungeonScore.initialize()
		DoorKeys.initialize()
		DoorHighlight.initialize()
		BreakerHelper.initialize()
		RoomAlerts.initialize()
		LagDetector.initialize()
		SmartTickTimer.initialize()
		SbKick.initialize()
		DungeonRun.initialize()
		// Before any module, so the room's secret count is read ahead of the
		// Secrets counter taking it out of the action bar.
		RoomSecrets.initialize()
		ClassNames.initialize()
		Etherwarp.initialize()
		LeapMessage.initialize()
		WitherCloakEffect.initialize()
		CustomNametags.initialize()
		Highlight.initialize()
		Secrets.initialize()
		ArrowHitboxes.initialize()
		DeviceSolver.initialize()
		TerminalEsp.initialize()
		TerminalOrder.initialize()
		TerminalTimes.initialize()
		InvincibilityTimer.initialize()
		GyroHelper.initialize()
		CroesusHelper.initialize()
		BossTimings.initialize()
		GateHighlight.initialize()
		F7Qol.initialize()
		AutoGfs.initialize()
		AutoRequeue.initialize()
		SpiritBear.initialize()
		LividSolver.initialize()
		PositionalMessages.initialize()
		DungeonWaypoints.initialize()
		BossWaypoints.initialize()
		SpiritLeapOverlay.initialize()
		BloodCamp.initialize()
		PuzzleSolver.initialize()
		MageBeam.initialize()
		NucleusQol.initialize()
		BlockOverlay.initialize()
		CarryManager.initialize()
		KeybindManager.initialize()
		AliasManager.initialize()
		SoundVolumes.initialize()
		UpdateChecker.initialize()
		TerracottaTimer.initialize()
		MelodyHud.initialize()
		BlessingDisplay.initialize()
		PerformanceHud.initialize()
		SpringBootsHelper.initialize()
		PartyFeatures.initialize()
		ExampleModule.initialize()
		PuzzleHud.initialize()
		DungeonWarpCooldown.initialize()
		ILoveGlass.initialize()
		// A new connection is a new server, and its ping and tick rate are its own.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> ServerStats.forget() }

		// Last, so every element and setting above exists to be filled in.
		ConfigManager.initialize()

		// Two subcommands big enough to live with their modules. Brigadier merges
		// a second "cryptic" into the first, so these sit beside the rest.
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommands.literal("cryptic")
					.then(PositionalMessages.command())
					.then(DungeonWaypoints.command())
					.then(BossWaypoints.command()),
			)
		}

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommands.literal("cryptic")
					.executes {
						// Opening immediately here races with ChatScreen closing after it submits
						// the command. The end-of-tick handler below opens it safely instead.
						openGuiRequested = true
						1
					}
					.then(
						ClientCommands.literal("config")
							.then(ClientCommands.literal("import").executes { context ->
								val clipboard = Minecraft.getInstance().keyboardHandler.clipboard
								ConfigManager.importProfile(clipboard).fold(
									onSuccess = { name ->
										context.source.sendFeedback(Component.literal("Imported Cryptic profile '$name'. It was not activated."))
										1
									},
									onFailure = { error ->
										context.source.sendError(Component.literal(error.message ?: "Could not import Cryptic profile"))
										0
									},
								)
							})
							.then(ClientCommands.literal("export").executes { context ->
								ConfigManager.exportActiveProfile().fold(
									onSuccess = { encoded ->
										Minecraft.getInstance().keyboardHandler.clipboard = encoded
										context.source.sendFeedback(Component.literal("Copied the active Cryptic profile to your clipboard."))
										1
									},
									onFailure = { error ->
										context.source.sendError(Component.literal(error.message ?: "Could not export Cryptic profile"))
										0
									},
								)
							}),
					)
					.then(
						// Switches for looking at dungeon-only features outside a
						// dungeon. They last until the game closes, on purpose.
						ClientCommands.literal("debug")
							.then(ClientCommands.literal("witheroutline").executes { context ->
								val enabled = DebugOverrides.toggleOutlineEveryWither()
								val note = if (!WitherOutline.active) {
									" Turn Wither outline on in F7/M7 QOL to see it."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal(
										if (enabled) {
											"Wither Outline debug on: every wither is outlined, anywhere.$note"
										} else {
											"Wither Outline debug off: back to Floor 7 and Master Mode 7 only."
										},
									),
								)
								1
							})
							.then(ClientCommands.literal("withercloak").executes { context ->
								val enabled = DebugOverrides.toggleWitherCloak()
								val note = if (!WitherCloakEffect.module.enabled) {
									" Turn the Custom Wither Cloak module on to see it."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal(
										if (enabled) {
											"Wither Cloak debug on: the shields orbit you anywhere.$note"
										} else {
											"Wither Cloak debug off: back to following Hypixel's veil."
										},
									),
								)
								1
							})
							.then(ClientCommands.literal("leapmessage").executes { context ->
								val preview = DebugOverrides.toggleLeapPreview()
								if (!preview) {
									context.source.sendFeedback(
										Component.literal("Leap Message debug off: announcements go to party chat again."),
									)
									return@executes 1
								}

								context.source.sendFeedback(
									Component.literal("Leap Message debug on: announcements are shown to you instead of sent."),
								)
								// One straight away, so the wording can be read
								// back without a teammate to leap to.
								LeapMessage.simulate(Minecraft.getInstance().player?.name?.string ?: "Steve")
								1
							})
							.then(ClientCommands.literal("map").executes { context ->
								val loaded = DungeonMapReader.toggleSample()
								val note = if (!DungeonMap.module.enabled) {
									" Turn the Dungeon Map module on to see it."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal(
										if (loaded) {
											"Dungeon Map debug on: a sample floor is loaded, so the map draws anywhere.$note"
										} else {
											"Dungeon Map debug off. Last map update: ${DungeonMapReader.status}"
										},
									),
								)
								1
							})
							.then(
								ClientCommands.literal("floor")
									.then(
										ClientCommands.argument("floor", StringArgumentType.word())
											.suggests { _, builder ->
												SharedSuggestionProvider.suggest(DebugOverrides.floorNames, builder)
											}
											.executes { context ->
												val requested = StringArgumentType.getString(context, "floor")
												val active = DebugOverrides.toggleDungeonFloor(requested)
												if (active == null && requested.uppercase(Locale.ROOT) !in DebugOverrides.floorNames) {
													context.source.sendError(
														Component.literal(
															"Unknown floor '$requested'. Options: ${DebugOverrides.floorNames.joinToString(", ")}",
														),
													)
													return@executes 0
												}

												context.source.sendFeedback(
													Component.literal(
														if (active == null) {
															"Dungeon floor debug off: reading the scoreboard again."
														} else {
															val (number, master) = active
															"Dungeon floor debug on: Cryptic reads you as being on " +
																"${if (master) "Master Mode " else ""}floor $number."
														},
													),
												)
												1
											},
									),
							)
							.then(ClientCommands.literal("roomalerts").executes { context ->
								val note = if (!RoomAlerts.module.enabled) {
									" Turn the Room Alerts module on to use it."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal("Showing the Cleared title, so its wording can be read back.$note"),
								)
								RoomAlerts.preview(Minecraft.getInstance())
								1
							})
							.then(ClientCommands.literal("keys").executes { context ->
								val enabled = DebugOverrides.toggleArmorStandKeys()
								val note = if (!DoorKeys.module.enabled) {
									" Turn the Door Keys module on to see it."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal(
										if (enabled) {
											"Door Keys debug on: every armour stand is highlighted, anywhere.$note"
										} else {
											"Door Keys debug off: back to real wither and blood keys."
										},
									),
								)
								// The pling too, so it can be heard without a
								// teammate picking anything up.
								DoorKeys.play()
								1
							})
							.then(ClientCommands.literal("experiments").executes { context ->
								// A switch rather than a question, because the
								// table is a chest and a chest is a screen: chat
								// cannot be typed into at any of the moments worth
								// knowing about, so it has to watch instead of
								// being asked afterwards.
								val watching = ExperimentDebug.toggle()
								if (!watching) {
									context.source.sendFeedback(
										Component.literal("Experiment watch off."),
									)
									return@executes 1
								}

								val note = if (!ExperimentSolver.module.enabled) {
									" Turn the Experiment Solver module on as well."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal(
										"Experiment watch on: every menu and every decision is announced here " +
											"and written to logs/latest.log, slot by slot.$note",
									),
								)
								context.source.sendFeedback(
									Component.literal(
										"Now: ${ExperimentTracker.status} — runner ${ExperimentRunner.status}, " +
											"${ExperimentRunner.renewsUsed} renews used",
									),
								)
								1
							})
							.then(ClientCommands.literal("terminals").executes { context ->
								// Same reason as the experiment watch: a terminal
								// is a chest, chat cannot be typed into behind one,
								// and the simulator drives the same solver without
								// reproducing the fault — so the difference is in
								// what Hypixel sends, and that has to be recorded.
								val watching = TerminalDebug.toggle()
								context.source.sendFeedback(
									Component.literal(
										if (watching) {
											"Terminal watch on: every window, solve and click is announced here and " +
												"written to logs/latest.log with the board it was working from."
										} else {
											"Terminal watch off."
										},
									),
								)
								1
							})
							.then(ClientCommands.literal("pet").executes { context ->
								// What Hypixel calls the pet following you, which
								// is what the guardian reminder reads.
								EquippedPet.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("carry").executes { context ->
								// Which armour stands sit on a slayer boss, and in
								// what order, is the one thing the carry tracker
								// depends on that cannot be checked from outside a
								// lobby with a boss in it.
								VoidgloomBosses.describe(Minecraft.getInstance()).forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("location").executes { context ->
								// Whether SkyBlock and the island are being read,
								// which everything gated on them depends on.
								SkyblockLocation.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("timers").executes { context ->
								// A timer that is not moving looks the same
								// whether its chat line never came, the server's
								// ping is not reaching the counter, or the run is
								// not being read as a run.
								SmartTickTimer.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(BossTimings.timingCommand())
							.then(BossTimings.bossLogCommand())
							.then(ClientCommands.literal("cookie").executes { context ->
								// The tab list's footer is the only place the
								// cookie's remaining time is written down, and
								// what Hypixel puts on which line of it can only
								// be seen from inside SkyBlock.
								BoosterCookie.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("mimic").executes { context ->
								// Which mob Hypixel spawns for the mimic, and what
								// it carries, is only knowable from inside a run.
								Highlight.describeMimics().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("blaze").executes { context ->
								// What every nametag in the room says, and what
								// the solver reads out of it. The health is the
								// whole puzzle, and no log ever shows a nametag.
								BlazeSolver.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("beam").executes { context ->
								// Whether a mage beam counted as a hit is a
								// geometry question nothing on screen answers,
								// and the mark being absent looks the same as
								// the check never running.
								MageBeam.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("highlight").executes { context ->
								// Which entities Cryptic has decided to mark, and
								// why. A box on something that is not a mob can
								// be read off here rather than guessed at.
								Highlight.describeHighlights().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("room").executes { context ->
								// The room the player is in and the turn that was
								// applied to it, which is what every puzzle
								// solver's coordinates are measured from.
								PuzzleSolver.describeRoom().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("devices").executes { context ->
								// The names and positions Hypixel gives the
								// stands behind every terminal, device and lever
								// are the one thing about Goldor's tower that
								// cannot be worked out from outside it.
								Floor7Progress.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("lava").executes { context ->
								// What lava is being drawn as once every mod
								// that swaps fluid models has had its say, which
								// is the one thing looking at the lava cannot
								// tell you when it looks wrong.
								LavaToWater.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("f7").executes { context ->
								// Which Goldor section the Section Complete title
								// thinks it is on, and what it is still waiting for.
								F7Qol.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("waypoints").executes { context ->
								// The room the waypoints are drawn in, which way it
								// is turned, and how many there are to draw.
								DungeonWaypoints.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("requeue").executes { context ->
								// What Auto Requeue is waiting on, if anything.
								AutoRequeue.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("leap").executes { context ->
								// Who the leap menu puts in each corner, and where
								// each face came from, for a menu showing the
								// wrong heads.
								SpiritLeapOverlay.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("melody").executes { context ->
								// Whether the melody relay is connected, and what
								// it has heard from whom.
								MelodyHud.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("inventory").executes { context ->
								// The screen on display and the menu the player
								// holds are two separate things, and an
								// inventory that takes no clicks is the two of
								// them disagreeing. Nothing on screen shows it.
								InventoryWatch.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("terracotta").executes { context ->
								// The real thing needs Sadan's room. With this
								// on, a flower pot placed anywhere starts a
								// timer, which is enough to see it drawn.
								val anywhere = DebugOverrides.toggleTerracottaAnywhere()
								context.source.sendFeedback(
									Component.literal(
										if (anywhere) {
											"Terracotta Timer debug on: any flower pot placed starts a timer, anywhere."
										} else {
											"Terracotta Timer debug off: back to Sadan's room only."
										},
									),
								)
								TerracottaTimer.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("party").executes { context ->
								val preview = DebugOverrides.togglePartyPreview()
								context.source.sendFeedback(
									Component.literal(
										if (preview) {
											"Party debug on: commands are shown to you instead of sent."
										} else {
											"Party debug off: commands go to Hypixel again."
										},
									),
								)
								PartyFeatures.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("partyinvite").executes { context ->
								// An invite from nobody, so the notification
								// and its two keys can be tried without one.
								val note = if (!PartyFeatures.module.enabled) {
									" Turn the Party Features module on to see it."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal("Showing a party invite. Turn on /cryptic debug party first to keep Y from sending anything.$note"),
								)
								PartyFeatures.simulateInvite("Steve")
								1
							})
							.then(ClientCommands.literal("hudsample").executes { context ->
								val sample = DebugOverrides.toggleSampleHudValues()
								context.source.sendFeedback(
									Component.literal(
										if (sample) {
											"HUD sample on: the Blessing Display, Spring Boots Helper, Puzzle HUD, Dungeon Warp Cooldown, Melody HUD and F7/M7 titles show made-up numbers."
										} else {
											"HUD sample off."
										},
									),
								)
								1
							})
							.then(ClientCommands.literal("scan").executes { context ->
								// The world scan is invisible when it works and
								// invisible when it does not, so it can say so.
								DungeonFloor.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("team").executes { context ->
								// Who the map thinks each head is. The pairing is
								// by position — the first marker belongs to the
								// first teammate in the tab list — so the only way
								// to check it is to see both lists side by side.
								DungeonTeam.describePairing().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("score").executes { context ->
								val preview = DebugOverrides.toggleScorePreview()
								if (!preview) {
									context.source.sendFeedback(
										Component.literal("Dungeon Score debug off: announcements go to party chat again."),
									)
									return@executes 1
								}

								context.source.sendFeedback(
									Component.literal(
										"Dungeon Score debug on: announcements are shown to you instead of sent. " +
											"Paul: ${if (MayorPaul.known) MayorPaul.active else "not looked up yet"}.",
									),
								)
								// The line itself straight away, so the wording
								// can be read back outside a run.
								DungeonScore.preview(Minecraft.getInstance())
								1
							})
							.then(
								ClientCommands.literal("setclass")
									.then(
										ClientCommands.argument("class", StringArgumentType.word())
											.suggests { _, builder ->
												SharedSuggestionProvider.suggest(DebugOverrides.classNames, builder)
											}
											.executes { context ->
												val requested = StringArgumentType.getString(context, "class")
												val dungeonClass = DebugOverrides.parseClass(requested)
												if (dungeonClass == null) {
													context.source.sendError(
														Component.literal(
															"Unknown class '$requested'. Options: ${DebugOverrides.classNames.joinToString(", ")}",
														),
													)
													return@executes 0
												}

												val active = DebugOverrides.toggleDungeonClass(dungeonClass)
												context.source.sendFeedback(
													Component.literal(
														if (active == null) {
															"Dungeon class debug off: reading the tab list again."
														} else {
															"Dungeon class debug on: Cryptic reads you as a ${
																active.name.lowercase(Locale.ROOT)
																	.replaceFirstChar { it.uppercase() }
															} in the Catacombs."
														},
													),
												)
												1
											},
									),
							),
					)
					.then(
						// What is actually installed, which is the first thing
						// worth knowing when a fix is supposed to have shipped.
						ClientCommands.literal("version").executes { context ->
							val mod = FabricLoader.getInstance().getModContainer(Cryptic.MOD_ID).orElse(null)
							val version = mod?.metadata?.version?.friendlyString ?: "unknown"
							val minecraft = FabricLoader.getInstance().getModContainer("minecraft")
								.map { it.metadata.version.friendlyString }.orElse("unknown")
							context.source.sendFeedback(
								Component.literal("Cryptic $version for Minecraft $minecraft"),
							)
							1
						},
					)
					.then(
						ClientCommands.literal("hud").executes {
							// Same deferral as the menu: the chat screen is still
							// closing while this runs.
							hudEditorRequested = true
							1
						},
					)
					// The Settings tab's managers, which open whether or not their
					// module is on. Deferred like the rest, for the chat screen.
					.then(ClientCommands.literal("keybinds").executes {
						KeybindsScreen.request()
						1
					})
					.then(ClientCommands.literal("aliases").executes {
						AliasScreen.request()
						1
					})
					.then(ClientCommands.literal("sounds").executes {
						SoundsScreen.request()
						1
					})
					.then(ClientCommands.literal("update").executes {
						UpdateChecker.check(manual = true)
						1
					})
					.then(
						ClientCommands.literal("carry")
							.executes {
								// Same deferral again: the window cannot open
								// while the chat screen is still closing.
								CarryScreen.request()
								1
							}
							.then(ClientCommands.literal("list").executes { context ->
								CarryManager.describeCarries().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(
								ClientCommands.literal("history")
									.executes { context ->
										CarryManager.describeHistory(CARRY_HISTORY_LINES).forEach {
											context.source.sendFeedback(Component.literal(it))
										}
										1
									}
									.then(
										ClientCommands.argument("count", IntegerArgumentType.integer(1, 100))
											.executes { context ->
												val count = IntegerArgumentType.getInteger(context, "count")
												CarryManager.describeHistory(count).forEach {
													context.source.sendFeedback(Component.literal(it))
												}
												1
											},
									),
							)
							.then(
								ClientCommands.literal("add")
									.then(
										ClientCommands.argument("player", StringArgumentType.word())
											.suggests { _, builder ->
												// Whoever is in the lobby, which is
												// usually who is asking.
												SharedSuggestionProvider.suggest(
													Minecraft.getInstance().connection
														?.onlinePlayers
														?.map { it.profile.name }
														.orEmpty(),
													builder,
												)
											}
											.then(
												ClientCommands.argument("tier", IntegerArgumentType.integer(1, 4))
													.then(
														ClientCommands.argument("amount", IntegerArgumentType.integer(1, 999))
															.executes { context ->
																val player = StringArgumentType.getString(context, "player")
																val tier = IntegerArgumentType.getInteger(context, "tier")
																val amount = IntegerArgumentType.getInteger(context, "amount")

																if (!CarryManager.add(player, tier, amount)) {
																	context.source.sendError(
																		Component.literal("Could not add that carry."),
																	)
																	return@executes 0
																}

																context.source.sendFeedback(
																	Component.literal(
																		"§dTracking §b$player §7for §f${amount}× §7Tier §f$tier§7.",
																	),
																)
																1
															},
													),
											),
									),
							)
							.then(
								ClientCommands.literal("remove")
									.then(
										ClientCommands.argument("player", StringArgumentType.word())
											.suggests { _, builder ->
												SharedSuggestionProvider.suggest(CarryManager.trackedNames(), builder)
											}
											.executes { context ->
												val player = StringArgumentType.getString(context, "player")
												val removed = CarryManager.removeByName(player)
												if (removed == 0) {
													context.source.sendError(
														Component.literal("$player is not on the list."),
													)
													return@executes 0
												}

												context.source.sendFeedback(
													Component.literal("§7Removed §b$player §7from the list."),
												)
												1
											},
									),
							),
					)
					.then(
						ClientCommands.literal("termsim").executes {
							// Same deferral again, for the same reason.
							termSimRequested = true
							1
						},
					)
					.then(
						ClientCommands.literal("etherwarp")
							.then(ClientCommands.literal("sound")
								.then(
									ClientCommands.argument("sound", StringArgumentType.word())
										.suggests { _, builder ->
											SharedSuggestionProvider.suggest(Etherwarp.soundIds, builder)
										}
										.executes { context ->
											val requested = StringArgumentType.getString(context, "sound")
											val selected = Etherwarp.selectSound(requested)
											if (selected == null) {
												context.source.sendError(
													Component.literal("Unknown sound '$requested'. Options: ${Etherwarp.soundIds.joinToString(", ")}"),
												)
												0
											} else {
												val note = when {
													!Etherwarp.module.enabled ->
														" Enable the Etherwarp Customization module to hear it in game."
													!Etherwarp.customSound.value ->
														" Turn on its Custom sound setting to hear it in game."
													else -> ""
												}
												context.source.sendFeedback(Component.literal("Etherwarp sound set to $selected.$note"))
												1
											}
										},
								),
							),
					),
			)
		}

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			// Which island, and whether it is SkyBlock at all, for the modules that
			// must not act anywhere else.
			SkyblockLocation.tick(
				client,
				SlotBinds.module.enabled || NucleusQol.module.enabled || CarryManager.module.enabled ||
					LoadoutManager.needsSkyblock ||
					KeybindManager.needsSkyblock ||
					DungeonWarpCooldown.module.enabled,
			)
			Zoom.tick(client)
			// One tab-list scan feeds every module that needs to know the party.
			DungeonTeam.tick(
				client,
				ClassColors.module.enabled || DungeonMap.module.enabled ||
					TerminalOrder.needsTeamTracking || SpiritLeapOverlay.module.enabled ||
					F7Qol.module.enabled || MelodyHud.wanted || GyroHelper.module.enabled ||
					AutoGfs.module.enabled || AutoRequeue.module.enabled || KeybindManager.needsTeam,
			)
			// Which floor the player is on only matters to modules limited to one.
			DungeonLocation.tick(
				client,
				F7Qol.module.enabled || DungeonMap.module.enabled ||
					DoorHighlight.module.enabled ||
					RoomAlerts.module.enabled || BreakerHelper.module.enabled ||
					HiddenMobs.module.enabled || Highlight.module.enabled ||
					Secrets.module.enabled || RenderOptimizer.module.enabled ||
					HidePlayers.module.enabled || TerminalEsp.module.enabled ||
					TerminalOrder.module.enabled || DeviceSolver.needsPhaseTracking ||
					SmartTickTimer.module.enabled || Etherwarp.needsFloorTracking ||
					PuzzleSolver.module.enabled || BloodCamp.module.enabled ||
					InvincibilityTimer.module.enabled ||
					SpiritLeapOverlay.needsFloor7 ||
					TerracottaTimer.module.enabled || BlessingDisplay.module.enabled ||
					MelodyHud.wanted || LagDetector.needsDungeon ||
					WitherCloakEffect.needsDungeon || PuzzleHud.module.enabled ||
					DungeonWarpCooldown.module.enabled || ILoveGlass.module.enabled ||
					KeybindManager.needsDungeon ||
					AutoGfs.module.enabled || AutoRequeue.module.enabled ||
					SpiritBear.needsDungeon || LividSolver.needsDungeon ||
					PositionalMessages.needsDungeon || DungeonWaypoints.needsDungeon ||
					BossWaypoints.needsDungeon || NoItemPlace.needsDungeon || CroesusHelper.module.enabled ||
					(SlotBinds.module.enabled && SlotBinds.dungeonsOnly.value),
			)
			// Which part of Goldor's tower the player is in, for the modules
			// whose devices and terminals repeat in every quarter of it.
			Floor7.tick(
				client,
				TerminalEsp.module.enabled || TerminalOrder.module.enabled ||
					DeviceSolver.needsPhaseTracking || F7Qol.module.enabled || MelodyHud.wanted ||
					NoItemPlace.module.enabled ||
					SpiritLeapOverlay.needsFloor7 || ILoveGlass.needsPhase || KeybindManager.needsPhase,
			)
			// What the section has already had done to it, which only matters
			// to whoever is drawing labels over the things still to do.
			Floor7Progress.tick(client, TerminalOrder.needsProgressTracking)
			// The score's ingredients are read once and shared, map included.
			DungeonStats.tick(
				client,
				DungeonMap.module.enabled || RoomAlerts.module.enabled || SmartTickTimer.module.enabled ||
					PuzzleHud.module.enabled,
			)
			DungeonMap.tick(client)
			PuzzleSolver.tick(client)
			MageBeam.tick(client)
			CroesusHelper.tick(client)
			LoadoutManager.tick(client)
			GyroHelper.tick(client)
			BossTimings.tick(client)
			DungeonScore.tick(client)
			MelodyHud.tick()
			DoorKeys.tick(client)
			NoDebuff.tick(client)
			// Which of the four withers is up only matters while they are colored apart.
			DungeonBoss.tick(client, WitherOutline.needsBossTracking)
			// Escape closes a terminal without the server saying so.
			Terminals.tick(client)
			ExperimentSolver.tick(client)
			DeviceSolver.tick(client)
			TerminalEsp.tick(client)
			Highlight.tick(client)
			Secrets.tick()
			SlotBinds.tick(client)
			CookieReminder.tick(client)
			TimeChanger.tick(client)
			LavaToWater.tick(client)
			DoorFix.tick(client)
			CarryManager.tick(client)
			CarryScreen.openIfRequested(client)
			KeybindsScreen.openIfRequested(client)
			AliasScreen.openIfRequested(client)
			SoundsScreen.openIfRequested(client)
			AliasManager.tick(client)
			CrosshairScreen.openIfRequested(client)
			WitherCloakEffect.tick(client)
			BlessingDisplay.tick(client)
			SpringBootsHelper.tick(client)
			PartyFeatures.tick(client)
			TerracottaTimer.tick()
			ILoveGlass.tick(client)
			AutoGfs.tick(client)
			AutoRequeue.tick(client)
			LividSolver.tick(client)
			PositionalMessages.tick(client)
			DungeonWaypoints.tick(client)
			BossWaypoints.tick(client)
			// Asks the server how far away it is, but only while something is
			// showing the answer.
			ServerStats.tick(client)
			// Last, so it sees the menus as every module above has left them.
			InventoryWatch.tick(client)

			while (nucleusWarpKey.consumeClick()) {
				NucleusQol.onWarpKey(client)
			}

			while (carryManagerKey.consumeClick()) {
				if (CarryManager.module.enabled) CarryScreen.request()
			}

			while (crosshairEditorKey.consumeClick()) {
				if (CrosshairEditor.module.enabled) CrosshairScreen.request()
			}

			while (openGuiKey.consumeClick()) {
				openGuiRequested = true
			}

			if (openGuiRequested) {
				openGuiRequested = false
				openGui(client)
			}

			if (termSimRequested) {
				termSimRequested = false
				TerminalSimulator.open()
			}

			if (hudEditorRequested) {
				hudEditorRequested = false
				Hud.openEditor()
			}
		}
	}

	private fun openGui(client: Minecraft) {
		ImGuiRuntime.open(client) { CrypticScreen() }
	}
}
