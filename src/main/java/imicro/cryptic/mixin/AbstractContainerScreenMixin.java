package imicro.cryptic.mixin;

import imicro.cryptic.feature.TerminalSolver;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sends a terminal's input to the solver instead of to the chest.
 *
 * {@link ScreenMixin} is what puts the solver on screen in the chest's place;
 * once nothing of the chest is drawn its clicks have to go too, or a click
 * aimed at the solver would land on whatever slot happened to be behind it.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickTerminal(MouseButtonEvent click, boolean doubleClick, CallbackInfoReturnable<Boolean> info) {
        if (TerminalSolver.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.x(), click.y(), click.button())) {
            info.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cryptic$keyTerminal(KeyEvent event, CallbackInfoReturnable<Boolean> info) {
        if (TerminalSolver.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event)) {
            info.setReturnValue(true);
        }
    }
}
