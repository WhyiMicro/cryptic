package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import imicro.cryptic.feature.CustomNametags;
import imicro.cryptic.feature.CustomScale;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows your own name tag and resizes the model. Forcing vanilla's own tag means Hypixel's rank,
 * colours and level come along. Adapted from NoammAddons.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    /** Capes from the cape mods, and your own. */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void cryptic$cape(Avatar entity, net.minecraft.client.renderer.entity.state.AvatarRenderState state, float partialTicks, CallbackInfo info) {
        state.skin = imicro.cryptic.feature.Capes.skinFor(entity, state.skin);
        imicro.cryptic.feature.HideArmor.apply(entity, state);
    }

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("HEAD"), cancellable = true)
    private void cryptic$showOwnNameTag(Avatar entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (CustomNametags.showsOwnNameTag(entity)) cir.setReturnValue(true);
    }

    /**
     * Multiplies Custom Scale's factor onto vanilla's, inside vanilla's transform so the model
     * stays on its feet. The same point Athen uses.
     */
    @Inject(
        method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V")
    )
    private void cryptic$scalePlayer(AvatarRenderState state, PoseStack poseStack, CallbackInfo info) {
        if (!CustomScale.scalesModel()) return;
        if (!CustomScale.appliesTo(CustomScale.entityOf(state))) return;

        float scale = CustomScale.modelScale();
        poseStack.scale(scale, scale, scale);
    }
}
