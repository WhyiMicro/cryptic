package imicro.cryptic.mixin;

import imicro.cryptic.device.SimonSays;
import imicro.cryptic.feature.BloodCamp;
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
 * Counts Hypixel's per-tick ping the instant it comes off the wire.
 *
 * Every countdown in a dungeon runs on the server's clock, and this packet is
 * the only place the client hears that clock advance. Where it is counted turns
 * out to matter as much as that it is counted: this used to be read at the end
 * of the handler, which the game runs on the client thread one hop later, so a
 * tick landed whenever the client next got round to it. At a low frame rate
 * several arrive in one frame and the timers drift away from the fight they are
 * timing — a second out by the end of a phase.
 *
 * Read here, off the network thread, a tick is counted when it happens. Odin
 * (BSD 3-Clause) counts it in the same place for the same reason. Everything
 * called from here only adds one to a counter.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void cryptic$countServerTick(io.netty.channel.ChannelHandlerContext context, Packet<?> packet, CallbackInfo info) {
        // Id zero is the keep-alive the client answers; the rest are the
        // server's own heartbeat, one per tick.
        if (!(packet instanceof ClientboundPingPacket ping) || ping.getId() == 0) return;

        ServerTicks.onServerTick();
        SimonSays.INSTANCE.onServerTick();
        SmartTickTimer.INSTANCE.onServerTick();
        InvincibilityTimer.onServerTick();
        BloodCamp.onServerTick();
        PuzzleSolver.onServerTick();
        Terminals.INSTANCE.onServerTick();
        TerminalTimes.INSTANCE.onServerTick();
        TerracottaTimer.onServerTick();
        ServerStats.onServerTick();
    }
}
