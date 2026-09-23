package imicro.cryptic.mixin;

import imicro.cryptic.device.SimonSays;
import imicro.cryptic.feature.EtherwarpZeroPing;
import imicro.cryptic.feature.SmartTickTimer;
import imicro.cryptic.feature.TerminalTimes;
import imicro.cryptic.terminal.ServerTicks;
import imicro.cryptic.terminal.Terminals;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
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
        if (packet.getId() != 0) {
            ServerTicks.onServerTick();
            // Simon Says counts the quiet after the last lantern in server
            // ticks rather than client ones, because what it is waiting for is
            // the server finishing, not the client drawing.
            SimonSays.INSTANCE.onServerTick();
            // Every dungeon countdown runs on the server's clock too, and this
            // is the only place the client hears it advance.
            SmartTickTimer.INSTANCE.onServerTick();
            // The terminal solver's lag protection counts the same ticks, and
            // so does the terminal clock when it is told not to use real time.
            Terminals.INSTANCE.onServerTick();
            TerminalTimes.INSTANCE.onServerTick();
        }
    }

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
