package imicro.cryptic.mixin;

import imicro.cryptic.feature.KeybindManager;
import imicro.cryptic.feature.Toasts;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Offers each key to a notification that's asking something (Y/N for invites) before the game sees
 * it, with no screen open. Only keys a notification wanted are kept from the game.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    private static final int PRESS = 1;

    private static final int RELEASE = 0;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void cryptic$answerNotification(long handle, int action, KeyEvent event, CallbackInfo info) {
        if (handle != Minecraft.getInstance().getWindow().handle()) return;
        // The Keybinds Manager hears every key down and up, and keeps none from the game.
        if (action == RELEASE) {
            KeybindManager.onKey(event.key(), false);
            return;
        }
        if (action != PRESS) return;
        if (Toasts.onKeyPressed(event.key())) {
            info.cancel();
            return;
        }
        KeybindManager.onKey(event.key(), true);
    }
}
