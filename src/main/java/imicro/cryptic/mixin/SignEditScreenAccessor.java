package imicro.cryptic.mixin;

import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A sign editor's sign (for the GUI log), its lines (the Party Finder pages answer Hypixel's signs)
 * and its cursor (kept in bounds when Farming writes a mousemat's angles).
 */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("sign")
    SignBlockEntity cryptic$sign();

    @Accessor("messages")
    String[] cryptic$messages();

    @Accessor("signField")
    @Nullable TextFieldHelper cryptic$signField();
}
