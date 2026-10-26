package imicro.cryptic.mixin;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Better Chat reads the chat's lines to merge repeats and to find the line under the mouse. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("allMessages")
    List<GuiMessage> cryptic$allMessages();

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> cryptic$trimmedMessages();

    @Accessor("chatScrollbarPos")
    int cryptic$scroll();

    @Invoker("getScale")
    double cryptic$scale();

    @Invoker("getWidth")
    int cryptic$width();

    @Invoker("refreshTrimmedMessages")
    void cryptic$refresh();
}
