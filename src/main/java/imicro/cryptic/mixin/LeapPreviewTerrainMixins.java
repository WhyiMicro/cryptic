package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import imicro.cryptic.feature.LeapPreview;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.debug.ChunkCullingDebugRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the terrain bookkeeping on your own camera while a Spirit Leap preview renders from a
 * teammate's eyes. Moving the section grid, the view area and the translucency sort to their eyes
 * and back every frame would redo that work twice a frame. All optional: Sodium replaces vanilla's
 * terrain path, and a missing target must not stop the game from starting.
 */
public final class LeapPreviewTerrainMixins {
    private LeapPreviewTerrainMixins() {}

    @Mixin(LevelExtractor.class)
    public abstract static class Extractor {
        @WrapWithCondition(
            method = "extract",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/SectionUpdateTracker;repositionCamera(Lnet/minecraft/core/SectionPos;)V"),
            require = 0
        )
        private boolean cryptic$keepSectionTracker(SectionUpdateTracker tracker, SectionPos pos) {
            return !LeapPreview.capturing;
        }

        /** Entities are most of a view's cost and not needed to see where someone stands. */
        @Inject(method = "extractVisibleEntities", at = @At("HEAD"), cancellable = true, require = 0)
        private void cryptic$skipEntities(CallbackInfo info) {
            if (LeapPreview.capturing && !LeapPreview.showsEntities()) info.cancel();
        }

        @WrapWithCondition(
            method = "extract",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V"),
            require = 0
        )
        private boolean cryptic$skipParticles(net.minecraft.client.particle.ParticleEngine engine, net.minecraft.client.renderer.state.level.ParticlesRenderState state,
                                              net.minecraft.client.renderer.culling.Frustum frustum, net.minecraft.client.Camera camera, float partialTick) {
            return !LeapPreview.capturing || LeapPreview.showsEntities();
        }
    }

    /**
     * Vanilla draws a captured frustum as a see-through box, a debug aid. A view on the vanilla
     * renderer borrows that capture to keep your terrain, which put every teammate inside the box.
     */
    @Mixin(ChunkCullingDebugRenderer.class)
    public abstract static class CullingDebug {
        @Inject(method = "emitGizmos", at = @At("HEAD"), cancellable = true, require = 0)
        private void cryptic$hideCapturedFrustum(CallbackInfo info) {
            if (LeapPreview.capturing) info.cancel();
        }
    }

    @Mixin(LevelRenderer.class)
    public abstract static class Renderer {
        @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true, require = 0)
        private void cryptic$keepViewArea(CameraRenderState camera, CallbackInfo info) {
            if (LeapPreview.capturing) info.cancel();
        }

        @WrapWithCondition(
            method = "compileSections",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V"),
            require = 0
        )
        private boolean cryptic$keepTranslucentSort(LevelRenderer self, Vec3 cameraPos) {
            return !LeapPreview.capturing;
        }
    }
}
