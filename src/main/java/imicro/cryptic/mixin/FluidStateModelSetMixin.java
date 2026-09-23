package imicro.cryptic.mixin;

import imicro.cryptic.feature.LavaToWater;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands back water's model when the game asks for lava's, for Lava to Water. */
@Mixin(FluidStateModelSet.class)
public abstract class FluidStateModelSetMixin {
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void cryptic$lavaLooksLikeWater(FluidState state, CallbackInfoReturnable<FluidModel> cir) {
        FluidModel replacement = LavaToWater.replacement((FluidStateModelSet) (Object) this, state);
        if (replacement != null) cir.setReturnValue(replacement);
    }
}
