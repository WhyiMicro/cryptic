package imicro.cryptic.mixin;

import imicro.cryptic.feature.CustomNametags;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets Cryptic restyle every name tag the game draws over an entity.
 *
 * Since 26.2 a name tag is laid out once in {@code prepareText} and drawn from
 * the result, rather than drawn directly — so the shadow and the background are
 * decided where the text is prepared. That method is static, which is why these
 * handlers are too.
 */
@Mixin(NameTagFeatureRenderer.class)
public abstract class NameTagFeatureRendererMixin {
    private static final String FONT_PREPARE_TEXT =
        "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)"
            + "Lnet/minecraft/client/gui/Font$PreparedText;";

    /** text, x, y, colour, <b>drawShadow</b>, includeEmpty, background. */
    @ModifyArg(method = "prepareText", at = @At(value = "INVOKE", target = FONT_PREPARE_TEXT), index = 4)
    private static boolean cryptic$nameTagShadow(boolean dropShadow) {
        return CustomNametags.dropShadow(dropShadow);
    }

    @ModifyArg(method = "prepareText", at = @At(value = "INVOKE", target = FONT_PREPARE_TEXT), index = 6)
    private static int cryptic$nameTagBackground(int backgroundColor) {
        return CustomNametags.backgroundColor(backgroundColor);
    }
}
