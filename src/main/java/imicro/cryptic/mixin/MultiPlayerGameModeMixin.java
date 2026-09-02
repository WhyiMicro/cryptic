package imicro.cryptic.mixin;

import imicro.cryptic.feature.Secrets;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells the Secrets module which block the player just reached for. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void cryptic$noteSecretClick(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<?> info) {
        Secrets.onBlockUsed(hit.getBlockPos());
    }
}
