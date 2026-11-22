package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer's fog and Crimson Isle colour. Open air only: water, lava and blindness use other environments. */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogEnvironmentMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void cryptic$adjustFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo info) {
        RenderOptimizer.adjustFog(fog);
    }

    /** The boss bar's fog also pulls in the sky and clouds, which the tail can't tell from their own. */
    @ModifyExpressionValue(
        method = "setupFog",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z")
    )
    private boolean cryptic$noBossFog(boolean original) {
        return original && !RenderOptimizer.removesFog();
    }
}
