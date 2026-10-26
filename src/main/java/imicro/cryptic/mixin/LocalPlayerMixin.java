package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import imicro.cryptic.feature.AutoSprint;
import imicro.cryptic.feature.ItemProtection;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Auto Sprint, Odin's way: the sprint key reads as held where movement asks for it, so the game's
 * own rules decide.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @ModifyExpressionValue(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Input;sprint()Z")
    )
    private boolean cryptic$autoSprint(boolean held) {
        return held || AutoSprint.holdsSprint();
    }

    /** The drop key on a protected item does nothing. */
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void cryptic$protectDrop(boolean all, CallbackInfoReturnable<Boolean> info) {
        if (ItemProtection.blocksDrop(((LocalPlayer) (Object) this).getMainHandItem())) info.setReturnValue(false);
    }
}
