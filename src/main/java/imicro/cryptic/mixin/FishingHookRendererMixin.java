package imicro.cryptic.mixin;

import imicro.cryptic.feature.Animations;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In first person the line starts at the rod's tip as drawn, not vanilla's fixed screen point. */
@Mixin(FishingHookRenderer.class)
public abstract class FishingHookRendererMixin {
    @Inject(method = "getPlayerHandPos", at = @At("HEAD"), cancellable = true)
    private void cryptic$lineFromRodTip(Player owner, float swing, float partialTicks, CallbackInfoReturnable<Vec3> cir) {
        Vec3 tip = Animations.rodTipInWorld(owner);
        if (tip != null) cir.setReturnValue(tip);
    }
}
