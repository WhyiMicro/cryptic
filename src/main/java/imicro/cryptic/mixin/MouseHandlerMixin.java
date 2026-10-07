package imicro.cryptic.mixin;

import imicro.cryptic.feature.BossWaypoints;
import imicro.cryptic.feature.DungeonWaypoints;
import imicro.cryptic.feature.ScrollableTooltips;
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
        // Shift and the wheel while placing waypoints picks what to place,
        // and must not also change the held item.
        if (DungeonWaypoints.onScroll(yoffset) || BossWaypoints.onScroll(yoffset)) {
            ci.cancel();
            return;
        }
        if (Zoom.onScroll(yoffset)) {
            ci.cancel();
            return;
        }
        // An item's tooltip takes the wheel before the screen under it does,
        // because with the cursor on an item that is what the turn meant.
        if (ScrollableTooltips.onScroll(yoffset)) ci.cancel();
    }

    /**
     * The button a notification took, until it comes back up.
     *
     * A click is a press and a release, and taking only the press would leave
     * the release to the screen underneath - which, for a chest with an item on
     * the cursor, is the instruction to put that item down wherever the cursor
     * happens to be. So whichever button dismissed a notification is kept from
     * the game on its way up as well.
     */
    @Unique
    private int cryptic$swallowedButton = -1;

    /** A click on one of Cryptic's notifications takes it away, and goes no further. */
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickNotification(long handle, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (handle != Minecraft.getInstance().getWindow().handle()) return;

        if (action != 1) {
            if (button.button() == cryptic$swallowedButton) {
                cryptic$swallowedButton = -1;
                ci.cancel();
            }
            return;
        }

        if (button.button() == 0 && Toasts.onMousePressed()) {
            cryptic$swallowedButton = 0;
            ci.cancel();
        }
    }
}
