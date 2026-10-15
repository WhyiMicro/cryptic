package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import imicro.cryptic.feature.Toasts;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws Cryptic's notifications right after vanilla's toasts: above any open
 * screen, as vanilla's are, and through the same renderer.
 */
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
}
