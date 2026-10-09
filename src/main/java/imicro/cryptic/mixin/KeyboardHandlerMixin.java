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
 * Offers a key to a notification that is asking something, before the game
 * does anything else with it.
 *
 * A party invite is answered with Y or N, and it has to be answerable with no
 * screen open — which is exactly when nothing else hands a key press to a mod.
 * So it is taken here, where every key arrives, and only a key a notification
 * actually wanted is kept from the game.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    /** GLFW's code for a key going down, as opposed to repeating or coming up. */
    private static final int PRESS = 1;

    /** GLFW's code for a key coming up. */
    private static final int RELEASE = 0;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void cryptic$answerNotification(long handle, int action, KeyEvent event, CallbackInfo info) {
        if (handle != Minecraft.getInstance().getWindow().handle()) return;
        // The Keybinds Manager hears every key going down and up, and keeps
        // none of them from the game.
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
