package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import imicro.cryptic.feature.PackBlocks;
import imicro.cryptic.feature.PackScreens;
import imicro.cryptic.feature.PackTexts;
import imicro.cryptic.feature.PackTweaks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.model.Model;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Pack Tweaks: the Firmament (`firmskyblock`) resource pack format. */
public class PackTweaksMixins {
    /** Turns old-format `firmskyblock` item models into client items, and reads the pack format. */
    @Mixin(ClientItemInfoLoader.class)
    public static class Loader {
        @Inject(method = "scheduleLoad", at = @At("RETURN"), cancellable = true)
        private static void cryptic$addLegacyModels(
            ResourceManager manager,
            Executor executor,
            CallbackInfoReturnable<CompletableFuture<ClientItemInfoLoader.LoadedClientInfos>> cir
        ) {
            cir.setReturnValue(PackTweaks.withLegacyModels(manager, cir.getReturnValue()));
        }
    }

    /** Puts what was read live once models are baked, so none is used before it can be drawn. */
    @Mixin(ModelManager.class)
    public static class Models {
        @Shadow
        private Map<Identifier, ItemModel> bakedItemStackModels;

        @Inject(method = "apply", at = @At("TAIL"))
        private void cryptic$noteItemModels(CallbackInfo ci) {
            PackTweaks.onItemModelsBaked(bakedItemStackModels.keySet());
            PackBlocks.onModelsBaked();
        }
    }

