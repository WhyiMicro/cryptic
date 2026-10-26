package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import imicro.cryptic.feature.DailyRewards;
import imicro.cryptic.feature.Toasts;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws Cryptic's notifications after vanilla's toasts: above any screen, through the same renderer. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @WrapOperation(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"
        )
    )
    private void cryptic$drawNotifications(ToastManager manager, GuiGraphicsExtractor graphics, Operation<Void> original) {
        original.call(manager, graphics);
        Toasts.extract(graphics);
    }

    /**
     * Swaps in a screen Cryptic draws over instead (Skyblocker's Party Finder for the plain chest
     * Cryptic's Party Finder takes over, or a holding screen between Hypixel's menus while a Party
     * Finder page sets a value), and notes the screen for the GUI log.
     */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen cryptic$replaceScreen(Screen screen) {
        Screen replaced = imicro.cryptic.feature.PartyFinderPages.holdInstead(imicro.cryptic.feature.PartyFinder.replaceScreen(screen));
        imicro.cryptic.debug.GuiLog.onScreen(replaced);
        return replaced;
    }

    /** Keeps Hypixel's reward book from covering the daily reward picker. */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideRewardBook(Screen screen, CallbackInfo info) {
        if (DailyRewards.blocksScreen(screen)) info.cancel();
    }
}
