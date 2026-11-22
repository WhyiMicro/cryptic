package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import imicro.cryptic.feature.HidePlayers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.SkullBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide Players' Head mode: a player's skin, worn skull and armour drawn with back faces culled, so
 * whatever the camera is inside isn't drawn from within, and the outer skin layer of the part the
 * camera is inside left out.
 */
public final class HidePlayersMixins {
    private HidePlayersMixins() {}

    @Mixin(LivingEntityRenderer.class)
    public abstract static class Skin {
        @Shadow
        protected EntityModel<?> model;

        @Shadow
        public abstract Identifier getTextureLocation(LivingEntityRenderState state);

        /** The model's transform is final here, just before it is submitted. */
        @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;")
        )
        private void cryptic$hideOuterLayers(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo info) {
            if (model instanceof PlayerModel player && state instanceof AvatarRenderState avatar) {
                HidePlayers.hideOuterLayersAround(player, avatar, poseStack);
            }
        }

        @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
        private void cryptic$cullSkin(LivingEntityRenderState state, boolean isBodyVisible, boolean forceTransparent, boolean appearGlowing, CallbackInfoReturnable<RenderType> info) {
            if (isBodyVisible && !forceTransparent && HidePlayers.cullsInsideState(state)) {
                info.setReturnValue(HidePlayers.skinType(getTextureLocation(state)));
            }
        }
    }

    @Mixin(CustomHeadLayer.class)
    public abstract static class WornSkull {
        @Shadow
        @Final
        private PlayerSkinRenderCache playerSkinRenderCache;

        @Inject(method = "resolveSkullRenderType", at = @At("HEAD"), cancellable = true)
        private void cryptic$cullSkull(LivingEntityRenderState state, SkullBlock.Type type, CallbackInfoReturnable<RenderType> info) {
            ResolvableProfile profile = state.wornHeadProfile;
            if (type == SkullBlock.Types.PLAYER && profile != null && HidePlayers.cullsInsideState(state)) {
                Identifier texture = playerSkinRenderCache.getOrDefault(profile).playerSkin().body().texturePath();
                info.setReturnValue(HidePlayers.skinType(texture));
            }
        }

        @WrapOperation(
            method = "submit",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/SkullBlockRenderer;submitSkull(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/model/object/skull/SkullModelBase;Lnet/minecraft/client/renderer/rendertype/RenderType;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V")
        )
        private void cryptic$skullWithoutHat(float animation, PoseStack poseStack, SubmitNodeCollector collector, int light, SkullModelBase model, RenderType renderType, int outline, ModelFeatureRenderer.CrumblingOverlay crumbling, Operation<Void> original, @Local(argsOnly = true) LivingEntityRenderState state) {
            boolean hideHat = HidePlayers.hidesSkullHat(state, poseStack);
            original.call(animation, poseStack, collector, light, hideHat ? HidePlayers.getSkullWithoutHat() : model, renderType, outline, crumbling);
        }
    }

    @Mixin(EquipmentLayerRenderer.class)
    public abstract static class Armour {
        @WrapOperation(
            method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;")
        )
        private RenderType cryptic$cullArmour(Identifier texture, Operation<RenderType> original, @Local(argsOnly = true) Object state) {
            return HidePlayers.cullsInsideState(state) ? HidePlayers.armourType(texture) : original.call(texture);
        }
    }
}
