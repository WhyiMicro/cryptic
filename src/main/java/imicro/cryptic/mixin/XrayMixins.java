package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import imicro.cryptic.feature.NucleusXray;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nucleus Features' x-ray: while a key has it on, the chunk builders (vanilla's and Sodium's) leave
 * every block but the targets out, draw the targets' faces against hidden neighbours, and light
 * everything fully, since ore buried in stone has no light of its own.
 */
public final class XrayMixins {
    private XrayMixins() {}

    /** Vanilla's and Indigo's face culling both end here. */
    @Mixin(Block.class)
    public static class VanillaFaces {
        @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
        private static void cryptic$showFace(BlockState state, BlockState neighbour, Direction direction, CallbackInfoReturnable<Boolean> info) {
            if (NucleusXray.shows(state)) info.setReturnValue(true);
        }
    }

    /** Vanilla's chunk builder. Fabric's renderer API takes over its block drawing, so the block is dropped before that. */
    @Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCompiler")
    public static class VanillaSections {
        @WrapOperation(
            method = "compile",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;")
        )
        private RenderShape cryptic$hideBlock(BlockState state, Operation<RenderShape> original) {
            return NucleusXray.hides(state) ? RenderShape.INVISIBLE : original.call(state);
        }

        @WrapOperation(
            method = "compile",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V")
        )
        private void cryptic$hideFluid(
            FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output,
            BlockState state, FluidState fluid, Operation<Void> original
        ) {
            if (!NucleusXray.hides(state)) original.call(renderer, level, pos, output, state, fluid);
        }
    }

    /** Sections behind solid stone are skipped by cave culling, ore and all, so it is off meanwhile. */
    @Mixin(Camera.class)
    public static class CaveCulling {
        @Inject(method = "extractRenderState", at = @At("TAIL"))
        private void cryptic$noCaveCulling(CameraRenderState state, float partialTicks, CallbackInfo ci) {
            if (NucleusXray.hiding()) state.smartCull = false;
        }
    }

    @Mixin(LightCoordsUtil.class)
    public static class Light {
        @Inject(
            method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true
        )
        private static void cryptic$fullLight(
            LightCoordsUtil.BrightnessGetter getter, BlockAndLightGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> info
        ) {
            if (NucleusXray.hiding()) info.setReturnValue(LightCoordsUtil.FULL_BRIGHT);
        }
    }

    /** Ambient occlusion would shade a face against a hidden neighbour nearly black. */
    @Mixin(BlockBehaviour.BlockStateBase.class)
    public static class Shade {
        @Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
        private void cryptic$noShade(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> info) {
            if (NucleusXray.hiding()) info.setReturnValue(1.0F);
        }
    }

    @Pseudo
    @Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
    public static class SodiumBlocks {
        @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
        private void cryptic$hideBlock(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
            if (NucleusXray.hides(state)) ci.cancel();
        }
    }

    @Pseudo
    @Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
    public abstract static class SodiumFaces {
        @Shadow(remap = false)
        protected BlockState state;

        @Inject(method = "isFaceCulled", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
        private void cryptic$showFace(Direction face, CallbackInfoReturnable<Boolean> info) {
            if (face != null && NucleusXray.shows(this.state)) info.setReturnValue(false);
        }
    }

    @Pseudo
    @Mixin(targets = "net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl", remap = false)
    public static class SodiumFluids {
        @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
        private void cryptic$hideFluid(CallbackInfo ci, @Local(argsOnly = true) BlockState state) {
            if (NucleusXray.hides(state)) ci.cancel();
        }
    }

    /** Sodium's light cache leaves full opaque blocks (the hidden stone) unlit; its low byte is block and sky light. */
    @Pseudo
    @Mixin(targets = "net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess", remap = false)
    public static class SodiumLight {
        @ModifyReturnValue(method = "compute", at = @At("RETURN"), remap = false, require = 0)
        private int cryptic$fullLight(int word) {
            return NucleusXray.hiding() ? word | 0xFF : word;
        }
    }
}
