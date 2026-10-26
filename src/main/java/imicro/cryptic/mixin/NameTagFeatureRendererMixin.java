package imicro.cryptic.mixin;

import imicro.cryptic.feature.CustomNametags;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets Cryptic restyle every name tag. Since 26.2 a tag is laid out in {@code prepareText}, which
 * is static, so these handlers are too.
 */
@Mixin(NameTagFeatureRenderer.class)
public abstract class NameTagFeatureRendererMixin {
    private static final String FONT_PREPARE_TEXT =
        "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)"
            + "Lnet/minecraft/client/gui/Font$PreparedText;";

    @ModifyArg(method = "prepareText", at = @At(value = "INVOKE", target = FONT_PREPARE_TEXT), index = 4)
    private static boolean cryptic$nameTagShadow(boolean dropShadow) {
        return CustomNametags.dropShadow(dropShadow);
    }

    @ModifyArg(method = "prepareText", at = @At(value = "INVOKE", target = FONT_PREPARE_TEXT), index = 6)
    private static int cryptic$nameTagBackground(int backgroundColor) {
        return CustomNametags.backgroundColor(backgroundColor);
    }
}
