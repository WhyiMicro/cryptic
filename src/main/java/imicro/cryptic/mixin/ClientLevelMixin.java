package imicro.cryptic.mixin;

import imicro.cryptic.feature.ILoveGlass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    /**
     * A block the server has just set, as the world takes it.
     *
     * Both single-block and whole-section updates end up here, which is what
     * lets I love glass swap a wall as it arrives rather than on its next scan:
     * Storm's pillars are resent a layer at a time as they sink, and a scan
     * that comes round a few ticks later leaves each new layer standing as
     * diorite until it does. Always the client thread, since both packets are
     * handed over before they touch the level.
     */
    @ModifyArg(
        method = "setServerVerifiedBlockState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"
        ),
        index = 1
    )
    private BlockState cryptic$glaze(BlockPos pos, BlockState state, int flags, int limit) {
        return ILoveGlass.substitute(pos, state);
    }
}
