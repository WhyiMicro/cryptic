package imicro.cryptic.mixin;

import imicro.cryptic.feature.CameraTweaks;
import imicro.cryptic.feature.CrosshairEditor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD's own overlays: Camera's portal swirl, and the Crosshair Editor.
 *
 * {@code Hud} is new in 26.2 — the HUD half of what used to be {@code Gui},
 * which is now only screens. The methods moved across unchanged.
 *
 * The crosshair is drawn here, straight after the point vanilla draws its own,
 * so it sits in the same layer and the attack indicator below it is still
 * vanilla's. Vanilla's sprite is <em>hidden</em> elsewhere — inside
 * {@code blitSprite} itself, in {@link GuiGraphicsExtractorMixin} — because
 * hiding it by editing this call's arguments lost to any mod that wraps the
 * call and passes its own.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    private static final String CROSSHAIR_BLIT =
        "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V";

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void cryptic$hidePortalOverlay(GuiGraphicsExtractor graphics, float alpha, CallbackInfo info) {
        if (CameraTweaks.hidesPortalOverlay()) info.cancel();
    }

    /** Ordinal zero is the crosshair; one and two are the attack indicator. */
    @Inject(method = "extractCrosshair", at = @At(value = "INVOKE", target = CROSSHAIR_BLIT, ordinal = 0, shift = At.Shift.AFTER))
    private void cryptic$drawCustomCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        CrosshairEditor.draw(graphics);
    }

    /** Vanilla returns before any of that in third person; this is that case. */
    @Inject(method = "extractCrosshair", at = @At("HEAD"))
    private void cryptic$drawThirdPersonCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        CrosshairEditor.drawThirdPerson(graphics);
    }
}
