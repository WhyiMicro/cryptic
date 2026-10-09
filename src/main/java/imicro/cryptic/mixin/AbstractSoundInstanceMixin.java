package imicro.cryptic.mixin;

import imicro.cryptic.feature.SoundVolumes;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales a sound by the volume the Sound Manager has for it.
 *
 * At the volume a sound reports rather than where it is played, so a sound
 * that changes its volume as it goes - a minecart, a beacon - is scaled every
 * time it is asked, not once at the start.
 */
@Mixin(AbstractSoundInstance.class)
public abstract class AbstractSoundInstanceMixin {
    @Inject(method = "getVolume", at = @At("RETURN"), cancellable = true)
    private void cryptic$scaleVolume(CallbackInfoReturnable<Float> cir) {
        float factor = SoundVolumes.factor((SoundInstance) (Object) this);
        if (factor != 1f) cir.setReturnValue(cir.getReturnValueF() * factor);
    }
}
