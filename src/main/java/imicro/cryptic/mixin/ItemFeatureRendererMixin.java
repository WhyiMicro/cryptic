package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import imicro.cryptic.feature.ItemOutline;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Outline's overlay goes on in the glint's pass, as a second coat of the held item's quads.
 * Their UVs are where each vertex sits on the item's plane ({@link imicro.cryptic.render.ItemSpaceUv}), so
 * the pattern sits on the item, moves with it, and runs over its edges.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin extends RenderTypeFeatureRenderer<ItemFeatureRenderer.Submit> {
    @Shadow @Final private QuadInstance quadInstance;

    @Inject(method = "prepareFoilSubmit", at = @At("HEAD"), cancellable = true)
    private void cryptic$overlayHeldItem(ItemFeatureRenderer.Submit submit, CallbackInfo info) {
        if (ItemOutline.drawsSharpOutline(submit.displayContext())) {
            int color = ItemOutline.sharpColor();
            for (org.joml.Matrix4f offset : ItemOutline.sharpOffsets(submit.pose().pose())) {
                com.mojang.blaze3d.vertex.PoseStack.Pose pose = submit.pose().copy();
                pose.pose().set(offset);
                for (BakedQuad quad : submit.quads()) {
                    RenderType silhouette = ItemOutline.silhouetteType(quad.materialInfo().sprite().atlasLocation());
                    new imicro.cryptic.render.FlatColor(this.getVertexBuilder(silhouette), color).putBakedQuad(pose, quad, this.quadInstance);
                }
            }
        }
        RenderType type = ItemOutline.overlayType(submit.displayContext());
        if (type == null) return;
        VertexConsumer buffer = new imicro.cryptic.render.ItemSpaceUv(this.getVertexBuilder(type), submit.pose());
        for (BakedQuad quad : submit.quads()) {
            buffer.putBakedQuad(submit.pose(), quad, this.quadInstance);
        }
        if (ItemOutline.hidesGlint(submit.displayContext())) info.cancel();
    }
}
