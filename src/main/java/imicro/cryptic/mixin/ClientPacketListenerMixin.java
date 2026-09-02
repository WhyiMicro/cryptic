package imicro.cryptic.mixin;

import imicro.cryptic.dungeon.map.DungeonMapReader;
import com.mojang.datafixers.util.Pair;
import imicro.cryptic.feature.Etherwarp;
import imicro.cryptic.feature.RenderOptimizer;
import imicro.cryptic.feature.RoomAlerts;
import imicro.cryptic.experiment.ExperimentTracker;
import imicro.cryptic.feature.Secrets;
import imicro.cryptic.terminal.Terminals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
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
        }
        if (Etherwarp.replaceTeleportSound(packet)) info.cancel();
    }

    /**
     * An item secret, which is picked up rather than clicked. The item entity
     * has to be read before the packet removes it from the world.
     */
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void cryptic$noteSecretPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (packet.getPlayerId() != client.player.getId()) return;
        if (!(client.level.getEntity(packet.getItemId()) instanceof ItemEntity item)) return;
        if (!Secrets.isSecretDrop(item.getItem())) return;
        Secrets.onSecretItemPickedUp(item.blockPosition());
    }

    /**
     * The mimic says nothing when it dies, so the death animation is what
     * announces it. On a floor that can hold one, the only baby zombie dying is
     * the mimic — the same test Odin makes.
     */
    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void cryptic$noteMimicDeath(ClientboundEntityEventPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || packet.getEventId() != 3) return;
        if (packet.getEntity(client.level) instanceof Zombie zombie && zombie.isBaby()) {
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
        Terminals.INSTANCE.windowOpened(packet.getTitle().getString());
        ExperimentTracker.INSTANCE.windowOpened(packet.getTitle().getString());
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
        if (RenderOptimizer.blocksSpawn(packet.getType())) info.cancel();
    }

    @Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideParticles(ClientboundLevelParticlesPacket packet, CallbackInfo info) {
        if (RenderOptimizer.blocksParticle(packet.getParticle())) info.cancel();
    }

    /**
     * Reads what an entity is carrying, which is the only thing that says what
     * some of Hypixel's decorations are. The tail, because this needs the world.
     */
    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void cryptic$hideEquippedDecorations(ClientboundSetEquipmentPacket packet, CallbackInfo info) {
        for (Pair<EquipmentSlot, ItemStack> slot : packet.getSlots()) {
            if (RenderOptimizer.hidesEquipment(slot.getFirst(), slot.getSecond())) {
                RenderOptimizer.discard(packet.getEntity());
                return;
            }
        }
    }

    /**
     * Reads an entity's nametag and the item it holds, which between them
     * identify a corpse still standing and the archer passive's bone meal.
     */
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
        if (Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen
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
        if (Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen
                && packet.containerId() == screen.getMenu().containerId) {
            Terminals.INSTANCE.windowFilled(screen.getMenu().getItems());
            ExperimentTracker.INSTANCE.slotUpdated(screen.getMenu().getItems());
        }
        // The first moment a secret chest has anything in it to take out.
        Secrets.onChestFilled(packet.containerId());
    }

    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void cryptic$terminalClosed(ClientboundContainerClosePacket packet, CallbackInfo info) {
        Terminals.INSTANCE.closed();
        ExperimentTracker.INSTANCE.closed();
    }
}
