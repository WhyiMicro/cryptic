package imicro.cryptic.mixin;

import imicro.cryptic.feature.ArrowHitSound;
import imicro.cryptic.feature.NucleusQol;
import imicro.cryptic.feature.SoundVolumes;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void cryptic$replaceArrowHit(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (ArrowHitSound.intercept(instance)) {
            imicro.cryptic.debug.SoundLog.muted(instance, "Arrow Hit Sound");
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
            return;
        }
        if (NucleusQol.mutes(instance)) {
            imicro.cryptic.debug.SoundLog.muted(instance, "Nucleus Features");
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
            return;
        }
        if (imicro.cryptic.feature.ChocolateFactory.mutes(instance)) {
            imicro.cryptic.debug.SoundLog.muted(instance, "Chocolate Factory");
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
            return;
        }
        // For the Sound Manager's Recent list.
        SoundVolumes.onPlay(instance);
    }

    /** For `/cryptic debug sounds`: whether a server sound really started. */
    @Inject(method = "play", at = @At("RETURN"))
    private void cryptic$notePlayed(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        imicro.cryptic.debug.SoundLog.played(instance, cir.getReturnValue());
    }
}
