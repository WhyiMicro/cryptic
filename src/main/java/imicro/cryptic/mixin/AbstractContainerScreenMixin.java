package imicro.cryptic.mixin;

import imicro.cryptic.feature.CroesusHelper;
import imicro.cryptic.feature.InvincibilityTimer;
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
 * Hands a chest's input to whichever module has taken the chest over.
 *
 * The terminal solver draws its own screen in place of the chest, which is
 * {@link ScreenMixin}'s doing; what is here is the other half. Every click in a
 * terminal goes through the solver, because the solver is what holds the
 * first-click protection.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    /**
     * One slot, for the mask timer, which wants the item drawn and something
     * over it. A terminal's slots are never drawn at all: the solver replaces
     * the whole screen, which is {@link ScreenMixin}'s doing.
     */
    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void cryptic$drawSlotCooldown(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo info) {
        CroesusHelper.drawSlotHighlight(graphics, slot);
        if (InvincibilityTimer.drawSlotCooldown(graphics, slot)) info.cancel();
    }

    /** A tooltip is the chest talking about items the solver has hidden. */
    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideTerminalTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo info) {
        if (TerminalSolver.hidesTooltip()) info.cancel();
    }

    /**
     * Every click the chest would make, including the ones vanilla builds out
     * of the drop key and the hotbar numbers.
     */
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickTerminalSlot(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo info) {
        CroesusHelper.onSlotClicked(slotId);
        LoadoutManager.onSlotClicked(slotId, button, input);
        if (TerminalSolver.handleSlotClick(slotId, button, input)) info.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickTerminal(MouseButtonEvent click, boolean doubleClick, CallbackInfoReturnable<Boolean> info) {
        if (SpiritLeapOverlay.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.x(), click.y())) {
            info.setReturnValue(true);
            return;
        }

        if (TerminalSolver.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.x(), click.y(), click.button())) {
            info.setReturnValue(true);
            return;
        }

        // The loadout menu's page buttons sit on the mouse's side buttons.
        if (LoadoutManager.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.button())) {
            info.setReturnValue(true);
            return;
        }

        // A bound slot's shift-click means a swap rather than the move across
        // the inventory vanilla would make of it, so it has to be taken here
        // before the screen acts on it.
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

        // Before the hotbar keys get it: in the loadout menu, 1 to 0 put on a
        // loadout rather than swap the hovered item.
        if (LoadoutManager.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event.key())) {
            info.setReturnValue(true);
            return;
        }

        if (SlotBinds.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event)) {
            info.setReturnValue(true);
        }
    }
}
