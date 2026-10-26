package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import imicro.cryptic.feature.ChatExtras;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Predicate;

/**
 * Better Chat's chat window: lines hidden by a filter stay in history (so they come back when the
 * filter is turned off), a longer history, and repeated lines merged into one.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    /** Peeking shows as many lines as an open chat. */
    @ModifyExpressionValue(method = "getHeight()I", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;isChatFocused()Z"))
    private boolean cryptic$peekHeight(boolean focused) {
        return focused || imicro.cryptic.feature.BetterChat.peeking();
    }

    /** A repeat of a recent line replaces it, with a count. */
    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component cryptic$compact(Component contents) {
        return ChatExtras.compact((ChatComponent) (Object) this, contents);
    }

    /** Kept in history either way; only the window skips a hidden line. */
    @WrapWithCondition(
        method = "addMessage",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V")
    )
    private boolean cryptic$showNewLine(ChatComponent self, GuiMessage message) {
        if (ChatExtras.hides(message)) return false;
        ChatExtras.onLineShown(message);
        return true;
    }

    /** New lines rise into place: the chat starts a little low and catches up. */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
        at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;pushMatrix()Lorg/joml/Matrix3x2fStack;", shift = At.Shift.AFTER)
    )
    private void cryptic$slideIn(GuiGraphicsExtractor graphics, Font font, int ticks, int mouseX, int mouseY, ChatComponent.DisplayMode mode, boolean changeCursor, CallbackInfo info) {
        float slide = ChatExtras.chatSlide();
        if (slide != 0f) graphics.pose().translate(0f, slide);
    }

    @ModifyExpressionValue(
        method = "refreshTrimmedMessages",
        at = @At(value = "INVOKE", target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z")
    )
    private boolean cryptic$showOldLine(boolean shown, @com.llamalad7.mixinextras.sugar.Local GuiMessage message) {
        return shown && !ChatExtras.hides(message);
    }

    @ModifyConstant(method = {"addMessageToDisplayQueue", "addMessageToQueue"}, constant = @Constant(intValue = 100))
    private int cryptic$historyLength(int vanilla) {
        return ChatExtras.historyLength();
    }
}
