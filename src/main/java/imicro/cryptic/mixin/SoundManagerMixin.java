package imicro.cryptic.mixin;

import imicro.cryptic.feature.ArrowHitSound;
import imicro.cryptic.feature.SoundVolumes;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swaps the arrow hit sound for the one Arrow Hit Sound was given. */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void cryptic$replaceArrowHit(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (ArrowHitSound.intercept(instance)) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
            return;
        }
        // For the Sound Manager's Recent list, which is how a sound just heard is found.
        SoundVolumes.onPlay(instance);
    }
}
