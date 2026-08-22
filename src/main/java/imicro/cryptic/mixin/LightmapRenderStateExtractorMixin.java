package imicro.cryptic.mixin;

import imicro.cryptic.feature.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overrides only the extracted client lightmap; the user's gamma stays intact. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
    @Inject(method = "extract", at = @At("TAIL"))
    private void cryptic$applyFullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (!Fullbright.module.getEnabled()) {
            if (Fullbright.lightmapApplied) {
                // One last update hands the lightmap back to vanilla's values.
                Fullbright.lightmapApplied = false;
                state.needsUpdate = true;
            }
            return;
        }

        // Cryptic's lightmap is constant, so it only has to be uploaded again
        // when the module turns on or when vanilla already wants an update.
        // Forcing it every frame rebuilt and re-uploaded the texture for nothing.
        if (!Fullbright.lightmapApplied) {
            Fullbright.lightmapApplied = true;
            state.needsUpdate = true;
        }
        state.brightness = 1.0f;
        state.darknessEffectScale = 0.0f;
        state.bossOverlayWorldDarkening = 0.0f;
        state.nightVisionEffectIntensity = 1.0f;
        state.nightVisionColor = LightmapRenderStateExtractor.WHITE;
    }
}
