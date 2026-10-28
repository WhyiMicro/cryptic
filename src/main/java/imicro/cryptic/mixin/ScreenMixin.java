package imicro.cryptic.mixin;

import imicro.cryptic.feature.CroesusHelper;
import imicro.cryptic.feature.ExperimentSolver;
import imicro.cryptic.feature.SlotBinds;
import imicro.cryptic.feature.SpiritLeapOverlay;
import imicro.cryptic.feature.TerminalSolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the terminal solver replace a screen outright. The chest's background texture is a picture
 * of an inventory, so cancelling only the slots would leave fake slots behind; the solver paints
 * the dimming itself.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void cryptic$drawTerminal(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
        // The Spirit Leap menu, replaced the same way.
        if (SpiritLeapOverlay.render((Screen) (Object) this, graphics, mouseX, mouseY)) {
            info.cancel();
            return;
        }
        if (TerminalSolver.renderCustomGui((Screen) (Object) this, graphics, mouseX, mouseY)) {
            info.cancel();
            return;
        }
        if (imicro.cryptic.feature.PartyFinder.render((Screen) (Object) this, graphics, mouseX, mouseY)) {
            info.cancel();
            return;
        }
        if (imicro.cryptic.feature.PartyFinderPages.render((Screen) (Object) this, graphics, mouseX, mouseY)) {
            info.cancel();
        }
    }

    /**
     * The experiment solver paints over the chest rather than replacing it, since the board is
     * what's being memorised. Drawn before the tooltips, so a tooltip stays on top of these.
     */
    @Inject(
        method = "extractRenderStateWithTooltipAndSubtitles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;extractDeferredElements(IIF)V")
    )
    private void cryptic$drawExperiment(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
        ExperimentSolver.render((Screen) (Object) this, graphics);
        SlotBinds.render((Screen) (Object) this, graphics, mouseX, mouseY);
        CroesusHelper.renderOverlay((Screen) (Object) this, graphics, mouseX, mouseY);
        imicro.cryptic.feature.Bazaar.renderOverlay((Screen) (Object) this, graphics);
        imicro.cryptic.feature.BazaarPresets.render((Screen) (Object) this, graphics, mouseX, mouseY);
        imicro.cryptic.feature.Farming.renderList((Screen) (Object) this, graphics, mouseX, mouseY);
        imicro.cryptic.feature.Farming.renderMousemat((Screen) (Object) this, graphics, mouseX, mouseY);
    }
}
