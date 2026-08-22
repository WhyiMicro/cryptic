package imicro.cryptic.mixin;

import imicro.cryptic.dungeon.map.DungeonMapReader;
import imicro.cryptic.feature.Etherwarp;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
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
}
