package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.vertex.VertexConsumer;
import imicro.cryptic.duck.OutlinedGlyph;
import imicro.cryptic.feature.TextOutline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Text Outline: shadows drawn on every side of a letter, and bold forced on all text. */
public final class TextOutlineMixins {
    private TextOutlineMixins() {}

    private static final String RENDER =
        "Lnet/minecraft/client/gui/font/glyphs/BakedSheetGlyph;render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V";

    /**
     * Replaces a letter's shadow with its outline. Wraps the shadow draw rather than the glyph's
     * render so nothing is allocated per letter.
     */
    @Mixin(BakedSheetGlyph.class)
    public abstract static class Sheet implements OutlinedGlyph {
        @Shadow @Final private GlyphInfo info;
        @Shadow @Final private float u0;
        @Shadow @Final private float u1;
        @Shadow @Final private float v0;
        @Shadow @Final private float v1;

        @Shadow
        private void render(boolean italic, float x, float y, float z, Matrix4fc pose, VertexConsumer builder, int color, boolean bold, int packedLightCoords) {
            throw new AssertionError();
        }

        @WrapWithCondition(method = "renderChar", at = @At(value = "INVOKE", target = RENDER, ordinal = 0))
        private boolean cryptic$outline(BakedSheetGlyph self, boolean italic, float x, float y, float z, Matrix4fc pose, VertexConsumer buffer, int color, boolean bold, int light) {
            int mode = TextOutline.outlineMode;
            if (mode == TextOutline.OFF) return true;
            float s = info.getShadowOffset();
            float b = bold ? info.getBoldOffset() : 0.0F;
            // The shadow is drawn one offset down and right of the letter.
            float gx = x - s;
            float gy = y - s;
            int c = TextOutline.outlineColour(color);
            boolean corners = mode == TextOutline.SQUARE;
            // The letter (and its bold copy) hides the middle of the middle row.
            render(italic, gx - s, gy, z, pose, buffer, c, bold, light);
            render(italic, gx + b + s, gy, z, pose, buffer, c, bold, light);
            cryptic$row(italic, gx, gy - s, z, s, b, corners, pose, buffer, c, bold, light);
            cryptic$row(italic, gx, gy + s, z, s, b, corners, pose, buffer, c, bold, light);
            return false;
        }

        /** The bold shadow is part of the outline already. */
        @WrapWithCondition(method = "renderChar", at = @At(value = "INVOKE", target = RENDER, ordinal = 1))
        private boolean cryptic$skipBoldShadow(BakedSheetGlyph self, boolean italic, float x, float y, float z, Matrix4fc pose, VertexConsumer buffer, int color, boolean bold, int light) {
            return TextOutline.outlineMode == TextOutline.OFF;
        }

        private void cryptic$row(boolean italic, float gx, float y, float z, float s, float b, boolean corners, Matrix4fc pose, VertexConsumer buffer, int c, boolean bold, int light) {
            if (corners) render(italic, gx - s, y, z, pose, buffer, c, bold, light);
            render(italic, gx, y, z, pose, buffer, c, bold, light);
            if (b != 0.0F) render(italic, gx + b, y, z, pose, buffer, c, bold, light);
            if (corners) render(italic, gx + b + s, y, z, pose, buffer, c, bold, light);
        }

        @Override
        public void cryptic$rect(Matrix4fc pose, VertexConsumer buffer, float x0, float y0, float x1, float y1, float z, int color, int light) {
            // Same corners and texture coordinates as the glyph's own effect quads.
            buffer.addVertex(pose, x0, y1, z).setColor(color).setUv(u0, v0).setLight(light);
            buffer.addVertex(pose, x1, y1, z).setColor(color).setUv(u0, v1).setLight(light);
            buffer.addVertex(pose, x1, y0, z).setColor(color).setUv(u1, v1).setLight(light);
            buffer.addVertex(pose, x0, y0, z).setColor(color).setUv(u1, v0).setLight(light);
        }
    }

    /** Grows a letter's bounds to cover the outline above and left of it. */
    @Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$GlyphInstance")
    public abstract static class Glyph {
        @Shadow @Final private int shadowColor;
        @Shadow @Final private float shadowOffset;

        @ModifyReturnValue(method = {"left", "top"}, at = @At("RETURN"))
        private float cryptic$outlineBounds(float edge) {
            return TextOutline.outlineMode != TextOutline.OFF && shadowColor != 0 ? edge - shadowOffset : edge;
        }
    }

    /**
     * An underline or strikethrough is a plain rectangle, so its outline is one bigger rectangle.
     * Each letter has its own piece, drawn in order, so the outline doesn't grow left: it would
     * cover the end of the previous letter's line.
     */
    @Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$EffectInstance")
    public abstract static class Effect {
        @Shadow @Final private BakedSheetGlyph glyph;
        @Shadow @Final private float x0;
        @Shadow @Final private float y0;
        @Shadow @Final private float x1;
        @Shadow @Final private float y1;
        @Shadow @Final private float depth;
        @Shadow @Final private int color;
        @Shadow @Final private int shadowColor;
        @Shadow @Final private float shadowOffset;

        @Inject(method = "render", at = @At("HEAD"), cancellable = true)
        private void cryptic$outline(Matrix4fc pose, VertexConsumer buffer, int light, boolean flat, CallbackInfo info) {
            if (TextOutline.outlineMode == TextOutline.OFF || shadowColor == 0) return;
            OutlinedGlyph sheet = (OutlinedGlyph) (Object) glyph;
            float s = shadowOffset;
            sheet.cryptic$rect(pose, buffer, x0, y0 - s, x1 + s, y1 + s, depth, TextOutline.outlineColour(shadowColor), light);
            sheet.cryptic$rect(pose, buffer, x0, y0, x1, y1, depth + Font.SHADOW_DEPTH, color, light);
            info.cancel();
        }
    }

    /** Bold on every letter as it is laid out. */
    @Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
    public abstract static class BoldGlyphs {
        @ModifyVariable(method = "accept(ILnet/minecraft/network/chat/Style;Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;)Z", at = @At("HEAD"), argsOnly = true)
        private Style cryptic$bold(Style style) {
            return TextOutline.boldAll ? TextOutline.bolded(style) : style;
        }
    }

    /** And in every width measured, so wrapping and centring match what is drawn. */
    @Mixin(Font.class)
    public abstract static class BoldWidths {
        @ModifyExpressionValue(method = {"lambda$new$0", "lambda$prepare8xTextOutline$0"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Style;isBold()Z"))
        private boolean cryptic$bold(boolean bold) {
            return bold || TextOutline.boldAll;
        }
    }
}
