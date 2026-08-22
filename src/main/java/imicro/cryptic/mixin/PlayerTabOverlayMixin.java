package imicro.cryptic.mixin;

import imicro.cryptic.feature.CustomNametags;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shows the player their own chosen name in the tab list. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void cryptic$customTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        if (original == null) return;
        Component renamed = CustomNametags.tabName(info.getProfile().id(), original);
        if (renamed != null) cir.setReturnValue(renamed);
    }
}
