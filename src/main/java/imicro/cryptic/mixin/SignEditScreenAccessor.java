package imicro.cryptic.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A sign editor's sign (for the GUI log) and its lines (the Party Finder pages answer Hypixel's signs). */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("sign")
    SignBlockEntity cryptic$sign();

    @Accessor("messages")
    String[] cryptic$messages();
}
