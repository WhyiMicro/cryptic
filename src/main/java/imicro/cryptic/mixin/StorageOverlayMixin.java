package imicro.cryptic.mixin;

import imicro.cryptic.feature.StorageOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Storage Overlay draws in place of a storage menu's chest texture. After the screen's own
 * background (the blurred, dimmed world), so only the chest is replaced.
 */
@Mixin(ContainerScreen.class)
public abstract class StorageOverlayMixin {
    @Inject(
        method = "extractBackground",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void cryptic$drawStorage(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo info) {
        ContainerScreen screen = (ContainerScreen) (Object) this;
        if (!StorageOverlay.INSTANCE.active(screen)) return;
        StorageOverlay.renderBackground(screen, graphics, mouseX, mouseY);
        info.cancel();
    }
}
