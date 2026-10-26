package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import imicro.cryptic.feature.CrosshairEditor;
import imicro.cryptic.feature.TooltipFeatures;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Moves and sizes a tooltip by Scrollable Tooltips' scroll. Wraps the whole method, since the
 * positioner clamps coordinates to the screen; moving the matrix moves the finished tooltip. The
 * same place NoammAddons does it.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    /**
     * Drops vanilla's (or a resource pack's) crosshair while the Crosshair Editor draws its own.
     * Every plain {@code blitSprite} overload ends here, so mods wrapping the crosshair call still
     * arrive.
     */
    @Inject(
        method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cryptic$hideVanillaCrosshair(
        RenderPipeline pipeline,
        Identifier location,
        int x,
        int y,
        int width,
        int height,
        int color,
        CallbackInfo info
    ) {
        if (CrosshairEditor.hidesSprite(location)) info.cancel();
    }

    /** The crosshair drawn from the atlas sprite directly, as some mods do. */
    @Inject(
        method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cryptic$hideVanillaCrosshairSprite(
        RenderPipeline pipeline,
        TextureAtlasSprite sprite,
        int x,
        int y,
        int width,
        int height,
        int color,
        CallbackInfo info
    ) {
        if (CrosshairEditor.hidesSprite(sprite)) info.cancel();
    }

    /** The text of an item's tooltip, as Tooltip Features last sees it before drawing. */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private List<net.minecraft.network.chat.Component> cryptic$tooltipText(List<net.minecraft.network.chat.Component> texts) {
        return TooltipFeatures.beforeDraw(texts);
    }

    @Inject(method = "tooltip", at = @At("HEAD"))
    private void cryptic$offsetTooltip(
        Font font,
        List<ClientTooltipComponent> lines,
        int xo,
        int yo,
        ClientTooltipPositioner positioner,
        @Nullable Identifier style,
        CallbackInfo info
    ) {
        TooltipFeatures.beforeTooltip((GuiGraphicsExtractor) (Object) this, xo, yo);
    }

    @Inject(method = "tooltip", at = @At("RETURN"))
    private void cryptic$restoreTooltip(
        Font font,
        List<ClientTooltipComponent> lines,
        int xo,
        int yo,
        ClientTooltipPositioner positioner,
        @Nullable Identifier style,
        CallbackInfo info
    ) {
        TooltipFeatures.afterTooltip((GuiGraphicsExtractor) (Object) this);
    }
}
