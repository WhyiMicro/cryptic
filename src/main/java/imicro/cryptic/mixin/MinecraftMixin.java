package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import imicro.cryptic.feature.AutoClicker;
import imicro.cryptic.gui.ImGuiRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two things that belong to the frame rather than the tick: drawing ImGui, and
 * running the Auto Clicker's press and release.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /**
     * Runs the Auto Clicker before the frame, which is the only place it can
     * run: a press and its release are both inside a single tick at any rate
     * worth using, so a tick hook could not fit them both.
     */
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void cryptic$autoClick(boolean renderLevel, CallbackInfo ci) {
        AutoClicker.frame();
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V",
            shift = At.Shift.AFTER
        )
    )
    private void cryptic$renderImGui(boolean renderLevel, CallbackInfo ci) {
        ImGuiRuntime.INSTANCE.renderIfOpen();
    }
}
