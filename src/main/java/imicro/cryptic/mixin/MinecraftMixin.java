package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import imicro.cryptic.gui.ImGuiRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws ImGui onto the window after Minecraft has copied its off-screen render
 * target to the window, but before the window's buffers are swapped.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
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
