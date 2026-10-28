package imicro.cryptic.mixin;

import imicro.cryptic.feature.SlayerFeatures;
import net.minecraft.client.renderer.entity.GuardianRenderer;
import net.minecraft.client.renderer.entity.state.GuardianRenderState;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops a beam Slayer Features draws itself: with no target, the renderer draws none. */
@Mixin(GuardianRenderer.class)
public abstract class GuardianRendererMixin {
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/monster/Guardian;Lnet/minecraft/client/renderer/entity/state/GuardianRenderState;F)V",
        at = @At("TAIL")
    )
    private void cryptic$hideBeam(Guardian entity, GuardianRenderState state, float partialTicks, CallbackInfo info) {
        if (SlayerFeatures.hidesBeam(entity)) state.attackTargetPosition = null;
    }
}
