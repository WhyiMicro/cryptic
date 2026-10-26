package imicro.cryptic.mixin;

import imicro.cryptic.feature.BossWaypoints;
import imicro.cryptic.feature.DungeonWaypoints;
import imicro.cryptic.feature.KeybindManager;
import imicro.cryptic.feature.TooltipFeatures;
import imicro.cryptic.feature.Toasts;
import imicro.cryptic.feature.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the wheel to the Zoom module while zoomed, before Minecraft treats it as a hotbar change. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /** Freelook takes the mouse's turn for the camera, so the player keeps facing the same way. */
    @com.llamalad7.mixinextras.injector.WrapWithCondition(
        method = "turnPlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V")
    )
    private boolean cryptic$freelook(net.minecraft.client.player.LocalPlayer player, double dx, double dy) {
        return !imicro.cryptic.feature.Freecam.turn(dx, dy) && !imicro.cryptic.feature.Freelook.turn(dx, dy);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cryptic$zoomScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        // Shift and the wheel picks what to place in the waypoint editors.
        if (DungeonWaypoints.onScroll(yoffset) || BossWaypoints.onScroll(yoffset)) {
            ci.cancel();
            return;
        }
        if (Zoom.onScroll(yoffset) || imicro.cryptic.feature.Freecam.onScroll(yoffset)) {
            ci.cancel();
            return;
        }
        // A tooltip takes the wheel before the screen under it.
        if (TooltipFeatures.onScroll(yoffset)) ci.cancel();
    }

    /**
     * The button a notification took, until it's released, so the release doesn't drop a held item
     * into the screen underneath.
     */
    @Unique
    private int cryptic$swallowedButton = -1;

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickNotification(long handle, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (handle != Minecraft.getInstance().getWindow().handle()) return;

        if (action != 1) {
            KeybindManager.onMouse(button.button(), false);
            if (button.button() == cryptic$swallowedButton) {
                cryptic$swallowedButton = -1;
                // The release decides: a click, or the end of a drag that may throw it away.
                if (button.button() == 0) Toasts.onMouseReleased();
                ci.cancel();
            }
            return;
        }

        if (button.button() == 0 && Toasts.onMousePressed()) {
            cryptic$swallowedButton = 0;
            ci.cancel();
            return;
        }
        KeybindManager.onMouse(button.button(), true);
    }
}
