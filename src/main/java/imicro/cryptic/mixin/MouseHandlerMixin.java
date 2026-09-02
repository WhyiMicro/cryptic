package imicro.cryptic.mixin;

import imicro.cryptic.feature.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the wheel to the Zoom module while it is zoomed.
 *
 * Taken at the head of the callback rather than further in, because everything
 * further in is Minecraft deciding what the turn meant — and the point is that
 * while zoomed it did not mean "change the held item".
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cryptic$zoomScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (Zoom.onScroll(yoffset)) ci.cancel();
    }
}
