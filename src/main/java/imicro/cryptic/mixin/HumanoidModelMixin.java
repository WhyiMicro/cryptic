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

/**
 * Two things that are set once a model has finished being posed: the classic
 * blocking arm, and Custom Scale's resized head.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends HumanoidRenderState> {
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;
    @Shadow public ModelPart head;

    /**
     * Resizes the head against the body, which is the whole of the chibi look.
     *
     * The tail of the pose, because a model part's scale is reset with the rest
     * of its pose every frame — setting it anywhere earlier is setting it on
     * something that is about to be overwritten. Adapted from Athen (BSD
     * 3-Clause).
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

        // The head alone, and deliberately not the hat with it. The hat is the
        // skin's outer head layer, and vanilla has already copied the head's
        // pose onto it — scale included — by the time this runs, so setting it
        // again applies the factor twice and the outer layer ends up floating
        // a long way off the face. Athen scales only the head for this reason.
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
