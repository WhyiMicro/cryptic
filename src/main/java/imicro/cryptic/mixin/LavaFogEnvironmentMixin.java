package imicro.cryptic.mixin;

import imicro.cryptic.feature.LavaToWater;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Takes the orange fog out of lava, for Lava to Water.
 *
 * A clear texture behind a solid orange fog is no clearer than before, so the
 * texture swap on its own does almost nothing while your head is under.
 */
@Mixin(LavaFogEnvironment.class)
public abstract class LavaFogEnvironmentMixin {
    @Unique
    private static final WaterFogEnvironment cryptic$WATER_FOG = new WaterFogEnvironment();

    @Inject(method = "setupFog", at = @At("HEAD"), cancellable = true)
    private void cryptic$clearLavaFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo info) {
        if (!LavaToWater.hidesFog()) return;
        fog.color.set(fog.color.x, fog.color.y, fog.color.z, 0.0F);
        fog.environmentalStart = renderDistance;
        fog.environmentalEnd = renderDistance;
        info.cancel();
    }

    @Inject(method = "getBaseColor", at = @At("HEAD"), cancellable = true)
    private void cryptic$waterFogColor(ClientLevel level, Camera camera, int renderDistance, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        if (!LavaToWater.isEnabled()) return;
        Integer tint = LavaToWater.fogTint();
        cir.setReturnValue(tint != null ? tint : cryptic$WATER_FOG.getBaseColor(level, camera, renderDistance, partialTicks));
    }
}
