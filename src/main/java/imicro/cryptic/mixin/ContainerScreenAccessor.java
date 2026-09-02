package imicro.cryptic.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
}
