package imicro.cryptic.mixin;

import imicro.cryptic.dungeon.BossTimings;
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
import imicro.cryptic.feature.MageBeam;
import imicro.cryptic.feature.MelodyHud;
import imicro.cryptic.feature.PartyFeatures;
import imicro.cryptic.feature.PuzzleSolver;
import imicro.cryptic.feature.RenderOptimizer;
import imicro.cryptic.feature.RoomAlerts;
import imicro.cryptic.experiment.ExperimentTracker;
import imicro.cryptic.feature.Secrets;
import imicro.cryptic.feature.SmartTickTimer;
import imicro.cryptic.feature.SpringBootsHelper;
import imicro.cryptic.debug.InventoryWatch;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets modules swallow a server sound so they can play their own instead. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void cryptic$replaceEtherwarpSound(ClientboundSoundPacket packet, CallbackInfo info) {
        // A bat dying is a secret found, and its own death sound is the only
        // announcement Hypixel gives — there is no chat line and no entity left
        // to look at by the time anything else could notice.
        if (packet.getSound().value() == SoundEvents.BAT_DEATH) {
            Secrets.onBatDied(packet.getX(), packet.getY(), packet.getZ());
            DungeonWaypoints.onBatDied(packet.getX(), packet.getY(), packet.getZ());
        }
        // The Spring Boots charge is a note that climbs in pitch, and nothing else.
        SpringBootsHelper.onSound(packet);
        GyroHelper.onSound(packet);
        if (Etherwarp.replaceTeleportSound(packet)) info.cancel();
    }

    /**
     * A teleport landing, which is the answer to a click Etherwarp predicted.
     *
     * The rotation is read before the packet is applied and put back after,
     * rather than the packet being rewritten: by then the client has already
     * accepted the teleport with the angles the server asked for, so the
     * server is told what it expects and only the view is kept.
     */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void cryptic$beforeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        EtherwarpZeroPing.beforeServerTeleport();
        // Before the move is applied, not after. A teleport onto an exact half
        // block is the maze throwing you about, and the solver needs to know
        // which pad threw you - which is the one you are standing on right now
        // and will not be standing on a line later.
        PuzzleSolver.onTeleport(packet);
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void cryptic$afterTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        EtherwarpZeroPing.afterServerTeleport();
        DungeonWaypoints.onTeleport();
    }

    /**
     * A single block changing, which for the Creeper Beams is one being broken.
     *
     * Taken at the head so the block that was there can still be read: the
     * packet carries what it is becoming, and the pair of them is what says a
     * lantern has gone out.
     */
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void cryptic$noteBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        // A packet handler runs twice: once as it arrives, on the network
        // thread, and again on the client thread once it has been handed over.
        // Only the second one may read the world, and the block being replaced
        // is still in it either way.
        if (!client.isSameThread() || client.level == null) return;
        PuzzleSolver.onBlockUpdate(packet.getPos(), client.level.getBlockState(packet.getPos()), packet.getBlockState());
    }

    /**
     * The knock Hypixel plays on a puzzle's own block when it is finished,
     * which is the only announcement several of them make.
     */
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void cryptic$notePuzzleComplete(ClientboundBlockEventPacket packet, CallbackInfo info) {
        PuzzleSolver.onBlockEvent(packet.getPos(), packet.getBlock());
    }

    /**
     * An item secret, which is picked up rather than clicked. The item entity
     * has to be read before the packet removes it from the world.
     */
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void cryptic$noteSecretPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        // The game thread's pass only. See cryptic$dropServerSneak for why an
        // entity ID must not be asked for from the network thread.
        if (!client.isSameThread()) return;
        if (client.level == null || client.player == null) return;
        if (packet.getPlayerId() != client.player.getId()) return;
        if (!(client.level.getEntity(packet.getItemId()) instanceof ItemEntity item)) return;
        if (!Secrets.isSecretDrop(item.getItem())) return;
        Secrets.onSecretItemPickedUp(item.blockPosition());
        DungeonWaypoints.onItemPickedUp(item.blockPosition());
    }

    /**
     * The mimic says nothing when it dies, so the death animation is what
     * announces it. On a floor that can hold one, the only baby zombie dying is
     * the mimic — the same test Odin makes.
     *
     * A slayer boss somebody is being carried through says nothing either, and
     * for the same reason is counted from the same signal.
     */
    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void cryptic$noteMimicDeath(ClientboundEntityEventPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || packet.getEventId() != 3) return;

        Entity dying = packet.getEntity(client.level);
        if (dying != null) CarryManager.onEntityDied(dying.getId());
        // A bat secret taken, wherever the bat had flown to.
        if (dying instanceof Bat) DungeonWaypoints.onBatDied(dying.getX(), dying.getY(), dying.getZ());

        if (dying instanceof Zombie zombie && zombie.isBaby() && DungeonRun.INSTANCE.mimicCouldDieNow()) {
            // Both halves: the title, and the two bonus points the score has
            // been missing all run because nothing else told it.
            DungeonStats.INSTANCE.onMimicKilled();
            RoomAlerts.onMimicKilled();
        }
    }

    /** Feeds Hypixel's dungeon map item to the map the HUD draws. */
    @Inject(method = "handleMapItemData", at = @At("TAIL"))
    private void cryptic$readDungeonMap(ClientboundMapItemDataPacket packet, CallbackInfo info) {
        DungeonMapReader.INSTANCE.accept(packet);
    }

    /**
     * The terminal solver reads the chest Hypixel opens rather than the screen
     * the game builds from it, because the title is what says which of the six
     * terminals this is and the contents are the puzzle itself.
     */
    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void cryptic$terminalOpened(ClientboundOpenScreenPacket packet, CallbackInfo info) {
        Terminals.INSTANCE.windowOpened(packet.getTitle().getString(), packet.getContainerId());
        ExperimentTracker.INSTANCE.windowOpened(packet.getTitle().getString());
        InventoryWatch.INSTANCE.note("server opened " + packet.getContainerId() + " \"" + packet.getTitle().getString() + "\"");
        Secrets.closesChest(packet.getContainerId(), packet.getType(), packet.getTitle().getString());
    }

    /**
     * Drops an entity before the world has it.
     *
     * The head rather than the tail, and before the packet is handed to the
     * client thread, because the cheapest thing to not draw is a thing that was
     * never added.
     */
    @Inject(method = "handleAddEntity", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideSpawnedEntity(ClientboundAddEntityPacket packet, CallbackInfo info) {
        BossTimings.onEntityAdded(packet.getType());
        if (RenderOptimizer.blocksSpawn(packet.getType())) info.cancel();
    }

    @Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideParticles(ClientboundLevelParticlesPacket packet, CallbackInfo info) {
        // The mage beam reads the particles it is drawn from before deciding
        // whether the game still needs them.
        if (MageBeam.onParticle(packet) || RenderOptimizer.blocksParticle(packet.getParticle())) info.cancel();
    }

    /**
     * Reads what an entity is carrying, which is the only thing that says what
     * some of Hypixel's decorations are. The tail, because this needs the world.
     */
    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void cryptic$hideEquippedDecorations(ClientboundSetEquipmentPacket packet, CallbackInfo info) {
        for (Pair<EquipmentSlot, ItemStack> slot : packet.getSlots()) {
            BloodCamp.onEquipment(packet.getEntity(), slot.getFirst(), slot.getSecond());
            if (RenderOptimizer.hidesEquipment(slot.getFirst(), slot.getSecond())) {
                RenderOptimizer.discard(packet.getEntity());
                return;
            }
        }
    }

    /**
     * One step of an entity's walk, straight off the wire.
     *
     * Blood Camp builds a mob's heading out of these: the entity itself is
     * moved by interpolation between them, so a direction taken from the
     * entity wobbles where one taken from the packets is straight.
     */
    @Inject(method = "handleMoveEntity", at = @At("TAIL"))
    private void cryptic$noteEntityStep(ClientboundMoveEntityPacket packet, CallbackInfo info) {
        BloodCamp.onEntityMove(packet);
    }

    /**
     * Holds the sky at the time the player chose, for Time Changer.
     *
     * The server announces the time regularly, and that announcement is exactly
     * what would otherwise put the world's own clock back.
     */
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
        // Once a second, which is what the TPS falls back on where there is no
        // per-tick ping to count.
        ServerStats.onTimePacket();
        if (!TimeChanger.overridesServerTime()) return;
        TimeChanger.apply();
        info.cancel();
    }

    /**
     * Drops the server's opinion of whether you are crouching, for Camera.
     *
     * Index six of the shared entity data is the pose flags. Hypixel resends
     * them at times the client has already moved on from, which is what leaves
     * a sneak needing a second press to take.
     */
    @Inject(method = "handleSetEntityData", at = @At("HEAD"))
    private void cryptic$dropServerSneak(ClientboundSetEntityDataPacket packet, CallbackInfo info) {
        if (!CameraTweaks.fixesDoubleSneak()) return;
        // HEAD runs twice: first on the network thread, then again on the game
        // thread, still before the data is applied. Only the second pass is
        // safe. On joining, the game thread builds a new player and gives it
        // its ID a moment later, and asking an entity for an ID it has not
        // been given throws — inside a packet handler, which Minecraft answers
        // by disconnecting. That was the occasional kick on joining Hypixel.
        if (!Minecraft.getInstance().isSameThread()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getId() != packet.id()) return;
        packet.packedItems().removeIf(value -> value.id() == 6);
    }

    /**
     * The tab list changing, which is where secrets, crypts, rooms and deaths
     * are written. The score reads it on the next tick instead of waiting out
     * its half-second poll, the way Odin reads it on every one of these.
     */
    @Inject(method = "handlePlayerInfoUpdate", at = @At("RETURN"))
    private void cryptic$noteTabList(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo info) {
        DungeonStats.markChanged();
    }

    /**
     * A sidebar line changing, for the Smart Tick Timer's secret spawn timer.
     *
     * Hypixel draws each line of the sidebar as a team's prefix and suffix, and
     * rewrites the "Time Elapsed" one the moment a second turns over — which is
     * exactly the moment the secret timer counts from.
     *
     * On the network thread's pass, not the game thread's. Server ticks are
     * counted on the network thread as they arrive, and when the rewrite and a
     * tick come in the same burst the tick was counted first and then undone by
     * a reset the game thread got round to a frame later — which left the timer
     * one tick behind Odin's, every second. Read here, both are taken in the
     * order the server sent them. Only the packet is read and one number set,
     * so there is nothing that can throw on this thread.
     */
    @Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"))
    private void cryptic$noteScoreboardLine(ClientboundSetPlayerTeamPacket packet, CallbackInfo info) {
        if (Minecraft.getInstance().isSameThread()) return;
        packet.getParameters().ifPresent(team ->
            SmartTickTimer.onScoreboardLine(team.playerPrefix().getString(), team.playerSuffix().getString()));
        // "Cleared: N%" lives on the sidebar too, so the score reads it now.
        DungeonStats.markChanged();
    }

    /**
     * Reads an entity's nametag and the item it holds, which between them
     * identify a corpse still standing and the archer passive's bone meal.
     */
    /** A stand getting its name, which is when Three Weirdos can send a click it was holding. */
    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void cryptic$instantWeirdos(ClientboundSetEntityDataPacket packet, CallbackInfo info) {
        PuzzleSolver.onEntityData(packet.id());
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

    /**
     * The container id is checked because this also carries updates to the
     * player's own inventory, whose slot numbers would otherwise be read as
     * changes to the puzzle.
     */
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void cryptic$terminalSlotChanged(ClientboundContainerSetSlotPacket packet, CallbackInfo info) {
        if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
                && packet.getContainerId() == screen.getMenu().containerId) {
            Terminals.INSTANCE.slotUpdated(packet.getSlot(), screen.getMenu().getItems());
            ExperimentTracker.INSTANCE.slotUpdated(screen.getMenu().getItems());
        }
    }

    /**
     * Hypixel fills a terminal a slot at a time, but a window that arrives in
     * one packet has to solve too, so it is reported as the last slot changing
     * — which is the moment a terminal is considered complete.
     */
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
     * The server's answer to a ping, which is how far away it is.
     *
     * This handler is one of the few the game runs straight off the network
     * thread, which is what makes the timing honest: the answer is timed as it
     * arrives rather than whenever the next frame gets to it. Nothing here but
     * a subtraction and a number stored.
     */
    @Inject(method = "handlePongResponse", at = @At("HEAD"))
    private void cryptic$notePong(ClientboundPongResponsePacket packet, CallbackInfo info) {
        ServerStats.onPong(packet.time());
    }
    /**
     * A line of chat, read off the packet itself.
     *
     * Fabric's message event is only raised for a message every other mod has
     * agreed to show, so a mod that hides or rewrites a line takes it away from
     * whoever is listening after it. A party invite and a party command are
     * both things that must not be missed that way, and reading them here —
     * where Odin and NoammAddons read their chat — cannot be. After the hand-off
     * to the client thread, so nothing here runs on the network's.
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
        BossTimings.onSystemChat(packet.content(), packet.overlay());
        SmartTickTimer.onSystemChat(packet.content(), packet.overlay());
        DeviceSolver.onSystemChat(packet.content(), packet.overlay());
        GyroHelper.onSystemChat(packet.content(), packet.overlay());
        Secrets.onSystemChat(packet.content(), packet.overlay());
        PartyFeatures.onSystemChat(packet.content(), packet.overlay());
        DungeonWarpCooldown.onSystemChat(packet.content(), packet.overlay());
        F7Qol.onSystemChat(packet.content(), packet.overlay());
        MelodyHud.onSystemChat(packet.content(), packet.overlay());
        AutoRequeue.onSystemChat(packet.content(), packet.overlay());
        AutoGfs.onSystemChat(packet.content(), packet.overlay());
        DungeonWaypoints.onSystemChat(packet.content(), packet.overlay());
        LividSolver.onSystemChat(packet.content(), packet.overlay());
        // Last, once everything above has read it: the party list asked for
        // at the end of a run is kept out of chat.
        if (AutoRequeue.hidesChat(packet.content(), packet.overlay())) info.cancel();
    }

    /**
     * The action bar's own packet, the other way Hypixel can send it. The gyro
     * hears Gravity Storm's mana line here as well as in the chat packet.
     * After the hand-off, like the chat.
     */
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
    }

    /**
     * Hypixel's "Steve activated a terminal! (3/7)" title, which Better P3
     * titles draws its own way. It comes as a subtitle, but a title is checked
     * too in case that changes. After the hand-off, like the chat.
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
