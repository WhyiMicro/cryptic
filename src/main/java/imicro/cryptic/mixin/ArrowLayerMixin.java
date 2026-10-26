package imicro.cryptic.mixin;

import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops the arrows stuck in a player's body by answering zero, so other mods wrapping the layer
 * still see a sane count.
 */
@Mixin(ArrowLayer.class)
public abstract class ArrowLayerMixin {
    @Inject(method = "numStuck", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideStuckArrows(AvatarRenderState state, CallbackInfoReturnable<Integer> info) {
        if (RenderOptimizer.hidesPlayerArrows()) info.setReturnValue(0);
    }
}
