package imicro.cryptic.mixin;

import imicro.cryptic.feature.WitherCloakEffect;
import net.minecraft.client.renderer.entity.layers.CreeperPowerLayer;
import net.minecraft.client.renderer.entity.state.CreeperRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hides the vanilla charged-creeper layer while Cryptic renders Creeper Veil. */
@Mixin(CreeperPowerLayer.class)
public abstract class CreeperPowerLayerMixin {
    @Inject(
        method = "isPowered(Lnet/minecraft/client/renderer/entity/state/CreeperRenderState;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cryptic$hideWitherCloakPowerLayer(
        CreeperRenderState state,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (WitherCloakEffect.shouldHideVanillaPower(state)) cir.setReturnValue(false);
    }
}
