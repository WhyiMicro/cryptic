package imicro.cryptic.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The camera's eased eye height and captured frustum, which Spirit Leap's teammate previews set
 * for each teammate's view and put back afterwards.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("eyeHeight")
    float cryptic$eyeHeight();

    @Accessor("eyeHeight")
    void cryptic$setEyeHeight(float value);

    @Accessor("eyeHeightOld")
    float cryptic$eyeHeightOld();

    @Accessor("eyeHeightOld")
    void cryptic$setEyeHeightOld(float value);

    @Accessor("capturedFrustum")
    @Nullable Frustum cryptic$capturedFrustum();

    @Accessor("capturedFrustum")
    void cryptic$setCapturedFrustum(@Nullable Frustum frustum);

    @Invoker("setRotation")
    void cryptic$setRotation(float yRot, float xRot);
}
