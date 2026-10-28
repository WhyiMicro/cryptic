package imicro.cryptic.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The chat's input, so the current chat's name can sit in front of what you type. */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
    @Accessor("input")
    EditBox cryptic$input();
}
