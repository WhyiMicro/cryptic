package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import imicro.cryptic.feature.CustomNametags;
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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Cryptic's through-walls geometry after translucent terrain. Blended custom geometry is
 * normally drawn before translucent blocks, so ice or glass in front washed it out. Besides the
 * through-walls pipelines, only what asks to with {@code CrypticRenderLayers.overTerrain} moves.
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

    /**
     * Custom Nametags' background, set on the submit itself: Essential reads it from there to pad
     * the tag's sides, so changing it later at layout left those strips at vanilla's opacity.
     */
    @ModifyArg(
        method = "submitNameTag",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/NameTagFeatureRenderer$Submit;<init>(Lorg/joml/Matrix4fc;FFLnet/minecraft/network/chat/Component;IIILnet/minecraft/client/gui/Font$DisplayMode;)V"),
        index = 6
    )
    private int cryptic$nameTagBackground(int backgroundColor) {
        return CustomNametags.backgroundColor(backgroundColor);
    }
}
