package imicro.cryptic.mixin;

import imicro.cryptic.feature.LeapPreview;
import imicro.cryptic.gui.menu.MenuScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives Cryptic's menu its own blur strength, without touching the Menu Background Blur option, and
 * renders Spirit Leap's teammate previews once your own view is done.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow @Final private com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget;
    @Shadow @Final private com.mojang.blaze3d.resource.CrossFrameResourcePool resourcePool;

    /** Item Outline, while the depth buffer holds only the hand. */
    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void cryptic$outlineHeldItem(net.minecraft.client.renderer.state.level.CameraRenderState cameraState, float deltaPartialTick,
                                         org.joml.Matrix4fc modelViewMatrix, CallbackInfo info) {
        imicro.cryptic.feature.ItemOutline.afterHand(this.mainRenderTarget, this.resourcePool, cameraState.hudFov);
    }

    @Inject(method = "extractOptions", at = @At("TAIL"))
    private void cryptic$menuBlur(CallbackInfo info) {
        int radius = MenuScreen.blurRadius();
        if (radius > 0) this.gameRenderState.optionsRenderState.menuBackgroundBlurriness = radius;
    }

    /**
     * After the world, its outlines and post effects, before the GUI: the previews' images are
     * drawn by the GUI pass, so they have to exist by then, and rendering them any earlier would
     * leave their entity outlines in your own frame.
     */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void cryptic$leapPreviews(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo info) {
        LeapPreview.renderFeeds(deltaTracker);
    }
}
