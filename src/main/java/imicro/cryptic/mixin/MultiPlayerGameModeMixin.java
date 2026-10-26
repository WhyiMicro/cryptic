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

/** Tells modules which block the player just reached for. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @org.spongepowered.asm.mixin.Shadow
    private net.minecraft.world.item.ItemStack destroyingItem;

    /**
     * Mining's break reset fix: Hypixel rewrites a drill's lore as it's used, which vanilla takes for a
     * different tool and restarts the break. The same SkyBlock item carries on instead.
     */
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
        method = "sameDestroyTarget",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z")
    )
    private boolean cryptic$keepBreaking(boolean same) {
        if (same) return true;
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null || !imicro.cryptic.feature.Mining.sameTool(player.getMainHandItem(), this.destroyingItem)) return false;
        this.destroyingItem = player.getMainHandItem();
        return true;
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void cryptic$noteSecretClick(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<?> info) {
        if (hand == InteractionHand.MAIN_HAND) imicro.cryptic.feature.SlayerFeatures.onItemUsed(player.getMainHandItem());
        Secrets.onBlockUsed(hit.getBlockPos());
        DungeonWaypoints.onBlockUsed(hit.getBlockPos());
        PuzzleSolver.onBlockUsed(hit.getBlockPos());
    }

    /** A right click in the air, for item abilities. */
    @Inject(method = "useItem", at = @At("HEAD"))
    private void cryptic$noteItemUse(Player player, InteractionHand hand, CallbackInfoReturnable<?> info) {
        if (hand == InteractionHand.MAIN_HAND) imicro.cryptic.feature.SlayerFeatures.onItemUsed(player.getMainHandItem());
    }

    /** A terminal's stand being clicked, which Terminal ESP flashes. */
    @Inject(method = "interact", at = @At("HEAD"))
    private void cryptic$noteEntityUse(Player player, Entity entity, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<?> info) {
        TerminalEsp.onClicked(entity);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void cryptic$breakerStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> info) {
        imicro.cryptic.feature.Mining.onStartBreak(pos);
        if (!BreakerHelper.allowsBreak(pos, direction, true)) info.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void cryptic$breakerContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> info) {
        if (!BreakerHelper.allowsBreak(pos, direction, false)) info.setReturnValue(false);
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void cryptic$noteEntityAttack(Player player, Entity entity, CallbackInfo info) {
        TerminalEsp.onClicked(entity);
    }
}
