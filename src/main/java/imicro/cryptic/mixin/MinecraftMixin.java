package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import imicro.cryptic.feature.AutoClicker;
import imicro.cryptic.feature.DeviceSolver;
import imicro.cryptic.feature.TerminalSolver;
import imicro.cryptic.gui.ImGuiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two things that belong to the frame rather than the tick — drawing ImGui, and
 * running the Auto Clicker's press and release — and the one place a right
 * click can be stopped before it leaves.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    @Nullable
    public HitResult hitResult;

    /**
     * Lets a device solver swallow a right click on a block.
     *
     * The injection is on the call rather than on the method, which is what
     * makes it precise: by this point the game has decided this is a use on a
     * block and which block it is, and cancelling here means the packet is
     * never built. The same point Odin (BSD 3-Clause) uses.
     */
    @Inject(
        method = "startUseItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"
        ),
        cancellable = true
    )
    private void cryptic$blockDeviceBlockUse(CallbackInfo info) {
        if (this.hitResult instanceof BlockHitResult hit && DeviceSolver.blocksBlockUse(hit.getBlockPos())) {
            info.cancel();
        }
    }

    /** The same for an item frame, which is what the arrow device is made of. */
    @Inject(
        method = "startUseItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"
        ),
        cancellable = true
    )
    private void cryptic$blockDeviceEntityUse(CallbackInfo info) {
        if (this.hitResult instanceof EntityHitResult hit && DeviceSolver.blocksEntityUse(hit.getEntity())) {
            info.cancel();
        }
    }

    /**
     * Scales the GUI up while a terminal is open, for the two render types
     * that draw on the chest itself.
     *
     * The scale the player chose is changed on its way into the calculation
     * rather than in the option it came from, so nothing is written to their
     * settings and the moment the terminal closes the game's own number is
     * back. Modifying an argument also claims nothing, so a mod that wraps the
     * same call — Odin does — keeps working alongside this.
     */
    @ModifyArg(
        method = "resizeGui",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;calculateScale(IZ)I"),
        index = 0
    )
    private int cryptic$terminalGuiScale(int requested) {
        Integer wanted = TerminalSolver.guiScaleOverride();
        return wanted != null ? wanted : requested;
    }

    /**
     * Runs the Auto Clicker before the frame, which is the only place it can
     * run: a press and its release are both inside a single tick at any rate
     * worth using, so a tick hook could not fit them both.
     */
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void cryptic$autoClick(boolean renderLevel, CallbackInfo ci) {
        AutoClicker.frame();
    }

    /**
     * Draws Cryptic's Dear ImGui windows over the finished frame.
     *
     * 26.2 replaced the old {@code blitToScreen} with a swapchain: the frame is
     * copied into the surface by {@code blitFromTexture} and shown by
     * {@code present}. On OpenGL those are a blit into the default framebuffer
     * and {@code glfwSwapBuffers}, so the moment just before {@code present} is
     * the same moment the old hook ran at — the whole frame is in the window's
     * framebuffer and has not been shown yet.
     *
     * On Vulkan there is no OpenGL context for Dear ImGui's renderer to draw
     * with, and {@link ImGuiRuntime} stands down rather than make a call that
     * would take the process with it.
     */
    @Inject(
        method = "renderFrame",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V")
    )
    private void cryptic$renderImGui(boolean renderLevel, CallbackInfo ci) {
        ImGuiRuntime.INSTANCE.renderIfOpen();
    }
}
