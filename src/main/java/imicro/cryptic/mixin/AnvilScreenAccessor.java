package imicro.cryptic.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The anvil's name box, which the Party Finder pages type a group note into. */
@Mixin(AnvilScreen.class)
public interface AnvilScreenAccessor {
    @Accessor("name")
    EditBox cryptic$name();
}
