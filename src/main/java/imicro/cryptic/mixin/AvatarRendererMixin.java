package imicro.cryptic.mixin;

import imicro.cryptic.feature.CustomNametags;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the player see their own name tag.
 *
 * Adapted from NoammAddons (CC0). The game deliberately never names the player
 * to themselves, and forcing that decision here rather than writing a tag by
 * hand is what makes it the real one: what gets drawn is the display name the
 * server dressed the player in, so Hypixel's rank, colours and SkyBlock level
 * all come along without Cryptic having to reassemble any of it.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("HEAD"), cancellable = true)
    private void cryptic$showOwnNameTag(Avatar entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (CustomNametags.showsOwnNameTag(entity)) cir.setReturnValue(true);
    }
}
