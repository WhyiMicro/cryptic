package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import imicro.cryptic.duck.EntityRenderStateHolder;
import imicro.cryptic.feature.SlayerFeatures;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slayer Features' Fade others: the model drawn the way vanilla draws an invisible mob a spectator
 * can see, at the chosen opacity, and without its layers (armour, eyes, held items), which would
 * stay solid.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    /** The alpha the entity being submitted is faded to, or -1. Rendering is on one thread. */
    @Unique
    private int cryptic$fade = -1;

    @Inject(method = SUBMIT, at = @At("HEAD"))
    private void cryptic$readFade(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo info) {
        // An invisible mob stays invisible; fading it would show it.
        cryptic$fade = state.isInvisible ? -1 : SlayerFeatures.fadeAlpha(((EntityRenderStateHolder) state).cryptic$entity());
    }

    @ModifyVariable(method = SUBMIT, at = @At("STORE"), ordinal = 1)
    private boolean cryptic$translucent(boolean forceTransparent) {
        return forceTransparent || cryptic$fade >= 0;
    }

    @ModifyVariable(method = SUBMIT, at = @At("STORE"), ordinal = 2)
    private int cryptic$fadeColour(int tintedColor) {
        return cryptic$fade >= 0 ? (cryptic$fade << 24) | (tintedColor & 0xFFFFFF) : tintedColor;
    }

    @ModifyExpressionValue(
        method = SUBMIT,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z")
    )
    private boolean cryptic$skipFadedLayers(boolean render) {
        return render && cryptic$fade < 0;
    }
}
