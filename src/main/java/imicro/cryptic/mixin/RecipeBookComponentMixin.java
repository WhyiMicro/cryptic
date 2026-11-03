package imicro.cryptic.mixin;

import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A book left open from before is shut while its button is hidden. Shut through the book's own
 * setting, so the next tick doesn't open it again.
 */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Shadow
    public abstract boolean isVisible();

    @Shadow
    protected abstract void setVisible(boolean visible);

    @Inject(method = "init", at = @At("TAIL"))
    private void cryptic$shutHiddenBook(CallbackInfo info) {
        if (RenderOptimizer.hidesRecipeBook() && this.isVisible()) this.setVisible(false);
    }
}
