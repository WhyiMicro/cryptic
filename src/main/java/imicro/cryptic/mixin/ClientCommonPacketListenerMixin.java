package imicro.cryptic.mixin;

import imicro.cryptic.feature.EtherwarpZeroPing;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client half of the connection, for the packets worth watching here. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
    /**
     * Every packet on its way out, which is where a teleport can be seen
     * before the server has answered it.
     *
     * The head rather than the tail: the click matters, not whether it reached
     * the socket, and this runs on whichever thread asked to send — the reason
     * the feature checks for the client thread itself.
     */
    @Inject(method = "send", at = @At("HEAD"))
    private void cryptic$watchOutgoingPacket(Packet<?> packet, CallbackInfo info) {
        EtherwarpZeroPing.onPacketSent(packet);
    }
}
