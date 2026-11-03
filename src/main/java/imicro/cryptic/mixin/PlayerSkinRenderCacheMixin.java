package imicro.cryptic.mixin;

import imicro.cryptic.feature.RenderOptimizer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Render Optimizer's head skin fix. Heads in menus, worn heads and placed heads all ask here for
 * their skin, and get the default one while it loads.
 */
@Mixin(PlayerSkinRenderCache.class)
public abstract class PlayerSkinRenderCacheMixin {
    @Shadow
    public abstract CompletableFuture<Optional<PlayerSkinRenderCache.RenderInfo>> lookup(ResolvableProfile profile);

    @Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
    private void cryptic$keepLoadedSkin(ResolvableProfile profile, CallbackInfoReturnable<PlayerSkinRenderCache.RenderInfo> info) {
        if (!RenderOptimizer.fixesHeadSkins()) return;
        PlayerSkinRenderCache.RenderInfo standIn = RenderOptimizer.headSkin(profile, this.lookup(profile).getNow(null));
        if (standIn != null) info.setReturnValue(standIn);
    }
}
