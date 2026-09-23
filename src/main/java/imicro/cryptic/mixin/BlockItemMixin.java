package imicro.cryptic.mixin;

import imicro.cryptic.feature.NoItemPlace;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swallows the placement of an item that was meant to be used, for No Item
 * Place.
 *
 * Both halves are needed: one stops the block appearing, and the other stops
 * the sound and the swing that would announce a placement that did not happen.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void cryptic$swallowPlacement(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (NoItemPlace.blocks(context)) cir.setReturnValue(true);
    }

    @Inject(
        method = "place",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"),
        cancellable = true
    )
    private void cryptic$swallowPlacementSound(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (NoItemPlace.blocks(context)) cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
