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
import imicro.cryptic.gui.CarryScreen
import imicro.cryptic.gui.CrosshairScreen
import imicro.cryptic.gui.CrypticScreen
import imicro.cryptic.gui.ImGuiRuntime
import imicro.cryptic.hud.Hud
import imicro.cryptic.config.ConfigManager
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.feature.ArrowHitboxes
import imicro.cryptic.feature.BlockOverlay
import imicro.cryptic.feature.CameraTweaks
import imicro.cryptic.feature.CarryManager
import imicro.cryptic.feature.CrosshairEditor
import imicro.cryptic.feature.DoorFix
import imicro.cryptic.feature.LavaToWater
import imicro.cryptic.feature.NoItemPlace
import imicro.cryptic.feature.SbKick
import imicro.cryptic.feature.ScrollableTooltips
import imicro.cryptic.feature.TimeChanger
import imicro.cryptic.feature.AutoSprint
import imicro.cryptic.feature.CookieReminder
import imicro.cryptic.feature.LagDetector
import imicro.cryptic.feature.NucleusQol
import imicro.cryptic.feature.SmartTickTimer
import imicro.cryptic.dungeon.DungeonBoss
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.Floor7Progress
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonMapReader
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
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
import imicro.cryptic.feature.Highlight
import imicro.cryptic.feature.RenderOptimizer
import imicro.cryptic.feature.Secrets
import imicro.cryptic.feature.SlotBinds
import imicro.cryptic.feature.LeapMessage
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
		// Every module that owns a HUD element registers *before* the profile is
		// read, or the placement saved in it is applied to a list that does not
		// contain the element yet and is silently dropped — which is what made
		// the lag display go back to the middle of the screen every session.
		DungeonMap.initialize()
		DungeonScore.initialize()
		DoorKeys.initialize()
		DoorHighlight.initialize()
		BreakerHelper.initialize()
		RoomAlerts.initialize()
		LagDetector.initialize()
		SmartTickTimer.initialize()
		SbKick.initialize()
		ConfigManager.initialize()
		DungeonRun.initialize()
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
		NucleusQol.initialize()
		BlockOverlay.initialize()
		CarryManager.initialize()

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
								val note = if (!WitherOutline.module.enabled) {
									" Turn the Wither Outline module on to see it."
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
									" Turn the Wither Cloak Effect module on to see it."
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
			AutoSprint.tick(client)
			// Which island, and whether it is SkyBlock at all, for the modules that
			// must not act anywhere else.
			SkyblockLocation.tick(
				client,
				SlotBinds.module.enabled || NucleusQol.module.enabled || CarryManager.module.enabled,
			)
			Zoom.tick(client)
			// One tab-list scan feeds every module that needs to know the party.
			DungeonTeam.tick(
				client,
				ClassColors.module.enabled || DungeonMap.module.enabled ||
					TerminalOrder.needsTeamTracking,
			)
			// Which floor the player is on only matters to modules limited to one.
			DungeonLocation.tick(
				client,
				WitherOutline.module.enabled || DungeonMap.module.enabled ||
					DoorHighlight.module.enabled ||
					RoomAlerts.module.enabled || BreakerHelper.module.enabled ||
					HiddenMobs.module.enabled || Highlight.module.enabled ||
					Secrets.module.enabled || RenderOptimizer.module.enabled ||
					HidePlayers.module.enabled || TerminalEsp.module.enabled ||
					TerminalOrder.module.enabled || DeviceSolver.needsPhaseTracking ||
					SmartTickTimer.module.enabled || Etherwarp.needsFloorTracking ||
					(SlotBinds.module.enabled && SlotBinds.dungeonsOnly.value),
			)
			// Which part of Goldor's tower the player is in, for the modules
			// whose devices and terminals repeat in every quarter of it.
			Floor7.tick(
				client,
				TerminalEsp.module.enabled || TerminalOrder.module.enabled ||
					DeviceSolver.needsPhaseTracking || DoorFix.needsPhaseTracking ||
					NoItemPlace.module.enabled,
			)
			// What the section has already had done to it, which only matters
			// to whoever is drawing labels over the things still to do.
			Floor7Progress.tick(client, TerminalOrder.needsProgressTracking)
			// The score's ingredients are read once and shared, map included.
			DungeonStats.tick(
				client,
				DungeonMap.module.enabled || RoomAlerts.module.enabled || SmartTickTimer.module.enabled,
			)
			DungeonMap.tick(client)
			DungeonScore.tick(client)
			DoorKeys.tick(client)
			NoDebuff.tick(client)
			// Which of the four withers is up only matters while they are colored apart.
			DungeonBoss.tick(client, WitherOutline.needsBossTracking)
			// Escape closes a terminal without the server saying so.
			Terminals.tick(client)
			// The chest-based render types ask the game for a GUI scale of their
			// own while a terminal is open, and give it back when one closes.
			TerminalSolver.tick(client)
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
			CrosshairScreen.openIfRequested(client)
			// Last, so it sees whether anything drew a tooltip this frame.
			ScrollableTooltips.endFrame()
			WitherCloakEffect.tick(client)

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
