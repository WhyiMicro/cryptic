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
 * Lets the player see their own name tag, and resizes the model.
 *
 * Adapted from NoammAddons (CC0). The game deliberately never names the player
 * to themselves, and forcing that decision here rather than writing a tag by
 * hand is what makes it the real one: what gets drawn is the display name the
 * server dressed the player in, so Hypixel's rank, colours and SkyBlock level
 * all come along without Cryptic having to reassemble any of it.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("HEAD"), cancellable = true)
    private void cryptic$showOwnNameTag(Avatar entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (CustomNametags.showsOwnNameTag(entity)) cir.setReturnValue(true);
    }

    /**
     * Multiplies Custom Scale's own factor onto the one vanilla just applied.
     *
     * On the call rather than the method so it lands inside vanilla's own
     * transform, where the model is already standing on its feet — scaling
     * around any other origin sinks the player into the floor or lifts them off
     * it. The same point Athen (BSD 3-Clause) uses.
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
