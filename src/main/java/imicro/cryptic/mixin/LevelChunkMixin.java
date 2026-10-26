package imicro.cryptic.mixin;

import imicro.cryptic.device.SharpShooter;
import imicro.cryptic.device.SimonSays;
import imicro.cryptic.feature.TerracottaTimer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports each block change with the state it replaces, for Simon Says (a lantern going out is a
 * step; lighting up can be a redraw) and the Sharp Shooter mark. The same hook Odin uses.
 *
 * Runs for every block change in the world, so receivers bail out first.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void cryptic$noteBlockChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> info) {
        // A terracotta going down leaves a flower pot; no lookup needed.
        if (TerracottaTimer.isWatching() && state.getBlock() instanceof FlowerPotBlock) {
            TerracottaTimer.onPotPlaced(pos);
        }

        // Checked before the old state lookup, the only real cost here.
        boolean simon = SimonSays.INSTANCE.isWatching();
        boolean sharp = SharpShooter.INSTANCE.isWatching();
        if (!simon && !sharp) return;

        BlockState old = getBlockState(pos);
        if (old == state) return;
        if (simon) SimonSays.INSTANCE.onBlockChanged(pos, old, state);
        if (sharp) SharpShooter.INSTANCE.onBlockChanged(pos, old, state);
    }
}
