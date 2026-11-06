package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import imicro.cryptic.feature.CustomScale;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Custom Scale's chibi head for helmets: the helmet is drawn scaled about the head's pivot, rather
 * than through the armour model's head part, so resource-pack armour models drawn by other mods
 * (Catharsis) grow with the head too.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {
    @WrapOperation(
        method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/layers/HumanoidArmorLayer;renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V"
        )
    )
    private void cryptic$scaleHelmet(
        HumanoidArmorLayer<?, ?, ?> layer, PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot, int light,
        HumanoidRenderState state, Operation<Void> original
    ) {
        Float scale = slot == EquipmentSlot.HEAD && state instanceof AvatarRenderState avatar
            && CustomScale.appliesTo(CustomScale.entityOf(avatar)) ? CustomScale.headScale() : null;
        if (scale == null) {
            original.call(layer, poseStack, collector, stack, slot, light, state);
            return;
        }
        ModelPart head = ((HumanoidModel<?>) ((RenderLayer<?, ?>) (Object) this).getParentModel()).head;
        poseStack.pushPose();
        poseStack.translate(head.x / 16.0F, head.y / 16.0F, head.z / 16.0F);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-head.x / 16.0F, -head.y / 16.0F, -head.z / 16.0F);
        original.call(layer, poseStack, collector, stack, slot, light, state);
        poseStack.popPose();
    }
}
