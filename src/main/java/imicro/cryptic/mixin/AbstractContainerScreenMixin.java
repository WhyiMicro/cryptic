package imicro.cryptic.mixin;

import imicro.cryptic.feature.CroesusHelper;
import imicro.cryptic.feature.ExperimentSolver;
import imicro.cryptic.feature.InvincibilityTimer;
import imicro.cryptic.feature.ItemProtection;
import imicro.cryptic.feature.LoadoutManager;
import imicro.cryptic.feature.SlotBinds;
import imicro.cryptic.feature.SpiritLeapOverlay;
import imicro.cryptic.feature.TerminalSolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands a chest's input to whichever module has taken the chest over. The terminal solver's own
 * screen is drawn by {@link ScreenMixin}; clicks go through the solver, which holds the first-click
 * protection.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    /**
     * One slot, for the mask timer. Terminal slots are never drawn here; the solver replaces the
     * whole screen.
     */
    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void cryptic$drawSlotCooldown(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo info) {
        if (imicro.cryptic.feature.Bazaar.drawReopen(graphics, slot)) {
            info.cancel();
            return;
        }
        CroesusHelper.drawSlotHighlight(graphics, slot);
        ItemProtection.drawSlot(graphics, slot.getItem(), slot.x, slot.y, false, false);
        imicro.cryptic.feature.Bazaar.drawSlot(graphics, slot);
        if (InvincibilityTimer.drawSlotCooldown(graphics, slot)) info.cancel();
    }

    /** Marks drawn over the item. */
    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void cryptic$drawOverSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo info) {
        ItemProtection.drawSlot(graphics, slot.getItem(), slot.x, slot.y, true, false);
    }

    /** No tooltips for items the solver has hidden. */
    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideTerminalTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo info) {
        if (TerminalSolver.hidesTooltip()
                || imicro.cryptic.feature.RenderOptimizer.hidesTooltip(((ContainerScreenAccessor) this).cryptic$hoveredSlot())
                || imicro.cryptic.feature.Bazaar.drawReopenTooltip((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY)) info.cancel();
    }

    /** Every click the chest would make, including the drop key and hotbar numbers. */
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickTerminalSlot(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo info) {
        if (imicro.cryptic.feature.Bazaar.onSlotClicked((AbstractContainerScreen<?>) (Object) this, slot)
                || ItemProtection.blocksClick((AbstractContainerScreen<?>) (Object) this, slot, slotId, button, input)) {
            info.cancel();
            return;
        }
        // While an experiment plays itself, your own clicks stay out.
        if (ExperimentSolver.blocksManualClicks()) {
            info.cancel();
            return;
        }
        CroesusHelper.onSlotClicked(slotId);
        imicro.cryptic.feature.Farming.onSlotClicked((AbstractContainerScreen<?>) (Object) this, slotId);
        LoadoutManager.onSlotClicked(slotId, button, input);
        if (TerminalSolver.handleSlotClick(slotId, button, input)) info.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickTerminal(MouseButtonEvent click, boolean doubleClick, CallbackInfoReturnable<Boolean> info) {
        if (SpiritLeapOverlay.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.x(), click.y())) {
            info.setReturnValue(true);
            return;
        }

        if (click.button() == 0 && imicro.cryptic.feature.Farming.handleMouseClick(click.x(), click.y())) {
            info.setReturnValue(true);
            return;
        }

        if (click.button() == 0 && imicro.cryptic.feature.BazaarPresets.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.x(), click.y())) {
            info.setReturnValue(true);
            return;
        }

        if (TerminalSolver.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.x(), click.y(), click.button())) {
            info.setReturnValue(true);
            return;
        }

        if (LoadoutManager.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.button())) {
            info.setReturnValue(true);
            return;
        }

        if (ItemProtection.handleMouse((AbstractContainerScreen<?>) (Object) this, click.button())) {
            info.setReturnValue(true);
            return;
        }

        // A bound slot's shift-click is a swap, so it's taken before the screen moves the item.
        if (SlotBinds.handleShiftClick((AbstractContainerScreen<?>) (Object) this, click.button())) {
            info.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void cryptic$releaseLeap(MouseButtonEvent click, CallbackInfoReturnable<Boolean> info) {
        if (SpiritLeapOverlay.handleMouseRelease((AbstractContainerScreen<?>) (Object) this, click.x(), click.y())) {
            info.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cryptic$keyTerminal(KeyEvent event, CallbackInfoReturnable<Boolean> info) {
        if (SpiritLeapOverlay.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event.key())) {
            info.setReturnValue(true);
            return;
        }

        if (TerminalSolver.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event)) {
            info.setReturnValue(true);
            return;
        }

        if (ItemProtection.handleKey((AbstractContainerScreen<?>) (Object) this, event.key())) {
            info.setReturnValue(true);
            return;
        }

        // Before the hotbar keys: in the loadout menu, 1 to 0 put on a loadout.
        if (LoadoutManager.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event.key())) {
            info.setReturnValue(true);
            return;
        }

        if (SlotBinds.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event)) {
            info.setReturnValue(true);
        }
    }
}
