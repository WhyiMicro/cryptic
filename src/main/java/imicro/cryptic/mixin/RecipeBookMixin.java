package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Render Optimizer's Hide recipe book: the button is never added, and the book stays shut. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookMixin {
    @WrapOperation(
        method = "initButton",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;addRenderableWidget(Lnet/minecraft/client/gui/components/events/GuiEventListener;)Lnet/minecraft/client/gui/components/events/GuiEventListener;"
        )
    )
    private GuiEventListener cryptic$skipRecipeButton(AbstractRecipeBookScreen<?> screen, GuiEventListener widget, Operation<GuiEventListener> original) {
        if (RenderOptimizer.hidesRecipeBook()) return widget;
        return original.call(screen, widget);
    }
}
