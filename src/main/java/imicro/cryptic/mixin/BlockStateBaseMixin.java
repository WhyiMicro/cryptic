package imicro.cryptic.mixin;

import imicro.cryptic.feature.BreakerHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Breaker Helper's Instant mine: the Dungeon Breaker mines at an enormous speed, so nearly every
 * block breaks in one hit through the game's instant-break path.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void cryptic$breakerInstant(Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> info) {
        Float progress = BreakerHelper.breakerProgress(player, level, pos, (BlockState) (Object) this);
        if (progress != null) info.setReturnValue(progress);
    }
}
