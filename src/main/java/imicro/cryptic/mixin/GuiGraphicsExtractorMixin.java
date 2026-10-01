package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import imicro.cryptic.feature.CrosshairEditor;
import imicro.cryptic.feature.ScrollableTooltips;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Moves and sizes a tooltip by whatever Scrollable Tooltips has been scrolled
 * to.
 *
 * Wrapped around the whole method rather than applied to the coordinates it is
 * given: the position those produce is clamped to the screen by the positioner,
 * which is exactly the behaviour being worked around. Changing the matrix moves
 * the finished tooltip, clamp and all - the same place NoammAddons does it.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    /**
     * Drops vanilla's crosshair sprite — or a resource pack's in its place —
     * while the Crosshair Editor is drawing its own.
     *
     * Every plain {@code blitSprite} overload ends up in this one, so any mod
     * that wraps the HUD's crosshair call still arrives here when it draws.
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
        ScrollableTooltips.beforeTooltip((GuiGraphicsExtractor) (Object) this, xo, yo);
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
        ScrollableTooltips.afterTooltip((GuiGraphicsExtractor) (Object) this);
    }
}
