package imicro.cryptic.mixin;

import imicro.cryptic.feature.ILoveGlass;
import imicro.cryptic.feature.NucleusQol;
import imicro.cryptic.feature.SpiritBear;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    /** The cracks on the block Mining's ping offset is timing, which its own box replaces. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideBreakAnimation(int id, BlockPos pos, int progress, org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        if (imicro.cryptic.feature.Mining.hidesBreakAnimation(pos)) info.cancel();
    }

    /** Render Optimizer's block break particles: the bits while mining, and the burst when it breaks. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideBreakBurst(BlockPos pos, BlockState state, org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        if (imicro.cryptic.feature.RenderOptimizer.hidesBreakParticles()) info.cancel();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideBreakingBits(BlockPos pos, net.minecraft.core.Direction direction, org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        if (imicro.cryptic.feature.RenderOptimizer.hidesBreakParticles()) info.cancel();
    }

    /**
     * A block the server just set, from both single-block and section updates. Lets I love glass
     * swap Storm's sinking pillars as each layer arrives. Always the client thread.
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
        // The replaced block is still in the world here (Spirit Bear tells a lantern lighting from
        // going out).
        BlockState old = ((ClientLevel) (Object) this).getBlockState(pos);
        SpiritBear.onBlockChanged(pos, old, state);
        // A Crystal Hollows treasure chest appearing, or going once looted.
        NucleusQol.onBlockChanged(pos, old, state);
        return ILoveGlass.substitute(pos, state);
    }
}
