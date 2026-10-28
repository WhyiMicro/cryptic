package imicro.cryptic.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import imicro.cryptic.feature.AutoClicker;
import imicro.cryptic.feature.DeviceSolver;
import imicro.cryptic.feature.BossWaypoints;
import imicro.cryptic.feature.DungeonWaypoints;
import imicro.cryptic.feature.PuzzleSolver;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Auto Clicker's press and release (per frame), and where a right click can be stopped before
 * it's sent.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    @Nullable
    public HitResult hitResult;

    @Shadow
    private int rightClickDelay;

    /**
     * A right click on nothing, for floating waypoints in the editors. Sets the click delay too, or
     * holding it would place and remove a waypoint every tick.
     */
    /** A right click on a Three Weirdos box talks to that weirdo instead. */
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void cryptic$instantWeirdos(CallbackInfo info) {
        if (PuzzleSolver.onUseItem()) {
            this.rightClickDelay = 4;
            info.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void cryptic$floatingWaypoint(CallbackInfo info) {
        if (this.hitResult != null && this.hitResult.getType() != HitResult.Type.MISS) return;
        if (DungeonWaypoints.onEditorAirClick() || BossWaypoints.onEditorAirClick()) {
            this.rightClickDelay = 4;
            info.cancel();
        }
    }

    /**
     * Lets a device solver swallow a right click on a block. On the call, where the block is
     * decided and nothing has been sent. The same point Odin uses.
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
        if (!(this.hitResult instanceof BlockHitResult hit)) return;
        // Puzzles that one wrong click can fail are asked here too.
        if (DeviceSolver.blocksBlockUse(hit.getBlockPos()) || PuzzleSolver.blocksClick(hit.getBlockPos())
                || DungeonWaypoints.onEditorClick(hit.getBlockPos()) || BossWaypoints.onEditorClick(hit.getBlockPos())) {
            info.cancel();
        }
    }

    /** The same for an item frame, which the arrow device is made of. */
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
     * Runs the Auto Clicker before each frame: at useful rates, a press and release both fit inside
     * one tick.
     */
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void cryptic$autoClick(boolean renderLevel, CallbackInfo ci) {
        AutoClicker.frame();
    }

    /**
     * No pause menu for losing focus while the Experimentation Table runs itself: between games no
     * menu is open, and the pause menu would swallow the click that reopens the table.
     */
    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void cryptic$keepRunning(CallbackInfo ci) {
        if (imicro.cryptic.experiment.ExperimentRunner.keepsGameRunning()) ci.cancel();
    }

}
