package imicro.cryptic.mixin;

import imicro.cryptic.terminal.ServerTicks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts Hypixel's per-tick ping, which is the only signal the client gets for
 * how fast the server is actually running.
 *
 * The tail rather than the head, because the head runs once on the network
 * thread before the packet is handed to the client thread, and counting there
 * would count every tick twice.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
    @Inject(method = "handlePing", at = @At("TAIL"))
    private void cryptic$countServerTick(ClientboundPingPacket packet, CallbackInfo info) {
        if (packet.getId() != 0) ServerTicks.onServerTick();
    }
}
