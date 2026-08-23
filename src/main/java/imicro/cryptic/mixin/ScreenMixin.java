package imicro.cryptic.mixin;

import imicro.cryptic.feature.TerminalSolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the terminal solver replace a screen outright.
 *
 * The whole screen has to go, not just its contents: a chest draws its own
 * texture as the background, and that texture is a picture of an inventory, so
 * cancelling only the slots would leave a row of fake item slots behind the
 * terminal. The solver paints the dimming back itself.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void cryptic$drawTerminal(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
        if (TerminalSolver.renderCustomGui((Screen) (Object) this, graphics, mouseX, mouseY)) {
            info.cancel();
        }
    }
}
