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
import imicro.cryptic.feature.LeapPreview
import imicro.cryptic.feature.SmoothTeleport
import imicro.cryptic.feature.UpdateChecker
import imicro.cryptic.feature.CrosshairEditor
import imicro.cryptic.feature.DoorFix
import imicro.cryptic.feature.LavaToWater
import imicro.cryptic.feature.NoItemPlace
import imicro.cryptic.feature.SbKick
import imicro.cryptic.feature.TimeChanger
import imicro.cryptic.feature.CookieReminder
import imicro.cryptic.feature.DailyRewards
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

/** Client-only setup: key mappings, commands, screens and ticks. */
object CrypticClient : ClientModInitializer {
	private val keyCategory = KeyMapping.Category.register(Cryptic.id("cryptic"))
	private const val CARRY_HISTORY_LINES = 10

	/** The short name for "/cryptic". */
	private const val SHORT_COMMAND = "cc"

	/**
	 * Takes the server's own "/cc" out of its command tree, before Fabric adds the client commands
	 * to it for completion, so only Cryptic's subcommands are suggested. Brigadier can't remove a
	 * node, so its maps are edited. Client thread, from the commands packet.
	 */
	@JvmStatic
	fun dropServerCommand(root: com.mojang.brigadier.tree.CommandNode<*>) {
		runCatching {
			for (name in listOf("children", "literals", "arguments")) {
				val field = com.mojang.brigadier.tree.CommandNode::class.java.getDeclaredField(name).apply { isAccessible = true }
				(field.get(root) as? MutableMap<*, *>)?.remove(SHORT_COMMAND)
			}
		}.onFailure { Cryptic.LOGGER.warn("Could not drop the server's /{}", SHORT_COMMAND, it) }
	}

	private var openGuiRequested = false
	private var hudEditorRequested = false
	private var termSimRequested = false

