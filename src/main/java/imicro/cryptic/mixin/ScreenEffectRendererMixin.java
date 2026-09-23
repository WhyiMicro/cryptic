package imicro.cryptic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import imicro.cryptic.feature.CameraTweaks;
import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The overlays painted over your own screen: Camera hides the water and the
 * inside-a-block texture, and Render Optimizer hides or fades the fire.
 *
 * Since 26.2 these are submitted rather than drawn, hence {@code submitWater}
 * and {@code submitFire} where they used to be {@code render…}. The fire's
 * colour is now one packed value handed to the quad builder, which is a
 * cleaner place to fade it than the four vertices it used to be written into
 * one at a time — and a {@code @ModifyArg} on it claims nothing, where the old
 * redirect of each vertex's colour was exclusive.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    private static final String BUILD_SPRITE_QUAD =
        "Lnet/minecraft/client/renderer/ScreenEffectRenderer;buildSpriteQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;FFFFFI)V";

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void cryptic$hideWaterOverlay(Minecraft minecraft, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo info) {
        if (CameraTweaks.hidesWaterOverlay()) info.cancel();
    }

    /**
     * Answering "no block" is what hides the texture pasted over the screen
     * inside a block: the renderer draws whatever this hands back, so handing
     * back nothing is the whole of it.
     */
    @Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true)
    private static void cryptic$hideBlockOverlay(Player player, CallbackInfoReturnable<BlockState> cir) {
        if (CameraTweaks.hidesBlockOverlay()) cir.setReturnValue(null);
    }

    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void cryptic$hideFireOverlay(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo info) {
        if (RenderOptimizer.hidesFireOverlay()) info.cancel();
    }

    /** Argument eight is the quad's colour, whose alpha is the whole of the fade. */
    @ModifyArg(method = "buildFireQuad", at = @At(value = "INVOKE", target = BUILD_SPRITE_QUAD), index = 8)
    private static int cryptic$fadeFireOverlay(int argb) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * RenderOptimizer.fireOverlayAlpha());
        return (argb & 0x00FFFFFF) | (Math.clamp(alpha, 0, 255) << 24);
    }
}
