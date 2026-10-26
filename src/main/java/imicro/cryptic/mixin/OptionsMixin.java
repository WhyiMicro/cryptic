package imicro.cryptic.mixin;

import imicro.cryptic.feature.CameraTweaks;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the front-facing view from the F5 cycle, for Camera. */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow
    public abstract void setCameraType(CameraType cameraType);

    @Inject(method = "setCameraType", at = @At("HEAD"), cancellable = true)
    private void cryptic$skipFrontCamera(CameraType cameraType, CallbackInfo info) {
        if (!CameraTweaks.skipsFrontCamera() || cameraType != CameraType.THIRD_PERSON_FRONT) return;
        // Skips to the next view, or the key would seem dead.
        setCameraType(CameraType.FIRST_PERSON);
        info.cancel();
    }
}
