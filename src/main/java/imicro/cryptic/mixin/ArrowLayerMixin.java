package imicro.cryptic.mixin;

import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops the arrows stuck in a player's body.
 *
 * Answered as a count rather than by cancelling the layer, because the count
 * is what the layer is asking for: zero arrows is a thing it already knows how
 * to draw, and every other mod wrapping the same layer still sees a sane
 * answer.
 */
@Mixin(ArrowLayer.class)
public abstract class ArrowLayerMixin {
    @Inject(method = "numStuck", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideStuckArrows(AvatarRenderState state, CallbackInfoReturnable<Integer> info) {
        if (RenderOptimizer.hidesPlayerArrows()) info.setReturnValue(0);
    }
}
