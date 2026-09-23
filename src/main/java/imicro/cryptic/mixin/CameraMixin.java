package imicro.cryptic.mixin;

import imicro.cryptic.feature.CameraTweaks;
import imicro.cryptic.feature.EtherwarpZeroPing;
import imicro.cryptic.feature.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Narrows the field of view for the Zoom module.
 *
 * The two angles are worked out separately and used for different things:
 * {@code calculateFov} is the world, and {@code calculateHudFov} is the pass
 * that draws the held item over it. Zooming only the first is what leaves the
 * hand its usual size, so the hand is a separate hook rather than a scale
 * applied afterwards.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void cryptic$zoomView(float partialTicks, CallbackInfoReturnable<Float> cir) {
        // The custom angle is applied as a ratio on whatever the game arrived
        // at, so a zoom narrowing the same value still narrows it.
        float value = cir.getReturnValueF() * CameraTweaks.fovRatio();
        float zoom = Zoom.factor();
        if (zoom > 1.0F) value /= zoom;
        cir.setReturnValue(value);
    }

    /**
     * Where the third-person camera sits, for Camera.
     *
     * The distance the game has worked out is changed on its way into the wall
     * check, rather than at the attribute read it came from. Both would work
     * alone; only this one shares. NoammAddons redirects that attribute read
     * for the same feature, and a redirect is exclusive — the first mod to
     * claim the call leaves the second with nothing to attach to, which is a
     * hard crash for whichever loses. Modifying an argument claims nothing, so
     * both mods keep working whether or not the other is installed.
     */
    @ModifyArg(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"),
        index = 0
    )
    private float cryptic$cameraDistance(float requested) {
        Double custom = CameraTweaks.cameraDistance();
        return custom != null ? custom.floatValue() : requested;
    }

    /**
     * Stops walls pushing the camera forward, for Camera.
     *
     * This method's whole job is to shorten the distance until nothing is in
     * the way; handing back what it was asked for skips the search.
     */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void cryptic$ignoreWalls(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (CameraTweaks.ignoresWalls()) cir.setReturnValue(cameraDist);
    }

    /**
     * Where the camera stands, for Etherwarp's Fake zpew.
     *
     * This is the one place the camera is given the player's eyes, and it runs
     * before third person pulls it back out along the view — so a moved camera
     * keeps whichever perspective is in use. The arguments are changed rather
     * than the call taken over, so NoammAddons' own version of this feature
     * and Cryptic's can both be installed without either losing its hook.
     */
    @ModifyArgs(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V")
    )
    private void cryptic$fakeZpewCamera(Args args) {
        Vec3 destination = EtherwarpZeroPing.cameraPosition();
        if (destination == null) return;
        args.set(0, destination.x);
        args.set(1, destination.y);
        args.set(2, destination.z);
    }

    @Inject(method = "calculateHudFov", at = @At("RETURN"), cancellable = true)
    private void cryptic$zoomHand(float partialTicks, CallbackInfoReturnable<Float> cir) {
        float zoom = Zoom.handFactor();
        if (zoom > 1.0F) cir.setReturnValue(cir.getReturnValueF() / zoom);
    }
}
