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
 * Draws Cryptic's through-the-wall geometry after the translucent terrain.
 *
 * Everything submitted during a frame is sorted into phases, and custom
 * geometry that blends lands in the one drawn <em>before</em> translucent
 * blocks. That is why a line across a sheet of ice, or a countdown seen
 * through glass, came out washed to half strength: the box was drawn, and then
 * the ice was drawn over the top of it.
 *
 * The phase after the terrain is where the game puts its own block outline, so
 * moving ours into it is not a new idea, only the same one applied to the
 * geometry a mod submits. Only the "through walls" pipelines are moved — those
 * are the ones asking to be seen whatever is in front of them. Depth-tested
 * geometry keeps vanilla's ordering, where being behind something means being
 * hidden by it.
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

    /**
     * The same for world text, which carries no render type to recognise it
     * by — so the caller says so instead, for the one submit it is about to
     * make. Both happen on the render thread, one immediately after the other.
     */
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
