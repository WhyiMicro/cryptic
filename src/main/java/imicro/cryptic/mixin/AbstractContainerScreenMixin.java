package imicro.cryptic.mixin;

import imicro.cryptic.feature.SlotBinds;
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
 * Lets the terminal solver paint over a chest, and take its input.
 *
 * Two of the solver's three render types leave the chest where it is and work
 * on its own slots — which is what these hooks are for. The third replaces the
 * screen outright, and that is {@link ScreenMixin}'s doing.
 *
 * The click hook is not a render type's business at all: every click in a
 * terminal goes through the solver whichever way it is drawn, because the
 * solver is what holds the first-click protection.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    /**
     * Before any slot is drawn, which is after the chest's background texture
     * — so the Odin render type's fill lands over the picture of an inventory
     * rather than under it, and under the answer that is drawn next.
     */
    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void cryptic$fillTerminalBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo info) {
        TerminalSolver.drawChestBackground((AbstractContainerScreen<?>) (Object) this, graphics);
    }

    /** One slot: painted by the solver, and then not drawn by the game. */
    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void cryptic$drawTerminalSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo info) {
        if (TerminalSolver.drawChestSlot(graphics, slot)) info.cancel();
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
        if (TerminalSolver.handleSlotClick(slotId, button, input)) info.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cryptic$clickTerminal(MouseButtonEvent click, boolean doubleClick, CallbackInfoReturnable<Boolean> info) {
        if (TerminalSolver.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click.x(), click.y(), click.button())) {
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

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cryptic$keyTerminal(KeyEvent event, CallbackInfoReturnable<Boolean> info) {
        if (TerminalSolver.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event)) {
            info.setReturnValue(true);
            return;
        }

        if (SlotBinds.handleKeyPress((AbstractContainerScreen<?>) (Object) this, event)) {
            info.setReturnValue(true);
        }
    }
}
