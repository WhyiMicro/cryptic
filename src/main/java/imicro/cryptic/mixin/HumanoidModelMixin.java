package imicro.cryptic.mixin;

import imicro.cryptic.feature.BlockHittingVisuals;
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

/** Applies the classic blocking arm pose to Cryptic's local player model. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends HumanoidRenderState> {
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;

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
