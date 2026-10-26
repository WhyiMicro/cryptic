package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sky renderer keeps the target it was made with instead of asking for the current one, so
 * Spirit Leap's teammate views point it at their own image while they render.
 */
public final class SkyRendererAccessors {
    private SkyRendererAccessors() {}

    @Mixin(LevelRenderer.class)
    public interface Level {
        @Accessor("skyRenderer")
        @Nullable SkyRenderer cryptic$skyRenderer();
    }

    @Mixin(SkyRenderer.class)
    public interface Sky {
        @Accessor("renderTarget")
        RenderTarget cryptic$renderTarget();

        @Mutable
        @Accessor("renderTarget")
        void cryptic$setRenderTarget(RenderTarget target);
    }
}
