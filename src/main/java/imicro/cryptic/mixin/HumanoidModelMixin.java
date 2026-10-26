package imicro.cryptic.mixin;

import imicro.cryptic.feature.BlockHittingVisuals;
import imicro.cryptic.feature.CustomScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Set after the model is posed: the classic blocking arm, and Custom Scale's head. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends HumanoidRenderState> {
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;
    @Shadow public ModelPart head;

    /**
     * Resizes the head for the chibi look, at the end of posing since part scales reset every
     * frame. Adapted from Athen.
     */
    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
        at = @At("TAIL")
    )
    private void cryptic$scaleHead(T state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatarState)) return;

        Float scale = CustomScale.headScale();
        if (scale == null) return;
        if (!CustomScale.appliesTo(CustomScale.entityOf(avatarState))) return;

        // The head alone: vanilla has already copied the head's scale onto the hat layer, so
        // scaling the hat too doubles it.
        head.xScale = scale;
        head.yScale = scale;
        head.zScale = scale;
    }

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
        at = @At("TAIL")
    )
    private void cryptic$poseLocalBlockingArm(T state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatarState)
            || !BlockHittingVisuals.isBlockingLocalPlayer(avatarState)) {
            return;
        }

        HumanoidArm mainArm = Minecraft.getInstance().player.getMainArm();
        ModelPart blockingArm = mainArm == HumanoidArm.RIGHT ? rightArm : leftArm;
        blockingArm.xRot = -0.94F;
    }
}
