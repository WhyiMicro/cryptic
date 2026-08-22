package imicro.cryptic.mixin;

import imicro.cryptic.feature.CustomNametags;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Lets Cryptic restyle every name tag the game draws over an entity. */
@Mixin(NameTagFeatureRenderer.class)
public abstract class NameTagFeatureRendererMixin {
    private static final String DRAW_IN_BATCH =
        "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V";

    @ModifyArg(method = "renderTranslucent", at = @At(value = "INVOKE", target = DRAW_IN_BATCH), index = 4)
    private boolean cryptic$nameTagShadow(boolean dropShadow) {
        return CustomNametags.dropShadow(dropShadow);
    }

    @ModifyArg(method = "renderTranslucent", at = @At(value = "INVOKE", target = DRAW_IN_BATCH), index = 8)
    private int cryptic$nameTagBackground(int backgroundColor) {
        return CustomNametags.backgroundColor(backgroundColor);
    }
}
