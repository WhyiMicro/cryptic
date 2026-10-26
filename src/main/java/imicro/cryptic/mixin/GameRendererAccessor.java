package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * What Spirit Leap's teammate previews need to render the world a second time into their own
 * image: the target the level draws into, and the three halves of extract that read the window,
 * options and camera.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Mutable
    @Accessor("mainRenderTarget")
    void cryptic$setMainRenderTarget(RenderTarget target);

    /** Holds the camera position terrain is drawn relative to, set once a frame. */
    @Accessor("globalSettingsUniform")
    GlobalSettingsUniform cryptic$globalSettings();

    @Invoker("extractWindow")
    void cryptic$extractWindow();

    @Invoker("extractOptions")
    void cryptic$extractOptions();

    @Invoker("extractCamera")
    void cryptic$extractCamera(DeltaTracker deltaTracker, float worldPartialTicks, float cameraEntityPartialTicks);
}
