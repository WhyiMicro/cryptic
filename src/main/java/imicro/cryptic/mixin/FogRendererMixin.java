package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Render Optimizer's edge fade, which vanilla sets after the fog environment. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @ModifyReturnValue(method = "setupFog", at = @At("RETURN"))
    private FogData cryptic$dropEdgeFade(FogData fog) {
        if (RenderOptimizer.removesEdgeFade()) {
            fog.renderDistanceStart = Float.MAX_VALUE;
            fog.renderDistanceEnd = Float.MAX_VALUE;
        }
        return fog;
    }
}
