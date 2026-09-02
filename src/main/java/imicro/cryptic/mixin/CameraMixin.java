package imicro.cryptic.mixin;

import imicro.cryptic.feature.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Narrows the field of view for the Zoom module.
 *
 * The two angles are worked out separately and used for different things:
 * {@code calculateFov} is the world, and {@code calculateHudFov} is the pass
 * that draws the held item over it. Zooming only the first is what leaves the
 * hand its usual size, so the hand is a separate hook rather than a scale
 * applied afterwards.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void cryptic$zoomView(float partialTicks, CallbackInfoReturnable<Float> cir) {
        float zoom = Zoom.factor();
        if (zoom > 1.0F) cir.setReturnValue(cir.getReturnValueF() / zoom);
    }

    @Inject(method = "calculateHudFov", at = @At("RETURN"), cancellable = true)
    private void cryptic$zoomHand(float partialTicks, CallbackInfoReturnable<Float> cir) {
        float zoom = Zoom.handFactor();
        if (zoom > 1.0F) cir.setReturnValue(cir.getReturnValueF() / zoom);
    }
}
