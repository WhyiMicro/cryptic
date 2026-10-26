package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import imicro.cryptic.feature.CameraTweaks;
import imicro.cryptic.feature.EtherwarpZeroPing;
import imicro.cryptic.feature.LeapPreview;
import imicro.cryptic.feature.SmoothTeleport;
import imicro.cryptic.feature.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Field of view for the Zoom module. {@code calculateFov} is the world and {@code calculateHudFov}
 * the held item, so the hand keeps its size.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    /** Freecam's camera, flown apart from the player, who is then drawn like in third person. */
    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void cryptic$freecam(float partialTicks, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (LeapPreview.capturing || !imicro.cryptic.feature.Freecam.getActive()) return;
        this.setRotation(imicro.cryptic.feature.Freecam.yaw(), imicro.cryptic.feature.Freecam.pitch());
        this.setPosition(
            imicro.cryptic.feature.Freecam.x(partialTicks),
            imicro.cryptic.feature.Freecam.y(partialTicks),
            imicro.cryptic.feature.Freecam.z(partialTicks)
        );
        this.detached = true;
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void cryptic$zoomView(float partialTicks, CallbackInfoReturnable<Float> cir) {
        // A teammate's preview is their plain view.
        if (LeapPreview.capturing) return;
        // Applied as a ratio on what the game arrived at.
        float value = cir.getReturnValueF() * CameraTweaks.fovRatio();
        float zoom = Zoom.factor();
        if (zoom > 1.0F) value /= zoom;
        cir.setReturnValue(value);
    }

    /**
     * Third-person camera distance, for Camera. Changed as an argument rather than redirecting the
     * attribute read, because NoammAddons redirects that read and two redirects on one call crash.
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
     * Freelook's angles in place of the player's, for the camera's first two rotations (riding a
     * minecart, and everything else). The mirrored front view then turns from them as usual.
     */
    @ModifyArgs(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0)
    )
    private void cryptic$freelookRiding(Args args) {
        cryptic$freelookArgs(args);
    }

    @ModifyArgs(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1)
    )
    private void cryptic$freelookView(Args args) {
        cryptic$freelookArgs(args);
    }

    private static void cryptic$freelookArgs(Args args) {
        if (!imicro.cryptic.feature.Freelook.getActive()) return;
        float yaw = args.get(0);
        float pitch = args.get(1);
        args.set(0, imicro.cryptic.feature.Freelook.yaw(yaw));
        args.set(1, imicro.cryptic.feature.Freelook.pitch(pitch));
    }

    /** Stops walls pushing the camera forward, for Camera. */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void cryptic$ignoreWalls(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (CameraTweaks.ignoresWalls()) cir.setReturnValue(cameraDist);
    }

    /**
     * Where the camera stands, for Etherwarp's Fake zpew. Runs before third person pulls the camera
     * back, so it works in any perspective. Changing arguments rather than taking the call lets
     * NoammAddons' version coexist.
     */
    @ModifyArgs(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V")
    )
    private void cryptic$fakeZpewCamera(Args args) {
        if (LeapPreview.capturing) return;
        // The camera's own eased eye height, so crouching slides instead of staying at standing
        // height.
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float eye = Mth.lerp(partialTick, this.eyeHeightOld, this.eyeHeight);

        Vec3 destination = EtherwarpZeroPing.cameraPosition(eye);
        if (destination == null) return;
        args.set(0, destination.x);
        args.set(1, destination.y);
        args.set(2, destination.z);
    }

    /**
     * Where the camera is, for Etherwarp's Smooth teleport. Changing the return value covers third
     * person and everything drawn relative to the camera, and lets other mods hook it too.
     */
    @ModifyReturnValue(method = "position", at = @At("RETURN"))
    private Vec3 cryptic$smoothTeleport(Vec3 original) {
        if (LeapPreview.capturing) return original;
        Vec3 glided = SmoothTeleport.cameraPosition(original);
        return glided != null ? glided : original;
    }

    @Inject(method = "calculateHudFov", at = @At("RETURN"), cancellable = true)
    private void cryptic$zoomHand(float partialTicks, CallbackInfoReturnable<Float> cir) {
        float zoom = Zoom.handFactor();
        if (zoom > 1.0F) cir.setReturnValue(cir.getReturnValueF() / zoom);
    }
}
