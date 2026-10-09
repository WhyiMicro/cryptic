package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import imicro.cryptic.feature.AutoSprint;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Auto Sprint, the way Odin does it (BSD 3-Clause, Copyright (c) 2025
 * odtheking): the sprint key reads as held where the player's movement asks
 * for it, so the game's own rules decide whether a sprint can start.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @ModifyExpressionValue(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Input;sprint()Z")
    )
    private boolean cryptic$autoSprint(boolean held) {
        return held || AutoSprint.holdsSprint();
    }
}
