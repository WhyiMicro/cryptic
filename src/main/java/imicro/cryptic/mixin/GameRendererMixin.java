package imicro.cryptic.mixin;

import imicro.cryptic.gui.menu.MenuScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the blur pass Cryptic's own strength while Cryptic's menu is open.
 *
 * Vanilla blurs a screen by the Menu Background Blur option, and plenty of
 * players keep that at nought; this lets the menu blur anyway, by its own
 * setting, without touching the option or any other screen.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private GameRenderState gameRenderState;

    @Inject(method = "extractOptions", at = @At("TAIL"))
    private void cryptic$menuBlur(CallbackInfo info) {
        int radius = MenuScreen.blurRadius();
        if (radius > 0) this.gameRenderState.optionsRenderState.menuBackgroundBlurriness = radius;
    }
}
