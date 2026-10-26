package imicro.cryptic.mixin;

import imicro.cryptic.device.SimonSays;
import imicro.cryptic.feature.BloodCamp;
import imicro.cryptic.feature.F7Qol;
import imicro.cryptic.feature.InvincibilityTimer;
import imicro.cryptic.feature.PuzzleSolver;
import imicro.cryptic.feature.SmartTickTimer;
import imicro.cryptic.feature.TerminalTimes;
import imicro.cryptic.feature.TerracottaTimer;
import imicro.cryptic.skyblock.ServerStats;
import imicro.cryptic.terminal.ServerTicks;
import imicro.cryptic.terminal.Terminals;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts Hypixel's per-tick ping straight off the network thread, as Odin does. Counted later on
 * the client thread, ticks bunched up at low frame rates and timers drifted a second per phase.
 * Only increments a counter.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void cryptic$countServerTick(io.netty.channel.ChannelHandlerContext context, Packet<?> packet, CallbackInfo info) {
        // Id zero is the keep-alive; the rest are the server's per-tick heartbeat.
        if (!(packet instanceof ClientboundPingPacket ping) || ping.getId() == 0) return;

        ServerTicks.onServerTick();
        SimonSays.INSTANCE.onServerTick();
        SmartTickTimer.INSTANCE.onServerTick();
        imicro.cryptic.feature.F3Features.onServerTick();
        InvincibilityTimer.onServerTick();
        BloodCamp.onServerTick();
        PuzzleSolver.onServerTick();
        Terminals.INSTANCE.onServerTick();
        TerminalTimes.INSTANCE.onServerTick();
        TerracottaTimer.onServerTick();
        F7Qol.onServerTick();
        imicro.cryptic.feature.WitherDragons.onServerTick();
        ServerStats.onServerTick();
    }
}