    /** Draws a SkyBlock item with the pack's model for its ID, swap animation included. */
    @Mixin(ItemModelResolver.class)
    public static class Resolver {
        @WrapOperation(
            method = {"appendItemLayers", "shouldPlaySwapAnimation", "swapAnimationScale"},
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"
            )
        )
        private Object cryptic$packModel(ItemStack stack, DataComponentType<?> type, Operation<Object> original) {
            Object model = original.call(stack, type);
            if (type != DataComponents.ITEM_MODEL) return model;
            if (PackTweaks.forcedModel != null) return PackTweaks.forcedModel;
            return PackTweaks.modelFor(stack, (Identifier) model);
        }
    }

    /** Draws a model's `firmament:head_model` in place of the worn helmet. */
    @Mixin(LivingEntityRenderer.class)
    public static class HeadModel {
        @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
        private void cryptic$headModel(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
            ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty()) return;
            ClientLevel level = entity.level() instanceof ClientLevel client ? client : null;
            Identifier model = PackTweaks.headModelFor(head, entity, level);
            if (model == null) return;
            state.wornHeadType = null;
            state.wornHeadProfile = null;
            PackTweaks.forcedModel = model;
            try {
                Minecraft.getInstance().getItemModelResolver().updateForLiving(state.headItem, head, ItemDisplayContext.HEAD, entity);
            } finally {
                PackTweaks.forcedModel = null;
            }
        }
    }

    /** Draws worn armour with the pack's layers for its SkyBlock ID. */
    @Mixin(EquipmentLayerRenderer.class)
    public static class Armour {
        @Inject(
            method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At("HEAD"),
            cancellable = true
        )
        private <S> void cryptic$packArmour(
            EquipmentClientInfo.LayerType layerType,
            ResourceKey<EquipmentAsset> assetId,
            Model<? super S> model,
            S state,
            ItemStack stack,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            @Nullable Identifier playerTexture,
            int outline,
            int order,
            CallbackInfo ci
        ) {
            if (layerType != EquipmentClientInfo.LayerType.HUMANOID
                && layerType != EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS
                && layerType != EquipmentClientInfo.LayerType.HUMANOID_BABY) return;
            List<PackTweaks.ArmourTexture> layers = PackTweaks.armourFor(stack, layerType == EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS);
            if (layers == null) return;
            int dye = DyedItemColor.getOrDefault(stack, 0);
            // Leather's colour when undyed.
            int tint = dye != 0 ? dye : ARGB.opaque(0xA06540);
            boolean foil = stack.hasFoil();
            int next = order;
            for (PackTweaks.ArmourTexture layer : layers) {
                int color = layer.tint ? tint : -1;
                RenderType type = RenderTypes.armorCutoutNoCull(layer.texture);
                collector.order(next++).submitModel(model, state, poseStack, type, light, OverlayTexture.NO_OVERLAY, color, null, outline, null);
                if (foil) {
                    collector.order(next++).submitModel(model, state, poseStack, RenderTypes.armorEntityGlint(), light, OverlayTexture.NO_OVERLAY, color, null, outline, null);
                    foil = false;
                }
            }
            ci.cancel();
        }
    }

    /** Swaps a placed head's skin for the pack's texture for that skin. */
    @Mixin(SkullBlockRenderer.class)
    public static class Skulls {
        @Inject(method = "resolveSkullRenderType", at = @At("HEAD"), cancellable = true)
        private void cryptic$packSkull(SkullBlock.Type type, SkullBlockEntity entity, CallbackInfoReturnable<RenderType> cir) {
            if (type != SkullBlock.Types.PLAYER) return;
            Identifier texture = PackTweaks.placedSkull(entity.getOwnerProfile());
            if (texture != null) cir.setReturnValue(SkullBlockRenderer.getPlayerSkinRenderType(texture));
        }
    }

    @Mixin(AbstractContainerScreen.class)
    public interface LabelAccessor {
        @Accessor("titleLabelX") int cryptic$titleLabelX();
        @Accessor("titleLabelY") int cryptic$titleLabelY();
        @Accessor("inventoryLabelX") int cryptic$inventoryLabelX();
        @Accessor("inventoryLabelY") int cryptic$inventoryLabelY();
        @Accessor("titleLabelX") void cryptic$setTitleLabelX(int x);
        @Accessor("titleLabelY") void cryptic$setTitleLabelY(int y);
        @Accessor("inventoryLabelX") void cryptic$setInventoryLabelX(int x);
        @Accessor("inventoryLabelY") void cryptic$setInventoryLabelY(int y);
    }

    @Mixin(Slot.class)
    public interface SlotAccessor {
        @Mutable @Accessor("x") void cryptic$setX(int x);
        @Mutable @Accessor("y") void cryptic$setY(int y);
    }

    /** Applies a screen layout whenever a menu is laid out. */
    @Mixin(Screen.class)
    public static class ScreenInit {
        @Inject(method = {"init(II)V", "resize(II)V"}, at = @At("TAIL"))
        private void cryptic$applyLayout(int width, int height, CallbackInfo ci) {
            if ((Object) this instanceof AbstractContainerScreen<?> screen) PackScreens.onInit(screen);
        }

        /** Marks a menu's background drawing, where its background texture is swapped. */
        @WrapOperation(
            method = "extractRenderStateWithTooltipAndSubtitles",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
        )
        private void cryptic$background(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, Operation<Void> original) {
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                original.call(screen, graphics, mouseX, mouseY, a);
                return;
            }
            PackScreens.beginBackground(container);
            try {
                original.call(screen, graphics, mouseX, mouseY, a);
            } finally {
                PackScreens.endBackground();
            }
        }
    }

    @Mixin(AbstractContainerScreen.class)
    public static class ContainerScreen {
        @Inject(method = "removed", at = @At("HEAD"))
        private void cryptic$restoreSlots(CallbackInfo ci) {
            PackScreens.restoreSlots();
        }

        @WrapOperation(
            method = "extractContents",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractLabels(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V")
        )
        private void cryptic$labels(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int x, int y, Operation<Void> original) {
            PackScreens.endBackground();
            PackScreens.drawingLabels = screen;
            try {
                original.call(screen, graphics, x, y);
            } finally {
                PackScreens.drawingLabels = null;
            }
        }

        @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
        private void cryptic$widerBackground(double mx, double my, int xo, int yo, CallbackInfoReturnable<Boolean> cir) {
            if (PackScreens.insideBackground((AbstractContainerScreen<?>) (Object) this, mx, my)) cir.setReturnValue(false);
        }
    }

    @Mixin(AbstractSignEditScreen.class)
    public static class SignScreen {
        @WrapOperation(
            method = "extractSign",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractSignEditScreen;extractSignBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V")
        )
        private void cryptic$signBackground(AbstractSignEditScreen screen, GuiGraphicsExtractor graphics, Operation<Void> original) {
            if (!PackScreens.drawSignBackground(screen, graphics)) original.call(screen, graphics);
        }

        @WrapOperation(
            method = "extractSignText",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V")
        )
        private void cryptic$signLine(GuiGraphicsExtractor graphics, Font font, String line, int x, int y, int color, boolean shadow, Operation<Void> original) {
            AbstractSignEditScreen screen = (AbstractSignEditScreen) (Object) this;
            PackScreens.Mover mover = PackScreens.signLineOffset(screen, y, ((SignEditScreenAccessor) screen).cryptic$sign().getTextLineHeight());
            if (mover != null) {
                if (mover.getX() != null) x += mover.getX();
                if (mover.getY() != null) y += mover.getY();
            }
            original.call(graphics, font, line, x, y, color, shadow);
        }
    }

    @Mixin(GuiGraphicsExtractor.class)
    public static class Graphics {
        /** Every texture blit ends here, so a menu's background is found whichever overload drew it. */
        @Inject(
            method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
            at = @At("HEAD"),
            cancellable = true
        )
        private void cryptic$screenBackground(
            RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int width, int height,
            int srcWidth, int srcHeight, int textureWidth, int textureHeight, int color, CallbackInfo ci
        ) {
            if (PackScreens.drawingBackground != null && PackScreens.replaceBlit((GuiGraphicsExtractor) (Object) this, pipeline, texture)) ci.cancel();
        }

        @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"), cancellable = true)
        private void cryptic$menuLabel(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
            if (PackScreens.drawingLabels != null && PackScreens.drawLabel((GuiGraphicsExtractor) (Object) this, font, text, x, y, color, shadow)) ci.cancel();
        }

        @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), argsOnly = true)
        private Component cryptic$replaceText(Component text) {
            return PackTexts.apply(text);
        }

        @ModifyVariable(method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V", at = @At("HEAD"), argsOnly = true)
        private Component cryptic$replaceCentredText(Component text) {
            return PackTexts.apply(text);
        }
    }

    /** Vanilla's chunk builder: draws each block with the pack's model for that place. */
    @Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCompiler")
    public static class Sections {
        @org.spongepowered.asm.mixin.injection.ModifyArgs(
            method = "compile",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V")
        )
        private void cryptic$packBlock(Args args) {
            BlockPos pos = args.get(5);
            BlockState state = args.get(6);
            BlockStateModel model = args.get(7);
            BlockStateModel replaced = PackBlocks.modelAt(state, pos, model);
            if (replaced != model) args.set(7, replaced);
        }
    }

    /** Sodium's chunk builder, when Sodium is installed. */
    @Pseudo
    @Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
    public static class SodiumBlocks {
        @ModifyVariable(method = "renderModel", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false, require = 0)
        private BlockStateModel cryptic$packBlock(BlockStateModel model, BlockStateModel original, BlockState state, BlockPos pos, BlockPos origin) {
            return PackBlocks.modelAt(state, pos, model);
        }
    }

    /** The pack's hit sound while mining a replaced block. */
    @Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
    public static class HitSound {
        @WrapOperation(
            method = "continueDestroyBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/SoundType;getHitSound()Lnet/minecraft/sounds/SoundEvent;")
        )
        private SoundEvent cryptic$hitSound(
            net.minecraft.world.level.block.SoundType type,
            Operation<SoundEvent> original,
            @Local(argsOnly = true) BlockPos pos
        ) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                SoundEvent sound = PackBlocks.hitSound(level.getBlockState(pos), pos);
                if (sound != null) return sound;
            }
            return original.call(type);
        }
    }
}