	/** Right shift by default. Once rebound, Minecraft's options file keeps the key. */
	val openGuiKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.open_gui",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_SHIFT,
			keyCategory,
		),
	)

	/**
	 * Unbound by default: a held zoom key depends on the player, and binding C would take it from
	 * whatever else uses it.
	 */
	val zoomKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.zoom",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/** Unbound by default, like the zoom key. */
	val freelookKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.freelook",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/** Unbound by default, like the zoom key. */
	val freecamKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.freecam",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/** Unbound by default, like the zoom key. The menu can rebind it to a mouse button. */
	val autoClickerKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.auto_clicker",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/**
	 * Unbound by default. Pressed over an inventory slot, so it can share a key with something that
	 * only acts in the world.
	 */
	val slotBindKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.slot_bind",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/** Unbound by default. */
	val carryManagerKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.carry_manager",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	/** Unbound by default. */
	val crosshairEditorKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping(
			"key.${Cryptic.MOD_ID}.crosshair_editor",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.value,
			keyCategory,
		),
	)

	override fun onInitializeClient() {
		imicro.cryptic.feature.Toasts.initialize()
		Hud.initialize()
		// Every module registers before the profile is read; a HUD element registered later loses
		// its saved placement.
		DungeonMap.initialize()
		DungeonScore.initialize()
		DoorKeys.initialize()
		DoorHighlight.initialize()
		BreakerHelper.initialize()
		RoomAlerts.initialize()
		LagDetector.initialize()
		SmartTickTimer.initialize()
		imicro.cryptic.feature.F3Features.initialize()
		SbKick.initialize()
		DungeonRun.initialize()
		// Before any module, so the secret count is read before the Secrets counter strips it from
		// the action bar.
		RoomSecrets.initialize()
		ClassNames.initialize()
		Etherwarp.initialize()
		WitherCloakEffect.initialize()
		CustomNametags.initialize()
		Highlight.initialize()
		Secrets.initialize()
		ArrowHitboxes.initialize()
		ClassColors.initialize()
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
		imicro.cryptic.feature.ClassAbilities.initialize()
		imicro.cryptic.feature.ItemProtection.initialize()
		imicro.cryptic.feature.Mining.initialize()
		imicro.cryptic.feature.Fishing.initialize()
		imicro.cryptic.feature.Bazaar.initialize()
		imicro.cryptic.feature.TooltipFeatures.initialize()
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
		CookieReminder.initialize()
		imicro.cryptic.feature.ConfirmDisconnect.initialize()
		imicro.cryptic.feature.ChatExtras.initialize()
		imicro.cryptic.feature.WitherDragons.initialize()
		imicro.cryptic.feature.Relics.initialize()
		imicro.cryptic.feature.PartyFinder.initialize()
		imicro.cryptic.feature.PartyBlacklist.initialize()
		imicro.cryptic.feature.ItemOutline.touch()
		imicro.cryptic.feature.CurrentChat.initialize()
		imicro.cryptic.feature.ClientRestrictions.initialize()
		imicro.cryptic.feature.PartyFinderPages.initialize()
		DailyRewards.initialize()
		// The menu's shape pipelines must be registered before shaders load.
		imicro.cryptic.render.RoundRect.touch()
		BlockOverlay.initialize()
		CarryManager.initialize()
		imicro.cryptic.feature.SlayerFeatures.initialize()
		imicro.cryptic.feature.ChocolateFactory.initialize()
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
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> ServerStats.forget() }

		// Last, so every element and setting exists to be filled in.
		ConfigManager.initialize()

		// Two subcommands that live with their modules; Brigadier merges them into "cryptic".
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
						// Opening now races with the chat screen closing, so the end-of-tick
						// handler opens it.
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
						// Switches for trying dungeon-only features outside a dungeon. They last
						// until the game closes.
						ClientCommands.literal("debug")
							.then(imicro.cryptic.debug.ToastCommand.build())
							.then(ClientCommands.literal("witheroutline").executes { context ->
								val enabled = DebugOverrides.toggleOutlineEveryWither()
								val note = if (!WitherOutline.active) {
									" Turn Wither outline on in F7/M7 Features to see it."
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
									Component.literal("Leap announcement debug on: announcements are shown to you instead of sent."),
								)
								SpiritLeapOverlay.simulateLeap(Minecraft.getInstance().player?.name?.string ?: "Steve")
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
								DoorKeys.play()
								1
							})
							.then(ClientCommands.literal("experiments").executes { context ->
								// A switch rather than a readout: chat can't be typed into while
								// the table's chest is open.
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
								// A switch for the same reason: chat can't be typed into behind a
								// terminal, and the simulator doesn't reproduce what Hypixel sends.
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
								EquippedPet.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("carry").executes { context ->
								// The stand offsets the carry tracker depends on can only be
								// checked in a lobby with a boss.
								VoidgloomBosses.describe(Minecraft.getInstance()).forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("location").executes { context ->
								SkyblockLocation.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(
								ClientCommands.literal("hollows")
									.then(
										ClientCommands.argument("zone", StringArgumentType.greedyString())
											.suggests { _, builder ->
												SharedSuggestionProvider.suggest(DebugOverrides.hollowsZones, builder)
											}
											.executes { context ->
												val zone = DebugOverrides.toggleHollowsZone(StringArgumentType.getString(context, "zone"))
												context.source.sendFeedback(
													Component.literal(
														if (zone == null) {
															"Crystal Hollows debug off: reading the tab list and sidebar again."
														} else {
															"Crystal Hollows debug on: Cryptic reads you as in $zone."
														},
													),
												)
												1
											},
									),
							)
							.then(
								// The daily reward can only be claimed once a day, so the picker,
								// reminder and day can be tried here.
								ClientCommands.literal("reward")
									.then(ClientCommands.literal("sample").executes {
										DailyRewards.requestOpen(DailyRewards.sampleOffer())
										1
									})
									.then(ClientCommands.literal("ad").executes {
										DailyRewards.requestOpen(DailyRewards.sampleOffer(adSeconds = 10))
										1
									})
									.then(
										ClientCommands.literal("streak").then(
											ClientCommands.argument("days", IntegerArgumentType.integer(0, 999)).executes { context ->
												DailyRewards.requestOpen(DailyRewards.sampleOffer(streak = IntegerArgumentType.getInteger(context, "days")))
												1
											},
										),
									)
									.then(ClientCommands.literal("error").executes {
										// A link that doesn't exist, to see the failure screen.
										DailyRewards.openLink("CRYPTIC0")
										1
									})
									.then(ClientCommands.literal("remind").executes {
										DailyRewards.remind()
										1
									})
									.then(ClientCommands.literal("reset").executes { context ->
										DailyRewards.forgetToday()
										context.source.sendFeedback(Component.literal("Forgot today's claim: the reminder will come back."))
										1
									})
									.then(ClientCommands.literal("status").executes { context ->
										DailyRewards.describeStatus().forEach { context.source.sendFeedback(Component.literal(it)) }
										1
									})
									.executes { context ->
										DailyRewards.describeStatus().forEach { context.source.sendFeedback(Component.literal(it)) }
										1
									},
							)
							.then(ClientCommands.literal("block").executes { context ->
								imicro.cryptic.debug.BlockInfo.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("tooltip").executes { context ->
								context.source.sendFeedback(Component.literal(imicro.cryptic.feature.TooltipFeatures.startDump()))
								1
							})
							.then(ClientCommands.literal("detector").executes { context ->
								NucleusQol.describeDetector().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("sounds").executes { context ->
								imicro.cryptic.debug.SoundLog.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("capes").executes { context ->
								imicro.cryptic.feature.Capes.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("barriers").executes { context ->
								NucleusQol.describeBarriers().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("crystals").executes { context ->
								NucleusQol.describeCrystals().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("timers").executes { context ->
								SmartTickTimer.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(BossTimings.timingCommand())
							.then(BossTimings.bossLogCommand())
							.then(ClientCommands.literal("cookie").executes { context ->
								BoosterCookie.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("mimic").executes { context ->
								Highlight.describeMimics().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("blaze").executes { context ->
								BlazeSolver.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("beam").executes { context ->
								MageBeam.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("highlight").executes { context ->
								Highlight.describeHighlights().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("room").executes { context ->
								PuzzleSolver.describeRoom().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("devices").executes { context ->
								Floor7Progress.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("lava").executes { context ->
								LavaToWater.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("f7").executes { context ->
								F7Qol.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("waypoints").executes { context ->
								DungeonWaypoints.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("mining").executes { context ->
								imicro.cryptic.feature.Mining.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("fishing").executes { context ->
								imicro.cryptic.feature.Fishing.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("bazaar").executes { context ->
								imicro.cryptic.feature.Bazaar.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("requeue").executes { context ->
								AutoRequeue.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("leap").executes { context ->
								SpiritLeapOverlay.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("guilog").executes { context ->
								context.source.sendFeedback(Component.literal("§8[Cryptic] §7" + imicro.cryptic.debug.GuiLog.toggle()))
								1
							})
							.then(ClientCommands.literal("leap_overlay").executes { context ->
								context.source.sendFeedback(Component.literal("§8[Cryptic] " + SpiritLeapOverlay.openDebug()))
								1
							})
							.then(ClientCommands.literal("secret_sync").executes { context ->
								imicro.cryptic.dungeon.SecretSync.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("melody").executes { context ->
								MelodyHud.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("inventory").executes { context ->
								InventoryWatch.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("terracotta").executes { context ->
								// The real thing needs Sadan's room; with this on, any flower pot
								// starts a timer.
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
							.then(ClientCommands.literal("slayer").executes { context ->
								val anywhere = DebugOverrides.toggleSlayerAnywhere()
								context.source.sendFeedback(
									Component.literal(
										if (anywhere) {
											"Slayer Features debug on: runs outside the End, on any stand named like a Voidgloom."
										} else {
											"Slayer Features debug off: back to the End only."
										},
									),
								)
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
							.then(ClientCommands.literal("friendrequest").executes { context ->
								val note = if (!PartyFeatures.module.enabled) {
									" Turn the Party Features module on to see it."
								} else {
									""
								}
								context.source.sendFeedback(
									Component.literal("Showing a friend request. Turn on /cryptic debug party first to keep Y and N from sending anything.$note"),
								)
								PartyFeatures.simulateFriendRequest("Steve")
								1
							})
							.then(ClientCommands.literal("teleportlog").executes { context ->
								imicro.cryptic.debug.TeleportLog.initialize()
								context.source.sendFeedback(Component.literal(imicro.cryptic.debug.TeleportLog.toggle()))
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
								DungeonFloor.describe().forEach {
									context.source.sendFeedback(Component.literal(it))
								}
								1
							})
							.then(ClientCommands.literal("team").executes { context ->
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
							// Deferred like the menu: chat is still closing.
							hudEditorRequested = true
							1
						},
					)
					// The Settings tab's managers, which open whether or not their module is on.
					.then(ClientCommands.literal("keybinds").executes {
						imicro.cryptic.gui.menu.MenuScreen.request(KeybindManager.module)
						1
					})
					.then(ClientCommands.literal("aliases").executes {
						imicro.cryptic.gui.menu.MenuScreen.request(AliasManager.module)
						1
					})
					.then(ClientCommands.literal("sounds").executes {
						imicro.cryptic.gui.menu.MenuScreen.request(SoundVolumes.module)
						1
					})
					.then(imicro.cryptic.feature.PartyBlacklist.command())
					.then(ClientCommands.literal("reward").executes {
						DailyRewards.reopen()
						1
					})
					.then(ClientCommands.literal("update").executes {
						UpdateChecker.check(manual = true)
						1
					})
					.then(
						ClientCommands.literal("carry")
							.executes {
								imicro.cryptic.gui.menu.MenuScreen.request(CarryManager.module)
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
												// Whoever is in the lobby, usually who is asking.
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

		// "/cc" for everything "/cryptic" does. Registered after both "cryptic" callbacks so the
		// node exists; a server's own /cc is dropped from completion when its commands arrive.
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			val cryptic = dispatcher.root.getChild("cryptic") ?: return@register
			dispatcher.register(ClientCommands.literal(SHORT_COMMAND).executes(cryptic.command).redirect(cryptic))
		}

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			SkyblockLocation.tick(
				client,
				SlotBinds.module.enabled || NucleusQol.module.enabled || CarryManager.module.enabled || imicro.cryptic.feature.SlayerFeatures.needsSkyblock || imicro.cryptic.feature.ChocolateFactory.needsSkyblock ||
					LoadoutManager.needsSkyblock || imicro.cryptic.feature.ItemProtection.module.enabled ||
					imicro.cryptic.feature.Mining.needsSkyblock || RenderOptimizer.needsSkyblock || imicro.cryptic.feature.Fishing.needsSkyblock || imicro.cryptic.feature.Bazaar.needsSkyblock || imicro.cryptic.feature.TooltipFeatures.needsSkyblock ||
					KeybindManager.needsSkyblock ||
					SmoothTeleport.predictive ||
					DungeonWarpCooldown.module.enabled,
			)
			Zoom.tick(client)
			// One tab-list scan feeds every module that needs the party.
			DungeonTeam.tick(
				client,
				ClassColors.module.enabled || DungeonMap.module.enabled ||
					TerminalOrder.needsTeamTracking || SpiritLeapOverlay.module.enabled ||
					F7Qol.module.enabled || MelodyHud.wanted || GyroHelper.module.enabled ||
					AutoGfs.module.enabled || AutoRequeue.module.enabled || KeybindManager.needsTeam ||
					imicro.cryptic.feature.ClassAbilities.module.enabled || imicro.cryptic.feature.PartyKick.needsTeam,
			)
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
					imicro.cryptic.feature.F3Features.module.enabled ||
					PuzzleSolver.module.enabled || BloodCamp.module.enabled ||
					InvincibilityTimer.module.enabled ||
					SpiritLeapOverlay.needsDungeon ||
					TerracottaTimer.module.enabled || BlessingDisplay.module.enabled ||
					MelodyHud.wanted || LagDetector.needsDungeon ||
					WitherCloakEffect.needsDungeon || PuzzleHud.module.enabled ||
					DungeonWarpCooldown.module.enabled || ILoveGlass.module.enabled ||
					KeybindManager.needsDungeon ||
					AutoGfs.module.enabled || AutoRequeue.module.enabled ||
					SpiritBear.needsDungeon || LividSolver.needsDungeon ||
					PositionalMessages.needsDungeon || DungeonWaypoints.needsDungeon ||
					BossWaypoints.needsDungeon || NoItemPlace.needsDungeon || CroesusHelper.module.enabled ||
					(SlotBinds.module.enabled && SlotBinds.dungeonsOnly.value) || imicro.cryptic.feature.WitherDragons.module.enabled ||
					imicro.cryptic.feature.Relics.module.enabled || imicro.cryptic.feature.ClassAbilities.module.enabled ||
					imicro.cryptic.feature.ItemProtection.module.enabled,
			)
			Floor7.tick(
				client,
				TerminalEsp.module.enabled || TerminalOrder.module.enabled ||
					DeviceSolver.needsPhaseTracking || F7Qol.module.enabled || MelodyHud.wanted ||
					NoItemPlace.module.enabled ||
					SpiritLeapOverlay.needsFloor7 || ILoveGlass.needsPhase || KeybindManager.needsPhase || SmoothTeleport.predictive ||
						imicro.cryptic.feature.WitherDragons.needsPhase || imicro.cryptic.feature.Relics.needsPhase,
			)
			Floor7Progress.tick(client, TerminalOrder.needsProgressTracking)
			DungeonStats.tick(
				client,
				DungeonMap.module.enabled || RoomAlerts.module.enabled || SmartTickTimer.module.enabled ||
					PuzzleHud.module.enabled,
			)
			DungeonMap.tick(client)
			PuzzleSolver.tick(client)
			MageBeam.tick(client)
			RenderOptimizer.tick(client)
			SmoothTeleport.tick()
			CroesusHelper.tick(client)
			LoadoutManager.tick(client)
			NucleusQol.tick(client)
			GyroHelper.tick(client)
			BossTimings.tick(client)
			DungeonScore.tick(client)
			MelodyHud.tick()
			DoorKeys.tick(client)
			NoDebuff.tick(client)
			DungeonBoss.tick(client, WitherOutline.needsBossTracking)
			// Escape closes a terminal without the server saying so.
			Terminals.tick(client)
			ExperimentSolver.tick(client)
			DeviceSolver.tick(client)
			TerminalEsp.tick(client)
			Highlight.tick(client)
			Secrets.tick()
			SlotBinds.tick(client)
			LeapPreview.tick()
			imicro.cryptic.feature.ChatExtras.tick()
			imicro.cryptic.feature.Relics.tick()
			imicro.cryptic.debug.GuiLog.tick()
			imicro.cryptic.feature.PartyFinderPages.tick()
			CookieReminder.tick(client)
			TimeChanger.tick(client)
			LavaToWater.tick(client)
			DoorFix.tick(client)
			CarryManager.tick(client)
			imicro.cryptic.feature.SlayerFeatures.tick(client)
			imicro.cryptic.feature.ChocolateFactory.tick(client)
			imicro.cryptic.gui.menu.MenuScreen.openIfRequested(client)
			AliasManager.tick(client)
			WitherCloakEffect.tick(client)
			BlessingDisplay.tick(client)
			SpringBootsHelper.tick(client)
			PartyFeatures.tick(client)
			TerracottaTimer.tick()
			ILoveGlass.tick(client)
			AutoGfs.tick(client)
			AutoRequeue.tick(client)
			imicro.cryptic.feature.ClassAbilities.tick(client)
			imicro.cryptic.feature.ItemProtection.tick(client)
			imicro.cryptic.feature.Mining.tick(client)
			imicro.cryptic.feature.Fishing.tick(client)
			imicro.cryptic.feature.Bazaar.tick(client)
			imicro.cryptic.feature.TooltipFeatures.tick()
			imicro.cryptic.feature.QueueScreen.tick(client)
			imicro.cryptic.feature.PartyKick.tick()
			imicro.cryptic.feature.Freelook.tick(client)
			imicro.cryptic.feature.Freecam.tick(client)
			imicro.cryptic.feature.Capes.tick()
			imicro.cryptic.feature.ItemOutline.tick(client)
			LividSolver.tick(client)
			PositionalMessages.tick(client)
			DungeonWaypoints.tick(client)
			BossWaypoints.tick(client)
			// Asks for the ping only while something shows it.
			ServerStats.tick(client)
			// Last, so it sees the menus as every module left them.
			InventoryWatch.tick(client)

			while (carryManagerKey.consumeClick()) {
				if (CarryManager.module.enabled) imicro.cryptic.gui.menu.MenuScreen.request(CarryManager.module)
			}

			while (crosshairEditorKey.consumeClick()) {
				if (CrosshairEditor.module.enabled) imicro.cryptic.gui.menu.MenuScreen.request(CrosshairEditor.module)
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
		client.gui.setScreen(imicro.cryptic.gui.menu.MenuScreen())
	}
}
