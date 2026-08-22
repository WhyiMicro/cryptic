package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import imicro.cryptic.feature.BlockHittingVisuals;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the held sword aligned with the local player's third-person block pose. */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
    @Inject(
        method = "submitArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
            shift = At.Shift.BEFORE
        )
    )
    private void cryptic$poseLocalBlockingSword(
        ArmedEntityRenderState state,
        ItemStackRenderState itemState,
        ItemStack itemStack,
        HumanoidArm arm,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        int light,
        CallbackInfo ci
    ) {
        if (!(state instanceof AvatarRenderState avatarState)
            || !BlockHittingVisuals.isBlockingLocalPlayer(avatarState, arm)) {
            return;
        }

        // BringBlockingBack's third-person sword transform, mirrored for a
        // genuinely left-handed local player.
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F * side));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-40.0F * side));
        poseStack.mulPose(Axis.XP.rotationDegrees(51.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F * side));
        poseStack.mulPose(Axis.XP.rotationDegrees(197.2F));
        poseStack.translate(-0.22F * side, 0.13F, -0.22F);
    }
}
