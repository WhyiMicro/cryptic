package imicro.cryptic.mixin;

import imicro.cryptic.feature.Mining;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives glass panes (gemstone panes) a full block hitbox on mining islands. Only the outline and
 * aiming shape: collision is left alone. Client-side only; the server still decides.
 */
@Mixin(CrossCollisionBlock.class)
public abstract class GlassPaneHitboxMixin {
    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void cryptic$growPane(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> info) {
        if (Mining.growsPane(state)) info.setReturnValue(Shapes.block());
    }
}
