package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import imicro.cryptic.feature.Animations;
import imicro.cryptic.feature.BlockHittingVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the Animations module to Minecraft's first-person item renderer. */
// Apply after renderer mods using Mixin's default priority. In particular,
// Bring Blocking Back redirects the same swing call as Cryptic's optional
// compatibility path below.
@Mixin(value = ItemInHandRenderer.class, priority = 900)
public abstract class ItemInHandRendererMixin {
    @Shadow private ItemStack mainHandItem;
    @Shadow private float oMainHandHeight;
    @Shadow private float mainHandHeight;
    @Shadow private float oOffHandHeight;
    @Shadow private float offHandHeight;

    @Shadow
    protected abstract void swingArm(
        float swingProgress,
        PoseStack poseStack,
        int armX,
        HumanoidArm arm
    );

    @Shadow
    protected abstract void applyItemArmAttackTransform(
        PoseStack poseStack,
        HumanoidArm arm,
        float swingProgress
    );

    @Inject(
        method = "submitArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
            shift = At.Shift.AFTER
        )
    )
    private void cryptic$translateItem(
        AbstractClientPlayer player,
        float frameInterpolation,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        int light,
        CallbackInfo ci
    ) {
        if (!Animations.module.getEnabled() || stack.isEmpty()) return;

        float offsetX = (float) Animations.positionX.getValue();
        float offsetY = (float) Animations.positionY.getValue();
        float offsetZ = (float) Animations.positionZ.getValue();
        if (offsetX == 0.0f && offsetY == 0.0f && offsetZ == 0.0f) return;

        float handSign = hand == InteractionHand.MAIN_HAND ? 1.0f : -1.0f;
        poseStack.translate(offsetX * handSign, offsetY, offsetZ);
    }

    /**
     * Let vanilla render the main hand as the opposite arm. This moves both the
     * arm and item without mirroring their textures or reversing their models.
     */
    @Redirect(
        method = "submitArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/AbstractClientPlayer;getMainArm()Lnet/minecraft/world/entity/HumanoidArm;"
        )
    )
    private HumanoidArm cryptic$useLeftHand(AbstractClientPlayer player) {
        HumanoidArm mainArm = player.getMainArm();
        if (Animations.module.getEnabled() && Animations.leftHand.getValue()) {
            return mainArm.getOpposite();
        }
        return mainArm;
    }

    @ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), ordinal = 2, argsOnly = true)
    private float cryptic$modifySwingProgress(float swingProgress) {
        if (!Animations.module.getEnabled() || !Animations.disableSwingAnimation.getValue()) {
            return swingProgress;
        }
        if (Animations.terminatorOnly.getValue() && !Animations.isTerminator(mainHandItem)) {
            return swingProgress;
        }
        return 1.0f;
    }

    /**
     * Minecraft can briefly lower the held item when an attack is registered.
     * Keep the normal swing transform but discard that equip offset. Real item
     * swaps still use the ordinary equip animation when its toggle is disabled.
     */
    @ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), ordinal = 3, argsOnly = true)
    private float cryptic$keepSwingEquipped(float equipProgress) {
        if (!Animations.module.getEnabled()) return equipProgress;

        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null) return equipProgress;
        return player.swinging && !player.isUsingItem() ? 0.0f : equipProgress;
    }

    @Inject(
        method = "submitArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"
        )
    )
    private void cryptic$transformItem(
        AbstractClientPlayer player,
        float frameInterpolation,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        int light,
        CallbackInfo ci
    ) {
        if (!Animations.module.getEnabled()) return;

        // Rotating by zero and scaling by one are identity transforms; skipping
        // them keeps the default configuration free of per-frame matrix work.
        float rotationX = (float) Animations.rotationX.getValue();
        float rotationY = (float) Animations.rotationY.getValue();
        float rotationZ = (float) Animations.rotationZ.getValue();
        if (rotationX != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(rotationX));
        if (rotationY != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(rotationY));
        if (rotationZ != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(rotationZ));

        float scale = 1.0f + (float) Animations.itemScale.getValue();
        if (scale != 1.0f) poseStack.scale(scale, scale, scale);
    }

    /**
     * BringBlockingBack applies its pose inside renderItem rather than beside
     * vanilla's first-person transforms. Keeping the same boundary prevents
     * Cryptic's position, rotation, and scale controls from changing which
     * transform the block animation considers its origin.
     */
    @Inject(method = "renderItem", at = @At("HEAD"))
    private void cryptic$applySwordBlockPose(
        net.minecraft.world.entity.LivingEntity livingEntity,
        ItemStack itemStack,
        net.minecraft.world.item.ItemDisplayContext displayContext,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        int light,
        CallbackInfo ci
    ) {
        if (!BlockHittingVisuals.isBlockingWith(livingEntity, itemStack)) return;

        float side = displayContext == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            ? -1.0f
            : 1.0f;
        poseStack.translate(-0.15f * side, 0.16f, 0.15f);
        poseStack.mulPose(Axis.YP.rotationDegrees(-18.0f * side));
        poseStack.mulPose(Axis.ZP.rotationDegrees(82.0f * side));
        poseStack.mulPose(Axis.YP.rotationDegrees(112.0f * side));
    }

    /**
     * Port of BringBlockingBack's block-hit swing path, first written for 26.1.2 and unchanged since. Vanilla
     * already applied the arm/equip transform before this call in this version,
     * so the blocking path only needs the attack rotation. In particular it
     * skips swingArm's large XYZ translation, which caused the sword to jump
     * around when combined with Cryptic's item-position and scale settings.
     */
    @Redirect(
        method = "submitArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"
        ),
        require = 0
    )
    private void cryptic$renderSwordBlockSwing(
        ItemInHandRenderer instance,
        float swingProgress,
        PoseStack poseStack,
        int armX,
        HumanoidArm arm
    ) {
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player != null && BlockHittingVisuals.isBlockingWith(player, player.getMainHandItem())) {
            applyItemArmAttackTransform(poseStack, arm, swingProgress);
        } else {
            swingArm(swingProgress, poseStack, armX, arm);
        }
    }

    @Redirect(
        method = "swingArm",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V")
    )
    private void cryptic$modifySwingTranslation(PoseStack poseStack, float x, float y, float z) {
        if (!Animations.module.getEnabled()) {
            poseStack.translate(x, y, z);
            return;
        }
        poseStack.translate(
            x * (float) Animations.swingX.getValue(),
            y * (float) Animations.swingY.getValue(),
            z * (float) Animations.swingZ.getValue()
        );
    }

    @Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true)
    private void cryptic$skipEquipAnimation(
        ItemStack visibleItem,
        ItemStack expectedItem,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (Animations.module.getEnabled() && Animations.disableEquipAnimation.getValue()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptic$keepItemsEquipped(CallbackInfo ci) {
        if (Animations.module.getEnabled() && Animations.disableEquipAnimation.getValue()) {
            oMainHandHeight = 1.0f;
            mainHandHeight = 1.0f;
            oOffHandHeight = 1.0f;
            offHandHeight = 1.0f;
        }
    }

    @Redirect(
        method = "submitHandsWithItems",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V")
    )
    private void cryptic$disableHandMovement(PoseStack poseStack, Quaternionfc rotation) {
        if (!(Animations.module.getEnabled() && Animations.disableHandMovement.getValue())) {
            poseStack.mulPose(rotation);
        }
    }
}
