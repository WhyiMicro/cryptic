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

    /**
     * Hypixel's per-tick ping, on the client thread, in order with the block packets around it: the
     * Water Board solver tags each change with the tick it came in. (ConnectionMixin counts the same
     * pings straight off the network for timers.)
     */
    @Inject(method = "handlePing", at = @At("HEAD"))
    private void cryptic$tickInOrder(net.minecraft.network.protocol.common.ClientboundPingPacket packet, CallbackInfo info) {
        if (packet.getId() == 0 || !net.minecraft.client.Minecraft.getInstance().isSameThread()) return;
        imicro.cryptic.puzzle.WaterDynamic.onPing();
    }
}
