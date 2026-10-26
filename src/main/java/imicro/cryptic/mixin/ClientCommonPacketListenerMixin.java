package imicro.cryptic.mixin;

import imicro.cryptic.feature.EtherwarpZeroPing;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
    /**
     * Every packet on its way out, where a teleport can be seen before the server answers. Runs on
     * whichever thread is sending, so features check for the client thread themselves.
     */
    @Inject(method = "send", at = @At("HEAD"))
    private void cryptic$watchOutgoingPacket(Packet<?> packet, CallbackInfo info) {
        EtherwarpZeroPing.onPacketSent(packet);
        imicro.cryptic.debug.GuiLog.onPacketSent(packet);
    }
}
