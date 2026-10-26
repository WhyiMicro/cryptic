package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import imicro.cryptic.render.CrypticRenderLayers;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Cryptic's through-walls geometry after translucent terrain. Blended custom geometry is
 * normally drawn before translucent blocks, so ice or glass in front washed it out. Only the
 * through-walls pipelines move; depth-tested geometry keeps vanilla's order.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {
    @Inject(method = "submitCustomGeometry", at = @At("HEAD"), cancellable = true)
    private void cryptic$geometryOverTerrain(
        PoseStack poseStack,
        RenderType renderType,
        SubmitNodeCollector.CustomGeometryRenderer renderer,
        CallbackInfo info
    ) {
        if (!CrypticRenderLayers.drawsOverTerrain(renderType)) return;

        SubmitNodeCollection self = (SubmitNodeCollection) (Object) this;
        self.afterTerrain.submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), renderType, renderer));
        info.cancel();
    }

    /** The same for world text, which has no render type, so the caller flags it around the one submit. */
    @Inject(method = "submitText", at = @At("HEAD"), cancellable = true)
    private void cryptic$textOverTerrain(
        PoseStack poseStack,
        float x,
        float y,
        FormattedCharSequence text,
        boolean dropShadow,
        Font.DisplayMode displayMode,
        int light,
        int color,
        int backgroundColor,
        int outlineColor,
        CallbackInfo info
    ) {
        if (!CrypticRenderLayers.textDrawsOverTerrain()) return;

        SubmitNodeCollection self = (SubmitNodeCollection) (Object) this;
        self.afterTerrain.submit(new TextFeatureRenderer.Submit(
            new Matrix4f(poseStack.last().pose()),
            x,
            y,
            text,
            dropShadow,
            displayMode,
            light,
            color,
            backgroundColor,
            outlineColor
        ));
        info.cancel();
    }
}
