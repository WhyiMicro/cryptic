package imicro.cryptic.mixin;

import imicro.cryptic.feature.BreakerHelper;
import imicro.cryptic.feature.DungeonWaypoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import imicro.cryptic.feature.PuzzleSolver;
import imicro.cryptic.feature.Secrets;
import imicro.cryptic.feature.TerminalEsp;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells the modules that watch for it which block the player just reached for. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void cryptic$noteSecretClick(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<?> info) {
        Secrets.onBlockUsed(hit.getBlockPos());
        DungeonWaypoints.onBlockUsed(hit.getBlockPos());
        PuzzleSolver.onBlockUsed(hit.getBlockPos());
    }

    /** A terminal's stand being clicked, which Terminal ESP flashes. */
    @Inject(method = "interact", at = @At("HEAD"))
    private void cryptic$noteEntityUse(Player player, Entity entity, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<?> info) {
        TerminalEsp.onClicked(entity);
    }

    /** About to break a block: Breaker Helper makes sure the server knows what is held. */
    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void cryptic$breakerSyncSlot(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> info) {
        BreakerHelper.onStartDestroyBlock();
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void cryptic$noteEntityAttack(Player player, Entity entity, CallbackInfo info) {
        TerminalEsp.onClicked(entity);
    }
}
