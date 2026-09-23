package imicro.cryptic.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where a chest's slots start on screen.
 *
 * A slot knows its position inside the window and nothing about where the
 * window is, so painting over one means knowing both. Both halves are protected
 * with no getter.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int cryptic$leftPos();

    @Accessor("topPos")
    int cryptic$topPos();

    /** How big the window's own texture is, which is where its slots end. */
    @Accessor("imageWidth")
    int cryptic$imageWidth();

    @Accessor("imageHeight")
    int cryptic$imageHeight();

    /**
     * The slot the cursor is over, which the screen keeps to itself.
     *
     * Anything acting on "the slot you are pointing at" has to ask the screen,
     * because working it back out of the mouse position means redoing the
     * layout the screen has already done.
     */
    @Accessor("hoveredSlot")
    Slot cryptic$hoveredSlot();
}
