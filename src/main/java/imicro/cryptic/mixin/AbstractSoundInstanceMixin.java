package imicro.cryptic.mixin;

import imicro.cryptic.feature.SoundVolumes;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales a sound by the Sound Manager's volume for it, each time the volume is read, so sounds that
 * change volume as they play are scaled throughout.
 */
@Mixin(AbstractSoundInstance.class)
public abstract class AbstractSoundInstanceMixin {
    @Inject(method = "getVolume", at = @At("RETURN"), cancellable = true)
    private void cryptic$scaleVolume(CallbackInfoReturnable<Float> cir) {
        float factor = SoundVolumes.factor((SoundInstance) (Object) this);
        if (factor != 1f) cir.setReturnValue(cir.getReturnValueF() * factor);
    }
}
