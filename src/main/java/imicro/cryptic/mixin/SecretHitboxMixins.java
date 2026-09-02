package imicro.cryptic.mixin;

import imicro.cryptic.feature.Secrets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Grows the hitboxes of the blocks dungeon secrets hide behind.
 *
 * All four report a shape far smaller than the block they occupy, which is what
 * makes clicking one at a run unreliable. A full block is not always the right
 * answer though: a button keeps its depth so it cannot be clicked through the
 * wall it is stuck to, and a skull only grows if it is one of the two Hypixel
 * actually hides secrets in. Only the shape this client tests against changes;
 * the server decides whether the interaction lands.
 */
public final class SecretHitboxMixins {
    private SecretHitboxMixins() {
    }

    @Mixin(LeverBlock.class)
    public abstract static class Lever {
        @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
        private void cryptic$growLever(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> info) {
            if (Secrets.growsLever(pos)) info.setReturnValue(Shapes.block());
        }
    }

    @Mixin(ButtonBlock.class)
    public abstract static class Button {
        @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
        private void cryptic$growButton(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> info) {
            if (Secrets.growsButton()) info.setReturnValue(Secrets.buttonShape(state));
        }
    }

    @Mixin(SkullBlock.class)
    public abstract static class Skull {
        @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
        private void cryptic$growSkull(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> info) {
            if (Secrets.growsSkull(pos)) info.setReturnValue(Shapes.block());
        }
    }

    @Mixin(MushroomBlock.class)
    public abstract static class Mushroom {
        @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
        private void cryptic$growMushroom(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> info) {
            if (Secrets.growsMushroom()) info.setReturnValue(Shapes.block());
        }
    }
}
