package imicro.cryptic.mixin;

import imicro.cryptic.dungeon.map.DungeonMapReader;
import imicro.cryptic.feature.Etherwarp;
import imicro.cryptic.terminal.Terminals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets modules swallow a server sound so they can play their own instead. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void cryptic$replaceEtherwarpSound(ClientboundSoundPacket packet, CallbackInfo info) {
        if (Etherwarp.replaceTeleportSound(packet)) info.cancel();
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
        }
    }

    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void cryptic$terminalClosed(ClientboundContainerClosePacket packet, CallbackInfo info) {
        Terminals.INSTANCE.closed();
    }
}
