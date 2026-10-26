package imicro.cryptic.mixin;

import imicro.cryptic.dungeon.BossTimings;
import imicro.cryptic.feature.BetterChat;
import imicro.cryptic.feature.SpiritLeapOverlay;
import imicro.cryptic.feature.TerminalSolver;
import imicro.cryptic.dungeon.DungeonRun;
import imicro.cryptic.dungeon.DungeonStats;
import imicro.cryptic.dungeon.map.DungeonMapReader;
import com.mojang.datafixers.util.Pair;
import imicro.cryptic.feature.AutoGfs;
import imicro.cryptic.feature.DeviceSolver;
import imicro.cryptic.feature.GyroHelper;
import imicro.cryptic.feature.AutoRequeue;
import imicro.cryptic.feature.BloodCamp;
import imicro.cryptic.feature.CameraTweaks;
import imicro.cryptic.feature.CarryManager;
import imicro.cryptic.feature.DungeonWarpCooldown;
import imicro.cryptic.feature.DungeonWaypoints;
import imicro.cryptic.feature.LividSolver;
import imicro.cryptic.feature.F7Qol;
import imicro.cryptic.feature.Etherwarp;
import imicro.cryptic.feature.EtherwarpZeroPing;
import imicro.cryptic.feature.SmoothTeleport;
import imicro.cryptic.feature.MageBeam;
import imicro.cryptic.feature.MelodyHud;
import imicro.cryptic.feature.NucleusQol;
import imicro.cryptic.feature.CookieReminder;
import imicro.cryptic.feature.DailyRewards;
import imicro.cryptic.feature.PartyFeatures;
import imicro.cryptic.feature.PuzzleSolver;
import imicro.cryptic.feature.RenderOptimizer;
import imicro.cryptic.feature.RoomAlerts;
import imicro.cryptic.experiment.ExperimentTracker;
import imicro.cryptic.feature.Secrets;
import imicro.cryptic.feature.SmartTickTimer;
import imicro.cryptic.feature.SpringBootsHelper;
import imicro.cryptic.debug.InventoryWatch;
import imicro.cryptic.debug.SoundLog;
import imicro.cryptic.skyblock.ServerStats;
import imicro.cryptic.feature.TimeChanger;
import imicro.cryptic.terminal.Terminals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets modules swallow a server sound to play their own. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @org.spongepowered.asm.mixin.Shadow
    private com.mojang.brigadier.CommandDispatcher<net.minecraft.client.multiplayer.ClientSuggestionProvider> commands;

    /** The server's commands, just read: its own /cc gives way to Cryptic's. Client thread only. */
    @Inject(
        method = "handleCommands",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;commands:Lcom/mojang/brigadier/CommandDispatcher;",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
        )
    )
    private void cryptic$dropServerShortCommand(net.minecraft.network.protocol.game.ClientboundCommandsPacket packet, CallbackInfo info) {
        imicro.cryptic.CrypticClient.dropServerCommand(this.commands.getRoot());
    }

    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void cryptic$replaceEtherwarpSound(ClientboundSoundPacket packet, CallbackInfo info) {
        // A bat dying is a secret found, and its death sound is Hypixel's only announcement.
        if (packet.getSound().value() == SoundEvents.BAT_DEATH) {
            Secrets.onBatDied(packet.getX(), packet.getY(), packet.getZ());
            DungeonWaypoints.onBatDied(packet.getX(), packet.getY(), packet.getZ());
        }
        SpringBootsHelper.onSound(packet);
        imicro.cryptic.feature.WitherDragons.onSound(packet);
        GyroHelper.onSound(packet);
        NucleusQol.onSound(packet.getSound().value(), packet.getPitch(), packet.getVolume());
        SoundLog.note(packet.getSound().value(), packet.getPitch(), packet.getVolume(), false);
        if (Etherwarp.replaceTeleportSound(packet)) {
            SoundLog.dropped(packet.getSound().value(), packet.getPitch(), packet.getVolume(), "Etherwarp");
            info.cancel();
            return;
        }
        // Hypixel's terminal click sound, while a chosen one plays instead.
        if (TerminalSolver.eatsServerSound(packet)) {
            SoundLog.dropped(packet.getSound().value(), packet.getPitch(), packet.getVolume(), "Terminal Solver");
            info.cancel();
        }
    }

    /** A sound played on an entity, as Hypixel sends some of its own (treasure chests). */
    @Inject(method = "handleSoundEntityEvent", at = @At("HEAD"))
    private void cryptic$noteEntitySound(ClientboundSoundEntityPacket packet, CallbackInfo info) {
        NucleusQol.onSound(packet.getSound().value(), packet.getPitch(), packet.getVolume());
        SoundLog.note(packet.getSound().value(), packet.getPitch(), packet.getVolume(), true);
    }

    /**
     * A teleport landing. The rotation is read before the packet and put back after, so the server
     * gets the acceptance it expects and only the view is kept.
     */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void cryptic$beforeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        imicro.cryptic.debug.TeleportLog.packet(packet);
        EtherwarpZeroPing.beforeServerTeleport(packet);
        SmoothTeleport.beforeServerTeleport();
        // Before the move: the maze solver needs the pad you're standing on as you're thrown.
        PuzzleSolver.onTeleport(packet);
        SpiritLeapOverlay.beforeServerTeleport(packet);
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void cryptic$afterTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        EtherwarpZeroPing.afterServerTeleport();
        SmoothTeleport.afterServerTeleport();
        imicro.cryptic.debug.TeleportLog.packetApplied();
        DungeonWaypoints.onTeleport();
        // Last, so nothing above puts the old rotation back over it.
        SpiritLeapOverlay.afterServerTeleport(packet);
    }

    /**
     * A single block changing (for Creeper Beams, one breaking). At the head, so the old block can
     * still be read.
     */
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void cryptic$noteBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        // Handlers run twice, first on the network thread; only the client-thread pass may read the
        // world.
        if (!client.isSameThread() || client.level == null) return;
        PuzzleSolver.onBlockUpdate(packet.getPos(), client.level.getBlockState(packet.getPos()), packet.getBlockState());
        imicro.cryptic.feature.WitherDragons.onBlockChanged(packet.getPos(), client.level.getBlockState(packet.getPos()), packet.getBlockState());
    }

    /** The knock Hypixel plays on a finished puzzle's block, the only announcement several make. */
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void cryptic$notePuzzleComplete(ClientboundBlockEventPacket packet, CallbackInfo info) {
        PuzzleSolver.onBlockEvent(packet.getPos(), packet.getBlock());
    }

    /** An item secret picked up; the item has to be read before the packet removes it. */
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void cryptic$noteSecretPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        // Client thread only; see cryptic$dropServerSneak for why.
        if (!client.isSameThread()) return;
        if (client.level == null || client.player == null) return;
        if (packet.getPlayerId() != client.player.getId()) return;
        if (!(client.level.getEntity(packet.getItemId()) instanceof ItemEntity item)) return;
        if (!Secrets.isSecretDrop(item.getItem())) return;
        Secrets.onSecretItemPickedUp(item.blockPosition());
        DungeonWaypoints.onItemPickedUp(item.blockPosition());
    }

    /**
     * The mimic's death animation announces it (only baby zombie on floors that hold one, Odin's
     * test). Carried slayer bosses are counted from the same signal.
     */
    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void cryptic$noteMimicDeath(ClientboundEntityEventPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || packet.getEventId() != 3) return;

        Entity dying = packet.getEntity(client.level);
        if (dying != null) {
            CarryManager.onEntityDied(dying.getId());
            imicro.cryptic.feature.SlayerFeatures.onEntityDied(dying.getId());
        }
        if (dying instanceof Bat) DungeonWaypoints.onBatDied(dying.getX(), dying.getY(), dying.getZ());

        if (dying instanceof Zombie zombie && zombie.isBaby() && DungeonRun.INSTANCE.mimicCouldDieNow()) {
            // The title, and the two bonus points nothing else would tell the score about.
            DungeonStats.INSTANCE.onMimicKilled();
            RoomAlerts.onMimicKilled();
        }
    }

    @Inject(method = "handleMapItemData", at = @At("TAIL"))
    private void cryptic$readDungeonMap(ClientboundMapItemDataPacket packet, CallbackInfo info) {
        DungeonMapReader.INSTANCE.accept(packet);
    }

    /** Reads the chest Hypixel opens: the title says which terminal, the contents are the puzzle. */
    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void cryptic$terminalOpened(ClientboundOpenScreenPacket packet, CallbackInfo info) {
        Terminals.INSTANCE.windowOpened(packet.getTitle().getString(), packet.getContainerId());
        ExperimentTracker.INSTANCE.windowOpened(packet.getTitle().getString());
        InventoryWatch.INSTANCE.note("server opened " + packet.getContainerId() + " \"" + packet.getTitle().getString() + "\"");
        Secrets.closesChest(packet.getContainerId(), packet.getType(), packet.getTitle().getString());
    }

    /**
     * Drops an entity before the world has it, on the network thread, since the cheapest thing not
     * to draw is one never added.
     */
    @Inject(method = "handleAddEntity", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideSpawnedEntity(ClientboundAddEntityPacket packet, CallbackInfo info) {
        BossTimings.onEntityAdded(packet.getType());
        imicro.cryptic.feature.WitherDragons.onAddEntity(packet);
        imicro.cryptic.feature.ClassAbilities.onAddEntity(packet);
        if (RenderOptimizer.blocksSpawn(packet.getType())) info.cancel();
    }

    /** Who hit a slayer boss, for the Carry Manager's guess at its owner. TAIL, so only the client thread. */
    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void cryptic$noteBossHit(net.minecraft.network.protocol.game.ClientboundDamageEventPacket packet, CallbackInfo info) {
        try {
            imicro.cryptic.slayer.VoidgloomBosses.onDamaged(packet.entityId(), packet.sourceCauseId());
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void cryptic$noteSwing(net.minecraft.network.protocol.game.ClientboundAnimatePacket packet, CallbackInfo info) {
        int action = packet.getAction();
        if (action == net.minecraft.network.protocol.game.ClientboundAnimatePacket.SWING_MAIN_HAND
            || action == net.minecraft.network.protocol.game.ClientboundAnimatePacket.SWING_OFF_HAND) {
            imicro.cryptic.slayer.VoidgloomBosses.onSwing(packet.getId());
        }
    }

    @Inject(method = "handleHurtAnimation", at = @At("TAIL"))
    private void cryptic$noteBossHurt(net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket packet, CallbackInfo info) {
        try {
            imicro.cryptic.slayer.VoidgloomBosses.onDamaged(packet.id(), -1);
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideParticles(ClientboundLevelParticlesPacket packet, CallbackInfo info) {
        NucleusQol.onParticle(packet);
        imicro.cryptic.feature.WishingCompass.onParticle(packet);
        imicro.cryptic.feature.WitherDragons.onParticle(packet);
        if (imicro.cryptic.feature.Fishing.onParticle(packet) || MageBeam.onParticle(packet) || RenderOptimizer.blocksParticle(packet)
            || imicro.cryptic.feature.SlayerFeatures.hidesParticle(packet)) info.cancel();
    }

    /**
     * What an entity carries is the only thing that identifies some of Hypixel's decorations. At
     * the tail, since this needs the world.
     */
    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void cryptic$hideEquippedDecorations(ClientboundSetEquipmentPacket packet, CallbackInfo info) {
        imicro.cryptic.feature.WitherDragons.onEquipment(packet);
        for (Pair<EquipmentSlot, ItemStack> slot : packet.getSlots()) {
            BloodCamp.onEquipment(packet.getEntity(), slot.getFirst(), slot.getSecond());
            if (RenderOptimizer.hidesEquipment(slot.getFirst(), slot.getSecond())) {
                RenderOptimizer.discard(packet.getEntity());
                return;
            }
        }
    }

    /**
     * One step of an entity's movement, off the wire. Blood Camp takes headings from these, since
     * the interpolated entity wobbles.
     */
    @Inject(method = "handleMoveEntity", at = @At("TAIL"))
    private void cryptic$noteEntityStep(ClientboundMoveEntityPacket packet, CallbackInfo info) {
        BloodCamp.onEntityMove(packet);
    }

    /** Holds the sky at the chosen time, for Time Changer; the server's time packets would reset it. */
    @Inject(
        method = "handleSetTime",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void cryptic$holdTheClock(ClientboundSetTimePacket packet, CallbackInfo info) {
        // Once a second; what TPS falls back on without per-tick pings.
        ServerStats.onTimePacket();
        if (!TimeChanger.overridesServerTime()) return;
        TimeChanger.apply();
        info.cancel();
    }

    /**
     * Drops the server's crouch flag (data index six), for Camera. Hypixel resends it late, which
     * made a sneak need a second press.
     */
    @Inject(method = "handleSetEntityData", at = @At("HEAD"))
    private void cryptic$dropServerSneak(ClientboundSetEntityDataPacket packet, CallbackInfo info) {
        if (!CameraTweaks.fixesDoubleSneak()) return;
        // Client thread only. On join the player gets its id a moment late, and asking for it on
        // the network thread throws, which disconnects.
        if (!Minecraft.getInstance().isSameThread()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getId() != packet.id()) return;
        packet.packedItems().removeIf(value -> value.id() == 6);
    }

    /** The tab list changing; the score reads it next tick rather than waiting for its poll, as Odin does. */
    @Inject(method = "handlePlayerInfoUpdate", at = @At("RETURN"))
    private void cryptic$noteTabList(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo info) {
        DungeonStats.markChanged();
    }

    /**
     * A sidebar line changing, for the Smart Tick Timer's secret spawn timer, which counts from the
     * "Time Elapsed" rewrite.
     *
     * Read on the network thread so it stays in order with the tick count; on the client thread it
     * was one tick behind Odin's every second. Only one number is set, so nothing can throw here.
     */
    @Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"))
    private void cryptic$noteScoreboardLine(ClientboundSetPlayerTeamPacket packet, CallbackInfo info) {
        if (Minecraft.getInstance().isSameThread()) return;
        packet.getParameters().ifPresent(team ->
            SmartTickTimer.onScoreboardLine(team.playerPrefix().getString(), team.playerSuffix().getString()));
        // "Cleared: N%" is on the sidebar too.
        DungeonStats.markChanged();
    }

    /**
     * An entity's nametag and held item, which identify a standing corpse and the archer passive's
     * bone meal.
     */
    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void cryptic$instantWeirdos(ClientboundSetEntityDataPacket packet, CallbackInfo info) {
        PuzzleSolver.onEntityData(packet.id());
        imicro.cryptic.feature.WitherDragons.onEntityData(packet);
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void cryptic$hideTaggedEntities(ClientboundSetEntityDataPacket packet, CallbackInfo info) {
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            Object raw = value.value();
            if (raw instanceof Optional<?> optional) {
                raw = optional.orElse(null);
            }

            if (raw instanceof Component name && RenderOptimizer.hidesNameTag(name)) {
                RenderOptimizer.discard(packet.id());
                return;
            }
            if (raw instanceof ItemStack item && RenderOptimizer.hidesHeldItem(item)) {
                RenderOptimizer.discard(packet.id());
                return;
            }
        }
    }

    /** Checks the container id, since this also carries the player's own inventory slots. */
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void cryptic$terminalSlotChanged(ClientboundContainerSetSlotPacket packet, CallbackInfo info) {
        if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
                && packet.getContainerId() == screen.getMenu().containerId) {
            Terminals.INSTANCE.slotUpdated(packet.getSlot(), screen.getMenu().getItems());
            ExperimentTracker.INSTANCE.slotUpdated(screen.getMenu().getItems());
        }
    }

    /** A whole window in one packet, reported as its last slot changing. */
    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void cryptic$terminalContentChanged(ClientboundContainerSetContentPacket packet, CallbackInfo info) {
        if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
                && packet.containerId() == screen.getMenu().containerId) {
            Terminals.INSTANCE.windowFilled(screen.getMenu().getItems());
            ExperimentTracker.INSTANCE.slotUpdated(screen.getMenu().getItems());
        }
    }

    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void cryptic$terminalClosed(ClientboundContainerClosePacket packet, CallbackInfo info) {
        InventoryWatch.INSTANCE.note("server closed " + packet.getContainerId());
        Terminals.INSTANCE.closed();
        ExperimentTracker.INSTANCE.closed();
    }
    /**
     * The server's answer to a ping. Runs on the network thread, so the timing isn't delayed by the
     * frame.
     */
    @Inject(method = "handlePongResponse", at = @At("HEAD"))
    private void cryptic$notePong(ClientboundPongResponsePacket packet, CallbackInfo info) {
        ServerStats.onPong(packet.time());
    }
    /**
     * Chat, read from the packet. Fabric's message event misses lines other mods hide, and party
     * invites and commands must not be missed. After the hand-off to the client thread.
     */
    @Inject(
        method = "handleSystemChat",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void cryptic$readSystemChat(ClientboundSystemChatPacket packet, CallbackInfo info) {
        DungeonRun.onSystemChat(packet.content(), packet.overlay());
        if (packet.overlay()) imicro.cryptic.dungeon.RoomSecrets.onActionBar(packet.content());
        BossTimings.onSystemChat(packet.content(), packet.overlay());
        SmartTickTimer.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.F3Features.onSystemChat(packet.content(), packet.overlay());
        DeviceSolver.onSystemChat(packet.content(), packet.overlay());
        GyroHelper.onSystemChat(packet.content(), packet.overlay());
        Secrets.onSystemChat(packet.content(), packet.overlay());
        PartyFeatures.onSystemChat(packet.content(), packet.overlay());
        DungeonWarpCooldown.onSystemChat(packet.content(), packet.overlay());
        F7Qol.onSystemChat(packet.content(), packet.overlay());
        MelodyHud.onSystemChat(packet.content(), packet.overlay());
        AutoRequeue.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.PartyKick.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.SlayerFeatures.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.ChocolateFactory.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.CurrentChat.onSystemChat(packet.content(), packet.overlay());
        AutoGfs.onSystemChat(packet.content(), packet.overlay());
        DungeonWaypoints.onSystemChat(packet.content(), packet.overlay());
        LividSolver.onSystemChat(packet.content(), packet.overlay());
        TerminalSolver.onSystemChat(packet.content(), packet.overlay());
        SpiritLeapOverlay.onSystemChat(packet.content(), packet.overlay());
        NucleusQol.onSystemChat(packet.content(), packet.overlay());
        CookieReminder.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.CroesusHelper.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.Relics.onSystemChat(packet.content(), packet.overlay());
        DailyRewards.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.ClassAbilities.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.Mining.onSystemChat(packet.content(), packet.overlay());
        imicro.cryptic.feature.Bazaar.onSystemChat(packet.content(), packet.overlay());
        // Last: Better Chat hides a line from the chat window, never from the mod.
        if (BetterChat.hidesChat(packet.content(), packet.overlay())
                || AutoRequeue.hidesChat(packet.content(), packet.overlay())
                || imicro.cryptic.feature.Bazaar.hidesChat(packet.content(), packet.overlay())) {
            info.cancel();
        }
    }

    /**
     * The line as the chat window shows it, with Better Chat's short channel names; swapped after
     * everything above has read the original.
     */
    @ModifyArg(
        method = "handleSystemChat",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
        ),
        index = 0
    )
    private Component cryptic$shownSystemChat(Component message) {
        return BetterChat.shown(message);
    }

    /** The action bar's own packet, Hypixel's other way of sending it. Client thread. */
    @Inject(
        method = "setActionBarText",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER
        )
    )
    private void cryptic$readActionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo info) {
        GyroHelper.onActionBarPacket(packet.text());
        imicro.cryptic.dungeon.RoomSecrets.onActionBar(packet.text());
        NucleusQol.onActionBar(packet.text());
    }

    /**
     * Hypixel's "Steve activated a terminal! (3/7)" title for Better P3 titles. Usually a subtitle;
     * titles are checked too.
     */
    @Inject(
        method = "setSubtitleText",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void cryptic$replaceSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo info) {
        if (F7Qol.hidesTitle(packet.text())) info.cancel();
    }

    @Inject(
        method = "setTitleText",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void cryptic$replaceTitle(ClientboundSetTitleTextPacket packet, CallbackInfo info) {
        if (F7Qol.hidesTitle(packet.text())) info.cancel();
    }
}
