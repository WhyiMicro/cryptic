package imicro.cryptic.mixin;

import imicro.cryptic.device.SharpShooter;
import imicro.cryptic.device.SimonSays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports a block changing, with the state it is replacing.
 *
 * The Simon Says device shows its sequence by turning sea lanterns off one at a
 * time, and which state a block is leaving is the whole signal — a lantern
 * lighting up can be the display being redrawn, but one going out is a step of
 * the sequence. Nothing else the client is told carries that, so it is read
 * here, on the way in, before the chunk has forgotten what was there — which is
 * also what keeps the Sharp Shooter mark on the block that is lit rather than a
 * tick behind it. The same hook Odin (BSD 3-Clause) uses for it.
 *
 * This runs for every block change in the world, so the receiver's first act is
 * to decide it has nothing to do.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void cryptic$noteBlockChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> info) {
        // Asked before the old state is looked up, because that lookup is the
        // only real cost here and almost every block change in the world is
        // one nothing is listening for.
        boolean simon = SimonSays.INSTANCE.isWatching();
        boolean sharp = SharpShooter.INSTANCE.isWatching();
        if (!simon && !sharp) return;

        BlockState old = getBlockState(pos);
        if (old == state) return;
        if (simon) SimonSays.INSTANCE.onBlockChanged(pos, old, state);
        if (sharp) SharpShooter.INSTANCE.onBlockChanged(pos, old, state);
    }
}
