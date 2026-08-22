package imicro.cryptic

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
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
import imicro.cryptic.gui.CrypticScreen
import imicro.cryptic.hud.Hud
import imicro.cryptic.config.ConfigManager
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.feature.AutoSprint
import imicro.cryptic.dungeon.DungeonBoss
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
import imicro.cryptic.feature.DoorHighlight
import imicro.cryptic.feature.DoorKeys
import imicro.cryptic.feature.DungeonMap
import imicro.cryptic.feature.DungeonScore
import imicro.cryptic.feature.HiddenMobs
import imicro.cryptic.feature.LeapMessage
import imicro.cryptic.feature.NoDebuff
import imicro.cryptic.feature.RoomAlerts
import imicro.cryptic.feature.Etherwarp
import imicro.cryptic.feature.WitherCloakEffect
import imicro.cryptic.feature.WitherOutline

/** Client-only setup: key mappings and screens belong here, not in [Cryptic]. */
object CrypticClient : ClientModInitializer {
	private val keyCategory = KeyMapping.Category.register(Cryptic.id("cryptic"))
	private var openGuiRequested = false
	private var hudEditorRequested = false

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

	override fun onInitializeClient() {
		Hud.initialize()
		// Elements register before the profile is read, or the placements in it
		// would be applied to a list that is still empty.
		DungeonMap.initialize()
		DungeonScore.initialize()
		DoorKeys.initialize()
		DoorHighlight.initialize()
		BreakerHelper.initialize()
		RoomAlerts.initialize()
		ConfigManager.initialize()
		DungeonRun.initialize()
		ClassNames.initialize()
		Etherwarp.initialize()
		LeapMessage.initialize()
		WitherCloakEffect.initialize()
		CustomNametags.initialize()

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
							.then(ClientCommands.literal("scan").executes { context ->
								// The world scan is invisible when it works and
								// invisible when it does not, so it can say so.
								DungeonFloor.describe().forEach {
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
						ClientCommands.literal("hud").executes {
							// Same deferral as the menu: the chat screen is still
							// closing while this runs.
							hudEditorRequested = true
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
			// One tab-list scan feeds every module that needs to know the party.
			DungeonTeam.tick(
				client,
				ClassColors.module.enabled || DungeonMap.module.enabled,
			)
			// Which floor the player is on only matters to modules limited to one.
			DungeonLocation.tick(
				client,
				WitherOutline.module.enabled || DungeonMap.module.enabled ||
					DungeonScore.module.enabled || DoorHighlight.module.enabled ||
					RoomAlerts.module.enabled || BreakerHelper.module.enabled ||
					HiddenMobs.module.enabled,
			)
			// The score's ingredients are read once and shared, map included.
			DungeonStats.tick(client, DungeonMap.module.enabled || DungeonScore.module.enabled)
			DungeonMap.tick(client)
			DungeonScore.tick(client)
			DoorKeys.tick(client)
			NoDebuff.tick(client)
			RoomAlerts.tick(client)
			// Which of the four withers is up only matters while they are colored apart.
			DungeonBoss.tick(client, WitherOutline.needsBossTracking)
			WitherCloakEffect.tick(client)

			while (openGuiKey.consumeClick()) {
				openGuiRequested = true
			}

			if (openGuiRequested) {
				openGuiRequested = false
				openGui(client)
			}

			if (hudEditorRequested) {
				hudEditorRequested = false
				Hud.openEditor()
			}
		}
	}

	private fun openGui(client: Minecraft) {
		client.setScreen(CrypticScreen())
	}
}
