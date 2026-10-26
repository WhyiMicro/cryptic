package imicro.cryptic.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a chest's slots start on screen; both fields are protected. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int cryptic$leftPos();

    @Accessor("topPos")
    int cryptic$topPos();

    @Accessor("imageWidth")
    int cryptic$imageWidth();

    @Accessor("imageHeight")
    int cryptic$imageHeight();

    /** The slot under the cursor, which the screen keeps to itself. */
    @Accessor("hoveredSlot")
    Slot cryptic$hoveredSlot();
}
