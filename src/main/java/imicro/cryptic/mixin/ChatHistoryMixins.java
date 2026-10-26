package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import imicro.cryptic.feature.ChatExtras;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Better Chat's keep-history and skip-confirmation switches. */
public final class ChatHistoryMixins {
    private ChatHistoryMixins() {}

    /** Leaving a server keeps the chat, as a lobby swap otherwise loses it. */
    @Mixin(Hud.class)
    public abstract static class KeepHistory {
        @WrapWithCondition(method = "onDisconnected", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V"))
        private boolean cryptic$keepChat(ChatComponent chat, boolean history) {
            return !ChatExtras.keepsHistory();
        }
    }

    /**
     * A command from a clicked chat line is sent straight away, the way it is when the game finds
     * nothing to ask about, instead of opening "Are you sure?" first.
     */
    @Mixin(ClientPacketListener.class)
    public abstract static class NoConfirm {
        @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
        private void cryptic$skipConfirmation(String command, @Nullable Screen screenAfterCommand, CallbackInfo info) {
            if (!ChatExtras.skipsConfirmation()) return;
            ClientPacketListener self = (ClientPacketListener) (Object) this;
            self.send(new ServerboundChatCommandPacket(command));
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(screenAfterCommand);
            info.cancel();
        }
    }
}
