package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Render Optimizer take the flames off your own screen.
 *
 * Hiding it outright is one injection; fading it is a second, because the
 * overlay's alpha is not a value anywhere — it is written into each of the
 * quad's corners as they are built, so the fade has to be applied there.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void cryptic$hideFireOverlay(PoseStack poseStack, MultiBufferSource bufferSource, TextureAtlasSprite sprite, CallbackInfo info) {
        if (RenderOptimizer.hidesFireOverlay()) info.cancel();
    }

    @Redirect(
        method = "renderFire",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;")
    )
    private static VertexConsumer cryptic$fadeFireOverlay(VertexConsumer consumer, float red, float green, float blue, float alpha) {
        return consumer.setColor(red, green, blue, alpha * RenderOptimizer.fireOverlayAlpha());
    }
}
